package com.blackwell.cosmosportalsliveview.mixin;

import com.blackwell.cosmosportalsliveview.ducks.IEFrameBuffer;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;

/**
 * Patches MainTarget (the primary screen framebuffer) to support the stencil channel.
 *
 * MainTarget uses a separate allocateDepthAttachment() call instead of createBuffers(),
 * so it needs its own ModifyArgs hooks in addition to MixinRenderTarget.
 *
 * The stencil is NOT enabled by default — IEFrameBuffer.setIsStencilBufferEnabled(true)
 * must be called first (done by StencilPortalRenderer on first use), followed by a
 * resize() call to reallocate the buffers with the new format.
 */
@Mixin(MainTarget.class)
public abstract class MixinMainTarget extends RenderTarget {

    public MixinMainTarget(boolean useDepth) {
        super(useDepth);
        throw new RuntimeException("Mixin constructor stub");
    }

    @ModifyArgs(
        method = "allocateDepthAttachment",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V",
            remap = false
        )
    )
    private void modifyDepthTexFormat(Args args) {
        if (((IEFrameBuffer) this).getIsStencilBufferEnabled()) {
            args.set(2, GL_DEPTH24_STENCIL8);
            args.set(6, ARBFramebufferObject.GL_DEPTH_STENCIL);
            args.set(7, GL30C.GL_UNSIGNED_INT_24_8);
        }
    }

    @ModifyArgs(
        method = "createFrameBuffer",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glFramebufferTexture2D(IIIII)V",
            remap = false
        )
    )
    private void modifyDepthAttachment(Args args) {
        if (((IEFrameBuffer) this).getIsStencilBufferEnabled()) {
            if ((int) args.get(1) == GL30.GL_DEPTH_ATTACHMENT) {
                args.set(1, GL30.GL_DEPTH_STENCIL_ATTACHMENT);
            }
        }
    }
}
