# Room ID validation during building edits

The original Build 42.22.2 editor commits a small room edit through the same path as a large edit. `BuildingRoomsEditor.applyChanges(boolean)` runs three global ID checks and one global rebuild. The validator walks the meta cells reachable from world lot headers and searches an array-backed room list once per building room and outside-room definition. The rebuild assigns IDs from list positions and re-keys live room and building maps.

## Hook and ownership

`ApocBrAgent.premain` enters `AgentRuntime`. It installs one production Byte Buddy transformation pipeline from `AgentModules.all()`, including `RoomIdValidationModule`. The module targets the original `BuildingRoomsEditor.checkBuildingAndRoomIDs(IsoMetaCell)` method. It expects both original `ArrayList.indexOf(Object)` call sites and replaces each with a lookup helper.

The helper decodes the room index and checks that the slot contains the same room object. If the bound or identity check fails, it uses the original list search so missing rooms and stale indices reach the existing diagnostics. Positional checks and the separate room-list/map validation still run. The fast lookup relies on the normal invariant that a room definition occurs once in the cell list. A repeated instance in a corrupt list remains detectable by the original positional check. No index is stored or built by the agent, and all room updates run on the original thread.

The independent startup switch is `-Dapocbr.optimization.roomIdValidation.enabled=true`. It defaults off. The module declares the injected helper call through `HookCheck`. No room edits, ID assignments, lighting or spatial invalidations move out of the vanilla call.

## Validation and remaining work

The [dedicated-server scenario](../validation/server-integration.md#room-registration) runs on the original JAR and verifies one-room lookup across the expanded footprint, replacement under the same building meta ID, current room and building map entries, and preservation of a sibling room and its live object after reindexing. Compare all three arms in the `room-registration-ab` job summary. [Project Zomboid server integration](https://github.com/rbm4/apocalipsebr-zomboid-patches/actions/workflows/server-integration.yml) · [Validation report](../reports/2026-11-27-room-registration-validation.md)

## Scoped Lua commits

`-Dapocbr.optimization.scopedRoomCommit.enabled=true` enables a separate hook on the original editor. It defaults off and composes with indexed validation. A server Lua mod can call `ApocBRRoomEdits.tryApply(editor, x, y, z)` with coordinates in the live registered room it is editing. A false return leaves the draft available for the ordinary apply. A true return means the editor call ran once. Commit exceptions propagate to the caller.

The bridge requires the server Lua thread, a valid draft and an anchor whose building is registered in the owner cell's meta-ID map. It scopes one synchronous editor call. The scope starts with the anchor owner and the owners of any pre-edit buildings. It then follows the two native owner lookups in the commit to include removals and draft insertions. Each global validation dispatch checks only the owners known at that point. The final rebuild uses the unchanged per-cell rebuild method for the collected owners. The editor keeps its existing immediate per-cell repairs, geometry updates, adjacent-cell spatial registration, lighting, and cleanup. Loading and direct editor calls retain the global passes, including direct calls nested within a scoped commit.

The [scoped commit A/B report](../reports/2026-11-27-scoped-room-commit-validation.md) covers same-cell growth, growth over a boundary with the original owner unchanged, and an edit with old and new owners. A production BasementDig capture is still needed to measure its Lua refresh and publication costs alongside the Java commit.
