#!/usr/bin/env bash
# patchApocalipseBr.sh
# Compiles ALL ApocBR patched Java sources and deploys .class files
# to Project Zomboid via classpath override.
#
# Combined deploy script for all ApocBR patches targeting Build 42.19:
#
# 1. Zombie NoCull Fix   â€“ MovingObjectUpdateScheduler.postupdate()
#    Removes ZombieCountOptimiser.deleteZombies() call that aggressively
#    culls zombie populations on servers with many connected players.
#
# 2. Pathfind Safety     â€“ PathfindNative + ChunkUpdateTask
#    Stale-chunk guard that prevents SIGSEGV crashes in libPZPathFind64.so
#    when a ChunkUpdateTask executes after its chunk has been removed or
#    reloaded in native pathfind state.
#
# 3. NullCraft Fix       â€“ CompressIdenticalItems.save() Null Guard
#    Adds a null guard in save(ByteBuffer, InventoryItem) to prevent NPE
#    when a drying/curing craft item becomes null, which would corrupt
#    chunk saves and cause vehicles to vanish.
#
# 4. Async Save Telemetry â€“ ServerMap, ApocBRServerTelemetry, etc.
#    Async background save (ServerMap) + ApocBR server telemetry +
#    guarded IsoWorld parallelism + vehicle hit-field optimizations.
#
# All classes are compiled in a single javac invocation so there are no
# conflicts between patches that touch the same class.
#
# Strategy: classpath override via "java/." before "java/projectzomboid.jar".
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.
#
# Usage:
#   ./patchApocalipseBr.sh [--pz-dir PATH] [--dry-run] [--revert]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="ApocBR All-in-One (NoCull + PathfindSafety + NullCraft + AsyncSaveTelemetry)"
PZ_DIR="/opt/pzserver"
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pz-dir)    PZ_DIR="$2";  shift 2 ;;
        --dry-run)   DRY_RUN=true; shift ;;
        --revert|-r) REVERT=true;  shift ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Usage: $0 [--pz-dir PATH] [--dry-run] [--revert]" >&2
            exit 1
            ;;
    esac
done

# Auto-detect JAR location (Linux server: java/ subdir; Windows: PZ root)
if [[ -f "$PZ_DIR/java/projectzomboid.jar" ]]; then
    JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR/java"
else
    JAR_FILE="$PZ_DIR/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR"
fi

SRC_ROOT="$SCRIPT_DIR/src"
BACKUP_DIR="$SCRIPT_DIR/backups/ApocalipseBr"
WORK_DIR="$(mktemp -d /tmp/pzpatch_apocalipsebr.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT
OUTPUT_DIR="$WORK_DIR/classes"

DEPLOY_ZOMBIE="$DEPLOY_BASE/zombie"
DEPLOY_POPMAN="$DEPLOY_BASE/zombie/popman"
DEPLOY_NET="$DEPLOY_BASE/zombie/network"
DEPLOY_GAMESTATES="$DEPLOY_BASE/zombie/gameStates"
DEPLOY_ISO="$DEPLOY_BASE/zombie/iso"
DEPLOY_VEHICLES="$DEPLOY_BASE/zombie/vehicles"
DEPLOY_PATHFIND="$DEPLOY_BASE/zombie/pathfind/nativeCode"
DEPLOY_INVENTORY="$DEPLOY_BASE/zombie/inventory"
DEPLOY_LUA="$DEPLOY_BASE/zombie/Lua"
DEPLOY_CHARACTERS_ANIMALS="$DEPLOY_BASE/zombie/characters/animals"

REQUIRED_MAJOR=25

# --- All patched source files ---
SOURCES=(
    "$SRC_ROOT/zombie/ApocBRServerTelemetry.java"
    "$SRC_ROOT/zombie/GameTime.java"
    "$SRC_ROOT/zombie/MovingObjectUpdateScheduler.java"
    "$SRC_ROOT/zombie/MovingObjectUpdateSchedulerUpdateBucket.java"
    "$SRC_ROOT/zombie/popman/NetworkZombiePacker.java"
    "$SRC_ROOT/zombie/popman/ZombiePopulationManager.java"
    "$SRC_ROOT/zombie/Lua/ApocBRMainThreadLuaQueue.java"
    "$SRC_ROOT/zombie/Lua/AsyncLuaManager.java"
    "$SRC_ROOT/zombie/Lua/LuaManager.java"
    "$SRC_ROOT/zombie/WorldSoundManager.java"
    "$SRC_ROOT/zombie/radio/ZomboidRadio.java"
    "$SRC_ROOT/zombie/inventory/ItemContainer.java"
    "$SRC_ROOT/zombie/iso/FishSchoolManager.java"
    "$SRC_ROOT/zombie/iso/IsoPuddlesCompute.java"
    "$SRC_ROOT/zombie/iso/IsoChunk.java"
    "$SRC_ROOT/zombie/iso/IsoGridSquare.java"
    "$SRC_ROOT/zombie/iso/objects/IsoZombieGiblets.java"
  "$SRC_ROOT/zombie/iso/objects/IsoDoor.java"
  "$SRC_ROOT/zombie/iso/WorldReuserThread.java"
  "$SRC_ROOT/zombie/vehicles/BaseVehicle.java"
  "$SRC_ROOT/zombie/vehicles/VehicleManager.java"
    "$SRC_ROOT/zombie/entity/GameEntity.java"
    "$SRC_ROOT/zombie/entity/EntityBucket.java"
    "$SRC_ROOT/zombie/entity/EntityBucketManager.java"
    "$SRC_ROOT/zombie/entity/EngineEntityManager.java"
    "$SRC_ROOT/zombie/entity/UsingPlayerUpdateSystem.java"
    "$SRC_ROOT/zombie/entity/components/fluids/FluidContainerUpdateSystem.java"
    "$SRC_ROOT/zombie/network/GameServer.java"
    "$SRC_ROOT/zombie/gameStates/IngameState.java"
    "$SRC_ROOT/zombie/iso/IsoWorld.java"
    "$SRC_ROOT/zombie/iso/IsoCell.java"
    "$SRC_ROOT/zombie/network/PlayerDownloadServer.java"
    "$SRC_ROOT/zombie/network/ServerMap.java"
    "$SRC_ROOT/zombie/pathfind/PathFindBehavior2.java"
    "$SRC_ROOT/zombie/pathfind/nativeCode/PathfindNative.java"
    "$SRC_ROOT/zombie/pathfind/nativeCode/ChunkUpdateTask.java"
    "$SRC_ROOT/zombie/pathfind/LineClearCollideMain.java"
    "$SRC_ROOT/zombie/inventory/CompressIdenticalItems.java"
    "$SRC_ROOT/zombie/network/ServerChunkLoader.java"
    "$SRC_ROOT/zombie/characters/animals/IsoAnimal.java"
    "$SRC_ROOT/zombie/characters/animals/AnimalPopulationManager.java"
    "$SRC_ROOT/zombie/characters/animals/AnimalChunk.java"
    "$SRC_ROOT/zombie/characters/animals/AnimalZones.java"
    "$SRC_ROOT/zombie/characters/animals/VirtualAnimal.java"
    "$SRC_ROOT/zombie/characters/animals/VirtualAnimalState.java"
    "$SRC_ROOT/zombie/characters/IsoGameCharacter.java"
    "$SRC_ROOT/zombie/characters/action/ActionStateContainer.java"
    "$SRC_ROOT/zombie/characters/IsoPlayer.java"
    "$SRC_ROOT/zombie/network/ServerLOS.java"
)

# --- All expected class files (relative to deploy base) ---
CLASSES=(
    "zombie/ApocBRServerTelemetry.class"
    "zombie/GameTime.class"
    "zombie/MovingObjectUpdateScheduler.class"
    "zombie/MovingObjectUpdateSchedulerUpdateBucket.class"
    "zombie/popman/NetworkZombiePacker.class"
    "zombie/popman/ZombiePopulationManager.class"
    "zombie/Lua/ApocBRMainThreadLuaQueue.class"
    'zombie/Lua/ApocBRMainThreadLuaQueue$QueuedLuaCall.class'
    "zombie/Lua/AsyncLuaManager.class"
    'zombie/Lua/LuaManager$GlobalObject.class'
    "zombie/WorldSoundManager.class"
    'zombie/WorldSoundManager$ResultBiggestSound.class'
    'zombie/WorldSoundManager$WorldSound.class'
    "zombie/radio/ZomboidRadio.class"
    'zombie/radio/ZomboidRadio$FreqListEntry.class'
    "zombie/inventory/ItemContainer.class"
    'zombie/inventory/ItemContainer$CategoryPredicate.class'
    'zombie/inventory/ItemContainer$Comparators.class'
    'zombie/inventory/ItemContainer$ConditionComparator.class'
    'zombie/inventory/ItemContainer$EvalArgComparator.class'
    'zombie/inventory/ItemContainer$EvalArgPredicate.class'
    'zombie/inventory/ItemContainer$EvalComparator.class'
    'zombie/inventory/ItemContainer$EvalPredicate.class'
    'zombie/inventory/ItemContainer$InventoryItemList.class'
    'zombie/inventory/ItemContainer$InventoryItemListPool.class'
    'zombie/inventory/ItemContainer$Predicates.class'
    'zombie/inventory/ItemContainer$TagEvalArgPredicate.class'
    'zombie/inventory/ItemContainer$TagEvalPredicate.class'
    'zombie/inventory/ItemContainer$TagPredicate.class'
    'zombie/inventory/ItemContainer$TypeEvalArgPredicate.class'
    'zombie/inventory/ItemContainer$TypeEvalPredicate.class'
    'zombie/inventory/ItemContainer$TypePredicate.class'
    "zombie/iso/FishSchoolManager.class"
    'zombie/iso/FishSchoolManager$ChumData.class'
    'zombie/iso/FishSchoolManager$ZoneData.class'
    "zombie/iso/IsoPuddlesCompute.class"
    "zombie/iso/IsoChunk.class"
    "zombie/iso/IsoGridSquare.class"
    "zombie/iso/objects/IsoZombieGiblets.class"
  "zombie/iso/objects/IsoDoor.class"
  "zombie/iso/WorldReuserThread.class"
  "zombie/vehicles/BaseVehicle.class"
    'zombie/vehicles/BaseVehicle$1.class'
    'zombie/vehicles/BaseVehicle$Authorization.class'
    'zombie/vehicles/BaseVehicle$engineStateTypes.class'
    'zombie/vehicles/BaseVehicle$HitVars.class'
    'zombie/vehicles/BaseVehicle$L_testCollisionWithVehicle.class'
    'zombie/vehicles/BaseVehicle$Matrix4fObjectPool.class'
    'zombie/vehicles/BaseVehicle$MinMaxPosition.class'
    'zombie/vehicles/BaseVehicle$ModelInfo.class'
    'zombie/vehicles/BaseVehicle$Passenger.class'
    'zombie/vehicles/BaseVehicle$QuaternionfObjectPool.class'
    'zombie/vehicles/BaseVehicle$ServerVehicleState.class'
    'zombie/vehicles/BaseVehicle$TransformPool.class'
    'zombie/vehicles/BaseVehicle$UpdateFlags.class'
    'zombie/vehicles/BaseVehicle$Vector2fObjectPool.class'
    'zombie/vehicles/BaseVehicle$Vector3fObjectPool.class'
    'zombie/vehicles/BaseVehicle$Vector3ObjectPool.class'
    'zombie/vehicles/BaseVehicle$Vector4fObjectPool.class'
    'zombie/vehicles/BaseVehicle$VehicleImpulse.class'
    'zombie/vehicles/BaseVehicle$WeightedVehiclePart.class'
    'zombie/vehicles/BaseVehicle$ApocBRBreakingResult.class'
    'zombie/vehicles/BaseVehicle$WheelInfo.class'
  "zombie/vehicles/VehicleManager.class"
  'zombie/vehicles/VehicleManager$PosUpdateVars.class'
    "zombie/entity/GameEntity.class"
    "zombie/entity/EntityBucket.class"
    'zombie/entity/EntityBucket$BucketListenerComparator.class'
    'zombie/entity/EntityBucket$BucketListenerData.class'
    'zombie/entity/EntityBucket$CustomBucket.class'
    'zombie/entity/EntityBucket$EntityValidator.class'
    'zombie/entity/EntityBucket$FamilyBucket.class'
    'zombie/entity/EntityBucket$InventoryItemBucket.class'
    'zombie/entity/EntityBucket$IsoObjectBucket.class'
    'zombie/entity/EntityBucket$RendererBucket.class'
    'zombie/entity/EntityBucket$VehiclePartBucket.class'
    "zombie/entity/EntityBucketManager.class"
    'zombie/entity/EntityBucketManager$BucketsUpdatingInformer.class'
    "zombie/entity/EngineEntityManager.class"
    'zombie/entity/EngineEntityManager$ComponentOperationListener.class'
    'zombie/entity/EngineEntityManager$EntityOperation.class'
    'zombie/entity/EngineEntityManager$EntityOperation$Type.class'
    'zombie/entity/EngineEntityManager$EntityOperationPool.class'
    "zombie/entity/UsingPlayerUpdateSystem.class"
    "zombie/entity/components/fluids/FluidContainerUpdateSystem.class"
    "zombie/network/GameServer.class"
    'zombie/network/GameServer$1.class'
    'zombie/network/GameServer$2.class'
    'zombie/network/GameServer$CCFilter.class'
    'zombie/network/GameServer$DelayedConnection.class'
    'zombie/network/GameServer$MapRemotePlayerVisibility.class'
    'zombie/network/GameServer$s_performance.class'
    "zombie/gameStates/IngameState.class"
    'zombie/gameStates/IngameState$CountFileVisitor.class'
    'zombie/gameStates/IngameState$s_performance.class'
    "zombie/iso/IsoWorld.class"
    'zombie/iso/IsoWorld$CompDistToPlayer.class'
    'zombie/iso/IsoWorld$CompScoreToPlayer.class'
    'zombie/iso/IsoWorld$Frame.class'
    'zombie/iso/IsoWorld$MetaCell.class'
    'zombie/iso/IsoWorld$s_performance.class'
    "zombie/iso/IsoCell.class"
    'zombie/iso/IsoCell$BuildingSearchCriteria.class'
    'zombie/iso/IsoCell$PerPlayerRender.class'
    'zombie/iso/IsoCell$SnowGrid.class'
    'zombie/iso/IsoCell$SnowGridTiles.class'
    'zombie/iso/IsoCell$s_performance.class'
    'zombie/iso/IsoCell$s_performance$renderTiles.class'
    'zombie/iso/IsoCell$s_performance$renderTiles$PerformRenderTilesLayer.class'
    "zombie/network/PlayerDownloadServer.class"
    'zombie/network/PlayerDownloadServer$EThreadCommand.class'
    'zombie/network/PlayerDownloadServer$WorkerThread.class'
    'zombie/network/PlayerDownloadServer$WorkerThreadCommand.class'
    "zombie/network/ServerMap.class"
    'zombie/network/ServerMap$DistToCellComparator.class'
    'zombie/network/ServerMap$EThreadCommand.class'
    'zombie/network/ServerMap$PhaseAResult.class'
    'zombie/network/ServerMap$ServerCell.class'
    'zombie/network/ServerMap$ServerCell$LoadState.class'
    'zombie/network/ServerMap$WorkerThread.class'
    'zombie/network/ServerMap$WorkerThreadCommand.class'
    "zombie/pathfind/PathFindBehavior2.class"
    "zombie/pathfind/nativeCode/PathfindNative.class"
    "zombie/pathfind/nativeCode/ChunkUpdateTask.class"
    "zombie/pathfind/LineClearCollideMain.class"
    "zombie/inventory/CompressIdenticalItems.class"
    'zombie/inventory/CompressIdenticalItems$1.class'
    'zombie/inventory/CompressIdenticalItems$PerCallData.class'
    'zombie/inventory/CompressIdenticalItems$PerThreadData.class'
    "zombie/network/ServerChunkLoader.class"
    'zombie/network/ServerChunkLoader$GetSquare.class'
    'zombie/network/ServerChunkLoader$LoaderThread.class'
    'zombie/network/ServerChunkLoader$QuitThreadTask.class'
    'zombie/network/ServerChunkLoader$RecalcAllThread.class'
    'zombie/network/ServerChunkLoader$SaveChunkThread.class'
    'zombie/network/ServerChunkLoader$SaveGameTimeTask.class'
    'zombie/network/ServerChunkLoader$SaveLoadedTask.class'
    'zombie/network/ServerChunkLoader$SaveTask.class'
    'zombie/network/ServerChunkLoader$SaveUnloadedTask.class'
    "zombie/characters/animals/IsoAnimal.class"
    "zombie/characters/animals/AnimalPopulationManager.class"
    "zombie/characters/animals/AnimalChunk.class"
    "zombie/characters/animals/AnimalZones.class"
    "zombie/characters/animals/VirtualAnimal.class"
    "zombie/characters/animals/VirtualAnimalState.class"
    'zombie/characters/animals/VirtualAnimalState$StateEat.class'
    'zombie/characters/animals/VirtualAnimalState$StateFollow.class'
    'zombie/characters/animals/VirtualAnimalState$StateMoveFromEat.class'
    'zombie/characters/animals/VirtualAnimalState$StateMoveFromSleep.class'
    'zombie/characters/animals/VirtualAnimalState$StateMoveToEat.class'
    'zombie/characters/animals/VirtualAnimalState$StateMoveToSleep.class'
    'zombie/characters/animals/VirtualAnimalState$StateSleep.class'
    "zombie/characters/IsoGameCharacter.class"
    "zombie/characters/action/ActionStateContainer.class"
    "zombie/characters/IsoPlayer.class"
    "zombie/characters/IsoPlayer\$LOSRecord.class"
    "zombie/network/ServerLOS.class"
    'zombie/network/ServerLOS$LOSThread.class'
    'zombie/network/ServerLOS$PlayerData.class'
    'zombie/network/ServerLOS$ServerLighting.class'
    'zombie/network/ServerLOS$UpdateStatus.class'
)

LEGACY_CLASSES=(
    "zombie/Lua/LuaManager.class"
    'zombie/Lua/LuaManager$QueuedLuaCall.class'
)

echo ""
echo "=== ApocBR All-in-One Patch Suite (Build 42.19) ==="
echo "=== $PATCH_NAME ==="
echo ""

# --- Handle --revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting ALL ApocBR patches..."
    reverted=false
    for class_file in "${CLASSES[@]}" "${LEGACY_CLASSES[@]}"; do
        target="$DEPLOY_BASE/$class_file"
        if [[ -f "$target" ]]; then
            rm -f "$target"
            echo "    Removed: $class_file"
            reverted=true
        fi
    done
    if [[ "$reverted" == "true" ]]; then
        echo ""
        echo "=== All patches reverted. JAR originals restored on next server start. ==="
    else
        echo "    No patch files found to remove."
    fi
    echo ""
    echo "Included patches:"
    echo "  - Zombie NoCull Fix"
    echo "  - Pathfind Safety (Stale-Chunk Guard)"
    echo "  - NullCraft Fix (CompressIdenticalItems null guard)"
    echo "  - Async Save + Server Telemetry"
    echo ""
    exit 0
fi

# --- Validate inputs ---
if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: projectzomboid.jar not found at $JAR_FILE" >&2
    echo "       Set --pz-dir to your Project Zomboid server directory." >&2
    exit 1
fi

for src in "${SOURCES[@]}"; do
    if [[ ! -f "$src" ]]; then
        echo "ERROR: Required patched source not found: $src" >&2
        exit 1
    fi
done

# --- Find javac ---
JAVAC=""
if command -v javac &>/dev/null; then
    ver=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -1 || echo 0)
    if (( ver >= REQUIRED_MAJOR )); then
        JAVAC=$(command -v javac)
    fi
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-${REQUIRED_MAJOR}-openjdk-amd64/bin/javac"
fi
if [[ ! -x "$JAVAC" ]]; then
    echo "ERROR: javac >= $REQUIRED_MAJOR not found." >&2
    echo "       Install: apt install openjdk-${REQUIRED_MAJOR}-jdk-headless" >&2
    exit 1
fi

echo "[*] PZ dir:  $PZ_DIR"
echo "[*] JAR:     $JAR_FILE"
echo "[*] Deploy:  $DEPLOY_BASE"
echo "[*] javac:   $JAVAC ($("$JAVAC" -version 2>&1 | head -1))"
echo ""

# --- Compile ---
mkdir -p "$OUTPUT_DIR"
echo "[*] Compiling all patched sources together ($(echo ${SOURCES[@]} | wc -w) source files)..."
JAVAC_LOG="$WORK_DIR/javac.log"
if ! "$JAVAC" --release 25 \
    -Xlint:none \
    -implicit:none \
    -cp "$JAR_FILE" \
    -sourcepath "$SRC_ROOT" \
    -d "$OUTPUT_DIR" \
    -encoding UTF-8 \
    "${SOURCES[@]}" >"$JAVAC_LOG" 2>&1; then
    cat "$JAVAC_LOG" >&2
    exit 1
fi
grep -Ev "^(Note:|Recompile with)" "$JAVAC_LOG" || true
echo "    Compiled successfully."

# --- Deploy ---
echo ""
if [[ "$DRY_RUN" == "true" ]]; then
    echo "[*] DRY RUN: Would deploy combined classes to $DEPLOY_BASE"
    for class_file in "${CLASSES[@]}"; do
        if [[ -f "$OUTPUT_DIR/$class_file" ]]; then
            echo "    $class_file"
        fi
    done
    echo ""
    echo "=== Dry run complete. No files changed. ==="
else
    echo "[*] Deploying..."
    mkdir -p "$DEPLOY_ZOMBIE" "$DEPLOY_POPMAN" "$DEPLOY_NET" "$DEPLOY_GAMESTATES" "$DEPLOY_ISO" \
             "$DEPLOY_VEHICLES" "$DEPLOY_PATHFIND" "$DEPLOY_INVENTORY" "$DEPLOY_CHARACTERS_ANIMALS" "$BACKUP_DIR"

    for class_file in "${LEGACY_CLASSES[@]}"; do
        stale="$DEPLOY_BASE/$class_file"
        if [[ -f "$stale" ]]; then
            rm -f "$stale"
            echo "    Removed stale override: $class_file"
        fi
    done

    ts=$(date +%Y%m%d_%H%M%S)
    deployed=0
    for class_file in "${CLASSES[@]}"; do
        compiled="$OUTPUT_DIR/$class_file"
        [[ -f "$compiled" ]] || continue
        target="$DEPLOY_BASE/$class_file"

        # Class paths include nested Java packages. Create the exact destination
        # directory so newly added overrides do not depend on a previous deployment.
        mkdir -p "$(dirname "$target")"

        # Backup existing override
        if [[ -f "$target" ]]; then
            safe_name="${class_file//\//_}"
            cp "$target" "$BACKUP_DIR/${safe_name}.prev_$ts" 2>/dev/null || true
        fi

        cp "$compiled" "$target"
        echo "    Deployed: $class_file"
        deployed=$((deployed + 1))
    done
    echo "    Deployed $deployed class files."
fi

echo ""
echo "=== Done ==="
echo ""
echo "Patch deployed: $PATCH_NAME"
echo ""
echo "Included patches:"
echo "  1. Zombie NoCull Fix"
echo "     - Removes ZombieCountOptimiser.deleteZombies() from postupdate()"
echo "  2. Pathfind Safety (Stale-Chunk Guard)"
echo "     - Prevents SIGSEGV in libPZPathFind64.so from stale ChunkUpdateTasks"
echo "  3. NullCraft Fix"
echo "     - Null guard in CompressIdenticalItems.save() prevents chunk corruption"
echo "  4. Async Save + Server Telemetry"
echo "     - Async background save, guarded IsoWorld parallelism, vehicle optimizations"
echo ""
echo "How it works:"
echo "  PZ classpath has 'java/.' before 'java/projectzomboid.jar', so loose"
echo "  .class files take precedence over the ones inside the JAR."
echo ""
echo "Config (AsyncSaveTelemetry):"
echo "  -Dapocbr.telemetry.enabled=true -Dapocbr.telemetry.intervalMs=30000"
echo "  -Dapocbr.parallel.isoWorldSafe=true -Dapocbr.parallel.skipIfBacklogged=true"
echo ""
echo "To revert:"
echo "  ./patchApocalipseBr.sh --revert"
echo ""
