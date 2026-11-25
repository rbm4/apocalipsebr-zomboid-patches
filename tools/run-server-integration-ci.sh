#!/usr/bin/env bash
set -euo pipefail

: "${PZ_HOME:?Path to the original dedicated server installation}"
: "${PZ_GAME_JAR:?Path to its original projectzomboid.jar}"
: "${PZ_SERVER_HEAP:=6g}"

common=(
    --batch-mode --no-transfer-progress -Dstyle.color=never -Pserver-integration
    "-Dpz.game.jar=$PZ_GAME_JAR" "-Dpz.it.home=$PZ_HOME" "-Dpz.it.heap=$PZ_SERVER_HEAP"
    -Dpz.it.streaming=true -Dpz.it.serverLogs=false -Dpz.it.color=false
    -Dpz.it.experimentalWorldSimulation=false
)
artifact=

run_stage() {
    local stage=$1 status=0
    shift
    local output="target/ci/$stage"
    mkdir -p "$output"
    printf '\n=== %s ===\n' "$stage"
    mvn "${common[@]}" "-Dpz.it.reportsDirectory=$PWD/$output/failsafe-reports" "$@" |
        tee "$output/maven.log" || status=$?
    if (( status != 0 )); then
        return "$status"
    fi
    artifact="$(sed -n 's/^\[Server-IT\] Artifacts: //p' "$output/maven.log" | tail -n 1)"
    [[ -n "$artifact" && -d "$artifact" ]] || { echo "Missing run directory for $stage" >&2; return 1; }
}

integration=(failsafe:integration-test failsafe:verify)
run_stage players30-baseline verify -Dpz.it.streamingProfile=players30 -Dpz.it.verifyStartupHooks=true
baseline=$artifact
common+=(-Dpz.it.verifyStartupHooks=false)
players30=(-Dpz.it.streamingProfile=players30 "-Dpz.it.streamingBaseline=$baseline")
run_stage cell-border "${integration[@]}" "${players30[@]}" -Dapocbr.optimization.cellBorder.enabled=true
run_stage entity-removal-off "${integration[@]}" "${players30[@]}" -Dapocbr.optimization.entityRemoval.enabled=false
run_stage los-off "${integration[@]}" "${players30[@]}" -Dapocbr.optimization.serverLos.enabled=false
run_stage moving-object-attribution "${integration[@]}" -Dpz.it.streamingProfile=players30 -Dpz.it.movingObjects=true
run_stage parked-vehicle-cadence "${integration[@]}" -Dpz.it.streamingProfile=vehicles72 -Dpz.it.parkedVehicleComparison=true
run_stage chunk-lifecycle "${integration[@]}" -Dpz.it.streamingProfile=vehicles72 -Dpz.it.chunkLifecycleComparison=true
run_stage hook-rollback "${integration[@]}" -Dpz.it.streamingProfile=smoke -Dpz.it.chunkLifecycle=true \
    -Dapocbr.optimization.parkedVehicle.enabled=false -Dapocbr.optimization.chunkContainers.enabled=false \
    -Dapocbr.optimization.chunkAdjacency.enabled=false
