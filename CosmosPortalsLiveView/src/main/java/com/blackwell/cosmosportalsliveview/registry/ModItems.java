package com.blackwell.cosmosportalsliveview.registry;

import com.blackwell.cosmosportalsliveview.CosmosPortalsLiveView;
import com.blackwell.cosmosportalsliveview.item.ItemLiveViewWand;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CosmosPortalsLiveView.MOD_ID);

    public static final RegistryObject<Item> LIVE_VIEW_WAND = ITEMS.register(
            "live_view_wand",
            () -> new ItemLiveViewWand(new Item.Properties().stacksTo(1))
    );
}
