package com.blackwell.cosmosportalsliveview.client.renderer;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.tcn.cosmosportals.core.blockentity.BlockEntityPortal;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class PortalViewData {
    public final BlockPos portalPos;
    public final ResourceLocation destDimension;
    public final BlockPos destPos;

    /**
     * Yaw/pitch captured when the player used the unlinked dimension container.
     * These define the "camera" direction for the live view perspective render.
     * Yaw: Minecraft convention (0=south, 90=west). Pitch: 0=horizontal, neg=up.
     */
    public final float destYaw;
    public final float destPitch;

    private DynamicTexture liveViewTexture;
    private long lastCaptureTime;
    /** Incremented each time a new texture is set, so ResourceLocation keys stay unique. */
    private volatile int textureVersion = 0;

    /**
     * Half-width and half-height of the portal quad in blocks, updated each render frame.
     * Used by the raycaster to compute a per-portal FOV so the live view behaves like a
     * window: larger portal = wider view, smaller portal = narrower view.
     */
    public volatile float portalHalfW = 1.0f;
    public volatile float portalHalfH = 1.0f;

    /**
     * World Y of the lowest portal block's floor (integer — the minY block's bottom face).
     * Used by the raycaster to anchor the eye at the correct height above the destination floor.
     */
    public volatile float portalBottomY = 0f;

    /**
     * Player's lateral offset from the portal center projected onto the portal's
     * right and up axes. Updated each render frame on the main thread.
     * Consumed by captureAsync to shift the virtual camera, producing parallax.
     *
     * parallaxOffsetForward is SIGNED:
     *   axis=X portals: positive = player on +Z side (south face), negative = -Z side (north face)
     *   axis=Z portals: positive = player on +X side (west face),  negative = -X side (east face)
     * The sign tells the raycaster which side the viewer is on, so it can flip
     * the right-axis direction accordingly.
     *
     * The "Smooth" variants are exponentially smoothed on the render thread each frame
     * and are what the raycaster actually consumes — this eliminates jitter from async gaps.
     */
    public volatile float parallaxOffsetRight   = 0f;
    public volatile float parallaxOffsetUp      = 0f;
    public volatile float parallaxOffsetForward = 2.0f; // signed — see above

    // Smoothed values — interpolated toward the raw offsets each frame.
    public volatile float smoothParallaxRight   = 0f;
    public volatile float smoothParallaxUp      = 0f;
    public volatile float smoothParallaxForward = 2.0f;

    /**
     * Destination hole offset in portal-local space (blocks).
     * destOffsetRight: shifts the eye left/right at the destination.
     * destOffsetUp:    shifts the eye up/down at the destination.
     * Adjusted via sneak+right-click with the wand, persisted in LiveViewState.
     */
    public volatile float destOffsetRight = 0f;
    public volatile float destOffsetUp    = 0f;

    /** Exponential smoothing factor per frame (0=no smoothing, 1=instant). 0.18 ≈ ~5 frame blend. */
    public static final float PARALLAX_SMOOTH = 0.18f;

    private final Set<ChunkPos> cachedChunks = new HashSet<>();
    private boolean needsUpdate = true;

    /** True while a background capture is in progress — prevents duplicate submissions. */
    private final AtomicBoolean captureInFlight = new AtomicBoolean(false);

    public PortalViewData(BlockEntityPortal entity, BlockPos portalPos) {
        this.portalPos = portalPos;
        this.destDimension = entity.destDimension;
        BlockPos dp = entity.getDestPos();
        this.destPos = (dp != null && !dp.equals(BlockPos.ZERO)) ? dp : null;

        // Read the yaw/pitch stored in destInfo.
        // getYaw() → player yaw at link time (MC convention: 0=south, 90=west, -90/270=east, 180=north)
        // getPitch() → player pitch at link time (MC convention: negative=up, positive=down)
        //
        // The raycaster fwdY = -sin(pitchRad), so positive pitch → looking down.
        // MC pitch -90 = straight up, 0 = horizontal, +90 = straight down — same sign convention.
        // No swap or negation needed; read straight.
        float yaw = 0f, pitch = 0f;
        try {
            yaw   = entity.destInfo.getYaw();
            pitch = entity.destInfo.getPitch();
        } catch (Exception ignored) {}
        this.destYaw   = yaw;
        this.destPitch = pitch;

        this.lastCaptureTime = 0;
    }

    public boolean shouldUpdateCapture(long currentTime, long captureInterval) {
        return (currentTime - lastCaptureTime) >= captureInterval || needsUpdate;
    }

    public DynamicTexture getTexture() {
        return liveViewTexture;
    }

    public void setTexture(DynamicTexture texture) {
        if (this.liveViewTexture != null && this.liveViewTexture != texture) {
            this.liveViewTexture.close();
        }
        this.liveViewTexture = texture;
        this.textureVersion++;
        // Set lastCaptureTime to 0 so shouldUpdateCapture fires immediately on the next
        // render frame after this one completes. captureInFlight prevents overlap.
        // The config captureIntervalMs now acts as a floor only when the raycaster is fast;
        // when it's slow the next capture starts as soon as the previous finishes.
        this.lastCaptureTime = 0;
        this.needsUpdate = false;
    }

    public int getTextureVersion() {
        return textureVersion;
    }

    public void cleanup() {
        if (liveViewTexture != null) {
            liveViewTexture.close();
            liveViewTexture = null;
        }
        cachedChunks.clear();
    }

    public void markForUpdate() {
        this.needsUpdate = true;
    }

    public boolean isCaptureInFlight() {
        return captureInFlight.get();
    }

    public void setCaptureInFlight(boolean inFlight) {
        captureInFlight.set(inFlight);
    }
}
