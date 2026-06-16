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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class PortalRenderEventHandler {

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

        long captureInterval = PortalLiveViewConfig.CAPTURE_INTERVAL_MS.get();
        int  portalsPerFrame = PortalLiveViewConfig.PORTALS_PER_FRAME.get();

        PortalLiveViewManager.updatePortalsIncremental(level, captureInterval, portalsPerFrame);
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

        for (PortalViewData data : PortalLiveViewManager.getActivePortals().values()) {
            // Only render if the controlling dock has live view enabled
            BlockPos dockPos = findDockPos(level, data.portalPos);
            if (dockPos == null) continue;
            if (!LiveViewState.isEnabled(dockPos)) continue;

            DynamicTexture texture = data.getTexture();
            if (texture == null || texture.getPixels() == null) continue;

            ResourceLocation texLoc = minecraft.getTextureManager().register(
                    "cosmosportals_liveview/portal_" + data.portalPos.asLong(),
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
    private static BlockPos findDockPos(Level level, BlockPos portalPos) {
        // Search the full portal shape first (find all connected portal blocks),
        // then check adjacency of the whole frame for a dock.
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
     * Limits search to {@code maxBlocks} to avoid runaway on broken frames.
     */
    private static Set<BlockPos> findConnectedPortalBlocks(Level level, BlockPos origin, int maxBlocks) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            BlockPos current = queue.poll();
            // Only spread horizontally and vertically (portal blocks form a flat frame)
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
     * The quad is oriented based on the portal block's AXIS block state property.
     */
    private static void renderPortalFrame(PoseStack poseStack,
                                           MultiBufferSource bufferSource,
                                           PortalViewData data,
                                           ResourceLocation textureLocation,
                                           Camera camera,
                                           Level level) {
        // Find all portal blocks in this frame
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

        // Determine axis from the block state of our portal block
        BlockState portalState = level.getBlockState(data.portalPos);
        boolean isXAxis = false;
        try {
            if (portalState.hasProperty(BlockStateProperties.AXIS)) {
                net.minecraft.core.Direction.Axis axis = portalState.getValue(BlockStateProperties.AXIS);
                isXAxis = (axis == net.minecraft.core.Direction.Axis.X);
            }
        } catch (Exception ignored) {}

        // Center of the frame in world space
        double centerX = (minX + maxX) / 2.0 + 0.5;
        double centerY = (minY + maxY) / 2.0 + 0.5;
        double centerZ = (minZ + maxZ) / 2.0 + 0.5;

        // Half-extents: the portal fills its blocks edge-to-edge
        float halfW; // horizontal half-width of the quad
        float halfH = (maxY - minY) / 2.0f + 0.5f; // vertical half-height

        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        poseStack.pushPose();
        poseStack.translate(centerX - camX, centerY - camY, centerZ - camZ);

        RenderType renderType = RenderType.entityTranslucentCull(textureLocation);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);
        Matrix4f matrix = poseStack.last().pose();

        if (isXAxis) {
            // Portal faces East-West (X axis) — the flat face is the X plane, normal along Z
            // But CosmosPortals AXIS=X means the portal opening is along X (players walk through Z),
            // so the visible face is perpendicular to Z.
            // Actually in BlockPortal: X_AXIS_AABB is (0,0,6,16,16,10) — thin slab along Z at center.
            // So AXIS=X portals face the Z direction (you see them from north/south).
            halfW = (maxX - minX) / 2.0f + 0.5f;
            // Quad in the XY plane (facing ±Z), offset 0.001 towards viewer
            // Front face (facing -Z / north)
            consumer.vertex(matrix, -halfW, -halfH,  0.001f).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix,  halfW, -halfH,  0.001f).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix,  halfW,  halfH,  0.001f).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            consumer.vertex(matrix, -halfW,  halfH,  0.001f).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,1).endVertex();
            // Back face (facing +Z / south)
            consumer.vertex(matrix,  halfW, -halfH, -0.001f).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix, -halfW, -halfH, -0.001f).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix, -halfW,  halfH, -0.001f).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
            consumer.vertex(matrix,  halfW,  halfH, -0.001f).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(0,0,-1).endVertex();
        } else {
            // Portal faces North-South (Z axis) — visible face is the Z plane, normal along X.
            // AXIS=Z means thin slab in the Z direction; face visible from east/west (±X).
            halfW = (maxZ - minZ) / 2.0f + 0.5f;
            // Quad in the ZY plane (facing ±X)
            // Front face (facing -X / west)
            consumer.vertex(matrix,  0.001f, -halfH, -halfW).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  0.001f, -halfH,  halfW).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  0.001f,  halfH,  halfW).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            consumer.vertex(matrix,  0.001f,  halfH, -halfW).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(1,0,0).endVertex();
            // Back face (facing +X / east)
            consumer.vertex(matrix, -0.001f, -halfH,  halfW).color(255,255,255,230).uv(0f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -0.001f, -halfH, -halfW).color(255,255,255,230).uv(1f,1f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -0.001f,  halfH, -halfW).color(255,255,255,230).uv(1f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
            consumer.vertex(matrix, -0.001f,  halfH,  halfW).color(255,255,255,230).uv(0f,0f).overlayCoords(0,10).uv2(0xF000F0).normal(-1,0,0).endVertex();
        }

        poseStack.popPose();
    }
}
