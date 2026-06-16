package com.blackwell.cosmosportalsliveview.client.renderer;

import com.blackwell.cosmosportalsliveview.config.PortalLiveViewConfig;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class LocalizedChunkCapture {
    
    public static void captureLocalizedPortalView(PortalViewData portalData, Level level) {
        if (portalData == null || level == null) return;
        
        BlockPos destPos = portalData.destPos;
        ResourceLocation destDimension = portalData.destDimension;
        
        if (!canAccessDimension(destDimension, level)) return;
        if (destPos == null || destPos == BlockPos.ZERO) return;
        
        int captureRadius = PortalLiveViewConfig.CAPTURE_RADIUS_CHUNKS.get();
        int resolution = PortalLiveViewConfig.CAPTURE_RESOLUTION.get();
        
        try {
            DynamicTexture texture = createPortalViewTexture(level, destPos, captureRadius, resolution);
            if (texture != null) {
                portalData.setTexture(texture);
            }
        } catch (Exception e) {
        }
    }
    
    private static DynamicTexture createPortalViewTexture(Level level, BlockPos center, int radiusChunks, int resolution) {
        int halfRadius = radiusChunks * 16;
        int minX = center.getX() - halfRadius;
        int minZ = center.getZ() - halfRadius;
        int maxX = center.getX() + halfRadius;
        int maxZ = center.getZ() + halfRadius;
        
        int width = maxX - minX;
        int depth = maxZ - minZ;
        
        for (int texY = 0; texY < resolution; texY++) {
            for (int texX = 0; texX < resolution; texX++) {
                int worldX = minX + (int) ((texX / (float) resolution) * width);
                int worldZ = minZ + (int) ((texY / (float) resolution) * depth);
                int worldY = center.getY();
                
                BlockPos samplePos = new BlockPos(worldX, worldY, worldZ);
                BlockState blockState = level.getBlockState(samplePos);
                getBlockColor(blockState);
            }
        }
        
        return createSimpleTexture(resolution);
    }
    
    private static DynamicTexture createSimpleTexture(int size) {
        try {
            return new DynamicTexture(size, size, false);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static int getBlockColor(BlockState blockState) {
        // Simple block identification
        if (blockState.isAir()) {
            return 0xFF000000;
        }
        
        int r = 100, g = 100, b = 100, a = 255;
        
        // Color blocks by type
        try {
            if (blockState.getBlock() == Blocks.GRASS_BLOCK || blockState.getBlock() == Blocks.DIRT) {
                r = 139; g = 101; b = 68;
            } else if (blockState.getBlock() == Blocks.STONE || blockState.getBlock() == Blocks.COBBLESTONE) {
                r = 128; g = 128; b = 128;
            } else if (blockState.getBlock() == Blocks.OAK_LOG || blockState.getBlock() == Blocks.OAK_LEAVES) {
                r = 139; g = 69; b = 19;
            } else if (blockState.getBlock() == Blocks.WATER) {
                r = 0; g = 100; b = 200;
            } else if (blockState.getBlock() == Blocks.SAND) {
                r = 238; g = 203; b = 139;
            } else if (blockState.getBlock() == Blocks.SNOW) {
                r = 255; g = 255; b = 255;
            }
        } catch (Exception e) {
            // Fallback to default gray
        }
        
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    
    private static boolean canAccessDimension(ResourceLocation dimension, Level currentLevel) {
        if (currentLevel == null) return false;
        try {
            return currentLevel.dimension().location().equals(dimension);
        } catch (Exception e) {
            return false;
        }
    }
}
