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
     * Player's lateral offset from the portal center projected onto the portal's
     * right and up axes. Updated each render frame on the main thread.
     * Consumed by captureAsync to shift the virtual camera, producing parallax.
     */
    public volatile float parallaxOffsetRight = 0f;
    public volatile float parallaxOffsetUp    = 0f;

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
        this.lastCaptureTime = System.currentTimeMillis();
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
