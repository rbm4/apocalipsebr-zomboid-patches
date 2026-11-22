# Server Lua dialogue actions and pooled responses

Status: implemented for 42.22.1 server; exact-JAR compilation and isolated tests
pass. Dedicated-server/client gameplay validation remains pending. These changes
extend existing ApocalipseBR support classes; no vanilla replacement baseline
was introduced for this feature.

## Configuration and authority

Manager game-runtime migrations V40 and V41 must run before the updated engine.
V40 adds `npc_engine.dialogue_choice_action`, keyed by dialogue/revision/choice,
with a named `action_id` and `response_prefix`. Published rows are immutable.
A choice cannot combine this binding with an existing NPC task command. No code
strings or function paths are stored in SQL. V40 grants the new table and
validation function to `pz_server_01`, and retains creator defaults for
`apocalipsebr`. Manager `NpcTables` includes the binding in load/edit/publication,
so subsequent edits preserve it.

V41 adds five PTBR `label_text` variants for each outcome pool:

- `safehouse.teleport.success`
- `safehouse.teleport.cooldown` (placeholder `{remainingMinutes}`)
- `safehouse.teleport.no_safehouse`
- `safehouse.teleport.unavailable`

It clones the complete current Agente V dialogue into a new revision, preserving
the original lore option/history, and adds **Pode me enviar ao meu abrigo?** with
action `player.teleport_safehouse`. The profile and apoc-dev-only spawn are
unchanged. See [Agente V setup](2026-11-04-agente-v-dev-lobby.md).

## Execution contract

`ApocBRHumanAPI` version 5 retains the previous methods and adds
`dialogueComplete(player,id,token,result)`, `accountIdentity(player)` and
`teleport(player,x,y,z)`. These are trusted server Lua APIs, not client commands.
The mod continues to forward only client option/session identifiers.

For a plain text/task option, `dialogueSelect` retains the existing flow. For a
Lua action it validates the same authenticated actor, proximity, revision,
sequence and flags, then stores one pending choice and returns its server-only
binding. No flag, cursor transition or speech is applied at this point. Another
selection cannot replace or repeat the pending action. The mod calls its
registered server handler outside the Java factory monitor.

The handler returns `success`, `responsePool`, optional string-valued `values`,
and `close`. `dialogueComplete` revalidates the lease, proximity and expected
sequence, resolves a pool inside the authored prefix, consumes the pending
choice and speaks the resolved text. The same text becomes the panel response.
Failure consumes the request sequence but keeps the current node and grants no
flag. Success applies the authored transition/flag, and may close. Duplicate
completion cannot speak or grant facts again. Text-only choices do not require
callbacks. Handler absence/error uses `<response_prefix>.unavailable`; a missing
or invalid pool fails closed and releases the conversation.

An optional server-only `afterResponse` closure runs after successful completion
and response packet preparation. `cancel` releases reservations if completion or
response framing fails. Callbacks must reserve/validate before side effects and
use `afterResponse` for irreversible work. Java does not automatically undo
arbitrary Lua effects, nor make effects crash-atomic. The existing `selected`
notification remains observational; it cannot override the action result.

`HumanDialogueResponses` resolves at most 32 numbered labels `<pool>.1` through
`.32`, deduplicates text, and avoids the previous template per NPC incarnation
when alternatives exist. Memory retains at most 16 pool entries. One immutable
cached catalog snapshot supplies each resolution. Language uses existing PTBR
preference/fallback. Template substitution is single-pass, with at most 16 values,
64 characters per value and 256 characters in the final spoken/panel text.
Unknown placeholders, invalid content or pools outside the binding prefix fail.
No SQL or live-world access occurs in the resolver.

## Threading, cancellation and teleport

Lua `OnClientCommand`/`OnTick` own action dispatch, safehouse checks, transfer
tracking and cooldown mutation on the server thread. Java API calls synchronize
on the existing factory monitor shared by the NPC worker. Database workers only
publish detached graph/label snapshots; they never execute callbacks. User Lua
handlers run between API calls, outside that monitor. NPC worker session close
continues through the normal lease lifecycle. No new background threads, blocking
waits or per-action SQL calls are introduced.

The `teleport` capability validates a connected living player, finite bounded
coordinates, integer floor and execution on `GameServer.mainThread`, then calls
exact native `GameServer.sendTeleport`, including its movement/anti-cheat resets.
It reports request acceptance, **not arrival**. It does not reuse the login-only
one-time relocation helper. Account IDs use exact unsigned Steam ID strings;
non-Steam servers use username identity.

ExtractionMapUtils owns safehouse membership/destination policy, six-hour
cooldowns and confirmation. Revision 6 uses `SafeHouse.hasSafehouse(player)` (owner or
member), then `X + floor(H/2), Y + floor(W/2), 0`, matching the exact 42.22.1
IsoWorld death-respawn coordinate calculation (including its dimension order). It calls the existing native server teleport bridge
without loaded-square checks or forced destination streaming, like the lobby
relocation transport. The native client handles relocation/loading. No Java
change accompanies this mod-only correction.

Preparation lasts at most 20 seconds; polling runs every 250 ms and the pending
set is capped at 256. Membership, player identity and vehicle/death/connection
state are rechecked before sending. One native request is sent after a
nonblocking 2.5-second NPC response interval. A separate 20-second arrival window
starts after sending. Only observed arrival on the exact target tile at floor
zero starts the memory cooldown; native request acceptance alone does not.
Cooldown identity is shared across characters/NPCs for the account within this
server process. Revision 4 makes it memory-only at the user's request: reconnects
and respawns retain it, while every server restart clears it. Old Global ModData
cooldowns are ignored without deleting saved data. Success pool wording
authorizes/starts transfer; late timeout does not record arrival or cooldown and
does not produce a second NPC response after closing.

## Validation and deployment

- `py -3 tools/test_dialogue_actions.py`: 15 checks executing extracted production
  selection/completion methods with explicit lease/world/delivery doubles.
- `py -3 tools/test_npc_persistence.py --version 42.22.1 --agente-v-manager-test`
  with `APOCBR_TEST_POSTGRES_BIN=Z:\postgresql\bin`: 195 target JDBC/profile/pool
  checks in a disposable PostgreSQL cluster, plus 26 manager tests including
  Flyway upgrade/repeat, engine permissions and editor binding preservation.
- Companion mod: safehouse travel 25 checks, dialogue graph/routing 31 checks,
  existing dialogue 89 checks and roster 45 checks, using engine doubles.
- Actual `42.22.1/patchApocalipseBr.ps1 -BuildOnly -ArtifactDir <new-directory>`:
  312 sources / 913 classes, installed JAR, javac 25.0.4; no deployment.

Apply manager game-runtime Flyway V40/V41 after V39, patch the server, deploy the
mod's updated `common` Lua files, and restart. No live DB migration, restart or
game installation mutation was executed during implementation. Validate actual
shout/panel presentation, stock client relocation and anti-cheat, unloaded
safehouse streaming, membership changes, cooldown after reconnect/respawn and
ordinary server save/restart in multiplayer.

Related: [NPC architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md),
[NPC profiles/lifecycle](../../../42.21.0/docs/features/2026-10-20-npc-profiles-lifecycle.md),
[native streaming bridge](2026-11-04-lobby-startup-streaming.md).
