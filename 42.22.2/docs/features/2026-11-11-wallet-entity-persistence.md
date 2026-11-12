# Wallet entity checkpoints

Status: implemented; isolated PostgreSQL and actual Kahlua tests passed; live
deployment pending. Target: server 42.22.2. Created 2026-11-11.

The wallet now requires ApocalisebrPack's existing `ApocBR/Entities` facade.
`ApocBRModEntities` adds owner-thread `serverScope`, bounded data-only JSON encode
and decode, and a `text` mapping type capped at 32 MiB Java characters. Existing
string limits and worker/CAS ownership are unchanged. JSON rejects cycles,
userdata, deep/excessive collections and numbers outside Lua's exact range.
Scope comes from configured engine server ID and native save-world, not a client.

One entity maps to `mod_data.wallet_mod_data` on the game_runtime datasource.
Manager V50 projects that revisioned checkpoint into nine market/custody/courier
tables in the SAME PostgreSQL transaction. Lua serializes operations and yields
at entity-save barriers; native item removal and payment dispatch continue only
after the worker's committed callback on the owning server Lua thread. This
replaces native `ModData.save`/`saveModData` persistence. Existing global wallet
data is read only for initial import if the SQL row is absent. The native item
codec remains for exact item custody; no HTTP API contract changes are needed.

Documentation and migration:

- [Wallet map and failure/recovery semantics](../../../../ApocalipseBR-ExtractionMapUtils/.docs/features/wallet-entity-persistence.md).
- [Manager migration and role permissions](../../../../apocalipsebr-manager/manager/docs/wallet-entities.md).

Validation:

- `py -3 tools/test_mod_entities.py --version 42.22.2`: 326 Java checks and Lua
  repository lifecycle/scoped read checks, including JSON and bounded text.
- `py -3 tools/test_wallet_store_kahlua.py`: 21 production store/pack/pickup
  checks in the actual game Kahlua runtime. The entity transport is a detached
  async test double; no live SQL is used by this harness.
- Manager WalletEntityMigrationTest: 2 PostgreSQL 17.6 tests, no skips/failures,
  using isolated backend/engine roles, Flyway and actual projection triggers.
- Full 42.22.2 BuildOnly: 371 sources / 1062 classes retained at
  `42.22.2/artifacts/wallet-entity-store-2026-11-11-validated`. Nothing deployed.

Apply V50, update the pack and wallet, and deploy these server classes together.
Native inventory/world files are not one transaction with SQL; uncertain physical
handoffs still require reconciliation rather than an exactly-once crash claim.
