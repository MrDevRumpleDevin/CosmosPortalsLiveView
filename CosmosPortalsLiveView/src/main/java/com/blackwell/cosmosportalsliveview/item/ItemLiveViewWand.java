package com.blackwell.cosmosportalsliveview.item;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalLiveViewManager;
import com.blackwell.cosmosportalsliveview.client.renderer.PortalViewData;
import com.tcn.cosmosportals.core.block.BlockPortal;
import com.tcn.cosmosportals.core.blockentity.AbstractBlockEntityPortalDock;
import com.tcn.cosmosportals.core.blockentity.BlockEntityPortal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class ItemLiveViewWand extends Item {

    public ItemLiveViewWand(Properties properties) {
        super(properties);
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

        BlockPos dockPos = findDockPos(level, clickedPos);
        if (dockPos == null) {
            player.displayClientMessage(
                    Component.literal("[LiveView] No portal dock found nearby.").withStyle(ChatFormatting.RED),
                    true
            );
            return InteractionResult.FAIL;
        }

        if (player.isShiftKeyDown()) {
            // Sneak + right-click: cycle the quad offset
            float newOffset = LiveViewState.cycleOffset(dockPos);
            player.displayClientMessage(
                    Component.literal("[LiveView] Quad offset: ")
                            .append(Component.literal(String.format("%.1f", newOffset))
                                    .withStyle(ChatFormatting.YELLOW)),
                    true
            );
        } else {
            // Right-click: toggle live view on/off
            boolean nowEnabled = LiveViewState.toggle(dockPos);
            player.displayClientMessage(
                    Component.literal("[LiveView] Live View: ")
                            .append(Component.literal(nowEnabled ? "ON" : "OFF")
                                    .withStyle(nowEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY)),
                    true
            );

            // Debug: print raw destPos, raw getYaw/getPitch, and the swapped values
            // used by PortalViewData (i.e., what the raycaster actually uses).
            try {
                BlockEntity dockBe = level.getBlockEntity(dockPos);
                // Find the portal BE for this dock to read raw destInfo
                BlockEntityPortal portalBe = null;
                outer:
                for (BlockPos fp : findConnectedPortalBlocks(level, clickedPos, 64)) {
                    BlockEntity be = level.getBlockEntity(fp);
                    if (be instanceof BlockEntityPortal portal) {
                        portalBe = portal;
                        break outer;
                    }
                }
                if (portalBe != null) {
                    BlockPos rawDest = portalBe.getDestPos();
                    float rawGetYaw   = portalBe.destInfo.getYaw();   // contains actual PITCH
                    float rawGetPitch = portalBe.destInfo.getPitch(); // contains actual YAW
                    // After the swap fix: destYaw=getPitch(), destPitch=getYaw()
                    float usedYaw   = rawGetPitch; // what raycaster uses as yaw
                    float usedPitch = rawGetYaw;   // what raycaster uses as pitch

                    // Look up from PortalViewData for the actual used values
                    PortalViewData pvd = PortalLiveViewManager.getActivePortals().get(clickedPos);

                    player.sendSystemMessage(Component.literal(
                        String.format("[LiveView DEBUG] destPos=%s  dim=%s",
                            rawDest, portalBe.destDimension)
                    ).withStyle(ChatFormatting.AQUA));
                    player.sendSystemMessage(Component.literal(
                        String.format("[LiveView DEBUG] raw getYaw()=%.1f  raw getPitch()=%.1f",
                            rawGetYaw, rawGetPitch)
                    ).withStyle(ChatFormatting.GRAY));
                    player.sendSystemMessage(Component.literal(
                        String.format("[LiveView DEBUG] used yaw=%.1f  used pitch=%.1f  (after swap fix)",
                            usedYaw, usedPitch)
                    ).withStyle(ChatFormatting.GREEN));
                    if (pvd != null) {
                        player.sendSystemMessage(Component.literal(
                            String.format("[LiveView DEBUG] PortalViewData: destYaw=%.1f  destPitch=%.1f",
                                pvd.destYaw, pvd.destPitch)
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
            } catch (Exception debugEx) {
                player.sendSystemMessage(Component.literal(
                    "[LiveView DEBUG] error: " + debugEx.getMessage()
                ).withStyle(ChatFormatting.RED));
            }
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Flood-fill connected portal blocks, then check adjacent blocks for a dock BE.
     */
    public static BlockPos findDockPos(Level level, BlockPos portalPos) {
        Set<BlockPos> frame = findConnectedPortalBlocks(level, portalPos, 64);
        for (BlockPos fp : frame) {
            for (Direction dir : Direction.values()) {
                BlockEntity be = level.getBlockEntity(fp.relative(dir));
                if (be instanceof AbstractBlockEntityPortalDock) {
                    return be.getBlockPos();
                }
            }
        }
        return null;
    }

    public static Set<BlockPos> findConnectedPortalBlocks(Level level, BlockPos origin, int maxBlocks) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (visited.contains(next)) continue;
                BlockState nextState = level.getBlockState(next);
                if (nextState.getBlock() instanceof BlockPortal) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return visited;
    }
}
