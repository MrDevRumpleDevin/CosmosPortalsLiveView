package com.blackwell.cosmosportalsliveview.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class PortalLiveViewConfig {
    
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    public static final ForgeConfigSpec.BooleanValue ENABLE_LIVE_VIEW;
    public static final ForgeConfigSpec.IntValue CAPTURE_RADIUS_CHUNKS;
    public static final ForgeConfigSpec.IntValue CAPTURE_RESOLUTION;
    public static final ForgeConfigSpec.IntValue CAPTURE_INTERVAL_MS;
    public static final ForgeConfigSpec.IntValue PORTALS_PER_FRAME;
    
    static {
        BUILDER.comment("CosmosPortals Live View Configuration").push("general");
        
        ENABLE_LIVE_VIEW = BUILDER
            .comment("Enable live view rendering on portals")
            .define("enableLiveView", true);
        
        CAPTURE_RADIUS_CHUNKS = BUILDER
            .comment("Radius of chunks around portal to capture (1-5 chunks)")
            .defineInRange("captureRadiusChunks", 2, 1, 5);
        
        CAPTURE_RESOLUTION = BUILDER
            .comment("Resolution of portal texture (128-1024 pixels)")
            .defineInRange("captureResolution", 256, 128, 1024);
        
        CAPTURE_INTERVAL_MS = BUILDER
            .comment("Milliseconds between texture updates (100-5000ms)")
            .defineInRange("captureIntervalMs", 1000, 100, 5000);
        
        PORTALS_PER_FRAME = BUILDER
            .comment("Maximum portals to update per frame (1-3 recommended)")
            .defineInRange("portalsPerFrame", 1, 1, 3);
        
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
