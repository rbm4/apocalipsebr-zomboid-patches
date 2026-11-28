#!/usr/bin/env bash
set -euo pipefail

: "${PZ_HOME:?Path to the original dedicated server installation}"
: "${PZ_GAME_JAR:?Path to its original projectzomboid.jar}"
: "${PZ_SERVER_HEAP:=6g}"
stage=${1:?Specify the integration scenario}
baseline=target/players30-baseline
startup_hooks=false
scenario=()

if [[ "$stage" == room-registration-ab ]]; then
    status=0
    for arm in room-registration-vanilla room-registration-indexed room-registration-scoped; do
        bash "$0" "$arm" || status=1
    done
    exit "$status"
fi

case "$stage" in
    players30-baseline)
        startup_hooks=true
        scenario=(-Dpz.it.streamingProfile=players30)
        ;;
    cell-border|entity-removal-off|los-off)
        scenario=(-Dpz.it.streamingProfile=players30 "-Dpz.it.streamingBaseline=$PWD/$baseline")
        case "$stage" in
            cell-border) scenario+=(-Dapocbr.optimization.cellBorder.enabled=true) ;;
            entity-removal-off) scenario+=(-Dapocbr.optimization.entityRemoval.enabled=false) ;;
            los-off) scenario+=(-Dapocbr.optimization.serverLos.enabled=false) ;;
        esac
        ;;
    moving-object-attribution)
        scenario=(-Dpz.it.streamingProfile=players30 -Dpz.it.movingObjects=true)
        ;;
    parked-vehicle-cadence)
        scenario=(-Dpz.it.streamingProfile=vehicles72 -Dpz.it.parkedVehicleComparison=true)
        ;;
    chunk-lifecycle)
        scenario=(-Dpz.it.streamingProfile=vehicles72 -Dpz.it.chunkLifecycleComparison=true)
        ;;
    hook-rollback)
        scenario=(-Dpz.it.streamingProfile=smoke -Dpz.it.chunkLifecycle=true
            -Dapocbr.optimization.parkedVehicle.enabled=false -Dapocbr.optimization.chunkContainers.enabled=false
            -Dapocbr.optimization.chunkAdjacency.enabled=false)
        ;;
    room-registration-vanilla|room-registration-indexed|room-registration-scoped)
        indexed=false
        scoped=false
        if [[ "$stage" != room-registration-vanilla ]]; then indexed=true; fi
        if [[ "$stage" == room-registration-scoped ]]; then scoped=true; fi
        scenario=(-Dpz.it.roomRegistration=true "-Dpz.it.heap=$PZ_SERVER_HEAP"
            "-Dapocbr.optimization.roomIdValidation.enabled=$indexed"
            "-Dapocbr.optimization.scopedRoomCommit.enabled=$scoped")
        ;;
    postgres-bootstrap)
        : "${APOCBR_POSTGRES_URL:?PostgreSQL bootstrap requires the isolated test database URL}"
        scenario=(-Dpz.it.useTaskConfiguration=true -Dapocbr.postgres.enabled=true
            -Dapocbr.postgres.serverDatabase.enabled=true -Dapocbr.postgres.players.enabled=false
            -Dapocbr.postgres.dictionary.enabled=false -Dapocbr.postgres.loginHistory.enabled=false
            -Dapocbr.npc.persistence.enabled=false -Dapocbr.restart.enabled=false)
        ;;
    *)
        echo "Unknown integration scenario: $stage" >&2
        exit 2
        ;;
esac

(cd target && sha256sum --check apocbr-agent.jar.sha256)
read -r digest _ < target/apocbr-agent.jar.sha256
case "$stage" in
    cell-border|entity-removal-off|los-off)
        test -f "$baseline/streaming-complete"
        test -s "$baseline/streaming.csv"
        grep -Fxq "agent.sha256=$digest" "$baseline/runtime.properties"
        ;;
esac

if [[ "$stage" != postgres-bootstrap && "$stage" != room-registration-* ]]; then
    scenario+=(-Dpz.it.streaming=true "-Dpz.it.heap=$PZ_SERVER_HEAP")
fi

output="target/ci/$stage"
mkdir -p "$output"
mvn --batch-mode --no-transfer-progress -Dstyle.color=never -Pserver-integration \
    "-Dpz.game.jar=$PZ_GAME_JAR" "-Dpz.it.home=$PZ_HOME" \
    "-Dpz.it.reportsDirectory=$PWD/$output/failsafe-reports" \
    -Dpz.it.serverLogs=false -Dpz.it.color=false -Dpz.it.experimentalWorldSimulation=false \
    "-Dpz.it.verifyStartupHooks=$startup_hooks" "${scenario[@]}" \
    failsafe:integration-test failsafe:verify | tee "$output/maven.log"

artifact="$(sed -n 's/^\[Server-IT\] Artifacts: //p' "$output/maven.log" | tail -n 1)"
[[ -n "$artifact" && -d "$artifact" ]] || { echo "Missing run directory for $stage" >&2; exit 1; }
if ! grep -Fxq "agent.sha256=$digest" "$artifact/runtime.properties"; then
    echo "::error::Scenario $stage did not record the shared agent digest."
    exit 1
fi

if [[ "$stage" == players30-baseline ]]; then
    mkdir -p "$baseline"
    cp "$artifact/runtime.properties" "$artifact/streaming-complete" "$artifact/streaming.csv" "$baseline/"
elif [[ "$stage" == room-registration-* ]]; then
    test -f "$artifact/room-registration-complete"
    test -s "$artifact/room-registration.csv"
    cp "$artifact/runtime.properties" "$artifact/room-registration.csv" "$output/"
fi
