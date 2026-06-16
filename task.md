# CosmosPortalsLiveView Fix Tasks

## Root Cause Analysis (confirmed)

### Yaw/Pitch Swap Bug
ItemPortalContainer.onContainerLink() stores:
  - `pos_yaw`   = Vec2.x = Entity.getRotationVector().x = **xRot = PITCH**
  - `pos_pitch` = Vec2.y = Entity.getRotationVector().y = **yRot = YAW**

These are stored with swapped names. Then ABEPD passes them as:
  setDestInfo(pos, array[0]=PITCH_value, array[1]=YAW_value)
  → ODI constructor: (pos, yawIn=PITCH_value, pitchIn=YAW_value)
  → destInfo.yaw = actual_pitch, destInfo.pitch = actual_yaw

**Fix: In PortalViewData, swap getYaw()/getPitch() reads:**
  - destYaw   = entity.destInfo.getPitch()  // this has the actual YAW stored in pitch field
  - destPitch = entity.destInfo.getYaw()    // this has the actual PITCH stored in yaw field

This ALSO fixes the axis=Z 90° rotation — the image was rendered with yaw=pitch causing
a 90° rotated view direction, which appears as a 90° rotated image on the quad.

## Status
- [ ] Fix PortalViewData yaw/pitch swap
- [ ] Add wand debug output showing raw getYaw()/getPitch() vs swapped values
- [ ] Push to GitHub
