package com.blackwell.cosmosportalsliveview.item;

import com.blackwell.cosmosportalsliveview.client.LiveViewState;
import com.tcn.cosmosportals.core.block.BlockPortal;
import com.tcn.cosmosportals.core.blockentity.AbstractBlockEntityPortalDock;
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

        // Only fire on client; only when sneaking
        if (!level.isClientSide()) return InteractionResult.SUCCESS;
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;

        BlockState state = level.getBlockState(clickedPos);
        if (!(state.getBlock() instanceof BlockPortal)) return InteractionResult.PASS;

        // Find the controlling dock
        BlockPos dockPos = findDockPos(level, clickedPos);
        if (dockPos == null) {
            player.displayClientMessage(
                    Component.literal("[LiveView] No portal dock found nearby.").withStyle(ChatFormatting.RED),
                    true
            );
            return InteractionResult.FAIL;
        }

        boolean nowEnabled = LiveViewState.toggle(dockPos);

        player.displayClientMessage(
                Component.literal("[LiveView] Live View: ")
                        .append(Component.literal(nowEnabled ? "ON" : "OFF")
                                .withStyle(nowEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY)),
                true
        );

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
