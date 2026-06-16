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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders a perspective "camera" view of the destination position using the
 * yaw/pitch stored in PortalViewData. Captures run on a dedicated single-thread
 * executor so the game thread is never blocked.
 */
@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    /** Vertical FOV in degrees. */
    private static final float FOV_DEGREES = 70.0f;

    /** Maximum ray march distance in blocks. */
    private static final int MAX_RAY_DIST = 48;

    /**
     * Single background thread for ray-march captures.
     * One thread is enough — captures are throttled by captureInterval anyway.
     * Using a daemon thread so it doesn't block JVM shutdown.
     */
    private static final ExecutorService CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CosmosLiveView-Capture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1); // slightly below game thread
        return t;
    });

    /**
     * Schedules an async capture for {@code portalData}.
     * If a capture is already in flight for this portal, the new request is ignored.
     */
    public static void captureAsync(PortalViewData portalData, Level level) {
        if (portalData == null) return;
        if (portalData.isCaptureInFlight()) return; // don't queue duplicate work

        BlockPos destPos = portalData.destPos;
        if (destPos == null) return;

        Level sampleLevel = resolveSampleLevel(portalData.destDimension, level);
        if (sampleLevel == null) return;

        int resolution = Math.max(64, Math.min(PortalLiveViewConfig.CAPTURE_RESOLUTION.get(), 1024));

        portalData.setCaptureInFlight(true);

        final float yaw   = portalData.destYaw;
        final float pitch = portalData.destPitch;

        CAPTURE_EXECUTOR.submit(() -> {
            try {
                NativeImage image = renderPerspectiveView(sampleLevel, destPos, yaw, pitch, resolution);
                if (image != null) {
                    // Hand the image back to the main/render thread.
                    // We do this by posting to the Minecraft main-thread queue.
                    Minecraft.getInstance().execute(() -> {
                        try {
                            DynamicTexture texture = new DynamicTexture(image);
                            texture.upload();
                            portalData.setTexture(texture);
                        } catch (Exception ignored) {
                        } finally {
                            portalData.setCaptureInFlight(false);
                        }
                    });
                } else {
                    portalData.setCaptureInFlight(false);
                }
            } catch (Exception e) {
                portalData.setCaptureInFlight(false);
            }
        });
    }

    // ── Ray-march renderer ─────────────────────────────────────────────────────

    /**
     * Renders a perspective view from {@code eyePos} in the direction yawDeg/pitchDeg.
     * Returns a NativeImage (caller owns it), or null on failure.
     *
     * Coordinate convention:
     *   image (0,0) = top-left  →  V=0 in texture
     *   image (0,res-1) = bottom-left  →  V=1 in texture
     *
     * The quad renderer maps:
     *   top    vertex → V=0
     *   bottom vertex → V=1
     *
     * So rows must be stored top-first (py=0 → topmost pixel), which is exactly what
     * ndcY = 1 - (2*py/res) produces: py=0 → ndcY=+1 (up), py=res-1 → ndcY=-1 (down).
     */
    private static NativeImage renderPerspectiveView(Level level, BlockPos eyePos,
                                                      float yawDeg, float pitchDeg,
                                                      int resolution) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);

        double eyeX = eyePos.getX() + 0.5;
        double eyeY = eyePos.getY() + 1.62; // player eye height
        double eyeZ = eyePos.getZ() + 0.5;

        // MC yaw: 0=south(+Z), 90=west(-X), 180=north(-Z), 270=east(+X)
        // MC pitch: 0=horizontal, -90=up, +90=down
        double yawRad   = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);

        // Forward vector
        double fwdX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fwdY = -Math.sin(pitchRad);
        double fwdZ =  Math.cos(yawRad) * Math.cos(pitchRad);

        // Right vector (horizontal, perpendicular to forward)
        double rightX = Math.cos(yawRad);
        double rightY = 0.0;
        double rightZ = Math.sin(yawRad);

        // Up vector = right × forward  (points upward in world space)
        double upX = rightY * fwdZ - rightZ * fwdY;
        double upY = rightZ * fwdX - rightX * fwdZ;
        double upZ = rightX * fwdY - rightY * fwdX;

        double halfFov = Math.tan(Math.toRadians(FOV_DEGREES / 2.0));

        for (int py = 0; py < resolution; py++) {
            for (int px = 0; px < resolution; px++) {
                // NDC: px=0,py=0 → top-left of image → ndcX=-1, ndcY=+1
                double ndcX =  (2.0 * px / resolution) - 1.0;
                double ndcY = 1.0 - (2.0 * py / resolution); // +1 at top, -1 at bottom

                // Ray direction in world space
                double rdX = fwdX + rightX * ndcX * halfFov + upX * ndcY * halfFov;
                double rdY = fwdY + rightY * ndcX * halfFov + upY * ndcY * halfFov;
                double rdZ = fwdZ + rightZ * ndcX * halfFov + upZ * ndcY * halfFov;

                double len = Math.sqrt(rdX*rdX + rdY*rdY + rdZ*rdZ);
                if (len < 1e-6) { image.setPixelRGBA(px, py, toABGR(0xFF000000)); continue; }
                rdX /= len; rdY /= len; rdZ /= len;

                int color = marchRay(level, eyeX, eyeY, eyeZ, rdX, rdY, rdZ);
                image.setPixelRGBA(px, py, toABGR(color));
            }
        }

        return image;
    }

    /** DDA ray march. Returns 0xAARRGGBB color. */
    private static int marchRay(Level level,
                                  double ox, double oy, double oz,
                                  double dx, double dy, double dz) {
        // Step size: 0.35 blocks — slightly finer than before for better quality
        final double step = 0.35;
        double x = ox, y = oy, z = oz;
        int steps = (int)(MAX_RAY_DIST / step);

        for (int i = 0; i < steps; i++) {
            x += dx * step;
            y += dy * step;
            z += dz * step;

            if (y < level.getMinBuildHeight() || y > level.getMaxBuildHeight()) break;

            BlockPos bp = new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
            BlockState state = level.getBlockState(bp);

            if (!state.isAir() && state.isSolidRender(level, bp)) {
                double dist = Math.sqrt((x-ox)*(x-ox) + (y-oy)*(y-oy) + (z-oz)*(z-oz));
                float shade = (float) Math.max(0.25, 1.0 - dist / (MAX_RAY_DIST * 0.85));
                return shadeColor(getBlockColor(state), shade);
            }
        }

        return getSkyColor(dy);
    }

    private static int getSkyColor(double rdY) {
        float t = (float) Math.max(0.0, rdY);
        int r = (int)(10  + t * 100);
        int g = (int)(20  + t * 140);
        int b = (int)(60  + t * 175);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static int shadeColor(int argb, float shade) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.min(255, (int)(((argb >> 16) & 0xFF) * shade));
        int g = Math.min(255, (int)(((argb >>  8) & 0xFF) * shade));
        int b = Math.min(255, (int)(( argb        & 0xFF) * shade));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 0xAARRGGBB → 0xAABBGGRR (NativeImage little-endian byte order). */
    private static int toABGR(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int getBlockColor(BlockState state) {
        if (state.isAir()) return 0x00000000;
        int r = 100, g = 100, b = 100;
        try {
            if      (state.is(Blocks.GRASS_BLOCK))                                          { r=95;  g=159; b=53;  }
            else if (state.is(Blocks.DIRT))                                                  { r=139; g=101; b=68;  }
            else if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE))                { r=128; g=128; b=128; }
            else if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG) || state.is(Blocks.SPRUCE_LOG)) { r=101; g=77; b=47; }
            else if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES) || state.is(Blocks.SPRUCE_LEAVES)) { r=59; g=101; b=36; }
            else if (state.is(Blocks.WATER))                                                 { r=0;   g=100; b=200; }
            else if (state.is(Blocks.SAND))                                                  { r=238; g=203; b=139; }
            else if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK))                 { r=245; g=245; b=245; }
            else if (state.is(Blocks.NETHERRACK))                                            { r=114; g=22;  b=22;  }
            else if (state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL))             { r=75;  g=61;  b=50;  }
            else if (state.is(Blocks.END_STONE))                                             { r=220; g=213; b=150; }
            else if (state.is(Blocks.BEDROCK))                                               { r=50;  g=50;  b=50;  }
            else if (state.is(Blocks.GRAVEL))                                                { r=147; g=139; b=131; }
            else if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE))     { r=70;  g=68;  b=80;  }
            else if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.SPRUCE_PLANKS))        { r=180; g=130; b=70;  }
            else if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE))                { r=200; g=230; b=240; }
            else if (state.is(Blocks.LAVA))                                                  { r=220; g=90;  b=10;  }
            else if (state.is(Blocks.GLOWSTONE))                                             { r=255; g=220; b=120; }
            else if (state.is(Blocks.NETHER_BRICKS))                                        { r=80;  g=20;  b=20;  }
            else if (state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.MOSSY_STONE_BRICKS)) { r=90; g=120; b=70; }
            else if (state.is(Blocks.BRICKS))                                               { r=160; g=90;  b=70;  }
            else if (state.is(Blocks.CLAY))                                                  { r=160; g=160; b=175; }
            else if (state.is(Blocks.PACKED_ICE) || state.is(Blocks.ICE))                  { r=160; g=195; b=220; }
        } catch (Exception ignored) {}
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static Level resolveSampleLevel(ResourceLocation destDimension, Level clientLevel) {
        if (clientLevel == null) return null;
        if (clientLevel.dimension().location().equals(destDimension)) return clientLevel;

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
