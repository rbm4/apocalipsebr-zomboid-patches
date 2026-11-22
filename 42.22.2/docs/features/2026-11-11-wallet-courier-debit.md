# Wallet courier debit and item custody bridge

Status: implemented server bridge; backend endpoint and live validation pending.
Created 2026-11-11. Target: server 42.22.2. Uses the existing generic API bridge;
no client patch, backend endpoint, database schema or deployment is introduced.

Persistence correction: [entity checkpoints](2026-11-11-wallet-entity-persistence.md)
now replace native GlobalModData writes through the required ApocalisebrPack
facade and game_runtime.mod_data mappings. The earlier native save-facade attempt
is superseded; current wallet persistence requires the updated entity contract.

Update 2026-11-11: `CHARACTER_CREDIT` now supports seller settlement for the
[player market](../../../../ApocalipseBR-ExtractionMapUtils/.docs/features/wallet-player-market.md).
It resolves the same authenticated identity/query contract to `/credit`, uses the
same bounded JSON mutation body (`amount`, `reason`, `idempotencyKey`) and requires
the same balance/transaction/key response. The market sends reason `market.sale`
and retains unpaid obligations through retries/restarts, including offline sellers.
The user reports the debit endpoint implemented; the credit backend must implement
atomic idempotent addition. No backend code was changed here.

`ApocBrApiBridge.CHARACTER_DEBIT` resolves an authenticated POST to
`/api/v1/game/characters/{runtimeUuid}/debit?sourceServerId={id}&steamId={id}`.
The three identity fields follow CHARACTER_BALANCE/CHARACTER_LOGIN validation.
The validated JSON body contains `amount` (integer 1..1,000,000,000 wallet units),
`reason` (1..128 ASCII ledger characters) and `idempotencyKey` (1..160 ASCII
identifier characters). URLs and credentials remain Java-owned. GET/bind requests
retain their existing no-body semantics. Only debit gains a JSON request body.

Successful responses require finite numeric `balance`, nonempty `transactionId`
and `idempotencyKey`; the latter identifiers are bounded and validated before
exposure to Lua. Existing callback correlation, one worker, bounded admission,
HTTP deadlines and server-thread callback ownership are unchanged. The dispatcher
transports immutable body strings. It does not retry; durable wallet Lua owns
reconciliation using the same key/body after uncertain results.

Backend must atomically debit and persist an immutable idempotent result before
returning success. Identical replay returns the original transaction; conflicting
amount/reason yields 409. No engine item reference is part of this contract.
The complete contract, error policy and Lua interfaces are maintained in the
[wallet courier guide](../../../../ApocalipseBR-ExtractionMapUtils/.docs/features/wallet-courier-delivery.md).

Server-thread-only `encodeItem(InventoryItem)` and `decodeItem(String)` delegate
to package-private `CourierItemCodec`: base64 of the native world version followed
by `InventoryItem.saveWithSize`, decoded with `InventoryItem.loadItem`. Payloads
are bounded to 1 MiB, malformed/future-version/trailing data is rejected. The API
facade checks the existing owning bridge thread before codec access. Blobs are
server custody data, not accepted from client commands. They are native-format,
world-dictionary-dependent saves, not portable generic backend item JSON. Calling
decode twice creates two instances; the caller must enforce single consumption.

Persistence is owned by wallet Lua GlobalModData and native world/inventory
saves. These files are not one atomic transaction; the implementation does not
promise exactly-once physical item survival after arbitrary JVM/power failure.

Verification 2026-11-11:

- `py -3 tools/test_api_bridge.py`: 105 assertions using production API classes,
  real Kahlua, loopback HTTP, debit validation/body transport/receipt decoding and
  malformed/oversized codec rejection. No real backend requests.
  Includes credit route/method/body ownership checks.
- `42.22.2/patchApocalipseBr.ps1 -BuildOnly -ArtifactDir
  42.22.2/artifacts/courier-2026-11-11`: passed, 371 Java sources, 1062 classes,
  installed exact game JAR, JDK 25.0.4. Artifacts retained; nothing deployed.
  Repeated after the credit addition at
  `42.22.2/artifacts/market-radio-2026-11-11`: 371 sources / 1062 classes passed.
- Wallet Lua tests cover batching, offline fulfillment, receipt replay and debit
  success/failure gating. Engine/world boundaries are mocked.

Pending: native customized-item codec roundtrip in the actual runtime, client
rendering, paused-server destination streaming and backend integration.
