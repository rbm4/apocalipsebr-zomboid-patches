# Applied 42.22.2 source port and target build

Date: **2026-11-08**. Target: **42.22.2 dedicated-server patches**.
Status: **source port applied and compiled; no deployment**.

The user authorized carrying forward current 42.22.1 patched sources and build
scripts and applying the GameServer change identified in the
[initial audit](2026-11-08-port-42-22-1-to-42-22-2.md). That report records the
pre-port investigation; this document records the applied result.

## Applied changes

The [copy script](../../../tools/copy_42_22_2_port.py) copied **351 files** from
the 42.22.1 working tree, including its uncommitted NPC work. Every copied file
was verified by SHA-256 before target edits. It copies `src/`, `lib/`, `tools/`
and both patch entry points, excluding caches. It preflights destinations and
refuses to overwrite differing files. It is a baseline-copy tool: rerunning it
after target-specific edits intentionally fails rather than resetting the port.

```powershell
py -3 tools/copy_42_22_2_port.py
```

No vanilla, historical documentation, old build output or deployment backups were
copied. All **3,107 target vanilla files** were verified unchanged. The target
index links current 42.22.1 implementation guides rather than duplicating history.

After the copy, exactly four carried files changed:

| File | Applied delta |
| --- | --- |
| `src/zombie/network/GameServer.java` | Two revision literals: `71e8410c42` to `3d39148846`. All custom hooks and integrity startup remain. |
| `patchApocalipseBr.ps1` | Target description/build label becomes 42.22.2. BuildOnly, DryRun, deployment and revert logic retained. |
| `patchApocalipseBr.sh` | Target description/build label becomes 42.22.2. Linux deployment/revert and helper integration retained. |
| `tools/retired-class-families.json` | Target label becomes 42.22.2; cleanup remains explicitly unimplemented/unverified. |

The other **338 Java sources** are byte-identical to the current 42.22.1 sources.
Both dependency JARs and all other runtime/build tools are unchanged, including
the unsigned-runtime helpers. Repository guidance now selects 42.22.2 as the
current default source target. Existing 42.22.1 user edits remain untouched.

## Actual target build

Run from the repository root:

```powershell
.\42.22.2\patchApocalipseBr.ps1 -PZDir 'Z:\SteamLibrary\steamapps\common\ProjectZomboid' -BuildOnly -ArtifactDir 'Z:\git\Github\apocalipsebr-zomboid-patches\42.22.2\artifacts\build-2026-11-08'
```

Result: **exit 0; javac 25.0.4; 339 sources; 984 generated class files**.
Compilation uses Java release 25 and the installed original target JAR, revision
`3d39148846`, world format `251`. Original JAR SHA-256:
`3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`.
Only standard deprecated/unchecked compiler notes were emitted.

The ignored [artifact directory](../../artifacts/build-2026-11-08/) retains
`classes/`, `javac.out.log` and `javac.err.log`. The committed build receipt
records source/output hashes. No release ZIP was created. Windows build-only
does not configure launchers or a derived runtime JAR and exits before deployment.

PowerShell syntax checks cover the target entry point and copied helpers. The
Linux entry point is carried unchanged apart from its label; Linux runtime
execution is not established by the Windows Java compilation. See the final
receipt for the Bash syntax check result.

## Preserved limits

Persistence and event labels remain **42.22.0**, exactly as in the source tree.
No storage/namespace policy was changed. Exact 42.22.2 provenance still needs a
coordinated backend migration and engine changes, as detailed in the audit.
The inherited five-family stale-class cleanup remains tracked but unimplemented.

No game installation, launcher JSON, server process or live database was changed;
no commit was created. Dedicated-server startup, native launcher/unsigned-runtime
refresh, gameplay, persistence, NPC behavior and shutdown still require runtime
validation. Existing concurrency/reconstruction limitations remain in scope.

## Receipts

- [Copy receipt](2026-11-08-port-copy-receipt.json): original/copy hashes for all 351 files, before the four target edits.
- [Final build receipt](2026-11-08-port-build-receipt.json): final source/output hashes, differences and verification results.
- [Initial audit evidence](2026-11-08-port-42-22-1-to-42-22-2/): full upstream diffs and earlier temporary candidate build.
