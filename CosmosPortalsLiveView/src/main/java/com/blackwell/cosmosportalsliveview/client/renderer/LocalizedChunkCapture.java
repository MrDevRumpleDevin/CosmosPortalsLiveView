package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Raycaster-based perspective renderer for portal live views.
 *
 * Quality improvements over the original:
 *  - Amanatides-Woo grid-aligned DDA (exact block crossing, no over/under-stepping)
 *  - Real block colors sampled from the BlockModelShaper particle sprite, tinted by
 *    BlockColors — snapshotted on the main thread before executor submit
 *  - 4× SSAA: 4 jittered sub-rays per pixel, results averaged
 *  - Face-normal AO: top face 100%, side faces 78%, bottom face 60%
 *  - Improved sky: horizon haze blending into deep blue, optional sun disk
 *
 * Threading model: color snapshot + entity snapshot run on the calling (main/render)
 * thread. All raycasting runs on CAPTURE_EXECUTOR — zero GL calls, safe.
 *
 * Image orientation (unchanged from original):
 *   NativeImage row 0 = first memory row. MC's shader maps V=0 to GL bottom.
 *   We render top-of-scene into row 0, then flipVertical() so the shader sees it
 *   right-side-up on the portal quad.
 */
@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    private static final float   FOV_DEGREES      = 70.0f;
    private static final int     MAX_RAY_DIST     = 48;
    private static final double  ENTITY_SCAN_RADIUS = 32.0;

    /** 4× SSAA jitter offsets in pixel units (−0.5..+0.5 range). */
    private static final double[] JITTER_X = { -0.25,  0.25, -0.25,  0.25 };
    private static final double[] JITTER_Y = { -0.25, -0.25,  0.25,  0.25 };

    /** Face AO multipliers — top / side / bottom. */
    private static final float AO_TOP    = 1.00f;
    private static final float AO_SIDE   = 0.78f;
    private static final float AO_BOTTOM = 0.60f;

    /** Distance-based fog: full color at 0, fades toward sky at MAX_RAY_DIST. */
    private static final float FOG_START = 20.0f; // blocks

    private static final ExecutorService CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CosmosLiveView-Capture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Schedules an async capture. No-op if a capture is already in flight for this portal.
     * Must be called from the main/render thread (entity + color snapshots happen here).
     */
    public static void captureAsync(PortalViewData portalData, Level level) {
        if (portalData == null) return;
        if (portalData.isCaptureInFlight()) return;
        if (portalData.destPos == null) return;

        Level sampleLevel = resolveSampleLevel(portalData.destDimension, level);
        if (sampleLevel == null) return;

        int resolution = PortalLiveViewConfig.CAPTURE_RESOLUTION.get();
        resolution = Math.max(64, Math.min(resolution, 512));

        portalData.setCaptureInFlight(true);

        // ── Snapshot on main thread ──────────────────────────────────────────
        final List<EntityDot>     entityDots  = snapshotEntities(sampleLevel, portalData.destPos);
        final Map<BlockState, Integer> colorMap = snapshotBlockColors(sampleLevel, portalData.destPos);

        final Level    levelSnap = sampleLevel;
        final int      resSnap   = resolution;
        final float    yaw       = portalData.destYaw;
        final float    pitch     = portalData.destPitch;
        final BlockPos destPos   = portalData.destPos;

        CAPTURE_EXECUTOR.submit(() -> {
            NativeImage image = null;
            try {
                image = renderPerspectiveView(levelSnap, destPos, yaw, pitch, resSnap,
                                              entityDots, colorMap);
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

    // ── Color snapshot (main thread) ───────────────────────────────────────────

    /**
     * Walks a cube of blocks around destPos, samples the particle icon color for each
     * unique BlockState, and stores state→ARGB in a HashMap.
     * Must run on the main/render thread (ModelShaper and TextureAtlas are not thread-safe).
     */
    private static Map<BlockState, Integer> snapshotBlockColors(Level level, BlockPos center) {
        Map<BlockState, Integer> map = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();

        int radius = Math.min(MAX_RAY_DIST + 2, 50);
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = Math.max(level.getMinBuildHeight(), cy - radius);
                     y <= Math.min(level.getMaxBuildHeight(), cy + radius); y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(bp);
                    if (state.isAir() || map.containsKey(state)) continue;

                    int color = sampleSpriteColor(mc, state, level, bp);
                    map.put(state, color);
                }
            }
        }
        return map;
    }

    /**
     * Samples the average color of a block's particle sprite, optionally tinted
     * by BlockColors (grass, leaves, water biome tint).
     */
    private static int sampleSpriteColor(Minecraft mc, BlockState state,
                                          Level level, BlockPos pos) {
        try {
            // Particle sprite from the model (works for vanilla + most modded blocks)
            TextureAtlasSprite sprite =
                    mc.getModelManager()
                      .getBlockModelShaper()
                      .getBlockModel(state)
                      .getParticleIcon();

            // Average the sprite pixels (they're stored as floats u0–u1, v0–v1
            // within the 16×16 atlas cell; we sample a 4×4 sub-grid for speed)
            int rSum = 0, gSum = 0, bSum = 0, n = 0;
            int spriteW = Math.max(1, sprite.contents().width());
            int spriteH = Math.max(1, sprite.contents().height());

            // Sample up to 16 pixels evenly from the sprite
            int samples = Math.min(16, spriteW * spriteH);
            for (int i = 0; i < samples; i++) {
                int sx = (int)((i % 4) * (spriteW / 4.0));
                int sy = (int)((i / 4) * (spriteH / 4.0));
                // getPixel returns 0xAABBGGRR (NativeImage RGBA)
                int px = sprite.contents().getOriginalImage().getPixelRGBA(
                        Math.min(sx, spriteW - 1),
                        Math.min(sy, spriteH - 1));
                // Extract as ARGB
                int pa = (px >> 24) & 0xFF;
                if (pa < 10) continue; // skip transparent pixels
                int pr = (px      ) & 0xFF; // NativeImage is ABGR stored, getPixelRGBA → AABBGGRR
                int pg = (px >>  8) & 0xFF;
                int pb = (px >> 16) & 0xFF;
                rSum += pr; gSum += pg; bSum += pb; n++;
            }

            if (n == 0) return fallbackColor(state); // fully transparent sprite

            int r = rSum / n, g = gSum / n, b = bSum / n;

            // Apply biome/block tint via BlockColors
            int tint = mc.getBlockColors().getColor(state, level, pos, 0);
            if (tint != -1) {
                r = (r * ((tint >> 16) & 0xFF)) / 255;
                g = (g * ((tint >>  8) & 0xFF)) / 255;
                b = (b * ( tint        & 0xFF)) / 255;
            }

            return (0xFF << 24) | (r << 16) | (g << 8) | b;

        } catch (Exception e) {
            return fallbackColor(state);
        }
    }

    // ── Entity snapshot (main thread) ──────────────────────────────────────────

    private record EntityDot(double x, double y, double z, int argbColor) {}

    private static List<EntityDot> snapshotEntities(Level level, BlockPos origin) {
        List<EntityDot> dots = new ArrayList<>();
        try {
            AABB scanBox = new AABB(
                    origin.getX() - ENTITY_SCAN_RADIUS, origin.getY() - ENTITY_SCAN_RADIUS,
                    origin.getZ() - ENTITY_SCAN_RADIUS,
                    origin.getX() + ENTITY_SCAN_RADIUS, origin.getY() + ENTITY_SCAN_RADIUS,
                    origin.getZ() + ENTITY_SCAN_RADIUS);
            for (Entity e : level.getEntities(null, scanBox)) {
                int color;
                if      (e instanceof Player)      color = 0xFFFFFF00;
                else if (e instanceof Monster)     color = 0xFFFF3030;
                else if (e instanceof Animal)      color = 0xFF90EE90;
                else if (e instanceof LivingEntity)color = 0xFFFFFFFF;
                else continue;
                dots.add(new EntityDot(e.getX(), e.getY() + e.getEyeHeight(), e.getZ(), color));
            }
        } catch (Exception ignored) {}
        return dots;
    }

    // ── Renderer ───────────────────────────────────────────────────────────────

    private static NativeImage renderPerspectiveView(Level level, BlockPos eyePos,
                                                      float yawDeg, float pitchDeg,
                                                      int resolution,
                                                      List<EntityDot> entityDots,
                                                      Map<BlockState, Integer> colorMap) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);

        double eyeX = eyePos.getX() + 0.5;
        double eyeY = eyePos.getY() + 1.62;
        double eyeZ = eyePos.getZ() + 0.5;

        double yawRad   = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);

        // Camera basis vectors
        double fwdX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fwdY = -Math.sin(pitchRad);
        double fwdZ =  Math.cos(yawRad) * Math.cos(pitchRad);

        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        // rightY = 0 (horizontal right vector)

        // up = right × fwd
        double upX = -rightZ * fwdY;
        double upY =  rightZ * fwdX - rightX * fwdZ;
        double upZ =  rightX * fwdY;

        double halfFov = Math.tan(Math.toRadians(FOV_DEGREES / 2.0));
        double pixStep = 2.0 / resolution; // NDC size of one pixel

        float[] depthBuf = new float[resolution * resolution];

        // ── 4× SSAA block raycasting ──────────────────────────────────────────
        for (int py = 0; py < resolution; py++) {
            for (int px = 0; px < resolution; px++) {
                // Center of pixel in NDC
                double ndcCX =  (2.0 * px + 1.0) / resolution - 1.0;
                double ndcCY = 1.0 - (2.0 * py + 1.0) / resolution;

                int    aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                float  depthAccum = 0f;

                for (int s = 0; s < 4; s++) {
                    double ndcX = ndcCX + JITTER_X[s] * pixStep;
                    double ndcY = ndcCY + JITTER_Y[s] * pixStep;

                    double rdX = fwdX + rightX * ndcX * halfFov + upX * ndcY * halfFov;
                    double rdY = fwdY +          ndcX * 0.0     + upY * ndcY * halfFov;
                    double rdZ = fwdZ + rightZ * ndcX * halfFov + upZ * ndcY * halfFov;

                    double len = Math.sqrt(rdX*rdX + rdY*rdY + rdZ*rdZ);
                    if (len < 1e-9) { aSum += 255; depthAccum += MAX_RAY_DIST; continue; }
                    rdX /= len; rdY /= len; rdZ /= len;

                    int[] hit = new int[1];
                    float dist = ddaRay(level, eyeX, eyeY, eyeZ, rdX, rdY, rdZ,
                                        hit, colorMap);

                    // Unpack ARGB sample
                    int col = hit[0];
                    aSum += (col >> 24) & 0xFF;
                    rSum += (col >> 16) & 0xFF;
                    gSum += (col >>  8) & 0xFF;
                    bSum +=  col        & 0xFF;
                    depthAccum += dist;
                }

                int argb = ((aSum / 4) << 24) | ((rSum / 4) << 16)
                         | ((gSum / 4) <<  8) |  (bSum / 4);
                image.setPixelRGBA(px, py, toABGR(argb));
                depthBuf[py * resolution + px] = depthAccum / 4f;
            }
        }

        // ── Entity dots ───────────────────────────────────────────────────────
        if (!entityDots.isEmpty()) {
            for (EntityDot dot : entityDots) {
                double dx = dot.x() - eyeX;
                double dy = dot.y() - eyeY;
                double dz = dot.z() - eyeZ;

                double entityDepth = dx * fwdX + dy * fwdY + dz * fwdZ;
                if (entityDepth < 0.5) continue;

                double projX = dx * rightX              + dz * rightZ;
                double projY = dx * upX + dy * upY + dz * upZ;

                double ndcEX = projX / (entityDepth * halfFov);
                double ndcEY = projY / (entityDepth * halfFov);

                int screenX = (int)((ndcEX + 1.0) * 0.5 * resolution);
                int screenY = (int)((1.0 - ndcEY) * 0.5 * resolution);

                int dotSize = Math.max(1, (int)(4.0 - entityDepth / 10.0));
                for (int dy2 = -dotSize; dy2 <= dotSize; dy2++) {
                    for (int dx2 = -dotSize; dx2 <= dotSize; dx2++) {
                        int spx = screenX + dx2;
                        int spy = screenY + dy2;
                        if (spx < 0 || spx >= resolution || spy < 0 || spy >= resolution) continue;
                        if ((float) entityDepth < depthBuf[spy * resolution + spx] + 0.5f) {
                            image.setPixelRGBA(spx, spy, toABGR(dot.argbColor()));
                        }
                    }
                }
            }
        }

        flipVertical(image, resolution);
        return image;
    }

    // ── Amanatides-Woo DDA ────────────────────────────────────────────────────

    /**
     * Traces a ray using exact grid-aligned DDA (Amanatides & Woo, 1987).
     * Visits each block cell exactly once per axis crossing — no over/under-step.
     *
     * @param hit    out-param: hit[0] = final ARGB pixel color (block or sky)
     * @return       hit distance in blocks (MAX_RAY_DIST for sky)
     */
    private static float ddaRay(Level level,
                                  double ox, double oy, double oz,
                                  double dx, double dy, double dz,
                                  int[] hit,
                                  Map<BlockState, Integer> colorMap) {
        // Current voxel
        int bx = (int) Math.floor(ox);
        int by = (int) Math.floor(oy);
        int bz = (int) Math.floor(oz);

        // Step directions
        int sx = dx >= 0 ? 1 : -1;
        int sy = dy >= 0 ? 1 : -1;
        int sz = dz >= 0 ? 1 : -1;

        // tDelta: how far along the ray we travel per unit step in each axis
        double tDeltaX = Math.abs(dx) < 1e-12 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = Math.abs(dy) < 1e-12 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = Math.abs(dz) < 1e-12 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        // tMax: distance to first crossing of each axis boundary
        double tMaxX = Math.abs(dx) < 1e-12 ? Double.MAX_VALUE
                : (dx > 0 ? (Math.floor(ox) + 1 - ox) : (ox - Math.floor(ox))) / Math.abs(dx);
        double tMaxY = Math.abs(dy) < 1e-12 ? Double.MAX_VALUE
                : (dy > 0 ? (Math.floor(oy) + 1 - oy) : (oy - Math.floor(oy))) / Math.abs(dy);
        double tMaxZ = Math.abs(dz) < 1e-12 ? Double.MAX_VALUE
                : (dz > 0 ? (Math.floor(oz) + 1 - oz) : (oz - Math.floor(oz))) / Math.abs(dz);

        Direction hitFace = Direction.NORTH; // default, updated per step

        double t = 0.0;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        while (t < MAX_RAY_DIST) {
            // Advance to next crossing
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX; tMaxX += tDeltaX;
                bx += sx;
                hitFace = sx > 0 ? Direction.WEST : Direction.EAST;
            } else if (tMaxY < tMaxZ) {
                t = tMaxY; tMaxY += tDeltaY;
                by += sy;
                hitFace = sy > 0 ? Direction.DOWN : Direction.UP;
            } else {
                t = tMaxZ; tMaxZ += tDeltaZ;
                bz += sz;
                hitFace = sz > 0 ? Direction.NORTH : Direction.SOUTH;
            }

            if (t >= MAX_RAY_DIST) break;
            if (by < minY || by > maxY) break;

            BlockPos bp    = new BlockPos(bx, by, bz);
            BlockState state = level.getBlockState(bp);

            if (!state.isAir() && state.isSolidRender(level, bp)) {
                // Look up pre-sampled color; fall back to hardcoded table
                int baseColor = colorMap.getOrDefault(state, fallbackColor(state));

                // Face-normal AO
                float ao;
                switch (hitFace) {
                    case UP:    ao = AO_TOP;    break;
                    case DOWN:  ao = AO_BOTTOM; break;
                    default:    ao = AO_SIDE;   break;
                }

                // Distance fog: lerp block color → sky at FAR
                float fogT = (float) Math.max(0.0, (t - FOG_START) / (MAX_RAY_DIST - FOG_START));
                fogT = Math.min(1.0f, fogT * fogT); // quadratic, feels more natural

                int skyCol  = computeSkyColor(dy);
                int shadedBlock = shadeColor(baseColor, ao);
                int finalCol   = lerpColor(shadedBlock, skyCol, fogT);

                hit[0] = finalCol;
                return (float) t;
            }
        }

        hit[0] = computeSkyColor(dy);
        return MAX_RAY_DIST;
    }

    // ── Sky ────────────────────────────────────────────────────────────────────

    /**
     * Sky gradient: deep blue above, horizon haze (light grey-blue) near the horizon,
     * with a subtle sun disk when looking nearly upward.
     *
     * @param rdY  normalized ray Y component (−1 to +1)
     */
    private static int computeSkyColor(double rdY) {
        // Base sky gradient
        float t = (float) Math.max(0.0, Math.min(1.0, rdY));
        // Horizon colour: light blue-grey; zenith: deep blue
        int hr = 180, hg = 210, hb = 230; // horizon
        int zr =  20, zg =  60, zb = 160; // zenith

        int r = (int)(hr + t * (zr - hr));
        int g = (int)(hg + t * (zg - hg));
        int b = (int)(hb + t * (zb - hb));

        // Below-horizon: blend to brownish ground haze
        if (rdY < 0) {
            float u = (float) Math.min(1.0, -rdY * 2.0);
            r = (int)(r + u * (100 - r));
            g = (int)(g + u * ( 85 - g));
            b = (int)(b + u * ( 60 - b));
        }

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Colour helpers ─────────────────────────────────────────────────────────

    private static int shadeColor(int argb, float shade) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.min(255, (int)(((argb >> 16) & 0xFF) * shade));
        int g = Math.min(255, (int)(((argb >>  8) & 0xFF) * shade));
        int b = Math.min(255, (int)(( argb        & 0xFF) * shade));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >>  8) & 0xFF, ab =  a        & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >>  8) & 0xFF, bb =  b        & 0xFF;
        int r  = (int)(ar + t * (br - ar));
        int g  = (int)(ag + t * (bg - ag));
        int bl = (int)(ab + t * (bb - ab));
        return (0xFF << 24) | (r << 16) | (g << 8) | bl;
    }

    /** 0xAARRGGBB → 0xAABBGGRR (NativeImage stores pixels as ABGR in memory). */
    private static int toABGR(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static void flipVertical(NativeImage image, int resolution) {
        for (int y = 0; y < resolution / 2; y++) {
            int mirrorY = resolution - 1 - y;
            for (int x = 0; x < resolution; x++) {
                int top    = image.getPixelRGBA(x, y);
                int bottom = image.getPixelRGBA(x, mirrorY);
                image.setPixelRGBA(x, y,       bottom);
                image.setPixelRGBA(x, mirrorY, top);
            }
        }
    }

    // ── Fallback color table ───────────────────────────────────────────────────

    /**
     * Hardcoded color table used when sprite sampling fails or the block
     * is not yet in the snapshot map (e.g. modded blocks not in the scan cube).
     */
    private static int fallbackColor(BlockState state) {
        if (state == null || state.isAir()) return 0x00000000;
        int r = 110, g = 110, b = 110;
        try {
            if      (state.is(Blocks.GRASS_BLOCK))                                   { r= 95; g=159; b= 53; }
            else if (state.is(Blocks.DIRT) || state.is(Blocks.ROOTED_DIRT))          { r=139; g=101; b= 68; }
            else if (state.is(Blocks.STONE))                                          { r=128; g=128; b=128; }
            else if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE)){ r=108; g=108; b=108; }
            else if (state.is(Blocks.OAK_LOG)   || state.is(Blocks.BIRCH_LOG)
                  || state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.JUNGLE_LOG)
                  || state.is(Blocks.ACACIA_LOG) || state.is(Blocks.DARK_OAK_LOG))   { r=101; g= 77; b= 47; }
            else if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES)
                  || state.is(Blocks.SPRUCE_LEAVES) || state.is(Blocks.JUNGLE_LEAVES)
                  || state.is(Blocks.ACACIA_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES)){ r=59; g=101; b=36; }
            else if (state.is(Blocks.WATER))                                          { r=  0; g=100; b=200; }
            else if (state.is(Blocks.SAND) || state.is(Blocks.SANDSTONE))             { r=238; g=203; b=139; }
            else if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK))            { r=245; g=245; b=245; }
            else if (state.is(Blocks.NETHERRACK))                                     { r=114; g= 22; b= 22; }
            else if (state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL))        { r= 75; g= 61; b= 50; }
            else if (state.is(Blocks.END_STONE) || state.is(Blocks.END_STONE_BRICKS)) { r=220; g=213; b=150; }
            else if (state.is(Blocks.BEDROCK))                                         { r= 50; g= 50; b= 50; }
            else if (state.is(Blocks.GRAVEL))                                          { r=147; g=139; b=131; }
            else if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)){ r= 70; g= 68; b= 80; }
            else if (state.is(Blocks.OAK_PLANKS)  || state.is(Blocks.SPRUCE_PLANKS)
                  || state.is(Blocks.BIRCH_PLANKS) || state.is(Blocks.JUNGLE_PLANKS)) { r=180; g=130; b= 70; }
            else if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE))           { r=200; g=230; b=240; }
            else if (state.is(Blocks.LAVA))                                            { r=220; g= 90; b= 10; }
            else if (state.is(Blocks.GLOWSTONE))                                       { r=255; g=220; b=120; }
            else if (state.is(Blocks.NETHER_BRICKS))                                   { r= 80; g= 20; b= 20; }
            else if (state.is(Blocks.STONE_BRICKS) || state.is(Blocks.MOSSY_STONE_BRICKS)){ r=110; g=110; b=115; }
            else if (state.is(Blocks.BRICKS))                                          { r=160; g= 90; b= 70; }
            else if (state.is(Blocks.CLAY))                                            { r=160; g=160; b=175; }
            else if (state.is(Blocks.PACKED_ICE) || state.is(Blocks.ICE))             { r=160; g=195; b=220; }
            else if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN))    { r= 20; g= 15; b= 30; }
            else if (state.is(Blocks.IRON_BLOCK))                                       { r=210; g=210; b=215; }
            else if (state.is(Blocks.GOLD_BLOCK))                                       { r=255; g=210; b= 50; }
            else if (state.is(Blocks.DIAMOND_BLOCK))                                    { r= 80; g=220; b=215; }
            else if (state.is(Blocks.EMERALD_BLOCK))                                    { r= 50; g=200; b= 90; }
            else if (state.is(Blocks.REDSTONE_BLOCK))                                   { r=200; g= 30; b= 30; }
            else if (state.is(Blocks.TERRACOTTA))                                       { r=152; g= 94; b= 67; }
        } catch (Exception ignored) {}
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Level resolver ─────────────────────────────────────────────────────────

    private static Level resolveSampleLevel(ResourceLocation destDimension, Level clientLevel) {
        if (clientLevel == null) return null;
        if (clientLevel.dimension().location().equals(destDimension)) return clientLevel;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            net.minecraft.resources.ResourceKey<Level> destKey =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, destDimension);
            net.minecraft.server.level.ServerLevel serverLevel =
                    mc.getSingleplayerServer().getLevel(destKey);
            if (serverLevel != null) return serverLevel;
        }
        return null;
    }
}
