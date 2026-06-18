package com.blackwell.cosmosportalsliveview.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enables the stencil buffer on Minecraft's main framebuffer.
 *
 * Vanilla never allocates a stencil attachment on the main RenderTarget/MainTarget.
 * Without it, any GL_STENCIL_TEST calls are no-ops and the stencil portal renderer
 * silently does nothing. This single injection passes useStencil=true when the
 * framebuffer is (re-)created, which attaches an 8-bit stencil to the depth buffer.
 *
 * Valkyrien Skies compatibility: VS does not touch MainTarget creation or the
 * framebuffer allocation path. This mixin is inert from VS's perspective.
 *
 * Shader mod compatibility: OptiFine/Iris replace the main framebuffer with their
 * own target after this fires, so those mods must independently enable stencil if
 * they want it. We detect at render time whether stencil is actually functional and
 * gracefully fall back to the raycaster if not.
 */
@Mixin(MainTarget.class)
public class MixinMainTarget {

    @Inject(method = "createFrameBuffer", at = @At("HEAD"))
    private void cosmosLiveView_enableStencil(int width, int height, CallbackInfo ci) {
        // The actual stencil-enable happens via the useDepthBuffer/useStencilBuffer
        // flags that Blaze3D reads during RenderTarget.createFrameBuffer().
        // We set the stencil flag on the target itself before the native GL call fires.
        // Cast is safe — we are mixed into MainTarget, so 'this' IS a MainTarget/RenderTarget.
        com.mojang.blaze3d.pipeline.RenderTarget self =
                (com.mojang.blaze3d.pipeline.RenderTarget) (Object) this;
        self.useStencil = true;
    }
}
