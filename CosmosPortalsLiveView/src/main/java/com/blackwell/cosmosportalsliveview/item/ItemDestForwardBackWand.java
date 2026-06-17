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
 * Destination Forward/Back Wand.
 *
 * Right-click on portal       → move destination eye FORWARD (+0.5 blocks deeper into dest room)
 * Shift + right-click portal  → move destination eye BACK    (-0.5 blocks, toward portal face)
 * Ctrl  + right-click portal  → toggle wireframe outline of dest hole at destination
 */
public class ItemDestForwardBackWand extends Item {

    private static final float NUDGE = 0.5f;

    public ItemDestForwardBackWand(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click portal → dest FORWARD (+0.5)").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift+right-click  → dest BACK    (-0.5)").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Ctrl+right-click   → toggle wireframe").withStyle(ChatFormatting.GRAY));
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

        // Detect Ctrl via GLFW key state on the client side
        boolean ctrl = org.lwjgl.glfw.GLFW.glfwGetKey(
                net.minecraft.client.Minecraft.getInstance().getWindow().getWindow(),
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(
                net.minecraft.client.Minecraft.getInstance().getWindow().getWindow(),
                org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

        if (ctrl) {
            // Toggle wireframe
            boolean on = LiveViewState.toggleWireframe(dockPos);
            player.displayClientMessage(
                    Component.literal("[LiveView] Wireframe " + (on ? "ON" : "OFF"))
                             .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
                    true
            );
            return InteractionResult.SUCCESS;
        }

        boolean shift = player.isShiftKeyDown();
        float delta = shift ? -NUDGE : +NUDGE;
        float newVal = LiveViewState.nudgeDestForward(dockPos, delta);

        // Push into active PortalViewData immediately
        for (BlockPos fp : ItemLiveViewWand.findConnectedPortalBlocks(level, clickedPos, 64)) {
            PortalViewData pvd = PortalLiveViewManager.getActivePortals().get(fp);
            if (pvd != null) {
                pvd.destOffsetRight   = LiveViewState.getDestOffsetRight(dockPos);
                pvd.destOffsetUp      = LiveViewState.getDestOffsetUp(dockPos);
                pvd.destOffsetForward = LiveViewState.getDestOffsetForward(dockPos);
                pvd.markForUpdate();
            }
        }

        String dir = shift ? "BACK" : "FORWARD";
        player.displayClientMessage(
                Component.literal(String.format("[LiveView] Dest %s  →  %.1f", dir, newVal))
                         .withStyle(ChatFormatting.AQUA),
                true
        );

        return InteractionResult.SUCCESS;
    }
}
