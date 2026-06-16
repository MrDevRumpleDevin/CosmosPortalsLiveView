package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;

import com.tcn.cosmosportals.core.blockentity.AbstractBlockEntityPortalDock;
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

        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof BlockEntityPortal) {
                PortalLiveViewManager.removePortal(be.getBlockPos());
            }
        }
    }

    /**
     * When a block is broken on the client side, check if it's a dock.
     * If so: disable its live-view state and remove all adjacent tracked portals.
     * Also handles portal block breaks (removes tracker directly).
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            // Server-side BreakEvent fires on server; this class is CLIENT dist only.
            // We won't get here in normal usage — see note below.
        }
        // BlockEvent.BreakEvent fires on the logical side matching the level.
        // On an integrated (singleplayer) server this is server-side, but our
        // @OnlyIn(CLIENT) / Dist.CLIENT subscription limits us to client dist.
        // For client-side detection we rely on PortalLifecycleListener which handles
        // the portal block itself. Here we handle dock breaks via entity removal.
        handleBreak(event.getLevel() instanceof Level lv ? lv : null, event.getPos());
    }

    /**
     * When a block entity is removed from the world (covers break + explosion + any removal),
     * clean up stale tracking if it was a dock or a portal block entity.
     *
     * NOTE: BlockEntityCleanupListener is Dist.CLIENT. Forge fires BlockEvent.BreakEvent
     * on the server side in integrated SP, so we also hook the client-tick scan in
     * PortalRenderEventHandler to catch dock removals by checking if the BE is still present.
     */
    private static void handleBreak(Level level, BlockPos pos) {
        if (level == null) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AbstractBlockEntityPortalDock) {
            // Dock was broken — disable live-view and remove adjacent portals.
            LiveViewState.disable(pos);
            for (Direction dir : Direction.values()) {
                BlockPos adjacent = pos.relative(dir);
                if (PortalLiveViewManager.isTracked(adjacent)) {
                    PortalLiveViewManager.removePortal(adjacent);
                }
            }
        } else if (be instanceof BlockEntityPortal) {
            // Portal block itself was broken.
            PortalLiveViewManager.removePortal(pos);
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
