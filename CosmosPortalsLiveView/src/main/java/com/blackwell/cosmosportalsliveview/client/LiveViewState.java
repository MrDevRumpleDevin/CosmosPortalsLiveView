package com.blackwell.cosmosportalsliveview.client;

import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely client-side store of which dock positions have "Live View" mode enabled.
 * No server sync needed — rendering is entirely client-side.
 */
@OnlyIn(Dist.CLIENT)
public class LiveViewState {

    private static final Set<Long> enabledDocks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static boolean isEnabled(BlockPos dockPos) {
        return enabledDocks.contains(dockPos.asLong());
    }

    public static void toggle(BlockPos dockPos) {
        long key = dockPos.asLong();
        if (!enabledDocks.remove(key)) {
            enabledDocks.add(key);
        }
    }

    public static void enable(BlockPos dockPos) {
        enabledDocks.add(dockPos.asLong());
    }

    public static void disable(BlockPos dockPos) {
        enabledDocks.remove(dockPos.asLong());
    }

    public static void clear() {
        enabledDocks.clear();
    }
}
