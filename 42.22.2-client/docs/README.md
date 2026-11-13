# Build 42.22.2 client documentation

## Features

- 2026-11-13: [Async building geometry and bulk owner publication (schema 8)](features/2026-11-13-async-building-reconciliation.md).

- 2026-11-13: [Client network update and packet-stage telemetry (schema 7)](features/2026-11-13-client-network-telemetry.md).

- 2026-11-12: [Deferred textures, asynchronous translucent ordering and building lookups](features/2026-11-12-client-deferred-textures-and-async-translucency.md).

- 2026-11-12: [Texture workers, region/cell probes and slow OnTick callbacks](features/2026-11-12-texture-workers-and-client-probes.md).

- 2026-11-12: [Client main-thread telemetry scaffold](features/2026-11-12-client-main-thread-telemetry.md).

## Reports

- 2026-11-13: [Schema 8 capture: worker building plans, remaining commit and render waits](reports/2026-11-13-schema-8-capture-analysis.md).

- 2026-11-13: [Translucent work and practical building-reconciliation async lifecycle](reports/2026-11-13-rendering-and-building-async-lifecycle.md).

- 2026-11-13: [Worker boundaries and full client cost review](reports/2026-11-13-client-worker-boundaries-and-full-cost-review.md).

- 2026-11-13: [Schema 7 capture: animal, vehicle and zombie work inside packet parsing](reports/2026-11-13-schema-7-capture-analysis.md).

- 2026-11-13: [Binary wire format, direct codecs and client decoder feasibility](reports/2026-11-13-client-binary-protocol-and-decode-pipeline.md).

- 2026-11-13: [Schema 6 capture: continuity cost and remaining network/building stalls](reports/2026-11-13-schema-6-capture-analysis.md).

- 2026-11-12: [Client network asynchronous-processing assessment](reports/2026-11-12-client-network-async-assessment.md).

- 2026-11-12: [Schema 5 capture: deferred resizing, translucent reuse and remaining stalls](reports/2026-11-12-schema-5-capture-analysis.md).

- 2026-11-12: [Schema 4 capture: texture workers, building reconciliation and translucent rendering](reports/2026-11-12-schema-4-capture-analysis.md).

- 2026-11-12: [Schema 3 capture: confirmed texture preprocessing, regions and cell rendering](reports/2026-11-12-schema-3-capture-analysis.md).

- 2026-11-12: [Schema 3 probes and image worker-offload assessment](reports/2026-11-12-client-probe-schema-3-texture-offload.md).

- 2026-11-12: [Schema 2 capture: scene rendering and image-result bursts](reports/2026-11-12-schema-2-capture-analysis.md).

- 2026-11-12: [Schema 2 probe implementation and validation](reports/2026-11-12-client-probe-schema-2.md).

- 2026-11-12: [Sprite-heavy multiplayer client capture](reports/2026-11-12-sprite-heavy-client-capture.md).

- 2026-11-12: [Baseline provenance and scaffold validation](reports/2026-11-12-client-telemetry-validation.md).

## Issues

- 2026-11-12: [Translucent flicker during asynchronous ordering refresh](issues/2026-11-12-async-translucent-flicker.md).

The sprite-heavy capture identifies coarse phase costs and stalls. Exact causes
and optimization gains remain unverified; see the report's next measurement boundaries.
