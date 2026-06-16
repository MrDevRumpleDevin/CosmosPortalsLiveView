package com.blackwell.cosmosportalsliveview.client.renderer;

import java.util.HashSet;
import java.util.Set;

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

    private DynamicTexture liveViewTexture;
    private long lastCaptureTime;

    private final Set<ChunkPos> cachedChunks = new HashSet<>();
    private boolean needsUpdate = true;

    /**
     * @param entity    the {@link BlockEntityPortal} this data wraps
     * @param portalPos the world position of the portal block
     */
    public PortalViewData(BlockEntityPortal entity, BlockPos portalPos) {
        this.portalPos = portalPos;
        // Read the actual destination dimension and position from the block entity.
        // destDimension is a public field; getDestPos() delegates to destInfo.getPos().
        this.destDimension = entity.destDimension;
        BlockPos dp = entity.getDestPos();
        // Treat BlockPos.ZERO / missing info as null so the capture skips gracefully.
        this.destPos = (dp != null && !dp.equals(BlockPos.ZERO)) ? dp : null;
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
        this.lastCaptureTime = System.currentTimeMillis();
        this.needsUpdate = false;
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
}
