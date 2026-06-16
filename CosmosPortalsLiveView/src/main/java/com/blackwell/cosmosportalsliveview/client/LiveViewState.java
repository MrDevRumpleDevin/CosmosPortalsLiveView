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
 * plus a per-dock quad offset (adjusted with sneak+right-click wand).
 * Persisted to a file in the world's data folder so state survives reloads.
 */
@OnlyIn(Dist.CLIENT)
public class LiveViewState {

    private static final Set<Long> enabledDocks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Per-dock offset in blocks along the face normal. 0.0 = centred in portal. */
    private static final Map<Long, Float> dockOffsets = new ConcurrentHashMap<>();

    /** Steps cycled through by sneak+right-click. */
    private static final float[] OFFSET_STEPS = { -0.4f, -0.2f, 0.0f, 0.2f, 0.4f };

    // ── Offset API ─────────────────────────────────────────────────────────────

    public static float getOffset(BlockPos dockPos) {
        return dockOffsets.getOrDefault(dockPos.asLong(), 0.0f);
    }

    /**
     * Cycles the offset for this dock to the next step in OFFSET_STEPS.
     * Returns the new offset value.
     */
    public static float cycleOffset(BlockPos dockPos) {
        long key = dockPos.asLong();
        float current = dockOffsets.getOrDefault(key, 0.0f);
        // Find current index (or default to middle)
        int idx = OFFSET_STEPS.length / 2; // default = 0.0
        for (int i = 0; i < OFFSET_STEPS.length; i++) {
            if (Math.abs(OFFSET_STEPS[i] - current) < 0.01f) { idx = i; break; }
        }
        idx = (idx + 1) % OFFSET_STEPS.length;
        float next = OFFSET_STEPS[idx];
        dockOffsets.put(key, next);
        save();
        return next;
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

    public static void enable(BlockPos dockPos) {
        enabledDocks.add(dockPos.asLong());
        save();
    }

    public static void disable(BlockPos dockPos) {
        enabledDocks.remove(dockPos.asLong());
        save();
    }

    public static void clear() {
        enabledDocks.clear();
        dockOffsets.clear();
        // Don't delete file on clear — called on world unload, re-load will restore state
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Load enabled dock positions from the world's save file.
     * Call this when the client joins a world (after the level is set).
     */
    public static void load() {
        enabledDocks.clear();
        dockOffsets.clear();
        Path file = getSaveFile();
        if (file == null || !Files.exists(file)) return;

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    // Format: plain long = enabled dock
                    //         long:float  = offset entry
                    if (line.contains(":")) {
                        String[] parts = line.split(":", 2);
                        long key = Long.parseLong(parts[0]);
                        float val = Float.parseFloat(parts[1]);
                        dockOffsets.put(key, val);
                    } else {
                        enabledDocks.add(Long.parseLong(line));
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /**
     * Save enabled docks and offsets to disk. Called on every change.
     */
    public static void save() {
        Path file = getSaveFile();
        if (file == null) return;

        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                for (long key : enabledDocks) {
                    writer.write(Long.toString(key));
                    writer.newLine();
                }
                for (Map.Entry<Long, Float> entry : dockOffsets.entrySet()) {
                    writer.write(entry.getKey() + ":" + entry.getValue());
                    writer.newLine();
                }
            }
        } catch (IOException ignored) {}
    }

    /**
     * Returns the save-file path for the current world, or null if not in a world yet.
     * In SSP: .minecraft/saves/<world>/data/liveview_docks.txt
     * In SMP: falls back to .minecraft/liveview_server_<ip>.txt (best effort)
     */
    private static Path getSaveFile() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;

        // Singleplayer — use world folder
        if (mc.getSingleplayerServer() != null) {
            Path worldPath = mc.getSingleplayerServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT);
            return worldPath.resolve("data").resolve("liveview_docks.txt");
        }

        // Multiplayer — store per server address in the game dir
        if (mc.getCurrentServer() != null) {
            String addr = mc.getCurrentServer().ip
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            return mc.gameDirectory.toPath()
                    .resolve("liveview_" + addr + ".txt");
        }

        return null;
    }
}
