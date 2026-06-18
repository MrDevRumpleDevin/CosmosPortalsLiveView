package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.ducks.IEFrameBuffer;
import com.blackwell.cosmosportalsliveview.mixin.MixinLevelRenderer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Stencil-based portal renderer for same-dimension portals.
 *
 * Technique (mirrors Immersive Portals' RendererUsingStencil, simplified):
 *
 *  1. Ensure the main framebuffer has a stencil attachment (lazy, one-time init).
 *  2. Stamp the portal quad silhouette into the stencil buffer (color+depth writes off).
 *  3. Clear depth to MAX (1.0) behind the stencil mask so the destination geometry
 *     renders without fighting source-side depth.
 *  4. Re-render solid/cutout chunk layers with the camera translated to the destination
 *     position, clipped by the stencil mask.
 *  5. Restore the portal quad's depth so source geometry in front of the portal face
 *     correctly occludes the destination view.
 *  6. Restore all GL state.
 *
 * Valkyrien Skies safety:
 *  - No second ClientLevel or WorldRenderer created.
 *  - No entity transforms or contraption state touched.
 *  - All GL state saved/restored around the render pass.
 *  - Stencil buffer is enabled lazily and only on the main RenderTarget.
 *
 * Shader mod fallback:
 *  If the stencil buffer cannot be confirmed functional (Iris/OptiFine replaced the
 *  framebuffer), isStencilAvailable() returns false and the caller falls back to the
 *  raycaster for this portal.
 */
@OnlyIn(Dist.CLIENT)
public class StencilPortalRenderer {

    // ── Stencil availability / lazy init ─────────────────────────────────────

    /** null = not yet probed, true/false = cached result */
    private static Boolean stencilAvailable = null;
    private static boolean stencilInitialized = false;

    /**
     * Ensures the main framebuffer has a stencil attachment, then probes whether
     * it actually works. Called once on first portal render attempt.
     * Safe to call every frame — subsequent calls are immediate no-ops.
     */
    public static boolean ensureStencilAvailable() {
        if (stencilAvailable != null) return stencilAvailable;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) return false;

        if (!stencilInitialized) {
            IEFrameBuffer iefb = (IEFrameBuffer) mc.getMainRenderTarget();
            if (!iefb.getIsStencilBufferEnabled()) {
                iefb.setIsStencilBufferEnabled(true);
                // Resize to 0 then back to current size forces buffer reallocation
                // with the new depth+stencil format.
                mc.getMainRenderTarget().resize(
                        mc.getMainRenderTarget().width,
                        mc.getMainRenderTarget().height,
                        Minecraft.ON_OSX
                );
            }
            stencilInitialized = true;
        }

        // Probe: stencil bits > 0 means allocation succeeded.
        try {
            int bits = GL11.glGetInteger(GL30.GL_STENCIL_BITS);
            stencilAvailable = (bits > 0);
        } catch (Exception e) {
            stencilAvailable = false;
        }

        if (!stencilAvailable) {
            // Revert — we couldn't get a stencil buffer (shader mod replaced FBO, etc.)
            // Don't keep resizing every frame.
            stencilInitialized = true;
        }

        return stencilAvailable;
    }

    /** Called on window resize or F3+T to force re-probe next frame. */
    public static void invalidateStencilCache() {
        stencilAvailable = null;
        stencilInitialized = false;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Attempts to render {@code data} using the stencil technique.
     *
     * @return true if stencil rendering succeeded; false to fall back to raycaster.
     */
    public static boolean renderPortal(PortalViewData data,
                                       PoseStack poseStack,
                                       Camera camera,
                                       float partialTick) {
        if (!ensureStencilAvailable()) return false;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return false;

        // Only handle same-dimension portals
        ResourceKey<Level> currentDim = level.dimension();
        if (data.destDimension == null) return false;
        ResourceKey<Level> destDim = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, data.destDimension);
        if (!currentDim.equals(destDim)) return false;

        // Need destination hole center to be scanned
        if (Double.isNaN(data.destHoleCenterX) || Double.isNaN(data.destHoleCenterZ)
                || Double.isNaN(data.destHoleBottomY)) {
            return false;
        }

        Vec3 camPos = camera.getPosition();

        // Build portal quad vertices (camera-relative)
        List<float[]> quad = buildPortalQuad(data, camPos);
        if (quad == null) return false;

        // Compute where the camera maps to on the destination side
        Vec3 destCamPos = computeDestCameraPos(data, camPos);

        // Camera-relative translation delta
        double dx = destCamPos.x - camPos.x;
        double dy = destCamPos.y - camPos.y;
        double dz = destCamPos.z - camPos.z;

        // Grab current projection matrix BEFORE we touch anything
        Matrix4f projMatrix = RenderSystem.getProjectionMatrix();

        // Save GL state
        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int prevStencilFuncVal = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        int prevStencilRef     = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        int prevStencilMask    = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        int prevDepthFunc      = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        try {
            // ── Step 1: stamp portal shape into stencil ───────────────────────
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);

            GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INCR);
            GL11.glStencilMask(0xFF);

            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(false);
            RenderSystem.enableDepthTest();

            drawPortalQuad(poseStack, quad);

            // ── Step 2: clear depth behind the stencil mask ───────────────────
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(true);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthRange(1.0, 1.0);

            drawFullscreenTriangle();

            GL11.glDepthRange(0.0, 1.0);
            GL11.glDepthFunc(prevDepthFunc);

            // ── Step 3: render destination world through the stencil hole ─────
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            poseStack.pushPose();
            poseStack.translate(-dx, -dy, -dz);

            MixinLevelRenderer accessor = (MixinLevelRenderer) mc.levelRenderer;
            try {
                accessor.cosmosLiveView_renderChunkLayer(
                        RenderType.solid(), poseStack,
                        destCamPos.x, destCamPos.y, destCamPos.z, projMatrix);
                accessor.cosmosLiveView_renderChunkLayer(
                        RenderType.cutoutMipped(), poseStack,
                        destCamPos.x, destCamPos.y, destCamPos.z, projMatrix);
                accessor.cosmosLiveView_renderChunkLayer(
                        RenderType.cutout(), poseStack,
                        destCamPos.x, destCamPos.y, destCamPos.z, projMatrix);
            } catch (Exception e) {
                // Never crash during render — log and eat the exception
                org.apache.logging.log4j.LogManager.getLogger("CosmosLiveView")
                    .warn("[StencilPortalRenderer] renderChunkLayer failed: {}", e.getMessage());
            }

            poseStack.popPose();

            // ── Step 4: restore depth over the portal hole ────────────────────
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(true);
            GL11.glDepthFunc(GL11.GL_ALWAYS);

            drawPortalQuad(poseStack, quad);

            GL11.glDepthFunc(prevDepthFunc);

        } finally {
            // ── Restore all GL state ──────────────────────────────────────────
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            if (depthTestEnabled) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (stencilWasEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST);
            else GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glStencilFunc(prevStencilFuncVal, prevStencilRef, prevStencilMask);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glStencilMask(0xFF);
        }

        return true;
    }

    // ── Camera transform ──────────────────────────────────────────────────────

    /**
     * Maps the player camera through the portal: offset from source portal center
     * is preserved and re-applied at the destination portal center.
     */
    private static Vec3 computeDestCameraPos(PortalViewData data, Vec3 camPos) {
        double srcCX = data.portalPos.getX() + 0.5;
        double srcCY = data.portalPos.getY() + 0.5;
        double srcCZ = data.portalPos.getZ() + 0.5;

        double offX = camPos.x - srcCX;
        double offY = camPos.y - srcCY;
        double offZ = camPos.z - srcCZ;

        double dstCX = data.destHoleCenterX;
        double dstCY = data.destHoleBottomY + data.portalHalfH;
        double dstCZ = data.destHoleCenterZ;

        double yawRad = Math.toRadians(data.destYaw);
        double fwdX   = -Math.sin(yawRad);
        double fwdZ   =  Math.cos(yawRad);
        double rightX =  Math.cos(yawRad);
        double rightZ =  Math.sin(yawRad);

        double localRight   = offX * rightX + offZ * rightZ;
        double localUp      = offY;
        double localForward = offX * fwdX   + offZ * fwdZ;

        return new Vec3(
                dstCX + localRight * rightX + localForward * fwdX,
                dstCY + localUp,
                dstCZ + localRight * rightZ + localForward * fwdZ
        );
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    /** Builds 4 camera-relative XYZW vertices for the portal quad. */
    private static List<float[]> buildPortalQuad(PortalViewData data, Vec3 camPos) {
        double cx = data.portalPos.getX() + 0.5;
        double cy = data.portalPos.getY() + 0.5;
        double cz = data.portalPos.getZ() + 0.5;

        float hw = data.portalHalfW;
        float hh = data.portalHalfH;

        double yawRad = Math.toRadians(data.destYaw);
        double rightX =  Math.cos(yawRad);
        double rightZ =  Math.sin(yawRad);

        float rx = (float)(cx - camPos.x);
        float ry = (float)(cy - camPos.y);
        float rz = (float)(cz - camPos.z);

        float drx = (float)(rightX * hw);
        float drz = (float)(rightZ * hw);

        List<float[]> quad = new ArrayList<>(4);
        quad.add(new float[]{ rx - drx, ry - hh, rz - drz });
        quad.add(new float[]{ rx + drx, ry - hh, rz + drz });
        quad.add(new float[]{ rx + drx, ry + hh, rz + drz });
        quad.add(new float[]{ rx - drx, ry + hh, rz - drz });
        return quad;
    }

    /** Draws the portal quad geometry using the POSITION shader. */
    private static void drawPortalQuad(PoseStack poseStack, List<float[]> quad) {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        Matrix4f mat = poseStack.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (float[] v : quad) {
            buf.vertex(mat, v[0], v[1], v[2]).endVertex();
        }
        tess.end();
    }

    /**
     * Draws a clip-space triangle covering the entire screen at depth=1.0.
     * Used to flood depth behind the stencil mask.
     * Uses a single oversized triangle instead of a quad — avoids the diagonal seam
     * that can appear with two triangles at identical depth.
     */
    private static void drawFullscreenTriangle() {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        // Save + replace both matrices with identity for clip-space drawing
        RenderSystem.getModelViewStack().pushPose();
        RenderSystem.getModelViewStack().setIdentity();
        RenderSystem.applyModelViewMatrix();

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f identityProj = new Matrix4f().identity();
        RenderSystem.setProjectionMatrix(identityProj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

        RenderSystem.setShader(GameRenderer::getPositionShader);
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        // One oversized triangle covers the entire NDC [-1,1]^2 viewport.
        // Vertices at z=1.0 (far plane in NDC after depth range 1,1 set by caller).
        buf.vertex(identityProj, -1f, -1f, 1f).endVertex();
        buf.vertex(identityProj,  3f, -1f, 1f).endVertex();
        buf.vertex(identityProj, -1f,  3f, 1f).endVertex();
        tess.end();

        // Restore projection
        RenderSystem.setProjectionMatrix(savedProj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
    }
}
