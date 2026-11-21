# <Feature name>

Status: proposed / implementing / implemented / runtime validated.
Date: <date>. Scope: <one bounded behavior and explicit exclusions>.

## Source behavior and provenance

- Exact game build, original JAR path and SHA-256:
- Legacy patched source and same-build vanilla reference:
- Relevant callers, fields and dependency closure:
- Legacy intent versus unrelated changes:

## Contract and agent implementation

- Observable behavior and intentional differences from vanilla:
- Original behavior/call order retained, or deliberate skip/replacement:
- Module, target signatures, advice/helpers and validated bytecode anchors:
- Class-loader visibility, target mismatch diagnostics and failure behavior:
- Server/client guards and execution threads:
- State ownership, invalidation, pooling/reuse, unload and shutdown:
- Configuration declared in `AgentConfiguration` and defaults:

## Telemetry

- Metric names, populations, units, start/end boundaries and reset interval:
- Skipped-work counters and internal decisions requiring explicit instrumentation:
- Availability, overhead, bounded cardinality and allocation behavior:
- Differences from legacy metrics and comparison limits:

## Validation and delivery

- Production-code regression tests and transformed original-JAR checks:
- Commands, results and skipped checks:
- Artifact validation:
- Dedicated-server runtime scenarios and measured evidence:
- Deployment status and rollback, including feature-owned persistent effects:
- Remaining gaps and separately scoped dependencies:

Do not mark runtime validation complete based on source inspection or synthetic
timing alone. Remove template prompts from completed feature guides.
