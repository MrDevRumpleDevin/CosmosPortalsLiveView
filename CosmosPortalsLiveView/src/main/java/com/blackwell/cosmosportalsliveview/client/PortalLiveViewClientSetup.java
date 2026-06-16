package com.blackwell.cosmosportalsliveview.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;

import com.blackwell.cosmosportalsliveview.client.event.PortalRenderEventHandler;
import com.blackwell.cosmosportalsliveview.client.event.PortalLifecycleListener;
import com.blackwell.cosmosportalsliveview.client.event.BlockEntityCleanupListener;

@OnlyIn(Dist.CLIENT)
public class PortalLiveViewClientSetup {
    
    public static void setupClient() {
        MinecraftForge.EVENT_BUS.register(PortalRenderEventHandler.class);
        MinecraftForge.EVENT_BUS.register(PortalLifecycleListener.class);
        MinecraftForge.EVENT_BUS.register(BlockEntityCleanupListener.class);
    }
}
