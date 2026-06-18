package com.blackwell.cosmosportalsliveview.ducks;

/**
 * Duck interface injected onto RenderTarget via MixinRenderTarget.
 * Allows external code to query and toggle the stencil buffer on any RenderTarget
 * without reflection, matching the pattern used by Immersive Portals.
 */
public interface IEFrameBuffer {
    boolean getIsStencilBufferEnabled();
    void setIsStencilBufferEnabled(boolean enabled);
}
