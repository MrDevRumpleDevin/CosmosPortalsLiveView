package com.blackwell.cosmosportalsliveview.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely client-side store of which dock positions have "Live View" mode enabled.
 * Persisted to a file in the world's data folder so state survives reloads.
 */
@OnlyIn(Dist.CLIENT)
public class LiveViewState {

    private static final Set<Long> enabledDocks = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
        // Don't delete file on clear — called on world unload, re-load will restore state
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Load enabled dock positions from the world's save file.
     * Call this when the client joins a world (after the level is set).
     */
    public static void load() {
        enabledDocks.clear();
        Path file = getSaveFile();
        if (file == null || !Files.exists(file)) return;

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    enabledDocks.add(Long.parseLong(line));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /**
     * Save enabled dock positions to disk. Called on every toggle so state is never lost.
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
