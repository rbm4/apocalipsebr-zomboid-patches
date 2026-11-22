# Review 28/48: NetworkZombieAI

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 27 is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/characters/NetworkZombieAI.java).
- [Old patch](../../../42.21.0/src/zombie/characters/NetworkZombieAI.java).
- [Target vanilla](../../decompiled/zombie/characters/NetworkZombieAI.java).
- [Candidate diff](2026-10-28-merge-28-network-zombie-ai.diff).

Retain prediction allocation reductions, threshold query adoption, enum caches
and the packet bookkeeping/field split. Adapt the old state resolver to preserve
the new native null-animation fallback. One text conflict overlaps that fallback;
it belongs in state resolution, not duplicated in packet field filling.
Applied source equals the reviewed candidate after exact vanilla baseline `e1fb420`. Primary owner: OPT-ZOMBIE; shared owners:
packet preparation, prediction, path thresholds and network state encoding.

## Existing optimizations and lifecycle

Use forward-direction component getters instead of allocating temporary Vector2
objects in prediction/extrapolation updates. Use the now-applied
PathFindBehavior2.isPathLengthGreaterThan(5) query in the walk-toward branch.
Keep native packet destination/prediction choices around that boolean decision.

Cache WalkType conversion by exact input string and ZombieState conversion by
state name plus default Locale (the state parser uses locale-sensitive lowercasing).
Keep primitive boolean-variable encoding rather than allocating/wrapping flags.
These caches avoid repeated conversion work while inputs remain unchanged.

Retain set(packet) as bookkeeping followed by packet fields for ordinary callers.
Expose updatePacketBookkeeping and setPacketFields for per-auth-frame preparation:
state/prediction/extrapolation bookkeeping can run once on the owning thread,
while prepared packet data can be reused across connections. Those methods are
not independently safe to call concurrently, and fields expect current bookkeeping.
The frame/lifetime contract is completed by ZombiePacket review 29 and the later
NetworkZombiePacker integration, not by this class alone.

## Required upstream adaptation

42.22.0 resolves a server zombie with null animator state to PathFind when it has
a location goal, otherwise Idle. A client null still follows normal parsing.
Preserve that rule inside resolveState. Evaluate the goal on every server-null
call instead of caching only the null state name; otherwise changing goals while
the animator remains null could leave stale wire state. Named-state and locale
caches retain their existing invalidation. Do not insert a second state overwrite
in setPacketFields after bookkeeping.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_network_zombie_ai_port.py)
reconstructs the candidate without creating an override. It passed **294 assertions**
using extracted candidate caches/resolver, actual target NetworkVariables enums
and animator/path/server doubles. It compares cached/nonnative-null behavior with
native enum conversion, tests server goal changes under unchanged null names,
client nulls, repeated/changed names and English/Turkish/root Locale transitions.
It does not execute full AI, prediction, packet contents, flags, culling, worker
ownership or bookkeeping once-per-frame behavior.

Path threshold dependency is now applied. Primitive flag and enum helpers are
staged target sources; packet/preparation/packer/native ownership integrations
remain separate reviews. Validate wire equivalence, state/prediction transitions,
pooled lifetimes, packet reuse and main-thread/worker boundaries. Exact-JAR
compilation and native multiplayer runtime validation remain pending. No game
installation or database was changed.

User approved: "yes". Applied-candidate equality and 294 cache/resolver assertions
passed again. Full prediction/packet/worker integration remains pending. No
installed files were changed.
