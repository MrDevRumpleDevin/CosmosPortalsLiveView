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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Raycaster-based perspective renderer for portal live views.
 *
 * Rendering pipeline:
 *  - Amanatides-Woo DDA walks the block grid
 *  - On each non-air cell, Möller–Trumbore ray-quad intersection tests the actual
 *    BakedModel geometry — slabs, stairs, doors, fences all have correct silhouettes
 *  - Hit quad's sprite is sampled for color; tint (grass/leaves/water) applied
 *  - Face-normal AO: top 100%, sides 78%, bottom 60%
 *  - Distance fog blends toward sky at range
 *  - 1× ray per pixel (SSAA disabled while geometry work is in progress)
 *
 * Threading: entity snapshot runs on main thread. All raycasting + model reads run
 * on CAPTURE_EXECUTOR — BakedModel/TextureAtlas are read-only after bake, safe.
 */
@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    private static final float  VIRTUAL_SCREEN_DIST  = 2.0f;
    private static final float  EYE_FORWARD_OFFSET   = -0.5f;
    private static final int    MAX_RAY_DIST         = 48;
    private static final double ENTITY_SCAN_RADIUS   = 32.0;

    /** Face AO multipliers */
    private static final float AO_TOP    = 1.00f;
    private static final float AO_SIDE   = 0.78f;
    private static final float AO_BOTTOM = 0.60f;

    /** Distance fog: full color until FOG_START, then blends to sky */
    private static final float FOG_START = 20.0f;

    /**
     * Quad vertex stride in the int[] returned by BakedQuad.getVertices().
     * Layout per vertex: [x_float, y_float, z_float, normal_int, u_float, v_float, misc, misc]
     */
    private static final int VERTEX_STRIDE = 8;

    private static final ExecutorService CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CosmosLiveView-Capture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

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

        // Entity snapshot must run on main thread
        final List<EntityDot> entityDots = snapshotEntities(sampleLevel, portalData.destPos);

        final Level    levelSnap  = sampleLevel;
        final int      resWSnap   = resW;
        final int      resHSnap   = resH;
        final float    yaw        = portalData.destYaw;
        final float    pitch      = portalData.destPitch;
        final BlockPos destPos    = portalData.destPos;
        final float    halfWSnap  = halfW;
        final float    halfHSnap  = halfH;

        CAPTURE_EXECUTOR.submit(() -> {
            NativeImage image = null;
            try {
                image = renderPerspectiveView(levelSnap, destPos, yaw, pitch,
                                              resWSnap, resHSnap,
                                              halfWSnap, halfHSnap,
                                              entityDots);
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
                                                      List<EntityDot> entityDots) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, resW, resH, false);

        double yawRad   = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);

        double fwdX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fwdY = -Math.sin(pitchRad);
        double fwdZ =  Math.cos(yawRad) * Math.cos(pitchRad);

        double eyeX = eyePos.getX() + 0.5 + fwdX * EYE_FORWARD_OFFSET;
        double eyeY = eyePos.getY() + 1.62 + fwdY * EYE_FORWARD_OFFSET;
        double eyeZ = eyePos.getZ() + 0.5 + fwdZ * EYE_FORWARD_OFFSET;

        double rightX =  Math.cos(yawRad);
        double rightZ =  Math.sin(yawRad);

        // up = right × fwd
        double upX = -rightZ * fwdY;
        double upY =  rightZ * fwdX - rightX * fwdZ;
        double upZ =  rightX * fwdY;

        double halfFovW = portalHalfW / VIRTUAL_SCREEN_DIST;
        double halfFovH = portalHalfH / VIRTUAL_SCREEN_DIST;

        float[] depthBuf = new float[resW * resH];

        for (int py = 0; py < resH; py++) {
            for (int px = 0; px < resW; px++) {
                double ndcX =  (2.0 * px + 1.0) / resW - 1.0;
                double ndcY = 1.0 - (2.0 * py + 1.0) / resH;

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
        if (!entityDots.isEmpty()) {
            for (EntityDot dot : entityDots) {
                double dx = dot.x() - eyeX;
                double dy = dot.y() - eyeY;
                double dz = dot.z() - eyeZ;

                double depth = dx * fwdX + dy * fwdY + dz * fwdZ;
                if (depth < 0.5) continue;

                double projX = dx * rightX              + dz * rightZ;
                double projY = dx * upX + dy * upY + dz * upZ;

                double ndcEX = projX / (depth * halfFovW);
                double ndcEY = projY / (depth * halfFovH);

                int screenX = (int)((ndcEX + 1.0) * 0.5 * resW);
                int screenY = (int)((1.0 - ndcEY) * 0.5 * resH);

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
        }

        flipVertical(image, resW, resH);
        return image;
    }

    // ── Amanatides-Woo DDA + quad intersection ────────────────────────────────

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

        double tDeltaX = Math.abs(dx) < 1e-12 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = Math.abs(dy) < 1e-12 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = Math.abs(dz) < 1e-12 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        double tMaxX = Math.abs(dx) < 1e-12 ? Double.MAX_VALUE
                : (dx > 0 ? (Math.floor(ox) + 1 - ox) : (ox - Math.floor(ox))) / Math.abs(dx);
        double tMaxY = Math.abs(dy) < 1e-12 ? Double.MAX_VALUE
                : (dy > 0 ? (Math.floor(oy) + 1 - oy) : (oy - Math.floor(oy))) / Math.abs(dy);
        double tMaxZ = Math.abs(dz) < 1e-12 ? Double.MAX_VALUE
                : (dz > 0 ? (Math.floor(oz) + 1 - oz) : (oz - Math.floor(oz))) / Math.abs(dz);

        double t = 0.0;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        while (t < MAX_RAY_DIST) {
            // Step to next cell boundary
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX; tMaxX += tDeltaX; bx += sx;
            } else if (tMaxY < tMaxZ) {
                t = tMaxY; tMaxY += tDeltaY; by += sy;
            } else {
                t = tMaxZ; tMaxZ += tDeltaZ; bz += sz;
            }

            if (t >= MAX_RAY_DIST) break;
            if (by < minY || by > maxY) break;

            BlockState state = level.getBlockState(new BlockPos(bx, by, bz));
            if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) continue;
            // ENTITYBLOCK_ANIMATED (beds, chests, signs, etc.) have no BakedModel quads —
            // handled via VoxelShape fallback inside intersectBlockQuads.

            // Intersect ray against this block's actual quad geometry.
            // tEntry = how far along the ray we entered this cell.
            double tEntry = t;
            QuadHit qh = intersectBlockQuads(state, level, new BlockPos(bx, by, bz),
                                              ox, oy, oz, dx, dy, dz, tEntry);
            if (qh == null) continue; // ray passed through geometry gap (open door, slab air half, etc.)

            // Sample color from the hit quad's sprite
            int baseColor = sampleSpriteColor(qh.sprite, qh.tintIndex, state, level,
                                              new BlockPos(bx, by, bz));

            // Transparency: glass panes, ice, etc. — blend with sky
            int alpha = (baseColor >> 24) & 0xFF;
            if (alpha < 200) {
                int skyCol = computeSkyColor(dy);
                float blendT = 1.0f - (alpha / 255.0f);
                int r = blend((baseColor >> 16) & 0xFF, (skyCol >> 16) & 0xFF, blendT);
                int g = blend((baseColor >>  8) & 0xFF, (skyCol >>  8) & 0xFF, blendT);
                int b = blend( baseColor        & 0xFF,  skyCol        & 0xFF, blendT);
                hit[0] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                return (float) qh.t;
            }

            // AO by face normal
            float ao;
            double ny = qh.ny;
            if      (ny >  0.5) ao = AO_TOP;
            else if (ny < -0.5) ao = AO_BOTTOM;
            else                ao = AO_SIDE;

            // Fog
            float fogT = (float) Math.max(0.0, (qh.t - FOG_START) / (MAX_RAY_DIST - FOG_START));
            fogT = Math.min(1.0f, fogT * fogT);

            int shaded = shadeColor(baseColor, ao);
            hit[0] = lerpColor(shaded, computeSkyColor(dy), fogT);
            return (float) qh.t;
        }

        hit[0] = computeSkyColor(dy);
        return MAX_RAY_DIST;
    }

    // ── Quad geometry intersection ─────────────────────────────────────────────

    /** Result of a successful ray-quad intersection inside a block cell. */
    private record QuadHit(double t, double nx, double ny, double nz,
                            TextureAtlasSprite sprite, int tintIndex) {}

    /**
     * Tests the ray against every BakedQuad in the block's model (all 6 directional
     * faces + unculled quads). Returns the closest hit inside [tEntry, tEntry+√3],
     * or null if the ray passes through without hitting any geometry.
     *
     * For blocks with RenderShape.ENTITYBLOCK_ANIMATED (beds, chests, signs, etc.)
     * BakedModel returns no quads — falls back to VoxelShape AABB intersection so
     * these blocks are never invisible.
     *
     * Vertex layout in BakedQuad.getVertices() (stride 8 ints per vertex):
     *   [0] x as float bits, [1] y as float bits, [2] z as float bits,
     *   [3] normal packed, [4] u as float bits, [5] v as float bits, [6-7] misc
     */
    private static QuadHit intersectBlockQuads(BlockState state, Level level, BlockPos bp,
                                                double ox, double oy, double oz,
                                                double dx, double dy, double dz,
                                                double tEntry) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource rand = RandomSource.create(42L);

        // Collect all quads: unculled (null face) + each of 6 directional faces
        List<BakedQuad> allQuads = new ArrayList<>();
        try {
            var model = mc.getModelManager().getBlockModelShaper().getBlockModel(state);
            // Unculled quads (most geometry is here for non-full-cube blocks)
            allQuads.addAll(model.getQuads(state, null, rand));
            for (Direction face : Direction.values()) {
                allQuads.addAll(model.getQuads(state, face, rand));
            }
        } catch (Exception e) {
            // Model unavailable — try VoxelShape fallback
        }

        // ENTITYBLOCK_ANIMATED blocks (beds, chests, signs, bells, lecterns…) expose
        // no quads via BakedModel. Fall back to their VoxelShape collision boxes so
        // they are never invisible in the live view.
        if (allQuads.isEmpty()) {
            return intersectVoxelShape(state, level, bp, ox, oy, oz, dx, dy, dz, tEntry);
        }

        // Block-local ray origin: shift so block corner = (0,0,0)
        double lox = ox - bp.getX();
        double loy = oy - bp.getY();
        double loz = oz - bp.getZ();

        double bestT  = Double.MAX_VALUE;
        double bestNX = 0, bestNY = 1, bestNZ = 0;
        TextureAtlasSprite bestSprite  = null;
        int                bestTint    = -1;

        // Search window: ray must hit within the cell's diagonal (max √3 ≈ 1.732)
        double tMin = Math.max(0.0, tEntry - 0.01); // slight back-step for rays starting inside
        double tMax = tEntry + 1.8;

        for (BakedQuad quad : allQuads) {
            int[] verts = quad.getVertices();
            if (verts.length < 4 * VERTEX_STRIDE) continue;

            // Unpack 4 vertices
            float x0 = Float.intBitsToFloat(verts[0]);
            float y0 = Float.intBitsToFloat(verts[1]);
            float z0 = Float.intBitsToFloat(verts[2]);
            float x1 = Float.intBitsToFloat(verts[VERTEX_STRIDE]);
            float y1 = Float.intBitsToFloat(verts[VERTEX_STRIDE + 1]);
            float z1 = Float.intBitsToFloat(verts[VERTEX_STRIDE + 2]);
            float x2 = Float.intBitsToFloat(verts[2 * VERTEX_STRIDE]);
            float y2 = Float.intBitsToFloat(verts[2 * VERTEX_STRIDE + 1]);
            float z2 = Float.intBitsToFloat(verts[2 * VERTEX_STRIDE + 2]);
            float x3 = Float.intBitsToFloat(verts[3 * VERTEX_STRIDE]);
            float y3 = Float.intBitsToFloat(verts[3 * VERTEX_STRIDE + 1]);
            float z3 = Float.intBitsToFloat(verts[3 * VERTEX_STRIDE + 2]);

            // Test two triangles of the quad: (v0,v1,v2) and (v0,v2,v3)
            double t1 = mollerTrumbore(lox, loy, loz, dx, dy, dz,
                                        x0, y0, z0, x1, y1, z1, x2, y2, z2);
            double t2 = mollerTrumbore(lox, loy, loz, dx, dy, dz,
                                        x0, y0, z0, x2, y2, z2, x3, y3, z3);

            for (double tHit : new double[]{t1, t2}) {
                if (tHit < tMin || tHit > tMax) continue;
                if (tHit < bestT) {
                    bestT = tHit;
                    // Compute face normal from edge vectors
                    double e1x = x1 - x0, e1y = y1 - y0, e1z = z1 - z0;
                    double e2x = x2 - x0, e2y = y2 - y0, e2z = z2 - z0;
                    double nx = e1y * e2z - e1z * e2y;
                    double ny = e1z * e2x - e1x * e2z;
                    double nz = e1x * e2y - e1y * e2x;
                    double nl = Math.sqrt(nx*nx + ny*ny + nz*nz);
                    if (nl > 1e-9) { nx /= nl; ny /= nl; nz /= nl; }
                    // Flip normal toward ray origin
                    if (nx * dx + ny * dy + nz * dz > 0) { nx = -nx; ny = -ny; nz = -nz; }
                    bestNX = nx; bestNY = ny; bestNZ = nz;
                    bestSprite = quad.getSprite();
                    bestTint   = quad.isTinted() ? quad.getTintIndex() : -1;
                }
            }
        }

        if (bestSprite == null) return null;
        return new QuadHit(bestT, bestNX, bestNY, bestNZ, bestSprite, bestTint);
    }

    /**
     * Fallback for blocks with no BakedModel quads (ENTITYBLOCK_ANIMATED: beds, chests,
     * signs, bells, lecterns, etc.). Ray-tests against the block's VoxelShape AABB boxes.
     * Uses the particle icon for color — not per-face texture, but at least correct
     * shape and color rather than invisible.
     */
    private static QuadHit intersectVoxelShape(BlockState state, Level level, BlockPos bp,
                                                double ox, double oy, double oz,
                                                double dx, double dy, double dz,
                                                double tEntry) {
        VoxelShape shape;
        try {
            // getShape gives the visual/collision outline; good enough for rendering
            shape = state.getShape(level, bp);
        } catch (Exception e) {
            return null;
        }

        if (shape == null || shape.isEmpty()) return null;

        // Block-local ray origin
        double lox = ox - bp.getX();
        double loy = oy - bp.getY();
        double loz = oz - bp.getZ();

        double bestT  = Double.MAX_VALUE;
        double bestNX = 0, bestNY = 1, bestNZ = 0;

        double tMin = Math.max(0.0, tEntry - 0.01);
        double tMax = tEntry + 1.8;

        for (AABB box : shape.toAabbs()) {
            // Ray-AABB slab test
            double txMin = (dx == 0) ? Double.NEGATIVE_INFINITY : (box.minX - lox) / dx;
            double txMax = (dx == 0) ? Double.POSITIVE_INFINITY : (box.maxX - lox) / dx;
            if (txMin > txMax) { double tmp = txMin; txMin = txMax; txMax = tmp; }

            double tyMin = (dy == 0) ? Double.NEGATIVE_INFINITY : (box.minY - loy) / dy;
            double tyMax = (dy == 0) ? Double.POSITIVE_INFINITY : (box.maxY - loy) / dy;
            if (tyMin > tyMax) { double tmp = tyMin; tyMin = tyMax; tyMax = tmp; }

            double tzMin = (dz == 0) ? Double.NEGATIVE_INFINITY : (box.minZ - loz) / dz;
            double tzMax = (dz == 0) ? Double.POSITIVE_INFINITY : (box.maxZ - loz) / dz;
            if (tzMin > tzMax) { double tmp = tzMin; tzMin = tzMax; tzMax = tmp; }

            double tEnterBox = Math.max(Math.max(txMin, tyMin), tzMin);
            double tExitBox  = Math.min(Math.min(txMax, tyMax), tzMax);

            if (tEnterBox > tExitBox + 1e-8) continue; // miss
            if (tExitBox  < tMin)            continue; // behind
            if (tEnterBox > tMax)            continue; // too far

            double tHit = (tEnterBox >= tMin) ? tEnterBox : tExitBox;
            if (tHit < tMin || tHit > tMax)  continue;
            if (tHit >= bestT)               continue;

            bestT = tHit;

            // Determine which face was hit by which slab was the tEnterBox constraint
            if (tEnterBox == txMin)      { bestNX = -Math.signum(dx); bestNY = 0; bestNZ = 0; }
            else if (tEnterBox == tyMin) { bestNX = 0; bestNY = -Math.signum(dy); bestNZ = 0; }
            else                         { bestNX = 0; bestNY = 0; bestNZ = -Math.signum(dz); }
        }

        if (bestT == Double.MAX_VALUE) return null;

        // Use particle icon for color (no per-face quad data available)
        TextureAtlasSprite sprite = null;
        try {
            sprite = Minecraft.getInstance()
                    .getModelManager().getBlockModelShaper()
                    .getBlockModel(state).getParticleIcon();
        } catch (Exception ignored) {}

        return new QuadHit(bestT, bestNX, bestNY, bestNZ, sprite, -1);
    }

    /**
     * Möller–Trumbore ray-triangle intersection.
     * Returns t along the ray, or -1 if no intersection.
     * All coordinates in block-local space (origin at block corner).
     */
    private static double mollerTrumbore(double ox, double oy, double oz,
                                          double dx, double dy, double dz,
                                          double ax, double ay, double az,
                                          double bx, double by, double bz,
                                          double cx, double cy, double cz) {
        final double EPS = 1e-8;

        double e1x = bx - ax, e1y = by - ay, e1z = bz - az;
        double e2x = cx - ax, e2y = cy - ay, e2z = cz - az;

        // h = d × e2
        double hx = dy * e2z - dz * e2y;
        double hy = dz * e2x - dx * e2z;
        double hz = dx * e2y - dy * e2x;

        double det = e1x * hx + e1y * hy + e1z * hz;
        if (Math.abs(det) < EPS) return -1.0; // parallel

        double invDet = 1.0 / det;

        // s = o - a
        double sx = ox - ax, sy = oy - ay, sz = oz - az;

        double u = (sx * hx + sy * hy + sz * hz) * invDet;
        if (u < 0.0 || u > 1.0) return -1.0;

        // q = s × e1
        double qx = sy * e1z - sz * e1y;
        double qy = sz * e1x - sx * e1z;
        double qz = sx * e1y - sy * e1x;

        double v = (dx * qx + dy * qy + dz * qz) * invDet;
        if (v < 0.0 || u + v > 1.0) return -1.0;

        double t = (e2x * qx + e2y * qy + e2z * qz) * invDet;
        return t > EPS ? t : -1.0;
    }

    // ── Sprite color sampling ──────────────────────────────────────────────────

    /**
     * Samples the average color from a sprite, applying block tint (grass/leaves/water).
     * Uses an 8×8 grid sample for good accuracy.
     */
    private static int sampleSpriteColor(TextureAtlasSprite sprite, int tintIndex,
                                          BlockState state, Level level, BlockPos pos) {
        if (sprite == null) return fallbackColor(state);
        try {
            int spriteW = Math.max(1, sprite.contents().width());
            int spriteH = Math.max(1, sprite.contents().height());

            int gridN = Math.min(8, spriteW);
            int gridM = Math.min(8, spriteH);

            int rSum = 0, gSum = 0, bSum = 0, aSum = 0, n = 0;
            int total = gridN * gridM;

            for (int gi = 0; gi < gridN; gi++) {
                for (int gj = 0; gj < gridM; gj++) {
                    int sx = Math.min((int)((gi + 0.5) * spriteW / gridN), spriteW - 1);
                    int sy = Math.min((int)((gj + 0.5) * spriteH / gridM), spriteH - 1);
                    // NativeImage pixel layout: ABGR (A=bits31-24, B=bits23-16, G=bits15-8, R=bits7-0)
                    int px = sprite.contents().getOriginalImage().getPixelRGBA(sx, sy);
                    int pa = (px >> 24) & 0xFF;
                    aSum += pa;
                    if (pa < 10) continue;
                    rSum += (px      ) & 0xFF;  // R
                    gSum += (px >>  8) & 0xFF;  // G
                    bSum += (px >> 16) & 0xFF;  // B
                    n++;
                }
            }

            if (n == 0) return fallbackColor(state);

            int r = rSum / n, g = gSum / n, b = bSum / n;
            int avgAlpha = aSum / total; // averaged over all samples including transparent

            // Apply biome tint (grass, leaves, water, etc.)
            if (tintIndex >= 0) {
                Minecraft mc = Minecraft.getInstance();
                int tint = mc.getBlockColors().getColor(state, level, pos, tintIndex);
                if (tint != -1) {
                    r = (r * ((tint >> 16) & 0xFF)) / 255;
                    g = (g * ((tint >>  8) & 0xFF)) / 255;
                    b = (b * ( tint        & 0xFF)) / 255;
                }
            }

            return (avgAlpha << 24) | (r << 16) | (g << 8) | b;
        } catch (Exception e) {
            return fallbackColor(state);
        }
    }

    // ── Sky ────────────────────────────────────────────────────────────────────

    private static int computeSkyColor(double rdY) {
        float t = (float) Math.max(0.0, Math.min(1.0, rdY));
        int hr = 180, hg = 210, hb = 230;
        int zr =  20, zg =  60, zb = 160;
        int r = (int)(hr + t * (zr - hr));
        int g = (int)(hg + t * (zg - hg));
        int b = (int)(hb + t * (zb - hb));
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

    /** 0xAARRGGBB → 0xAABBGGRR (NativeImage stores pixels ABGR in memory). */
    private static int toABGR(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static void flipVertical(NativeImage image, int width, int height) {
        for (int y = 0; y < height / 2; y++) {
            int mirrorY = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int top    = image.getPixelRGBA(x, y);
                int bottom = image.getPixelRGBA(x, mirrorY);
                image.setPixelRGBA(x, y,       bottom);
                image.setPixelRGBA(x, mirrorY, top);
            }
        }
    }

    // ── Fallback color table ───────────────────────────────────────────────────

    private static int fallbackColor(BlockState state) {
        if (state == null || state.isAir()) return 0x00000000;
        int r = 110, g = 110, b = 110;
        try {
            if      (state.is(Blocks.GRASS_BLOCK))                                    { r= 95; g=159; b= 53; }
            else if (state.is(Blocks.DIRT) || state.is(Blocks.ROOTED_DIRT))           { r=139; g=101; b= 68; }
            else if (state.is(Blocks.STONE))                                          { r=128; g=128; b=128; }
            else if (state.is(Blocks.COBBLESTONE)||state.is(Blocks.MOSSY_COBBLESTONE)){ r=108; g=108; b=108; }
            else if (state.is(Blocks.OAK_LOG)   ||state.is(Blocks.BIRCH_LOG)
                  || state.is(Blocks.SPRUCE_LOG) ||state.is(Blocks.JUNGLE_LOG)
                  || state.is(Blocks.ACACIA_LOG) ||state.is(Blocks.DARK_OAK_LOG))     { r=101; g= 77; b= 47; }
            else if (state.is(Blocks.OAK_LEAVES)||state.is(Blocks.BIRCH_LEAVES)
                  || state.is(Blocks.SPRUCE_LEAVES)||state.is(Blocks.JUNGLE_LEAVES)
                  || state.is(Blocks.ACACIA_LEAVES)||state.is(Blocks.DARK_OAK_LEAVES)){ r= 59; g=101; b= 36; }
            else if (state.is(Blocks.WATER))                                          { r=  0; g=100; b=200; }
            else if (state.is(Blocks.SAND)||state.is(Blocks.SANDSTONE))              { r=238; g=203; b=139; }
            else if (state.is(Blocks.SNOW)||state.is(Blocks.SNOW_BLOCK))             { r=245; g=245; b=245; }
            else if (state.is(Blocks.NETHERRACK))                                     { r=114; g= 22; b= 22; }
            else if (state.is(Blocks.SOUL_SAND)||state.is(Blocks.SOUL_SOIL))          { r= 75; g= 61; b= 50; }
            else if (state.is(Blocks.END_STONE)||state.is(Blocks.END_STONE_BRICKS))   { r=220; g=213; b=150; }
            else if (state.is(Blocks.BEDROCK))                                         { r= 50; g= 50; b= 50; }
            else if (state.is(Blocks.GRAVEL))                                          { r=147; g=139; b=131; }
            else if (state.is(Blocks.DEEPSLATE)||state.is(Blocks.COBBLED_DEEPSLATE))  { r= 70; g= 68; b= 80; }
            else if (state.is(Blocks.OAK_PLANKS) ||state.is(Blocks.SPRUCE_PLANKS)
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
