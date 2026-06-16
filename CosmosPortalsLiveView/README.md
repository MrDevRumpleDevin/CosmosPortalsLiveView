# CosmosPortals Live View

A Minecraft Forge addon mod for version 1.20.1 that adds live view portal rendering to [CosmosPortals](https://github.com/TheCosmosSeries/CosmosPortals).

## Features

- **Live Portal Views**: See the destination location rendered on the portal surface
- **Localized Rendering**: Only renders chunks around the portal destination (configurable radius)
- **Performance Optimized**: Spreads portal updates across multiple frames
- **Non-Invasive**: Works alongside other mods like Valkyrien Skies and Immersive Portals
- **Read-Only**: No interaction with the destination world
- **Automatic Cleanup**: Properly cleans up resources when portals are destroyed

## Configuration

Config file: `cosmosportals-liveview-client.toml`

```toml
[general]
# Enable live view rendering on portals
enableLiveView = true

# Radius of chunks around portal to capture (1-5 chunks, default 2 = 32 block radius)
captureRadiusChunks = 2

# Resolution of portal texture (128-1024 pixels)
captureResolution = 256

# Milliseconds between texture updates (100-5000ms)
captureIntervalMs = 1000

# Maximum portals to update per frame (1-3 recommended)
portalsPerFrame = 1
```

## Installation

1. Download the mod jar
2. Place in your `mods` folder
3. Requires: Forge 47.3.0+, CosmosPortals 1.20.1+, CosmosLibrary 1.20.1+

## How It Works

1. When a CosmosPortals portal is detected, it registers for live view rendering
2. Every tick, the mod captures a texture of the destination portal area
3. The captured texture is rendered on the portal block surface
4. When portals are destroyed, resources are automatically cleaned up
5. Chunk loading/unloading is tracked to minimize unnecessary re-captures

## Technical Details

### Rendering Pipeline
- Uses `RenderLevelStageEvent.Stage.AFTER_ENTITIES` to avoid conflicts with other mods
- Renders in a separate pass after all block entities
- Compatible with physics mods like Valkyrien Skies

### Resource Management
- Portals are updated incrementally (configurable portals per frame)
- Update intervals prevent excessive texture regeneration
- Chunk change detection optimizes capture frequency
- Proper cleanup on world unload and chunk unload

### Localized Capture
- Only renders blocks within configurable chunk radius (default: 2 chunks = 32 blocks)
- Reduces performance impact by focusing on portal vicinity
- Configurable resolution and update frequency

## Compatibility

- ✅ Valkyrien Skies
- ✅ Immersive Portals
- ✅ Other rendering mods
- ✅ Multiplayer (client-side only)

## Development

Built with:
- Forge 47.3.0
- Minecraft 1.20.1
- CosmosPortals 1.20.1

## License

MIT License

## Credits

Developed as an addon to the excellent CosmosPortals mod by The Cosmos Series.
