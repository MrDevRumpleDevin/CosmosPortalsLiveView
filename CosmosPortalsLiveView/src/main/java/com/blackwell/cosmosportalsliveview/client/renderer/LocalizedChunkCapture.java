package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.lang.reflect.Method;

/**
 * High-quality live-view capture using Minecraft's actual renderer.
 *
 * Renders into an offscreen {@link RenderTarget} (FBO) at the configured resolution.
 * All work happens on the render/game thread — no background threads.
 *
 * Implementation notes:
 * - Forge 47.x adds Camera.setAnglesInternal(yaw, pitch) — use it directly.
 * - Camera.setPosition(x, y, z) is protected — call via reflection once.
 * - We use RenderTarget directly (SimpleRenderTarget is NeoForge/1.20.4+).
 * - GameRenderer.renderLevel(float, long, PoseStack) is called via reflection
 *   since it's protected/package in 1.20.1.
 */
@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    // One shared offscreen FBO — resized lazily.
    private static RenderTarget offscreenTarget = null;
    private static int offscreenResolution = -1;

    // Cached reflection handles — resolved once.
    private static Method cameraSetPosition = null;
    private static Method gameRendererRenderLevel = null;
    private static boolean reflectionResolved = false;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Schedules a render-thread capture for {@code portalData}.
     * No-op if a capture is already in flight.
     */
    public static void captureAsync(PortalViewData portalData, Level level) {
        if (portalData == null) return;
        if (portalData.isCaptureInFlight()) return;
        if (portalData.destPos == null) return;

        int resolution = Math.max(64, Math.min(PortalLiveViewConfig.CAPTURE_RESOLUTION.get(), 1024));

        Level destLevel = resolveSampleLevel(portalData.destDimension, level);
        // Destination level must be accessible for rendering; skip if not.
        if (destLevel == null) return;

        portalData.setCaptureInFlight(true);

        final int   resSnap    = resolution;
        final float yaw        = portalData.destYaw;
        final float pitch      = portalData.destPitch;
        final BlockPos destPos = portalData.destPos;

        Minecraft.getInstance().execute(() -> {
            try {
                NativeImage image = renderOffscreen(destLevel, destPos, yaw, pitch, resSnap);
                if (image != null) {
                    DynamicTexture texture = new DynamicTexture(image);
                    texture.upload();
                    portalData.setTexture(texture);
                }
            } catch (Exception e) {
                // Silently swallow — next tick will retry.
            } finally {
                portalData.setCaptureInFlight(false);
            }
        });
    }

    // ── Offscreen render ───────────────────────────────────────────────────────

    private static NativeImage renderOffscreen(Level level,
                                                BlockPos destPos,
                                                float yawDeg, float pitchDeg,
                                                int resolution) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null || mc.level == null) return null;

        resolveReflection(mc);
        ensureOffscreenTarget(resolution);
        if (offscreenTarget == null) return null;

        RenderTarget mainTarget = mc.getMainRenderTarget();
        Camera camera = mc.gameRenderer.getMainCamera();

        // ── Save camera state ──────────────────────────────────────────────────
        Vec3 origPos   = camera.getPosition();
        float origYaw   = camera.getYRot();
        float origPitch = camera.getXRot();

        // Eye pos: standard player eye height above destPos centre.
        Vec3 eyePos = new Vec3(destPos.getX() + 0.5, destPos.getY() + 1.62, destPos.getZ() + 0.5);

        NativeImage image = null;
        try {
            // ── Bind offscreen FBO ─────────────────────────────────────────────
            offscreenTarget.bindWrite(true);
            offscreenTarget.clear(false);
            GlStateManager._viewport(0, 0, resolution, resolution);

            // ── Move camera to destination ─────────────────────────────────────
            setCameraPos(camera, eyePos);
            // Forge 47.x adds setAnglesInternal(yaw, pitch) — public API.
            camera.setAnglesInternal(yawDeg, pitchDeg);

            // ── Set projection for square FBO ──────────────────────────────────
            float fovRad = (float) Math.toRadians(mc.options.fov().get());
            float renderDist = mc.gameRenderer.getRenderDistance();
            Matrix4f proj = new Matrix4f().setPerspective(fovRad, 1.0f, 0.05f, renderDist);
            RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);

            // ── Render the level ───────────────────────────────────────────────
            float partialTick = mc.getFrameTime();
            PoseStack poseStack = new PoseStack();
            renderLevelReflective(mc, poseStack, partialTick);

            // ── Read pixels from FBO ───────────────────────────────────────────
            image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);
            RenderSystem.bindTexture(offscreenTarget.getColorTextureId());
            image.downloadTexture(0, false);
            // OpenGL stores bottom-to-top; flip so row 0 = top of scene.
            image.flipY();

            return image;

        } catch (Exception e) {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
            return null;
        } finally {
            // ── Restore camera ─────────────────────────────────────────────────
            try {
                setCameraPos(camera, origPos);
                camera.setAnglesInternal(origYaw, origPitch);
            } catch (Exception ignored) {}

            // ── Restore main render target & projection ────────────────────────
            try {
                mainTarget.bindWrite(true);
                GlStateManager._viewport(0, 0, mainTarget.viewWidth, mainTarget.viewHeight);
                float fovRad = (float) Math.toRadians(mc.options.fov().get());
                float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
                Matrix4f origProj = new Matrix4f().setPerspective(fovRad, aspect, 0.05f,
                        mc.gameRenderer.getRenderDistance());
                RenderSystem.setProjectionMatrix(origProj, VertexSorting.DISTANCE_TO_ORIGIN);
            } catch (Exception ignored) {}
        }
    }

    // ── Camera helpers ─────────────────────────────────────────────────────────

    /**
     * Calls Camera.setPosition(double, double, double) which is protected in 1.20.1.
     * Uses cached reflection after first call.
     */
    private static void setCameraPos(Camera camera, Vec3 pos) {
        if (cameraSetPosition != null) {
            try {
                cameraSetPosition.invoke(camera, pos.x, pos.y, pos.z);
                return;
            } catch (Exception ignored) {}
        }
        // Reflection not available — try field manipulation for position.
        try {
            for (java.lang.reflect.Field f : Camera.class.getDeclaredFields()) {
                if (f.getType() == Vec3.class) {
                    f.setAccessible(true);
                    f.set(camera, pos);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Reflection resolution ──────────────────────────────────────────────────

    private static void resolveReflection(Minecraft mc) {
        if (reflectionResolved) return;
        reflectionResolved = true;

        // Camera.setPosition(double, double, double) — protected
        try {
            Method m = Camera.class.getDeclaredMethod("setPosition", double.class, double.class, double.class);
            m.setAccessible(true);
            cameraSetPosition = m;
        } catch (Exception e) {
            // Try SRG name
            try {
                for (Method m : Camera.class.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == double.class && p[1] == double.class && p[2] == double.class) {
                        m.setAccessible(true);
                        cameraSetPosition = m;
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        // GameRenderer.renderLevel(float, long, PoseStack)
        try {
            Method m = mc.gameRenderer.getClass().getMethod("renderLevel", float.class, long.class, PoseStack.class);
            m.setAccessible(true);
            gameRendererRenderLevel = m;
        } catch (NoSuchMethodException ex) {
            try {
                for (Method m : mc.gameRenderer.getClass().getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == float.class && p[1] == long.class && p[2] == PoseStack.class) {
                        m.setAccessible(true);
                        gameRendererRenderLevel = m;
                        break;
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static void renderLevelReflective(Minecraft mc, PoseStack poseStack, float partialTick) {
        if (gameRendererRenderLevel != null) {
            try {
                gameRendererRenderLevel.invoke(mc.gameRenderer, partialTick, System.nanoTime(), poseStack);
            } catch (Exception ignored) {}
        }
    }

    // ── Offscreen FBO management ───────────────────────────────────────────────

    private static void ensureOffscreenTarget(int resolution) {
        if (offscreenTarget != null && offscreenResolution == resolution) return;

        if (offscreenTarget != null) {
            try { offscreenTarget.destroyBuffers(); } catch (Exception ignored) {}
            offscreenTarget = null;
        }

        try {
            // RenderTarget is abstract in 1.20.1 with no abstract methods — subclass anonymously.
            // RenderTarget(boolean useDepth) then resize via createBuffers(w, h, onOSX).
            offscreenTarget = new RenderTarget(true) {};
            offscreenTarget.createBuffers(resolution, resolution, Minecraft.ON_OSX);
            offscreenTarget.setClearColor(0f, 0f, 0f, 0f);
            offscreenResolution = resolution;
        } catch (Exception e) {
            offscreenTarget = null;
            offscreenResolution = -1;
        }
    }

    // ── Dimension resolution ───────────────────────────────────────────────────

    private static Level resolveSampleLevel(ResourceLocation destDimension, Level clientLevel) {
        if (clientLevel == null) return null;
        if (clientLevel.dimension().location().equals(destDimension)) return clientLevel;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            ResourceKey<Level> destKey =
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, destDimension);
            ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(destKey);
            if (serverLevel != null) return serverLevel;
        }
        return null;
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    /** Call on world unload to free the offscreen FBO. */
    public static void destroyOffscreenTarget() {
        if (offscreenTarget != null) {
            try { offscreenTarget.destroyBuffers(); } catch (Exception ignored) {}
            offscreenTarget = null;
            offscreenResolution = -1;
        }
        reflectionResolved = false;
        cameraSetPosition = null;
        gameRendererRenderLevel = null;
    }
}
