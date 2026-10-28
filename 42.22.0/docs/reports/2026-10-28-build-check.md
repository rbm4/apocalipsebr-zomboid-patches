# Build42.22.0 source compilation checkpoint

Created2026-10-28. Status: **approved303-source tree compiles successfully; runtime unverified**. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[JAR identity/compiler record](2026-10-28-build-check.json) and
[next compile-repair review52](2026-10-28-merge-52-iso-door-compile.md).

The installed projectzomboid.jar was identified through javap bytecode: Core's
static initializer constructs GameVersion42,22 and IsoWorld.WorldVersion is251.
JAR SHA256:90c54b84ebc160ae053cbefe30c7713826ca4fdb4934fde53c9ebde0ff9892f3.
This identifies the installed build; it is not a class-by-class decompiled/JAR
identity certification. The record preserves its path and temporary compiler log.

Java25 compiled all303 explicit target sources with the target JAR and bundled
PostgreSQL/Hikari libraries, matching the patch script's release, encoding,
sourcepath and implicit-compilation settings. Output stayed in a temporary folder.
No deployment script, launcher modification, server startup or database call ran.

Four errors were reported:

| Class | Compile failure | Next action |
| --- | --- | --- |
| IsoDoor | Redundant default after unconditional pattern and null switch cases | Review52 removes the decompiler arm. |
| ServerHumanTransport | Two references to removed PlayerPacketReliable.id field | Review native replacement field and NPC ID/wire handling next. |
| ServerZombiePath | Native addRequest now needs a trailing boolean | Trace native callers before choosing the argument. |

Review52 is approved/applied: its source equals the compiled overlay that removed
the IsoDoor error, leaving three errors. A further303-source compile with only
[proposed53 transport](2026-10-28-merge-53-npc-transport.md) overlaid removes both
transport errors, leaving one native path API error. Review53 is approved/applied and its773 focused checks passed again. A further
303-source compile with [proposed54 path argument](2026-10-28-merge-54-zombie-path.md)
overlaid succeeds: exit0, zero errors and895 class files. Review54 is approved/applied. A further compile of the actual approved tree
succeeds:303 sources, zero errors and895 class files. All compilation output stays in temporary folders. Native/client runtime tests, stale-class cleanup,
List-cache work and event-control metadata review remain open. Runtime verification may expose further issues. Historical payload migration planning
and disabled vehicle PostgreSQL repairs retain their user-deferred status.
