package com.blackwell.cosmosportalsliveview.mixin;

import com.blackwell.cosmosportalsliveview.client.renderer.FboCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into GameRenderer.renderLevel() at TAIL — after the full main frame
 * has been rendered — to trigger FBO captures for portal live views.
 *
 * This is intentionally minimal: we touch nothing in the render pipeline itself,
 * no entity transforms, no chunk render interception, no recursion into renderLevel.
 * Valkyrien Skies is safe because VS patches entity/ship transforms earlier in the
 * frame; by TAIL those are already applied and we're just scheduling our own
 * separate FBO pass.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void onRenderLevelTail(float partialTick, long finishNanoTime,
                                   PoseStack poseStack, CallbackInfo ci) {
        // FBO approach disabled — GL state corruption not resolved.
        // Re-enable once FboCapture is fixed.
        // FboCapture.onRenderLevelTail(partialTick);
    }
}
