# Server cell perimeter lookups

The `cell-border-lookup` module ports the same-cell lookup portion of the
legacy 42.22.2 perimeter optimization to the original JAR. It uses the original
`ServerMap.ServerCell.RecalcAll2()` entry point. The hook is off by default. Enable it with
`-Dapocbr.optimization.cellBorder.enabled=true`.

The transformation replaces the four square lookup sites in the perimeter
recalculation loop. The eight lookups in the preceding square-creation pass,
all native recalculation callbacks, and the order and number of corner visits
are preserved. The helper checks the world bounds, loaded state and canonical
map cell. Unusual coordinates, unpublished cells and alternate map receivers
use the vanilla lookup. The helper reads the live chunk and square for each
lookup and owns no state that would need to be invalidated at unload.

The original-JAR test compares the method invocation sequence and checks that
disabling the module restores the original transformation path. The dedicated
server streaming probe observes the production hook at all four sites and
compares its returned square identities with vanilla during the generation
cycle, including missing squares. The warm measurements keep the existing
checks for saved-chunk reads, chunk integration, unload, save and release.

Run the [30-position streaming scenario](../validation/server-integration.md#playerless-streaming-measurements)
on the same JAR with the feature flag enabled and disabled. Compare the
`cell.integrate` phase. The probe also records `chunk.safeRead` within
`chunk.readSave` to distinguish file access and read locking from the
rest of the saved-chunk load. These phases overlap.

## Local validation

Both 30-position runs against the original 42.22.2 JAR on Windows passed and
shut down cleanly. The enabled run checked 35,584 live lookups against vanilla,
including 21,890 absent squares. Both runs completed the generation cycle and
five saved-chunk reloads at 45 cells and 2,880 chunks per cycle. A final
packaged smoke run with the hook enabled passed the full Maven suite and
checked the detached-cell, adjacent-cell and invalid-floor fallbacks.

Median warm cell integration was 1,038 ms with the hook enabled and 910 ms
with it disabled. This sequential comparison did not demonstrate a speedup,
so the feature is opt-in. The enabled run used the oracle wrapper to check
the first cycle, with a lightweight wrapper branch during reloads. Treat the
comparison as a whole-phase timing, with contributions from the rest of
cell integration and host variation.

Median `SafeRead` was 22.34 s of 23.07 s in `LoadFromDisk` with the
hook on and 21.95 s of 22.62 s with it off. These timings point to file access
and locking as the next area to profile. They do not separate the cost of
opening, metadata lookup, reading and waiting on locks.

The fixture covers Windows and an isolated dedicated server without
clients. Connected-player behavior and representative deployment remain
outside the recorded validation.
