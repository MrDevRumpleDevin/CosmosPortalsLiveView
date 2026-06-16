package com.blackwell.cosmosportalsliveview.client.renderer;

import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class PortalLiveViewManager {
    private static final Map<BlockPos, PortalViewData> activePortals = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Set<BlockPos>> dimensionPortals = new ConcurrentHashMap<>();
    private static final Queue<BlockPos> updateQueue = new ConcurrentLinkedQueue<>();
    
    public static void addPortal(Object entity, BlockPos pos, ResourceLocation dim) {
        if (entity == null) return;
        
        PortalViewData data = new PortalViewData(entity, pos, dim);
        activePortals.put(pos, data);
        
        dimensionPortals.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(pos);
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
                }
            }
        }
        
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
            try {
                data.cleanup();
            } catch (Exception e) {
            }
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
}
