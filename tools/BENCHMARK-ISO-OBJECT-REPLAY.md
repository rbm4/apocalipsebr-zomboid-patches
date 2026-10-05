# IsoObject aggregate replay, 42.21.0

`benchmark_iso_object_replay.py` executes the production ProcessIsoObject methods,
production phase hash, exact-version vanilla IsoObject ID/index getters, production
PZArrayList, production thumpable no-work predicate and production per-class timing
helper in a small Java fixture. No game files are changed or deployed.

Run from the repository root with the repository JDK 25:

```powershell
python tools/benchmark_iso_object_replay.py tools/fixtures/iso_object_seq160.json --repeats 3 --output tools/reports/iso_object_seq160_spin.json
python tools/benchmark_iso_object_replay.py tools/fixtures/iso_object_seq160.json --repeats 3 --wait none --output tools/reports/iso_object_seq160_no_wait.json
```

The input can also be a single schema-3 telemetry JSON snapshot. `--save-fixture`
saves only the required aggregate measurements. `--square-sizes 4,32,128` selects
synthetic square occupancies. These are sensitivity cases, not observed occupancies.

## Workload and assertions

The supplied seq160 fixture reproduces 294 processing passes, 1,510,461 registered
entry inspections, 75,364 update calls (including 25,174 every-tick generator/trap
calls) and 98,333 thumpable no-work skips. Calls are apportioned across frames and
families from the aggregate counts; actual object histories are unavailable.

Telemetry has no update latency median. Mean latency for each family is computed
as its summed body milliseconds divided by its **attempt counter**, not phase
calls (which count batches). Each fake update waits a seeded uniform 80%-120% of
that family mean. The spin implementation uses nanoTime and onSpinWait because
most durations are too short for sleep/park to model faithfully. Spin overhead
and OS scheduling can extend actual elapsed time beyond the requested duration.
`--wait none` preserves the same calls and planned durations but performs no wait.

The fixture checks total and per-family update counts, original full-scan checks,
skip counts, and identical ordered-update hashes and requested wait totals across
the three variants. It deliberately has no timing-based pass/fail thresholds.

Variants:

- **current:** production scan and entity-ID-dependent phase lookup.
- **preassigned-phase:** same production scan, with phase lookup replaced by a
  fixture field initialized during registration. No ID getter runs in scheduling.
- **due-list:** same preassigned-phase path over only the entries selected for that
  synthetic frame, preserving their original relative order. This includes the
  no-work thumpables so the skip predicate remains exercised.

Both alternatives are experimental fixture variants, not deployed implementations.
They assume fixed phases for each fixture lifetime. They do not prove compatibility
with changing IDs, square edits, callback mutation or a new live scheduling policy.

## Recorded seq160 assessment, 2026-10-05

The production window spans 30.004 seconds with 33 players, 335 loaded cells,
1,779 active zombies, 314 animals, 4,655 giblets and 914 vehicles at its endpoint.
It completes 294 ticks (9.799/s), averaging 72.499 ms with a 251.847 ms maximum.
51 ticks exceed 100 ms (17.35%); no telemetry or normal-queue packet drops occur.

Simulation costs 15,474.780 ms/window (52.635 ms/tick), including a 200.340 ms
maximum. Player update chains cost 4,021.513 ms (13.679 ms/tick), vehicle chains
2,391.048 ms (8.133 ms/tick), inline animation 1,293.545 ms (4.400 ms/tick), and
IsoObjects 1,220.214 ms (4.150 ms/tick). Inclusive scopes overlap.

IsoObject bodies total 218.954 ms; the parent residual is 1,001.260 ms, or 82.06%
outside bodies. The replay uses those new body totals, not the older 106.189 ms.
Giblets have a large endpoint population but their measured update+postupdate
totals are only 288.569 ms. Concurrent ZGC cycles total 11,437 ms; reported pause
time is zero milliseconds at collector resolution, not proof of zero pauses.

## Synthetic results

Six occupancy/exposure cases, three variants and three rotated repetitions produce
54 samples in each wait mode. The table shows medians of elapsed milliseconds
across the three repetitions with randomized spin waits:

| Square occupancy | Backing array exposed | Current | Preassigned phase | Due list |
| --- | --- | ---: | ---: | ---: |
| 4 | No | 312.707 | 255.755 | 234.712 |
| 4 | Yes | 327.149 | 266.917 | 242.476 |
| 32 | No | 351.705 | 252.746 | 234.471 |
| 32 | Yes | 346.560 | 252.256 | 235.316 |
| 128 | No | 348.538 | 251.054 | 235.831 |
| 128 | Yes | 412.854 | 249.344 | 235.473 |

Every variant invokes exactly 75,364 updates and preserves their order. The
current variant executes 2,970,574 object-index getter calls in this synthetic
trace; alternatives execute zero. No ID changes occur during measured replay.
The due list inspects 173,697 entries, an 88.50% reduction from 1,510,461.

For the 128-entry exposed-array case, median elapsed time outside fake update
bodies is 186.698 / 22.741 / 6.897 ms respectively. The no-wait run gives
175.518 / 20.046 / 6.790 ms. This isolates a real algorithm difference in the
fixture, but does not recreate the production 1,001.260 ms residual.

## Limits and interpretation

The later registry prerequisite patch adds a server getter and mutation-boundary
repair. This replay deliberately retains the vanilla getter used by seq160 as its
reference path, with registry notifications stubbed because no entities are
registered in the replay. Its "current" label describes that reference, not the
latest registry patch, and it does not measure the new boundary/fallback costs.

Preparation, classification, due-list construction and initial position-cache
builds are outside measured time. No pending removals, ID changes, network registry
mutations, Lua work, engine callbacks, live-world contention or actual production
allocation pattern are replayed. Registry reconciliation is a counted stub; a
minimal map also stands in for telemetry aggregation. Family update methods are
wait stubs and therefore neither gameplay tests nor realistic CPU work.

The assumed frame modulus is the patch default 10, with synthetic frame start zero.
The same-frame aggregate counts are exact; identities, registration/removal times,
square topology and call durations are synthetic. Absolute milliseconds and
percent speedups cannot be projected onto the server. The small sample medians
summarize this machine/run, not a stable performance distribution.

The evidence favors separating scheduling identity from mutable network identity,
then eliminating not-due discovery. Network-ID reconciliation still has a purpose
for networking/entity registration; the experiment removes its need **in the
scheduler**, not from the engine. A production implementation must deliberately
choose phase-change behavior and preserve activation, removal and callback order.
