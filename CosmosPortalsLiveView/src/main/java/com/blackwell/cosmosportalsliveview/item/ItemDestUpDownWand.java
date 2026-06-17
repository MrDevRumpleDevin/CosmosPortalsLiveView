package com.blackwell.cosmosportalsliveview.item;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalViewData;
import com.tcn.cosmosportals.core.block.BlockPortal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Destination Up/Down Wand.
 *
 * Right-click on portal       → move destination quad UP   (+0.5 blocks)
 * Shift + right-click portal  → move destination quad DOWN (-0.5 blocks)
 */
public class ItemDestUpDownWand extends Item {

    private static final float NUDGE = 0.5f;

    public ItemDestUpDownWand(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click portal → dest UP   (+0.5)").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift+right-click  → dest DOWN (-0.5)").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos clickedPos = context.getClickedPos();

        if (!level.isClientSide()) return InteractionResult.SUCCESS;
        if (player == null) return InteractionResult.PASS;

        BlockState state = level.getBlockState(clickedPos);
        if (!(state.getBlock() instanceof BlockPortal)) return InteractionResult.PASS;

        BlockPos dockPos = ItemLiveViewWand.findDockPos(level, clickedPos);
        if (dockPos == null) {
            player.displayClientMessage(
                    Component.literal("[LiveView] No portal dock found nearby.").withStyle(ChatFormatting.RED),
                    true
            );
            return InteractionResult.FAIL;
        }

        boolean shift = player.isShiftKeyDown();
        float delta = shift ? -NUDGE : +NUDGE;
        float newVal = LiveViewState.nudgeDestUp(dockPos, delta);

        // Push into active PortalViewData immediately
        for (BlockPos fp : ItemLiveViewWand.findConnectedPortalBlocks(level, clickedPos, 64)) {
            PortalViewData pvd = PortalLiveViewManager.getActivePortals().get(fp);
            if (pvd != null) {
                pvd.destOffsetRight = LiveViewState.getDestOffsetRight(dockPos);
                pvd.destOffsetUp    = LiveViewState.getDestOffsetUp(dockPos);
                pvd.markForUpdate();
            }
        }

        String dir = shift ? "DOWN" : "UP";
        player.displayClientMessage(
                Component.literal(String.format("[LiveView] Dest %s  →  %.1f", dir, newVal))
                         .withStyle(ChatFormatting.AQUA),
                true
        );

        return InteractionResult.SUCCESS;
    }
}
