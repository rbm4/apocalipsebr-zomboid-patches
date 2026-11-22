# Review 46/48: LuaManager

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review45 is applied. This one-class patch is also applied after baseline aca4626. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-46-lua-manager.diff) and
[verification record](2026-10-28-merge-46-lua-manager.json).

## Old patch intent and reconstruction repairs

Compared with [42.21 decompiled vanilla](../../../42.21.0/decompiled/zombie/Lua/LuaManager.java),
the [old patch](../../../42.21.0/src/zombie/Lua/LuaManager.java) makes three changes:

- Preserve the normalized requested filename before physical resolution and use
  that key for loaded/loadedReturn writes, matching cache reads. Keep physical
  paths for IO, compiler context and filenameToClosure.
- Expose the existing ApocBREventControl, ApocBRRadioBridge and ApocBRPlayerLogin
  Java classes to Lua only when GameServer.server is true.
- Repair decompiled Java that reuses a File local as an ArrayList in LoadDirBase,
  plus four ambiguous Logger.log(...,null) overload calls. Use typed modFiles and
  explicit (Object)null, as in the old source patch.

These include **source reconstruction repairs**, not evidence that the shipped
engine has the same defect. The old test_lua_manager_cache.py contains a separate
installed-JAR bytecode check documented for the prior build. This review's fixture
does not run that check and has no verified 42.22 bytecode equivalence evidence.

## Target baseline and cache contract

[42.22 decompiled LuaManager](../../decompiled/zombie/Lua/LuaManager.java) still
contains the File/List reuse, ambiguous logging calls and cache read/write key
mismatch in its reconstructed source. The candidate applies the same limited
repairs to that target, retaining all upstream bindings and signatures, including
updated Hit/climb/door exposures, action transaction methods and local-player
item synchronization guards. It does not rebuild the class from the old version.

The candidate saves requestedFilename after slash normalization and before
ZomboidFileSystem.getString resolves the physical path. loaded and loadedReturn
use requestedFilename together. Otherwise a repeated logical require whose file
resolves elsewhere can execute again in this reconstructed source, replacing a
module's globals and erasing dependent initialization. The fixture reproduces that
WorldGen/Features/Biomes dependency pattern with a resolving filesystem double.

Native recursion tracking, physical closure indexing, dirty flags, Lua calls and
failure flow remain. Nil/runtime-error returns retain their existing caching
behavior; missing files remain uncached. Requested-key invalidation allows a
fresh execution/return. Distinct logical/absolute aliases remain distinct keys:
this is not a canonical-path deduplication system or a new invalidation policy.
Native compile-error/rewrite-flag and global reload-function behavior is not
redesigned here. No benchmark or runtime speedup is claimed.

## Server API registration

Only this existing block is added after native TradingState exposure:
GameServer.server -> setExposed(ApocBREventControl,ApocBRRadioBridge,ApocBRPlayerLogin).
The following native exposeLikeJavaRecursively loop remains unchanged.
Clients gain no new class registration from this block.

The three support sources already exist in the staged target. This registers
interfaces, without modifying their implementation or authorization/data flow.
Real Kahlua registration, event polling/JDBC, radio publishing, login movement and
callbacks still need their own target integration. In particular, copied event
control registration metadata still names 42.21.0/event-control-v2; that label
needs review alongside the actual feature, not an implicit change in this class.
GameServer/bootstrap work and remaining support metadata/reconstruction work stay
in scope. Exposure alone does not establish that those subsystems are ported.

## Verification

[Fixture](../../../tools/test_lua_manager_port.py): **33 assertions passed**:
**21** cache/dependency assertions and **12** load-order/exposure assertions.
It extracts the actual candidate RunLua wrapper/internal and LoadDirBase methods,
plus the added exposure branch. It reuses the previous cache harness without
executing its installed-game-JAR inspection.

Cache coverage includes normalized slash lookup, repeated requires, logical vs
physical cache roles, dependent module initialization, nil/error return handling,
recursive require cleanup, missing files, unexpected exceptions, requested-key
invalidation, distinct aliases and reset/reinitialization. Loading coverage keeps
base-game files first, case-insensitive sorting within each mod's common/version
files, mod order, exact-string duplicate suppression, checksum exclusions,
onlyChecksum behavior, load-list cleanup and server-only registration.

Filesystem resolution/discovery, Lua compiler/caller, checksums, dirty state and
exposure registration use doubles. Exposed API classes in that harness are name
markers; real support files are checked for presence, but API bodies/reflection
are not exercised. No full LuaManager/JAR compile, real Lua/Kahlua runtime,
complete reloadLuaFile/reloadServerLuaFile path validation, live mod environment,
JDBC, backend delivery or login behavior was executed.

## Decision

The user approved this one-class candidate. Exact native baseline aca4626 was
committed locally, then the reviewed diff applied. Applied-candidate equality and
33 assertions (21 cache, 12 loading/exposure) passed again. No deployment or
installed-file changes occurred; the integration limits above still apply.

Next is [review47: IngameState](2026-10-28-merge-47-ingame-state.md), a separate
unapplied telemetry-scope candidate. Full-class compilation and remaining
bootstrap/support/subsystem validation stay in scope.
