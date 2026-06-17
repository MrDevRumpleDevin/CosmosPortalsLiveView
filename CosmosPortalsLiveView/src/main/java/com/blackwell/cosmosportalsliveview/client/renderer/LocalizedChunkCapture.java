package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Raycaster-based perspective renderer for portal live views.
 *
 * Rendering pipeline:
 *  - Amanatides-Woo DDA walks the block grid
 *  - Möller–Trumbore ray-quad intersection against actual BakedModel geometry
 *  - Barycentric UV interpolation → exact pixel sampled from the hit quad's sprite
 *  - Tint (grass/leaves/water) applied per-pixel
 *  - ENTITYBLOCK_ANIMATED fallback (beds, chests) via VoxelShape AABB + face-projected UV
 *  - Face-normal AO: top 100%, sides 78%, bottom 60%
 *  - Distance fog blends toward sky
 *  - 1× ray per pixel
 */
@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    private static final float  VIRTUAL_SCREEN_DIST = 2.0f;
    private static final float  EYE_FORWARD_OFFSET  = -0.5f;
    private static final int    MAX_RAY_DIST        = 48;
    private static final double ENTITY_SCAN_RADIUS  = 32.0;

    private static final float AO_TOP    = 1.00f;
    private static final float AO_SIDE   = 0.78f;
    private static final float AO_BOTTOM = 0.60f;

    private static final float FOG_START = 20.0f;

    /**
     * BakedQuad vertex stride (ints). Per vertex:
     *   [0] x float, [1] y float, [2] z float,
     *   [3] normal packed int,
     *   [4] u float (atlas UV), [5] v float (atlas UV),
     *   [6] AO/lightmap, [7] misc
     */
    private static final int VERTEX_STRIDE = 8;

    private static final ExecutorService CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CosmosLiveView-Capture");
        t.setDaemon(true);
        // Slightly below normal so the render thread is not starved.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /**
     * Per-frame budget for the raycaster in nanoseconds.
     * If a render exceeds this, remaining pixels get sky color and the result is still uploaded
     * (partial frame rather than a full freeze/stale hold).
     * 80 ms gives a comfortable margin below 100 ms interval at cost of occasional partial renders.
     */
    private static final long RENDER_BUDGET_NS = 80_000_000L; // 80 ms

    // ── Public API ─────────────────────────────────────────────────────────────

    public static void captureAsync(PortalViewData portalData, Level level) {
        if (portalData == null) return;
        if (portalData.isCaptureInFlight()) return;
        if (portalData.destPos == null) return;

        Level sampleLevel = resolveSampleLevel(portalData.destDimension, level);
        if (sampleLevel == null) return;

        int baseRes = PortalLiveViewConfig.CAPTURE_RESOLUTION.get();
        baseRes = Math.max(64, Math.min(baseRes, 512));

        float halfW  = Math.max(0.5f, portalData.portalHalfW);
        float halfH  = Math.max(0.5f, portalData.portalHalfH);
        float aspect = halfW / halfH;
        int resW, resH;
        if (aspect >= 1.0f) {
            resW = baseRes;
            resH = Math.max(16, Math.min(baseRes, (int)(baseRes / aspect)));
        } else {
            resH = baseRes;
            resW = Math.max(16, Math.min(baseRes, (int)(baseRes * aspect)));
        }

        portalData.setCaptureInFlight(true);

        final List<EntityDot> entityDots = snapshotEntities(sampleLevel, portalData.destPos);

        final Level    levelSnap = sampleLevel;
        final int      resWSnap  = resW;
        final int      resHSnap  = resH;
        final float    yaw       = portalData.destYaw;
        final float    pitch     = portalData.destPitch;
        final BlockPos destPos   = portalData.destPos;
        final float    halfWSnap = halfW;
        final float    halfHSnap = halfH;
        // Use the smoothed parallax values so the raycaster sees a continuously
        // interpolated offset — eliminates jumps from async timing gaps.
        final float parallaxRight   = portalData.smoothParallaxRight;
        final float parallaxUp      = portalData.smoothParallaxUp;
        final float parallaxForward = portalData.parallaxOffsetForward; // forward only used for sign

        CAPTURE_EXECUTOR.submit(() -> {
            NativeImage image = null;
            try {
                long deadlineNs = System.nanoTime() + RENDER_BUDGET_NS;
                image = renderPerspectiveView(levelSnap, destPos, yaw, pitch,
                                              resWSnap, resHSnap,
                                              halfWSnap, halfHSnap,
                                              parallaxRight, parallaxUp, parallaxForward,
                                              entityDots, deadlineNs);
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
                if      (e instanceof Player)       color = 0xFFFFFF00;
                else if (e instanceof Monster)      color = 0xFFFF3030;
                else if (e instanceof Animal)       color = 0xFF90EE90;
                else if (e instanceof LivingEntity) color = 0xFFFFFFFF;
                else continue;
                dots.add(new EntityDot(e.getX(), e.getY() + e.getEyeHeight(), e.getZ(), color));
            }
        } catch (Exception ignored) {}
        return dots;
    }

    // ── Renderer ───────────────────────────────────────────────────────────────

    private static NativeImage renderPerspectiveView(Level level, BlockPos eyePos,
                                                      float yawDeg, float pitchDeg,
                                                      int resW, int resH,
                                                      float portalHalfW, float portalHalfH,
                                                      float parallaxRight, float parallaxUp, float parallaxForward,
                                                      List<EntityDot> entityDots,
                                                      long deadlineNs) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, resW, resH, false);

        double yawRad = Math.toRadians(yawDeg);
        // Always render horizontally — ignore stored pitch so the baseline view
        // is always eye-level through the portal, not tilted up/down from when
        // the portal was linked.
        // pitchDeg is kept as a parameter for API compatibility but not used here.

        double fwdX = -Math.sin(yawRad);
        double fwdY =  0.0;
        double fwdZ =  Math.cos(yawRad);

        // Eye at portal destination center, at eye height, pushed back by EYE_FORWARD_OFFSET
        double eyeX = eyePos.getX() + 0.5 + fwdX * EYE_FORWARD_OFFSET;
        double eyeY = eyePos.getY() + 1.62 + fwdY * EYE_FORWARD_OFFSET;
        double eyeZ = eyePos.getZ() + 0.5 + fwdZ * EYE_FORWARD_OFFSET;

        double rightX =  Math.cos(yawRad);
        double rightZ =  Math.sin(yawRad);
        // rightY = 0 (always horizontal)

        // up is always world-up when pitch=0
        double upX = 0.0;
        double upY = 1.0;
        double upZ = 0.0;

        // ── Doorway / off-axis projection ─────────────────────────────────────
        // The eye is FIXED behind the portal centre at EYE_FORWARD_OFFSET depth.
        // The portal opening is the aperture. When the player moves laterally
        // relative to the portal, the visible "window" into the destination shifts
        // — you see more of one side, less of the other. This is exactly an
        // off-axis (asymmetric) frustum: eye stays put, the screen-plane centre moves.
        //
        // Implementation:
        //   The virtual screen plane sits at distance VIRTUAL_SCREEN_DIST in front
        //   of the eye. Its half-extents are (portalHalfW, portalHalfH) — so the
        //   frustum exactly spans the portal opening at that distance (no zoom in/out).
        //
        //   parallaxRight: player's lateral offset from portal centre in portal-space.
        //   Player moves right → they see more of the right side of the destination
        //   → screen centre shifts LEFT in NDC → we subtract from screenCentreX.
        //   Clamped to ±portalHalfW so you can't pan past the edge of the portal.
        //
        // Eye does NOT translate — no wall clipping.
        float scale = PortalLiveViewConfig.PARALLAX_SCALE.get().floatValue();

        float clampedRight = (float) Math.max(-portalHalfW, Math.min(portalHalfW, parallaxRight * scale));
        float clampedUp    = (float) Math.max(-portalHalfH, Math.min(portalHalfH, parallaxUp    * scale * 0.3f));

        // Shift of the screen centre in world units at the virtual screen plane.
        // Positive parallaxRight (player right of centre) → screen centre moves left → negative shift.
        double screenShiftRight = -clampedRight;
        double screenShiftUp    = -clampedUp;

        // Fixed virtual screen distance — determines FOV only, never changes.
        double virtualScreenDist = VIRTUAL_SCREEN_DIST;

        double halfFovW = portalHalfW / virtualScreenDist;
        double halfFovH = portalHalfH / virtualScreenDist;

        float[] depthBuf = new float[resW * resH];
        int skyAbgr = toABGR(computeSkyColor(0));

        for (int py = 0; py < resH; py++) {
            // Budget check once per scanline — avoids nanoTime() overhead per pixel.
            if (py > 0 && (py & 7) == 0 && System.nanoTime() > deadlineNs) {
                // Fill remaining rows with sky and bail — gives a partial but uploadable frame.
                for (int fy = py; fy < resH; fy++) {
                    for (int fx = 0; fx < resW; fx++) {
                        image.setPixelRGBA(fx, fy, skyAbgr);
                        depthBuf[fy * resW + fx] = MAX_RAY_DIST;
                    }
                }
                break;
            }
            for (int px = 0; px < resW; px++) {
                // NDC in [-1..1], then offset by screen shift (in portal-half-extent units).
                // screenShiftRight/Up are in world units at the virtual screen plane.
                double ndcX =  (2.0 * px + 1.0) / resW - 1.0 + screenShiftRight / portalHalfW;
                double ndcY = 1.0 - (2.0 * py + 1.0) / resH + screenShiftUp    / portalHalfH;

                double rdX = fwdX + rightX * ndcX * halfFovW + upX * ndcY * halfFovH;
                double rdY = fwdY +                            upY * ndcY * halfFovH;
                double rdZ = fwdZ + rightZ * ndcX * halfFovW + upZ * ndcY * halfFovH;

                double len = Math.sqrt(rdX*rdX + rdY*rdY + rdZ*rdZ);
                if (len < 1e-9) {
                    image.setPixelRGBA(px, py, toABGR(computeSkyColor(0)));
                    depthBuf[py * resW + px] = MAX_RAY_DIST;
                    continue;
                }
                rdX /= len; rdY /= len; rdZ /= len;

                int[] hitColor = new int[1];
                float dist = ddaRay(level, eyeX, eyeY, eyeZ, rdX, rdY, rdZ, hitColor);

                image.setPixelRGBA(px, py, toABGR(hitColor[0]));
                depthBuf[py * resW + px] = dist;
            }
        }

        // Entity dots
        for (EntityDot dot : entityDots) {
            double dx = dot.x() - eyeX;
            double dy = dot.y() - eyeY;
            double dz = dot.z() - eyeZ;
            double depth = dx * fwdX + dy * fwdY + dz * fwdZ;
            if (depth < 0.5) continue;
            double projX = dx * rightX              + dz * rightZ;
            double projY = dx * upX + dy * upY + dz * upZ;
            int screenX = (int)((projX / (depth * halfFovW) + 1.0) * 0.5 * resW);
            int screenY = (int)((1.0 - projY / (depth * halfFovH)) * 0.5 * resH);
            int dotSize = Math.max(1, (int)(4.0 - depth / 10.0));
            for (int dy2 = -dotSize; dy2 <= dotSize; dy2++) {
                for (int dx2 = -dotSize; dx2 <= dotSize; dx2++) {
                    int spx = screenX + dx2, spy = screenY + dy2;
                    if (spx < 0 || spx >= resW || spy < 0 || spy >= resH) continue;
                    if ((float) depth < depthBuf[spy * resW + spx] + 0.5f)
                        image.setPixelRGBA(spx, spy, toABGR(dot.argbColor()));
                }
            }
        }

        // No vertical flip — raycaster already writes py=0 as sky (top of image).
        // NativeImage in Minecraft is stored top-row-first matching GL_UNPACK_FLIP_ROW_ORDER.
        return image;
    }

    // ── DDA ────────────────────────────────────────────────────────────────────

    /** Alpha below this is treated as fully transparent (cutout miss — keep marching). */
    private static final int CUTOUT_ALPHA_THRESHOLD = 16;
    /** Alpha below this (but above cutout) is treated as translucent — blend with what's behind. */
    private static final int TRANSLUCENT_ALPHA_THRESHOLD = 200;

    private static float ddaRay(Level level,
                                 double ox, double oy, double oz,
                                 double dx, double dy, double dz,
                                 int[] hit) {
        int bx = (int) Math.floor(ox);
        int by = (int) Math.floor(oy);
        int bz = (int) Math.floor(oz);

        int sx = dx >= 0 ? 1 : -1;
        int sy = dy >= 0 ? 1 : -1;
        int sz = dz >= 0 ? 1 : -1;

        double tDeltaX = Math.abs(dx) < 1e-12 ? Double.MAX_VALUE : 1.0 / Math.abs(dx);
        double tDeltaY = Math.abs(dy) < 1e-12 ? Double.MAX_VALUE : 1.0 / Math.abs(dy);
        double tDeltaZ = Math.abs(dz) < 1e-12 ? Double.MAX_VALUE : 1.0 / Math.abs(dz);

        double tMaxX = Math.abs(dx) < 1e-12 ? Double.MAX_VALUE
                : (dx > 0 ? Math.floor(ox) + 1 - ox : ox - Math.floor(ox)) / Math.abs(dx);
        double tMaxY = Math.abs(dy) < 1e-12 ? Double.MAX_VALUE
                : (dy > 0 ? Math.floor(oy) + 1 - oy : oy - Math.floor(oy)) / Math.abs(dy);
        double tMaxZ = Math.abs(dz) < 1e-12 ? Double.MAX_VALUE
                : (dz > 0 ? Math.floor(oz) + 1 - oz : oz - Math.floor(oz)) / Math.abs(dz);

        double t = 0.0;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        // Accumulated translucent color (for glass stacking)
        int accumR = 0, accumG = 0, accumB = 0;
        float accumAlpha = 0f; // 0..1, how much of the final pixel is already determined

        while (t < MAX_RAY_DIST) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) { t = tMaxX; tMaxX += tDeltaX; bx += sx; }
            else if (tMaxY < tMaxZ)              { t = tMaxY; tMaxY += tDeltaY; by += sy; }
            else                                 { t = tMaxZ; tMaxZ += tDeltaZ; bz += sz; }

            if (t >= MAX_RAY_DIST) break;
            if (by < minY || by > maxY) break;

            BlockState state = level.getBlockState(new BlockPos(bx, by, bz));
            if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) continue;

            BlockPos bp = new BlockPos(bx, by, bz);
            QuadHit qh = intersectBlockQuads(state, level, bp,
                                              ox, oy, oz, dx, dy, dz, t);
            if (qh == null) continue;

            // Sample the exact pixel at the hit UV
            int baseColor = sampleHitPixel(qh, state, level, bp);
            int alpha = (baseColor >> 24) & 0xFF;

            // Cutout: fully transparent pixel -> skip this geometry, keep marching
            if (alpha < CUTOUT_ALPHA_THRESHOLD) continue;

            // AO from face normal Y component
            float ao = (qh.ny > 0.5) ? AO_TOP : (qh.ny < -0.5) ? AO_BOTTOM : AO_SIDE;

            float fogT = (float) Math.max(0.0, (qh.t - FOG_START) / (MAX_RAY_DIST - FOG_START));
            fogT = Math.min(1.0f, fogT * fogT);

            int shadedColor = lerpColor(shadeColor(baseColor, ao), computeSkyColor(dy), fogT);
            int sR = (shadedColor >> 16) & 0xFF;
            int sG = (shadedColor >>  8) & 0xFF;
            int sB =  shadedColor        & 0xFF;

            if (alpha >= TRANSLUCENT_ALPHA_THRESHOLD) {
                // Opaque hit: composite accumulated translucency over this surface
                float remainWeight = 1.0f - accumAlpha;
                int finalR = Math.min(255, (int)(accumR + remainWeight * sR));
                int finalG = Math.min(255, (int)(accumG + remainWeight * sG));
                int finalB = Math.min(255, (int)(accumB + remainWeight * sB));
                hit[0] = (0xFF << 24) | (finalR << 16) | (finalG << 8) | finalB;
                return (float) qh.t;
            } else {
                // Translucent hit (glass, ice): accumulate, keep marching for what's behind
                float sA = alpha / 255.0f;
                float layerContrib = sA * (1.0f - accumAlpha);
                accumR = Math.min(255, (int)(accumR + layerContrib * sR));
                accumG = Math.min(255, (int)(accumG + layerContrib * sG));
                accumB = Math.min(255, (int)(accumB + layerContrib * sB));
                accumAlpha = Math.min(1.0f, accumAlpha + layerContrib);
                if (accumAlpha > 0.98f) {
                    hit[0] = (0xFF << 24) | (accumR << 16) | (accumG << 8) | accumB;
                    return (float) qh.t;
                }
            }
        }

        // Ray escaped: composite accumulated translucency over sky
        int skyColor = computeSkyColor(dy);
        float remainWeight = 1.0f - accumAlpha;
        int skyR = (skyColor >> 16) & 0xFF;
        int skyG = (skyColor >>  8) & 0xFF;
        int skyB =  skyColor        & 0xFF;
        hit[0] = (0xFF << 24)
               | (Math.min(255, (int)(accumR + remainWeight * skyR)) << 16)
               | (Math.min(255, (int)(accumG + remainWeight * skyG)) <<  8)
               |  Math.min(255, (int)(accumB + remainWeight * skyB));
        return MAX_RAY_DIST;
    }

    // ── Quad intersection ──────────────────────────────────────────────────────

    /**
     * Result of a ray-quad or ray-AABB hit.
     *
     * For quad hits: sprite + atlas UVs (u0,v0)..(u3,v3) at the 4 quad vertices,
     * plus barycentric coords (bary1, bary2) so we can interpolate to the exact hit UV.
     * triIdx: 0 = triangle (v0,v1,v2), 1 = triangle (v0,v2,v3).
     *
     * For VoxelShape hits: sprite only, hitU/hitV = face-projected UV (0..1).
     * bary1=bary2=-1 signals "use hitU/hitV directly".
     */
    private record QuadHit(
            double t,
            double nx, double ny, double nz,
            TextureAtlasSprite sprite,
            int tintIndex,
            // Quad vertex atlas UVs  (only valid when bary1 >= 0)
            float u0, float v0,
            float u1, float v1,
            float u2, float v2,
            float u3, float v3,
            // Barycentric coords at hit point inside the two-triangle quad
            double bary1, double bary2,
            int triIdx,
            // Direct UV for VoxelShape fallback (bary1 < 0)
            float hitU, float hitV
    ) {}

    private static QuadHit intersectBlockQuads(BlockState state, Level level, BlockPos bp,
                                                double ox, double oy, double oz,
                                                double dx, double dy, double dz,
                                                double tEntry) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource rand = RandomSource.create(42L);

        List<BakedQuad> allQuads = new ArrayList<>();
        try {
            var model = mc.getModelManager().getBlockModelShaper().getBlockModel(state);
            allQuads.addAll(model.getQuads(state, null, rand));
            for (Direction face : Direction.values()) {
                allQuads.addAll(model.getQuads(state, face, rand));
            }
        } catch (Exception ignored) {}

        if (allQuads.isEmpty()) {
            return intersectVoxelShape(state, level, bp, ox, oy, oz, dx, dy, dz, tEntry);
        }

        // Block-local ray origin
        double lox = ox - bp.getX();
        double loy = oy - bp.getY();
        double loz = oz - bp.getZ();

        double tMin = Math.max(0.0, tEntry - 0.01);
        double tMax = tEntry + 1.8;

        double bestT   = Double.MAX_VALUE;
        double bestNX  = 0, bestNY = 1, bestNZ = 0;
        double bestB1  = 0, bestB2 = 0;
        int    bestTri = 0;
        float  bu0 = 0, bv0 = 0, bu1 = 0, bv1 = 0, bu2 = 0, bv2 = 0, bu3 = 0, bv3 = 0;
        TextureAtlasSprite bestSprite = null;
        int    bestTint = -1;

        for (BakedQuad quad : allQuads) {
            int[] verts = quad.getVertices();
            if (verts.length < 4 * VERTEX_STRIDE) continue;

            // Unpack positions and atlas UVs for all 4 vertices
            float x0 = Float.intBitsToFloat(verts[0]),  y0 = Float.intBitsToFloat(verts[1]),  z0 = Float.intBitsToFloat(verts[2]);
            float x1 = Float.intBitsToFloat(verts[8]),  y1 = Float.intBitsToFloat(verts[9]),  z1 = Float.intBitsToFloat(verts[10]);
            float x2 = Float.intBitsToFloat(verts[16]), y2 = Float.intBitsToFloat(verts[17]), z2 = Float.intBitsToFloat(verts[18]);
            float x3 = Float.intBitsToFloat(verts[24]), y3 = Float.intBitsToFloat(verts[25]), z3 = Float.intBitsToFloat(verts[26]);

            float qu0 = Float.intBitsToFloat(verts[4]),  qv0 = Float.intBitsToFloat(verts[5]);
            float qu1 = Float.intBitsToFloat(verts[12]), qv1 = Float.intBitsToFloat(verts[13]);
            float qu2 = Float.intBitsToFloat(verts[20]), qv2 = Float.intBitsToFloat(verts[21]);
            float qu3 = Float.intBitsToFloat(verts[28]), qv3 = Float.intBitsToFloat(verts[29]);

            // Triangle 0: v0,v1,v2
            double[] bary = new double[2];
            double t0 = mollerTrumbore(lox, loy, loz, dx, dy, dz,
                                        x0, y0, z0, x1, y1, z1, x2, y2, z2, bary);
            if (t0 >= tMin && t0 <= tMax && t0 < bestT) {
                bestT = t0; bestB1 = bary[0]; bestB2 = bary[1]; bestTri = 0;
                setNormal(x0,y0,z0, x1,y1,z1, x2,y2,z2, dx,dy,dz, new double[]{0,0,0}); // computed below
                bestSprite = quad.getSprite();
                bestTint = quad.isTinted() ? quad.getTintIndex() : -1;
                bu0=qu0; bv0=qv0; bu1=qu1; bv1=qv1; bu2=qu2; bv2=qv2; bu3=qu3; bv3=qv3;
                double[] n = computeNormal(x0,y0,z0, x1,y1,z1, x2,y2,z2, dx,dy,dz);
                bestNX=n[0]; bestNY=n[1]; bestNZ=n[2];
            }
            // Triangle 1: v0,v2,v3
            double t1 = mollerTrumbore(lox, loy, loz, dx, dy, dz,
                                        x0, y0, z0, x2, y2, z2, x3, y3, z3, bary);
            if (t1 >= tMin && t1 <= tMax && t1 < bestT) {
                bestT = t1; bestB1 = bary[0]; bestB2 = bary[1]; bestTri = 1;
                bestSprite = quad.getSprite();
                bestTint = quad.isTinted() ? quad.getTintIndex() : -1;
                bu0=qu0; bv0=qv0; bu1=qu1; bv1=qv1; bu2=qu2; bv2=qv2; bu3=qu3; bv3=qv3;
                double[] n = computeNormal(x0,y0,z0, x2,y2,z2, x3,y3,z3, dx,dy,dz);
                bestNX=n[0]; bestNY=n[1]; bestNZ=n[2];
            }
        }

        if (bestSprite == null) return null;
        return new QuadHit(bestT, bestNX, bestNY, bestNZ, bestSprite, bestTint,
                           bu0, bv0, bu1, bv1, bu2, bv2, bu3, bv3,
                           bestB1, bestB2, bestTri,
                           0f, 0f);
    }

    /** Compute and flip face normal toward ray origin. */
    private static double[] computeNormal(double ax, double ay, double az,
                                           double bx, double by, double bz,
                                           double cx, double cy, double cz,
                                           double rdx, double rdy, double rdz) {
        double e1x = bx-ax, e1y = by-ay, e1z = bz-az;
        double e2x = cx-ax, e2y = cy-ay, e2z = cz-az;
        double nx = e1y*e2z - e1z*e2y;
        double ny = e1z*e2x - e1x*e2z;
        double nz = e1x*e2y - e1y*e2x;
        double nl = Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (nl > 1e-9) { nx/=nl; ny/=nl; nz/=nl; }
        if (nx*rdx + ny*rdy + nz*rdz > 0) { nx=-nx; ny=-ny; nz=-nz; }
        return new double[]{nx, ny, nz};
    }

    // Unused overload kept for clarity
    private static void setNormal(double ax, double ay, double az,
                                   double bx, double by, double bz,
                                   double cx, double cy, double cz,
                                   double rdx, double rdy, double rdz,
                                   double[] out) {}

    // ── VoxelShape fallback (ENTITYBLOCK_ANIMATED) ─────────────────────────────

    private static QuadHit intersectVoxelShape(BlockState state, Level level, BlockPos bp,
                                                double ox, double oy, double oz,
                                                double dx, double dy, double dz,
                                                double tEntry) {
        VoxelShape shape;
        try { shape = state.getShape(level, bp); }
        catch (Exception e) { return null; }
        if (shape == null || shape.isEmpty()) return null;

        double lox = ox - bp.getX(), loy = oy - bp.getY(), loz = oz - bp.getZ();
        double tMin = Math.max(0.0, tEntry - 0.01);
        double tMax = tEntry + 1.8;

        double bestT = Double.MAX_VALUE;
        double bestNX = 0, bestNY = 1, bestNZ = 0;
        double bestHitLX = 0, bestHitLY = 0, bestHitLZ = 0;
        AABB bestBox = null;

        for (AABB box : shape.toAabbs()) {
            double txn = dx == 0 ? Double.NEGATIVE_INFINITY : (box.minX - lox) / dx;
            double txx = dx == 0 ? Double.POSITIVE_INFINITY : (box.maxX - lox) / dx;
            if (txn > txx) { double tmp=txn; txn=txx; txx=tmp; }

            double tyn = dy == 0 ? Double.NEGATIVE_INFINITY : (box.minY - loy) / dy;
            double tyx = dy == 0 ? Double.POSITIVE_INFINITY : (box.maxY - loy) / dy;
            if (tyn > tyx) { double tmp=tyn; tyn=tyx; tyx=tmp; }

            double tzn = dz == 0 ? Double.NEGATIVE_INFINITY : (box.minZ - loz) / dz;
            double tzx = dz == 0 ? Double.POSITIVE_INFINITY : (box.maxZ - loz) / dz;
            if (tzn > tzx) { double tmp=tzn; tzn=tzx; tzx=tmp; }

            double tEnter = Math.max(Math.max(txn, tyn), tzn);
            double tExit  = Math.min(Math.min(txx, tyx), tzx);
            if (tEnter > tExit + 1e-8 || tExit < tMin || tEnter > tMax) continue;

            double tHit = (tEnter >= tMin) ? tEnter : tExit;
            if (tHit < tMin || tHit > tMax || tHit >= bestT) continue;

            bestT   = tHit;
            bestBox = box;
            if      (tEnter == txn) { bestNX = -Math.signum(dx); bestNY = 0; bestNZ = 0; }
            else if (tEnter == tyn) { bestNX = 0; bestNY = -Math.signum(dy); bestNZ = 0; }
            else                    { bestNX = 0; bestNY = 0; bestNZ = -Math.signum(dz); }

            // Hit point in block-local coords
            bestHitLX = lox + dx * bestT;
            bestHitLY = loy + dy * bestT;
            bestHitLZ = loz + dz * bestT;
        }

        if (bestBox == null) return null;

        // Project hit point onto the hit face to get a 0..1 UV
        float hitU, hitV;
        if (Math.abs(bestNX) > 0.5) {
            // X-facing face → UV from Z,Y
            hitU = (float)((bestHitLZ - bestBox.minZ) / Math.max(1e-5, bestBox.maxZ - bestBox.minZ));
            hitV = (float)(1.0 - (bestHitLY - bestBox.minY) / Math.max(1e-5, bestBox.maxY - bestBox.minY));
            if (bestNX > 0) hitU = 1f - hitU; // flip for +X face
        } else if (Math.abs(bestNY) > 0.5) {
            // Y-facing face → UV from X,Z
            hitU = (float)((bestHitLX - bestBox.minX) / Math.max(1e-5, bestBox.maxX - bestBox.minX));
            hitV = (float)((bestHitLZ - bestBox.minZ) / Math.max(1e-5, bestBox.maxZ - bestBox.minZ));
            if (bestNY < 0) hitV = 1f - hitV; // flip for bottom face
        } else {
            // Z-facing face → UV from X,Y
            hitU = (float)((bestHitLX - bestBox.minX) / Math.max(1e-5, bestBox.maxX - bestBox.minX));
            hitV = (float)(1.0 - (bestHitLY - bestBox.minY) / Math.max(1e-5, bestBox.maxY - bestBox.minY));
            if (bestNZ < 0) hitU = 1f - hitU; // flip for -Z face
        }
        hitU = Math.max(0f, Math.min(1f, hitU));
        hitV = Math.max(0f, Math.min(1f, hitV));

        TextureAtlasSprite sprite = null;
        try {
            sprite = Minecraft.getInstance()
                    .getModelManager().getBlockModelShaper()
                    .getBlockModel(state).getParticleIcon();
        } catch (Exception ignored) {}

        // bary1 = -1 signals "use hitU/hitV directly"
        return new QuadHit(bestT, bestNX, bestNY, bestNZ, sprite, -1,
                           0,0, 0,0, 0,0, 0,0,
                           -1, 0, 0,
                           hitU, hitV);
    }

    // ── Möller–Trumbore ────────────────────────────────────────────────────────

    /**
     * Returns t at intersection, or -1. Writes barycentric (u,v) into bary[0..1].
     * Coordinates in block-local space.
     */
    private static double mollerTrumbore(double ox, double oy, double oz,
                                          double dx, double dy, double dz,
                                          double ax, double ay, double az,
                                          double bx, double by, double bz,
                                          double cx, double cy, double cz,
                                          double[] bary) {
        final double EPS = 1e-8;
        double e1x = bx-ax, e1y = by-ay, e1z = bz-az;
        double e2x = cx-ax, e2y = cy-ay, e2z = cz-az;

        double hx = dy*e2z - dz*e2y;
        double hy = dz*e2x - dx*e2z;
        double hz = dx*e2y - dy*e2x;

        double det = e1x*hx + e1y*hy + e1z*hz;
        if (Math.abs(det) < EPS) return -1.0;
        double invDet = 1.0 / det;

        double sx = ox-ax, sy = oy-ay, sz = oz-az;
        double u = (sx*hx + sy*hy + sz*hz) * invDet;
        if (u < 0.0 || u > 1.0) return -1.0;

        double qx = sy*e1z - sz*e1y;
        double qy = sz*e1x - sx*e1z;
        double qz = sx*e1y - sy*e1x;
        double v = (dx*qx + dy*qy + dz*qz) * invDet;
        if (v < 0.0 || u+v > 1.0) return -1.0;

        double t = (e2x*qx + e2y*qy + e2z*qz) * invDet;
        if (t <= EPS) return -1.0;

        bary[0] = u;
        bary[1] = v;
        return t;
    }

    // ── Pixel sampling ─────────────────────────────────────────────────────────

    /**
     * Given a QuadHit, computes the exact pixel color at the hit point.
     *
     * For quad hits: interpolates atlas UVs using barycentric coords, maps to
     * sprite-local pixel coords, reads the pixel directly.
     *
     * For VoxelShape hits (bary1 < 0): uses the face-projected hitU/hitV directly.
     */
    private static int sampleHitPixel(QuadHit qh, BlockState state, Level level, BlockPos pos) {
        TextureAtlasSprite sprite = qh.sprite();
        if (sprite == null) return fallbackColor(state);

        try {
            NativeImage img = sprite.contents().getOriginalImage();
            if (img == null) return fallbackColor(state);

            int imgW = img.getWidth();
            int imgH = img.getHeight();
            if (imgW <= 0 || imgH <= 0) return fallbackColor(state);

            float atlasU, atlasV;

            if (qh.bary1() < 0) {
                // VoxelShape fallback: use face-projected UV mapped into sprite bounds
                atlasU = sprite.getU0() + qh.hitU() * (sprite.getU1() - sprite.getU0());
                atlasV = sprite.getV0() + qh.hitV() * (sprite.getV1() - sprite.getV0());
            } else {
                // Barycentric interpolation of atlas UVs across the hit triangle
                double w0 = 1.0 - qh.bary1() - qh.bary2();
                double w1 = qh.bary1();
                double w2 = qh.bary2();

                if (qh.triIdx() == 0) {
                    // Triangle (v0, v1, v2)
                    atlasU = (float)(w0 * qh.u0() + w1 * qh.u1() + w2 * qh.u2());
                    atlasV = (float)(w0 * qh.v0() + w1 * qh.v1() + w2 * qh.v2());
                } else {
                    // Triangle (v0, v2, v3)
                    atlasU = (float)(w0 * qh.u0() + w1 * qh.u2() + w2 * qh.u3());
                    atlasV = (float)(w0 * qh.v0() + w1 * qh.v2() + w2 * qh.v3());
                }
            }

            // Atlas UV → sprite-local pixel coords
            // The sprite occupies [u0..u1] x [v0..v1] within the atlas (0..1 range).
            float su0 = sprite.getU0(), su1 = sprite.getU1();
            float sv0 = sprite.getV0(), sv1 = sprite.getV1();

            float spriteRelU = (atlasU - su0) / Math.max(1e-6f, su1 - su0);
            float spriteRelV = (atlasV - sv0) / Math.max(1e-6f, sv1 - sv0);

            // Sprite height may be taller than wide for animated sprites (frames stacked).
            // Use only the first frame: frame height = width pixels.
            int frameH = sprite.contents().width(); // square frame assumption
            spriteRelU = Math.max(0f, Math.min(1f, spriteRelU));
            spriteRelV = Math.max(0f, Math.min(1f, spriteRelV));

            int px = Math.min((int)(spriteRelU * imgW), imgW - 1);
            int py = Math.min((int)(spriteRelV * frameH), frameH - 1);

            // NativeImage ABGR layout: A=bits31-24, B=bits23-16, G=bits15-8, R=bits7-0
            int raw = img.getPixelRGBA(px, py);
            int a = (raw >> 24) & 0xFF;
            int r = (raw      ) & 0xFF;
            int g = (raw >>  8) & 0xFF;
            int b = (raw >> 16) & 0xFF;

            // Apply biome tint
            if (qh.tintIndex() >= 0) {
                int tint = Minecraft.getInstance()
                        .getBlockColors().getColor(state, level, pos, qh.tintIndex());
                if (tint != -1) {
                    r = r * ((tint >> 16) & 0xFF) / 255;
                    g = g * ((tint >>  8) & 0xFF) / 255;
                    b = b * ( tint        & 0xFF)  / 255;
                }
            }

            return (a << 24) | (r << 16) | (g << 8) | b;

        } catch (Exception e) {
            return fallbackColor(state);
        }
    }

    // ── Sky ────────────────────────────────────────────────────────────────────

    private static int computeSkyColor(double rdY) {
        float t = (float) Math.max(0.0, Math.min(1.0, rdY));
        int r = (int)(180 + t * (20  - 180));
        int g = (int)(210 + t * (60  - 210));
        int b = (int)(230 + t * (160 - 230));
        if (rdY < 0) {
            float u = (float) Math.min(1.0, -rdY * 2.0);
            r = (int)(r + u * (100 - r));
            g = (int)(g + u * ( 85 - g));
            b = (int)(b + u * ( 60 - b));
        }
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Color helpers ──────────────────────────────────────────────────────────

    private static int blend(int a, int b, float t) {
        return Math.min(255, (int)(a * (1f - t) + b * t));
    }

    private static int shadeColor(int argb, float shade) {
        return ((argb >> 24) & 0xFF) << 24
             | Math.min(255, (int)(((argb >> 16) & 0xFF) * shade)) << 16
             | Math.min(255, (int)(((argb >>  8) & 0xFF) * shade)) <<  8
             | Math.min(255, (int)(( argb        & 0xFF) * shade));
    }

    private static int lerpColor(int a, int b, float t) {
        return (0xFF << 24)
             | (int)((( a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF))) << 16
             | (int)((( a >>  8) & 0xFF) + t * (((b >>  8) & 0xFF) - ((a >>  8) & 0xFF))) <<  8
             | (int)(( a        & 0xFF)  + t * (( b        & 0xFF)  - ( a        & 0xFF)));
    }

    private static int toABGR(int argb) {
        return ((argb >> 24) & 0xFF) << 24
             | ( argb        & 0xFF) << 16
             | ((argb >>  8) & 0xFF) <<  8
             | ((argb >> 16) & 0xFF);
    }

    private static void flipVertical(NativeImage img, int w, int h) {
        for (int y = 0; y < h / 2; y++) {
            int my = h - 1 - y;
            for (int x = 0; x < w; x++) {
                int top = img.getPixelRGBA(x, y);
                img.setPixelRGBA(x, y,  img.getPixelRGBA(x, my));
                img.setPixelRGBA(x, my, top);
            }
        }
    }

    // ── Fallback color table ───────────────────────────────────────────────────

    private static int fallbackColor(BlockState state) {
        if (state == null || state.isAir()) return 0x00000000;
        int r = 110, g = 110, b = 110;
        try {
            if      (state.is(Blocks.GRASS_BLOCK))                                    { r= 95; g=159; b= 53; }
            else if (state.is(Blocks.DIRT)||state.is(Blocks.ROOTED_DIRT))             { r=139; g=101; b= 68; }
            else if (state.is(Blocks.STONE))                                          { r=128; g=128; b=128; }
            else if (state.is(Blocks.COBBLESTONE)||state.is(Blocks.MOSSY_COBBLESTONE)){ r=108; g=108; b=108; }
            else if (state.is(Blocks.OAK_LOG)||state.is(Blocks.BIRCH_LOG)
                  || state.is(Blocks.SPRUCE_LOG)||state.is(Blocks.JUNGLE_LOG)
                  || state.is(Blocks.ACACIA_LOG)||state.is(Blocks.DARK_OAK_LOG))       { r=101; g= 77; b= 47; }
            else if (state.is(Blocks.OAK_LEAVES)||state.is(Blocks.BIRCH_LEAVES)
                  || state.is(Blocks.SPRUCE_LEAVES)||state.is(Blocks.JUNGLE_LEAVES)
                  || state.is(Blocks.ACACIA_LEAVES)||state.is(Blocks.DARK_OAK_LEAVES)) { r= 59; g=101; b= 36; }
            else if (state.is(Blocks.WATER))                                          { r=  0; g=100; b=200; }
            else if (state.is(Blocks.SAND)||state.is(Blocks.SANDSTONE))               { r=238; g=203; b=139; }
            else if (state.is(Blocks.SNOW)||state.is(Blocks.SNOW_BLOCK))              { r=245; g=245; b=245; }
            else if (state.is(Blocks.NETHERRACK))                                     { r=114; g= 22; b= 22; }
            else if (state.is(Blocks.SOUL_SAND)||state.is(Blocks.SOUL_SOIL))          { r= 75; g= 61; b= 50; }
            else if (state.is(Blocks.END_STONE)||state.is(Blocks.END_STONE_BRICKS))   { r=220; g=213; b=150; }
            else if (state.is(Blocks.BEDROCK))                                         { r= 50; g= 50; b= 50; }
            else if (state.is(Blocks.GRAVEL))                                          { r=147; g=139; b=131; }
            else if (state.is(Blocks.DEEPSLATE)||state.is(Blocks.COBBLED_DEEPSLATE))  { r= 70; g= 68; b= 80; }
            else if (state.is(Blocks.OAK_PLANKS)||state.is(Blocks.SPRUCE_PLANKS)
                  || state.is(Blocks.BIRCH_PLANKS)||state.is(Blocks.JUNGLE_PLANKS))   { r=180; g=130; b= 70; }
            else if (state.is(Blocks.GLASS)||state.is(Blocks.GLASS_PANE))             { r=200; g=230; b=240; }
            else if (state.is(Blocks.LAVA))                                            { r=220; g= 90; b= 10; }
            else if (state.is(Blocks.GLOWSTONE))                                       { r=255; g=220; b=120; }
            else if (state.is(Blocks.NETHER_BRICKS))                                   { r= 80; g= 20; b= 20; }
            else if (state.is(Blocks.STONE_BRICKS)||state.is(Blocks.MOSSY_STONE_BRICKS)){ r=110;g=110; b=115; }
            else if (state.is(Blocks.BRICKS))                                          { r=160; g= 90; b= 70; }
            else if (state.is(Blocks.CLAY))                                            { r=160; g=160; b=175; }
            else if (state.is(Blocks.PACKED_ICE)||state.is(Blocks.ICE))               { r=160; g=195; b=220; }
            else if (state.is(Blocks.OBSIDIAN)||state.is(Blocks.CRYING_OBSIDIAN))      { r= 20; g= 15; b= 30; }
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
