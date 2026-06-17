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
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Live View Wand — right-click a portal block to configure the live view.
 *
 * Controls:
 *   Right-click               → toggle live view ON / OFF
 *   Sneak + right-click       → cycle wand mode (see below), then nudge in that direction
 *   Sneak + right-click (air) → if no mode selected yet, prints current offsets
 *
 * Wand modes (cycled with sneak+click on a portal):
 *   DEST_UP    — nudge destination hole up   (+0.5 blocks)
 *   DEST_DOWN  — nudge destination hole down (-0.5 blocks)
 *   DEST_RIGHT — nudge destination hole right (+0.5 blocks)
 *   DEST_LEFT  — nudge destination hole left  (-0.5 blocks)
 *   DEST_RESET — reset both dest offsets to 0
 *   FACE_CYCLE — cycle the quad face offset (z-depth adjustment)
 */
public class ItemLiveViewWand extends Item {

    /** Per-player wand mode, stored client-side by player name. */
    private static final Map<String, WandMode> playerModes = new ConcurrentHashMap<>();

    private enum WandMode {
        DEST_UP("▲ Dest Up", ChatFormatting.AQUA),
        DEST_DOWN("▼ Dest Down", ChatFormatting.AQUA),
        DEST_RIGHT("► Dest Right", ChatFormatting.AQUA),
        DEST_LEFT("◄ Dest Left", ChatFormatting.AQUA),
        DEST_RESET("✕ Reset Dest Offsets", ChatFormatting.RED),
        FACE_CYCLE("⇄ Face Offset", ChatFormatting.YELLOW);

        final String label;
        final ChatFormatting color;
        WandMode(String label, ChatFormatting color) { this.label = label; this.color = color; }

        WandMode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public ItemLiveViewWand(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click portal → toggle live view").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Sneak+right-click → cycle mode & nudge").withStyle(ChatFormatting.GRAY));
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
            // Sneak+click: advance mode then apply nudge
            String pid = player.getGameProfile().getName();
            WandMode current = playerModes.getOrDefault(pid, WandMode.DEST_UP);
            WandMode next = current.next();
            playerModes.put(pid, next);

            String msg;
            switch (next) {
                case DEST_UP -> {
                    float v = LiveViewState.nudgeDestUp(dockPos, 0.5f);
                    msg = String.format("Dest Up  →  %.1f", v);
                }
                case DEST_DOWN -> {
                    float v = LiveViewState.nudgeDestUp(dockPos, -0.5f);
                    msg = String.format("Dest Down  →  %.1f", v);
                }
                case DEST_RIGHT -> {
                    float v = LiveViewState.nudgeDestRight(dockPos, 0.5f);
                    msg = String.format("Dest Right  →  %.1f", v);
                }
                case DEST_LEFT -> {
                    float v = LiveViewState.nudgeDestRight(dockPos, -0.5f);
                    msg = String.format("Dest Left  →  %.1f", v);
                }
                case DEST_RESET -> {
                    LiveViewState.resetDestOffsets(dockPos);
                    msg = "Dest offsets reset to 0";
                }
                case FACE_CYCLE -> {
                    float v = LiveViewState.cycleOffset(dockPos);
                    msg = String.format("Face offset  →  %.1f", v);
                }
                default -> msg = "?";
            }

            // Push updated offsets into PortalViewData immediately so next render is correct
            pushOffsetsToPortalData(level, clickedPos, dockPos);

            player.displayClientMessage(
                    Component.literal("[LiveView] " + next.label + " | " + msg)
                             .withStyle(next.color),
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

            // Debug info
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
                    int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
                    int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
                    for (BlockPos bp : frame2) {
                        minX=Math.min(minX,bp.getX()); maxX=Math.max(maxX,bp.getX());
                        minY=Math.min(minY,bp.getY()); maxY=Math.max(maxY,bp.getY());
                        minZ=Math.min(minZ,bp.getZ()); maxZ=Math.max(maxZ,bp.getZ());
                    }

                    PortalViewData pvd = PortalLiveViewManager.getActivePortals().get(clickedPos);
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

    /** Push current LiveViewState dest offsets into any matching PortalViewData immediately. */
    private static void pushOffsetsToPortalData(Level level, BlockPos clickedPos, BlockPos dockPos) {
        float dR = LiveViewState.getDestOffsetRight(dockPos);
        float dU = LiveViewState.getDestOffsetUp(dockPos);
        for (BlockPos fp : findConnectedPortalBlocks(level, clickedPos, 64)) {
            PortalViewData pvd = PortalLiveViewManager.getActivePortals().get(fp);
            if (pvd != null) {
                pvd.destOffsetRight = dR;
                pvd.destOffsetUp    = dU;
                pvd.markForUpdate();
            }
        }
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
