# Native API census

Compiler-attributed references from the 191 bot seed sources, using the original JAR. Imports/type uses are separate from executable member use. See native-api.csv for every reference and shared-closure rows. Compilation still has the recorded errors; this is not a runtime call graph.

## `zombie.AttackType`

Vanilla source: [42.22.2/decompiled/zombie/AttackType.java](../../../42.22.2/decompiled/zombie/AttackType.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `MELEE_SWING` | `[public, static, final]` |
| FIELD_READ | `MELEE_TO_FLOOR` | `[public, static, final]` |
| FIELD_READ | `SHOT` | `[public, static, final]` |

## `zombie.CombatManager`

Vanilla source: [42.22.2/decompiled/zombie/CombatManager.java](../../../42.22.2/decompiled/zombie/CombatManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getInstance()` | `[public, static]` |
| CALL | `processMaintenanceCheck(zombie.characters.IsoGameCharacter,zombie.inventory.types.HandWeapon,zombie.iso.IsoObject)` | `[public]` |
| CALL | `processWeaponEndurance(zombie.characters.IsoGameCharacter,zombie.inventory.types.HandWeapon)` | `[public]` |
| CALL | `splash(zombie.iso.IsoMovingObject,zombie.inventory.types.HandWeapon,zombie.characters.IsoGameCharacter)` | `[public]` |

## `zombie.CombatManager.HitData`

Vanilla source: [42.22.2/decompiled/zombie/CombatManager.java](../../../42.22.2/decompiled/zombie/CombatManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| CONSTRUCT | `HitData(byte,boolean,boolean,boolean,boolean)` | `[public]` |

## `zombie.CombatManager.TargetHitList`

Vanilla source: [42.22.2/decompiled/zombie/CombatManager.java](../../../42.22.2/decompiled/zombie/CombatManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| CONSTRUCT | `TargetHitList(zombie.iso.IsoObject,java.util.List<zombie.network.fields.hit.Hit>)` | `[public]` |

## `zombie.GameTime`

Vanilla source: [42.22.2/decompiled/zombie/GameTime.java](../../../42.22.2/decompiled/zombie/GameTime.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getInstance()` | `[public, static]` |
| CALL | `getTrueMultiplier()` | `[public]` |

## `zombie.Lua.LuaEventManager`

Vanilla source: [42.22.2/decompiled/zombie/Lua/LuaEventManager.java](../../../42.22.2/decompiled/zombie/Lua/LuaEventManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `AddEvent(java.lang.String)` | `[public, static]` |
| CALL | `triggerEvent(java.lang.String)` | `[public, static]` |
| CALL | `triggerEvent(java.lang.String,java.lang.Object,java.lang.Object)` | `[public, static]` |
| CALL | `triggerEvent(java.lang.String,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)` | `[public, static]` |

## `zombie.Lua.LuaManager`

Vanilla source: [42.22.2/decompiled/zombie/Lua/LuaManager.java](../../../42.22.2/decompiled/zombie/Lua/LuaManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `env` | `[public, static]` |
| FIELD_READ | `platform` | `[public, static]` |

## `zombie.ZomboidFileSystem`

Vanilla source: [42.22.2/decompiled/zombie/ZomboidFileSystem.java](../../../42.22.2/decompiled/zombie/ZomboidFileSystem.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getString(java.lang.String)` | `[public, synchronized]` |
| CALL | `resolveAllFiles(java.lang.String,java.io.FileFilter,boolean)` | `[public]` |
| FIELD_READ | `instance` | `[public, static, final]` |

## `zombie.ai.State`

Vanilla source: [42.22.2/decompiled/zombie/ai/State.java](../../../42.22.2/decompiled/zombie/ai/State.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.ai.State.Stage`

Vanilla source: [42.22.2/decompiled/zombie/ai/State.java](../../../42.22.2/decompiled/zombie/ai/State.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `Enter` | `[public, static, final]` |
| FIELD_READ | `Exit` | `[public, static, final]` |

## `zombie.ai.astar.Mover`

Vanilla source: [42.22.2/decompiled/zombie/ai/astar/Mover.java](../../../42.22.2/decompiled/zombie/ai/astar/Mover.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.ai.states.ClimbOverFenceState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/ClimbOverFenceState.java](../../../42.22.2/decompiled/zombie/ai/states/ClimbOverFenceState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.ai.states.ClimbThroughWindowPositioningParams`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/ClimbThroughWindowPositioningParams.java](../../../42.22.2/decompiled/zombie/ai/states/ClimbThroughWindowPositioningParams.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_WRITE | `canClimb` | `[public]` |
| FIELD_WRITE | `climbDir` | `[public]` |
| FIELD_WRITE | `climbingCharacter` | `[public]` |
| FIELD_WRITE | `endX` | `[public]` |
| FIELD_WRITE | `endY` | `[public]` |
| FIELD_WRITE | `isCounter` | `[public]` |
| FIELD_WRITE | `isFloor` | `[public]` |
| FIELD_WRITE | `isSheetRope` | `[public]` |
| FIELD_WRITE | `oppositeX` | `[public]` |
| FIELD_WRITE | `oppositeY` | `[public]` |
| FIELD_WRITE | `scratch` | `[public]` |
| FIELD_WRITE | `startX` | `[public]` |
| FIELD_WRITE | `startY` | `[public]` |
| FIELD_WRITE | `windowObject` | `[public]` |
| FIELD_WRITE | `z` | `[public]` |

## `zombie.ai.states.ClimbThroughWindowState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/ClimbThroughWindowState.java](../../../42.22.2/decompiled/zombie/ai/states/ClimbThroughWindowState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |
| FIELD_READ | `PARAMS` | `[public, static, final]` |

## `zombie.ai.states.IdleState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/IdleState.java](../../../42.22.2/decompiled/zombie/ai/states/IdleState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.ai.states.PlayerActionsState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/PlayerActionsState.java](../../../42.22.2/decompiled/zombie/ai/states/PlayerActionsState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.ai.states.PlayerAimState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/PlayerAimState.java](../../../42.22.2/decompiled/zombie/ai/states/PlayerAimState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.ai.states.PlayerDraggingCorpse`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/PlayerDraggingCorpse.java](../../../42.22.2/decompiled/zombie/ai/states/PlayerDraggingCorpse.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.ai.states.PlayerEmoteState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/PlayerEmoteState.java](../../../42.22.2/decompiled/zombie/ai/states/PlayerEmoteState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.ai.states.PlayerGetUpState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/PlayerGetUpState.java](../../../42.22.2/decompiled/zombie/ai/states/PlayerGetUpState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.ai.states.PlayerSitOnFurnitureState`

Vanilla source: [42.22.2/decompiled/zombie/ai/states/PlayerSitOnFurnitureState.java](../../../42.22.2/decompiled/zombie/ai/states/PlayerSitOnFurnitureState.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `instance()` | `[public, static]` |

## `zombie.audio.parameters.ParameterBulletHitSurface`

Vanilla source: [42.22.2/decompiled/zombie/audio/parameters/ParameterBulletHitSurface.java](../../../42.22.2/decompiled/zombie/audio/parameters/ParameterBulletHitSurface.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.audio.parameters.ParameterBulletHitSurface.Material`

Vanilla source: [42.22.2/decompiled/zombie/audio/parameters/ParameterBulletHitSurface.java](../../../42.22.2/decompiled/zombie/audio/parameters/ParameterBulletHitSurface.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `Flesh` | `[public, static, final]` |

## `zombie.audio.parameters.ParameterMeleeHitSurface`

Vanilla source: [42.22.2/decompiled/zombie/audio/parameters/ParameterMeleeHitSurface.java](../../../42.22.2/decompiled/zombie/audio/parameters/ParameterMeleeHitSurface.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.audio.parameters.ParameterMeleeHitSurface.Material`

Vanilla source: [42.22.2/decompiled/zombie/audio/parameters/ParameterMeleeHitSurface.java](../../../42.22.2/decompiled/zombie/audio/parameters/ParameterMeleeHitSurface.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `Body` | `[public, static, final]` |

## `zombie.characters.BaseCharacterSoundEmitter`

Vanilla source: [42.22.2/decompiled/zombie/characters/BaseCharacterSoundEmitter.java](../../../42.22.2/decompiled/zombie/characters/BaseCharacterSoundEmitter.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `stopAll()` | `[public, abstract]` |

## `zombie.characters.BodyDamage.BodyDamage`

Vanilla source: [42.22.2/decompiled/zombie/characters/BodyDamage/BodyDamage.java](../../../42.22.2/decompiled/zombie/characters/BodyDamage/BodyDamage.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `AddRandomDamageFromZombie(zombie.characters.IsoZombie,java.lang.String,int)` | `[public]` |
| CALL | `calculateOverallHealth()` | `[public]` |
| CALL | `getBodyParts()` | `[public]` |
| CALL | `getOverallBodyHealth()` | `[public]` |
| CALL | `setOverallBodyHealth(float)` | `[public]` |

## `zombie.characters.BodyDamage.BodyPart`

Vanilla source: [42.22.2/decompiled/zombie/characters/BodyDamage/BodyPart.java](../../../42.22.2/decompiled/zombie/characters/BodyDamage/BodyPart.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `SetHealth(float)` | `[public]` |
| CALL | `bandaged()` | `[public]` |
| CALL | `getBiteTime()` | `[public]` |
| CALL | `getBleedingTime()` | `[public]` |
| CALL | `getBurnTime()` | `[public]` |
| CALL | `getCutTime()` | `[public]` |
| CALL | `getDamageScaler()` | `[public]` |
| CALL | `getDeepWoundTime()` | `[public]` |
| CALL | `getHealth()` | `[public]` |
| CALL | `getScratchTime()` | `[public]` |
| CALL | `getType()` | `[public]` |
| CALL | `setBandaged(boolean,float,boolean,java.lang.String)` | `[public]` |

## `zombie.characters.BodyDamage.BodyPartType`

Vanilla source: [42.22.2/decompiled/zombie/characters/BodyDamage/BodyPartType.java](../../../42.22.2/decompiled/zombie/characters/BodyDamage/BodyPartType.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `Foot_L` | `[public, static, final]` |
| FIELD_READ | `Foot_R` | `[public, static, final]` |
| FIELD_READ | `ForeArm_L` | `[public, static, final]` |
| FIELD_READ | `ForeArm_R` | `[public, static, final]` |
| FIELD_READ | `Groin` | `[public, static, final]` |
| FIELD_READ | `Hand_L` | `[public, static, final]` |
| FIELD_READ | `Hand_R` | `[public, static, final]` |
| FIELD_READ | `Head` | `[public, static, final]` |
| FIELD_READ | `LowerLeg_L` | `[public, static, final]` |
| FIELD_READ | `LowerLeg_R` | `[public, static, final]` |
| FIELD_READ | `Neck` | `[public, static, final]` |
| FIELD_READ | `Torso_Lower` | `[public, static, final]` |
| FIELD_READ | `UpperArm_L` | `[public, static, final]` |
| FIELD_READ | `UpperArm_R` | `[public, static, final]` |
| FIELD_READ | `UpperLeg_L` | `[public, static, final]` |
| FIELD_READ | `UpperLeg_R` | `[public, static, final]` |

## `zombie.characters.CharacterStat`

Vanilla source: [42.22.2/decompiled/zombie/characters/CharacterStat.java](../../../42.22.2/decompiled/zombie/characters/CharacterStat.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `ENDURANCE` | `[public, static, final]` |
| FIELD_READ | `FATIGUE` | `[public, static, final]` |
| FIELD_READ | `HUNGER` | `[public, static, final]` |
| FIELD_READ | `PAIN` | `[public, static, final]` |
| FIELD_READ | `PANIC` | `[public, static, final]` |
| FIELD_READ | `SICKNESS` | `[public, static, final]` |
| FIELD_READ | `THIRST` | `[public, static, final]` |
| FIELD_READ | `ZOMBIE_INFECTION` | `[public, static, final]` |

## `zombie.characters.Faction`

Vanilla source: [42.22.2/decompiled/zombie/characters/Faction.java](../../../42.22.2/decompiled/zombie/characters/Faction.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `isInSameFaction(zombie.characters.IsoPlayer,zombie.characters.IsoPlayer)` | `[public, static]` |

## `zombie.characters.IsoGameCharacter`

Vanilla source: [42.22.2/decompiled/zombie/characters/IsoGameCharacter.java](../../../42.22.2/decompiled/zombie/characters/IsoGameCharacter.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `<T>get(zombie.ai.State.Param<T>)` | `[public]` |
| CALL | `Hit(zombie.inventory.types.HandWeapon,zombie.characters.IsoGameCharacter,float,boolean,float,boolean)` | `[public]` |
| CALL | `addOnDiedListener(zombie.characters.CharacterDiedListener,boolean)` | `[public]` |
| CALL | `calculateGrappleEffectivenessFromTraits()` | `[public]` |
| CALL | `clearVariable(java.lang.String)` | `[public]` |
| CALL | `die()` | `[public, final]` |
| CALL | `faceThisObject(zombie.iso.IsoObject)` | `[public]` |
| CALL | `getAttackVars()` | `[public]` |
| CALL | `getAttackedBy()` | `[public]` |
| CALL | `getBodyDamage()` | `[public]` |
| CALL | `getCurrentState()` | `[public]` |
| CALL | `getDescriptor()` | `[public]` |
| CALL | `getDirectionAngleRadians()` | `[public]` |
| CALL | `getEmitter()` | `[public]` |
| CALL | `getHealth()` | `[public]` |
| CALL | `getHitInfoList()` | `[public]` |
| CALL | `getHittingMod()` | `[public]` |
| CALL | `getInventory()` | `[public]` |
| CALL | `getPrimaryHandItem()` | `[public]` |
| CALL | `getSafety()` | `[public]` |
| CALL | `getSecondaryHandItem()` | `[public]` |
| CALL | `getSitOnFurnitureObject()` | `[public]` |
| CALL | `getSpeedMod()` | `[public]` |
| CALL | `getStats()` | `[public]` |
| CALL | `getWrappedGrappleable()` | `[public]` |
| CALL | `helmetFall(boolean)` | `[public]` |
| CALL | `isAimAtFloor()` | `[public]` |
| CALL | `isDead()` | `[public]` |
| CALL | `isDeathDragDown()` | `[public]` |
| CALL | `isFallOnFront()` | `[public]` |
| CALL | `isGodMod()` | `[public]` |
| CALL | `isInvisible()` | `[public]` |
| CALL | `isKnockedDown()` | `[public]` |
| CALL | `isNpc()` | `[public]` |
| CALL | `isRagdoll()` | `[public]` |
| CALL | `isSittingOnFurniture()` | `[public]` |
| CALL | `processHitDamage(zombie.inventory.types.HandWeapon,zombie.characters.IsoGameCharacter,float,boolean,float)` | `[public]` |
| CALL | `setAimAtFloor(boolean)` | `[public]` |
| CALL | `setAttackTargetSquare(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `setAttackedBy(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `setCriticalHit(boolean)` | `[public]` |
| CALL | `setDirectionAngle(float)` | `[public]` |
| CALL | `setForwardIsoDirection(zombie.iso.IsoDirections)` | `[public]` |
| CALL | `setGodMod(boolean,boolean)` | `[public]` |
| CALL | `setHealth(float)` | `[public]` |
| CALL | `setHitReaction(java.lang.String)` | `[public]` |
| CALL | `setInvisible(boolean,boolean)` | `[public]` |
| CALL | `setIsAiming(boolean)` | `[public]` |
| CALL | `setOnKillDone(boolean)` | `[public]` |
| CALL | `setPrimaryHandItem(zombie.inventory.InventoryItem)` | `[public]` |
| CALL | `setRunning(boolean)` | `[public]` |
| CALL | `setSecondaryHandItem(zombie.inventory.InventoryItem)` | `[public]` |
| CALL | `setSitOnFurnitureDirection(zombie.iso.IsoDirections)` | `[public]` |
| CALL | `setSitOnFurnitureObject(zombie.iso.IsoObject)` | `[public]` |
| CALL | `setSittingOnFurniture(boolean)` | `[public]` |
| CALL | `setSneaking(boolean)` | `[public]` |
| CALL | `setSprinting(boolean)` | `[public]` |
| CALL | `setUseHandWeapon(zombie.inventory.types.HandWeapon)` | `[public]` |
| CALL | `setVariable(java.lang.String,boolean)` | `[public]` |
| CALL | `setVariable(java.lang.String,float)` | `[public]` |
| CALL | `setVariable(java.lang.String,java.lang.String)` | `[public]` |
| CALL | `splatBloodFloor()` | `[public]` |
| FIELD_WRITE | `realx` | `[public]` |
| FIELD_WRITE | `realy` | `[public]` |
| FIELD_WRITE | `realz` | `[public]` |

## `zombie.characters.IsoLivingCharacter`

Vanilla source: [42.22.2/decompiled/zombie/characters/IsoLivingCharacter.java](../../../42.22.2/decompiled/zombie/characters/IsoLivingCharacter.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getAttackingWeapon()` | `[public]` |
| CALL | `setDoShove(boolean)` | `[public]` |
| FIELD_READ | `bareHands` | `[public, final]` |

## `zombie.characters.IsoPlayer`

Vanilla source: [42.22.2/decompiled/zombie/characters/IsoPlayer.java](../../../42.22.2/decompiled/zombie/characters/IsoPlayer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `addWorldSoundUnlessInvisible(int,int,boolean)` | `[public]` |
| CALL | `calculateCritChance(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `getNetworkCharacterAI()` | `[public]` |
| CALL | `getOnlineID()` | `[public]` |
| CALL | `getSteamID()` | `[public]` |
| CALL | `getUsername()` | `[public]` |
| CALL | `isAiming()` | `[public]` |
| CALL | `isFactionPvp()` | `[public]` |
| CALL | `isGhostMode()` | `[public]` |
| CALL | `isPerformingAnAction()` | `[public]` |
| CALL | `setAttackType(zombie.AttackType)` | `[public]` |
| CALL | `setDisplayName(java.lang.String)` | `[public]` |
| CALL | `setOnlineID(short)` | `[public]` |
| CALL | `setPerformingAnAction(boolean)` | `[public]` |
| CALL | `setUsername(java.lang.String)` | `[public]` |
| CONSTRUCT | `IsoPlayer(zombie.iso.IsoCell,zombie.characters.SurvivorDesc,int,int,int)` | `[public]` |
| FIELD_WRITE | `playerIndex` | `[public]` |
| FIELD_WRITE | `remote` | `[public]` |
| FIELD_WRITE | `useChargeDelta` | `[public]` |

## `zombie.characters.IsoZombie`

Vanilla source: [42.22.2/decompiled/zombie/characters/IsoZombie.java](../../../42.22.2/decompiled/zombie/characters/IsoZombie.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `addFootstepParametersIfNeeded()` | `[public]` |
| CALL | `getNetworkCharacterAI()` | `[public]` |
| CALL | `getOnlineID()` | `[public]` |
| CALL | `getOwner()` | `[public]` |
| CALL | `getSpeedType()` | `[public]` |
| CALL | `getWalkType()` | `[public]` |
| CALL | `getZombieWalkTowardSpeed(float,float,zombie.iso.Vector2)` | `[public]` |
| CALL | `isCanWalk()` | `[public]` |
| CALL | `isCrawling()` | `[public]` |
| CALL | `isFakeDead()` | `[public]` |
| CALL | `isNoTeeth()` | `[public]` |
| CALL | `isReanimatedForGrappleOnly()` | `[public]` |
| CALL | `isStaggerBack()` | `[public]` |
| CALL | `isUseless()` | `[public]` |
| CALL | `setCrawler(boolean)` | `[public]` |
| CALL | `setFakeDead(boolean)` | `[public]` |
| CALL | `setReanimatedForGrappleOnly(boolean)` | `[public]` |
| CALL | `setTarget(zombie.iso.IsoMovingObject)` | `[public]` |
| FIELD_READ | `inactive` | `[public]` |
| FIELD_READ | `lastChangeOwner` | `[public]` |
| FIELD_READ | `onlineId` | `[public]` |
| FIELD_READ | `running` | `[public]` |
| FIELD_READ | `target` | `[public]` |
| FIELD_WRITE | `lastChangeOwner` | `[public]` |
| FIELD_WRITE | `running` | `[public]` |
| FIELD_WRITE | `timeSinceSeenFlesh` | `[public]` |

## `zombie.characters.NetworkCharacterAI`

Vanilla source: [42.22.2/decompiled/zombie/characters/NetworkCharacterAI.java](../../../42.22.2/decompiled/zombie/characters/NetworkCharacterAI.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `resetSpeedLimiter()` | `[public]` |

## `zombie.characters.NetworkPlayerAI`

Vanilla source: [42.22.2/decompiled/zombie/characters/NetworkPlayerAI.java](../../../42.22.2/decompiled/zombie/characters/NetworkPlayerAI.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_WRITE | `runSpeed` | `[public]` |
| FIELD_WRITE | `walkSpeed` | `[public]` |

## `zombie.characters.NetworkPlayerVariables`

Vanilla source: [42.22.2/decompiled/zombie/characters/NetworkPlayerVariables.java](../../../42.22.2/decompiled/zombie/characters/NetworkPlayerVariables.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.characters.NetworkPlayerVariables.Flags`

Vanilla source: [42.22.2/decompiled/zombie/characters/NetworkPlayerVariables.java](../../../42.22.2/decompiled/zombie/characters/NetworkPlayerVariables.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `hasDeferredMovement` | `[public, static, final]` |
| FIELD_READ | `isDoContinueGrapple` | `[public, static, final]` |
| FIELD_READ | `isPerformingAction` | `[public, static, final]` |
| FIELD_READ | `isRunning` | `[public, static, final]` |
| FIELD_READ | `isSneaking` | `[public, static, final]` |
| FIELD_READ | `isSprinting` | `[public, static, final]` |

## `zombie.characters.NetworkZombieVariables`

Vanilla source: [42.22.2/decompiled/zombie/characters/NetworkZombieVariables.java](../../../42.22.2/decompiled/zombie/characters/NetworkZombieVariables.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.characters.NetworkZombieVariables.Flag`

Vanilla source: [42.22.2/decompiled/zombie/characters/NetworkZombieVariables.java](../../../42.22.2/decompiled/zombie/characters/NetworkZombieVariables.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `IsRunning` | `[public, static, final]` |

## `zombie.characters.Safety`

Vanilla source: [42.22.2/decompiled/zombie/characters/Safety.java](../../../42.22.2/decompiled/zombie/characters/Safety.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `isEnabled()` | `[public]` |
| CALL | `setEnabled(boolean)` | `[public]` |

## `zombie.characters.Stats`

Vanilla source: [42.22.2/decompiled/zombie/characters/Stats.java](../../../42.22.2/decompiled/zombie/characters/Stats.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `get(zombie.characters.CharacterStat)` | `[public]` |
| CALL | `set(zombie.characters.CharacterStat,float)` | `[public]` |

## `zombie.characters.SurvivorDesc`

Vanilla source: [42.22.2/decompiled/zombie/characters/SurvivorDesc.java](../../../42.22.2/decompiled/zombie/characters/SurvivorDesc.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `dressInNamedOutfit(java.lang.String)` | `[public]` |
| CALL | `getHumanVisual()` | `[public]` |
| CALL | `setFemale(boolean)` | `[public]` |
| CALL | `setForename(java.lang.String)` | `[public]` |
| CALL | `setInstance(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `setSurname(java.lang.String)` | `[public]` |
| CONSTRUCT | `SurvivorDesc(boolean)` | `[public]` |

## `zombie.config.BooleanConfigOption`

Vanilla source: [42.22.2/decompiled/zombie/config/BooleanConfigOption.java](../../../42.22.2/decompiled/zombie/config/BooleanConfigOption.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getValue()` | `[public]` |

## `zombie.core.Core`

Vanilla source: [42.22.2/decompiled/zombie/core/Core.java](../../../42.22.2/decompiled/zombie/core/Core.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `gameSaveWorld` | `[public, static]` |

## `zombie.core.TradingState`

Vanilla source: [42.22.2/decompiled/zombie/core/TradingState.java](../../../42.22.2/decompiled/zombie/core/TradingState.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `DealWasFinalized` | `[public, static, final]` |
| FIELD_READ | `DealWasSealed` | `[public, static, final]` |
| FIELD_READ | `DealWasUnsealed` | `[public, static, final]` |
| FIELD_READ | `WindowWasClosed` | `[public, static, final]` |

## `zombie.core.network.ByteBufferReader`

Vanilla source: [42.22.2/decompiled/zombie/core/network/ByteBufferReader.java](../../../42.22.2/decompiled/zombie/core/network/ByteBufferReader.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.core.network.ByteBufferWriter`

Vanilla source: [42.22.2/decompiled/zombie/core/network/ByteBufferWriter.java](../../../42.22.2/decompiled/zombie/core/network/ByteBufferWriter.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `putByte(int)` | `[public]` |
| CALL | `putInt(int)` | `[public]` |
| CONSTRUCT | `ByteBufferWriter(java.nio.ByteBuffer)` | `[public]` |

## `zombie.core.properties.PropertyContainer`

Vanilla source: [42.22.2/decompiled/zombie/core/properties/PropertyContainer.java](../../../42.22.2/decompiled/zombie/core/properties/PropertyContainer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `has(zombie.iso.SpriteDetails.IsoFlagType)` | `[public]` |

## `zombie.core.raknet.UdpConnection`

Vanilla source: [42.22.2/decompiled/zombie/core/raknet/UdpConnection.java](../../../42.22.2/decompiled/zombie/core/raknet/UdpConnection.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getPlayerAt(int)` | `[public]` |
| CALL | `getPlayerIndex(zombie.characters.IsoPlayer)` | `[public]` |
| CALL | `hasPlayer(short)` | `[public]` |
| CALL | `isFullyConnected()` | `[public]` |
| CALL | `isRelevantTo(float,float)` | `[public]` |
| FIELD_READ | `players` | `[public]` |

## `zombie.core.raknet.UdpEngine`

Vanilla source: [42.22.2/decompiled/zombie/core/raknet/UdpEngine.java](../../../42.22.2/decompiled/zombie/core/raknet/UdpEngine.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `connections` | `[public, final]` |

## `zombie.core.random.Rand`

Vanilla source: [42.22.2/decompiled/zombie/core/random/Rand.java](../../../42.22.2/decompiled/zombie/core/random/Rand.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `Next(float,float)` | `[public, static]` |
| CALL | `Next(int)` | `[public, static]` |

## `zombie.core.skinnedmodel.BaseGrappleable`

Vanilla source: [42.22.2/decompiled/zombie/core/skinnedmodel/BaseGrappleable.java](../../../42.22.2/decompiled/zombie/core/skinnedmodel/BaseGrappleable.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getGrappledBy()` | `[public]` |

## `zombie.core.skinnedmodel.IGrappleable`

Vanilla source: [42.22.2/decompiled/zombie/core/skinnedmodel/IGrappleable.java](../../../42.22.2/decompiled/zombie/core/skinnedmodel/IGrappleable.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `LetGoOfGrappled(java.lang.String)` | `[public, abstract]` |

## `zombie.core.skinnedmodel.IGrappleableWrapper`

Vanilla source: [42.22.2/decompiled/zombie/core/skinnedmodel/IGrappleableWrapper.java](../../../42.22.2/decompiled/zombie/core/skinnedmodel/IGrappleableWrapper.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `Grappled(zombie.core.skinnedmodel.IGrappleable,zombie.inventory.types.HandWeapon,float,java.lang.String)` | `[public, default]` |
| CALL | `LetGoOfGrappled(java.lang.String)` | `[public, default]` |
| CALL | `getBearingFromGrappledTarget()` | `[public, default]` |
| CALL | `getGrappledBy()` | `[public, default]` |
| CALL | `getGrapplingTarget()` | `[public, default]` |
| CALL | `isBeingGrappled()` | `[public, default]` |
| CALL | `isDoContinueGrapple()` | `[public, default]` |
| CALL | `isGrappling()` | `[public, default]` |
| CALL | `resetGrappleStateToDefault(java.lang.String)` | `[public, default]` |
| CALL | `setDoContinueGrapple(boolean)` | `[public, default]` |
| CALL | `setDoGrapple(boolean)` | `[public, default]` |
| CALL | `setSharedGrappleType(java.lang.String)` | `[public, default]` |

## `zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSourceContainer`

Vanilla source: [42.22.2/decompiled/zombie/core/skinnedmodel/advancedanimation/IAnimationVariableSourceContainer.java](../../../42.22.2/decompiled/zombie/core/skinnedmodel/advancedanimation/IAnimationVariableSourceContainer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getVariableString(java.lang.String)` | `[public, default]` |

## `zombie.core.skinnedmodel.visual.HumanVisual`

Vanilla source: [42.22.2/decompiled/zombie/core/skinnedmodel/visual/HumanVisual.java](../../../42.22.2/decompiled/zombie/core/skinnedmodel/visual/HumanVisual.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `setBeardModel(java.lang.String)` | `[public]` |
| CALL | `setHairModel(java.lang.String)` | `[public]` |
| CALL | `setSkinTextureIndex(int)` | `[public]` |

## `zombie.core.znet.SteamUtils`

Vanilla source: [42.22.2/decompiled/zombie/core/znet/SteamUtils.java](../../../42.22.2/decompiled/zombie/core/znet/SteamUtils.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `isSteamModeEnabled()` | `[public, static]` |

## `zombie.debug.DebugLog`

Vanilla source: [42.22.2/decompiled/zombie/debug/DebugLog.java](../../../42.22.2/decompiled/zombie/debug/DebugLog.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `log(java.lang.String)` | `[public, static]` |

## `zombie.entity.GameEntity`

Vanilla source: [42.22.2/decompiled/zombie/entity/GameEntity.java](../../../42.22.2/decompiled/zombie/entity/GameEntity.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getXi()` | `[public, final]` |
| CALL | `getYi()` | `[public, final]` |
| CALL | `getZi()` | `[public, final]` |

## `zombie.inventory.InventoryItem`

Vanilla source: [42.22.2/decompiled/zombie/inventory/InventoryItem.java](../../../42.22.2/decompiled/zombie/inventory/InventoryItem.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getBandagePower()` | `[public]` |
| CALL | `getCondition()` | `[public]` |
| CALL | `getContainer()` | `[public]` |
| CALL | `getCount()` | `[public]` |
| CALL | `getCurrentAmmoCount()` | `[public]` |
| CALL | `getFullType()` | `[public]` |
| CALL | `getID()` | `[public]` |
| CALL | `getMaxAmmo()` | `[public]` |
| CALL | `isAlcoholic()` | `[public]` |
| CALL | `isInfected()` | `[public]` |
| CALL | `isTwoHandWeapon()` | `[public]` |
| CALL | `setCount(int)` | `[public]` |
| CALL | `setCurrentAmmoCount(int)` | `[public]` |

## `zombie.inventory.InventoryItemFactory`

Vanilla source: [42.22.2/decompiled/zombie/inventory/InventoryItemFactory.java](../../../42.22.2/decompiled/zombie/inventory/InventoryItemFactory.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `<T>CreateItem(java.lang.String)` | `[public, static]` |

## `zombie.inventory.ItemContainer`

Vanilla source: [42.22.2/decompiled/zombie/inventory/ItemContainer.java](../../../42.22.2/decompiled/zombie/inventory/ItemContainer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `AddItem(zombie.inventory.InventoryItem)` | `[public]` |
| CALL | `Remove(zombie.inventory.InventoryItem)` | `[public]` |
| CALL | `getItemWithID(int)` | `[public]` |
| CALL | `getItems()` | `[public]` |

## `zombie.inventory.types.HandWeapon`

Vanilla source: [42.22.2/decompiled/zombie/inventory/types/HandWeapon.java](../../../42.22.2/decompiled/zombie/inventory/types/HandWeapon.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getCriticalChance()` | `[public]` |
| CALL | `getDamageMod(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `getHitChance()` | `[public]` |
| CALL | `getMaxDamage()` | `[public]` |
| CALL | `getMaxHitCount()` | `[public]` |
| CALL | `getMaxRange(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `getMinDamage()` | `[public]` |
| CALL | `getProjectileCount()` | `[public]` |
| CALL | `getSoundRadius()` | `[public]` |
| CALL | `getSoundVolume()` | `[public]` |
| CALL | `getSwingSound()` | `[public]` |
| CALL | `getZombieHitSound()` | `[public]` |
| CALL | `isJammed()` | `[public]` |
| CALL | `isRanged()` | `[public]` |
| CALL | `isRoundChambered()` | `[public]` |
| CALL | `setContainsClip(boolean)` | `[public]` |
| CALL | `setJammed(boolean)` | `[public]` |
| CALL | `setRoundChambered(boolean)` | `[public]` |
| CALL | `setSpentRoundChambered(boolean)` | `[public]` |

## `zombie.iso.IsoCell`

Vanilla source: [42.22.2/decompiled/zombie/iso/IsoCell.java](../../../42.22.2/decompiled/zombie/iso/IsoCell.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getAddList()` | `[public]` |
| CALL | `getGridSquare(int,int,int)` | `[public]` |
| CALL | `getObjectList()` | `[public]` |

## `zombie.iso.IsoDirections`

Vanilla source: [42.22.2/decompiled/zombie/iso/IsoDirections.java](../../../42.22.2/decompiled/zombie/iso/IsoDirections.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `RotLeft(int)` | `[public]` |
| CALL | `RotRight(int)` | `[public]` |
| CALL | `fromString(java.lang.String)` | `[public, static]` |
| CALL | `valueOf(java.lang.String)` | `[public, static]` |
| FIELD_READ | `E` | `[public, static, final]` |
| FIELD_READ | `N` | `[public, static, final]` |
| FIELD_READ | `S` | `[public, static, final]` |
| FIELD_READ | `W` | `[public, static, final]` |

## `zombie.iso.IsoGridSquare`

Vanilla source: [42.22.2/decompiled/zombie/iso/IsoGridSquare.java](../../../42.22.2/decompiled/zombie/iso/IsoGridSquare.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `HasStairs()` | `[public]` |
| CALL | `TreatAsSolidFloor()` | `[public]` |
| CALL | `addCorpse(zombie.iso.objects.IsoDeadBody,boolean)` | `[public]` |
| CALL | `getBuildingDef()` | `[public]` |
| CALL | `getDoorTo(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `getHoppableTo(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `getMovingObjects()` | `[public]` |
| CALL | `getObjects()` | `[public]` |
| CALL | `getRoom()` | `[public]` |
| CALL | `getStaticMovingObjects()` | `[public]` |
| CALL | `getWindowFrameTo(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `getWindowThumpableTo(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `getWindowTo(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `has(zombie.iso.SpriteDetails.IsoFlagType)` | `[public]` |
| CALL | `isFree(boolean)` | `[public]` |
| CALL | `isPlayerAbleToHopWallTo(zombie.iso.IsoDirections,zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `isSolid()` | `[public]` |
| CALL | `isSolidTrans()` | `[public]` |
| CALL | `isSomethingTo(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `removeCorpse(zombie.iso.objects.IsoDeadBody,boolean)` | `[public]` |
| CALL | `testCollideAdjacentAdvanced(int,int,int,boolean)` | `[public]` |
| FIELD_READ | `x` | `[public]` |
| FIELD_READ | `y` | `[public]` |
| FIELD_READ | `z` | `[public]` |

## `zombie.iso.IsoMovingObject`

Vanilla source: [42.22.2/decompiled/zombie/iso/IsoMovingObject.java](../../../42.22.2/decompiled/zombie/iso/IsoMovingObject.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getCurrentSquare()` | `[public]` |
| CALL | `getHitDir()` | `[public]` |
| CALL | `getHitForce()` | `[public]` |
| CALL | `getID()` | `[public]` |
| CALL | `getSquare()` | `[public]` |
| CALL | `getX()` | `[public]` |
| CALL | `getY()` | `[public]` |
| CALL | `getZ()` | `[public]` |
| CALL | `isOnFloor()` | `[public]` |
| CALL | `removeFromSquare()` | `[public]` |
| CALL | `setCurrent(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `setCurrentSquare(zombie.iso.IsoGridSquare)` | `[public]` |
| CALL | `setHitForce(float)` | `[public]` |
| CALL | `setLastX(float)` | `[public]` |
| CALL | `setLastY(float)` | `[public]` |
| CALL | `setMovingSquareNow()` | `[public]` |
| CALL | `setNextX(float)` | `[public]` |
| CALL | `setNextY(float)` | `[public]` |
| CALL | `setOnFloor(boolean)` | `[public]` |
| CALL | `setX(float)` | `[public]` |
| CALL | `setY(float)` | `[public]` |
| CALL | `setZ(float)` | `[public]` |

## `zombie.iso.IsoObject`

Vanilla source: [42.22.2/decompiled/zombie/iso/IsoObject.java](../../../42.22.2/decompiled/zombie/iso/IsoObject.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getCell()` | `[public]` |
| CALL | `getContainer()` | `[public]` |
| CALL | `getDoRender()` | `[public]` |
| CALL | `getModData()` | `[public]` |
| CALL | `getObjectIndex()` | `[public]` |
| CALL | `getProperties()` | `[public]` |
| CALL | `getSprite()` | `[public]` |
| CALL | `getSquare()` | `[public]` |
| CALL | `getStaticMovingObjectIndex()` | `[public]` |
| CALL | `isTallHoppable()` | `[public]` |
| CALL | `setDoRender(boolean)` | `[public]` |
| CALL | `setSatChair(boolean)` | `[public]` |
| CALL | `setSquare(zombie.iso.IsoGridSquare)` | `[public]` |

## `zombie.iso.IsoWorld`

Vanilla source: [42.22.2/decompiled/zombie/iso/IsoWorld.java](../../../42.22.2/decompiled/zombie/iso/IsoWorld.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `currentCell` | `[public]` |
| FIELD_READ | `instance` | `[public, static]` |

## `zombie.iso.LosUtil`

Vanilla source: [42.22.2/decompiled/zombie/iso/LosUtil.java](../../../42.22.2/decompiled/zombie/iso/LosUtil.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `lineClear(zombie.iso.IsoCell,int,int,int,int,int,int,boolean)` | `[public, static]` |

## `zombie.iso.LosUtil.TestResults`

Vanilla source: [42.22.2/decompiled/zombie/iso/LosUtil.java](../../../42.22.2/decompiled/zombie/iso/LosUtil.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `Blocked` | `[public, static, final]` |
| FIELD_READ | `ClearThroughClosedDoor` | `[public, static, final]` |
| FIELD_READ | `ClearThroughWindow` | `[public, static, final]` |

## `zombie.iso.SpriteDetails.IsoFlagType`

Vanilla source: [42.22.2/decompiled/zombie/iso/SpriteDetails/IsoFlagType.java](../../../42.22.2/decompiled/zombie/iso/SpriteDetails/IsoFlagType.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `solid` | `[public, static, final]` |
| FIELD_READ | `solidtrans` | `[public, static, final]` |
| FIELD_READ | `water` | `[public, static, final]` |

## `zombie.iso.Vector2`

Vanilla source: [42.22.2/decompiled/zombie/iso/Vector2.java](../../../42.22.2/decompiled/zombie/iso/Vector2.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getLength()` | `[public]` |
| CALL | `set(float,float)` | `[public]` |
| CONSTRUCT | `Vector2(float,float)` | `[public]` |

## `zombie.iso.Vector3`

Vanilla source: [42.22.2/decompiled/zombie/iso/Vector3.java](../../../42.22.2/decompiled/zombie/iso/Vector3.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(float,float,float)` | `[public]` |

## `zombie.iso.areas.NonPvpZone`

Vanilla source: [42.22.2/decompiled/zombie/iso/areas/NonPvpZone.java](../../../42.22.2/decompiled/zombie/iso/areas/NonPvpZone.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `isInNonPvpZone(zombie.characters.IsoPlayer)` | `[public, static]` |

## `zombie.iso.objects.IsoDeadBody`

Vanilla source: [42.22.2/decompiled/zombie/iso/objects/IsoDeadBody.java](../../../42.22.2/decompiled/zombie/iso/objects/IsoDeadBody.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getAngle()` | `[public]` |
| CALL | `getObjectID()` | `[public]` |
| CALL | `isAnimal()` | `[public]` |
| CALL | `isFakeDead()` | `[public]` |
| CALL | `reanimate()` | `[public]` |
| CALL | `setReanimateTime(float)` | `[public]` |
| CONSTRUCT | `IsoDeadBody(zombie.characters.IsoGameCharacter)` | `[public]` |

## `zombie.iso.objects.IsoDoor`

Vanilla source: [42.22.2/decompiled/zombie/iso/objects/IsoDoor.java](../../../42.22.2/decompiled/zombie/iso/objects/IsoDoor.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `IsOpen()` | `[public]` |
| CALL | `ToggleDoor(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `couldBeOpen(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `isBarricaded()` | `[public]` |
| CALL | `isObstructed()` | `[public]` |

## `zombie.iso.objects.IsoThumpable`

Vanilla source: [42.22.2/decompiled/zombie/iso/objects/IsoThumpable.java](../../../42.22.2/decompiled/zombie/iso/objects/IsoThumpable.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `IsOpen()` | `[public]` |
| CALL | `ToggleDoor(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `canClimbThrough(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `couldBeOpen(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `isBarricaded()` | `[public]` |
| CALL | `isDoor()` | `[public]` |
| CALL | `isObstructed()` | `[public]` |

## `zombie.iso.objects.IsoWindow`

Vanilla source: [42.22.2/decompiled/zombie/iso/objects/IsoWindow.java](../../../42.22.2/decompiled/zombie/iso/objects/IsoWindow.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `IsOpen()` | `[public]` |
| CALL | `ToggleWindow(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `canClimbThrough(zombie.characters.IsoGameCharacter)` | `[public]` |
| CALL | `isBarricaded()` | `[public]` |
| CALL | `isGlassRemoved()` | `[public]` |
| CALL | `isInvincible()` | `[public]` |
| CALL | `isLocked()` | `[public]` |
| CALL | `isPermaLocked()` | `[public]` |
| CALL | `isSmashed()` | `[public]` |

## `zombie.iso.objects.IsoWindowFrame`

Vanilla source: [42.22.2/decompiled/zombie/iso/objects/IsoWindowFrame.java](../../../42.22.2/decompiled/zombie/iso/objects/IsoWindowFrame.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `canClimbThrough(zombie.characters.IsoGameCharacter)` | `[public]` |

## `zombie.iso.objects.IsoZombieGiblets`

Vanilla source: [42.22.2/decompiled/zombie/iso/objects/IsoZombieGiblets.java](../../../42.22.2/decompiled/zombie/iso/objects/IsoZombieGiblets.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.iso.sprite.IsoSprite`

Vanilla source: [42.22.2/decompiled/zombie/iso/sprite/IsoSprite.java](../../../42.22.2/decompiled/zombie/iso/sprite/IsoSprite.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getName()` | `[public]` |
| FIELD_READ | `tileSheetIndex` | `[public]` |
| FIELD_READ | `tilesetName` | `[public]` |

## `zombie.network.GameServer`

Vanilla source: [42.22.2/decompiled/zombie/network/GameServer.java](../../../42.22.2/decompiled/zombie/network/GameServer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getConnectionFromPlayer(zombie.characters.IsoPlayer)` | `[public, static]` |
| CALL | `isDelayedDisconnect(zombie.characters.IsoPlayer)` | `[public, static]` |
| CALL | `isDelayedDisconnect(zombie.core.raknet.UdpConnection)` | `[public, static]` |
| CALL | `sendCorpse(zombie.iso.objects.IsoDeadBody)` | `[public, static]` |
| CALL | `sendPlayerConnected(zombie.characters.IsoPlayer,zombie.network.IConnection)` | `[public, static]` |
| CALL | `sendTeleport(zombie.characters.IsoPlayer,float,float,float)` | `[public, static]` |
| FIELD_READ | `IDToAddressMap` | `[public, static, final]` |
| FIELD_READ | `IDToPlayerMap` | `[public, static, final]` |
| FIELD_READ | `Players` | `[public, static, final]` |
| FIELD_READ | `mainThread` | `[public, static]` |
| FIELD_READ | `server` | `[public, static]` |
| FIELD_READ | `serverName` | `[public, static]` |
| FIELD_READ | `udpEngine` | `[public, static]` |

## `zombie.network.IConnection`

Vanilla source: [42.22.2/decompiled/zombie/network/IConnection.java](../../../42.22.2/decompiled/zombie/network/IConnection.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.network.IsoObjectID`

Vanilla source: [42.22.2/decompiled/zombie/network/IsoObjectID.java](../../../42.22.2/decompiled/zombie/network/IsoObjectID.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `get(short)` | `[public]` |

## `zombie.network.NetworkVariables`

Vanilla source: [42.22.2/decompiled/zombie/network/NetworkVariables.java](../../../42.22.2/decompiled/zombie/network/NetworkVariables.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.network.NetworkVariables.ZombieState`

Vanilla source: [42.22.2/decompiled/zombie/network/NetworkVariables.java](../../../42.22.2/decompiled/zombie/network/NetworkVariables.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `toString()` | `[public]` |
| FIELD_READ | `Attack` | `[public, static, final]` |
| FIELD_READ | `Grappled` | `[public, static, final]` |
| FIELD_READ | `Idle` | `[public, static, final]` |
| FIELD_READ | `Lunge` | `[public, static, final]` |
| FIELD_READ | `LungeNetwork` | `[public, static, final]` |
| FIELD_READ | `PathFind` | `[public, static, final]` |
| FIELD_READ | `WalkToward` | `[public, static, final]` |
| FIELD_READ | `WalkTowardNetwork` | `[public, static, final]` |

## `zombie.network.PZNetKahluaTableImpl`

Vanilla source: [42.22.2/decompiled/zombie/network/PZNetKahluaTableImpl.java](../../../42.22.2/decompiled/zombie/network/PZNetKahluaTableImpl.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `rawset(java.lang.Object,java.lang.Object)` | `[public]` |

## `zombie.network.PacketTypes`

Vanilla source: [42.22.2/decompiled/zombie/network/PacketTypes.java](../../../42.22.2/decompiled/zombie/network/PacketTypes.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.network.PacketTypes.PacketType`

Vanilla source: [42.22.2/decompiled/zombie/network/PacketTypes.java](../../../42.22.2/decompiled/zombie/network/PacketTypes.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `AcceptTrading` | `[public, static, final]` |
| FIELD_READ | `BodyPartSync` | `[public, static, final]` |
| FIELD_READ | `Equip` | `[public, static, final]` |
| FIELD_READ | `PlaySound` | `[public, static, final]` |
| FIELD_READ | `PlayerHit` | `[public, static, final]` |
| FIELD_READ | `PlayerHitPlayer` | `[public, static, final]` |
| FIELD_READ | `PlayerHitSquare` | `[public, static, final]` |
| FIELD_READ | `PlayerHitZombie` | `[public, static, final]` |
| FIELD_READ | `PlayerInjuries` | `[public, static, final]` |
| FIELD_READ | `PlayerTimeout` | `[public, static, final]` |
| FIELD_READ | `PlayerUpdateReliable` | `[public, static, final]` |
| FIELD_READ | `RangedWeaponSound` | `[public, static, final]` |
| FIELD_READ | `RejectTrading` | `[public, static, final]` |
| FIELD_READ | `RemoveInventoryItemFromContainer` | `[public, static, final]` |
| FIELD_READ | `State` | `[public, static, final]` |
| FIELD_READ | `TradingUIUpdateState` | `[public, static, final]` |
| FIELD_READ | `VariableSync` | `[public, static, final]` |

## `zombie.network.ServerMap`

Vanilla source: [42.22.2/decompiled/zombie/network/ServerMap.java](../../../42.22.2/decompiled/zombie/network/ServerMap.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `instance` | `[public, static]` |
| FIELD_READ | `zombieMap` | `[public, final]` |

## `zombie.network.ServerOptions`

Vanilla source: [42.22.2/decompiled/zombie/network/ServerOptions.java](../../../42.22.2/decompiled/zombie/network/ServerOptions.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `instance` | `[public, static, final]` |
| FIELD_READ | `pvp` | `[public]` |
| FIELD_READ | `safetySystem` | `[public]` |

## `zombie.network.chat.ChatServer`

Vanilla source: [42.22.2/decompiled/zombie/network/chat/ChatServer.java](../../../42.22.2/decompiled/zombie/network/chat/ChatServer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getInstance()` | `[public, static]` |

## `zombie.network.fields.IDShort`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/IDShort.java](../../../42.22.2/decompiled/zombie/network/fields/IDShort.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getID()` | `[public]` |

## `zombie.network.fields.MovingObject`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/MovingObject.java](../../../42.22.2/decompiled/zombie/network/fields/MovingObject.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.iso.IsoObject)` | `[public]` |

## `zombie.network.fields.Position`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/Position.java](../../../42.22.2/decompiled/zombie/network/fields/Position.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(float,float,float)` | `[public]` |

## `zombie.network.fields.StateID`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/StateID.java](../../../42.22.2/decompiled/zombie/network/fields/StateID.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.ai.State)` | `[public]` |

## `zombie.network.fields.Variables`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/Variables.java](../../../42.22.2/decompiled/zombie/network/fields/Variables.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `get()` | `[public]` |
| CONSTRUCT | `Variables()` | `[public]` |

## `zombie.network.fields.character.CharacterID`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/character/CharacterID.java](../../../42.22.2/decompiled/zombie/network/fields/character/CharacterID.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.characters.IsoGameCharacter)` | `[public]` |

## `zombie.network.fields.character.ConnectionPlayer`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/character/ConnectionPlayer.java](../../../42.22.2/decompiled/zombie/network/fields/character/ConnectionPlayer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.characters.IsoPlayer)` | `[public]` |
| CONSTRUCT | `ConnectionPlayer()` | `[public]` |

## `zombie.network.fields.character.OnlinePlayer`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/character/OnlinePlayer.java](../../../42.22.2/decompiled/zombie/network/fields/character/OnlinePlayer.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.characters.IsoPlayer)` | `[public]` |

## `zombie.network.fields.character.PFBData`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/character/PFBData.java](../../../42.22.2/decompiled/zombie/network/fields/character/PFBData.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `reset()` | `[public]` |

## `zombie.network.fields.character.Player`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/character/Player.java](../../../42.22.2/decompiled/zombie/network/fields/character/Player.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getId()` | `[public]` |
| CALL | `getPlayer()` | `[public]` |
| CALL | `write(zombie.core.network.ByteBufferWriter)` | `[public]` |

## `zombie.network.fields.character.PlayerVariables`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/character/PlayerVariables.java](../../../42.22.2/decompiled/zombie/network/fields/character/PlayerVariables.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.characters.IsoPlayer)` | `[public]` |

## `zombie.network.fields.character.Prediction`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/character/Prediction.java](../../../42.22.2/decompiled/zombie/network/fields/character/Prediction.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `position` | `[public, final]` |
| FIELD_READ | `x` | `[public]` |
| FIELD_READ | `y` | `[public]` |
| FIELD_READ | `z` | `[public]` |
| FIELD_WRITE | `direction` | `[public]` |
| FIELD_WRITE | `distance` | `[public]` |
| FIELD_WRITE | `moveDirection` | `[public]` |
| FIELD_WRITE | `speed` | `[public]` |
| FIELD_WRITE | `type` | `[public]` |
| FIELD_WRITE | `x` | `[public]` |
| FIELD_WRITE | `y` | `[public]` |
| FIELD_WRITE | `z` | `[public]` |

## `zombie.network.fields.hit.AttackVars`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/hit/AttackVars.java](../../../42.22.2/decompiled/zombie/network/fields/hit/AttackVars.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `clear()` | `[public]` |
| CALL | `setWeapon(zombie.inventory.types.HandWeapon)` | `[public]` |
| FIELD_READ | `targetDistance` | `[public]` |
| FIELD_READ | `targetOnGround` | `[public]` |
| FIELD_READ | `targetStanding` | `[public]` |
| FIELD_WRITE | `aimAtFloor` | `[public]` |
| FIELD_WRITE | `targetDistance` | `[public]` |
| FIELD_WRITE | `useChargeDelta` | `[public]` |

## `zombie.network.fields.hit.Fall`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/hit/Fall.java](../../../42.22.2/decompiled/zombie/network/fields/hit/Fall.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(float,float,byte,float)` | `[public]` |

## `zombie.network.fields.hit.Hit`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/hit/Hit.java](../../../42.22.2/decompiled/zombie/network/fields/hit/Hit.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `Hit()` | `[public]` |
| CALL | `set(float,float,float,zombie.CombatManager.HitData,boolean,float)` | `[public]` |
| CALL | `write(zombie.core.network.ByteBufferWriter)` | `[public]` |
| FIELD_READ | `hitDamage` | `[protected]` |
| FIELD_READ | `hitDirectionX` | `[protected]` |
| FIELD_READ | `hitDirectionY` | `[protected]` |
| FIELD_READ | `hitForce` | `[protected]` |

## `zombie.network.fields.hit.HitInfo`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/hit/HitInfo.java](../../../42.22.2/decompiled/zombie/network/fields/hit/HitInfo.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `init(zombie.iso.IsoMovingObject,float,float,float,float,float)` | `[public]` |
| CONSTRUCT | `HitInfo()` | `[public]` |

## `zombie.network.fields.hit.Player`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/hit/Player.java](../../../42.22.2/decompiled/zombie/network/fields/hit/Player.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getPlayer()` | `[public]` |
| CALL | `set(zombie.characters.IsoPlayer)` | `[public]` |

## `zombie.network.fields.hit.Weapon`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/hit/Weapon.java](../../../42.22.2/decompiled/zombie/network/fields/hit/Weapon.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.inventory.types.HandWeapon)` | `[public]` |
| CALL | `write(zombie.core.network.ByteBufferWriter)` | `[public]` |
| CONSTRUCT | `Weapon()` | `[public]` |

## `zombie.network.fields.hit.Zombie`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/hit/Zombie.java](../../../42.22.2/decompiled/zombie/network/fields/hit/Zombie.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getZombie()` | `[public]` |
| CALL | `set(zombie.characters.IsoZombie)` | `[public]` |

## `zombie.network.fields.vehicle.VehicleID`

Vanilla source: [42.22.2/decompiled/zombie/network/fields/vehicle/VehicleID.java](../../../42.22.2/decompiled/zombie/network/fields/vehicle/VehicleID.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `set(zombie.vehicles.BaseVehicle)` | `[public]` |

## `zombie.network.id.ObjectIDManager`

Vanilla source: [42.22.2/decompiled/zombie/network/id/ObjectIDManager.java](../../../42.22.2/decompiled/zombie/network/id/ObjectIDManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `get(zombie.network.id.ObjectID)` | `[public, static]` |

## `zombie.network.packets.INetworkPacket`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/INetworkPacket.java](../../../42.22.2/decompiled/zombie/network/packets/INetworkPacket.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `send(zombie.network.IConnection,zombie.network.PacketTypes.PacketType,java.lang.Object...)` | `[public, static]` |
| CALL | `sendToClient(zombie.network.PacketTypes.PacketType,zombie.network.IConnection)` | `[public, default]` |

## `zombie.network.packets.actions.StatePacket`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/actions/StatePacket.java](../../../42.22.2/decompiled/zombie/network/packets/actions/StatePacket.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `StatePacket()` | `[public]` |
| FIELD_READ | `characterId` | `[protected, final]` |
| FIELD_READ | `events` | `[protected, final]` |
| FIELD_READ | `params` | `[protected]` |
| FIELD_READ | `state` | `[protected, final]` |
| FIELD_WRITE | `isSubState` | `[protected]` |
| FIELD_WRITE | `stage` | `[protected]` |

## `zombie.network.packets.character.PlayerPacket`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/character/PlayerPacket.java](../../../42.22.2/decompiled/zombie/network/packets/character/PlayerPacket.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `hitVehicleId` | `[public, final]` |
| FIELD_READ | `player` | `[public, final]` |
| FIELD_READ | `prediction` | `[public, final]` |
| FIELD_READ | `variables` | `[public, final]` |
| FIELD_WRITE | `booleanVariables` | `[public]` |
| FIELD_WRITE | `disconnected` | `[public]` |

## `zombie.network.packets.character.PlayerPacketReliable`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/character/PlayerPacketReliable.java](../../../42.22.2/decompiled/zombie/network/packets/character/PlayerPacketReliable.java).

| Kind | Member | Access |
| --- | --- | --- |
| CONSTRUCT | `PlayerPacketReliable()` | `[public]` |

## `zombie.network.packets.character.ZombiePacket`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/character/ZombiePacket.java](../../../42.22.2/decompiled/zombie/network/packets/character/ZombiePacket.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `booleanVariables` | `[public]` |
| FIELD_READ | `pfb` | `[public, final]` |
| FIELD_READ | `predictionType` | `[public]` |
| FIELD_READ | `realState` | `[public]` |
| FIELD_READ | `realX` | `[public]` |
| FIELD_READ | `realY` | `[public]` |
| FIELD_READ | `realZ` | `[public]` |
| FIELD_READ | `update` | `[public]` |
| FIELD_WRITE | `booleanVariables` | `[public]` |
| FIELD_WRITE | `predictionType` | `[public]` |
| FIELD_WRITE | `realState` | `[public]` |
| FIELD_WRITE | `realX` | `[public]` |
| FIELD_WRITE | `realY` | `[public]` |
| FIELD_WRITE | `realZ` | `[public]` |
| FIELD_WRITE | `target` | `[public]` |
| FIELD_WRITE | `update` | `[public]` |
| FIELD_WRITE | `x` | `[public]` |
| FIELD_WRITE | `y` | `[public]` |
| FIELD_WRITE | `z` | `[public]` |

## `zombie.network.packets.hit.HitCharacter`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/hit/HitCharacter.java](../../../42.22.2/decompiled/zombie/network/packets/hit/HitCharacter.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `processClient(zombie.core.raknet.UdpConnection)` | `[public, default]` |

## `zombie.network.packets.hit.PlayerHit`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/hit/PlayerHit.java](../../../42.22.2/decompiled/zombie/network/packets/hit/PlayerHit.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `resetAttackState()` | `[public, static]` |
| CALL | `write(zombie.core.network.ByteBufferWriter)` | `[public]` |

## `zombie.network.packets.hit.PlayerHitPlayerPacket`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/hit/PlayerHitPlayerPacket.java](../../../42.22.2/decompiled/zombie/network/packets/hit/PlayerHitPlayerPacket.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `PlayerHitPlayerPacket()` | `[public]` |
| CALL | `processTarget()` | `[protected]` |
| CALL | `set(zombie.characters.IsoPlayer,zombie.inventory.types.HandWeapon,zombie.CombatManager.TargetHitList,java.util.List<zombie.network.fields.hit.TracerInfo>)` | `[public]` |
| FIELD_READ | `fall` | `[protected, final]` |
| FIELD_READ | `target` | `[public, final]` |

## `zombie.network.packets.hit.PlayerHitSquarePacket`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/hit/PlayerHitSquarePacket.java](../../../42.22.2/decompiled/zombie/network/packets/hit/PlayerHitSquarePacket.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `PlayerHitSquarePacket()` | `[public]` |
| CALL | `set(zombie.characters.IsoPlayer,zombie.inventory.types.HandWeapon,zombie.CombatManager.TargetHitList,java.util.List<zombie.network.fields.hit.TracerInfo>)` | `[public]` |

## `zombie.network.packets.hit.PlayerHitZombiePacket`

Vanilla source: [42.22.2/decompiled/zombie/network/packets/hit/PlayerHitZombiePacket.java](../../../42.22.2/decompiled/zombie/network/packets/hit/PlayerHitZombiePacket.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `PlayerHitZombiePacket()` | `[public]` |
| CALL | `processTarget()` | `[protected]` |
| CALL | `set(zombie.characters.IsoPlayer,zombie.inventory.types.HandWeapon,zombie.CombatManager.TargetHitList,java.util.List<zombie.network.fields.hit.TracerInfo>)` | `[public]` |
| FIELD_READ | `fall` | `[protected, final]` |
| FIELD_READ | `target` | `[protected, final]` |

## `zombie.pathfind.IPathfinder`

Vanilla source: [42.22.2/decompiled/zombie/pathfind/IPathfinder.java](../../../42.22.2/decompiled/zombie/pathfind/IPathfinder.java).

| Kind | Member | Access |
| --- | --- | --- |
| TYPE | Type-only reference | See CSV |

## `zombie.pathfind.Path`

Vanilla source: [42.22.2/decompiled/zombie/pathfind/Path.java](../../../42.22.2/decompiled/zombie/pathfind/Path.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getNode(int)` | `[public]` |
| CALL | `size()` | `[public]` |

## `zombie.pathfind.PathNode`

Vanilla source: [42.22.2/decompiled/zombie/pathfind/PathNode.java](../../../42.22.2/decompiled/zombie/pathfind/PathNode.java).

| Kind | Member | Access |
| --- | --- | --- |
| FIELD_READ | `x` | `[public]` |
| FIELD_READ | `y` | `[public]` |
| FIELD_READ | `z` | `[public]` |

## `zombie.pathfind.PolygonalMap2`

Vanilla source: [42.22.2/decompiled/zombie/pathfind/PolygonalMap2.java](../../../42.22.2/decompiled/zombie/pathfind/PolygonalMap2.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `addRequest(zombie.pathfind.IPathfinder,zombie.ai.astar.Mover,float,float,float,float,float,float)` | `[public]` |
| CALL | `canStandAt(float,float,int,zombie.iso.IsoMovingObject,boolean,boolean)` | `[public]` |
| CALL | `cancelRequest(zombie.ai.astar.Mover)` | `[public]` |
| CALL | `lineClearCollide(float,float,float,float,int,zombie.iso.IsoMovingObject,boolean,boolean)` | `[public]` |
| CALL | `updateMain()` | `[public]` |
| FIELD_READ | `instance` | `[public, static, final]` |

## `zombie.pathfind.nativeCode.PathfindNative`

Vanilla source: [42.22.2/decompiled/zombie/pathfind/nativeCode/PathfindNative.java](../../../42.22.2/decompiled/zombie/pathfind/nativeCode/PathfindNative.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `addRequest(zombie.pathfind.IPathfinder,zombie.ai.astar.Mover,float,float,float,float,float,float,boolean)` | `[public]` |
| CALL | `cancelRequest(zombie.ai.astar.Mover)` | `[public]` |
| CALL | `updateMain()` | `[public]` |
| FIELD_READ | `instance` | `[public, static, final]` |
| FIELD_READ | `useNativeCode` | `[public, static]` |

## `zombie.popman.NetworkZombieManager`

Vanilla source: [42.22.2/decompiled/zombie/popman/NetworkZombieManager.java](../../../42.22.2/decompiled/zombie/popman/NetworkZombieManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getInstance()` | `[public, static]` |
| CALL | `moveZombie(zombie.characters.IsoZombie,zombie.core.raknet.UdpConnection,zombie.characters.IsoPlayer)` | `[public]` |
| CALL | `updateAuth(zombie.characters.IsoZombie)` | `[public]` |

## `zombie.savefile.ServerPlayerDB`

Vanilla source: [42.22.2/decompiled/zombie/savefile/ServerPlayerDB.java](../../../42.22.2/decompiled/zombie/savefile/ServerPlayerDB.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getInstance()` | `[public, static, synchronized]` |
| CALL | `isAvailable()` | `[public, static]` |
| CALL | `serverUpdateNetworkCharacter(zombie.characters.IsoPlayer,int,zombie.core.raknet.UdpConnection)` | `[public]` |

## `zombie.seating.SeatingManager`

Vanilla source: [42.22.2/decompiled/zombie/seating/SeatingManager.java](../../../42.22.2/decompiled/zombie/seating/SeatingManager.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `getFacingDirection(zombie.iso.sprite.IsoSprite)` | `[public]` |
| CALL | `getInstance()` | `[public, static]` |
| CALL | `getModIDs()` | `[public]` |
| CALL | `getTilePositionCount(java.lang.String,int,int)` | `[public]` |
| CALL | `getTilePositionCount(java.lang.String,java.lang.String,int,int)` | `[public]` |
| CALL | `getTilePositionID(java.lang.String,java.lang.String,int,int,int)` | `[public]` |
| CALL | `getTilePositionProperty(java.lang.String,java.lang.String,int,int,int,java.lang.String)` | `[public]` |
| CALL | `getTilePositionTranslate(java.lang.String,java.lang.String,int,int,int)` | `[public]` |

## `zombie.util.StringUtils`

Vanilla source: [42.22.2/decompiled/zombie/util/StringUtils.java](../../../42.22.2/decompiled/zombie/util/StringUtils.java).

| Kind | Member | Access |
| --- | --- | --- |
| CALL | `isNullOrEmpty(java.lang.String)` | `[public, static]` |
