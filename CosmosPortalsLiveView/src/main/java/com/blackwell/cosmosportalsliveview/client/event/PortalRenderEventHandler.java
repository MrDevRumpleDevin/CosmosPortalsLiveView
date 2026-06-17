package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.blackwell.cosmosportalsliveview.client.renderer.LocalizedChunkCapture;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalViewData;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import com.tcn.cosmosportals.core.block.BlockPortal;
import com.tcn.cosmosportals.core.blockentity.BlockEntityPortal;
import com.tcn.cosmosportals.core.blockentity.AbstractBlockEntityPortalDock;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class PortalRenderEventHandler {

    /** Only update portals within this many blocks of the player. */
    private static final double RENDER_DISTANCE_SQ = 64.0 * 64.0; // 64 block radius

    /**
     * Ticks between full mid-session scans for newly placed portal block entities.
     * We scan all loaded chunks every N ticks to catch portals placed without a chunk reload.
     */
    private static final int MID_SESSION_SCAN_INTERVAL = 40; // ~2 seconds
    private static int midSessionScanCountdown = MID_SESSION_SCAN_INTERVAL;

    /**
     * When the client level finishes loading, restore saved live-view state.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            // Defer slightly so Minecraft.getInstance().getSingleplayerServer() is ready.
            // We use a flag checked on first tick instead.
            pendingLoad = true;
        }
    }

    private static boolean pendingLoad = false;

    /**
     * When a chunk loads, scan it for portal block entities to register.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        try {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!(be instanceof BlockEntityPortal)) continue;
                BlockPos pos = be.getBlockPos();
                if (!PortalLiveViewManager.isTracked(pos)) {
                    PortalLiveViewManager.addPortal((BlockEntityPortal) be, pos);
                }
            }
        } catch (Exception ignored) {}
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) return;

        // Load saved state on first tick after world join
        if (pendingLoad) {
            pendingLoad = false;
            LiveViewState.load();
        }

        // Mid-session scan only — captures are now triggered from onLevelRender
        // so they fire every frame instead of every tick, making the view feel live.
        LocalPlayer player = minecraft.player;

        // ── Mid-session scan: detect portals placed since world join ──────────
        // ChunkEvent.Load only fires when a chunk first enters the client, not when
        // a portal BE is placed into an already-loaded chunk. We periodically walk
        // all loaded chunk block entities to catch newly placed portals.
        midSessionScanCountdown--;
        if (midSessionScanCountdown <= 0) {
            midSessionScanCountdown = MID_SESSION_SCAN_INTERVAL;
            scanLoadedChunksForNewPortals(level, player);
        }
    }

    /**
     * Walks every loaded chunk's block entity list, registers any untracked
     * {@link BlockEntityPortal} instances, and prunes stale portal tracking.
     */
    private static void scanLoadedChunksForNewPortals(Level level, LocalPlayer player) {
        if (level == null) return;
        try {
            // Iterate loaded chunks via ClientChunkCache. We access it through the
            // client chunk source, which has a public getChunk() we can use after
            // finding the range of loaded chunks around the player.
            net.minecraft.client.multiplayer.ClientLevel clientLevel =
                    (net.minecraft.client.multiplayer.ClientLevel) level;
            net.minecraft.client.multiplayer.ClientChunkCache chunkCache =
                    clientLevel.getChunkSource();

            // Determine the chunk range to scan (player ± render distance).
            int scanRadius = Math.min(Minecraft.getInstance().options.renderDistance().get(), 8);
            int playerChunkX = player != null ? (int) player.getX() >> 4 : 0;
            int playerChunkZ = player != null ? (int) player.getZ() >> 4 : 0;

            for (int cx = playerChunkX - scanRadius; cx <= playerChunkX + scanRadius; cx++) {
                for (int cz = playerChunkZ - scanRadius; cz <= playerChunkZ + scanRadius; cz++) {
                    net.minecraft.world.level.chunk.LevelChunk chunk =
                            chunkCache.getChunk(cx, cz, net.minecraft.world.level.chunk.ChunkStatus.FULL, false);
                    if (chunk == null) continue;

                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (!(be instanceof BlockEntityPortal portal)) continue;
                        BlockPos pos = be.getBlockPos();
                        if (!PortalLiveViewManager.isTracked(pos)) {
                            PortalLiveViewManager.addPortal(portal, pos);
                        }
                    }
                }
            }

            // Prune stale tracking: if a portal BE no longer exists at the tracked pos, remove it.
            for (BlockPos tracked : new java.util.ArrayList<>(
                    PortalLiveViewManager.getActivePortals().keySet())) {
                BlockEntity be = level.getBlockEntity(tracked);
                if (!(be instanceof BlockEntityPortal)) {
                    PortalLiveViewManager.removePortal(tracked);
                }
            }

        } catch (Exception ignored) {
            // Silently skip — chunk-load path covers the normal case.
        }
    }

    /**
     * When a block is placed (EntityPlaceEvent fires on both sides — we filter client),
     * immediately check if it is a portal block entity and register it.
     */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!event.getLevel().isClientSide()) return;
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;
        BlockPos pos = event.getPos();
        Level level = (Level) event.getLevel();

        // Schedule a deferred check (1-tick delay) so the BE is fully initialized.
        Minecraft.getInstance().execute(() -> {
            try {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof BlockEntityPortal portal && !PortalLiveViewManager.isTracked(pos)) {
                    PortalLiveViewManager.addPortal(portal, pos);
                }
            } catch (Exception ignored) {}
        });
    }

    /** Player eye height above feet — used to normalize parallaxOffsetUp so pey=0 at resting position. */
    private static final float PLAYER_EYE_HEIGHT = 1.62f;

    @SubscribeEvent
    public static void onLevelRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        long currentTime = System.currentTimeMillis();
        long captureInterval = PortalLiveViewConfig.CAPTURE_INTERVAL_MS.get();
        int portalsPerFrame = PortalLiveViewConfig.PORTALS_PER_FRAME.get();
        int capturedThisFrame = 0;

        for (PortalViewData data : PortalLiveViewManager.getActivePortals().values()) {
            // Proximity cull rendering too
            BlockPos pp = data.portalPos;
            double dx = pp.getX() + 0.5 - camPos.x;
            double dy = pp.getY() + 0.5 - camPos.y;
            double dz = pp.getZ() + 0.5 - camPos.z;
            if (dx*dx + dy*dy + dz*dz > RENDER_DISTANCE_SQ) continue;

            // Only render if the controlling dock has live view enabled
            BlockPos dockPos = findDockPos(level, data.portalPos);
            if (dockPos == null) continue;
            if (!LiveViewState.isEnabled(dockPos)) continue;

            // ── Smooth parallax offsets toward raw values each frame ──────────
            float alpha = PortalViewData.PARALLAX_SMOOTH;
            data.smoothParallaxRight   = data.smoothParallaxRight   + alpha * (data.parallaxOffsetRight   - data.smoothParallaxRight);
            data.smoothParallaxUp      = data.smoothParallaxUp      + alpha * (data.parallaxOffsetUp      - data.smoothParallaxUp);
            data.smoothParallaxForward = data.smoothParallaxForward + alpha * (data.parallaxOffsetForward - data.smoothParallaxForward);

            // ── Sync destination hole offsets from LiveViewState ──────────────
            // The wand writes to LiveViewState; we mirror it into PortalViewData
            // so the raycaster sees the latest value without needing extra threading.
            data.destOffsetRight   = LiveViewState.getDestOffsetRight(dockPos);
            data.destOffsetUp      = LiveViewState.getDestOffsetUp(dockPos);
            data.destOffsetForward = LiveViewState.getDestOffsetForward(dockPos);

            // ── Per-frame capture trigger ──────────────────────────────────────
            if (capturedThisFrame < portalsPerFrame
                    && data.shouldUpdateCapture(currentTime, captureInterval)) {
                LocalizedChunkCapture.captureAsync(data, level);
                capturedThisFrame++;
            }

            DynamicTexture texture = data.getTexture();
            if (texture == null) continue;

            // Include version in key so resolution changes force a new GL texture object.
            ResourceLocation texLoc = minecraft.getTextureManager().register(
                    "cosmosportals_liveview/portal_" + data.portalPos.asLong() + "_v" + data.getTextureVersion(),
                    texture
            );

            renderPortalFrame(poseStack, bufferSource, data, texLoc, camera, level, dockPos);

            // ── Wireframe: draw dest hole outline at destination coords ────────
            if (LiveViewState.isWireframeEnabled(dockPos) && data.destPos != null) {
                renderDestWireframe(poseStack, bufferSource, data, camera, level);
            }
        }

        bufferSource.endBatch();
    }

    /**
     * Finds the dock block adjacent to this portal block (any direction).
     * Returns the dock's BlockPos, or null if none found.
     */
    public static BlockPos findDockPos(Level level, BlockPos portalPos) {
        Set<BlockPos> frame = findConnectedPortalBlocks(level, portalPos, 64);
        for (BlockPos fp : frame) {
            for (Direction dir : Direction.values()) {
                BlockEntity adjacent = level.getBlockEntity(fp.relative(dir));
                if (adjacent instanceof AbstractBlockEntityPortalDock) {
                    return adjacent.getBlockPos();
                }
            }
        }
        return null;
    }

    /**
     * Flood-fill to collect all connected CosmosPortal portal blocks starting from {@code origin}.
     */
    public static Set<BlockPos> findConnectedPortalBlocks(Level level, BlockPos origin, int maxBlocks) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (visited.contains(next)) continue;
                BlockState state = level.getBlockState(next);
                if (state.getBlock() instanceof BlockPortal) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    /**
     * Computes the bounding box of the portal frame and renders a single full-size quad.
     *
     * UV orientation:
     *   U = 0 → left edge of raycasted image, U = 1 → right edge
     *   V = 0 → top of image, V = 1 → bottom
     *
     * The quad is pushed 0.5 blocks outward along the face normal so it renders
     * in FRONT of CosmosPortals' translucent colour layer, not sandwiched inside it.
     * Both sides are pushed outward independently so the view is correct from either
     * direction.
     *
     * UV handedness:
     *   axis=X (face normal ±Z): raycaster right-vector points +X when player faces
     *     south, so U=0 maps to -X (left). Quad: BL at x=-halfW → U=0, BR at +halfW → U=1. ✓
     *
     *   axis=Z (face normal ±X): raycaster right-vector points +Z when player faces
     *     west (yaw=90°). Image U=0 = left = -right = -Z. So U=0 → z=-halfW.
     *     Front face (+X side): BL z=-halfW→U=0, BR z=+halfW→U=1.
     *     Back face (-X side): BL z=+halfW→U=0, BR z=-halfW→U=1.
     */
    private static void renderPortalFrame(PoseStack poseStack,
                                           MultiBufferSource bufferSource,
                                           PortalViewData data,
                                           ResourceLocation textureLocation,
                                           Camera camera,
                                           Level level,
                                           BlockPos dockPos) {
        Set<BlockPos> frameBlocks = findConnectedPortalBlocks(level, data.portalPos, 64);
        if (frameBlocks.isEmpty()) return;

        // Compute bounding box
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos bp : frameBlocks) {
            minX = Math.min(minX, bp.getX());
            minY = Math.min(minY, bp.getY());
            minZ = Math.min(minZ, bp.getZ());
            maxX = Math.max(maxX, bp.getX());
            maxY = Math.max(maxY, bp.getY());
            maxZ = Math.max(maxZ, bp.getZ());
        }

        // Determine portal orientation.
        // axis=X: portal blocks span along X, zero span along Z (face normal ±Z, player walks N/S)
        // axis=Z: portal blocks span along Z, zero span along X (face normal ±X, player walks E/W)
        //
        // We vote across ALL portal blocks: count how many unique X values vs unique Z values exist.
        // A flat N/S-facing portal has 1 unique X but many unique Z → spanZ wins.
        // A flat E/W-facing portal has many unique X but 1 unique Z → spanX wins.
        // This is robust against frame corners that might inflate the raw min/max span.
        java.util.Set<Integer> uniqueXSet = new java.util.HashSet<>();
        java.util.Set<Integer> uniqueZSet = new java.util.HashSet<>();
        for (BlockPos bp : frameBlocks) {
            uniqueXSet.add(bp.getX());
            uniqueZSet.add(bp.getZ());
        }
        int uniqueX = uniqueXSet.size();
        int uniqueZ = uniqueZSet.size();

        boolean isXAxis;
        if (uniqueX != uniqueZ) {
            // More unique X values → portal spans X → face normal ±Z (axis=X)
            isXAxis = uniqueX > uniqueZ;
        } else {
            // Ambiguous (single-block-wide portal) — read blockstate "axis" property by name.
            // BlockStateProperties.AXIS is Minecraft's own instance; CosmosPortals registers
            // its own EnumProperty with the same name but a different object, so hasProperty()
            // returns false even though the property exists. Look it up by name instead.
            BlockState portalState = level.getBlockState(data.portalPos);
            isXAxis = false;
            try {
                for (net.minecraft.world.level.block.state.properties.Property<?> prop
                        : portalState.getProperties()) {
                    if (prop.getName().equals("axis")) {
                        Object val = portalState.getValue(prop);
                        if (val instanceof net.minecraft.core.Direction.Axis a) {
                            isXAxis = (a == net.minecraft.core.Direction.Axis.X);
                        } else {
                            // value is a string in some mod versions
                            isXAxis = val.toString().equalsIgnoreCase("x");
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        double centerX = (minX + maxX) / 2.0 + 0.5;
        double centerY = (minY + maxY) / 2.0 + 0.5;
        double centerZ = (minZ + maxZ) / 2.0 + 0.5;

        float halfH = (maxY - minY) / 2.0f + 0.5f;

        // Compute halfW for whichever axis applies, then store both on the data object
        // so the raycaster can derive a per-portal FOV (window effect).
        {
            float hw = isXAxis ? (maxX - minX) / 2.0f + 0.5f : (maxZ - minZ) / 2.0f + 0.5f;
            data.portalHalfW = hw;
            data.portalHalfH = halfH;
            data.portalBottomY = (float) minY; // world Y of lowest portal block floor
        }

        // Base offset: -0.52 pushes the quad inward (toward the player's side of the portal,
        // in front of CosmosPortals' color layer). Negative = toward player based on the
        // previous test where +0.52 went away from the player.
        // The per-dock offset (cycled with sneak+right-click wand) is added on top.
        // ── Parallax: project camera offset onto portal-local axes ─────────────
        // Project camera offset onto portal-local axes.
        //   axis=X (face normal ±Z): right=+X, up=+Y, forward=+Z
        //   axis=Z (face normal ±X): right=+Z, up=+Y, forward=+X
        //
        // parallaxOffsetForward is stored SIGNED so the raycaster knows which side
        // of the portal the player is on.  The right-axis direction must be flipped
        // for the back face (negative forward) to keep "move right → see left" correct
        // from both sides.
        //
        // parallaxOffsetRight stores the lateral offset in portal-local space,
        // already sign-corrected: positive = player to the viewer's right.
        // When forward < 0 the viewer is on the back face, so "right" in world space
        // is the opposite portal-local direction — we negate it.
        //
        // parallaxOffsetUp: eye height normalized so standing at floor in front of a
        // floor-to-ceiling portal gives ~0.  We subtract PLAYER_EYE_HEIGHT.
        {
            Vec3 camPos3 = camera.getPosition();
            double pex = camPos3.x - centerX;
            // Eye height above the portal floor.
            // Use feet Y + constant eye height instead of raw camera Y to strip head-bob,
            // which would otherwise oscillate parallaxUp and cause the bottom rows to flicker.
            LocalPlayer lp = Minecraft.getInstance().player;
            double stableEyeY = (lp != null)
                    ? lp.getY() + PLAYER_EYE_HEIGHT
                    : camPos3.y;
            double pey = stableEyeY - minY;
            double pez = camPos3.z - centerZ;

            if (isXAxis) {
                // forward = pez (signed): +Z side or -Z side
                double fwd = pez;
                // Negate right so moving right at source shifts the eye LEFT at dest,
                // producing the correct "peek through hole" parallax (moving right → see left side).
                double right = (fwd >= 0) ? -pex : pex;
                data.parallaxOffsetRight   = (float) right;
                data.parallaxOffsetUp      = (float) pey;
                data.parallaxOffsetForward = (float) fwd;
            } else {
                // forward = pex (signed): +X side or -X side
                double fwd = pex;
                double right = (fwd >= 0) ? -pez : pez;
                data.parallaxOffsetRight   = (float) right;
                data.parallaxOffsetUp      = (float) pey;
                data.parallaxOffsetForward = (float) fwd;
            }
        }

        final float FACE_OFFSET = 0.0f + LiveViewState.getOffset(dockPos);

        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        poseStack.pushPose();
        poseStack.translate(centerX - camX, centerY - camY, centerZ - camZ);

        RenderType renderType = RenderType.entityTranslucentCull(textureLocation);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);
        Matrix4f matrix = poseStack.last().pose();

        if (isXAxis) {
            // ── AXIS=X: portal plane is XY, face normal is ±Z ──────────────────
            // Horizontal span runs along X.  Player looks ±Z to see the portal.
            // Raycaster right-vector is ±X when player faces ±Z (yaw=0/180).
            // U=0 → -X side (left), U=1 → +X side (right). Vertex at -halfW → U=0. ✓
            float halfW = (maxX - minX) / 2.0f + 0.5f;
            float fz    = FACE_OFFSET; // push front face toward +Z viewer

            // Front face (viewer on +Z side, looking north toward -Z)
            // U flipped horizontally: -halfW→U=1, +halfW→U=0
            consumer.vertex(matrix, -halfW, -halfH,  fz).color(255,255,255,255).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix,  halfW, -halfH,  fz).color(255,255,255,255).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix,  halfW,  halfH,  fz).color(255,255,255,255).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix, -halfW,  halfH,  fz).color(255,255,255,255).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            // Back face (viewer on -Z side, looking south toward +Z)
            // Mirror U relative to front: +halfW→U=1, -halfW→U=0
            consumer.vertex(matrix,  halfW, -halfH, -fz).color(255,255,255,255).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix, -halfW, -halfH, -fz).color(255,255,255,255).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix, -halfW,  halfH, -fz).color(255,255,255,255).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix,  halfW,  halfH, -fz).color(255,255,255,255).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
        } else {
            // ── AXIS=Z: portal plane is ZY, face normal is ±X ──────────────────
            // Horizontal span runs along Z. Player looks ±X to see the portal.
            // U=0 → +Z side, U=1 → -Z side (matches working commit 25d5433).
            float halfW = (maxZ - minZ) / 2.0f + 0.5f;
            float fx    = FACE_OFFSET;

            // Front face (+X side)
            // U flipped horizontally: +halfW→U=1, -halfW→U=0
            consumer.vertex(matrix,  fx, -halfH,  halfW).color(255,255,255,255).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  fx, -halfH, -halfW).color(255,255,255,255).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  fx,  halfH, -halfW).color(255,255,255,255).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  fx,  halfH,  halfW).color(255,255,255,255).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            // Back face (-X side) — mirror U relative to front: -halfW→U=1, +halfW→U=0
            consumer.vertex(matrix, -fx, -halfH, -halfW).color(255,255,255,255).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -fx, -halfH,  halfW).color(255,255,255,255).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -fx,  halfH,  halfW).color(255,255,255,255).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -fx,  halfH, -halfW).color(255,255,255,255).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
        }

        poseStack.popPose();
    }

    /**
     * Renders a wireframe box at the destination "hole" — the region of world space
     * that the live-view eye looks out from.
     *
     * The box is centred at:
     *   destPos + 0.5 (block centre) + fwd*EYE_FORWARD_OFFSET + destOffsets
     * and has dimensions  portalHalfW × portalHalfH (half-extents).
     *
     * Drawn with RenderType.LINES so it requires no texture and ignores depth (always visible).
     */
    private static void renderDestWireframe(PoseStack poseStack,
                                             MultiBufferSource.BufferSource bufferSource,
                                             PortalViewData data,
                                             Camera camera,
                                             Level level) {
        // Reconstruct dest eye centre (same math as LocalizedChunkCapture)
        double yawRad = Math.toRadians(data.destYaw);
        double fwdX = -Math.sin(yawRad);
        double fwdZ =  Math.cos(yawRad);
        double rightX =  Math.cos(yawRad);
        double rightZ =  Math.sin(yawRad);

        final float EYE_FORWARD_OFFSET = -0.5f;

        double cx = data.destPos.getX() + 0.5
                + fwdX * (EYE_FORWARD_OFFSET + data.destOffsetForward)
                + rightX * data.destOffsetRight;
        double cy = data.portalBottomY + data.portalHalfH + data.destOffsetUp;
        double cz = data.destPos.getZ() + 0.5
                + fwdZ * (EYE_FORWARD_OFFSET + data.destOffsetForward)
                + rightZ * data.destOffsetRight;

        float hw = data.portalHalfW;
        float hh = data.portalHalfH;

        // The box right-axis is the portal right-vector (in world XZ), up is Y.
        // We express the 8 corners in world space.
        double[] cornersX = new double[8];
        double[] cornersY = new double[8];
        double[] cornersZ = new double[8];
        int i = 0;
        for (float sx : new float[]{-hw, hw}) {
            for (float sy : new float[]{-hh, hh}) {
                cornersX[i] = cx + rightX * sx;
                cornersY[i] = cy + sy;
                cornersZ[i] = cz + rightZ * sx;
                i++;
            }
        }
        // corner index layout:
        //  0: left-bottom,  1: left-top,  2: right-bottom,  3: right-top
        // Front (fwd) and back are the same box (it's flat along fwd), but we draw
        // two parallel face rectangles offset ±0.05 along fwd to give the wireframe depth.
        final double THICK = 0.05;
        double[][] faces = {
            // front offset
            { cx + fwdX*THICK, cy, cz + fwdZ*THICK },
            // back offset
            { cx - fwdX*THICK, cy, cz - fwdZ*THICK }
        };

        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        // Wireframe colour: bright cyan, full alpha
        float r = 0f, g = 1f, b = 1f, a = 1f;

        poseStack.pushPose();
        poseStack.translate(cx - camX, cy - camY, cz - camZ);

        RenderType linesType = RenderType.lines();
        VertexConsumer vc = bufferSource.getBuffer(linesType);
        Matrix4f mat = poseStack.last().pose();
        org.joml.Matrix3f nor = poseStack.last().normal();

        // Draw two rect outlines (front slab face, back slab face) and 4 connecting edges.
        // We work in pose-translated space: cx,cy,cz is origin, corners relative to it.
        // Relative corner coords:
        float[] rx = { (float)(rightX * (-hw)), (float)(rightX * hw), (float)(rightX * hw), (float)(rightX * (-hw)) };
        float[] ry = { -hh, -hh, hh, hh };
        float[] rz = { (float)(rightZ * (-hw)), (float)(rightZ * hw), (float)(rightZ * hw), (float)(rightZ * (-hw)) };

        for (double fOff : new double[]{ THICK, -THICK }) {
            float fx = (float)(fwdX * fOff);
            float fz2 = (float)(fwdZ * fOff);
            // 4 edges of this rect
            for (int e = 0; e < 4; e++) {
                int next = (e + 1) % 4;
                vc.vertex(mat, rx[e] + fx, ry[e], rz[e] + fz2).color(r, g, b, a).normal(nor, 0f, 1f, 0f).endVertex();
                vc.vertex(mat, rx[next] + fx, ry[next], rz[next] + fz2).color(r, g, b, a).normal(nor, 0f, 1f, 0f).endVertex();
            }
        }
        // 4 connecting depth edges
        for (int e = 0; e < 4; e++) {
            vc.vertex(mat, rx[e] + (float)(fwdX * THICK), ry[e], rz[e] + (float)(fwdZ * THICK)).color(r, g, b, a).normal(nor, 0f, 1f, 0f).endVertex();
            vc.vertex(mat, rx[e] - (float)(fwdX * THICK), ry[e], rz[e] - (float)(fwdZ * THICK)).color(r, g, b, a).normal(nor, 0f, 1f, 0f).endVertex();
        }

        bufferSource.endBatch(linesType);
        poseStack.popPose();
    }
}
