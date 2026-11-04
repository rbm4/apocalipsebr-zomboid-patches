# Lobby cleanup finishes before virtual zombies appear

Status: source fix and regression/build passed 2026-11-04; live retest pending.

User reported CLEANED and COMPLETE for 24/24 chunks, removed=0, followed by zombies
inside the lobby on login. The [streaming bridge](../features/2026-11-04-lobby-startup-streaming.md)
previously loaded only server geometry. Exact-version LoadedAreas.set(false)
normally describes real-player/connection population activation areas; forced
server-cell relevance is a separate list. IngameState also skips population
updateMain while paused. Thus waiting for ServerCell.isLoaded and five seconds
could complete without realizing virtual population. This source explanation
matches the symptom; no native population trace from the affected server is available.

Fix: copy the 142-line exact-version LoadedAreas baseline and add a server-only
hook after its native area collection. The companion bridge appends one temporary
cell area, publishes it, pumps the paused population consumer and returns native
readyToPause. The mod resweeps until readiness and a quiet interval after removals,
and releases both population activation and frame relevance afterward. Activation
does not mutate real players, save formats, native worker scheduling or game time.
No broad debug population-clear command is used. Changes are server-only; world
mutation remains on the server thread. Existing loaded players/areas are preserved.

Regression: the mod fixture supplies an empty first sweep, keeps population pending,
then materializes a zombie. The old code completes before it appears; revised code
removes it and waits for readiness/quiet verification. Permanent pending work times
out as FAILED and releases requests. Old bridge versions fail visibly.

`py tools/test_lobby_streaming.py` passes 24 production bridge/LoadedAreas checks
with engine doubles. The 42.22.1 patch script's actual -BuildOnly compilation passes:
310 sources, 911 classes, retained at artifacts/lobby-population-fix-20261104.
`tests/lobby-cleanup.lua` in ExtractionMapUtils passes. Native JNI realization,
pause readiness semantics under load, first login, unloading/saving and reload still
need a dedicated-server retest. The build includes other existing working-tree
patches. No installed game files or live server were changed.
