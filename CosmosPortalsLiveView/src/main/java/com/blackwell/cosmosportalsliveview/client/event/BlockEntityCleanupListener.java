package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.ModLogger;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.level.LevelEvent;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class BlockEntityCleanupListener {
    
    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ModLogger.logInfo("Level unloading, cleaning up portals");
            PortalLiveViewManager.cleanup();
        }
    }
    
    @SubscribeEvent
    public static void onWorldLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            ModLogger.logInfo("Level loaded, ready to scan for portals");
        }
    }
}
