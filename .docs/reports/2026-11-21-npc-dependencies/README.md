# Bot dependency audit evidence

This directory is the reproducible evidence snapshot for the
[2026-11-21 report](../2026-11-21-npc-dependency-map.md). It contains analysis
tools and captured results only, not agent/game implementation.

| File | Use |
| --- | --- |
| `manifest.json` | Input/artifact hashes, original JAR hash, capture version/context and summary counts |
| `custom-dependencies.json` | 191 starting sources, conservative 234-source lexical custom closure and dependency edges |
| `native-inbound.csv` | Direct lexical references from native replacements to seed types; distinguish imports from executable references |
| `native-api-catalog.md` | Human-readable index of native member references from the seed sources, grouped by declaring type |
| `native-api.csv` | Compiler-attributed source locations/types/members/access for the full custom closure |
| `dynamic-and-config.csv` | Configuration/reflection/string-based integration candidates requiring manual classification |
| `native-member-deltas.csv` | AST method/field changes across the 109 legacy native counterparts; candidates, not proof of NPC dependency |
| `original-jar-boundaries.json` | Classfile declarations, hierarchy/access and signing entries from the original archive; no class initialization |
| `vanilla-object-list-sites.csv` | Same-build textual object-list uses and IsoCell mutation sites; not alias-complete mutation coverage |
| `javac-diagnostics.txt` | Original-JAR compile probe output: expected failure with ten diagnostics |
| `compiler-diagnostics.txt` | Diagnostics from symbol attribution, also retaining the known missing APIs |
| `NativeDependencyScan.java` | JDK compiler Trees/JavacTask symbol census; no native/game execution |
| `NativeDeltaScan.java` | AST member delta collector; no whole-program semantic analysis |
| `capture.py` | Standard-library capture, classfile inspection and hash verification orchestrator |

From the repository root, verify the captured inputs/artifacts:

```powershell
py -3 .docs/reports/2026-11-21-npc-dependencies/capture.py --verify --game-jar 'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'
```

The original capture command was the same without `--verify`. It requires the
repository JDK (`jdk/bin`, or explicit `--java`/`--javac`) and historical HikariCP
and PostgreSQL dependency JARs under `42.22.2/lib`. It compiles report tools and
runs an isolated compilation probe under ignored `target/`, with annotation
processing and implicit source discovery disabled. The probe's failing exit is
recorded evidence, not a successful build. It does not start a game, connect to a
database, alter the original archive, or compile/deploy legacy replacements.

Capture refuses to overwrite an existing manifest. Preserve this dated snapshot
when inputs change; take a separately dated capture rather than refreshing its
hashes to conceal drift. `README.md` is explanatory and excluded from the
artifact hash set; the tools and generated catalog are included.

At the final report check, strict verification detected post-capture drift in
`AGENTS.md`. A full comparison confirmed the other 473 source/context files,
all 13 captured artifacts and the original JAR were unchanged. The manifest
remains the original capture; this context drift is documented in the report.

Rows represent attributed references, not dynamic execution counts. The summary
deduplicates rows; distinct member counts include reference kind, declaring owner
and member signature, excluding type-only rows. Overloaded declarations and
inherited method owners matter. Lexical closure can overinclude shared services
and miss reflective/indirect entry points, so it must be read with the report's
behavioral map. AST deltas do not enumerate class-header/static-initializer
changes and cannot determine whether an optimization is needed by the NPC port.
