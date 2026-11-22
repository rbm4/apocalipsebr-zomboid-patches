# NPC overhead names

Target: 42.22.1 server; verified by compilation on 2026-11-04.

NPC native `IsoPlayer.username` now uses the definition's `displayName`, preserving
spaces and accents. A definition with username `NPC_AgenteV` and display name
`Agente V` therefore appears as **Agente V** above its head and in native speech.
The configured username remains in the immutable definition and persistence;
logical actor keys, profile selection and online ID allocation are unchanged.
There is no prefix-stripping heuristic: authors must supply the desired display
name. Existing persisted definitions work without a database migration.

The exact vanilla baseline explains the behavior:
`decompiled/zombie/characters/IsoGameCharacter.java:updateUserName` resets the
display name before reading it, and `IsoPlayer.getDisplayName` rebuilds it from
the username under the server's name/disguise options. The native
`network/packets/connection/ConnectedPacket` writes the username as UTF text and
accepts spaces. No vanilla replacement or client patch is added.

`ServerHuman` owns native name assignment during construction. `ServerHumanFactory`
reserves canonical display names at registration, startup validation and retired
state hydration, and checks them against online real usernames at registration
and activation. Canonicalization retains the existing NFC/strip/case policy.
Two NPCs with equal canonical display names are rejected, even with distinct
configured usernames; distinct display names are distinct native identities.
Reservations last for the process lifetime to protect chat caches.

The existing factory/server or NPC-worker ownership applies. Inputs are immutable
definitions; no new worker, queue, database access or cross-thread mutation is
introduced. Construction/name collision failures retain existing registration
and activation failure handling. This assignment has no cancellable execution.

Validation:

- `py -3 tools/test_npc_display_names.py`: 8 checks using extracted production
  assignment/reservation and native reset/get-display methods with engine doubles;
  spaces, accents, persistence input preservation, duplicate/retired/online name
  collisions and Unicode normalization. Source contracts also check hydration,
  renderer reset and the native packet username field.
- `42.22.1/patchApocalipseBr.ps1 -BuildOnly -ArtifactDir
  42.22.1/artifacts/npc-display-names-20261104`: passed, 312 sources / 913 classes
  against the installed game JAR. No deployment.

Install the updated server build and restart to recreate native actors and clear
old client/chat identities. Live multiplayer overhead labels, speech, reconnect
and streaming under the server's name/disguise settings still require validation.
Vanilla settings can still append usernames or hide disguised names.

Related: [Agente V lobby configuration](2026-11-04-agente-v-dev-lobby.md),
[NPC architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md),
[profiles and lifecycle](../../../42.21.0/docs/features/2026-10-20-npc-profiles-lifecycle.md).
