# Nearby squad strength in fight-or-flight decisions

Target: **42.22.1 dedicated server**. Implemented and fixture/JAR-build verified
on 2026-11-05. Deployment and live validation remain pending.

Related: [squad assistance](2026-11-04-npc-squad-combat-assistance.md),
[retreat rendezvous](2026-11-05-npc-retreat-squad-rendezvous.md), and
[NPC architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md).

## Latest runtime finding

Evidence: `C:/Users/ricar/Zomboid/Logs/2026-11-05_00-28_DebugLog-server.txt`.
Times below are literal log timestamps on 2026-11-05.

| Time | Evidence |
| --- | --- |
| 00:32:21.420, line 8912 | Vector enters survival with `reason=outnumbered; nearby=7; health=1.0; endurance=1.0`. |
| 00:32:21.542/599, lines 8921/8923 | HUNK and Spectre begin approaching Vector's distress. |
| 00:32:33.666/965, lines 8980/8991 | HUNK and Spectre reach `ENGAGING`, near X=9851, Y=2510.5. Combat execution is reached; this alone does not prove a successful hit. |
| 00:32:36.773/778, lines 9035/9043 | Both helpers enter survival with `reason=outnumbered; nearby=6; health=1.0; endurance=1.0`. |
| 00:32:36.783 onward | All three repeatedly start escape legs with six threats; later legs show `support=[]`. Zero/six awareness fluctuations and recovery transitions continue. |

Previously each actor compared its personal nearby count to four, without any
squad strength input. Two healthy reinforcements made no difference to that
threshold. Survival preempted assistance, and helpers in survival stopped
supplying the separate active-pursuit rendezvous channel. This explains the
assistance-to-flight cascade. The zero/six sensor fluctuation remains unverified.

## Behavior and bounds

`ServerHumanGroups` captures a capable-ally count for each live grouped actor.
Another member can contribute only when it is:

- In the same registered group, within **six tiles**, on the same floor, and
  visible under the recipient's existing perception rules (including loaded-ray
  checks when that profile enables LOS).
- Alive, autonomous, mobile, not downed, actively bleeding or traversing, with
  a loaded movement origin and its own profile's reengagement health/endurance
  thresholds met (defaults .55/.72).
- In patrol, zombie engagement, survival or without a selected module. Healing,
  other tasks, dialogue/trading and pending player retaliation cannot lend capacity.

The recipient must also be combat-ready. At most seven allies contribute;
there are at most 56 directed proximity/visibility candidates per eight-member
group per capture. The count is immutable and expires after **500 ms**. Future
timestamps, absent support and a closed adapter contribute zero. A fresh
incarnation starts without support.

`ThreatAssessment` now uses:

```text
crowdLimit = configured crowdCount * (1 + fresh capable nearby allies)
outnumbered = nearby zombies >= crowdLimit
```

With defaults, the crowd retreat threshold is four zombies solo, eight for a
pair, twelve for three local fighters. Six/seven zombies do not by themselves
force three capable members to flee. This is a local capacity heuristic, not
measured weapon DPS or a deduplicated group-wide horde estimate. Different
clusters, weapon loadouts and narrow geometry still need live tuning. There is
no new profile/schema setting. Personal low health and exhaustion still force
retreat, regardless of allies. Attack visibility/identity/range/territory rules
remain unchanged; support is not attack authority.

Capacity does **not** depend on an assistance assignment, pursuit lease or SAFE
survival phase. Otherwise the first retreat would erase capacity for everyone
else and recreate the cascade. A physically capable retreating member can
still help the group stabilize once close together. Rendezvous routing remains
separate and still requires an active helper execution lease.

## Recovery and cancellation

A healthy, rested actor with fresh support and pressure below its group limit
can build the controlled-relief timer even with an immediate enemy nearby.
After one sustained second it stops escaping and defends through the existing
recovery/guard execution. It returns to SAFE after the existing reengagement
hold (default eight seconds for controlled relief), then releases survival to
normal combat arbitration. A close enemy alone does not restart escape while
that supported state holds. Lost/stale support or renewed overload resets relief.

Injury/exhaustion, death, movement cancellation and exclusive interactions retain
their owners. No extra damage commits or persistence effects come from counting
an ally. Survival phase diagnostics now include `allies` and `crowdLimit` with
`nearby`, health and endurance. Adapter-only movement failures use zero capacity
fields because they do not carry an assessment. Logs remain transition-based.

## Ownership

The registered world thread captures membership, positions, native availability
and visibility under the factory monitor in both startup modes. The per-actor
`ServerHumanSquadAssist` retains an immutable timestamp/count for its incarnation.
`ServerHumanPerception.observe` attaches it to `HumanObservation`; serialized
simulation consumes detached values in assessment/survival. Existing constructors
default to no support. No new worker, queue, database access or packet format.

Existing NPC worker/native-access limitations remain. A captured count can lag a
death, displacement or interruption until the next world capture, bounded by
500 ms freshness. It grants no native attack permission.

## Verification

- `py -3 tools/test_human_squad_assist.py`: **143 assertions passed** against
  production target capture, support adapter, assessment, survival and arbitration.
  Covers six-versus-three, overload, stale/future counts, distance/floor/group
  exclusion, wounded/exhausted/bleeding/downed/unloaded/occluded/occupied allies,
  personal emergencies, support during retreat, close-enemy recovery, member
  removal and renewed survival after losing allies. Native geometry/visibility
  are explicit engine doubles; full native perception is not executed here.
- `py -3 tools/test_human_groups.py`: **141 assertions passed**.
- `py -3 tools/test_human_healing.py`: **70 assertions passed**.
- `42.22.1/patchApocalipseBr.ps1 -BuildOnly`: exact installed-JAR compilation
  passed with JDK 25.0.4, **331 sources / 958 classes**, retained at
  `42.22.1/artifacts/build_20261105_003858/`. No installation changes.

Live follow-up in async and synchronous modes: repeat this encounter, check
`allies/crowdLimit`, confirm helper attacks and Vector's recovery into combat,
and verify personal injury/real overload still cause retreat. Include closed
doors, obstacles, streaming, member death/reincarnation, separation and stale
world captures. Fixtures/build do not prove native LOS or live combat success.
