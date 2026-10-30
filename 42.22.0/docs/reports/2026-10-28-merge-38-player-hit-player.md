# Review 38/48: PlayerHitPlayerPacket / NPC combat integration

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
All 16 approved review38 files are applied. Review37 remains applied. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[combined draft diff](2026-10-28-merge-38-player-hit-player.diff) and
[scope/verification record](2026-10-28-merge-38-player-hit-player.json).

## Approved stock-client identity policy

The user selected preserving unmodified 42.22.0 clients. The applied port now
reserves **768..1023** for NPC presentations and leaves **0..767** for real
connection player IDs. It keeps the existing **64-active-NPC** limit.

| Contract | Candidate |
| --- | --- |
| NPC presentation IDs | 768..1023 inclusive; 256 fresh IDs per process |
| Real connection slots | 0..191; 192 simultaneous connection slots |
| Split-screen IDs | Four per connection, entirely below 768 |
| NPC ID recycling | None, including deactivation, failed activation and death |
| Exhaustion | Reject new activation; existing actors continue; restart required |
| Client Java changes | None proposed |
| Deployment | Full server-process restart; never hot-swap this identity policy |

`ServerHumanPresentationIds` holds the shared constants and slot predicate.
`ServerHumanFactory` uses those bounds with its unchanged monotonic allocator.
`GameServer.getFreeSlot` skips every reserved connection slot, whether an NPC is
active there yet or not. Normal login assigns slot*4 and stock co-op login derives
slot*4+playerIndex from that same admitted connection. Blocking whole slots therefore
covers all four player positions. UdpEngine's socket connection setting and
ServerOptions.MaxPlayers are unchanged; the effective admitted connection-slot
ceiling becomes 192 (up to 768 player IDs, subject to normal server/player limits).
The fixed reservation remains in force even when no NPC is active, allowing later
activation without colliding with a newly connected player.

The important tradeoff is **process-lifetime incarnation capacity**, not just
active actors. The previous allocator offered 31,743 IDs (1024..32766); this
proposal offers 256. With no skipped IDs or construction failures, 64 initial
NPCs leave 192 IDs for later activation/respawn. Failed construction after ID
allocation and unexpected occupied player/address entries also consume IDs.
The candidate does not silently recycle them: the wire has no NPC generation
field that could distinguish a delayed packet for an earlier incarnation.
Fresh process startup also creates fresh client sessions; no current live
connections or production configuration were modified for this investigation.

For comparison, reserving 512..1023 would offer 512 incarnations and 128 real
connection slots; 256..1023 would offer 768 incarnations and 64 real connection
slots. These remain design alternatives, not runtime settings. The user approved
**768..1023**, its capacity tradeoff and the coordinated merge.

## Native identity evidence and regression

The old factory IDs **1024..32766** fail the new envelope's native outer
ConnectionPlayer parser: Player.isInvalidOnlineId rejects IDs **>=1024** before
resolving them from the client player map. A correctly encoded child cannot
repair that rejected outer identity. The fixture retains that rejection regression,
and additionally passes reserved-boundary IDs **768** and **1023** through the
unmodified native parser. Human-origin hits to an NPC victim also remain consistent.
The initial draft fixture's low ID missed the old-range incompatibility; the
extended-ID regression exposed it before application.

Source evidence:

- [Current factory allocator](../../src/zombie/apocalipse/humans/ServerHumanFactory.java).
- [ConnectionPlayer.parse](../../decompiled/zombie/network/fields/character/ConnectionPlayer.java).
- [Player.isInvalidOnlineId](../../decompiled/zombie/network/fields/character/Player.java).
- [Envelope consistency and dispatch](../../decompiled/zombie/network/packets/hit/PlayerHitPacket.java).
- [GameServer.getFreeSlot/receiveClientConnect](../../decompiled/zombie/network/GameServer.java).
- [ConnectCoopPacket's existing slot-derived IDs](../../decompiled/zombie/network/packets/connection/ConnectCoopPacket.java).

## Old intent and target changes

The [old patch](../../../42.21.0/src/zombie/network/packets/hit/PlayerHitPlayerPacket.java)
adds detached NPC victim resolution, authenticated attack intake, deduplication,
native attack effects and logical health import to
[old vanilla](../../../42.21.0/decompiled/zombie/network/packets/hit/PlayerHitPlayerPacket.java).

[Target vanilla](../../decompiled/zombie/network/packets/hit/PlayerHitPlayerPacket.java)
extends PlayerHit, uses List<Hit>/CombatManager.TargetHitList and handlingType=0.
PlayerHitPacket is the handlingType=3 envelope; it runs native child consistency,
anti-cheat and weapon checks, recomputes attack state, then calls child processServer.
The old standalone child transport, WeaponHit setters and shot-ID APIs are gone.

Native Hit.roll computes server evidence for player targets. Hit.process calls
IsoGameCharacter.Hit -> IsoPlayer.hitConsequences -> BodyDamage.DamageFromWeapon.
Our old extra DamageFromWeapon loop would therefore inflict damage twice. The old
WeaponHit replacement only exposed ignoreDamage to that adapter; the draft does
not recreate that obsolete class or wire contract.

## Concrete candidate

The approved diff contains four target-baseline replacements, eight existing
support-file adaptations and four new support helpers (16 files). Exact vanilla
baselines were committed locally before applying the reviewed diff:
PlayerHitPlayerPacket 22a379e, PlayerHitPacket 4999063, Hit 8a6561f and
GameServer a5e1000. No push or deployment occurred. GameServer's only change here is the slot reservation;
**review48 must later extend this source**, preserving the reservation while
porting its remaining old custom integrations. Review38 does not approve review48.
The old WeaponHit ignore-damage accessor is covered by the new native hit/damage
path; its removed-class design is resolved without recreating WeaponHit. Its old
loose-class family is now tracked in the retirement manifest; cleanup remains
unimplemented and no installed files were removed.

- Resolve only a PLAYER-typed NPC victim. Keep native attacker lookup, connection
  ownership, weapon, count, life/floor, PvP/faction/safety, finite range and line gates.
  Require the child attacker to equal the envelope's connection-scoped attacker.
- Remove rejected/duplicate NPC children before native envelope effects and relay;
  recompute the closest surviving target so rejected claims cannot influence common
  critical/attack-state calculations.
  Allow one child per NPC victim per envelope; pellets remain in that child's hit list.
  Reject stale mapped instances before damage and suppress their relay.
- Use native hit rolls/damage once, then import measured body loss into logical
  health, killer attribution and physiology. Keep retaliation/encounter/dialogue/
  trading/speech intake once per accepted attempted attack. Missed rolls do no damage.
- Preserve server NPC posture/reaction state rather than consuming client victim
  flags/fall coordinates. Replace client force/direction/floor-hit-location evidence
  with the resulting native server values for recognized NPC victims.
- Exempt only recognized NPCs from Hit's connectionless-player delayed-disconnect
  bookkeeping. Ordinary player behavior follows native methods.
- Adapt outgoing authored NPC hits/misses to native Hit/TargetHitList child APIs and
  an envelope writer. Preserve the old authored chance/damage calibration instead
  of rerolling detached NPC attacks through client-lighting paths. Commit locally
  once before serialization, suppress a second inventory attack, retain explicit
  endurance/XP/maintenance effects. The reservation above resolves the observed native parser bound in the fixture; real-client gameplay remains to be validated.

## Approved duplicate policy

The user selected **a 500 ms exact-payload guard**, accepting the rapid-attack
limitation. SHA-256 fingerprints the original NPC child payload before native
rolls/sanitization. Entries belong to one NPC incarnation and are keyed by exact
attacker, connection and weapon instances. Each incarnation holds at most 128
unexpired entries, rejects saturation instead of evicting accepted entries, and
expires entries at 500 ms. No cache is populated for ordinary PvP targets.

Identical legitimate attacks during that window can be suppressed. Altered
payloads and replays after expiry can evade this exact-match filter; it is not
general replay prevention or a replacement for native anti-cheat. Native reliable
transport duplicate suppression is separate. The protocol contains no shot/nonce
that would distinguish every legitimate identical attack from a malicious replay.

## Verification and remaining work

[Fixture](../../../tools/test_player_hit_player_port.py): **1,725 assertions passed**.
It compiles the draft packet/Hit classes and NPC adapters against actual target
PlayerHit, HitCharacter, PlayerHitZombie/Square, CharacterID, character/hit Player,
Zombie, ConnectionPlayer, Weapon, Fall, ID fields and ByteBufferReader sources.

Coverage includes owner spoofing, parent/child identity mismatch, native anti-cheat
rejection dispatch, stale victim instances, exact-repeat cache boundaries/capacity,
repeated target children, hit-count/float bounds, native miss/shove/stomp paths,
logical damage/killer import, no extra body damage, NPC disconnect exemption,
outgoing hit/miss/maintenance/endurance, ordinary player behavior, human-to-NPC
client relay, the extended-ID stock-client rejection, and reserved-ID native
client acceptance. Outbound envelope bytes
are compared with the native serializer using the same fixture fields.

Reservation tests execute the exact candidate GameServer.getFreeSlot and unchanged
factory allocateId bodies against slot/map doubles: all 192 allowed slots and
four player indices each, skipping reserved empty slots, reconnecting into a freed
real slot, complete 256-ID allocation, collision skips, exhaustion and no reuse
after map clearing. The fixture checks that the sole GameServer diff is its slot
predicate. Full GameServer and factory composition are not compiled here.

Engine state, CombatManager rolls, armor/body damage, Lua, transport, attack vars,
hit-info/tracers and primitive writer dependencies use explicit doubles. Real
ServerHuman composition is replaced with a recording logical-state double; its
state-field adaptation is source-checked. Tests do not establish native armor/RNG,
wire capture equivalence, complete ammo accounting, lifecycle/thread safety, exact
JAR compilation or live multiplayer compatibility. The old extended-ID rejection
remains an
asserted regression; reserved-ID parser acceptance is not a live-client gameplay
compatibility claim.

The user approved the reservation, capacity tradeoff and coordinated merge.
All 16 applied files equal the reviewed candidate after newline normalization;
the 1,725-assertion fixture passed again after application. No installed files,
server configuration, client binaries or live database changed.

Next is [review39: PlayerHitSquarePacket](2026-10-28-merge-39-player-hit-square.md),
a separate unapplied diagnostic-only candidate. Full GameServer custom integration
remains review48 and must preserve this reservation.
