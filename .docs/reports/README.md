# Reports and historical evidence

New feature audits, validation records and measurements belong in this directory
and should be linked here and from the owning feature guide. Use dated filenames
such as `YYYY-MM-DD-<feature>-validation.md`.

## Historical references

Pre-port audit: [2026-11-21 bot engine vanilla dependency map](2026-11-21-npc-dependency-map.md), with a hashed source/API inventory, original-JAR compile probe and required native hook contracts. Report only; no NPC port implemented.

Integration infrastructure: [2026-11-21 real-server integration validation](2026-11-21-server-integration.md).

[Vehicle simulation and streaming pressure](2026-11-22-vehicle-simulation-pressure.md) traces the original dedicated-server vehicle load, update and relay boundaries and records the pressure fixture.

[Chunk integration and detachment](2026-11-22-chunk-lifecycle.md) measures the original-JAR hot paths and traces the world-owner and worker boundaries.

[Cell unload scan optimization and thread ownership](2026-11-23-unload-scan-and-async-review.md) records the scan ports and the async safety review.

Current work: [2026-11-21 PostgreSQL and NPC vanilla compatibility audit](2026-11-21-postgresql-vanilla-audit.md).

Implementation follow-up: [2026-11-21 PostgreSQL module and account hook registration](2026-11-21-postgresql-hook-registration.md).

- [Legacy optimization patches: current-agent migration status](2026-11-21-legacy-optimization-migration-status.md): separates historical optimization implementations and remaining candidates from the root agent's current implementation.
- [2026-11-18 migration baseline and impact inventory](../../42.22.2/docs/reports/2026-11-18-byte-buddy-migration-baseline/2026-11-18-byte-buddy-migration-baseline.md): captured 375 implementation sources, including 109 modified vanilla counterparts and 266 custom sources. This is a historical source inventory, not completed migration or current runtime proof.
- [Per-source inventory](../../42.22.2/docs/reports/2026-11-18-byte-buddy-migration-baseline/2026-11-18-class-inventory.md): navigation into captured diffs; inspect current exact sources before implementing.
- [Historical telemetry basics guide](../../42.22.2/docs/features/2026-11-18-byte-buddy-server-telemetry-basics.md): records the first telemetry tranche and its runtime gaps. Current active status belongs in the [feature catalog](../features/README.md).

The baseline verifier was run on 2026-11-21 and stopped at
`Context file drift: pom.xml`. This does not establish that the captured legacy
source deltas changed, nor does it verify all current context. Keep the dated
snapshot unchanged; record fresh per-feature provenance and findings here.
