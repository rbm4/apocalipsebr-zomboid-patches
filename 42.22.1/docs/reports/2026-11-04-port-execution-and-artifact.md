# 42.22.1 port execution and retained build artifact

Date: 2026-11-04. Dedicated-server source target: **42.22.1**.

The user authorized the reviewed GameServer merge, copying the 42.22.0 files,
separate Git commits and an actual compilation producing a retained artifact.
The [source audit](2026-11-04-port-42-22-0-to-42-22-1.md) owns the vanilla diff
and compatibility assessment.

## Applied progression

| Commit | Step |
| --- | --- |
| `9c2ef2d` | Copy the existing patched GameServer as a byte-identical baseline into the target. |
| `886f0e5` | Apply the exact reviewed GameServer merge: integrity import/startup and revision update. |
| `e863606` | Copy remaining sources, libraries, scripts, tools and historical documentation; record per-file hashes. |
| `41ef8ee` | Configure target labels/documentation and actual build-only artifact retention. |

The complete [copy receipt](2026-11-04-port-copy-receipt.json) records 4,954
verified file copies, including 4,486 ignored historical backup files. The
GameServer baseline was copied/committed separately. Every target vanilla source
remains untouched. The two dependency JARs already present in the target matched
the source hashes before copying. No old vanilla source replaced the new baseline.

Source verification: 305 target Java files; 304 are byte-identical to the current
42.22.0 sources, and GameServer differs only by the approved upstream merge.
Subsequent changes update build-script labels, the retirement manifest target,
the documentation index and current repository guidance. Historical copied
documents keep their original filenames/dates and describe their original
42.22.0 investigations, not newly repeated tests. Their dates were retained from
the existing source filenames rather than reconstructed from Git history.

## Deliberately retained storage compatibility

This is an exact implementation carry-forward with the approved GameServer merge.
The following copied values remain **42.22.0**:

- `ApocBRPlayerRelationalDatabase`: source-dataset registration build label.
- `ApocBRWorldDictionaryDatabase`: publication, reuse and historical-read build pairs.
- `ApocBREventControl`: engine registration prefix; protocol remains `event-control-v2`.

The artifact targets game **42.22.1/world251**, but these storage/registration
labels continue to identify the copied implementation as 42.22.0. In particular,
new dataset metadata still records 42.22.0. This is a documented limitation, not
exact 42.22.1 provenance. Keeping these values avoids introducing an unsupported
dictionary pair into the existing backend constraints. No database migration,
namespace relabelling or historical payload rewrite was performed. A subsequent
coordinated metadata migration remains necessary for exact-build provenance;
the [historical dictionary guide](../features/2026-10-29-historical-dictionary-compatibility.md)
and audit explain the preserved boundaries. Do not fix this with a global replace.

## Actual build and artifact

The Windows build script now accepts `-BuildOnly` and an optional `-ArtifactDir`.
It runs the same real javac compilation as deployment, retains generated classes
and compiler logs, then exits before installation changes. It does not set
`-DryRun`. A new artifact directory is required to prevent stale output reuse.
Normal deployment and revert entry points remain available separately.

Command from the repository root:

```powershell
.\42.22.1\patchApocalipseBr.ps1 -PZDir 'Z:\SteamLibrary\steamapps\common\ProjectZomboid' -BuildOnly -ArtifactDir 'Z:\git\Github\apocalipsebr-zomboid-patches\42.22.1\artifacts\build-2026-11-04'
```

**Result: PASS**, javac 25.0.4, exit 0, **305 Java sources / 902 class files**.
The target JAR revision is `71e8410c42`, world format 251. Its SHA-256 and the
compiled source commit are recorded in the
[build receipt](2026-11-04-port-build-receipt.json). Inspection of the actual
GameServer bytecode confirms the `IntegrityReport.collectInBackground` call.

Deliverables:

- [ApocBR-server-42.22.1.zip](../../artifacts/ApocBR-server-42.22.1.zip), 4,356,973 bytes.
- [Retained class output](../../artifacts/build-2026-11-04/classes/).
- [Complete source/class/dependency hash manifest](../../artifacts/build-2026-11-04/manifest.json).
- [Compiler standard output](../../artifacts/build-2026-11-04/javac.out.log) and
  [diagnostics](../../artifacts/build-2026-11-04/javac.err.log).

ZIP SHA-256:
`134a93c2c1c54fb1f3c489cf755eba0b3366bdea329896832e6fe345c3a25683`.
The ZIP contains the 902 compiled classes at their package paths, both dependency
JARs, runtime-classpath helpers, the retirement manifest, README and hash manifest.
Archive CRCs and all 902 archived class hashes were verified against compiler
output. It does not include historical backups. Generated artifacts are ignored
by Git; source, documentation and build receipts are committed. The game
installation, launcher and live database were not modified by this build.

## Validation boundaries

Compilation, source hash preservation, merge equality, documentation links and
artifact contents are checked for this port. Runtime login/save/reconnect,
PostgreSQL interaction, streaming, NPC/vehicle behavior and shutdown still need
in-game validation. The inherited retired-class cleanup manifest is tracked but
automatic installed-class cleanup remains unimplemented. No game runtime or
database was started as part of this build.
