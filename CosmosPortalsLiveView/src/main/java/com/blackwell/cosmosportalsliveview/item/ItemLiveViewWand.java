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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Live View Wand.
 *
 *   Right-click portal        → toggle live view ON / OFF
 *   Shift + right-click portal → cycle face offset (z-depth of the rendered quad)
 */
public class ItemLiveViewWand extends Item {

    public ItemLiveViewWand(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click portal → toggle live view").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift+right-click  → cycle quad face offset").withStyle(ChatFormatting.GRAY));
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
            // Shift+click: cycle quad face z-offset
            float v = LiveViewState.cycleOffset(dockPos);
            player.displayClientMessage(
                    Component.literal(String.format("[LiveView] Face offset  →  %.1f", v))
                             .withStyle(ChatFormatting.YELLOW),
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

            // Debug info on toggle
            try {
                BlockEntityPortal portalBe = null;
                outer:
                for (BlockPos fp : findConnectedPortalBlocks(level, clickedPos, 64)) {
                    BlockEntity be = level.getBlockEntity(fp);
                    if (be instanceof BlockEntityPortal portal) { portalBe = portal; break outer; }
                }
                if (portalBe != null) {
                    BlockPos rawDest = portalBe.getDestPos();
                    float rawYaw   = portalBe.destInfo.getYaw();
                    float rawPitch = portalBe.destInfo.getPitch();

                    Set<BlockPos> frame2 = findConnectedPortalBlocks(level, clickedPos, 64);
                    int minX=Integer.MAX_VALUE, minY=Integer.MAX_VALUE, minZ=Integer.MAX_VALUE;
                    int maxX=Integer.MIN_VALUE, maxY=Integer.MIN_VALUE, maxZ=Integer.MIN_VALUE;
                    for (BlockPos bp : frame2) {
                        minX=Math.min(minX,bp.getX()); maxX=Math.max(maxX,bp.getX());
                        minY=Math.min(minY,bp.getY()); maxY=Math.max(maxY,bp.getY());
                        minZ=Math.min(minZ,bp.getZ()); maxZ=Math.max(maxZ,bp.getZ());
                    }

                    float dR = LiveViewState.getDestOffsetRight(dockPos);
                    float dU = LiveViewState.getDestOffsetUp(dockPos);

                    player.sendSystemMessage(Component.literal(
                        String.format("[LiveView] destPos=%s  dim=%s  yaw=%.1f  pitch=%.1f",
                            rawDest, portalBe.destDimension, rawYaw, rawPitch)
                    ).withStyle(ChatFormatting.AQUA));
                    player.sendSystemMessage(Component.literal(
                        String.format("[LiveView] bbox spanX=%d spanZ=%d  frameBlocks=%d  destOffR=%.1f  destOffU=%.1f",
                            maxX-minX, maxZ-minZ, frame2.size(), dR, dU)
                    ).withStyle(ChatFormatting.YELLOW));
                }
            } catch (Exception ignored) {}
        }

        return InteractionResult.SUCCESS;
    }

    public static BlockPos findDockPos(Level level, BlockPos portalPos) {
        Set<BlockPos> frame = findConnectedPortalBlocks(level, portalPos, 64);
        for (BlockPos fp : frame) {
            for (Direction dir : Direction.values()) {
                BlockEntity be = level.getBlockEntity(fp.relative(dir));
                if (be instanceof AbstractBlockEntityPortalDock) return be.getBlockPos();
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
                if (level.getBlockState(next).getBlock() instanceof BlockPortal) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return visited;
    }
}
