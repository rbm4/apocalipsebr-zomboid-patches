# NPC ghost mode hides bots from ordinary players

Status: source fix compiled and isolated regression passed on 2026-11-12.
Deployment and dedicated-server/client verification remain pending.

## Cause and correction

The 42.22.2 vanilla `IsoPlayer.setGhostMode(boolean, boolean)` delegates to
`setInvisible(boolean, boolean)`; `isGhostMode()` returns `isInvisible()`.
`ServerHuman` applied the profile's `invisible` value and then its `ghostMode`
value, overwriting visual visibility. Consequently, `ghostMode=true` hid an NPC
from ordinary players even when `invisible=false`. Admin viewers can see
invisible players, masking the problem. The reverse combination also incorrectly
cleared intentionally authored invisibility.

NPC construction now applies only the explicit profile `invisible` value to the
native actor. It no longer calls the native ghost-mode setter.
`ServerHumanZombieVictims` already excludes profiles with either `ghostMode` or
`invisible` from victim snapshots and resolution, preserving server-controlled
zombie immunity independently of client presentation. God mode remains separate.
No vanilla player behavior, profile schema or packet format changed.

This fixes the authored visible-but-ignored behavior described in the
[Agente V guide](../../../42.22.1/docs/features/2026-11-04-agente-v-dev-lobby.md).
That guide's earlier compilation/JDBC validation did not verify player visibility.

All initialization and victim resolution remain on their existing server owner;
no worker inputs, synchronization or cross-thread work were added. Restart with
the updated patch to recreate existing native NPC actors.

## Verification and remaining checks

- `py -3 tools/test_npc_visibility.py`: 43 checks passed using extracted production
  activation statements, zombie victim selection, the exact vanilla ghost alias,
  and the actual `HumanCheats` record with engine doubles. Covers all eight flag
  combinations, logical/native death and null victims.
- `42.22.2/patchApocalipseBr.ps1 -BuildOnly -ArtifactDir
  42.22.2/artifacts/build-2026-11-12-npc-visibility`: 371 sources compiled into
  1062 class files against the installed target JAR. No deployment.
- Dedicated-server verification pending: join as an ordinary player and an admin,
  confirm the same ghost-only NPC is visible, then reconnect and leave/reenter
  relevance. Confirm zombies ignore that NPC and still target a non-ghost NPC.
  Check an explicitly invisible profile remains hidden from ordinary players.
  Isolated checks do not establish client rendering or native zombie behavior.
