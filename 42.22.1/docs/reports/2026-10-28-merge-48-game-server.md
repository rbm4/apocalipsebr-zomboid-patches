# Review 48/48: GameServer and coupled packer API adaptation

Created 2026-10-28. Status: **approved and applied; full build/runtime unverified**.
The user approved both files on 2026-10-28. Applied sources equal the reviewed diff,
and the 824 counted assertions plus continuation/source checks passed again. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-48-game-server.diff) and
[scope/preimage/verification record](2026-10-28-merge-48-game-server.json).

## Starting point and conflict resolution

GameServer already contains the approved review38 NPC connection-slot reservation,
after exact native baseline **a5e1000**. This diff extends that current source.
**Do not copy vanilla over it or create another baseline.** The candidate preserves
its getFreeSlot body exactly: NPC IDs 768..1023 and 192 real connection slots remain.

The old [GameServer patch](../../../42.21.0/src/zombie/network/GameServer.java)
was merged against [old vanilla](../../../42.21.0/decompiled/zombie/network/GameServer.java)
and [target vanilla](../../decompiled/zombie/network/GameServer.java), using the
current reserved-ID source as the target. There was one textual conflict at RCON:
retain target profiler local var118 and add the old rcon.update telemetry scope.
No conflict markers or temporary merged source are shipped in the report.

## Custom subsystems retained

| Integration | Candidate behavior |
| --- | --- |
| PostgreSQL startup | prepareStartup before database creation; fatal SQL/class-load failure when the PostgreSQL server database is enabled; Roles.init then finishStartup. Existing disabled/native behavior remains. |
| Login continuations | Poll AsyncLogin and PlayerMigration on the server thread before normal packet dispatch. Keep bounded worker/read and deferred activation paths. |
| Player connect | Keep the public native entry and add the copied callback overload. Guard admission/deadline, copy remaining packet bytes, validate slot and vacancy, use durable-created-player handoff or asynchronous profile read. Recheck slot/username before finishPostgresPlayer and native connection completion. |
| Connect follow-up | The existing copied ConnectPacket already supplies a continuation for initial-world/metadata packets. They wait for completion; target co-op calls remain supported through the three-argument entry. |
| Player login audit | After successful native connection setup, record successfulLogin and run the continuation. Existing missing-player/Steam/native connection behavior stays in the ready path. |
| Mod entities / event control | Install custom mod entities after LuaManager.init; start event control and backend bridge before OnServerStarted. Tick created-player handoff, event control and ServerHumanFactory after LoginQueue.update. |
| Backend events | Restore tick, connection/disconnection and helicopter hooks. Existing bridge worker owns delivery; no external service is invoked by this review. |
| Zombie sender | Drain existing packer completions at outer-loop entry. awaitWorkers is a compatibility name: the implementation polls completions/releases lanes/restores failed requests; it does not wait for network workers. |
| Telemetry | Restore old phase/counter/tick/loop hooks, including finally-based outer-loop timing. Preserve packet budgets, native limiter/paused flow and native updates. These are measurements, not zero-cost scheduling optimizations. |

The candidate keeps the actual native receivePlayerConnectReady implementation,
substituting the old loaded-player/disabled-database load branch and adding only
its existing success hooks/callback. The fixture exercises dispatch and records
that terminal call; it does not run real native activation.

## Upstream changes preserved

Keep LocaleManager startup, GameMode.MULTIPLAYER, native revision/constants and
removal of UnitTests.runIfEnabled. Preserve the new silent-player stop/resend loop
and delayed dead-player handling. Native canModifyPlayerStats, whisper entry with
connection ownership, disconnect medical/trade-request cleanup, transaction cancel
by online ID, version251 inventory/save handling and removed legacy packet helpers
remain target-native. No old hit-character/slow-factor/inventory forwarding helper
is resurrected. The source fixture checks exact native methods and silent-player
block retention where those sections do not require custom hooks.

## Why NetworkZombiePacker is included

Its vanilla baseline is byte-identical between builds, so the old custom sender
was copied earlier. Its custom snapshot still calls ZombiePacket.write(ByteBuffer),
which the target no longer provides. This candidate adds a **single reusable**
ByteBufferWriter over the existing snapshotBuffer, then calls the target writer.
Snapshot clear, byte count, immutable copy, packet capacity, selection and queue
logic stay unchanged. This is a coupled compatibility adaptation, not another
rewrite of the asynchronous sender or a change to native packet fields.

## Verification

[Fixture](../../../tools/test_game_server_port.py) passes:

- **38** checks using the actual target asynchronous login coordinator with
  controlled worker/connection/DB ports: bounded/coalesced work, thread separation,
  stale sessions/world/identity, failure, expiry, disconnect and shutdown behavior.
- **18** checks on extracted candidate connection guard/dispatch methods: queued
  read before activation, packet-copy isolation, Steam co-op identity, created
  handoff, invalid/occupied slots, changed username, post-read vacancy and guard/
  budget/failure/native fallback behavior. Native activation is a recording port.
- **768** assertions across all 256 flag combinations using the actual native
  ByteBufferWriter and exact target ZombiePacket.write body, with field/UTF ports:
  buffer position, reused-writer byte equality and frozen-byte isolation.
- Additional continuation-order and native-source/startup/reservation contracts.

That is **824 counted assertions**, plus continuation/source checks. The previous
async fixture is run in a temporary overlay of current **42.22** support sources
and this candidate, rather than its original old-version paths. Writer field/UTF
encoding ports are doubles; this is not a native wire capture. The decoder,
packet preparation/worker queue, entire GameServer/Packer, server roles/JDBC/native
player activation, real schemas, RakNet and client/world runtime are not exercised.

Actual target JAR identification and full source compilation remain open. The
remaining source integrations can still fail without their supporting repairs;
this candidate is reviewable staging, not a deployable release or security/
durability certification.

## Decision and work after the numbered queue

Decision: applied this **two-file candidate** to the existing source, preserving
its recorded reservation. Neither existing custom source was recopied or given
a new baseline. The numbered queue is complete (45 merges, three retirements),
and the packer API gap is adapted. The migration remains incomplete.

Remaining work includes the PZArrayList/List cache design, relational database/
reconstruction/dictionary support gaps, event-control version metadata, full-JAR
build and native/DB/client verification, deferred disabled vehicle PostgreSQL gaps,
and tracked stale-class backup/pruning/restoration before deployment. Five class
families are tracked but automatic cleanup is not implemented. No production
configuration, installed game files or live database changed in this review.
