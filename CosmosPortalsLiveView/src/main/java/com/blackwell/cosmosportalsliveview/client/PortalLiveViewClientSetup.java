package com.blackwell.cosmosportalsliveview.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;

import com.blackwell.cosmosportalsliveview.ModLogger;
import com.blackwell.cosmosportalsliveview.client.event.PortalRenderEventHandler;
import com.blackwell.cosmosportalsliveview.client.event.PortalLifecycleListener;
import com.blackwell.cosmosportalsliveview.client.event.BlockEntityCleanupListener;
import com.blackwell.cosmosportalsliveview.client.event.PortalScannerEventHandler;

@OnlyIn(Dist.CLIENT)
public class PortalLiveViewClientSetup {
    
    public static void setupClient() {
        ModLogger.logInfo("Setting up CosmosPortalsLiveView client");
        
        try {
            MinecraftForge.EVENT_BUS.register(PortalRenderEventHandler.class);
            ModLogger.logInfo("Registered PortalRenderEventHandler");
        } catch (Exception e) {
            ModLogger.logException("Failed to register PortalRenderEventHandler", e);
        }
        
        try {
            MinecraftForge.EVENT_BUS.register(PortalLifecycleListener.class);
            ModLogger.logInfo("Registered PortalLifecycleListener");
        } catch (Exception e) {
            ModLogger.logException("Failed to register PortalLifecycleListener", e);
        }
        
        try {
            MinecraftForge.EVENT_BUS.register(BlockEntityCleanupListener.class);
            ModLogger.logInfo("Registered BlockEntityCleanupListener");
        } catch (Exception e) {
            ModLogger.logException("Failed to register BlockEntityCleanupListener", e);
        }
        
        try {
            MinecraftForge.EVENT_BUS.register(PortalScannerEventHandler.class);
            ModLogger.logInfo("Registered PortalScannerEventHandler");
        } catch (Exception e) {
            ModLogger.logException("Failed to register PortalScannerEventHandler", e);
        }
        
        ModLogger.logInfo("CosmosPortalsLiveView client setup complete");
    }
}
