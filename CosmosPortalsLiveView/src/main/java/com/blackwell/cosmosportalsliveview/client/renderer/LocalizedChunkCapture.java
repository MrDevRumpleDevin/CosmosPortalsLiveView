package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.mojang.blaze3d.platform.NativeImage;

/**
 * Renders a perspective "camera" view of the destination position using the
 * yaw/pitch that was captured when the player used the unlinked dimension container.
 *
 * <p>Instead of a top-down minimap, we cast rays from destPos in the direction
 * the player was facing when they captured the destination, producing a view that
 * looks like a real window/camera into the other side.</p>
 */
@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    /** Vertical FOV in degrees for the perspective raycaster. */
    private static final float FOV_DEGREES = 70.0f;

    /** Maximum ray march distance in blocks. */
    private static final int MAX_RAY_DIST = 48;

    public static void captureLocalizedPortalView(PortalViewData portalData, Level level) {
        if (portalData == null) return;

        BlockPos destPos = portalData.destPos;
        ResourceLocation destDimension = portalData.destDimension;

        if (destPos == null) return;

        Level sampleLevel = resolveSampleLevel(destDimension, level);
        if (sampleLevel == null) return;

        int resolution = PortalLiveViewConfig.CAPTURE_RESOLUTION.get();
        resolution = Math.min(resolution, 256);

        try {
            DynamicTexture texture = renderPerspectiveView(
                    sampleLevel, destPos,
                    portalData.destYaw, portalData.destPitch,
                    resolution);
            if (texture != null) {
                portalData.setTexture(texture);
            }
        } catch (Exception ignored) {
            // Never crash the render thread.
        }
    }

    /**
     * Renders a perspective view from {@code eyePos} looking in the direction
     * defined by {@code yawDeg} / {@code pitchDeg} (Minecraft convention:
     * yaw=0 → south, yaw=90 → west; pitch=0 → horizontal, pitch=-90 → up).
     */
    private static DynamicTexture renderPerspectiveView(Level level, BlockPos eyePos,
                                                         float yawDeg, float pitchDeg,
                                                         int resolution) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);

        // Eye position — slightly above the destination block so we don't look inside it.
        double eyeX = eyePos.getX() + 0.5;
        double eyeY = eyePos.getY() + 1.62; // player eye height
        double eyeZ = eyePos.getZ() + 0.5;

        // Convert yaw/pitch to a forward unit vector.
        // MC yaw: 0=south(+Z), 90=west(-X), 180=north(-Z), 270=east(+X)
        // MC pitch: 0=horizontal, negative=up, positive=down
        double yawRad   = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);

        // Forward vector (the direction the camera looks)
        double fwdX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fwdY = -Math.sin(pitchRad);
        double fwdZ =  Math.cos(yawRad) * Math.cos(pitchRad);

        // Right vector (perpendicular to forward, in the horizontal plane)
        double rightX = Math.cos(yawRad);
        double rightY = 0.0;
        double rightZ = Math.sin(yawRad);

        // Up vector = right × forward
        double upX = rightY * fwdZ - rightZ * fwdY;
        double upY = rightZ * fwdX - rightX * fwdZ;
        double upZ = rightX * fwdY - rightY * fwdX;

        // Half-plane size at unit distance from camera (from FOV)
        float halfFov = (float) Math.tan(Math.toRadians(FOV_DEGREES / 2.0));

        for (int py = 0; py < resolution; py++) {
            for (int px = 0; px < resolution; px++) {
                // NDC coords: [-1, 1] for both axes
                float ndcX = (2.0f * px / resolution) - 1.0f;
                float ndcY = 1.0f - (2.0f * py / resolution); // flip Y (top = positive)

                // Ray direction
                double rdX = fwdX + rightX * ndcX * halfFov + upX * ndcY * halfFov;
                double rdY = fwdY + rightY * ndcX * halfFov + upY * ndcY * halfFov;
                double rdZ = fwdZ + rightZ * ndcX * halfFov + upZ * ndcY * halfFov;

                // Normalise
                double len = Math.sqrt(rdX * rdX + rdY * rdY + rdZ * rdZ);
                if (len < 1e-6) { image.setPixelRGBA(px, py, toABGR(0xFF000000)); continue; }
                rdX /= len; rdY /= len; rdZ /= len;

                int color = marchRay(level, eyeX, eyeY, eyeZ, rdX, rdY, rdZ);
                image.setPixelRGBA(px, py, toABGR(color));
            }
        }

        DynamicTexture texture = new DynamicTexture(image);
        texture.upload();
        return texture;
    }

    /**
     * Simple DDA ray march. Steps through blocks until it hits a solid one.
     * Returns an 0xAARRGGBB colour for the hit surface, or sky colour if nothing hit.
     */
    private static int marchRay(Level level,
                                  double ox, double oy, double oz,
                                  double dx, double dy, double dz) {
        // DDA: step size of 0.4 blocks — good balance of accuracy vs speed
        final double step = 0.4;
        double x = ox, y = oy, z = oz;

        for (int i = 0; i < (int)(MAX_RAY_DIST / step); i++) {
            x += dx * step;
            y += dy * step;
            z += dz * step;

            // Clamp Y to world bounds
            if (y < level.getMinBuildHeight() || y > level.getMaxBuildHeight()) break;

            BlockPos bp = new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
            BlockState state = level.getBlockState(bp);

            if (!state.isAir() && state.isSolidRender(level, bp)) {
                // Add simple distance shading
                double dist = Math.sqrt((x - ox) * (x - ox) + (y - oy) * (y - oy) + (z - oz) * (z - oz));
                float shade = (float) Math.max(0.3, 1.0 - dist / (MAX_RAY_DIST * 0.8));
                return shadeColor(getBlockColor(state), shade);
            }
        }

        // Sky / no hit — gradient based on ray direction
        return getSkyColor(dy);
    }

    /** Sky gradient from light blue (up) to dark blue/black (horizon). */
    private static int getSkyColor(double rdY) {
        // rdY: +1 = straight up, 0 = horizon, -1 = straight down
        float t = (float) Math.max(0.0, rdY); // 0..1
        int r = (int)(10 + t * 100);
        int g = (int)(20 + t * 140);
        int b = (int)(60 + t * 175);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /** Apply a brightness shade multiplier to an 0xAARRGGBB colour. */
    private static int shadeColor(int argb, float shade) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.min(255, (int)(((argb >> 16) & 0xFF) * shade));
        int g = Math.min(255, (int)(((argb >>  8) & 0xFF) * shade));
        int b = Math.min(255, (int)(( argb        & 0xFF) * shade));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Convert 0xAARRGGBB → 0xAABBGGRR (the byte order NativeImage.setPixelRGBA expects
     * on little-endian systems, which is what MC's NativeImage uses).
     */
    private static int toABGR(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /** Block type → 0xAARRGGBB colour. */
    private static int getBlockColor(BlockState state) {
        if (state.isAir()) return 0x00000000;

        int r = 100, g = 100, b = 100;

        try {
            if (state.is(Blocks.GRASS_BLOCK))                              { r = 95;  g = 159; b = 53;  }
            else if (state.is(Blocks.DIRT))                                { r = 139; g = 101; b = 68;  }
            else if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)) { r = 128; g = 128; b = 128; }
            else if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG) || state.is(Blocks.SPRUCE_LOG)) { r = 101; g = 77; b = 47; }
            else if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES) || state.is(Blocks.SPRUCE_LEAVES)) { r = 59; g = 101; b = 36; }
            else if (state.is(Blocks.WATER))                               { r = 0;   g = 100; b = 200; }
            else if (state.is(Blocks.SAND))                                { r = 238; g = 203; b = 139; }
            else if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)){ r = 245; g = 245; b = 245; }
            else if (state.is(Blocks.NETHERRACK))                          { r = 114; g = 22;  b = 22;  }
            else if (state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)) { r = 75; g = 61; b = 50; }
            else if (state.is(Blocks.END_STONE))                           { r = 220; g = 213; b = 150; }
            else if (state.is(Blocks.BEDROCK))                             { r = 50;  g = 50;  b = 50;  }
            else if (state.is(Blocks.GRAVEL))                              { r = 147; g = 139; b = 131; }
            else if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)) { r = 70; g = 68; b = 80; }
            else if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.SPRUCE_PLANKS)) { r = 180; g = 130; b = 70; }
            else if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) { r = 200; g = 230; b = 240; }
            else if (state.is(Blocks.LAVA))                                { r = 220; g = 90;  b = 10;  }
            else if (state.is(Blocks.GLOWSTONE))                           { r = 255; g = 220; b = 120; }
            else if (state.is(Blocks.NETHER_BRICKS))                       { r = 80;  g = 20;  b = 20;  }
        } catch (Exception ignored) {}

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Resolves which Level instance to sample blocks from.
     * Same-dim → client level. Cross-dim SP → integrated server level. MP cross-dim → null.
     */
    private static Level resolveSampleLevel(ResourceLocation destDimension, Level clientLevel) {
        if (clientLevel == null) return null;

        if (clientLevel.dimension().location().equals(destDimension)) {
            return clientLevel;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            net.minecraft.resources.ResourceKey<Level> destKey =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION,
                            destDimension);
            net.minecraft.server.level.ServerLevel serverLevel =
                    mc.getSingleplayerServer().getLevel(destKey);
            if (serverLevel != null) return serverLevel;
        }

        return null;
    }
}
