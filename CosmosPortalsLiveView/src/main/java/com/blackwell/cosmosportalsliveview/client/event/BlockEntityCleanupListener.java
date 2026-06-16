package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;

import com.tcn.cosmosportals.core.blockentity.BlockEntityPortal;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class BlockEntityCleanupListener {

    /**
     * When a chunk unloads on the client, remove any tracked portals within it
     * to avoid rendering stale data for positions that are no longer accessible.
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;

        // ChunkEvent gives ChunkAccess; getBlockEntities() only exists on LevelChunk.
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof BlockEntityPortal) {
                PortalLiveViewManager.removePortal(be.getBlockPos());
            }
        }
    }

    /** Full cleanup when the client leaves a world. */
    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            PortalLiveViewManager.cleanup();
        }
    }
}
