# Wallet nil save call at the Lua boundary

Superseded resolution: the user requested [entity-backed wallet checkpoints](../features/2026-11-11-wallet-entity-persistence.md).
Current persistence uses PostgreSQL through ApocalisebrPack, not a native save
facade. The diagnosis and intermediate implementation below are historical.

Status: fixed in source; dedicated-server validation pending. Created 2026-11-11.
Owner: [wallet courier bridge](../features/2026-11-11-wallet-courier-debit.md).

The live trace reported Kahlua `Object tried to call nil` in CourierService.persist.
The exact build's `decompiled/zombie/world/moddata/ModData.java` is the Lua facade
registered by LuaManager; it has no `save` method. Native disk saving belongs to
`GlobalModData.instance.save()`. Earlier Lua-only stubs invented a save method and
therefore did not reproduce the integration failure.

`ApocBrApiBridge.saveModData()` is now the explicit server-Lua entry point. It
rejects a caller outside the initialized bridge's owner thread, rejects NoSave
mode, invokes the native save on that thread, and reports false on IOException or
runtime failure. It never enters the detached HTTP worker or exposes a file path
to Lua. No vanilla ModData class is replaced. Wallet Lua checks capability before
mutating inventory/payment state and retains stable intent IDs on recording errors.

Verification: `py -3 tools/test_api_bridge.py` passed 111 assertions. The harness
uses the production facade and real Kahlua, verifies the actual ModData class has
no save method, and stubs native disk I/O to test successful delegation, failure,
NoSave, client and worker rejection. Exact-JAR BuildOnly compilation passed with
371 sources / 1062 classes at
`42.22.2/artifacts/wallet-persistence-fix-2026-11-11`. No deployment/live save test
was performed. Deploy the updated server classes and wallet files together and
restart the JVM. See the
[wallet reproduction and recovery guide](../../../../ApocalipseBR-ExtractionMapUtils/.docs/hotfixes/wallet-native-moddata-save.md).
