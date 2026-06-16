package com.blackwell.cosmosportalsliveview;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;
import com.blackwell.cosmosportalsliveview.client.PortalLiveViewClientSetup;

@Mod(CosmosPortalsLiveView.MOD_ID)
public class CosmosPortalsLiveView {
    
    public static final String MOD_ID = "cosmosportals_liveview";
    public static IEventBus MOD_EVENT_BUS;
    
    public CosmosPortalsLiveView() {
        // Get the mod event bus using the correct Forge 47.3.0 API
        MOD_EVENT_BUS = FMLJavaModLoadingContext.get().getModEventBus();
        
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PortalLiveViewConfig.SPEC, "cosmosportals-liveview-client.toml");
        
        MOD_EVENT_BUS.addListener(this::onFMLCommonSetup);
        MOD_EVENT_BUS.addListener(this::onFMLClientSetup);
    }
    
    private void onFMLCommonSetup(final FMLCommonSetupEvent event) {
    }
    
    @OnlyIn(Dist.CLIENT)
    private void onFMLClientSetup(final FMLClientSetupEvent event) {
        PortalLiveViewClientSetup.setupClient();
    }
}
