# Counted dictionary source pagination and failure diagnostics

Implemented in 42.22.2 on 2026-11-12; compiled and fixture-verified, not deployed
or benchmarked against the incident database. Addresses the read-size part of
the [dictionary timeout investigation](../issues/2026-11-12-dictionary-read-timeout-retry-amplification.md).
The [historical compatibility contract](../../../42.22.1/docs/features/2026-10-29-historical-dictionary-compatibility.md)
still governs source provenance and decoding.

## Loading behavior

[ApocBRWorldDictionaryDatabase.load](../../src/zombie/savefile/ApocBRWorldDictionaryDatabase.java)
is the shared reader invoked by dictionary listener startup/reconciliation and
by exact-view preparation on the player I/O path. Each uncached source load:

1. Verifies the exact ready server/namespace/view and supported build/world pair,
   and pins its identity revision. Reads the small registry header as before.
2. Counts eligible identity rows for that namespace through that revision, and
   overrides for that exact namespace/view. Counts use bounded subqueries capped
   at the existing budgets plus one: 200,001 entries / 1,000,001 overrides. They
   do not count every historical namespace/view in the database. Exceeding either
   existing budget fails before bulk page reads.
3. Calculates each stream's page size independently:
   `max(1000, ceil(rowCount / 10))`. Thus every nonempty stream needs at most ten
   data-page queries; small streams need fewer. An empty stream needs no data
   query. There is no extra end-of-stream probe after the known last page.
4. Loads ordered entry pages with keyset `(family, register_name, native_id)` and
   override pages with `(family, register_name, native_id, position)`. The cursor
   predicate is omitted on page one; subsequent pages resume strictly after the
   previous key. LIMIT shrinks for the last page. No OFFSET or new index/schema
   migration is required; these orders follow the existing primary keys after
   their namespace/view equality prefixes. Ordered overrides can cross pages
   inside one identity without losing their position order.
5. Requires the expected number of rows per page, validates the complete snapshot
   and commits. The decode cache installs only the completed snapshot. A missing
   row/state or any failed page rolls back and discards the partial local result.

Examples for one stream:

| Count | Page size | Data queries |
| ---: | ---: | ---: |
| 0 | 1,000 | 0 |
| 800 | 1,000 | 1 |
| 4,500 | 1,000 | 5 |
| 10,001 | 1,001 | 10 |
| 120,000 | 12,000 | 10 |
| 200,000 | 20,000 | 10 |

Every query, including counts, retains `setQueryTimeout(5)`. This is a per-query
limit, not a new 50-second total deadline. Entries and overrides have separate
page limits and there are additional metadata/count queries. Existing connection,
socket and workflow deadlines continue to apply. No sleep, login deadline change,
background executor change or retry policy change was introduced.

The existing ready-view immutability and append-only identities make these reads
stable without a new transaction isolation mode: future identity revisions are
excluded from counts and pages. Every page retains the original source view and
identity revision. The source dictionary is never replaced with the newest view
or the current engine's IDs. Work remains on the existing caller-owned I/O thread;
no partial state is handed to simulation or decoder threads.

Counting adds narrow queries; pagination does not reduce total rows read and
cannot guarantee a good PostgreSQL query plan or success under severe load. The
count itself can time out. No execution-time improvement has been measured yet.

## Observability

`SOURCE_READ_PLAN` logs exact source identity, counts, page sizes, planned pages
and the five-second query timeout after sizing succeeds.

`SOURCE_READ_FAILED` now covers acquisition and lease-return failures as well as
the in-transaction stages. It includes:

- Stage, total elapsed milliseconds, stage milliseconds and current page milliseconds.
- Current 1-based page, planned pages, page size, entry/override rows read and
  expected counts. Page fields are zero outside a paged stage; counts are -1
  until counted.
- Controlled reason categories for cancellation/timeouts, constraints, schema,
  permissions, connections and runtime failures, or allowlisted validation reasons.
- Structured PostgreSQL SQLSTATE, error code, primary message, schema, table,
  column, constraint and position. Nested causes and next SQL exceptions are
  traversed with cycle detection and an eight-error bound. Cleanup exceptions
  are included separately. PostgreSQL DETAIL/WHERE and raw wrapper messages are
  excluded; existing connector redaction still applies.

For example, an entry failure can identify `stage=READ_SOURCE_ENTRIES; page=2;
plannedPages=10; pageSize=1001; entryRows=1001; expectedEntries=10001` together
with `SQLState=23514; constraint=stable_key_check`. Acquisition failures identify
`stage=ACQUIRE_CONNECTION`; count timeouts identify `COUNT_SOURCE_ENTRIES` or
`COUNT_SOURCE_OVERRIDES`, rather than suggesting the bulk page failed.

[ApocBRWorldDictionaryDecode.prepare](../../src/zombie/world/ApocBRWorldDictionaryDecode.java)
logs the safe source failure context at PREPARE_FAILED, and preserves the SQLSTATE
and original cause when propagating errors. It no longer replaces every SQLSTATE
with 55000. Cancellation is classified as `QUERY_CANCELED_OR_TIMED_OUT`, since
57014 cannot prove whether JDBC's timeout or an external cancel caused it.

## Verification and remaining operations

Run the target fixture with an unsigned copy of the installed game JAR (the signed
original cannot mix patched and original classes in the same package):

```powershell
$env:APOCBR_TEST_GAME_JAR='Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid-apocbr.jar'
py -3 tools/test_postgres_dictionary_decode.py --version 42.22.2
.\42.22.2\patchApocalipseBr.ps1 -BuildOnly
```

The fixture compiles the production reader, snapshot, decoder/getters and actual
connector diagnostic methods. It exercises empty/small/exact/remainder pages up
to 200,000 entries, cross-register keys, multi-page override order, budget
rejection, incomplete state, count/acquisition/page/commit/return/cleanup failures,
SQLSTATE and constraint-field preservation, private-detail exclusion, and failed
partial-load retry followed by successful caching. JDBC is simulated; this is
not a PostgreSQL planner/performance or multiplayer runtime test.

Target fixtures and the full 42.22.2 BuildOnly passed with javac 25.0.4. Existing
login integration and one-second listener retry behavior are unchanged by user
scope. Verify actual SOURCE_READ_PLAN sizes, elapsed times, failures and query
plans after deployment; retry amplification remains open if a page/count still
fails repeatedly. No live database changes, installation deployment or commit
were performed by this implementation.
