# Integrated registry and IsoObject replay, 42.21.0

Run from the repository root:

```powershell
python tools/benchmark_iso_object_integrated.py tools/fixtures/iso_object_seq160.json --repeats 3 --output tools/reports/iso_object_integrated_seq160_spin.json
python tools/test_iso_entity_registry.py
```

The integrated script shares source-building utilities with the isolated registry
test and the original aggregate replay. It compiles the production registry helper,
PZArrayList and class telemetry helper plus extracted production lifecycle,
lookup, object-ID and processing methods with minimal engine stubs. No runtime
scheduler source is changed. Defaults: JDK 25, frame modulus 10, square occupancies
4/32/128, three rotated repetitions, uniform seeded waits at each class's telemetry
mean +/-20%. Telemetry contains no per-update median.

## Integrated workload

Before each processing frame, register one of that frame's processing objects.
A mock player inserts/removes a preceding object, edits a retained backing array,
or changes square coordinates. Packet-style GetEntity lookups assert the current
mapping before explicitly requesting that object's ID. Restore this processing
target's position/coordinates before calling ProcessIsoObject to retain the
original paired timing workload. Unregister it after processing.

A separate four-entity registered neighborhood undergoes insertion/removal,
cyclic list edits, retained-array edits and unregister/re-add. Reorders persist
across frames. All variants execute identical mutation operations and packet
lookups. Measured elapsed time includes mutations, lookup assertions, registration,
unregistration, registry repair, processing and final neighborhood cleanup.

A separate 100-frame functional test mutates the actual processing objects'
squares persistently, verifies packet lookups and asserts the booked processing
phase/cadence remains stable despite changing network identities. This deliberately
tests the proposed stable-phase policy, not equivalence to the old policy which
can change phases with IDs. Every-tick generators remain every-tick and no-work
thumpables remain skipped. It asserts zero processing index reads.

## Reference variants

- **legacy-reference:** full processing traversal with the old repeated-index ID
  getter, augmented with dirty-ID invalidation so it can use the new registry repair.
  It is a comparison of old discovery work, not an unmodified old server.
- **registry-reference:** full traversal with the actual patched server ID getter,
  which computes the index once per call.
- **preassigned-phase:** same processing traversal but fixture-assigned phases;
  no ID/index getter runs in processing.
- **due-list:** same assigned-phase processing over the preselected ordered entries
  for that frame. Due no-work thumpables are retained so their predicate still runs.

All use the real production registry repair. Registry/lookups are not stubbed;
engine/native dependencies and fake update bodies are. Each variant must reproduce
75,364 updates, each family count, 98,333 skips, update order hash and planned wait
sum. Full scans inspect 1,510,461 entries; the due list inspects 173,697. Each measured
sample also performs 2,060 successful packet-style registry assertions, 1,620
entry refreshes and finishes with no registry/engine-membership leaks or collision
errors. Decoupled variants must perform zero processing index reads.

## Recorded results, 2026-10-05

72 timing samples passed, totaling 148,320 packet-style assertions, plus the
100-frame durable-mutation check. The independent registry fixture again passes
70,329 assertions. Times below are medians of three measured repetitions, in ms
for the entire synthetic 294-frame workload:

| Occupancy | Raw array exposed | First static reference | Integrated legacy reference | Integrated patched getter | Assigned phase | Due list | Paired legacy minus due |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | No | 312.707 | 316.235 | 301.140 | 264.526 | 239.102 | 77.133 |
| 4 | Yes | 327.149 | 331.305 | 320.519 | 257.836 | 239.506 | 91.799 |
| 32 | No | 351.705 | 375.129 | 358.098 | 255.985 | 239.327 | 135.802 |
| 32 | Yes | 346.560 | 350.240 | 388.026 | 253.278 | 236.629 | 113.612 |
| 128 | No | 348.538 | 370.005 | 346.180 | 255.633 | 238.417 | 131.589 |
| 128 | Yes | 412.854 | 423.114 | 547.157 | 252.609 | 236.930 | 186.184 |

The 128/exposed case is 175.924 ms lower than the archived first static reference,
and 186.184 ms lower than the same-run integrated legacy reference. Only the latter
includes identical mutation workloads in both sides. The first static measurements
come from a different run with no mutations and are historical context.

The patched-getter reference has anomalously higher timings in some cases; the
fixture does not establish their cause. These small-sample results are sensitive
to JIT/GC/OS effects and must not be treated as stable speedup guarantees. Full raw
samples preserve those results rather than discarding them.

The legacy reference performs 2,970,574 processing index reads; the patched getter
reference performs 1,485,287; the assigned-phase and due-list variants perform zero.
The due list reduces entry visits by 88.50% while preserving the paired workload.

## Scope and limits

Initial trace construction, classification, position-cache warmup and preselected
due-list construction are outside measured time. There is no implemented live due
index or its maintenance cost. Timing and functional tests separate workload
equivalence from the intentionally changed stable-phase policy. Production mutation
histories and square occupancy are unavailable; this is a controlled synthetic
replay, not a capture replay of actual player actions.

Only four persistent registered mock objects and one per-frame processing target
exercise the registry. This does not measure raw-array validation against the full
production registered-entity population. Real update callbacks, native engine work,
packet decoding and meta persistence are not executed. Absolute fixture milliseconds
cannot be projected onto the server's 1,001.260 ms residual or tick latency.
