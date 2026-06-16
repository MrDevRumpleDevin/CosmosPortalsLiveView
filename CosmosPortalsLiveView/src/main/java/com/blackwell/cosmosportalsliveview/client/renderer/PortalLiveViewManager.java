package com.blackwell.cosmosportalsliveview.client.renderer;

import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.blackwell.cosmosportalsliveview.ModLogger;

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
    
    public static boolean addPortalIfNew(BlockPos pos, Level level) {
        if (activePortals.containsKey(pos)) {
            ModLogger.logWarn("Portal at " + pos + " already tracked");
            return false;
        }
        
        try {
            ModLogger.logInfo("Adding new portal at: " + pos + " in dimension: " + level.dimension());
            PortalViewData data = new PortalViewData(null, pos, level.dimension());
            activePortals.put(pos, data);
            
            Set<BlockPos> dimSet = dimensionPortals.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
            dimSet.add(pos);
            updateQueue.offer(pos);
            
            ModLogger.logInfo("Successfully added portal. Total active portals: " + activePortals.size());
            return true;
        } catch (Exception e) {
            ModLogger.logException("Error adding portal at " + pos, e);
            return false;
        }
    }
    
    public static void addPortal(Object entity, BlockPos pos, ResourceLocation dim) {
        if (entity == null) return;
        
        ModLogger.logInfo("Adding portal from entity at: " + pos);
        PortalViewData data = new PortalViewData(entity, pos, dim);
        activePortals.put(pos, data);
        
        dimensionPortals.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(pos);
        updateQueue.offer(pos);
        ModLogger.logInfo("Portal added. Total active: " + activePortals.size());
    }
    
    public static void updatePortalsIncremental(Level level, long captureInterval, int portalsPerFrame) {
        int updated = 0;
        long currentTime = System.currentTimeMillis();
        
        while (!updateQueue.isEmpty() && updated < portalsPerFrame) {
            BlockPos pos = updateQueue.poll();
            PortalViewData data = activePortals.get(pos);
            
            if (data != null && data.shouldUpdateCapture(currentTime, captureInterval)) {
                try {
                    ModLogger.logInfo("Updating portal capture at: " + pos);
                    LocalizedChunkCapture.captureLocalizedPortalView(data, level);
                    updated++;
                } catch (Exception e) {
                    ModLogger.logException("Error capturing portal at " + pos, e);
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
            ModLogger.logInfo("Removing portal at: " + pos);
            data.cleanup();
            dimensionPortals.forEach((dim, positions) -> {
                positions.remove(pos);
                if (positions.isEmpty()) dimensionPortals.remove(dim);
            });
            ModLogger.logInfo("Portal removed. Total active: " + activePortals.size());
        }
        updateQueue.remove(pos);
    }
    
    public static void cleanup() {
        ModLogger.logInfo("Cleaning up all portals. Total to cleanup: " + activePortals.size());
        activePortals.forEach((pos, data) -> {
            try {
                data.cleanup();
            } catch (Exception e) {
                ModLogger.logException("Error cleaning up portal at " + pos, e);
            }
        });
        activePortals.clear();
        dimensionPortals.clear();
        updateQueue.clear();
        ModLogger.logInfo("Cleanup complete");
    }
    
    public static PortalViewData getPortalData(BlockPos pos) {
        return activePortals.get(pos);
    }
    
    public static Map<BlockPos, PortalViewData> getActivePortals() {
        return Collections.unmodifiableMap(activePortals);
    }
}
