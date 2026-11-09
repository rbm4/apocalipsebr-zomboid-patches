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

No target feature guides yet. Consult the [42.22.1 index](../../42.22.1/docs/README.md)
for current implementation contracts and historical verification.

## Reports

- 2026-11-08 — [42.22.1 to 42.22.2: initial compatibility and porting report](reports/2026-11-08-port-42-22-1-to-42-22-2.md)
- 2026-11-08 — [Applied source port and target build](reports/2026-11-08-port-execution.md)
- 2026-11-08 — [Copy receipt](reports/2026-11-08-port-copy-receipt.json) and [final verification/build receipt](reports/2026-11-08-port-build-receipt.json)

## Issues

No target issue documents yet. Inherited limitations are identified in the report.
