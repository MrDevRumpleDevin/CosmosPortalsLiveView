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
import net.minecraft.world.phys.Vec3;
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

    /**
     * Incremental update — only captures portals that are within render distance of the player
     * and have live view enabled. Captures happen on a background thread to avoid frame stutter.
     *
     * @param playerPos      current player position (nullable; if null, no distance culling)
     * @param maxDistanceSq  squared block distance cutoff
     */
    public static void updatePortalsIncremental(Level level, long captureInterval,
                                                 int portalsPerFrame,
                                                 Vec3 playerPos, double maxDistanceSq) {
        int updated = 0;
        long currentTime = System.currentTimeMillis();

        while (!updateQueue.isEmpty() && updated < portalsPerFrame) {
            BlockPos pos = updateQueue.poll();
            PortalViewData data = activePortals.get(pos);
            if (data == null) continue;

            // Proximity cull — skip if player is too far away
            if (playerPos != null) {
                double dx = pos.getX() + 0.5 - playerPos.x;
                double dy = pos.getY() + 0.5 - playerPos.y;
                double dz = pos.getZ() + 0.5 - playerPos.z;
                if (dx*dx + dy*dy + dz*dz > maxDistanceSq) continue;
            }

            if (data.shouldUpdateCapture(currentTime, captureInterval)) {
                // Capture on background thread — pass a snapshot of the level reference.
                // NativeImage allocation and pixel fill happen off the main thread;
                // DynamicTexture.upload() is called back on the render thread via markDirty().
                LocalizedChunkCapture.captureAsync(data, level);
                updated++;
            }
        }

        // Re-queue portals due for refresh (proximity check deferred to next poll)
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
