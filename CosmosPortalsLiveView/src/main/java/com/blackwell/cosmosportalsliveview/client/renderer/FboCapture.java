package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.client.event.PortalRenderEventHandler;
import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders each portal's destination into an off-screen FBO using Minecraft's
 * own LevelRenderer. Called from MixinGameRenderer at TAIL of renderLevel —
 * after the main frame is fully drawn — so we never interrupt the normal render
 * pipeline and never conflict with Valkyrien Skies.
 *
 * GL STATE CONTRACT
 * -----------------
 * renderLevel() leaves behind bound shaders, texture units, blend state, depth
 * state, and framebuffer bindings that differ from what MC expects when it
 * continues drawing the GUI / inventory after our hook. We save every piece of
 * GL state we touch and restore it unconditionally (even on exception) via a
 * try/finally block so the main frame is never corrupted.
 *
 * RenderTarget is abstract in 1.20.1 — we create a concrete anonymous subclass
 * that simply calls createBuffers() to set up the FBO.
 */
@OnlyIn(Dist.CLIENT)
public class FboCapture {

    /** Per-portal FBO cache. Keyed by portal BlockPos.asLong(). */
    private static final ConcurrentHashMap<Long, RenderTarget> fboCache = new ConcurrentHashMap<>();

    /** Last capture timestamp per portal. */
    private static final ConcurrentHashMap<Long, Long> lastCaptureMs = new ConcurrentHashMap<>();

    // Cached reflection handles — resolved once on first use
    private static Field camPositionField;
    private static Field camXRotField;
    private static Field camYRotField;
    private static Field camInitializedField;
    private static boolean reflectionResolved = false;

    // ── Entry point (called from mixin) ────────────────────────────────────────

    public static void onRenderLevelTail(float partialTick) {
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return;

        long nowMs       = System.currentTimeMillis();
        long intervalMs  = PortalLiveViewConfig.CAPTURE_INTERVAL_MS.get();
        int  resolution  = Math.max(64, PortalLiveViewConfig.CAPTURE_RESOLUTION.get());
        int  maxPerFrame = PortalLiveViewConfig.PORTALS_PER_FRAME.get();

        int captured = 0;

        for (Map.Entry<BlockPos, PortalViewData> entry :
                PortalLiveViewManager.getActivePortals().entrySet()) {

            if (captured >= maxPerFrame) break;

            PortalViewData data = entry.getValue();
            if (data.destPos == null) continue;
            if (data.isCaptureInFlight()) continue;

            // Interval throttle
            long key = data.portalPos.asLong();
            Long last = lastCaptureMs.get(key);
            if (last != null && (nowMs - last) < intervalMs) continue;

            // Dock live-view enabled check
            BlockPos dockPos = PortalRenderEventHandler.findDockPos(level, data.portalPos);
            if (dockPos == null || !LiveViewState.isEnabled(dockPos)) continue;

            // Proximity cull — 64 block radius
            Vec3 playerPos = mc.player.position();
            BlockPos pp = data.portalPos;
            double dx = pp.getX() + 0.5 - playerPos.x;
            double dy = pp.getY() + 0.5 - playerPos.y;
            double dz = pp.getZ() + 0.5 - playerPos.z;
            if (dx*dx + dy*dy + dz*dz > 64.0 * 64.0) continue;

            try {
                capturePortal(mc, data, resolution, partialTick);
                lastCaptureMs.put(key, nowMs);
                captured++;
            } catch (Exception e) {
                // Never crash the game — skip this portal this frame
            }
        }
    }

    // ── Per-portal FBO render ──────────────────────────────────────────────────

    private static void capturePortal(Minecraft mc, PortalViewData data,
                                       int resolution, float partialTick) throws Exception {
        RenderSystem.assertOnRenderThread();

        long key = data.portalPos.asLong();

        // Get or create FBO for this portal at the required resolution
        RenderTarget fbo = fboCache.compute(key, (k, existing) -> {
            if (existing != null
                    && existing.width  == resolution
                    && existing.height == resolution) {
                return existing;
            }
            if (existing != null) existing.destroyBuffers();
            RenderTarget fresh = new RenderTarget(true) {};
            fresh.createBuffers(resolution, resolution, Minecraft.ON_OSX);
            fresh.setClearColor(0f, 0f, 0f, 1f);
            return fresh;
        });

        // ── Snapshot ALL GL state we will touch ───────────────────────────────
        // Framebuffer
        int prevDrawFbo  = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFbo  = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        // Viewport
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);

        // Active texture unit
        int prevActiveTexUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        // Texture bound on unit 0 (the one renderLevel hammers)
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        // Texture bound on unit 2 (lightmap)
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 2);
        int prevTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        // Restore active unit to what it was
        GL13.glActiveTexture(prevActiveTexUnit);

        // Shader program
        int prevShader = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        // Depth
        boolean prevDepthTest  = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int     prevDepthFunc  = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        // Blend
        boolean prevBlend     = GL11.glIsEnabled(GL11.GL_BLEND);
        int     prevBlendSrc  = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int     prevBlendDst  = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        // Cull
        boolean prevCull     = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int     prevCullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);

        // Projection matrix (RenderSystem-level, not raw GL)
        // We capture it by re-fetching from gameRenderer after our pass
        double fovDeg = mc.options.fov().get();
        Matrix4f captureProj = mc.gameRenderer.getProjectionMatrix(fovDeg);

        // ── Build virtual camera ────────────────────────────────────────────────
        Camera virtualCam = new Camera();
        Vec3 eye = Vec3.atCenterOf(data.destPos)
                .add(0, mc.player.getEyeHeight(), 0);
        setCameraTransform(virtualCam, eye, data.destYaw, data.destPitch);

        // ── Render into FBO ────────────────────────────────────────────────────
        try {
            // Square FBO: aspect = 1.0 so 90° looks reasonable; keep player's FOV
            RenderSystem.setProjectionMatrix(captureProj, VertexSorting.DISTANCE_TO_ORIGIN);

            fbo.clear(Minecraft.ON_OSX);
            fbo.bindWrite(true);

            mc.levelRenderer.renderLevel(
                    new PoseStack(), partialTick, 0L, false,
                    virtualCam, mc.gameRenderer,
                    mc.gameRenderer.lightTexture(), captureProj);

        } finally {
            // ── Unconditional full GL state restore ───────────────────────────

            // Framebuffer — restore both read and draw
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);

            // Viewport
            GL11.glViewport(prevViewport[0], prevViewport[1],
                            prevViewport[2], prevViewport[3]);

            // Shader
            GL20.glUseProgram(prevShader);

            // Texture units
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2);
            GL13.glActiveTexture(prevActiveTexUnit);

            // Depth
            if (prevDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else               GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(prevDepthFunc);
            GL11.glDepthMask(prevDepthWrite);

            // Blend
            if (prevBlend) GL11.glEnable(GL11.GL_BLEND);
            else           GL11.glDisable(GL11.GL_BLEND);
            GL11.glBlendFunc(prevBlendSrc, prevBlendDst);

            // Cull
            if (prevCull) GL11.glEnable(GL11.GL_CULL_FACE);
            else          GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glCullFace(prevCullFace);

            // Projection matrix — restore to main frame projection
            Matrix4f mainProj = mc.gameRenderer.getProjectionMatrix(fovDeg);
            RenderSystem.setProjectionMatrix(mainProj, VertexSorting.DISTANCE_TO_ORIGIN);

            // Rebind main render target so MC can continue drawing GUI on top
            mc.getMainRenderTarget().bindWrite(true);
        }

        // ── Read pixels and upload texture ────────────────────────────────────
        // Only do this after full state restore — texture read doesn't need FBO bound
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);
        RenderSystem.bindTexture(fbo.getColorTextureId());
        img.downloadTexture(0, false);
        img.flipY();

        DynamicTexture tex = new DynamicTexture(img);
        tex.upload();
        data.setTexture(tex);
    }

    // ── Camera positioning ─────────────────────────────────────────────────────

    private static void setCameraTransform(Camera cam, Vec3 pos,
                                            float yaw, float pitch) throws Exception {
        if (!reflectionResolved) resolveReflection();

        if (camInitializedField != null) camInitializedField.setBoolean(cam, true);
        if (camPositionField    != null) camPositionField.set(cam, pos);
        if (camXRotField        != null) camXRotField.setFloat(cam, pitch);
        if (camYRotField        != null) camYRotField.setFloat(cam, yaw);
    }

    private static void resolveReflection() {
        reflectionResolved = true;
        try {
            camInitializedField = Camera.class.getDeclaredField("initialized");
            camInitializedField.setAccessible(true);
        } catch (Exception e) { camInitializedField = null; }

        try {
            camPositionField = Camera.class.getDeclaredField("position");
            camPositionField.setAccessible(true);
        } catch (Exception e) { camPositionField = null; }

        try {
            camXRotField = Camera.class.getDeclaredField("xRot");
            camXRotField.setAccessible(true);
        } catch (Exception e) { camXRotField = null; }

        try {
            camYRotField = Camera.class.getDeclaredField("yRot");
            camYRotField.setAccessible(true);
        } catch (Exception e) { camYRotField = null; }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    public static void cleanup() {
        fboCache.forEach((k, fbo) -> {
            try { fbo.destroyBuffers(); } catch (Exception ignored) {}
        });
        fboCache.clear();
        lastCaptureMs.clear();
        reflectionResolved = false;
        camPositionField    = null;
        camXRotField        = null;
        camYRotField        = null;
        camInitializedField = null;
    }
}
