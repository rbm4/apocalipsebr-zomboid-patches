# Discard rejected player saves and release legacy quarantine (42.21.0 server)

The user explicitly selected availability over preserving uncommitted player
progress: log a rejected save, abandon that mutation, and allow loading the last
committed database state. This policy replaces the persistent rejection guard in
[runtime failure isolation](2026-10-17-persistence-runtime.md). It accompanies the
[SpriteConfig codec](2026-10-28-player-sprite-config-persistence.md).

## Runtime behavior

The coordinator still distinguishes transport loss from an operation-specific
failure. During runtime, connection failures retain the active operation and use
outage recovery. SQLState classes 22/23 abandon the rejected operation immediately;
other operation-specific failures use the existing five-attempt retry budget.
Rejection logs retain sanitized error details, operation UUID, kind and attempts,
plus `discarded=true; reloadBlocked=false; recovery=last_committed` for players.

The journal durably archives the rejected payload before advancing the checkpoint.
It creates no new `.pending` marker and does not fabricate a database receipt.
A checkpoint/archive failure leaves the operation active for local recovery.
If a crash occurs after archive but before acknowledgment, replay recognizes the
archived UUID and completes abandonment without applying it again. All normal
active operations, including newer operations for that same player, retain their
pending counts until acknowledged. `blockedPlayers` remains in progress logs for
compatibility and reports zero (rejected-player blocks only).

This does not delete a single item or commit a partially captured graph. It
abandons the entire failing operation. Inventory, position and other changes
present only in that operation are not persisted. Restoring an older inventory
can also roll back transfers; this is an explicit consequence of the selected
policy. No new valid character is manufactured when there is no committed save.
Normal character validation and active-save/outage admission still apply.

## Startup upgrade of existing quarantine

Before journal worker threads start, initialization scans `quarantine/*.pending`.
It verifies each marker's CRC/header, key-derived filename and referenced archive
presence using the existing marker contract, then atomically renames it to
`<original marker filename>.<operation UUID>.released`. Each successful rename
logs `PLAYER_REJECTION_RELEASED; operation=...; reloadBlocked=false;
recovery=last_committed; archived=true`.

Already released markers are not scanned again. Interrupted migration resumes
with remaining `.pending` files; active counts reconstructed from the journal
remain enforced throughout. Historical `.record` contents are neither decoded nor
replayed by this migration, avoiding repeated capture of unsupported old state.
No PostgreSQL writes or player reconstruction occur in the migration. Retained
records remain available to the [inspector](../issues/2026-10-28-quarantine-item-inspection.md).
Malformed markers or filesystem failures remain explicit initialization failures;
this policy does not bypass journal integrity checks.

Audit and sale records retain their existing special quarantine handling; this
change neither credits a sale nor retries an audit record. Startup/shutdown
attempt budgets and database bootstrap requirements remain unchanged.

## Ownership and deployment

Changes are limited to the custom server coordinator
[ApocBRPersistence](../../src/zombie/savefile/ApocBRPersistence.java) and journal
[ApocBRPersistenceJournal](../../src/zombie/savefile/ApocBRPersistenceJournal.java).
No vanilla class replacement baseline or SQL migration is needed. Journal
mutations remain synchronized; marker migration runs with the exclusive journal
file lock held, before workers are created. No live player is touched.

Deploy the 42.21.0 build with the normal patch flow to `/opt/pzserver`, preserve
the journal, and restart. Existing markers are released automatically without an
extra JVM flag or manual quarantine deletion. This is implemented locally, not
deployed by this task. Runtime reconnect validation remains necessary.

## Verification

`python tools/test_postgres_resilience.py` exercises production journal,
coordinator and receipt code with fixture JDBC. Coverage includes a real rejected
live graph becoming readable, no false success receipt, archive retention,
same-player active successors, archive/checkpoint crash recovery, fresh quarantine
without markers, thirty historical records with three legacy player markers,
restart idempotence, real coordinator startup releasing an old marker, and existing
transport-loss/attempt-budget/audit/sale contracts.

Validation passed: 691 main resilience checks, 3 missing-migration checks,
13 shutdown checks, 9 offline checks and 12 startup checks (728 total).
`./42.21.0/patchApocalipseBr.ps1 -DryRun` also succeeded: 301 server sources
compiled and 891 class files enumerated for deployment. No installed game classes
were changed. No tests connect to production PostgreSQL or alter production saves.
