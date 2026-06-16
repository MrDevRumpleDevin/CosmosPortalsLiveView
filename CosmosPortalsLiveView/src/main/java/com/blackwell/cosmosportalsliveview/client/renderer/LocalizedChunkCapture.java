package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.mojang.blaze3d.platform.NativeImage;

@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {

    public static void captureLocalizedPortalView(PortalViewData portalData, Level level) {
        if (portalData == null) return;

        BlockPos destPos = portalData.destPos;
        ResourceLocation destDimension = portalData.destDimension;

        // destPos being null means the portal isn't linked yet; skip silently.
        if (destPos == null) return;

        // Resolve which level to sample from.
        Level sampleLevel = resolveSampleLevel(destDimension, level);
        if (sampleLevel == null) return;

        int captureRadius = PortalLiveViewConfig.CAPTURE_RADIUS_CHUNKS.get();
        int resolution   = PortalLiveViewConfig.CAPTURE_RESOLUTION.get();
        // Clamp to avoid OOM on huge resolutions.
        resolution = Math.min(resolution, 256);

        try {
            DynamicTexture texture = createPortalViewTexture(sampleLevel, destPos, captureRadius, resolution);
            if (texture != null) {
                portalData.setTexture(texture);
            }
        } catch (Exception ignored) {
            // Never crash the render thread.
        }
    }

    /**
     * Samples block colours around {@code center} and writes them into a fresh
     * {@link DynamicTexture}, then uploads to GPU.
     */
    private static DynamicTexture createPortalViewTexture(Level level, BlockPos center,
                                                           int radiusChunks, int resolution) {
        int halfRadius = radiusChunks * 16;
        int minX = center.getX() - halfRadius;
        int minZ = center.getZ() - halfRadius;
        int span = halfRadius * 2; // width == depth

        // NativeImage uses ARGB (Format.RGBA in the enum, but MC internally treats it as ABGR on
        // some platforms — we use NativeImage.Format.RGBA and let MC handle it).
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);

        for (int texY = 0; texY < resolution; texY++) {
            for (int texX = 0; texX < resolution; texX++) {
                int worldX = minX + (int) ((texX / (float) resolution) * span);
                int worldZ = minZ + (int) ((texY / (float) resolution) * span);
                int worldY = center.getY();

                int color;
                try {
                    BlockPos samplePos = new BlockPos(worldX, worldY, worldZ);
                    BlockState blockState = level.getBlockState(samplePos);
                    color = getBlockColor(blockState);
                } catch (Exception e) {
                    color = 0xFF404040; // dark fallback — chunk not loaded
                }
                // NativeImage.setPixelRGBA expects ABGR on little-endian (MC's NativeImage stores
                // components in RGBA memory order which appears as ABGR when read as int).
                // We therefore swap R and B channels here.
                image.setPixelRGBA(texX, texY, toABGR(color));
            }
        }

        DynamicTexture texture = new DynamicTexture(image);
        // Upload the pixel data to the GPU immediately (must be called on the render thread,
        // which tick events are).
        texture.upload();
        return texture;
    }

    /**
     * Convert an 0xAARRGGBB int to the 0xAABBGGRR layout that
     * {@link NativeImage#setPixelRGBA} expects.
     */
    private static int toABGR(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /** Simple block-type → colour lookup.  Returns 0xAARRGGBB. */
    private static int getBlockColor(BlockState state) {
        if (state.isAir()) return 0xFF000000; // transparent / sky

        int r = 100, g = 100, b = 100;

        try {
            if (state.is(Blocks.GRASS_BLOCK)) { r = 95;  g = 159; b = 53;  }
            else if (state.is(Blocks.DIRT))   { r = 139; g = 101; b = 68;  }
            else if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)) { r = 128; g = 128; b = 128; }
            else if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG)
                  || state.is(Blocks.SPRUCE_LOG)) { r = 101; g = 77; b = 47; }
            else if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES)
                  || state.is(Blocks.SPRUCE_LEAVES)) { r = 59;  g = 101; b = 36; }
            else if (state.is(Blocks.WATER))   { r = 0;   g = 100; b = 200; }
            else if (state.is(Blocks.SAND))    { r = 238; g = 203; b = 139; }
            else if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)) { r = 245; g = 245; b = 245; }
            else if (state.is(Blocks.NETHERRACK)) { r = 114; g = 22; b = 22; }
            else if (state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)) { r = 75;  g = 61; b = 50; }
            else if (state.is(Blocks.END_STONE)) { r = 220; g = 213; b = 150; }
            else if (state.is(Blocks.BEDROCK))   { r = 50;  g = 50;  b = 50; }
            else if (state.is(Blocks.GRAVEL))    { r = 147; g = 139; b = 131; }
            else if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)) { r = 70; g = 68; b = 80; }
        } catch (Exception ignored) {}

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Resolve which {@link Level} instance to sample blocks from.
     *
     * <p>Same-dimension: use the client level directly (chunks already loaded).
     * Cross-dimension (singleplayer): access via the integrated server.
     * Cross-dimension (multiplayer): return null — we can't read server levels on the client.
     */
    private static Level resolveSampleLevel(ResourceLocation destDimension, Level clientLevel) {
        if (clientLevel == null) return null;

        if (clientLevel.dimension().location().equals(destDimension)) {
            return clientLevel; // same dimension — easy
        }

        // Try the integrated server (singleplayer / LAN host).
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            net.minecraft.resources.ResourceKey<Level> destKey =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION,
                            destDimension);
            net.minecraft.server.level.ServerLevel serverLevel =
                    mc.getSingleplayerServer().getLevel(destKey);
            if (serverLevel != null) return serverLevel;
        }

        // Multiplayer cross-dim: show a placeholder static colour instead of crashing.
        return null;
    }
}
