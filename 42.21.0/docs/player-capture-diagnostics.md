# Player capture failures and replay rejection (42.21.0 server)

## Observed failures and scope

The October 17 server logs exposed two distinct failure paths: live snapshot copy
could throw a null pointer or attempt `HandWeapon.iterator`/`Clothing.iterator`,
and detached journal replay could reject a graph with SQLState `22000`.
The merged typed modData reference codec fixes the item-as-table routing. The
remaining null-pointer logs did not contain a stack or field path, and the replay
logs did not expose the graph's issue codes. They cannot establish which additional
codec or invalid state caused those failures.

The diagnostic changes identify these remaining failures without treating an
incomplete capture as valid. No additional codec is inferred from a bare null
pointer. Equipment-only and duplicate-item reference ownership rules remain as
documented in [the connector guide](../POSTGRESQL-CONNECTOR.md).

## Resulting diagnostics and owning code

- `ApocBRPlayerRelationalSnapshot` attaches bounded `CaptureFailure` context around
  projection fields, sequence/map values, structured values, inventory items and
  the player snapshot. `captureContext` contains static schema/field paths,
  collection indexes, traversal depths and runtime types; the original exception
  remains the cause. Size-budget exceptions retain their existing type and metrics.
- `ApocBRPlayerRelationalMutations.writeFailure` formats cause classes, controlled
  capture codes and six source locations per cause, for at most four causes. Live
  scalar and graph failures use this formatter under the existing rate limit.
  Unknown exception messages, including uppercase JDBC messages, are not logged.
- `ApocBRPlayerRelationalCapture.requireComplete` carries the issue count and up to
  eight distinct sanitized issue codes in `IncompleteGraphException` (SQLState
  `22000`). `ApocBRPlayerRelationalDatabase.applyFields` uses stage `live_fields`;
  `ApocBRPersistenceOperations` uses `initial_capture` for initial journal replay.
  Rejection occurs before publishing an incomplete graph.
- `ApocBRPersistence` logs `REPLAY_FAILED` once per failed head entry, including
  operation UUID, kind and lifecycle phase. `REPLAY_DISCARDED` retains the final
  failure details. Successful acknowledgement clears the stale exception class
  from later periodic progress reports.

Example rejection fields:

```text
SQLState=22000; stage=live_fields; graphIssueCount=1; graphIssues=UNSUPPORTED_ITEM_SUBTYPE
```

Use the operation UUID to correlate first failure, deferred replay and discard.
For null pointers, inspect the cause's source frames and `captureContext` to
identify the owning field/type before changing its codec. For graph rejections,
inspect `graphIssues` and the corresponding native loader/component save contract.
`UNCLASSIFIED_CAPTURE_ISSUE` means an issue string was not a bounded uppercase code;
the original string is intentionally omitted.

No configuration, permission, schema, migration or journal-format changes are
required. Capture errors still prevent snapshot publication, and incomplete graph
rejections preserve the existing startup/shutdown retry budget. See
[retry behavior](persistence-shutdown.md). These diagnostics do not recover already
discarded operations or establish that the earlier null-pointer cause is fixed.

## Validation

`tools/test_postgres_moddata_references.py` injects a nested Kahlua iterator null
pointer and verifies inner/outer context, original cause/source location, absence
of modData keys and exception values, and successful capture after correcting the
input. Existing reference/journal/identity regressions remain covered.

`tools/test_postgres_player_relational.py` verifies actual live graph rejection,
stage/issue diagnostics, sanitization and existing snapshot/worker behavior.
`tools/test_postgres_resilience.py` verifies first-failure correlation, discard
details and retry behavior through the real coordinator with temporary journals
and fake JDBC. Full patch compilation checks game-JAR compatibility. Confirm the
remaining production failures with a fresh log after deployment; no live commands
or database writes are required for these isolated checks.
