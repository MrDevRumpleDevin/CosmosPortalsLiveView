package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.client.event.PortalRenderEventHandler;
import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders each portal's destination into an off-screen FBO using Minecraft's
 * own LevelRenderer. Called from MixinGameRenderer at TAIL of renderLevel —
 * after the main frame is fully drawn — so we never interrupt the normal render
 * pipeline and never conflict with Valkyrien Skies.
 *
 * RenderTarget is abstract in 1.20.1. We create a concrete anonymous subclass
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

        long nowMs      = System.currentTimeMillis();
        long intervalMs = PortalLiveViewConfig.CAPTURE_INTERVAL_MS.get();
        int  resolution = Math.max(64, PortalLiveViewConfig.CAPTURE_RESOLUTION.get());
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

            // Proximity cull — 64 block radius, same as render handler
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
            // RenderTarget is abstract — create concrete anonymous subclass
            RenderTarget fresh = new RenderTarget(true) {};
            fresh.createBuffers(resolution, resolution, Minecraft.ON_OSX);
            fresh.setClearColor(0f, 0f, 0f, 1f);
            return fresh;
        });

        // ── Save state ─────────────────────────────────────────────────────────
        RenderTarget mainFbo = mc.getMainRenderTarget();

        // ── Build virtual camera ────────────────────────────────────────────────
        // Camera.setup() needs a real entity — but we only need position + rotation.
        // We position the eye slightly above destPos (player eye height) and look
        // in the direction stored in PortalViewData (destYaw / destPitch).
        Camera virtualCam = new Camera();
        Vec3 eye = Vec3.atCenterOf(data.destPos)
                .add(0, mc.player.getEyeHeight(), 0);
        setCameraTransform(virtualCam, eye, data.destYaw, data.destPitch);

        // ── Set projection ─────────────────────────────────────────────────────
        // square FBO: aspect ratio = 1.0, so we use 90° horizontal FOV
        double fovDeg = mc.options.fov().get();
        Matrix4f proj = mc.gameRenderer.getProjectionMatrix(fovDeg);
        RenderSystem.setProjectionMatrix(proj,
                com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

        // ── Bind FBO and render ────────────────────────────────────────────────
        fbo.clear(Minecraft.ON_OSX);
        fbo.bindWrite(true);

        mc.levelRenderer.renderLevel(new PoseStack(), partialTick, 0L, false,
                virtualCam, mc.gameRenderer, mc.gameRenderer.lightTexture(), proj);

        // ── Read pixels ────────────────────────────────────────────────────────
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);
        RenderSystem.bindTexture(fbo.getColorTextureId());
        img.downloadTexture(0, false);
        img.flipY();

        // ── Restore main FBO and projection ───────────────────────────────────
        mainFbo.bindWrite(true);
        Matrix4f mainProj = mc.gameRenderer.getProjectionMatrix(fovDeg);
        RenderSystem.setProjectionMatrix(mainProj,
                com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

        // ── Upload texture on render thread (we are already on it) ────────────
        DynamicTexture tex = new DynamicTexture(img);
        tex.upload();
        data.setTexture(tex);
    }

    // ── Camera positioning ─────────────────────────────────────────────────────

    /**
     * Sets Camera position and rotation via reflection.
     * Official mapping field names (1.20.1): initialized, position, xRot, yRot.
     */
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

    /** Called when leaving a world — release all FBO GL resources. */
    public static void cleanup() {
        fboCache.forEach((k, fbo) -> {
            try { fbo.destroyBuffers(); } catch (Exception ignored) {}
        });
        fboCache.clear();
        lastCaptureMs.clear();
        reflectionResolved = false;
        camPositionField = null;
        camXRotField     = null;
        camYRotField     = null;
        camInitializedField = null;
    }
}
