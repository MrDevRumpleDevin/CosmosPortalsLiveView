package com.blackwell.cosmosportalsliveview.mixin;

import com.blackwell.cosmosportalsliveview.ducks.IEFrameBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;

/**
 * Injects the IEFrameBuffer duck interface onto RenderTarget.
 *
 * When isStencilBufferEnabled is true, intercepts the two GL calls inside
 * createBuffers() that set up the depth attachment and upgrades them to a
 * combined depth+stencil format (GL_DEPTH24_STENCIL8 / GL_DEPTH_STENCIL),
 * exactly matching Immersive Portals' approach for 1.20.1.
 *
 * Valkyrien Skies safety: VS does not mixin to RenderTarget or its buffer
 * creation methods. This mixin only activates when setIsStencilBufferEnabled(true)
 * is called, which we do lazily (never by default — only when a same-dimension
 * live-view portal is first rendered).
 */
@Mixin(RenderTarget.class)
public abstract class MixinRenderTarget implements IEFrameBuffer {

    private boolean cosmosLiveView_stencilEnabled = false;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(boolean useDepth, CallbackInfo ci) {
        cosmosLiveView_stencilEnabled = false;
    }

    @Override
    public boolean getIsStencilBufferEnabled() {
        return cosmosLiveView_stencilEnabled;
    }

    @Override
    public void setIsStencilBufferEnabled(boolean enabled) {
        cosmosLiveView_stencilEnabled = enabled;
    }

    /**
     * When stencil is enabled, change the internal format of the depth texture
     * from GL_DEPTH_COMPONENT to GL_DEPTH24_STENCIL8 and update format/type accordingly.
     * This gives us an 8-bit stencil packed with the 24-bit depth.
     */
    @ModifyArgs(
        method = "createBuffers",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V",
            remap = false
        )
    )
    private void modifyDepthTexFormat(Args args) {
        if (cosmosLiveView_stencilEnabled && Objects.equals(args.get(2), GL_DEPTH_COMPONENT)) {
            args.set(2, GL_DEPTH24_STENCIL8);
            args.set(6, ARBFramebufferObject.GL_DEPTH_STENCIL);
            args.set(7, GL30C.GL_UNSIGNED_INT_24_8);
        }
    }

    /**
     * When stencil is enabled, attach the depth texture as GL_DEPTH_STENCIL_ATTACHMENT
     * instead of GL_DEPTH_ATTACHMENT, so the stencil channel is also bound to the FBO.
     */
    @ModifyArgs(
        method = "createBuffers",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glFramebufferTexture2D(IIIII)V",
            remap = false
        )
    )
    private void modifyDepthAttachment(Args args) {
        if (cosmosLiveView_stencilEnabled && Objects.equals(args.get(1), GL30C.GL_DEPTH_ATTACHMENT)) {
            args.set(1, GL30.GL_DEPTH_STENCIL_ATTACHMENT);
        }
    }
}
