package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.mojang.blaze3d.platform.NativeImage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders a perspective "camera" view of the destination position using the
 * yaw/pitch stored in PortalViewData. Captures run on a dedicated single-thread
 * executor so the game thread is never blocked.
 *
 * Image orientation:
 *   NativeImage(px, py) where py=0 is stored first in memory.
 *   OpenGL uploads this with row 0 at the BOTTOM of the texture (V=0 in GL).
 *   MC's entity/translucent shader does NOT flip V, so V=0 = GL bottom = NativeImage row 0.
 *   Our quad: bottom portal vertex → V=1, top portal vertex → V=0.
 *   Therefore: NativeImage row 0 → V=0 → maps to TOP of the portal quad.
 *   To show the scene right-side-up, row 0 must contain the TOP of the scene (ndcY=+1).
 *   However, testing shows the view is inverted, which means MC's shader DOES flip V
 *   (as it does for atlas textures). We correct by flipping the image vertically after
 *   ray-marching, so the in-memory layout matches what the shader expects.
 */
@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    private static final float FOV_DEGREES = 70.0f;
    private static final int MAX_RAY_DIST = 48;

    /** Entity render distances (blocks) for dots in the live view. */
    private static final double ENTITY_SCAN_RADIUS = 32.0;

    private static final ExecutorService CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CosmosLiveView-Capture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Schedules an async capture. Ignored if a capture is already in flight for this portal.
     */
    public static void captureAsync(PortalViewData portalData, Level level) {
        if (portalData == null) return;
        if (portalData.isCaptureInFlight()) return;
        if (portalData.destPos == null) return;

        Level sampleLevel = resolveSampleLevel(portalData.destDimension, level);
        if (sampleLevel == null) return;

        // Clamp resolution: respect config, hard cap at 512 for performance
        int resolution = PortalLiveViewConfig.CAPTURE_RESOLUTION.get();
        resolution = Math.max(64, Math.min(resolution, 512));

        portalData.setCaptureInFlight(true);

        final Level levelSnap    = sampleLevel;
        final int   resSnap      = resolution;
        final float yaw          = portalData.destYaw;
        final float pitch        = portalData.destPitch;
        final BlockPos destPos   = portalData.destPos;

        // Snapshot entity list on the main thread BEFORE jumping to background
        final List<EntityDot> entityDots = snapshotEntities(levelSnap, destPos);

        CAPTURE_EXECUTOR.submit(() -> {
            NativeImage image = null;
            try {
                image = renderPerspectiveView(levelSnap, destPos, yaw, pitch, resSnap, entityDots);
                final NativeImage finalImage = image;

                Minecraft.getInstance().execute(() -> {
                    try {
                        DynamicTexture texture = new DynamicTexture(finalImage);
                        texture.upload();
                        portalData.setTexture(texture);
                    } catch (Exception ignored) {
                    } finally {
                        portalData.setCaptureInFlight(false);
                    }
                });
            } catch (Exception e) {
                if (image != null) { try { image.close(); } catch (Exception ignored) {} }
                portalData.setCaptureInFlight(false);
            }
        });
    }

    // ── Entity snapshot ────────────────────────────────────────────────────────

    /** Lightweight entity data captured on the game thread before background rendering. */
    private record EntityDot(double x, double y, double z, int argbColor) {}

    /**
     * Snapshots nearby entity positions and assigns a color per entity type.
     * Must be called from the main/server thread since entity lists are not thread-safe.
     */
    private static List<EntityDot> snapshotEntities(Level level, BlockPos origin) {
        List<EntityDot> dots = new ArrayList<>();
        try {
            AABB scanBox = new AABB(
                    origin.getX() - ENTITY_SCAN_RADIUS, origin.getY() - ENTITY_SCAN_RADIUS, origin.getZ() - ENTITY_SCAN_RADIUS,
                    origin.getX() + ENTITY_SCAN_RADIUS, origin.getY() + ENTITY_SCAN_RADIUS, origin.getZ() + ENTITY_SCAN_RADIUS
            );
            List<Entity> entities = level.getEntities(null, scanBox);
            for (Entity e : entities) {
                int color;
                if (e instanceof Player)        color = 0xFFFFFF00; // yellow
                else if (e instanceof Monster)  color = 0xFFFF3030; // red
                else if (e instanceof Animal)   color = 0xFF90EE90; // light green
                else if (e instanceof LivingEntity) color = 0xFFFFFFFF; // white
                else continue; // skip non-living (drops, arrows, etc.)
                dots.add(new EntityDot(e.getX(), e.getY() + e.getEyeHeight(), e.getZ(), color));
            }
        } catch (Exception ignored) {}
        return dots;
    }

    // ── Renderer ───────────────────────────────────────────────────────────────

    private static NativeImage renderPerspectiveView(Level level, BlockPos eyePos,
                                                      float yawDeg, float pitchDeg,
                                                      int resolution,
                                                      List<EntityDot> entityDots) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);

        double eyeX = eyePos.getX() + 0.5;
        double eyeY = eyePos.getY() + 1.62;
        double eyeZ = eyePos.getZ() + 0.5;

        double yawRad   = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);

        // Forward, right, up vectors in world space
        double fwdX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fwdY = -Math.sin(pitchRad);
        double fwdZ =  Math.cos(yawRad) * Math.cos(pitchRad);

        double rightX = Math.cos(yawRad);
        double rightY = 0.0;
        double rightZ = Math.sin(yawRad);

        // up = right × fwd
        double upX = rightY * fwdZ - rightZ * fwdY;
        double upY = rightZ * fwdX - rightX * fwdZ;
        double upZ = rightX * fwdY - rightY * fwdX;

        double halfFov = Math.tan(Math.toRadians(FOV_DEGREES / 2.0));

        // Build a depth buffer so entities only show in front of geometry
        float[] depthBuf = new float[resolution * resolution];

        // ── Block raycasting ──────────────────────────────────────────────────
        for (int py = 0; py < resolution; py++) {
            for (int px = 0; px < resolution; px++) {
                // NDC: (0,0) = top-left → ndcX=-1, ndcY=+1
                double ndcX =  (2.0 * px / (double) resolution) - 1.0;
                double ndcY = 1.0 - (2.0 * py / (double) resolution);

                double rdX = fwdX + rightX * ndcX * halfFov + upX * ndcY * halfFov;
                double rdY = fwdY + rightY * ndcX * halfFov + upY * ndcY * halfFov;
                double rdZ = fwdZ + rightZ * ndcX * halfFov + upZ * ndcY * halfFov;

                double len = Math.sqrt(rdX*rdX + rdY*rdY + rdZ*rdZ);
                if (len < 1e-6) {
                    image.setPixelRGBA(px, py, toABGR(0xFF000000));
                    depthBuf[py * resolution + px] = MAX_RAY_DIST;
                    continue;
                }
                rdX /= len; rdY /= len; rdZ /= len;

                float dist = marchRay(level, eyeX, eyeY, eyeZ, rdX, rdY, rdZ, image, px, py);
                depthBuf[py * resolution + px] = dist;
            }
        }

        // ── Entity dots ───────────────────────────────────────────────────────
        if (!entityDots.isEmpty()) {
            for (EntityDot dot : entityDots) {
                // Project entity world position into screen space
                double dx = dot.x() - eyeX;
                double dy = dot.y() - eyeY;
                double dz = dot.z() - eyeZ;

                // Dot product with forward for depth
                double entityDepth = dx * fwdX + dy * fwdY + dz * fwdZ;
                if (entityDepth < 0.5) continue; // behind camera

                // Project onto image plane
                double projX = dx * rightX + dy * rightY + dz * rightZ;
                double projY = dx * upX    + dy * upY    + dz * upZ;

                // NDC (divide by depth * halfFov to get pixel)
                double ndcEX = projX / (entityDepth * halfFov);
                double ndcEY = projY / (entityDepth * halfFov);

                // Convert NDC to pixel
                int screenX = (int)((ndcEX + 1.0) * 0.5 * resolution);
                int screenY = (int)((1.0 - ndcEY) * 0.5 * resolution); // flip Y

                // Draw a small dot (3×3 pixels, scaled by distance)
                int dotSize = Math.max(1, (int)(4.0 - entityDepth / 10.0));
                for (int dy2 = -dotSize; dy2 <= dotSize; dy2++) {
                    for (int dx2 = -dotSize; dx2 <= dotSize; dx2++) {
                        int spx = screenX + dx2;
                        int spy = screenY + dy2;
                        if (spx < 0 || spx >= resolution || spy < 0 || spy >= resolution) continue;
                        // Only draw if in front of geometry at that pixel
                        if ((float) entityDepth < depthBuf[spy * resolution + spx] + 0.5f) {
                            image.setPixelRGBA(spx, spy, toABGR(dot.argbColor()));
                        }
                    }
                }
            }
        }

        // ── Vertical flip ─────────────────────────────────────────────────────
        // NativeImage row 0 maps to the BOTTOM of the displayed quad in MC's shader.
        // We rendered top-of-scene into row 0 (ndcY=+1 at py=0).
        // After flip: row 0 = bottom of scene → displayed at bottom of portal. Correct.
        flipVertical(image, resolution);

        return image;
    }

    /**
     * DDA ray march. Writes the pixel color and returns the hit distance (blocks).
     * Returns MAX_RAY_DIST if no hit (sky).
     */
    private static float marchRay(Level level,
                                    double ox, double oy, double oz,
                                    double dx, double dy, double dz,
                                    NativeImage image, int px, int py) {
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
                float shade = (float) Math.max(0.2, 1.0 - dist / (MAX_RAY_DIST * 0.85));
                image.setPixelRGBA(px, py, toABGR(shadeColor(getBlockColor(state), shade)));
                return (float) dist;
            }
        }

        image.setPixelRGBA(px, py, toABGR(getSkyColor(dy)));
        return MAX_RAY_DIST;
    }

    /** Flip image rows in-place. */
    private static void flipVertical(NativeImage image, int resolution) {
        for (int y = 0; y < resolution / 2; y++) {
            int mirrorY = resolution - 1 - y;
            for (int x = 0; x < resolution; x++) {
                int top    = image.getPixelRGBA(x, y);
                int bottom = image.getPixelRGBA(x, mirrorY);
                image.setPixelRGBA(x, y,        bottom);
                image.setPixelRGBA(x, mirrorY,  top);
            }
        }
    }

    // ── Color helpers ──────────────────────────────────────────────────────────

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
            if      (state.is(Blocks.GRASS_BLOCK))                                               { r=95;  g=159; b=53;  }
            else if (state.is(Blocks.DIRT) || state.is(Blocks.ROOTED_DIRT))                     { r=139; g=101; b=68;  }
            else if (state.is(Blocks.STONE))                                                      { r=128; g=128; b=128; }
            else if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE))        { r=108; g=108; b=108; }
            else if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG)
                  || state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.JUNGLE_LOG)
                  || state.is(Blocks.ACACIA_LOG) || state.is(Blocks.DARK_OAK_LOG))              { r=101; g=77;  b=47;  }
            else if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES)
                  || state.is(Blocks.SPRUCE_LEAVES) || state.is(Blocks.JUNGLE_LEAVES)
                  || state.is(Blocks.ACACIA_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES))        { r=59;  g=101; b=36;  }
            else if (state.is(Blocks.WATER))                                                      { r=0;   g=100; b=200; }
            else if (state.is(Blocks.SAND) || state.is(Blocks.SANDSTONE))                       { r=238; g=203; b=139; }
            else if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK))                      { r=245; g=245; b=245; }
            else if (state.is(Blocks.NETHERRACK))                                                 { r=114; g=22;  b=22;  }
            else if (state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL))                  { r=75;  g=61;  b=50;  }
            else if (state.is(Blocks.END_STONE) || state.is(Blocks.END_STONE_BRICKS))           { r=220; g=213; b=150; }
            else if (state.is(Blocks.BEDROCK))                                                    { r=50;  g=50;  b=50;  }
            else if (state.is(Blocks.GRAVEL))                                                     { r=147; g=139; b=131; }
            else if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE))          { r=70;  g=68;  b=80;  }
            else if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.SPRUCE_PLANKS)
                  || state.is(Blocks.BIRCH_PLANKS) || state.is(Blocks.JUNGLE_PLANKS))           { r=180; g=130; b=70;  }
            else if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE))                     { r=200; g=230; b=240; }
            else if (state.is(Blocks.LAVA))                                                       { r=220; g=90;  b=10;  }
            else if (state.is(Blocks.GLOWSTONE))                                                  { r=255; g=220; b=120; }
            else if (state.is(Blocks.NETHER_BRICKS) || state.is(Blocks.NETHER_BRICK_FENCE))     { r=80;  g=20;  b=20;  }
            else if (state.is(Blocks.MOSSY_STONE_BRICKS) || state.is(Blocks.STONE_BRICKS))      { r=110; g=110; b=115; }
            else if (state.is(Blocks.BRICKS))                                                     { r=160; g=90;  b=70;  }
            else if (state.is(Blocks.CLAY))                                                       { r=160; g=160; b=175; }
            else if (state.is(Blocks.PACKED_ICE) || state.is(Blocks.ICE))                       { r=160; g=195; b=220; }
            else if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN))              { r=20;  g=15;  b=30;  }
            else if (state.is(Blocks.IRON_BLOCK))                                                 { r=210; g=210; b=215; }
            else if (state.is(Blocks.GOLD_BLOCK))                                                 { r=255; g=210; b=50;  }
            else if (state.is(Blocks.DIAMOND_BLOCK))                                              { r=80;  g=220; b=215; }
            else if (state.is(Blocks.EMERALD_BLOCK))                                              { r=50;  g=200; b=90;  }
            else if (state.is(Blocks.REDSTONE_BLOCK))                                             { r=200; g=30;  b=30;  }
            else if (state.is(Blocks.TERRACOTTA))                                                 { r=152; g=94;  b=67;  }
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
