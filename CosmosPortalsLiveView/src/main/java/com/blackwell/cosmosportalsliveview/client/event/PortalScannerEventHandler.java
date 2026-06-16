package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.ModLogger;
import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class PortalScannerEventHandler {
    
    private static int scanCounter = 0;
    private static final int SCAN_INTERVAL = 20; // Scan every 20 ticks
    
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!PortalLiveViewConfig.ENABLE_LIVE_VIEW.get()) return;
        
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        
        // Scan for portals periodically
        scanCounter++;
        if (scanCounter >= SCAN_INTERVAL) {
            scanCounter = 0;
            scanForCosmosPortals(minecraft.level);
        }
        
        // Update portal textures
        long captureInterval = PortalLiveViewConfig.CAPTURE_INTERVAL_MS.get();
        int portalsPerFrame = PortalLiveViewConfig.PORTALS_PER_FRAME.get();
        PortalLiveViewManager.updatePortalsIncremental(minecraft.level, captureInterval, portalsPerFrame);
    }
    
    private static void scanForCosmosPortals(Level level) {
        ModLogger.logInfo("Scanning for CosmosPortals portals...");
        
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        
        BlockPos playerPos = minecraft.player.blockPosition();
        int searchRadius = PortalLiveViewConfig.CAPTURE_RADIUS_CHUNKS.get() * 16;
        
        // Search in a cube around the player
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int checked = 0;
        int found = 0;
        
        for (int x = playerPos.getX() - searchRadius; x <= playerPos.getX() + searchRadius; x++) {
            for (int y = playerPos.getY() - 16; y <= playerPos.getY() + 16; y++) {
                for (int z = playerPos.getZ() - searchRadius; z <= playerPos.getZ() + searchRadius; z++) {
                    mutablePos.set(x, y, z);
                    if (isCosmosPortalBlock(level, mutablePos)) {
                        if (PortalLiveViewManager.addPortalIfNew(mutablePos, level)) {
                            ModLogger.logInfo("Found new CosmosPortal at: " + mutablePos);
                            found++;
                        }
                        checked++;
                    }
                }
            }
        }
        
        if (found > 0) {
            ModLogger.logInfo("Scanner found " + found + " new portal(s) out of " + checked + " blocks checked");
        }
    }
    
    private static boolean isCosmosPortalBlock(Level level, BlockPos pos) {
        try {
            String blockName = level.getBlockState(pos).getBlock().toString();
            return blockName.contains("cosmosportals") && blockName.contains("portal");
        } catch (Exception e) {
            return false;
        }
    }
}
