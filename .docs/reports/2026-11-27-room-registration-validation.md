# Room registration on the original dedicated server

## Original behavior and first change

Build 42.22.2 runs three calls to `checkBuildingAndRoomIDs()` inside `BuildingRoomsEditor.applyChanges(false)`. Each traverses world lot headers. The per-cell method searches `roomList` for each normal room and outside-room definition. It repeats this work when an editor only grows one room by a tile. A replacement under the same building meta ID also removes the old definition and reindexes its cell. The final global pass rebuilds IDs and re-keys the live room and building maps.

The initial [agent hook](../features/room-id-validation.md) makes valid room lookups constant-time while retaining the existing search when the encoded index does not point to the room. The hook replaces the two search invocations in the original method and leaves the remaining Java flow intact. A single opt-in property controls installation through the production module registry.

## Reproduction

The [dedicated-server scenario](../validation/server-integration.md#room-registration) uses the original editor in an isolated world. It checks the main room's one-tile growth and same-meta-ID replacement, the spatial and ID maps, the sibling room across ID rebuild, and cleanup. The test-only timers split editor elapsed time into global checks, final global rebuild, removal, insertion and the editor square-update tail. The remainder includes work such as local ID bookkeeping and other editor notifications. Phase times are elapsed nanoseconds inside the apply total.

The original Build `42.22.2` bytecode contains both expected array-list lookups. The workflow verifies the original JAR and packaged agent before running the scenario.

## Where to compare results

Open [Project Zomboid server integration](https://github.com/rbm4/apocalipsebr-zomboid-patches/actions/workflows/server-integration.yml) and find `room-registration-ab` under the independent scenarios. In its **Summary**, compare **Vanilla** with **Indexed lookup** for interior and cross-boundary growth. Both use global editor commits. The distinction is the two room-list lookups. The job's diagnostics artifact contains each arm's `room-registration.csv`, which separates the three global ID checks and final global rebuild from the full editor time. See the [workflow guide](../validation/server-integration.md#room-registration) for artifact names and coverage.

## Follow-up

The earlier [BasementDig adoption sample](https://github.com/ApocalipseBr/ApocalipseBR-LoadGridSquare-Optimizations/blob/master/.docs/reports/2026-11-27-basementdig-room-registration-stutter.md) measured 228 ms for registration, including the native apply and Lua footprint work. The native fixture runs without BasementDig, connected clients or Lua square repair. Its timings isolate the editor path on the workflow's reference world.

BasementDig resets the editor before building its replacement, so the pre-edit list starts empty. The first two global checks surround that empty removal loop and revisit the same metadata. The third follows collision removal and reindexing of the affected cell. The [scoped commit follow-up](2026-11-27-scoped-room-commit-validation.md) limits these passes to affected owner cells for explicit Lua edits. Compare **Indexed lookup** with **Indexed + scoped** in the same workflow job to measure that change.
