# Client zombie movement capture — 42.21.0

Opt-in diagnostic capture for a playable multiplayer client. Dedicated servers
and single-player sessions do not collect anything: entry points check
`GameClient.client`. Production server-controlled movement and packet formats
are unchanged. The production clients receiving those packets need no sampler.

## Build and install on the diagnostic client

From the repository root:

```powershell
python -B tools/build_zombie_sampler_client.py
```

The tool compiles temporary copies of the exact vanilla 42.21.0 classes with
observational hooks and the separate `ClientZombieSampler` class. It verifies
the game JAR against recorded vanilla class hashes. It never ships the server
NPC/persistence patches and never installs anything. The archive is written to
`%TEMP%/apocbr-zombie-sampler-client.zip`; override with `--output <path>` or
`--game-jar <path>`.

Fully close the diagnostic client. Back up any existing loose overrides named
in the archive's `manifest.json`. Copy the contents of the archive's `classes/`
directory to the client classpath root (normally beside `projectzomboid.jar`).
Use the existing patched-client launch configuration with loose classes ahead
of the JAR. Do not copy server patch output onto the playable client.

Add these JVM arguments to the client launch configuration:

```text
-Dapocbr.zombieSampler.enabled=true
-Dapocbr.zombieSampler.maxBodies=1
-Dapocbr.zombieSampler.seconds=120
-Dapocbr.zombieSampler.maxMiB=64
-Dapocbr.zombieSampler.role=both
```

Join multiplayer. Capture begins when an eligible zombie is within 20 tiles
and on the local player's floor. Look for `[ApocBR][ZombieSampler] Capturing to`
in the client log. Output defaults to the game cache's `zombie-captures/`,
usually `C:/Users/<user>/Zomboid/zombie-captures/`.

Optional arguments:

| Suffix after `apocbr.zombieSampler.` | Default | Meaning |
| --- | --- | --- |
| `enabled` | false | Must be explicitly true. |
| `maxBodies` | 4 | Maximum tracked native bodies, clamped 1–16. |
| `seconds` | 120 | Duration from first capture; 5–1800 seconds. |
| `radius` | 20 | Same-floor radius; 1–100 tiles. |
| `onlineId` | -1 | Restrict to this zombie ID; -1 selects automatically. |
| `role` | both | `owner`, `remote`, or `both`. |
| `directory` | cache/zombie-captures | Output directory. |
| `maxRows` | 200000 | Data-row cap; 100–2000000. |
| `maxMiB` | 32 | Data-byte cap; 1–256 MiB. |

Capture also stops at its row/byte cap. A bounded 4096-row queue drops data
instead of blocking simulation. The summary reports written and dropped rows.
Files flush approximately every second and close on normal JVM shutdown.
An abrupt termination may lose the unflushed tail. Restart the client for another
capture or changed JVM settings. Disable the property afterward, or remove only
the files listed in the manifest and restore the backed-up overrides.

## What to capture

Start with one zombie, unobstructed ground, and one real player owning it. Include
straight walking, start/stop, turns, close pursuit/lunge, an obstacle, a weapon
hit and knockdown. Repeat with different configured zombie speed types. Use
another observing client for remote reconstruction if available; a server-owned
zombie is already remote to every client. Record the server's sandbox speed
settings and whether server NPC control was enabled alongside the capture.

For a native gait reference, keep NPC zombie control disabled on the test server
(`-Dapocbr.npc.zombieControl=false`) or capture away from NPCs. Then repeat with
server control enabled. Installing the sampler does not change ownership.

Bring back the `.ndjson` files and those scenario notes. No client usernames or
credentials are included. Coordinates and native entity IDs are recorded.

## Schema and hook ownership

Metadata includes build, schema and UTC start. Every body event includes a local
monotonic timestamp, sequence, online ID, instance ID, capture generation,
owner/remote role, native AI state, gait, speed modifiers, position, facing and
simulation/wall/animation deltas. Generations separate observed pooled identity
changes. `sampleUpdate` groups observations between completed post-updates of
that selected body; it is not a global renderer frame number. Timestamps are local to a process, not measurements of one-way network
latency between unsynchronized machines.

| Event | Exact hook and meaning |
| --- | --- |
| `root_read` | After an existing `IsoGameCharacter.getDeferredMovement` read. `consumed` identifies the original reset argument. Reads are not independent travel and must not be summed. No extra root-motion read/reset is performed. |
| `move_requested` | Entry to `IsoZombie.moveUnmodded`, before native sprinter clamping and remote correction. |
| `remote_correction` | After native remote movement scaling, including its exact multiplier. |
| `move_applied` | Before the original `super.moveUnmodded` call; the vector after native corrections. |
| `post` | After the complete original zombie post-update. Includes actual travel since the previous post, collision/separation flags, real/network positions, animation state and up to eight active animation tracks with time, duration, speed and blend weight. First-post displacement is explicitly marked. |
| `animation` | Selected original attack, completion and physical-state animation events before dispatch; no events are synthesized. |
| `packet_out` / `packet_in` | Actual zombie serialization/parsing, with real/predicted coordinates, prediction type, state, direction, gait, flags, target, path-goal kind and health. Unknown/unselected zombie IDs are omitted. |
| `receive_before` / `receive_after` | Around original `NetworkZombieAI.parse`, showing reconstruction error and immediate position correction. |
| `batch_encode` | Native owner-upload transport choice and zombie count before original serialization. This is an encode timestamp, not proof of network delivery. |
| `upload_cadence` | Native server-selected neighboring-player mode and resulting 200/4000 ms upload period. |
| `ownership` | A selected body changes owner/remote role during a `both` capture. |

Track movement lengths are native world/tile units. Animation delta is scaled
animation time; wall and simulation deltas remain separate. Fit gait using
unobstructed owning-client samples. Compare requested/applied/actual displacement
to identify correction, turning, collision and separation effects. State changes
and clip phase provide transition timing; packet samples provide the distinct
wire cadence and goal semantics.

`tools/test_client_zombie_sampler.py` validates bounded asynchronous output and
hook contracts. Build the client archive against the real game JAR before use.
Actual multiplayer playback and capture remain runtime checks.
