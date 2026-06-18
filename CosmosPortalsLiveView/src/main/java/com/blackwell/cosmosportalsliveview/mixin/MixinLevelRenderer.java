package com.blackwell.cosmosportalsliveview.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes LevelRenderer.renderChunkLayer() which is private in 1.20.1.
 *
 * Used by StencilPortalRenderer to re-draw solid/cutout geometry from a
 * translated camera position without spinning up a full second render pass.
 *
 * The @Invoker annotation generates a public accessor method that calls the
 * private target directly via Mixin's accessor mechanism — no reflection,
 * no access widener needed.
 */
@Mixin(LevelRenderer.class)
public interface MixinLevelRenderer {

    @Invoker("renderChunkLayer")
    void cosmosLiveView_renderChunkLayer(
            RenderType renderType,
            PoseStack poseStack,
            double camX, double camY, double camZ,
            Matrix4f projectionMatrix
    );
}
