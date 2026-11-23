package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.features.lua.LuaRuntime;
import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.random.RandLua;
import zombie.core.random.RandStandard;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/** Run in a fresh JVM with the original game JAR first and a disposable database. */
public final class LuaEntityOriginalSmokeMain {
    public static void main(String[] args) throws Exception {
        if (!LuaManager.class.getProtectionDomain().getCodeSource().getLocation().toString()
                .endsWith("projectzomboid.jar"))
            throw new AssertionError("Original LuaManager required");
        RandStandard.INSTANCE.init();
        RandLua.INSTANCE.init();
        ZomboidFileSystem.instance.setCacheDir(args[0]);
        ZomboidFileSystem.instance.init();
        GameServer.class.getField("mainThread").set(null, Thread.currentThread());
        initializeLua(); // Prove vanilla initialization still works on the client.
        if (LuaManager.env.rawget("ApocBRModEntities") != null)
            throw new AssertionError("Entity bridge leaked to client Lua");

        LuaEventManager.Reset();
        GameServer.server = true;
        initializeLua(); // Execute the production Byte Buddy advice before any mod code runs.
        try {
            if (args.length == 1) {
                lua("""
                    local b = assert(ApocBRModEntities)
                    local r = b.documentLoad('ApocWallet.Couriers.v2')
                    assert(not r.accepted and r.status == 'UNAVAILABLE')
                    assert(b.decode(b.encode({ value = 27 })).value == 27)
                    """);
                System.out.println("ORIGINAL_ENTITIES_DISABLED_PASS");
                return;
            }
            Path pack = Path.of(args[1]);
            Path wallet = Path.of(args[2]);
            setWorld("wallet-fixture-" + UUID.randomUUID());
            Object facade = lua(Files.readString(pack.resolve("Entities.lua")));
            Object protocol = lua(Files.readString(wallet.resolve("shared/ApocWallet/Protocol.lua")));
            Map<String, Object> imports = Map.of("ApocBR/Entities", facade, "ApocWallet/Protocol", protocol);
            LuaManager.env.rawset("require", (JavaFunction) (frame, count) -> {
                frame.push(imports.get(frame.get(0)));
                return 1;
            });
            lua(Files.readString(wallet.resolve("server/ApocWallet/EntityStore.lua")));
            lua(Files.readString(pack.resolve("BackendMessages.lua")));
            LuaEventManager.triggerEvent("OnServerStarted");
            await(() -> Boolean.TRUE.equals(lua("return ApocWallet.EntityStore.ready()")));
            lua("""
                assert(ApocWallet.EntityStore.revision == '1')
                assert(ApocWallet.EntityStore.run('fixture', function()
                    local wallet = ApocWallet.EntityStore.data()
                    wallet.jobs = { fixture = { amount = 17 } }
                    ApocWallet.EntityStore.persist('jobs', 'fixture')
                    walletCommitted = true
                end))
                """);
            await(() -> Boolean.TRUE.equals(LuaManager.env.rawget("walletCommitted")));
            awaitResult("""
                ApocBREntities.document('ApocWallet.Couriers.v2'):load(completed)
                """, """
                assert(result.revision == '2' and #result.records == 1)
                assert(ApocBREntities.decode(result.records[1].payload).value.amount == 17)
                """);
            awaitResult("""
                ApocBREntities.document('ApocWallet.Couriers.v2'):mutate(
                    '1', ApocBREntities.newMutationId(), {}, completed)
                """, "CONFLICT", "assert(result.revision == '2')");
            awaitResult("""
                ApocBREntities.document('ApocWallet.Couriers.v2'):mutate('2', ApocBREntities.newMutationId(),
                    {{collection='jobs', key='fixture', deleted=true},
                     {collection='jobs', key='invalid', payload='[]'}}, completed)
                """, "DATABASE_ERROR", "");
            awaitResult("ApocBREntities.document('ApocWallet.Couriers.v2'):load(completed)",
                "assert(result.revision == '2' and result.records[1].key == 'fixture')");

            setWorld("entity-fixture");
            scopedCollection();
            characterAndMessages();
            lua("""
                shutdownDocument = 'fixture-shutdown-' .. ApocBREntities.newMutationId()
                assert(ApocBRModEntities.documentMutate(shutdownDocument, '0',
                    ApocBREntities.newMutationId(), {}).accepted)
                """);
        } finally {
            LuaRuntime.shutdown();
            lua("""
                local r = ApocBRModEntities.documentLoad('shutdown')
                assert(not r.accepted and r.status == 'UNAVAILABLE')
                assert(#ApocBRModEntities.poll() == 0)
                """);
            if (Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("ApocBR-ModEntities")))
                throw new AssertionError("Entity worker survived shutdown");
        }
        try (var connection = PostgresRuntime.borrowBackground();
             var query = connection.prepareStatement("""
                 SELECT revision FROM mod_data.entity_document_state
                 WHERE server_id=1 AND world='entity-fixture' AND document_id=?
                 """)) {
            query.setString(1, (String) LuaManager.env.rawget("shutdownDocument"));
            try (var rows = query.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 1)
                    throw new AssertionError("Shutdown did not drain the accepted mutation");
            }
        }
        System.out.println("ORIGINAL_ENTITIES_POSTGRES_PASS");
    }

    private static void initializeLua() throws Exception {
        LuaManager.class.getMethod("init").invoke(null);
    }

    private static void setWorld(String world) throws Exception {
        IsoWorld.class.getMethod("setWorld", String.class).invoke(IsoWorld.instance, world);
    }

    private static void scopedCollection() throws Exception {
        awaitResult("""
            power = ApocBREntities.define('Fixture.FixedPower', {
                table = 'fixed_power_area', fields = {server_id={type='integer', id=true},
                    world={type='string', id=true}, area_id={type='string', id=true}, enabled={type='boolean'},
                    x={type='integer'}, y={type='integer'}, z={type='integer'}}
            })
            power:listForServer(completed)
            """, """
            assert(#result.entities == 1 and result.entities[1]:get('area_id') == 'own')
            """);
        awaitResult("""
            local absent = ApocBREntities.define('Fixture.Unknown', {
                table='fixture_nonexistent', fields={id={type='uuid', id=true}}
            })
            absent:validate(completed)
            """, "SCHEMA_ERROR", "");
    }

    private static void characterAndMessages() throws Exception {
        // Seed the existing player authority boundary without starting native client gameplay.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        var singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        IsoPlayer player = (IsoPlayer) unsafeType.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), IsoPlayer.class);
        var health = IsoGameCharacter.class.getDeclaredField("health");
        health.setAccessible(true);
        health.setFloat(player, 1f);
        var candidate = new PlayerDatabase.Candidate(UUID.randomUUID(), "fixture",
            "entity-fixture", "fixture-user", false, 0, 0, 0, "", java.util.List.of());
        LuaManager.env.rawset("fixturePlayer", player);
        lua("assert(ApocBRModEntities.character(fixturePlayer).status == 'IDENTITY_PENDING')");
        UUID session = PlayerMutations.register(player, candidate);
        UUID character = UUID.fromString("00000000-0000-0000-0000-000000000012");
        PlayerMutations.bind(candidate, session, character);

        awaitResult("""
            fixtureContext = ApocBRModEntities.character(fixturePlayer)
            assert(fixtureContext.status == 'OK' and fixtureContext.sourceServerId == 1)
            mastery = ApocBREntities.define('BeyondTen.Mastery', {
                table = 'beyondten_mastery',
                fields = {character_id = {type='uuid', id=true}, perk_id = {type='string', id=true}, xp = {type='number'}}
            })
            local stale = ApocBRModEntities.submitCharacter('BeyondTen.Mastery', 'loadOrCreate', fixturePlayer, 'stale', {})
            assert(not stale.accepted and stale.status == 'STALE_SESSION')
            mastery:loadOrCreateForCharacter(fixturePlayer, fixtureContext,
                {{revision='0', values={perk_id='Aiming', xp=10}}}, completed)
            """, """
            assert(result.imported and #result.rows == 1 and result.rows[1].values.character_id == fixtureContext.characterId)
            masterySnapshot = mastery:characterSnapshot(result.rows)
            """);
        awaitResult("""
            masterySnapshot:patch(fixturePlayer, fixtureContext, {{values={perk_id='Aiming', xp=11}}}, completed)
            """, "assert(result.rows[1].values.xp == 11 and result.rows[1].revision == '2')");
        awaitResult("""
            mastery:find({character_id=fixtureContext.characterId, perk_id='Aiming'}, completed)
            """, "assert(result.entity:get('xp') == 11)");
        lua("messageContext = BackendMessages.context(fixturePlayer); assert(messageContext.status == 'OK')");
        PlayerMutations.unregister(player);
        awaitResult("""
            publication = BackendMessages.publish('ZombieKillsRecorded.v1', messageContext, {count=2}, completed)
            """, "assert(result.revision == '1'); messageId = result.messageId");
        awaitResult("publication:retry(completed)", "assert(result.duplicate and result.messageId == messageId)");
    }

    private static void awaitResult(String submission, String assertion) throws Exception {
        awaitResult(submission, "OK", assertion);
    }

    private static void awaitResult(String submission, String status, String assertion) throws Exception {
        lua("result = nil; completed = function(r) result = r end");
        lua(submission);
        await(() -> LuaManager.env.rawget("result") != null);
        lua("assert(result.status == '" + status + "', result.status); " + assertion);
        lua("result = nil");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for Lua completion");
            LuaEventManager.triggerEvent("OnTickEvenPaused", 1d);
            Thread.sleep(10);
        }
    }

    private static Object lua(String source) {
        try {
            var thread = (KahluaThread) LuaManager.class.getField("thread").get(null);
            var caller = (LuaCaller) LuaManager.class.getField("caller").get(null);
            Object[] result = caller.pcall(thread,
                LuaCompiler.loadstring(source, "original-entity-smoke", LuaManager.env), new Object[0]);
            if (!Boolean.TRUE.equals(result[0])) throw new AssertionError(Arrays.toString(result));
            return result.length > 1 ? result[1] : null;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
