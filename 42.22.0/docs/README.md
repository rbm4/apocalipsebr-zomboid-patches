# Build 42.22.0 documentation

42.22.0 is the current server development target (pre-release). The vanilla
baseline is `../decompiled/zombie/`; replacement sources belong in `../src/zombie/`.
The source tree contains 251 Java files: 50 replacements whose vanilla
baselines are unchanged and 200 ApocalipseBR support classes, copied byte for byte
from 42.21.0, plus the approved GameEntity merge. Another 47 changed replacements
and 3 removed targets await review. GameEntity passed the target using-player
fixture; full-class compilation and runtime verification remain pending.
This directory is under migration and is not a deployable or validated release.

Start with the class-by-class migration report before carrying over any source.
The [42.21.0 documentation](../../42.21.0/docs/README.md) remains the historical
feature and configuration reference; those guides describe the previous build,
not implemented 42.22.0 behavior. Migrate a guide alongside its feature and update
this index when the target implementation has been verified.

## Features

| Date | Document |
| --- | --- |
| 2026-10-27 | [Subsystem ownership, staged sources and individual merge queue](features/2026-10-27-subsystem-port-tracker.md) |

No complete feature has been compiled and runtime-verified for this target yet.

## Reports

| Date | Document |
| --- | --- |
| 2026-10-27 | [Merge review 02/48: EngineEntityManager (awaiting discussion)](reports/2026-10-27-merge-02-engine-entity-manager.md) |
| 2026-10-27 | [Merge review 01/48: GameEntity (applied; target fixture passed)](reports/2026-10-27-merge-01-game-entity.md) |
| 2026-10-27 | [42.21.0 to 42.22.0: every replacement and support class](reports/2026-10-27-port-42-21-to-42-22.md) |

The report links the machine-readable inventory, upstream source changes and old
patch-intent diffs used as evidence.

Current artifacts: [copy receipt and 301-class subsystem ledger](reports/2026-10-27-carryover.json)
and [applied GameEntity diff](reports/2026-10-27-merge-01-game-entity.diff).
Next: [unapplied EngineEntityManager candidate](reports/2026-10-27-merge-02-engine-entity-manager.diff).

## Issues

Migration blockers are tracked in the report until implementation begins.
