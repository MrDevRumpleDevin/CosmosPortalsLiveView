package com.blackwell.cosmosportalsliveview.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely client-side store of which dock positions have "Live View" mode enabled,
 * plus per-dock settings (quad face offset, destination hole offsets).
 * Persisted to a file in the world's data folder so state survives reloads.
 */
@OnlyIn(Dist.CLIENT)
public class LiveViewState {

    private static final Set<Long> enabledDocks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Per-dock offset in blocks along the face normal (quad z-depth adjustment). */
    private static final Map<Long, Float> dockOffsets       = new ConcurrentHashMap<>();
    /** Per-dock destination hole right offset (shifts eye laterally at destination). */
    private static final Map<Long, Float> destOffsetRights  = new ConcurrentHashMap<>();
    /** Per-dock destination hole up offset (shifts eye vertically at destination). */
    private static final Map<Long, Float> destOffsetUps     = new ConcurrentHashMap<>();
    /** Per-dock destination hole forward offset (shifts eye along fwd axis at destination). */
    private static final Map<Long, Float> destOffsetForwards = new ConcurrentHashMap<>();

    /** Steps cycled through by sneak+right-click for the face offset. */
    private static final float[] OFFSET_STEPS = { -0.4f, -0.2f, 0.0f, 0.2f, 0.4f };

    /** Increment per wand nudge for destination hole offsets (blocks). */
    private static final float DEST_NUDGE = 0.5f;

    // ── Face offset API (sneak+click to cycle) ─────────────────────────────────

    public static float getOffset(BlockPos dockPos) {
        return dockOffsets.getOrDefault(dockPos.asLong(), 0.0f);
    }

    public static float cycleOffset(BlockPos dockPos) {
        long key = dockPos.asLong();
        float current = dockOffsets.getOrDefault(key, 0.0f);
        int idx = OFFSET_STEPS.length / 2;
        for (int i = 0; i < OFFSET_STEPS.length; i++) {
            if (Math.abs(OFFSET_STEPS[i] - current) < 0.01f) { idx = i; break; }
        }
        idx = (idx + 1) % OFFSET_STEPS.length;
        float next = OFFSET_STEPS[idx];
        dockOffsets.put(key, next);
        save();
        return next;
    }

    // ── Destination hole offset API ────────────────────────────────────────────

    public static float getDestOffsetRight(BlockPos dockPos) {
        return destOffsetRights.getOrDefault(dockPos.asLong(), 0.0f);
    }

    public static float getDestOffsetUp(BlockPos dockPos) {
        return destOffsetUps.getOrDefault(dockPos.asLong(), 0.0f);
    }

    public static float getDestOffsetForward(BlockPos dockPos) {
        return destOffsetForwards.getOrDefault(dockPos.asLong(), 0.0f);
    }

    /** Nudge the destination hole right by DEST_NUDGE blocks. Returns new value. */
    public static float nudgeDestRight(BlockPos dockPos, float delta) {
        long key = dockPos.asLong();
        float next = destOffsetRights.getOrDefault(key, 0.0f) + delta;
        next = Math.round(next / DEST_NUDGE) * DEST_NUDGE; // snap to grid
        destOffsetRights.put(key, next);
        save();
        return next;
    }

    /** Nudge the destination hole up by delta blocks. Returns new value. */
    public static float nudgeDestUp(BlockPos dockPos, float delta) {
        long key = dockPos.asLong();
        float next = destOffsetUps.getOrDefault(key, 0.0f) + delta;
        next = Math.round(next / DEST_NUDGE) * DEST_NUDGE;
        destOffsetUps.put(key, next);
        save();
        return next;
    }

    /** Nudge the destination hole forward by delta blocks. Returns new value. */
    public static float nudgeDestForward(BlockPos dockPos, float delta) {
        long key = dockPos.asLong();
        float next = destOffsetForwards.getOrDefault(key, 0.0f) + delta;
        next = Math.round(next / DEST_NUDGE) * DEST_NUDGE;
        destOffsetForwards.put(key, next);
        save();
        return next;
    }

    /** Reset all dest offsets to zero. */
    public static void resetDestOffsets(BlockPos dockPos) {
        long key = dockPos.asLong();
        destOffsetRights.remove(key);
        destOffsetUps.remove(key);
        destOffsetForwards.remove(key);
        save();
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    public static boolean isEnabled(BlockPos dockPos) {
        return enabledDocks.contains(dockPos.asLong());
    }

    // ── Mutation ───────────────────────────────────────────────────────────────

    public static boolean toggle(BlockPos dockPos) {
        long key = dockPos.asLong();
        boolean nowOn;
        if (!enabledDocks.remove(key)) {
            enabledDocks.add(key);
            nowOn = true;
        } else {
            nowOn = false;
        }
        save();
        return nowOn;
    }

    public static void enable(BlockPos dockPos)  { enabledDocks.add(dockPos.asLong());    save(); }
    public static void disable(BlockPos dockPos) { enabledDocks.remove(dockPos.asLong()); save(); }

    public static void clear() {
        enabledDocks.clear();
        dockOffsets.clear();
        destOffsetRights.clear();
        destOffsetUps.clear();
        destOffsetForwards.clear();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public static void load() {
        enabledDocks.clear();
        dockOffsets.clear();
        destOffsetRights.clear();
        destOffsetUps.clear();
        destOffsetForwards.clear();
        Path file = getSaveFile();
        if (file == null || !Files.exists(file)) return;

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    if (line.startsWith("dr:")) {
                        String[] parts = line.substring(3).split(":", 2);
                        destOffsetRights.put(Long.parseLong(parts[0]), Float.parseFloat(parts[1]));
                    } else if (line.startsWith("du:")) {
                        String[] parts = line.substring(3).split(":", 2);
                        destOffsetUps.put(Long.parseLong(parts[0]), Float.parseFloat(parts[1]));
                    } else if (line.startsWith("df:")) {
                        String[] parts = line.substring(3).split(":", 2);
                        destOffsetForwards.put(Long.parseLong(parts[0]), Float.parseFloat(parts[1]));
                    } else if (line.contains(":")) {
                        String[] parts = line.split(":", 2);
                        dockOffsets.put(Long.parseLong(parts[0]), Float.parseFloat(parts[1]));
                    } else {
                        enabledDocks.add(Long.parseLong(line));
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    public static void save() {
        Path file = getSaveFile();
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                for (long key : enabledDocks) {
                    writer.write(Long.toString(key)); writer.newLine();
                }
                for (Map.Entry<Long, Float> e : dockOffsets.entrySet()) {
                    writer.write(e.getKey() + ":" + e.getValue()); writer.newLine();
                }
                for (Map.Entry<Long, Float> e : destOffsetRights.entrySet()) {
                    writer.write("dr:" + e.getKey() + ":" + e.getValue()); writer.newLine();
                }
                for (Map.Entry<Long, Float> e : destOffsetUps.entrySet()) {
                    writer.write("du:" + e.getKey() + ":" + e.getValue()); writer.newLine();
                }
                for (Map.Entry<Long, Float> e : destOffsetForwards.entrySet()) {
                    writer.write("df:" + e.getKey() + ":" + e.getValue()); writer.newLine();
                }
            }
        } catch (IOException ignored) {}
    }

    private static Path getSaveFile() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        if (mc.getSingleplayerServer() != null) {
            Path worldPath = mc.getSingleplayerServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT);
            return worldPath.resolve("data").resolve("liveview_docks.txt");
        }
        if (mc.getCurrentServer() != null) {
            String addr = mc.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9._-]", "_");
            return mc.gameDirectory.toPath().resolve("liveview_" + addr + ".txt");
        }
        return null;
    }
}
