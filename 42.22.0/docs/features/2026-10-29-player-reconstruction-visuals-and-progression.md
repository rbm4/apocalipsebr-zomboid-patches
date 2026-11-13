# Reconstruction: item RGB, visual finalization, descriptor and XP baselines

Implemented 2026-10-29 for 42.22.0 server, following priorities 3 and 4 from the
[native-load matrix](../reports/2026-10-29-native-player-load-reconstruction-matrix.md).
Extends [component/equipment lifecycle](2026-10-29-player-reconstruction-lifecycle.md)
and [weight persistence](2026-10-29-player-item-weight-roundtrip.md). No deployment
or production data changes were performed.

## Separate RGB and visual finalization

Native items have independent `col` RGBA, `colorRed/Green/Blue` and ItemVisual tint.
The existing relational RGBA columns do not represent all three. Snapshot now
detaches raw RGB into optional `Node.itemRgb`, with a capture-budget charge.
New journal records serialize the array; old records deserialize it as null.

Capture writes a synthetic `item.rgb` component containing `r`, `g`, `b` through
the existing structured value tables. It is emitted for custom-color items or
nonwhite raw channels. Ordinary white/non-custom channels need no extra rows.
Each present RGB record adds six rows: object, component, map root and three
numeric values. Channels must be finite and within [0,1]. No DDL, table permissions
or schema-version changes are needed. This is not a native GameEntity component.

Reconstruction initializes a fallback after item scalar projection and restores
explicit RGB after entity state. It then runs `synchWithVisual()` for each saved
item after components and before equipment/final player callbacks. Native code
can now update icon/model selection, clothing/bag color and clothing blood/dirt
aggregates from the restored state. Native synchronization may normalize `col`;
the objective is native derived-state behavior, not retaining inconsistent caches.
The later [subtype/health implementation](2026-10-29-player-reconstruction-subtypes-and-health.md) resets clothing's wetness timestamp to the load-time baseline (priority 6).

For old SQL/journal data without RGB, custom-color items use the known `col` RGB
as a best-effort fallback; non-custom items start with white raw channels and
native random-tint clothing uses its saved visual tint. This avoids bleaching
known old colors. It cannot reconstruct historically different col/RGB values.
Explicit custom white is stored even when col differs, so new data is unambiguous.
Existing mutation deltas detect RGB-only changes and delete no-longer-needed RGB
rows on transition to ordinary white/non-custom state.

Schema/codec remain 1/4. Capture and reader must ship together: older executable
reconstruction rejects `item.rgb`, as it rejects other unknown structured kinds.
The normal mutation cadence, source provenance, budgets and rejection policy stay
in effect. Historical SQL rows are not rewritten by this source change.

## Progression and descriptor identity

`restoreProgressionBaseline` runs locally after graph coverage and hand-owner
restoration, before final player callbacks:

1. Reproduce native XP.load's total-XP threshold check: when total XP exceeds the
   next legacy character-XP-level threshold, use the current-level threshold.
   Valid in-range totals, perk XP, perk levels and saved boosts remain unchanged.
   This is the native legacy XP.level check, not a recalculation of every skill.
2. Clear the checker's XP, descriptor-boost and multiplier maps, then call native
   `updateXpChecker()`. Its implementation uses putAll; clearing removes stale
   constructor entries that otherwise survive. The checker is required, matching
   the native load assumption. Missing native state fails reconstruction.

No LogLevelPerk replay is added. This is an explicit synthetic-load policy for
both temporary and authoritative paths: restoring existing levels does not
generate a new gameplay level gain or replay Lua hooks. Ordinary future gameplay
events are unchanged. A mod that relies specifically on load-time LogLevelPerk
requires a separately designed, once-per-authoritative-login hook policy; native
byte/event-for-event equivalence is not claimed.

The restored descriptor's ID is validated during reconstruction. Negative IDs and
Integer.MAX_VALUE are rejected, avoiding an unrepresentable next ID. Temporary
client-profile and migration-verification copies do not publish restored IDs.

After successful relational tracking attachment, ServerPlayerDB calls
`publishDescriptorIdentity`, then promotes equipment. Descriptor publication:

- Inserts the authoritative descriptor into `IsoWorld.survivorDescriptors` using
  the native saved ID and native map replacement semantics.
- Advances `SurvivorDesc.idCount` past that ID when necessary. The constructor
  allocates `idCount++`, so the correct next-allocation watermark is saved ID+1.
  This intentionally improves native load's saved-ID-only assignment, which can
  leave the immediately next allocation using the restored ID. Lower restored
  IDs never rewind the counter.

These remain simulation-thread operations. This is not a cross-server UUID
namespace migration or a redesign of native concurrent descriptor allocation.
Registry cleanup during normal disconnect/replacement retains its existing
ownership; the change does not add random doStats replay or overwrite saved
progression with a newly generated character.

## Verification

- `python tools/test_player_reconstruction_visual_progression_42_22.py`: **143 checks**,
  including the existing 56 lifecycle checks. Compiles complete target reconstruction
  against the matching installed JAR. Exercises RGB-only structured SQL deltas and
  reconstruction, independent col/RGB/tint, custom white, historical fallback,
  native clothing tint synchronization and blood/dirt aggregate recalculation,
  invalid channels, raw owner-thread capture and Java journal serialization.
- XP tests compare threshold correction against the exact target native branch
  with controlled threshold inputs, then execute native checker population and
  verify stale entries disappear. Descriptor tests verify lookup, next-ID watermark,
  lower-ID behavior, invalid-ID rejection and temporary nonpublication.
- Asset lookup is supplied by a clothing asset/visual fixture; texture loading,
  rendering, random model selection and full player callbacks remain native runtime
  boundaries. JDBC transport is the existing in-memory statement/binding fixture;
  a real PostgreSQL server is not started.
- `python tools/test_player_weight_roundtrip_42_22.py`: **1,763 checks**. Adds full
  Snapshot -> serialized mutation queue -> Capture -> Delta SQL -> reconstruction
  coverage for independent RGB/RGBA, RGB-only changes and RGB-record deletion.
- `python tools/test_sprite_config_42_22.py`: **17 checks**. Shared target item
  doubles now include the newly captured raw channels.
- `python tools/test_postgres_resilience.py --version 42.22.0`: passed, main
  691 checks plus auxiliary startup/shutdown/missing-schema cases.
- `42.22.0/patchApocalipseBr.ps1 -DryRun`: passed, **303 sources / 895 class files**
  against the installed world251 target JAR. No deployment. Compilation does not
  establish live client/rendering correctness.

## Remaining priorities in practical terms

**Priority 5 — subtype initialization and caches.** Some objects need more than
their saved fields: a Moveable's sprite establishes its grid, name and drop rules;
a Radio needs its correct device parent; a weapon needs factory-only parts removed
before saved attachments are restored and magazine flags checked against the
current definition. Map rotation requires cosine/sine cache updates, entity script
identity needs its resolved script pointer, and a saved UI skin needs its resolved
asset. Tests should deliberately make factory defaults differ from saved state.
These changes remain open; calling the general visual synchronizer does not close them.

**Priority 6 — health and time baselines.** Native body load updates its previous
body-part snapshot, which later simulation compares with current injuries. Clothing
load resets its wetness-update clock to the current world hour; retaining an old
clock can change the next wetness/weight update. Food has special lastAged=-1 and
corpse-icon initialization. Nutrition and animal setters may clamp values or trigger
effects, so blanket setter invocation could duplicate damage/events on temporary
players. Compare the first post-load simulation tick, not only saved field equality.

**Priority 7 — saved empty versus absent versus current defaults.** A saved empty
recipe list should not accidentally inherit factory recipes; an absent old-codec
field may legitimately retain a current default. Lists, maps, visuals and optional
components need explicit per-field semantics. Component membership now has such
a marker, and RGB has a documented fallback. That does not yet solve all remaining
collections. Tests need nonempty constructor/mod defaults and empty saved state,
not merely objects whose constructors already produce empty collections.

Cross-cutting work also remains: embedded animals and opaque/modData payloads,
missing-content migration, and the stock client-profile normalization boundary.
Server reconstruction fidelity does not prove identical client values after native
profile decoding. See the matrix's open tracks and the weight guide's runtime limits.
