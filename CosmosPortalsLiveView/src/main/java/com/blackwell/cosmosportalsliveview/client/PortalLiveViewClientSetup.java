package com.blackwell.cosmosportalsliveview.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client setup class. Event handlers are registered automatically via
 * {@code @Mod.EventBusSubscriber} on each handler class — no manual
 * {@code MinecraftForge.EVENT_BUS.register()} calls are needed or wanted
 * (doing both causes double-registration crashes).
 */
@OnlyIn(Dist.CLIENT)
public class PortalLiveViewClientSetup {

    public static void setupClient() {
        // Intentionally empty — see class javadoc.
    }
}
