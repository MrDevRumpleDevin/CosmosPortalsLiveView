package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalViewData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class PortalRenderEventHandler {
    
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;
        
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        
        long captureInterval = PortalLiveViewConfig.CAPTURE_INTERVAL_MS.get();
        int portalsPerFrame = PortalLiveViewConfig.PORTALS_PER_FRAME.get();
        
        PortalLiveViewManager.updatePortalsIncremental(minecraft.level, captureInterval, portalsPerFrame);
    }
    
    @SubscribeEvent
    public static void onLevelRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;
        
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        
        for (PortalViewData data : PortalLiveViewManager.getActivePortals().values()) {
            DynamicTexture texture = data.getTexture();
            if (texture != null) {
                renderPortalWithTexture(poseStack, bufferSource, data, texture);
            }
        }
    }
    
    private static void renderPortalWithTexture(PoseStack poseStack, MultiBufferSource bufferSource,
                                                  PortalViewData data, DynamicTexture texture) {
        BlockPos portalPos = data.portalPos;
        
        poseStack.pushPose();
        poseStack.translate(portalPos.getX() + 0.5, portalPos.getY() + 0.5, portalPos.getZ() + 0.5);
        
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.solid());
        Matrix4f matrix = poseStack.last().pose();
        
        consumer.vertex(matrix, -0.5F, -0.5F, 0.001F).color(255, 255, 255, 255).uv(0, 0).endVertex();
        consumer.vertex(matrix,  0.5F, -0.5F, 0.001F).color(255, 255, 255, 255).uv(1, 0).endVertex();
        consumer.vertex(matrix,  0.5F,  0.5F, 0.001F).color(255, 255, 255, 255).uv(1, 1).endVertex();
        consumer.vertex(matrix, -0.5F,  0.5F, 0.001F).color(255, 255, 255, 255).uv(0, 1).endVertex();
        
        poseStack.popPose();
    }
}
