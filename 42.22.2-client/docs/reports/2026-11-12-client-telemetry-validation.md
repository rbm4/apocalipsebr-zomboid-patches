# Client telemetry baseline and validation

2026-11-12. Owning guide:
[client main-thread telemetry](../features/2026-11-12-client-main-thread-telemetry.md).

## Baseline

The user-created 42.22.2-client directory was empty. Selected vanilla references
were copied byte-for-byte from 42.22.2/decompiled: GameWindow.java, MainThread.java
and gameStates/IngameState.java. They are not copied from patched server src.
The installed original projectzomboid.jar SHA-256 is
`3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`, matching the
existing [42.22.2 build receipt](../../../42.22.2/docs/reports/2026-11-08-port-build-receipt.json).
That baseline identifies build 42.22.2, revision 3d39148846. The client directory
retains only references needed for this scaffold, not a full decompilation tree.

tools/Copy-PZPatchBaseline.ps1 copied GameWindow into src, verified its SHA-256
`51fa2e8e5cf9b90df018498d5ac92e16ae5853cf2096bdbc893c5a3cc14da343`, and created
local baseline-only commit `3d4543a`. No push. Telemetry changes remain separate
from that baseline commit. The original JAR and decompiled sources were not edited.

## Verification

- Build-ClientTelemetry.ps1: exact-JAR compilation with JDK 25, release 25;
  two sources / ten class files. Build-only output, no deployment.
- Production-helper fixture: owner-thread enforcement, connected/menu boundary,
  partial-window flushing, reconnect cadence reset, bounded worst-frame samples,
  histogram counts, valid NDJSON, parent-directory creation, clean writer drain
  and disabled-by-default behavior.
- Review GameWindow versus its own baseline: timing wrappers and shutdown hook;
  original calls retain their order and exception handlers. Phase finalizers run
  when the wrapped vanilla operation throws.
- Local documentation links and git diff whitespace checks.
- javap -public: compiled GameWindow API matches the original JAR. All three
  retained vanilla reference files match their exact-version source hashes.

Fixtures exercise the actual telemetry helper but do not launch the game. Main
thread callback/connection gating was traced in the exact source; live launch,
remote dedicated-server connection, large hordes, render contention, disconnect,
save/exit, slow/unwritable storage and telemetry overhead remain runtime checks.
No stutter cause or performance improvement has been established.
