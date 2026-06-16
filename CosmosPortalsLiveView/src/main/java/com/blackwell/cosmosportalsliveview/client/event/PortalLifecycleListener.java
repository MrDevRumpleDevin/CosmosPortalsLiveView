package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.level.BlockEvent;

@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class PortalLifecycleListener {
    
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Block block = event.getState().getBlock();
        
        if (isCosmosPortalBlock(block)) {
            PortalLiveViewManager.removePortal(event.getPos());
        }
    }
    
    private static boolean isCosmosPortalBlock(Block block) {
        try {
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key != null) {
                String modId = key.getNamespace();
                String blockName = key.getPath();
                return modId.equals("cosmosportals") && blockName.contains("portal");
            }
        } catch (Exception e) {
        }
        return false;
    }
}
