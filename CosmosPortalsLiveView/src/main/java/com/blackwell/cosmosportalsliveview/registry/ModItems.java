package com.blackwell.cosmosportalsliveview.registry;

import com.blackwell.cosmosportalsliveview.CosmosPortalsLiveView;
import com.blackwell.cosmosportalsliveview.item.ItemDestLeftRightWand;
import com.blackwell.cosmosportalsliveview.item.ItemDestUpDownWand;
import com.blackwell.cosmosportalsliveview.item.ItemLiveViewWand;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.minecraft.core.registries.Registries;

@Mod.EventBusSubscriber(modid = CosmosPortalsLiveView.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CosmosPortalsLiveView.MOD_ID);

    /** Original wand — toggle live view, cycle modes, face offset, debug info. */
    public static final RegistryObject<Item> LIVE_VIEW_WAND = ITEMS.register(
            "live_view_wand",
            () -> new ItemLiveViewWand(new Item.Properties().stacksTo(1))
    );

    /** Dedicated wand: right-click = dest LEFT, shift+right-click = dest RIGHT. */
    public static final RegistryObject<Item> DEST_LEFT_RIGHT_WAND = ITEMS.register(
            "dest_left_right_wand",
            () -> new ItemDestLeftRightWand(new Item.Properties().stacksTo(1))
    );

    /** Dedicated wand: right-click = dest UP, shift+right-click = dest DOWN. */
    public static final RegistryObject<Item> DEST_UP_DOWN_WAND = ITEMS.register(
            "dest_up_down_wand",
            () -> new ItemDestUpDownWand(new Item.Properties().stacksTo(1))
    );

    /**
     * Injects all Live View items into the CosmosPortals creative tab.
     * Tab resource key: cosmosportals:cosmos_portals
     */
    @SubscribeEvent
    public static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        ResourceKey<CreativeModeTab> cosmosTab = ResourceKey.create(
                Registries.CREATIVE_MODE_TAB,
                new ResourceLocation("cosmosportals", "cosmos_portals")
        );
        if (event.getTabKey().equals(cosmosTab)) {
            event.accept(LIVE_VIEW_WAND.get());
            event.accept(DEST_LEFT_RIGHT_WAND.get());
            event.accept(DEST_UP_DOWN_WAND.get());
        }
    }
}
