package com.blackwell.cosmosportalsliveview;

import com.blackwell.cosmosportalsliveview.registry.ModItems;
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

@Mod(CosmosPortalsLiveView.MOD_ID)
public class CosmosPortalsLiveView {

    public static final String MOD_ID = "cosmosportals_liveview";
    public static IEventBus MOD_EVENT_BUS;

    public CosmosPortalsLiveView() {
        MOD_EVENT_BUS = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PortalLiveViewConfig.SPEC, "cosmosportals-liveview-client.toml");

        // Register items
        ModItems.ITEMS.register(MOD_EVENT_BUS);

        MOD_EVENT_BUS.addListener(this::onFMLCommonSetup);
        MOD_EVENT_BUS.addListener(this::onFMLClientSetup);
    }

    private void onFMLCommonSetup(final FMLCommonSetupEvent event) {
    }

    @OnlyIn(Dist.CLIENT)
    private void onFMLClientSetup(final FMLClientSetupEvent event) {
        // Event handlers are registered via @Mod.EventBusSubscriber — nothing needed here.
    }
}
