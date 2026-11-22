# NPC patrol combat: retreat oscillation and missing group support

Status: **open investigation**. Target: 42.22.1 server. Investigation date:
2026-11-04. No runtime source, deployment, live configuration or database changes
were made for this investigation.

Owning feature: [shared group patrol](../features/2026-11-04-npc-group-patrol.md).

Implementation follow-up: [A-life squad combat assistance](../features/2026-11-04-npc-squad-combat-assistance.md)
now supplies the missing threat-sharing/helper behavior in source, after the user
selected combat assistance rather than downed-ally medical rescue. Deployment and
runtime validation are pending. The following encounter diagnosis describes the
earlier execution; terrain inputs, retreat oscillation and the deployed dwell
mismatch remain open.

## Evidence and runtime mismatch

Read local `C:/Users/ricar/Zomboid/Logs/2026-11-04_23-01_DebugLog-server.txt`,
especially the encounter at 23:18:57-23:19:50. Times below are literal log times.
The log is not a packet capture and contains no paired rendered-client/server
position samples. It cannot establish the size or cause of a visual position gap.

The installed loose classes under
`Z:/SteamLibrary/steamapps/common/ProjectZomboid/zombie/apocalipse/humans/groups/`
have modification time 2026-11-04 23:00:42. Running repository JDK `javap` against
the installed `GroupPatrolCoordinator$Phase` lists only `MOVING`, `REGROUPING`,
and `STALLED`. **DWELLING is absent.** Current repository source includes it.
The investigated log likewise contains no DWELLING phase; before combat,
regrouping repeatedly lasts only roughly 1-3 seconds before cursor advancement.
This run does not validate the later 15-45 second dwell implementation. A matching
deployment and process restart are required before testing that implementation.

## Encounter reconstruction

| Log time | Observed server behavior |
| --- | --- |
| 23:18:57.825 | HUNK begins engagement against zombie 28184. |
| 23:18:58.836 | Spectre also begins engagement against 28184. |
| 23:19:00.747 / 23:19:01.553 | HUNK and Spectre's first recorded shots miss. |
| 23:19:02.156 | HUNK's combat is cancelled with PREEMPTED; survival is selected. |
| 23:19:02.159-23:19:02.183 | HUNK counts four close zombies and Vector five; both start escape routes at full health. |
| 23:19:02.966 | Spectre's second shot kills 28184: health 1.0 to 0.0. |
| 23:19:03.167 | Spectre also enters survival with four nearby zombies and full health. |
| 23:19:11.424 / 23:19:13.546 | Spectre and HUNK return to SAFE. Vector continues fleeing. |
| 23:19:16.771 | Group enters REGROUPING at waypoint index 7. |
| 23:19:36.816 | Group becomes STALLED at the same waypoint. |
| 23:19:46.892-23:19:46.893 | HUNK/Spectre checkpoints are near Y=2402.56/2402.70; Vector is at Y=2516.75. |
| 23:19:50.425 | Vector's movement fails with scope_or_origin_unavailable at X=9849.5, Y=2525.018; occupancy is unloaded. |
| 23:19:50.425-23:19:50.426 | Vector reports CORNERED then RECOVERING, health=1.0, endurance=1.0. |
| 23:20:12.362 | Shutdown checkpoint retains Vector at Y=2525.018. |

Vector has 156 logged survival transitions in the 23:19 minute: 77 ESCAPING,
78 RECOVERING, one CORNERED. For comparison HUNK has 18 and Spectre eight.
Example: Vector becomes RECOVERING at 23:19:03.264 with two nearby threats,
then starts ESCAPING again at 23:19:03.369 with four. Similar cycles continue
until streaming fails. There is no confirmed NPC death or accepted bite in this
evidence. A visible bite could be a client presentation event without accepted
server damage; that remains unverified. The last server survival health is 100%.
Vector's final Y is about 53 tiles beyond the authored southern patrol bound.

## Source findings

### Survival oscillation and separation

[`SurvivalModule`](../../src/zombie/apocalipse/humans/survival/SurvivalModule.java)
immediately cancels escape movement on a single `controlled` observation and
sets `nextAttempt=now`. Another pressured observation can immediately start a new
route. The safe-hold/minimum-retreat timers gate return to SAFE, but do not prevent
this ESCAPING/RECOVERING churn. Default pressure starts at four zombies within
four tiles. This explains the logged policy transitions; it does not prove why
the measured nearby count changed so quickly.

[`ThreatAssessment`](../../src/zombie/apocalipse/humans/survival/ThreatAssessment.java)
accepts PARTIAL zombie coverage as reliable when fresh and not truncated.
[`ServerHumanPerception`](../../src/zombie/apocalipse/humans/ServerHumanPerception.java)
shares bounded discovery and keeps known contacts, but an incomplete discovery
frame is not proof that the local area is clear. Further diagnostic work should
record coverage, observer coordinates, and threat identities/positions for each
transition before attributing count changes to a specific sensing bug.

[`ServerHumanSurvival`](../../src/zombie/apocalipse/humans/ServerHumanSurvival.java)
plans emergency routes outside patrol bounds. That is intentional personal-danger
behavior, but there is no squad separation cost in its retreat objective. Vector
can repeatedly choose routes away from his allies until loaded terrain ends.

### Why the others did not rescue him

They initially fought, then individual survival preempted combat. Once recovered,
they returned toward the patrol point while Vector escaped south.
[`ServerHumanGroups.attacked`](../../src/zombie/apocalipse/humans/ServerHumanGroups.java)
fans out authenticated **player** retaliation, not zombie pressure or a request
for cover. No group rescue/escort objective is implemented there.
[`HumanEngagement.GUARD_ZOMBIES`](../../src/zombie/apocalipse/humans/brain/HumanEngagement.java)
also requires both actor and target to be inside territory. Following Vector
outside the bounds would not by itself authorize attacks there. Survival's opening
defense uses the same engagement policy, another limitation to address explicitly
when designing emergency protection outside patrol bounds.

### Terrain and snapshots

[`ServerHumanMovement.update`](../../src/zombie/apocalipse/humans/ServerHumanMovement.java)
advances `resolved.speed * elapsed` using direct setX/setY plus collision and
occupancy checks. The condition model supports `terrainScale`, but current normal
inputs use one and the adapter does not sample native tree slowdown. Native target
[`IsoGameCharacter.calculateWalkSpeed`](../../decompiled/zombie/characters/IsoGameCharacter.java)
uses `IsoTree.getSlowFactor` when in trees; the NPC adapter bypasses that movement
calculation. This is a confirmed missing terrain input, not measured proof of the
reported client/server divergence.

Do not assume clients independently apply the same tree animation slowdown:
target [`IsoPlayer.calculateWalkSpeed`](../../decompiled/zombie/characters/IsoPlayer.java)
uses cached network walk/run speeds on clients. The actual rendered movement and
collision behavior needs a paired capture.

[`ServerHumanTransport.idle`](../../src/zombie/apocalipse/humans/ServerHumanTransport.java)
sends native reliable player updates with an actual position and bounded prediction.
Current factory code publishes at 500 ms heartbeats and immediately on turns or
movement presentation changes; movement predicts up to one second ahead.
Native [`NetworkPlayerAI.parse(PlayerPacket)`](../../decompiled/zombie/characters/NetworkPlayerAI.java)
updates prediction targets and realx/realy rather than unconditionally assigning
rendered X/Y. Reliable delivery alone therefore does not guarantee an immediate
visual correction. Server logs cannot show how far behind a replica remained.
Persistence checkpoint messages are separate from these network updates.

Current [`ServerHumanGroups.capture`](../../src/zombie/apocalipse/humans/ServerHumanGroups.java)
captures actual native player positions on the world thread; worker progression
requires matching-incarnation samples no older than 500 ms. The
[`coordinator`](../../src/zombie/apocalipse/humans/groups/GroupPatrolCoordinator.java)
checks all roster members' coordinates against the slot or center, with a two-tile
tolerance. It does not infer arrival from a saved checkpoint, predicted position,
or merely a successful movement result. This is the correct authority contract.
Client visual arrival can still lag that server barrier.

### State dialogue

[`SpeechEvent`](../../src/zombie/apocalipse/humans/speech/SpeechEvent.java) already
declares RETREAT, ALLY_ATTACKED, ALLY_DOWN and COMBAT_ENDED. The current
[`speech defaults`](../../src/zombie/apocalipse/humans/speech/HumanSpeechDefaults.java)
provide rules/lines only for attack-player, attacked-by-player, zombie-kill and
damage-taken. Survival transitions currently log only; declaring an enum does not
wire speech. The existing catalog/engine should own weighted EN/PTBR pools,
cooldowns and repeat suppression. Add lines such as "I'm surrounded! Cover me!"
and "Estou cercado! Me deem cobertura!" on entry to a retreat episode, not on every
replanned route. Speaker names/ally names need explicit supported context; avoid
hardcoded checks for these three NPCs.

## Recommended implementation order

1. Deploy the already-built dwell implementation and restart before evaluating
   waypoint pauses. Keep actual server coordinates as the arrival authority.
2. Add retreat exit hysteresis: a sustained, sufficiently observed relief period
   before cancelling escape. Preserve immediate defense and low-health admission.
   Log position/coverage/threat facts to distinguish real crowd motion from sensing
   instability. Test oscillating counts and incomplete observations.
3. Introduce detached, bounded group danger/support observations and explicit
   support/cover or coordinated retreat policy. Revalidate individual targets,
   line of attack, identity and emergency territory rules in existing adapters.
   Do not simply force every ally to chase or suppress survival.
4. Capture terrain factors through an owning world adapter, feed the same resolved
   speed to server movement, animation and packet prediction, and measure replica
   error over trees/turns/stops. Avoid side-effectful native world calls on the NPC
   worker; respect the existing world-thread snapshot contract.
5. Wire state-entry speech through existing pool delivery with one event per
   retreat episode and cooldowns. Add recovery/ally-danger lines separately from
   gameplay support decisions. Chat text must not become the coordination channel.

These are recommendations, not shipped fixes. No new concurrency guarantees are
claimed. Runtime validation should include both NPC startup modes, seven zombies,
three separated allies, loaded-edge retreat, terrain movement, interrupted dwell,
and paired server/client positions. This documentation-only investigation does
not require Java compilation.
