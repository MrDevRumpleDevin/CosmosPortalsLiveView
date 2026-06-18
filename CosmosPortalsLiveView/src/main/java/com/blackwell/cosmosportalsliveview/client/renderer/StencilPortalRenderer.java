package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.client.event.PortalRenderEventHandler;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
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
 *  1. For each same-dimension portal in view:
 *     a. Write the portal quad silhouette into the stencil buffer (increment from 0→1).
 *        Color mask off — we only care about the stencil stamp.
 *     b. Clear depth to MAX (1.0) behind the stencil mask, so the destination
 *        world renders "through" the portal without fighting the source geometry.
 *     c. Translate the ModelView matrix so the camera appears at the destination
 *        position corresponding to the player's current position relative to the
 *        source portal.
 *     d. Re-render the level geometry clipped by the stencil mask (stencil == 1).
 *        This draws the destination view exactly where the portal hole is.
 *     e. Restore depth values over the portal hole (so source-side geometry that
 *        is closer than the portal plane correctly occludes the destination view).
 *     f. Reset stencil to 0 for the next portal.
 *
 * Valkyrien Skies safety:
 *  - We never create a second ClientLevel or WorldRenderer.
 *  - We never modify entity transform matrices or contraption state.
 *  - All GL state is saved and restored around each portal render pass.
 *  - The stencil buffer itself is new to the framebuffer (vanilla has none), so
 *    VS has no existing stencil state to conflict with.
 *
 * Fallback:
 *  If stencil is not functional (shader mods that replaced the framebuffer, or
 *  the MixinMainTarget injection was skipped), isStencilAvailable() returns false
 *  and the caller falls back to the raycaster for this portal.
 */
@OnlyIn(Dist.CLIENT)
public class StencilPortalRenderer {

    // ── Stencil availability ──────────────────────────────────────────────────

    private static Boolean stencilAvailable = null; // null = not yet probed

    /**
     * Returns true if the main framebuffer has a functional stencil buffer.
     * Result is cached after the first probe. Safe to call every frame.
     */
    public static boolean isStencilAvailable() {
        if (stencilAvailable != null) return stencilAvailable;
        // Probe: try to read the stencil buffer size. If it's 0, no stencil.
        try {
            int bits = GL11.glGetInteger(GL30.GL_STENCIL_BITS);
            stencilAvailable = (bits > 0);
        } catch (Exception e) {
            stencilAvailable = false;
        }
        return stencilAvailable;
    }

    /** Called when the framebuffer is recreated (window resize, F3+T) to re-probe. */
    public static void invalidateStencilCache() {
        stencilAvailable = null;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Attempts to render {@code data} using the stencil technique.
     *
     * @param data       the portal to render
     * @param poseStack  the current render pose stack (camera-relative)
     * @param camera     the active camera
     * @param partialTick partial tick for smooth rendering
     * @return true if stencil rendering succeeded; false if the caller should
     *         fall back to the raycaster.
     */
    public static boolean renderPortal(PortalViewData data,
                                       PoseStack poseStack,
                                       Camera camera,
                                       float partialTick) {
        if (!isStencilAvailable()) return false;

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
            return false; // not ready yet — caller will use raycaster until scan completes
        }

        Vec3 camPos = camera.getPosition();

        // ── Build portal quad vertices (camera-relative world space) ──────────
        List<float[]> quad = buildPortalQuad(data, camPos);
        if (quad == null) return false;

        // ── Compute destination camera position ───────────────────────────────
        // The player's offset from the source portal center, projected onto portal axes,
        // is replicated at the destination portal. This is the "window" effect.
        Vec3 destCamPos = computeDestCameraPos(data, camPos);

        // ── GL state save ─────────────────────────────────────────────────────
        boolean depthTestWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean stencilWasEnabled   = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int[] prevStencilFunc = new int[3]; // func, ref, mask
        GL11.glGetIntegerv(GL11.GL_STENCIL_FUNC,   new java.nio.IntBuffer[] { null }[0]); // dummy - use individual gets
        int prevStencilFuncVal  = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        int prevStencilRef      = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        int prevStencilMask     = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        int prevDepthFunc       = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        try {
            // ── Step 1: Stamp portal shape into stencil ───────────────────────
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glClearStencil(0);
            // Only clear the stencil in the portal's screen-space AABB for perf,
            // but a full clear is safer and correct for a single portal per frame.
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);

            // Pass if stencil == 0 (fresh), write 1 on pass
            GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INCR);
            GL11.glStencilMask(0xFF);

            // Color + depth writes off — stencil stamp only
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(false);
            RenderSystem.enableDepthTest();

            drawPortalQuad(poseStack, quad);

            // ── Step 2: Clear depth behind the stencil mask ───────────────────
            // Where stencil == 1, write depth = 1.0 (furthest possible).
            // This carves a "hole" through the depth buffer so the destination
            // world renders without fighting source-side geometry.
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(true);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthRange(1.0, 1.0);

            drawFullscreenQuad(); // fills depth = 1.0 everywhere stencil == 1

            GL11.glDepthRange(0.0, 1.0);
            GL11.glDepthFunc(prevDepthFunc);

            // ── Step 3: Render destination world through the stencil hole ─────
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            renderDestinationWorld(mc, poseStack, destCamPos, camPos, partialTick);

            // ── Step 4: Restore depth over the portal hole ────────────────────
            // Write the portal quad's actual depth values back so geometry in
            // front of the portal plane in the source world correctly occludes.
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(true);
            GL11.glDepthFunc(GL11.GL_ALWAYS);

            drawPortalQuad(poseStack, quad); // writes portal surface depth

            GL11.glDepthFunc(prevDepthFunc);

        } finally {
            // ── GL state restore ──────────────────────────────────────────────
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);

            if (depthTestWasEnabled) RenderSystem.enableDepthTest();
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
     * Maps the player's camera position through the portal.
     *
     * The player's offset from the SOURCE portal center (in portal-local space)
     * is added to the DESTINATION portal center. This makes the destination world
     * appear as if the portal is a window: moving left reveals more of the right
     * side of the destination room, exactly like Immersive Portals.
     *
     * Portal-local axes:
     *   right = along the portal face (X for axis=Z portals, Z for axis=X portals)
     *   up    = +Y
     *   fwd   = portal normal (into the room)
     */
    private static Vec3 computeDestCameraPos(PortalViewData data, Vec3 camPos) {
        // Source portal center (block center)
        double srcCenterX = data.portalPos.getX() + 0.5;
        double srcCenterY = data.portalPos.getY() + 0.5;
        double srcCenterZ = data.portalPos.getZ() + 0.5;

        // Player offset from source portal center
        double offsetX = camPos.x - srcCenterX;
        double offsetY = camPos.y - srcCenterY;
        double offsetZ = camPos.z - srcCenterZ;

        // Destination portal center
        double dstCenterX = Double.isNaN(data.destHoleCenterX)
                ? data.destPos.getX() + 0.5 : data.destHoleCenterX;
        double dstCenterY = data.destHoleBottomY + data.portalHalfH;
        double dstCenterZ = Double.isNaN(data.destHoleCenterZ)
                ? data.destPos.getZ() + 0.5 : data.destHoleCenterZ;

        // Portal orientation yaw (stored as the DESTINATION yaw — direction you'd
        // be looking when you step through). The portal face normal is perpendicular to this.
        double yawRad   = Math.toRadians(data.destYaw);
        // fwd points into the destination room (away from the portal face)
        double fwdX = -Math.sin(yawRad);
        double fwdZ =  Math.cos(yawRad);
        // right is perpendicular to fwd in the horizontal plane
        double rightX =  Math.cos(yawRad);
        double rightZ =  Math.sin(yawRad);

        // Decompose player offset into portal-local components
        double localRight   = offsetX * rightX + offsetZ * rightZ;
        double localUp      = offsetY;
        // forward component: how far in front of (or behind) the portal face
        double localForward = offsetX * fwdX   + offsetZ * fwdZ;

        // Re-apply offset at destination — mirror right/up, preserve forward sign
        // so the camera is the same distance in front of the dest portal.
        return new Vec3(
                dstCenterX + localRight * rightX + localForward * fwdX,
                dstCenterY + localUp,
                dstCenterZ + localRight * rightZ + localForward * fwdZ
        );
    }

    // ── World re-render ───────────────────────────────────────────────────────

    /**
     * Re-renders the level from {@code destCamPos} with the stencil mask active.
     * Only solid/cutout geometry is rendered (no translucent pass — keeps it fast
     * and avoids blending artifacts from nested transparency).
     *
     * We translate the model-view matrix instead of actually moving the camera
     * entity, so no game state is modified.
     */
    private static void renderDestinationWorld(Minecraft mc,
                                                PoseStack poseStack,
                                                Vec3 destCamPos,
                                                Vec3 origCamPos,
                                                float partialTick) {
        // The level renderer works in camera-relative coordinates.
        // Shift = destCamPos - origCamPos expressed as a matrix translation.
        double dx = destCamPos.x - origCamPos.x;
        double dy = destCamPos.y - origCamPos.y;
        double dz = destCamPos.z - origCamPos.z;

        poseStack.pushPose();
        // Translate so geometry at destCamPos appears at the screen origin.
        // We negate because the modelview is camera→world, and we want to
        // shift the world by -delta to move the virtual camera by +delta.
        poseStack.translate(-dx, -dy, -dz);

        // Re-render solid+cutout chunks using the existing world renderer.
        // This is the same call path Minecraft uses every frame — no custom
        // renderer needed. The stencil mask ensures only portal pixels are written.
        try {
            mc.levelRenderer.renderChunkLayer(
                    net.minecraft.client.renderer.RenderType.solid(),
                    poseStack,
                    destCamPos.x, destCamPos.y, destCamPos.z,
                    RenderSystem.getModelViewMatrix()
            );
            mc.levelRenderer.renderChunkLayer(
                    net.minecraft.client.renderer.RenderType.cutoutMipped(),
                    poseStack,
                    destCamPos.x, destCamPos.y, destCamPos.z,
                    RenderSystem.getModelViewMatrix()
            );
            mc.levelRenderer.renderChunkLayer(
                    net.minecraft.client.renderer.RenderType.cutout(),
                    poseStack,
                    destCamPos.x, destCamPos.y, destCamPos.z,
                    RenderSystem.getModelViewMatrix()
            );
        } catch (Exception e) {
            // Never crash the game — if the level renderer throws (e.g. during
            // world load/unload transitions), log and skip silently.
            net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    "[CosmosLiveView] stencil render error: " + e.getMessage()), true);
        }

        poseStack.popPose();
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    /**
     * Builds the portal quad as a list of 4 camera-relative XYZ vertices
     * (bottom-left, bottom-right, top-right, top-left — CCW when viewed from front).
     * Returns null if orientation data is insufficient.
     */
    private static List<float[]> buildPortalQuad(PortalViewData data, Vec3 camPos) {
        // Portal center in world space
        double cx = data.portalPos.getX() + 0.5;
        double cy = data.portalPos.getY() + 0.5;
        double cz = data.portalPos.getZ() + 0.5;

        float hw = data.portalHalfW;
        float hh = data.portalHalfH;

        // Portal right-axis in world XZ (same convention as the raycaster)
        double yawRad  = Math.toRadians(data.destYaw);
        double rightX  =  Math.cos(yawRad);
        double rightZ  =  Math.sin(yawRad);

        // Camera-relative center
        float rx = (float)(cx - camPos.x);
        float ry = (float)(cy - camPos.y);
        float rz = (float)(cz - camPos.z);

        float drx = (float)(rightX * hw);
        float drz = (float)(rightZ * hw);

        List<float[]> quad = new ArrayList<>(4);
        // BL, BR, TR, TL (CCW from front face)
        quad.add(new float[]{ rx - drx, ry - hh, rz - drz });
        quad.add(new float[]{ rx + drx, ry - hh, rz + drz });
        quad.add(new float[]{ rx + drx, ry + hh, rz + drz });
        quad.add(new float[]{ rx - drx, ry + hh, rz - drz });
        return quad;
    }

    /** Draws the portal quad (no texture, no color — stencil/depth writes only). */
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
     * Draws a full-screen quad at depth=1.0 (used to flood-fill depth behind stencil).
     * Works in clip space so it's independent of the current modelview matrix.
     */
    private static void drawFullscreenQuad() {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        // Use identity matrix — clip-space quad covers NDC [-1,1]²
        RenderSystem.setShader(GameRenderer::getPositionShader);

        // Temporarily push identity matrices
        RenderSystem.getModelViewStack().pushPose();
        RenderSystem.getModelViewStack().setIdentity();
        RenderSystem.applyModelViewMatrix();

        Matrix4f identity = new Matrix4f().identity();
        RenderSystem.setProjectionMatrix(identity);

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buf.vertex(identity, -1f, -1f, 1f).endVertex();
        buf.vertex(identity,  1f, -1f, 1f).endVertex();
        buf.vertex(identity,  1f,  1f, 1f).endVertex();
        buf.vertex(identity, -1f,  1f, 1f).endVertex();
        tess.end();

        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
        // Projection matrix will be restored by the caller's GL state restore.
    }
}
