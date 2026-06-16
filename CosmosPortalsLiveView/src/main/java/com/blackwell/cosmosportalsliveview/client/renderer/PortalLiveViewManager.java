package com.blackwell.cosmosportalsliveview.client.renderer;

import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.tcn.cosmosportals.core.blockentity.BlockEntityPortal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class PortalLiveViewManager {
    private static final Map<BlockPos, PortalViewData> activePortals = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Set<BlockPos>> dimensionPortals = new ConcurrentHashMap<>();
    private static final Queue<BlockPos> updateQueue = new ConcurrentLinkedQueue<>();

    /** Called from the chunk-load handler whenever a BlockEntityPortal is found. */
    public static void addPortal(BlockEntityPortal entity, BlockPos pos) {
        if (entity == null) return;
        if (activePortals.containsKey(pos)) return;

        PortalViewData data = new PortalViewData(entity, pos);
        activePortals.put(pos, data);

        dimensionPortals
                .computeIfAbsent(data.destDimension, k -> ConcurrentHashMap.newKeySet())
                .add(pos);
        updateQueue.offer(pos);
    }

    public static void updatePortalsIncremental(Level level, long captureInterval, int portalsPerFrame) {
        int updated = 0;
        long currentTime = System.currentTimeMillis();

        while (!updateQueue.isEmpty() && updated < portalsPerFrame) {
            BlockPos pos = updateQueue.poll();
            PortalViewData data = activePortals.get(pos);

            if (data != null && data.shouldUpdateCapture(currentTime, captureInterval)) {
                try {
                    LocalizedChunkCapture.captureLocalizedPortalView(data, level);
                    updated++;
                } catch (Exception e) {
                    // swallow; never crash the render thread
                }
            }
        }

        // Re-queue portals due for refresh
        for (Map.Entry<BlockPos, PortalViewData> entry : activePortals.entrySet()) {
            if (entry.getValue().shouldUpdateCapture(currentTime, captureInterval)) {
                updateQueue.offer(entry.getKey());
            }
        }
    }

    public static void removePortal(BlockPos pos) {
        PortalViewData data = activePortals.remove(pos);
        if (data != null) {
            data.cleanup();
            dimensionPortals.forEach((dim, positions) -> {
                positions.remove(pos);
                if (positions.isEmpty()) dimensionPortals.remove(dim);
            });
        }
        updateQueue.remove(pos);
    }

    public static void cleanup() {
        activePortals.forEach((pos, data) -> {
            try { data.cleanup(); } catch (Exception ignored) {}
        });
        activePortals.clear();
        dimensionPortals.clear();
        updateQueue.clear();
    }

    public static PortalViewData getPortalData(BlockPos pos) {
        return activePortals.get(pos);
    }

    public static Map<BlockPos, PortalViewData> getActivePortals() {
        return Collections.unmodifiableMap(activePortals);
    }

    public static boolean isTracked(BlockPos pos) {
        return activePortals.containsKey(pos);
    }

    /**
     * Marks all portals adjacent to {@code dockPos} for immediate texture refresh.
     * Called when the player toggles live view mode in the dock GUI.
     */
    public static void markPortalsForDockUpdate(BlockPos dockPos) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = dockPos.relative(dir);
            PortalViewData data = activePortals.get(adjacent);
            if (data != null) {
                data.markForUpdate();
                updateQueue.offer(adjacent);
            }
        }
    }
}
