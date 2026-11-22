# Merge review 05/48: SpriteConfig

Status: **approved and applied; source verification passed; target-JAR/runtime pending**. Reviews 01-04 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/entity/components/spriteconfig/SpriteConfig.java).
- [Old patch](../../../42.21.0/src/zombie/entity/components/spriteconfig/SpriteConfig.java).
- [Target vanilla](../../decompiled/zombie/entity/components/spriteconfig/SpriteConfig.java).
- [Applied diff](2026-10-27-merge-05-sprite-config.diff).

The user approved this merge with "yes, continue". The exact target vanilla
baseline was committed separately as `c2ad1d6`, then the approved diff was applied
to [target SpriteConfig](../../src/zombie/entity/components/spriteconfig/SpriteConfig.java).
The behavioral patch remains separate from the baseline commit; no push occurred.

## Upstream change and existing patch

42.22.0 changes `loadSyncData` to call `load(input, 251)` instead of
`load(input, 249)`. Keep 251 so sync decoding uses the target world format.

Our existing patch removes the DebugType import and three diagnostic emissions:

- Warning about an invalid SpriteConfig object before `resetObjectInfo()`.
- Error when no sprite-config script was saved.
- Error when the saved script cannot be resolved.

The reset, reads, script lookup and initialization remain unchanged. This patch
suppresses diagnostics; it is not a simulation or loading algorithm optimization.
It also removes those three messages as troubleshooting evidence. Retaining that
logging policy is the specific user decision for this review.

## Applied decision and verification

Preserve the new 251 sync-loader argument and retain the existing three-message
suppression. The candidate contains only the old diagnostic deletions against
target vanilla; it does not revert the upstream format change.

Source verification confirms the installed file matches the reviewed candidate
and equals target vanilla after deleting only the unused import and the three
approved diagnostic statements. The 251 sync-loader argument and all loader/reset
statements remain. No new gameplay fixture was run for these diagnostic-only
deletions. Target-JAR compilation and runtime sync/load checks remain integration
requirements; source verification does not establish complete-class linkage.

Next decision: [review 06, ItemContainer](2026-10-27-merge-06-item-container.md).
