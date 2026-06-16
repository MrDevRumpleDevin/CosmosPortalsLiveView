package com.blackwell.cosmosportalsliveview.client.event;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;

import com.tcn.cosmosportals.client.screen.ScreenPortalDock;
import com.tcn.cosmosportals.core.blockentity.AbstractBlockEntityPortalDock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Injects a "Live View" toggle button into ScreenPortalDock after it initialises.
 * The button is positioned just below the colour button in the existing UI.
 */
@Mod.EventBusSubscriber(modid = "cosmosportals_liveview", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class DockScreenOverlayListener {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof ScreenPortalDock dockScreen)) return;

        // Retrieve the block entity from the container menu via reflection-free approach:
        // ScreenPortalDock extends CosmosScreenUIModeBE which holds the menu.
        // The menu's BE position is accessible via the container.
        BlockPos dockPos = getDockPos(dockScreen);
        if (dockPos == null) return;

        boolean currentlyEnabled = LiveViewState.isEnabled(dockPos);

        // Place the button in the top-right corner of the screen, just above the UI edge.
        // guiLeft and guiTop are protected in Screen; we read them through the known formula.
        int guiLeft = (screen.width - 194) / 2;
        int guiTop = (screen.height - 256) / 2;

        // Position: top-right inside the GUI, stacked below the colour button area (y≈133)
        int btnX = guiLeft + 166;
        int btnY = guiTop + 133;
        int btnW = 22;
        int btnH = 10;

        Button liveBtn = Button.builder(
                getLiveViewLabel(currentlyEnabled),
                btn -> {
                    LiveViewState.toggle(dockPos);
                    // Update label on click
                    btn.setMessage(getLiveViewLabel(LiveViewState.isEnabled(dockPos)));
                    // Also mark all portal views for that dock as needing an update
                    com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager
                            .markPortalsForDockUpdate(dockPos);
                })
                .pos(btnX, btnY)
                .size(btnW, btnH)
                .build();

        event.addListener(liveBtn);
    }

    private static Component getLiveViewLabel(boolean enabled) {
        return enabled
                ? Component.literal("§aLV§r")   // green when on
                : Component.literal("§7LV§r");  // gray when off
    }

    /**
     * Extracts the dock's BlockPos from the screen's container menu.
     * ContainerPortalDock has a getBlockPos() method (inherited from CosmosContainerMenuBlockEntity).
     */
    private static BlockPos getDockPos(ScreenPortalDock screen) {
        try {
            // ScreenPortalDock extends CosmosScreenUIModeBE<ContainerPortalDock>
            // field f_97732_ is the AbstractContainerMenu (obfuscated: "menu")
            var menuField = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class
                    .getDeclaredField("f_97732_");
            menuField.setAccessible(true);
            Object menu = menuField.get(screen);
            if (menu == null) return null;

            // ContainerPortalDock extends CosmosContainerMenuBlockEntity which has getBlockPos()
            var getPosMethod = menu.getClass().getMethod("getBlockPos");
            return (BlockPos) getPosMethod.invoke(menu);
        } catch (Exception e) {
            // Fallback: try "menu" (mapped name)
            try {
                var menuField = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class
                        .getDeclaredField("menu");
                menuField.setAccessible(true);
                Object menu = menuField.get(screen);
                if (menu == null) return null;
                var getPosMethod = menu.getClass().getMethod("getBlockPos");
                return (BlockPos) getPosMethod.invoke(menu);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
