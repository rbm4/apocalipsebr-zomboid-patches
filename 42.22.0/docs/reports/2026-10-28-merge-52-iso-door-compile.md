# Follow-up review 52: IsoDoor decompiler compile repair

Created2026-10-28. Status: **approved/applied; gameplay/runtime unverified**. Review51 is applied.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-52-iso-door-compile.diff),
[verification record](2026-10-28-merge-52-iso-door-compile.json) and
[full build checkpoint](2026-10-28-build-check.md).

The full303-source compile against the installed42.22/world251 JAR found an
IsoDoor error: switch has both an unconditional pattern and a default label.
The target decompiled getBaseForceOpenChance switch selects GameEntityScript,
contains case GameEntityScript, explicitly handles null, and then has an impossible
default arm throwing MatchException. Java25 rejects that redundant default arm.

Remove only the two-line default/throw arm. The GameEntityScript case still tests
all door entity keys and falls through to property checks when none match. Keep
the explicit null case, metal-door sound fallback, burglar modifier, garage-door
branch and every chance value. No custom door optimization changes.

Extend [the existing review21 source](../../src/zombie/iso/objects/IsoDoor.java);
do not copy vanilla again. This defect also appears in the exact
[target decompiled method](../../decompiled/zombie/iso/objects/IsoDoor.java), which
remains unchanged. It is a decompiler source repair, not evidence of a shipped
vanilla gameplay bug.

Verification: candidate applies cleanly. A second full303-source Java25 compile
with only this candidate overlaid removed the IsoDoor diagnostic and left three
errors in ServerHumanTransport and ServerZombiePath. The build still fails;
this does not certify the entire IsoDoor class or runtime. No lockpicking/gameplay
execution or new isolated test was added for the redundant-arm removal.

Decision: applied on2026-10-28 after user approval. Exact two-line candidate
equality passed. The applied source equals the previously compiled overlay;
review53 now proposes the transport adaptation, followed by the path API. Historical-data migration planning remains
deferred while bringing the target source to a viable runnable state, as requested.
No installed game files, live database or production configuration changed.
