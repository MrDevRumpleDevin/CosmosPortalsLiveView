package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalViewData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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

        long captureInterval = PortalLiveViewConfig.CAPTURE_INTERVAL_MS.get();
        int  portalsPerFrame = PortalLiveViewConfig.PORTALS_PER_FRAME.get();

        // Pass player position for proximity culling
        LocalPlayer player = minecraft.player;
        Vec3 playerPos = (player != null) ? player.position() : null;

        PortalLiveViewManager.updatePortalsIncremental(level, captureInterval, portalsPerFrame, playerPos, RENDER_DISTANCE_SQ);

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

            DynamicTexture texture = data.getTexture();
            if (texture == null) continue;

            // Include version in key so resolution changes force a new GL texture object.
            ResourceLocation texLoc = minecraft.getTextureManager().register(
                    "cosmosportals_liveview/portal_" + data.portalPos.asLong() + "_v" + data.getTextureVersion(),
                    texture
            );

            renderPortalFrame(poseStack, bufferSource, data, texLoc, camera, level);
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
     * UV orientation: U goes left→right, V goes bottom→top (matching the raycaster output).
     */
    private static void renderPortalFrame(PoseStack poseStack,
                                           MultiBufferSource bufferSource,
                                           PortalViewData data,
                                           ResourceLocation textureLocation,
                                           Camera camera,
                                           Level level) {
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

        BlockState portalState = level.getBlockState(data.portalPos);
        boolean isXAxis = false;
        try {
            if (portalState.hasProperty(BlockStateProperties.AXIS)) {
                net.minecraft.core.Direction.Axis axis = portalState.getValue(BlockStateProperties.AXIS);
                isXAxis = (axis == net.minecraft.core.Direction.Axis.X);
            }
        } catch (Exception ignored) {}

        double centerX = (minX + maxX) / 2.0 + 0.5;
        double centerY = (minY + maxY) / 2.0 + 0.5;
        double centerZ = (minZ + maxZ) / 2.0 + 0.5;

        float halfH = (maxY - minY) / 2.0f + 0.5f;

        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        poseStack.pushPose();
        poseStack.translate(centerX - camX, centerY - camY, centerZ - camZ);

        RenderType renderType = RenderType.entityTranslucentCull(textureLocation);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);
        Matrix4f matrix = poseStack.last().pose();

        // UV convention: (0,0) = top-left of texture, (1,1) = bottom-right.
        // Vertex winding: bottom-left → bottom-right → top-right → top-left (counter-clockwise when viewed from front).
        // Y axis: -halfH = bottom of portal, +halfH = top.
        // So bottom vertices get V=1 (texture bottom), top vertices get V=0 (texture top).
        // This matches the raycaster where py=0 is the top row of the image.

        if (isXAxis) {
            // AXIS=X: portal opening along X, visible face normal is ±Z.
            float halfW = (maxX - minX) / 2.0f + 0.5f;

            // Front face (facing +Z / south) — normal points towards viewer from south
            // BL, BR, TR, TL with corrected UVs: bottom=V1, top=V0
            consumer.vertex(matrix, -halfW, -halfH,  0.001f).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix,  halfW, -halfH,  0.001f).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix,  halfW,  halfH,  0.001f).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix, -halfW,  halfH,  0.001f).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            // Back face (facing -Z / north)
            consumer.vertex(matrix,  halfW, -halfH, -0.001f).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix, -halfW, -halfH, -0.001f).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix, -halfW,  halfH, -0.001f).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix,  halfW,  halfH, -0.001f).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
        } else {
            // AXIS=Z: portal opening along Z, visible face normal is ±X.
            float halfW = (maxZ - minZ) / 2.0f + 0.5f;

            // Front face (facing +X / east)
            consumer.vertex(matrix,  0.001f, -halfH,  halfW).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  0.001f, -halfH, -halfW).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  0.001f,  halfH, -halfW).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  0.001f,  halfH,  halfW).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            // Back face (facing -X / west)
            consumer.vertex(matrix, -0.001f, -halfH, -halfW).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -0.001f, -halfH,  halfW).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -0.001f,  halfH,  halfW).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -0.001f,  halfH, -halfW).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
        }

        poseStack.popPose();
    }
}
