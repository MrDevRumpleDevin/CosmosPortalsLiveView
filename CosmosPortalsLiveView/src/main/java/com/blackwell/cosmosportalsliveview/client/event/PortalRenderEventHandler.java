package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalViewData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import com.tcn.cosmoslibrary.common.lib.ComponentColour;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class PortalRenderEventHandler {

    /**
     * When a chunk loads, scan it for portal block entities to register.
     * This is the primary registration path — much cheaper than a full scan every tick.
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
            // Only render if the controlling dock has customColour == EMPTY ("Live View" mode).
            if (!isDockInLiveViewMode(level, data.portalPos)) continue;

            DynamicTexture texture = data.getTexture();
            if (texture == null || texture.getPixels() == null) continue;

            ResourceLocation texLoc = minecraft.getTextureManager().register(
                    "cosmosportals_liveview/portal_" + data.portalPos.asLong(),
                    texture
            );

            renderPortalWithTexture(poseStack, bufferSource, data, texLoc, camera);
        }

        bufferSource.endBatch();
    }

    /**
     * Scans adjacent blocks of {@code portalPos} for a dock.
     * Returns {@code true} if the dock's active custom colour is {@link ComponentColour#EMPTY},
     * which is the "Live View" sentinel in the colour cycle.
     */
    private static boolean isDockInLiveViewMode(Level level, BlockPos portalPos) {
        for (Direction dir : Direction.values()) {
            BlockEntity adjacent = level.getBlockEntity(portalPos.relative(dir));
            if (adjacent instanceof AbstractBlockEntityPortalDock) {
                AbstractBlockEntityPortalDock dock = (AbstractBlockEntityPortalDock) adjacent;
                return dock.getCustomColour() == ComponentColour.EMPTY;
            }
        }
        return false;
    }

    private static void renderPortalWithTexture(PoseStack poseStack,
                                                 MultiBufferSource bufferSource,
                                                 PortalViewData data,
                                                 ResourceLocation textureLocation,
                                                 Camera camera) {
        BlockPos portalPos = data.portalPos;

        poseStack.pushPose();

        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        poseStack.translate(
                portalPos.getX() + 0.5 - camX,
                portalPos.getY() + 0.5 - camY,
                portalPos.getZ() + 0.5 - camZ
        );

        RenderType renderType = RenderType.entityTranslucentCull(textureLocation);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);
        Matrix4f matrix = poseStack.last().pose();

        // Full-block quad facing +Z. Full-bright, white tint, slight transparency.
        consumer.vertex(matrix, -0.5F, -0.5F, 0.001F).color(255, 255, 255, 230).uv(0.0F, 1.0F).overlayCoords(0, 10).uv2(0xF000F0).normal(0, 0, 1).endVertex();
        consumer.vertex(matrix,  0.5F, -0.5F, 0.001F).color(255, 255, 255, 230).uv(1.0F, 1.0F).overlayCoords(0, 10).uv2(0xF000F0).normal(0, 0, 1).endVertex();
        consumer.vertex(matrix,  0.5F,  0.5F, 0.001F).color(255, 255, 255, 230).uv(1.0F, 0.0F).overlayCoords(0, 10).uv2(0xF000F0).normal(0, 0, 1).endVertex();
        consumer.vertex(matrix, -0.5F,  0.5F, 0.001F).color(255, 255, 255, 230).uv(0.0F, 0.0F).overlayCoords(0, 10).uv2(0xF000F0).normal(0, 0, 1).endVertex();

        poseStack.popPose();
    }
}
