# Agente V: apoc-dev lobby NPC

Follow-up V40/V41 adds [server Lua actions and safehouse travel](2026-11-04-dialogue-lua-actions.md)
as a second option in a new immutable dialogue revision. The one-choice behavior
below describes V39's original revision.

Target: 42.22.1 server. Manager game-runtime Flyway migration
[`V39__Seed_Agente_V_Dev_Lobby.sql`](../../../../apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V39__Seed_Agente_V_Dev_Lobby.sql)
authors the complete NPC configuration. No actor-specific Java registration or
Lua startup script is required. Existing installations need the generic greeting
pool support in this build as well as the migration.

## Authored behavior

`server_spawn` contains only `(apoc-dev, agente-v)`, username `NPC_AgenteV`, display
name **Agente V**, female, `PrivateMilitia`, home `(9373,150,0)`, facing south.
The [native presentation name fix](2026-11-04-npc-display-names.md) uses **Agente V**
for overhead labels and native speech while retaining `NPC_AgenteV` in configuration.
The mod's `42/media/clothing/clothing.xml` overrides the female `PrivateMilitia`
outfit; that mod and its assets must be enabled on the server and clients.

Profile `agente-v-lobby` revision 1 enables god mode and zombie ghost mode, while
remaining visible to players. Autonomous modules are `engage-zombies` (100),
`greet` (200), and `walk` (1000), with 8-tile LOS perception every 100 ms. Defense
therefore preempts autonomous greetings/walking. The existing `indoor-wander`
background scope uses the actual building containing home and the actor's floor;
no approximate rectangle is authored. She engages visible zombies inside that
scope using the existing combat equipment/execution and zombie-kill speech pool.
There is no player aggression, encounter challenge, trading, survival retreat or
operation announcement binding. Existing exclusive dialogue control remains
above autonomous module arbitration.

The sole dialogue choice is **Que lugar é este?**. Its short PTBR response
introduces the lobby, her defection from Umbrella, experiments and territory
control in Ashford County, and rewards for zombie kills and territorial conquest.
This is narrative content; it does not add or change reward mechanics. The choice
returns to the same node without extra options. PTBR is the graph fallback and
the existing engine language default; no global language configuration changes.

## Generic greeting pools

`greeting_key = pool:<prefix>` selects up to 32 labels named `<prefix>.1` through
`<prefix>.32` from `npc_engine.label_text`. Sparse indexes work. Each line uses
the existing language/fallback policy, must be nonblank and at most 256 Java
characters, and duplicate resolved texts count once. Selection is uniform among
the eligible texts, excluding the previous delivered-to-action selection when
alternatives exist. Single-line pools may repeat. Missing pools return no speech;
invalid lines fail the greeting through the existing brain/provider failure path.
This is selection memory, not a network delivery acknowledgement.

`HumanGreetingProvider` owns only a previous-text value per behavior instance;
each activation gets a fresh provider. Existing plain label keys keep their
single-line behavior. Agente V has four numbered PTBR greeting labels. Greeting
selection does not use the combat reactive-speech event vocabulary or global pools.

The SQL worker publishes detached cached labels with `HumanDialogueCatalog`.
Each pool resolution captures one immutable snapshot, performs at most 32 cache
lookups, and does no JDBC/world access. The NPC simulation owner invokes the
provider and retains the existing action cancellation and exclusive dialogue
rules. A greeting cancelled after selection may still consume that selection;
despawn drops its transient memory. Labels follow the existing 30-second catalog
refresh; new server spawns are materialized only at startup.

## Startup, permissions and validation

Flyway runs V39 in **game_runtime** as the migration owner `apocalipsebr`. No new
schema/table/sequence/function is introduced; existing `npc_engine` engine grants
apply to all rows. On startup, `ApocBRNpcStore.materializeServerSpawns` copies the
definition only for the exact server key into its actual world. Other server keys
receive no instance. `ON CONFLICT DO NOTHING` preserves existing instances and
runtime history, including administrative deactivation. PostgreSQL persistence
must be enabled and healthy; the existing startup hydration/activation fence
applies. Restart apoc-dev after the migration and updated patch are installed.

Verification: `py -3 tools/test_npc_persistence.py --version 42.22.1` passed 181
checks against a disposable PostgreSQL cluster with distinct migration and engine
roles, exercising actual target JDBC/profile/dialogue/greeting code, server scope,
restart preservation, permissions and immutable publication. The manager's
`NpcAgenteVMigrationTest` separately verifies Flyway upgrade/repeat/validation.
`42.22.1/patchApocalipseBr.ps1 -BuildOnly -ArtifactDir <new-directory>` compiled
311 sources into 912 classes against the installed game JAR. No deployment or
live database changes were performed.

Dedicated-server validation remains: ensure the specified tile belongs to the
intended building and is streamed, inspect female UBCS visuals on clients, greeting
variation, dialogue display, patrol boundaries, zombie aggression immunity and
combat/kill reactions. Neither compilation nor isolated JDBC fixtures proves
these native-world behaviors.

Related: [profile/lifecycle contract](../../../42.21.0/docs/features/2026-10-20-npc-profiles-lifecycle.md),
[architecture contract](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md),
[reactive combat speech](../../../42.21.0/docs/features/2026-10-26-npc-speech-engine.md).
