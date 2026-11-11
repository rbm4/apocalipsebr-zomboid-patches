# Build 42.22.2 documentation

Source port applied on 2026-11-08 from the current 42.22.1 working tree, including
its uncommitted NPC work. All **339 Java sources**, dependencies, runtime helpers
and Windows/Linux build scripts are present. GameServer includes the two upstream
revision-string updates. The actual target Windows build passed against the
installed 42.22.2 JAR: **339 sources / 984 classes**. Classes and compiler logs are
retained under `../artifacts/build-2026-11-08/` (ignored). Nothing was deployed;
runtime validation remains pending. Persistence/event labels remain inherited
42.22.0 values, as explained in the execution report.

## Features

- 2026-11-11 — [Generic server Lua API bridge: character balance and bounded asynchronous callbacks](features/2026-11-11-generic-api-bridge.md)

- 2026-11-11 — [NPC seating execution: modular mechanics and stock-client presentation](features/2026-11-11-npc-seating-execution.md)

- 2026-11-11 ? [Managed native generators](features/2026-11-11-fixed-power-regions.md)

- 2026-11-11 — [NPC native furniture perception: current-room discovery and fair capture](features/2026-11-11-npc-furniture-native-perception.md)
- 2026-11-11 — [NPC furniture foundation: observations and shared interaction leases](features/2026-11-11-npc-furniture-foundation.md)

Consult the [42.22.1 index](../../42.22.1/docs/README.md) for inherited
implementation contracts and historical verification.

## Reports

- 2026-11-11 - [Fixed power: stock-client chunk-streaming audit](reports/2026-11-11-fixed-power-stock-client-streaming-audit.md)

- 2026-11-10 — [NPC furniture affordances, rest policy and seating implementation plan](reports/2026-11-10-npc-furniture-rest-implementation-plan.md)

- 2026-11-08 — [42.22.1 to 42.22.2: initial compatibility and porting report](reports/2026-11-08-port-42-22-1-to-42-22-2.md)
- 2026-11-08 — [Applied source port and target build](reports/2026-11-08-port-execution.md)
- 2026-11-08 — [Copy receipt](reports/2026-11-08-port-copy-receipt.json) and [final verification/build receipt](reports/2026-11-08-port-build-receipt.json)

## Issues

No target issue documents yet. Inherited limitations are identified in the report.
