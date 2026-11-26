package cloud.apocalipse.pz.agent.features.postgres.player;

import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.*;
import zombie.entity.ComponentType;
import zombie.entity.components.crafting.CraftMode;
import zombie.entity.components.crafting.DryingCraftLogic;
import zombie.entity.components.crafting.recipe.CraftRecipeData;
import zombie.entity.components.resources.*;
import zombie.entity.util.enums.EnumBitStore;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.scripting.entity.components.crafting.CraftRecipe;
import zombie.scripting.entity.components.spriteconfig.SpriteOverlayConfigScript;
import cloud.apocalipse.pz.agent.features.postgres.*;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.*;
import cloud.apocalipse.pz.agent.features.lua.login.*;

/** Original archive only; no server main, network services or external database. */
public final class PlayerOriginalSmokeMain {
    static volatile PlayerReader.Profile databaseProfile;
    static final AtomicInteger reads = new AtomicInteger();
    static volatile boolean queryOnOwner;
    static final UUID slotId = UUID.randomUUID(), headId = UUID.randomUUID();
    static volatile UUID liveCharacter, liveDataset;
    static final AtomicInteger nativeWrites = new AtomicInteger(), graphWrites = new AtomicInteger();
    static final AtomicInteger liveInventoryWrites = new AtomicInteger(), livePositionWrites = new AtomicInteger(), liveHealthWrites = new AtomicInteger();
    static final List<String> respawnWrites = new java.util.concurrent.CopyOnWriteArrayList<>();
    private record ScalarWrite(String sql, float x, boolean onServerThread) {}
    private static final List<ScalarWrite> scalarWrites = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static Thread ownerThread;
    static final List<String> acceptance = new ArrayList<>();
    static volatile boolean nativeQuitQueried,poolClosed;
    public static void nativeQuit() throws Exception {
        try(var connection=PostgresRuntime.borrowBackground()){check(!poolClosed,"pool closed before native quit SQL");connection.rollback();nativeQuitQueried=true;}
    }
    public static void nativeAcceptance(Object input, Object transport, String username) {
        var connection = (zombie.network.IConnection) transport; int slot = ((zombie.core.network.ByteBufferReader) input).getByte();
        var player = zombie.savefile.ServerPlayerDB.getInstance().serverLoadNetworkCharacter(slot, connection.getUserName());
        check(player != null, "prepared player did not reach native acceptance"); connection.setPlayerAt(slot, player); acceptance.add("accept");
    }
    public static void nativeWorld(Object connection) { check(((zombie.network.IConnection) connection).getPlayerAt(0) != null, "world state preceded acceptance"); acceptance.add("world"); }
    public static boolean metadata(Object type) {
        if (type != zombie.network.PacketTypes.PacketType.MetaData) return false;
        acceptance.add("metadata"); return true;
    }
    public static class MetadataAdvice {
        @net.bytebuddy.asm.Advice.OnMethodEnter(skipOn = net.bytebuddy.asm.Advice.OnNonDefaultValue.class)
        public static boolean enter(@net.bytebuddy.asm.Advice.Argument(1) Object type) { return PlayerOriginalSmokeMain.metadata(type); }
    }
    public static class ConnectionFixture extends UdpConnection {
        int sent, denied;
        final ByteBufferWriter output = new ByteBufferWriter(java.nio.ByteBuffer.allocate(1024 * 1024));
        public ConnectionFixture() { super(null, 71L, 0); setUserName("fixture"); setIDStr("fixture"); }
        @Override public String getDescription() { return "fixture"; }
        @Override public java.net.InetSocketAddress getInetSocketAddress() { return new java.net.InetSocketAddress("127.0.0.1", 12345); }
        @Override public ByteBufferWriter startPacket() { output.bb.clear(); return output; }
        @Override public void endPacket(int priority, int reliability, byte ordering) { sent++; }
        @Override public void cancelPacket() { }
        @Override public void forceDisconnect(String reason) { denied++; }
    }
    static Object zero(Class<?> type) { if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return null; }
    static Object proxy(Class<?> type, InvocationHandler handler) { return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler); }
    static java.sql.Connection connection() {
        return (java.sql.Connection) proxy(java.sql.Connection.class, (p, m, a) -> {
            if (m.getName().equals("prepareStatement")) {
                String sql = (String) a[0]; boolean profile = sql.equals(PlayerReader.SQL);
                var bindings = new HashMap<Integer, Object>(); var batch = new AtomicInteger();
                return proxy(PreparedStatement.class, (s, method, args) -> {
                    if (method.getName().startsWith("set")) { if (!method.getName().equals("setQueryTimeout")) bindings.put((Integer) args[0], args[1]); return null; }
                    if (method.getName().equals("addBatch")) {
                        if (sql.startsWith("INSERT INTO player_rel.item (")) liveInventoryWrites.incrementAndGet();
                        if (sql.startsWith("INSERT INTO player_rel.character_state (")
                                || sql.startsWith("UPDATE player_rel.character_state SET x")) livePositionWrites.incrementAndGet();
                        if (sql.startsWith("INSERT INTO player_rel.body_damage (")
                                || sql.startsWith("UPDATE player_rel.body_damage SET ")) liveHealthWrites.incrementAndGet();
                        if (sql.startsWith("UPDATE player_rel.character_state SET x")) {
                            scalarWrites.add(new ScalarWrite(sql, ((Number) bindings.get(1)).floatValue(), Thread.currentThread() == ownerThread));
                        }
                        batch.incrementAndGet(); return null;
                    }
                    if (method.getName().equals("executeBatch")) { var result = new int[batch.getAndSet(0)]; Arrays.fill(result, 1); return result; }
                    if (method.getName().equals("executeUpdate")) {
                        if (sql.startsWith("INSERT INTO public.networkplayers")) {
                            nativeWrites.incrementAndGet();
                            respawnWrites.add(Boolean.TRUE.equals(bindings.get(10)) ? "dead" : "alive");
                        }
                        if (sql.startsWith("INSERT INTO player_rel.snapshot (") && sql.contains("VALUES")) {
                            var columns = sql.substring(sql.indexOf('(') + 1, sql.indexOf(')')).split(",");
                            for (int i = 0; i < columns.length; i++) { if (columns[i].equals("character_id")) liveCharacter = (UUID) bindings.get(i + 1); if (columns[i].equals("source_dataset_id")) liveDataset = (UUID) bindings.get(i + 1); }
                            graphWrites.incrementAndGet();
                            respawnWrites.add("new-life");
                        }
                        return 1;
                    }
                    if (method.getName().equals("executeQuery")) {
                        if (!profile && (sql.contains("pg_try_advisory_xact_lock") || sql.startsWith("SELECT id FROM player_rel.player_slot") || sql.startsWith("SELECT COALESCE(MAX(incarnation)") || sql.startsWith("SELECT p.active_character_id,h.snapshot_id,h.revision"))) {
                            Object[] row = sql.contains("pg_try_advisory_xact_lock") ? new Object[]{true} : sql.startsWith("SELECT id FROM") ? new Object[]{slotId}
                                : sql.startsWith("SELECT COALESCE") ? new Object[]{1} : new Object[]{liveCharacter, headId, 0L, "complete", 1, 4, liveDataset, "new_character"};
                            var index = new AtomicInteger(-1);
                            return proxy(ResultSet.class, (r, read, values) -> read.getName().equals("next") ? index.incrementAndGet() == 0
                                : read.getName().startsWith("get") && values != null && values.length > 0 && values[0] instanceof Integer ? row[(int) values[0] - 1] : zero(read.getReturnType()));
                        }
                        var rows = new ArrayList<Map.Entry<String, Map<String, Object>>>();
                        if (profile) {
                            reads.incrementAndGet(); queryOnOwner |= Thread.currentThread() == GameServer.class.getField("mainThread").get(null);
                            var current = databaseProfile; if (current != null) current.tables().forEach((table, values) -> values.forEach(value -> rows.add(Map.entry(table, value))));
                        }
                        var index = new AtomicInteger(-1);
                        return proxy(ResultSet.class, (r, read, values) -> switch (read.getName()) {
                            case "next" -> index.incrementAndGet() < rows.size();
                            case "getString" -> (int) values[0] == 1 ? rows.get(index.get()).getKey() : new org.json.JSONObject(rows.get(index.get()).getValue()).toString();
                            default -> zero(read.getReturnType());
                        });
                    }
                    return zero(method.getReturnType());
                });
            }
            return zero(m.getReturnType());
        });
    }
    public static void premain(String arguments, Instrumentation instrumentation) {
        var module = new PlayerModule(); module.initialize();
        var decode = new DictionaryDecodeModule(); decode.initialize();
        // Test-only acceptance tail: exercise production deferral/resumption without starting
        // chat, RakNet, map download or world services. Original bytes are validated separately.
        instrumentation.addTransformer(new java.lang.instrument.ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> redefining,java.security.ProtectionDomain domain,byte[] bytes) {
                if(!"zombie/network/GameServer".equals(name)&&!"zombie/network/ServerMap".equals(name))return null;
                var writer=new net.bytebuddy.jar.asm.ClassWriter(net.bytebuddy.jar.asm.ClassWriter.COMPUTE_MAXS);
                new net.bytebuddy.jar.asm.ClassReader(bytes).accept(new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9,writer){
                    @Override public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                        var target=super.visitMethod(access,method,descriptor,signature,exceptions);
                        boolean connect=method.equals("receivePlayerConnect")&&descriptor.equals("(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;Ljava/lang/String;)V");
                        boolean world=method.equals("sendInitialWorldState")&&descriptor.equals("(Lzombie/network/IConnection;)V");
                        boolean quit=method.equals("QueuedQuit")&&descriptor.equals("()V");
                        if(!connect&&!world&&!quit)return target;
                        target.visitCode();if(!quit)target.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD,0);
                        if(connect){target.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD,1);target.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD,2);}
                        target.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,PlayerOriginalSmokeMain.class.getName().replace('.','/'),quit?"nativeQuit":connect?"nativeAcceptance":"nativeWorld",quit?"()V":connect?"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V":"(Ljava/lang/Object;)V",false);
                        target.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);target.visitMaxs(0,0);target.visitEnd();return null;
                    }
                },0);return writer.toByteArray();
            }
        });
        var shutdown = new ServerShutdownModule(); shutdown.initialize();
        shutdown.configure(module.configure(decode.configure(new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly()))))
            .type(net.bytebuddy.matcher.ElementMatchers.named("zombie.network.packets.INetworkPacket"))
            .transform((dynamic,type,loader,javaModule,domain)->dynamic.visit(net.bytebuddy.asm.Advice.to(MetadataAdvice.class).on(
                net.bytebuddy.matcher.ElementMatchers.named("send").and(net.bytebuddy.matcher.ElementMatchers.isStatic()).and(net.bytebuddy.matcher.ElementMatchers.takesArguments(3))
                    .and(net.bytebuddy.matcher.ElementMatchers.takesArgument(0,net.bytebuddy.matcher.ElementMatchers.named("zombie.network.IConnection")))))).installOn(instrumentation);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        ownerThread = Thread.currentThread();
        GameServer.class.getField("server").setBoolean(null, true); GameServer.class.getField("mainThread").set(null, Thread.currentThread()); Core.class.getField("gameSaveWorld").set(null, "player-fixture");
        zombie.ZomboidFileSystem.instance.setCacheDir(args[0]);
        zombie.core.random.RandStandard.INSTANCE.init(); zombie.core.random.RandLua.INSTANCE.init();
        zombie.ZomboidFileSystem.instance.init();
        zombie.Lua.LuaManager.class.getMethod("init").invoke(null); zombie.characters.skills.PerkFactory.init();
        zombie.characters.SurvivorDesc.HairCommonColors.add(new zombie.core.ImmutableColor(0.2f, 0.2f, 0.2f));
        zombie.core.skinnedmodel.population.HairStyles.init(); zombie.core.skinnedmodel.population.BeardStyles.init();
        zombie.core.skinnedmodel.population.PopTemplateManager.instance.init();
        zombie.SoundManager.instance = new zombie.DummySoundManager();
        zombie.characters.professions.CharacterProfessionDefinition.addCharacterProfessionDefinition(zombie.scripting.objects.CharacterProfession.UNEMPLOYED, "fixture", 0, "", null);
        var cell = new IsoCell(10, 10);
        ownershipScenario();
        PlayerReconstruction.set(zombie.network.ServerMap.instance, "grid", new zombie.iso.IsoMetaGrid());
        var tables = new LinkedHashMap<String, List<Map<String, Object>>>();
        tables.put("snapshot", List.of(Map.of("persisted_character_id", UUID.randomUUID().toString(), "source_dataset_id", UUID.randomUUID().toString())));
        tables.put("character_state", List.of(Map.of("character_id", 1, "inventory_container_id", 2, "x", 123.5f, "y", 456.5f, "z", 0, "direction", "SE", "is_dead", false, "on_fire", false)));
        tables.put("container", List.of(Map.of("id", 2, "owner_character_id", 1)));
        var profile = new PlayerReader.Profile(tables);
        var descriptorMap = (Map<?, ?>) IsoWorld.class.getField("survivorDescriptors").get(IsoWorld.instance);
        int descriptors = descriptorMap.size();
        IsoPlayer player = PlayerReconstruction.reconstruct(profile, 0);
        check(player.getClass().getProtectionDomain().getCodeSource().getLocation().toString().endsWith("projectzomboid.jar"), "original player required");
        check(player.getX() == 123.5f && player.getY() == 456.5f, "world coordinates must be restored exactly");
        check(!cell.getObjectList().contains(player) && !cell.getAddList().contains(player), "temporary player entered simulation");
        check(descriptorMap.size() == descriptors, "temporary descriptor was published");
        check(!PlayerConstruction.active(), "construction scope leaked");
        var origin = addHistoricalItem(player);
        var snapshot = PlayerSnapshot.copyLive(player);
        var graph = new PlayerCapture(UUID.randomUUID(), Map.of(), Long.MAX_VALUE).capture(snapshot, List.of());
        PlayerCapture.requireComplete(graph, "fixture");
        var restored = PlayerReconstruction.reconstruct(profile(graph), 0);
        check(restored.getX() == player.getX() && restored.getBodyDamage().getOverallBodyHealth() == player.getBodyDamage().getOverallBodyHealth(), "captured relational graph did not round trip");
        var opaque = restored.getInventory().getItems().getFirst().getByteData();
        check(PayloadProvenance.metadata(opaque).origin().equals(origin), "relational reconstruction lost opaque payload provenance");
        check("foreign-text".equals(zombie.world.StringDictionary.Generic.loadString(opaque, 251)), "reconstructed native buffer used the destination dictionary"); opaque.rewind();
        PlayerRuntime.discard(restored);
        packetScenario(graph, args[0]);
        PlayerRuntime.discard(player);
        System.out.println("original relational reconstruction passed");
    }
    static void ownershipScenario() {
        var tables=new HashMap<String,List<Map<String,Object>>>();tables.put("character_state",List.of(Map.of("inventory_container_id",1)));
        tables.put("container",List.of(Map.of("id",1)));tables.put("item",List.of(Map.of("id",2),Map.of("id",3)));
        tables.put("item_location",List.of(Map.of("item_id",2,"parent_item_id",3,"position",0,"relation","weapon_attachment"),Map.of("item_id",3,"parent_item_id",2,"position",0,"relation","weapon_attachment")));
        try{PlayerReconstruction.validateOwnership(new PlayerReader.Profile(tables));throw new AssertionError("cyclic inventory ownership accepted");}catch(IllegalStateException expected){}
    }
    @SuppressWarnings("unchecked")
    static DictionaryOrigin addHistoricalItem(IsoPlayer player) throws Exception {
        var module = new zombie.scripting.objects.ScriptModule(); module.name = "Base";
        zombie.scripting.ScriptManager.instance.moduleMap.put("Base", module);
        var script = new zombie.scripting.objects.Item(); script.setModule(module);
        PlayerReconstruction.set(script, "scriptObjectName", "Fixture"); PlayerReconstruction.set(script, "modId", "pz-vanilla");
        PlayerReconstruction.set(script, "name", "Fixture"); PlayerReconstruction.set(script, "moduleDotType", "Base.Fixture");
        PlayerReconstruction.set(script, "displayName", "Fixture"); script.setItemType(zombie.scripting.objects.ItemType.NORMAL); script.setRegistry_id((short) 99);
        ((Map<String, Object>) PlayerAccess.get(module.items, "scriptMap")).put("Fixture", script);
        var info = new zombie.world.ItemInfo();
        for (var value : Map.<String, Object>of("name", "Fixture", "moduleName", "Base", "fullType", "Base.Fixture", "registryId", (short) 99, "isLoaded", true,
            "modId", "pz-vanilla", "scriptItem", script, "entityScript", script).entrySet()) PlayerReconstruction.set(info, value.getKey(), value.getValue());
        var field = zombie.world.WorldDictionary.class.getDeclaredField("data"); field.setAccessible(true); Object data = field.get(null);
        if (data == null) { var constructor = Class.forName("zombie.world.DictionaryData").getDeclaredConstructor(); constructor.setAccessible(true); data = constructor.newInstance(); field.set(null, data); }
        var reset = data.getClass().getDeclaredMethod("reset"); reset.setAccessible(true); reset.invoke(data);
        ((Map<Short, Object>) PlayerAccess.get(data, "itemIdToInfoMap")).put((short) 99, info);
        ((Map<String, Object>) PlayerAccess.get(data, "itemTypeToInfoMap")).put("Base.Fixture", info);
        var origin = new DictionaryOrigin(2, UUID.randomUUID(), UUID.randomUUID());
        var dictionary = new DictionarySnapshot(List.of(new DictionarySnapshot.Registry("info", "", 2, null), new DictionarySnapshot.Registry("string", "Generic", 2, 64)),
            List.of(new DictionarySnapshot.Entry("info", "", 1, "item", "Base.Fixture", "Base", "Fixture", true, "pz-vanilla", true, false, false, false, false, null, List.of()),
                new DictionarySnapshot.Entry("string", "Generic", 1, "string", "foreign-text", null, null, true, null, null, null, null, null, null, null, List.of())));
        var install = DictionaryCache.class.getDeclaredMethod("install", DictionaryOrigin.class, DictionarySnapshot.class); install.setAccessible(true); install.invoke(null, origin, dictionary);
        var item = zombie.inventory.InventoryItemFactory.CreateItem("Base.Fixture"); check(item != null, "fixture item creation failed");
        var opaque = java.nio.ByteBuffer.wrap(new byte[]{1, 0, 1}); PlayerReconstruction.set(item, "byteData", opaque);
        PayloadProvenance.remember(opaque, new PayloadProvenance.Metadata(origin, 251)); player.getInventory().AddItem(item);
        var bytes = java.nio.ByteBuffer.allocate(32768); item.saveWithSize(bytes, false); bytes.flip(); bytes.putShort(4, (short) 1);
        PayloadProvenance.remember(bytes, new PayloadProvenance.Metadata(origin, 251));
        var decoded = zombie.inventory.InventoryItem.loadItem(bytes, 251);
        check(decoded != null && decoded.getFullType().equals("Base.Fixture"), "original native item reader did not remap foreign item ID");
        check(PayloadProvenance.metadata(decoded.getByteData()).origin().equals(origin), "native nested bytes did not inherit source origin");
        var secondOrigin=new DictionaryOrigin(3,UUID.randomUUID(),UUID.randomUUID());install.invoke(null,secondOrigin,dictionary);
        PlayerReconstruction.set(item,"byteData",null);var next=java.nio.ByteBuffer.allocate(32768);item.saveWithSize(next,false);next.flip();next.putShort(4,(short)1);
        PayloadProvenance.remember(next,new PayloadProvenance.Metadata(secondOrigin,251));
        zombie.inventory.InventoryItem.loadItem(next,251,true,decoded);
        check(PayloadProvenance.metadata(decoded.getByteData()).origin().equals(origin),"reused item relabeled an existing nested payload");
        PlayerReconstruction.set(item,"byteData",opaque);
        return origin;
    }
    static PlayerReader.Profile profile(PlayerCapture.Graph graph) {
        var refs = new LinkedHashMap<Object, Integer>();
        for (var row : graph.rows()) if (row.table().equals("object") || row.table().equals("value_node")) refs.computeIfAbsent(row.values().get("id"), key -> refs.size() + 1);
        var tables = new LinkedHashMap<String, List<Map<String, Object>>>();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.putAll(Map.of("persisted_character_id", graph.character().toString(), "source_dataset_id", UUID.randomUUID().toString(), "source_world_version", 251,
            "status", "complete", "schema_version", 1, "codec_version", 4, "expected_object_count", graph.objects(), "expected_item_count", graph.items(), "expected_container_count", graph.containers(), "expected_edge_count", graph.edges()));
        tables.put("snapshot", List.of(metadata));
        for (var row : graph.rows()) {
            var values = new LinkedHashMap<String, Object>();
            for (var column : PlayerSchema.TABLES.get(row.table())) {
                if (column.name().equals("snapshot_id")) continue;
                Object value = row.values().get(column.name());
                if (column.type().equals("uuid") && !column.name().startsWith("source_dictionary_")) value = value == null ? null : refs.get(value);
                if (value instanceof byte[] bytes) value = HexFormat.of().formatHex(bytes);
                values.put(column.name(), value);
            }
            if (row.table().equals("item")) values.put("persisted_item_id", row.values().get("id").toString());
            tables.computeIfAbsent(row.table(), key -> new ArrayList<>()).add(values);
        }
        return new PlayerReader.Profile(tables);
    }
    static void packetScenario(PlayerCapture.Graph graph, String cache) throws Exception {
        var roleType = Class.forName("zombie.characters.Role"); var capability = Class.forName("zombie.characters.Capability");
        var role = roleType.getConstructor(String.class).newInstance("fixture"); roleType.getMethod("setId", int.class).invoke(role, 1);
        roleType.getMethod("addCapability", capability).invoke(role, capability.getField("LoginOnServer").get(null));
        var roles = Class.forName("zombie.characters.Roles");
        @SuppressWarnings("unchecked") var registry = (List<Object>) roles.getMethod("getRoles").invoke(null); registry.add(role);
        for (String name : List.of("defaultForBanned", "defaultForUser", "defaultForNewUser")) { var field = roles.getDeclaredField(name); field.setAccessible(true); field.set(null, role); }
        DataSource source = (DataSource) proxy(DataSource.class, (p, m, a) -> m.getName().equals("getConnection") ? connection() : zero(m.getReturnType()));
        var constructor = PostgresConnections.class.getDeclaredConstructor(DataSource.class, AutoCloseable.class); constructor.setAccessible(true);
        var pool = constructor.newInstance(source, (AutoCloseable) () -> { poolClosed=true; });
        var install = PostgresRuntime.class.getDeclaredMethod("installForTest", cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings.class, PostgresConnections.class); install.setAccessible(true);
        install.invoke(null, new cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings(true, true, "jdbc:postgresql://fixture/unused", 1, 5000, 3000), pool);
        var connection = new ConnectionFixture(); connection.setRole((zombie.characters.Role) role);
        var unsafeClass = Class.forName("sun.misc.Unsafe"); var field = unsafeClass.getDeclaredField("theUnsafe"); field.setAccessible(true); var unsafe = field.get(null);
        var engine = (UdpEngine) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, UdpEngine.class);
        PlayerReconstruction.set(engine, "connectionMap", new HashMap<>(Map.of(71L, connection))); PlayerReconstruction.set(engine, "connections", new ArrayList<>(List.of(connection)));
        GameServer.class.getField("udpEngine").set(null, engine);
        System.setProperty("apocbr.postgres.playerJournal.directory", Path.of(cache, "journal").toString());
        zombie.savefile.ServerPlayerDB.setAllow(true); zombie.savefile.ServerPlayerDB.getInstance();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!PlayerSync.readable("player-fixture", "fixture", 0, false) && System.nanoTime() < deadline) Thread.sleep(10);
        check(PlayerSync.readable("player-fixture", "fixture", 0, false), "fixture journal unavailable");
        AsyncRuntime.initialize(true);
        try {
            var request = new zombie.network.packets.connection.LoadPlayerProfilePacket(); PlayerReconstruction.set(request, "playerIndex", (byte) 0);
            databaseProfile = null; request.processServer(null, connection); awaitRequest();
            check(connection.sent == 1 && connection.denied == 0 && reads.get() == 1 && !queryOnOwner, "absent native packet must use worker read and fresh reply");
            var local = DictionaryRuntime.class.getDeclaredField("local"); local.setAccessible(true);
            local.set(null, new DictionaryDatabase.Published(UUID.randomUUID(), UUID.randomUUID(), 1L, 1, "fixture"));
            databaseProfile = profile(graph); request.processServer(null, connection); awaitRequest();
            check(connection.sent == 2 && connection.denied == 0 && reads.get() == 2, "present native packet failed reconstruction/encoding");
            var wire = connection.output.bb.duplicate(); wire.flip(); wire.position(3);
            GameServer.class.getField("server").setBoolean(null, false);
            var clientReply = new zombie.network.packets.connection.LoadPlayerProfilePacket();
            clientReply.parse(new zombie.core.network.ByteBufferReader(wire), connection);
            check((boolean) PlayerAccess.get(clientReply, "isExist"), "native client parser rejected the profile reply");
            check(((Number) PlayerAccess.get(clientReply, "x")).floatValue() == 123.5f, "native client profile position changed");
            byte[] payload = (byte[]) PlayerAccess.get(clientReply, "buffer");
            IsoPlayer clientPlayer;
            try (var detached = PlayerConstruction.enter()) {
                clientPlayer = new IsoPlayer((IsoCell) IsoWorld.class.getField("currentCell").get(IsoWorld.instance), new zombie.characters.SurvivorDesc(true), 0, 0, 0);
                clientPlayer.load(java.nio.ByteBuffer.wrap(payload), ((Number) PlayerAccess.get(clientReply, "worldVersion")).intValue());
            }
            check(clientPlayer.getInventory().getItems().size() == graph.items(), "native client player decoder lost inventory");
            check(Arrays.equals(clientPlayer.getInventory().getItems().getFirst().getByteData().array(), new byte[]{1, 0, 1}), "native client discarded or rewrote embedded payload bytes");
            PlayerRuntime.discard(clientPlayer); GameServer.class.getField("server").setBoolean(null, true);
            creationScenario(connection);
            acceptanceScenario(connection, graph);
            databaseProfile = new PlayerReader.Profile(Map.of("snapshot", List.of(Map.of("status", "incomplete", "schema_version", 1, "codec_version", 4))));
            int deniedBefore=connection.denied;request.processServer(null, connection); awaitRequest(); check(connection.denied == deniedBefore+1, "corrupt profile must deny rather than report absence");
            int sentBefore=connection.sent,readsBefore=reads.get();deniedBefore=connection.denied;PlayerReconstruction.set(request,"playerIndex",(byte)4);request.processServer(null,connection);
            check(connection.sent==sentBefore+1&&connection.denied==deniedBefore&&reads.get()==readsBefore,"slot-four profile terminator must remain an absent reply without JDBC");
            shutdownScenario();
        } finally { AsyncRuntime.shutdown(); PlayerRuntime.shutdown(); PostgresRuntime.shutdown(); }
    }
    static void shutdownScenario()throws Exception {
        var field = GameServer.class.getDeclaredField("shutdownHook"); field.setAccessible(true);
        var hook = (Thread) field.get(null);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        hook.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
        hook.setDaemon(true);
        hook.start();
        hook.join(5000);
        check(!hook.isAlive()&&failure.get()==null&&nativeQuitQueried&&poolClosed,"native quit/pool shutdown coordination failed: "+failure.get());
    }
    static void acceptanceScenario(ConnectionFixture connection,PlayerCapture.Graph graph)throws Exception {
        connection.setUserName(0,"fixture");
        databaseProfile=profile(graph);acceptance.clear();
        var original=java.nio.ByteBuffer.wrap(new byte[]{0,12,0});
        var packet=new zombie.network.packets.connection.ConnectPacket();
        packet.parse(new zombie.core.network.ByteBufferReader(original),connection);
        check(acceptance.isEmpty()&&connection.getPlayerAt(0)==null,"connect sent state before the worker completed");
        original.put(0,(byte)3);awaitRequest();
        check(acceptance.equals(List.of("accept","world","metadata")),"native acceptance/state order changed: "+acceptance);
        var player=connection.getPlayerAt(0);check(player!=null&&player.getX()==123.5f,"authoritative reconstruction was not resumed");
        connection.setPlayerAt(0,null);PlayerRuntime.discard(player);
        int previous=acceptance.size();
        packet.parse(new zombie.core.network.ByteBufferReader(java.nio.ByteBuffer.wrap(new byte[]{0,12,0})),connection);
        @SuppressWarnings("unchecked") var active=(Map<Long,UdpConnection>)PlayerAccess.get(GameServer.class.getField("udpEngine").get(null),"connectionMap");
        active.clear();awaitRequest();check(acceptance.size()==previous,"disconnected request resumed native acceptance");active.put(71L,connection);
        int denied=connection.denied;
        GameServer.class.getMethod("receivePlayerConnect",zombie.core.network.ByteBufferReader.class,zombie.network.IConnection.class,String.class)
            .invoke(null,new zombie.core.network.ByteBufferReader(java.nio.ByteBuffer.wrap(new byte[]{9,12,0})),connection,"fixture");
        check(connection.denied==denied+1&&acceptance.size()==previous,"invalid direct connect reached native acceptance");
    }
    static void creationScenario(ConnectionFixture connection) throws Exception {
        Object options = Class.forName("zombie.network.ServerOptions").getField("instance").get(null);
        Object spawn = options.getClass().getField("spawnPoint").get(options); spawn.getClass().getMethod("setValue", String.class).invoke(spawn, "123,456,0");
        var request = new zombie.network.packets.character.CreatePlayerPacket();
        PlayerReconstruction.set(request, "playerIndex", (byte) 0); PlayerReconstruction.set(request, "survivorDescriptor", new zombie.characters.SurvivorDesc(true));
        int before = connection.sent;
        request.processServer(null, connection); request.processServer(null, connection);
        check(connection.sent == before, "creation replied before durable capture");
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (connection.sent == before && connection.denied == 0 && System.nanoTime() < deadline) { PlayerRuntime.update(); Thread.sleep(10); }
        check(connection.sent == before + 1 && connection.denied == 0 && graphWrites.get() == 1 && nativeWrites.get() == 1, "creation handoff did not commit and coalesce retransmission");
        var created = PlayerCreation.take(connection, 0); check(created != null, "committed creation instance was not retained");
        check(PlayerCreation.take(connection, 0) == null, "creation handoff consumed twice");
        mutationScenario(created);
        created.setX(999f); zombie.savefile.ServerPlayerDB.getInstance().serverUpdateNetworkCharacter(created, 0, connection);
        zombie.savefile.ServerPlayerDB.getInstance().saveFinishWait();
        check(PlayerSync.readable("player-fixture", "fixture", 0, false), "final capture remained pending");
        check(nativeWrites.get() == 2, "native final snapshot did not reach persistence");
        CarriedComponents.failedFallbackScenario(connection, created);
        CarriedComponents.roundTripScenario(connection, created);
        CarriedComponents.genericFallbackScenario(connection, created);
        respawnScenario(connection, created);
        var abandoned=new zombie.characters.SurvivorDesc();PlayerReconstruction.set(request,"survivorDescriptor",abandoned);request.processServer(null,connection);
        @SuppressWarnings("unchecked") var connections=(List<UdpConnection>)PlayerAccess.get(GameServer.class.getField("udpEngine").get(null),"connections");connections.clear();
        PlayerCreation.update(System.nanoTime()+1_000_000_000L);connections.add(connection);
        var descriptors=(Map<?,?>)IsoWorld.class.getField("survivorDescriptors").get(IsoWorld.instance);
        check(!descriptors.containsKey(abandoned.getID()),"cancelled creation retained its parsed descriptor");
    }

    // Resolve component and item signatures only after premain installs the dictionary hooks.
    static final class CarriedComponents {
        static void failedFallbackScenario(ConnectionFixture connection, IsoPlayer player) throws Exception {
            var item = InventoryItemFactory.CreateItem("Base.Fixture");
            attach(item, ComponentType.CraftLogic);
            player.getInventory().AddItem(item);
            float previousX = player.getX();
            float previousHealth = player.getBodyDamage().getOverallBodyHealth();
            int mirrors = nativeWrites.get(), inventory = liveInventoryWrites.get(), positions = livePositionWrites.get();
            int health = liveHealthWrites.get();
            var local = DictionaryRuntime.class.getDeclaredField("local");
            local.setAccessible(true);
            Object published = local.get(null);
            local.set(null, null);
            try {
                player.setX(previousX + 1);
                player.getBodyDamage().setOverallBodyHealth(previousHealth - 5);
                var snapshot = PlayerSnapshot.copyLive(player);
                var graph = new PlayerCapture(liveCharacter, Map.of(), Long.MAX_VALUE).capture(snapshot, List.of());
                check(graph.issues().equals(List.of("NATIVE_COMPONENT_CAPTURE_FAILED_CraftLogic")), "unexpected component issues: " + graph.issues());
                saveAndDrain(connection, player);
                check(quarantineRecords() == 1, "unsupported item capture must be quarantined");
                check(PlayerSync.readable("player-fixture", "fixture", 0, false),
                    "quarantined capture must clear the pending slot after drain");
                check(nativeWrites.get() == mirrors + 1, "native byte snapshot did not reach its mirror");
                check(liveInventoryWrites.get() == inventory && livePositionWrites.get() == positions + 1
                    && liveHealthWrites.get() == health + 1, "position and health must be applied while keeping the last committed inventory");
            } finally {
                local.set(null, published);
                player.getInventory().Remove(item);
                player.setX(previousX);
                player.getBodyDamage().setOverallBodyHealth(previousHealth);
            }
        }

        static void genericFallbackScenario(ConnectionFixture connection, IsoPlayer player) throws Exception {
            var item = InventoryItemFactory.CreateItem("Base.Fixture");
            var crafting = attach(item, ComponentType.CraftLogic);
            PlayerReconstruction.set(crafting, "inputsGroupName", "fixture-input");
            PlayerReconstruction.set(crafting, "actionAnimOverride", "FixtureCrafting");
            var marker = attach(item, ComponentType.MetaTag);
            PlayerReconstruction.set(marker, "storedId", 12345678L);
            player.getInventory().AddItem(item);
            try {
                var graph = new PlayerCapture(liveCharacter, Map.of(), Long.MAX_VALUE).capture(PlayerSnapshot.copyLive(player), List.of());
                PlayerCapture.requireComplete(graph, "generic-native-component");
                var saved = profile(graph);
                var restored = PlayerReconstruction.reconstruct(saved, 0);
                try {
                    var loadedItem = restored.getInventory().getItems().getFirst();
                    var loadedCraft = loadedItem.getComponent(ComponentType.CraftLogic);
                    var loadedMarker = loadedItem.getComponent(ComponentType.MetaTag);
                    check("fixture-input".equals(PlayerAccess.get(loadedCraft, "inputsGroupName"))
                        && "FixtureCrafting".equals(PlayerAccess.get(loadedCraft, "actionAnimOverride"))
                        && Long.valueOf(12345678L).equals(PlayerAccess.get(loadedMarker, "storedId")),
                        "generic fallback lost native component fields");
                } finally { PlayerRuntime.discard(restored); }
                var state = PlayerCodecs.fallbackComponent(crafting);
                var local = DictionaryRuntime.class.getDeclaredField("local");
                local.setAccessible(true);
                var current=(DictionaryDatabase.Published)local.get(null);
                local.set(null,new DictionaryDatabase.Published(current.namespace(),current.view(),current.revision(),
                    current.serverId()+1,"foreign-fixture"));
                try {
                    PlayerCodecs.restoreFallbackComponent(crafting,(String)state.get("nativeFallbackType"),(byte[])state.get("nativePayload"));
                    throw new AssertionError("generic fallback decoded on another server");
                } catch(IllegalStateException expected) {
                    check(expected.getMessage().contains("requires its source server"),"unexpected foreign fallback failure: "+expected);
                } finally { local.set(null,current); }
                int items = liveInventoryWrites.get();
                saveAndDrain(connection, player);
                check(quarantineRecords() == 1 && liveInventoryWrites.get() == items + 1,
                    "same-session save through the generic fallback must reach the inventory writer");
                System.out.println("Generic carried component fallback passed");
            } finally { player.getInventory().Remove(item); }
        }

        @SuppressWarnings("unchecked")
        static void roundTripScenario(ConnectionFixture connection, IsoPlayer player) throws Exception {
            var item = InventoryItemFactory.CreateItem("Base.Fixture");
            var module = item.getScriptItem().getModule();
            var overlay = attach(item, ComponentType.SpriteOverlayConfig);
            var script = (SpriteOverlayConfigScript) PlayerReconstruction.create(SpriteOverlayConfigScript.class.getName());
            script.setModule(module);
            script.InitLoadPP("FixtureOverlay");
            script.getAllStyleNames().add("Dry_3");
            var style = PlayerReconstruction.create(SpriteOverlayConfigScript.OverlayStyle.class.getName());
            ((Map<String,Object>) PlayerAccess.get(script, "allStyles")).put("Dry_3", style);
            var register = zombie.world.ScriptsDictionary.spriteOverlayConfigs;
            var scriptInfo = PlayerReconstruction.create("zombie.world.DictionaryScriptInfo");
            PlayerReconstruction.set(scriptInfo, "script", script);
            PlayerReconstruction.set(scriptInfo, "isLoaded", true);
            PlayerReconstruction.set(scriptInfo, "registryId", (short) 5);
            ((Map<String,Object>) PlayerAccess.get(register, "namedMap")).put(script.getScriptObjectFullType(), scriptInfo);
            ((Map<Short,Object>) PlayerAccess.get(register, "idMap")).put((short) 5, scriptInfo);
            PlayerReconstruction.set(overlay, "configScript", script);
            PlayerAccess.call(overlay, "setStyle", "Dry_3");

            var resources = (Resources) attach(item, ComponentType.Resources);
            for (String group : List.of("input", "output")) {
                var blueprint = ResourceBlueprint.alloc(group, ResourceType.Item, group.equals("input") ? ResourceIO.Input : ResourceIO.Output,
                    4, null, ResourceChannel.NO_CHANNEL, EnumBitStore.noneOf(ResourceFlag.class));
                try { resources.createResource(group, blueprint); }
                finally { ResourceBlueprint.release(blueprint); }
                var resource = (ResourceItem) resources.getResource(group);
                var content = InventoryItemFactory.CreateItem("Base.Fixture");
                content.setCondition(group.equals("input") ? 4 : 6);
                resource.getStoredItems().add(content);
            }
            var drying = (DryingCraftLogic) attach(item, ComponentType.DryingCraftLogic);
            var recipe = new CraftRecipe();
            recipe.setModule(module);
            var loadMod = zombie.scripting.ScriptManager.class.getDeclaredField("currentLoadFileMod");
            loadMod.setAccessible(true);
            Object previousLoadMod = loadMod.get(null);
            loadMod.set(null, "pz-vanilla");
            try { recipe.InitLoadPP("FixtureDry"); }
            finally { loadMod.set(null, previousLoadMod); }
            module.craftRecipes.getScriptMap().put("FixtureDry", recipe);
            var scriptManager = zombie.scripting.ScriptManager.instance;
            PlayerAccess.call(PlayerAccess.get(scriptManager, "craftRecipes"), "registerModule", module);
            check(scriptManager.getCraftRecipe(recipe.getScriptObjectFullType()) == recipe, "fixture recipe was not registered");
            var progress = CraftRecipeData.Alloc(CraftMode.Automation, true, false, true, false);
            progress.setRecipe(recipe);
            progress.setElapsedTime(12.75);
            ((List<CraftRecipeData>) PlayerAccess.get(drying, "craftDataInProgress")).add(progress);
            ((Map<CraftRecipeData,Double>) PlayerAccess.get(drying, "temporaryWetnesses")).put(progress, 0.625);

            var install = DictionaryCache.class.getDeclaredMethod("install", DictionaryOrigin.class, DictionarySnapshot.class);
            install.setAccessible(true);
            install.invoke(null, DictionaryRuntime.localEncoding(), new DictionarySnapshot(
                List.of(new DictionarySnapshot.Registry("info", "", 100, null)),
                List.of(new DictionarySnapshot.Entry("info", "", 99, "item", "Base.Fixture", "Base", "Fixture", true, "pz-vanilla",
                    true, false, false, false, false, null, List.of()))));
            var encoded = java.nio.ByteBuffer.allocate(32768);
            item.saveWithSize(encoded, false);
            encoded.flip();
            int worldVersion = (int) IsoWorld.class.getMethod("getWorldVersion").invoke(null);
            var nativeItem = InventoryItem.loadItem(encoded, worldVersion);
            check(nativeItem != null && !encoded.hasRemaining(), "vanilla did not round-trip the item");
            verifyComponents(nativeItem, script, recipe);

            player.getInventory().AddItem(item);
            try {
                var graph = new PlayerCapture(liveCharacter, Map.of(), Long.MAX_VALUE).capture(PlayerSnapshot.copyLive(player), List.of());
                PlayerCapture.requireComplete(graph, "supported-carried-components");
                var restored = PlayerReconstruction.reconstruct(profile(graph), 0);
                try { verifyComponents(restored.getInventory().getItems().getFirst(), script, recipe); }
                finally { PlayerRuntime.discard(restored); }
                int items = liveInventoryWrites.get();
                saveAndDrain(connection, player);
                check(quarantineRecords() == 1 && liveInventoryWrites.get() == items + 1,
                    "supported components must reach the relational inventory writer without quarantine");
                System.out.println("Carried component scenario B passed");
            } finally {
                player.getInventory().Remove(item);
            }
        }

        static void verifyComponents(InventoryItem item, SpriteOverlayConfigScript script, CraftRecipe recipe) {
            Object overlay = item.getComponent(ComponentType.SpriteOverlayConfig);
            check(PlayerAccess.get(overlay, "configScript") == script && "Dry_3".equals(PlayerAccess.get(overlay, "appliedStyle")),
                "overlay script or style was lost");
            var resources = (Resources) item.getComponent(ComponentType.Resources);
            for (String group : List.of("input", "output")) {
                var resource = (ResourceItem) resources.getResourcesForGroup(group).getFirst();
                check(resource.getItemAmount() == 1 && resource.peekItem().getCondition() == (group.equals("input") ? 4 : 6),
                    "contents of resource group " + group + " changed");
            }
            Object drying = item.getComponent(ComponentType.DryingCraftLogic);
            var data = (CraftRecipeData) ((List<?>) PlayerAccess.get(drying, "craftDataInProgress")).getFirst();
            check(data.getRecipe() == recipe && data.getElapsedTime() == 12.75
                && Double.valueOf(0.625).equals(((Map<?,?>) PlayerAccess.get(drying, "temporaryWetnesses")).get(data))
                && Double.valueOf(12.75).equals(((Map<?,?>) PlayerAccess.get(drying, "previousTickElapsedTimes")).get(data)),
                "drying recipe, elapsed time or wetness was lost");
        }

        static zombie.entity.Component attach(InventoryItem item, ComponentType type) throws Exception {
            var add = zombie.entity.GameEntity.class.getDeclaredMethod("addComponent", zombie.entity.Component.class);
            add.setAccessible(true);
            var component = type.CreateComponent();
            check(Boolean.TRUE.equals(add.invoke(item, component)), "could not attach " + type);
            return component;
        }

        static void saveAndDrain(ConnectionFixture connection, IsoPlayer player) {
            var database = zombie.savefile.ServerPlayerDB.getInstance();
            connection.setPlayerAt(0, player);
            try {
                database.save();
                database.saveFinishWait();
            } finally {
                connection.setPlayerAt(0, null);
            }
        }

        static long quarantineRecords() throws Exception {
            var quarantine = Path.of(System.getProperty("apocbr.postgres.playerJournal.directory"), "quarantine");
            try (var records = Files.list(quarantine)) {
                return records.filter(path -> path.toString().endsWith(".record")).count();
            }
        }
    }

    static void respawnScenario(ConnectionFixture connection, IsoPlayer previous) throws Exception {
        previous.setHealth(0);
        previous.getBodyDamage().setOverallBodyHealth(0);
        check(previous.isDead(), "fixture did not die");
        connection.setPlayerAt(0, previous);
        UUID oldLife = liveCharacter;
        respawnWrites.clear();
        var request = new zombie.network.packets.character.CreatePlayerPacket();
        PlayerReconstruction.set(request, "playerIndex", (byte) 0);
        PlayerReconstruction.set(request, "survivorDescriptor", new zombie.characters.SurvivorDesc(true));
        int before = connection.sent, denied = connection.denied;
        request.processServer(null, connection);
        check(connection.sent == before && connection.denied == denied, "respawn rejected or replied before replay");
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (connection.sent == before && connection.denied == denied && System.nanoTime() < deadline) { PlayerRuntime.update(); Thread.sleep(10); }
        check(connection.sent == before + 1 && connection.denied == denied, "respawn handoff did not send a reply");
        check(respawnWrites.equals(List.of("dead", "alive", "new-life")) && !oldLife.equals(liveCharacter), "respawn replay order changed: " + respawnWrites);
        check(connection.getPlayerAt(0) == previous, "creation changed the connection slot before vanilla release");

        var database = zombie.savefile.ServerPlayerDB.getInstance();
        database.serverUpdateNetworkCharacter(previous, 0, connection);
        database.process();
        PlayerJournal.drain();
        check(respawnWrites.size() == 3, "late save from dead occupant reached persistence");

        connection.setPlayerAt(0, null); // The native co-op exchange releases the old slot before connecting the replacement.
        PlayerRuntime.discard(previous);
        var packet = new zombie.network.packets.connection.ConnectPacket();
        packet.parse(new zombie.core.network.ByteBufferReader(java.nio.ByteBuffer.wrap(new byte[]{0,12,0})), connection);
        IsoPlayer replacement = connection.getPlayerAt(0);
        check(replacement != null && replacement != previous && !replacement.isDead(), "replacement did not reach native acceptance");
        connection.setPlayerAt(0, null);
        PlayerRuntime.discard(replacement);
    }
    static void mutationScenario(IsoPlayer player) throws Exception {
        short id = 1;
        short secondId = 2;
        IsoPlayer second = PlayerReconstruction.reconstruct(databaseProfile, 0);
        PlayerSync.register(second, "player-fixture", "second-mutation-player", 0, false, databaseProfile);
        var trackedField = PlayerMutations.class.getDeclaredField("TRACKED");
        trackedField.setAccessible(true);
        var tracked = (Map<?, ?>) trackedField.get(null);
        Object cursor = tracked.get(player);
        Object secondCursor = tracked.get(second);
        GameServer.IDToPlayerMap.put(id, player);
        try {
            // Resolve the native class after premain has installed the transformer.
            Class<?> managerType = Class.forName("zombie.network.NetworkPlayerManager");
            Object manager = managerType.getMethod("getInstance").invoke(null);
            Method update = managerType.getMethod("update");
            long deadline = System.nanoTime() + 1_000_000_000L;
            do {
                update.invoke(manager);
            } while (PlayerAccess.get(cursor, "observed") == null && System.nanoTime() < deadline);
            check((boolean) PlayerAccess.get(cursor, "observing"), "transformed simulation entry point did not observe the player");
            if (PlayerAccess.get(cursor, "observed") == null) {
                PlayerMutations.read(player);
                throw new AssertionError("transformed simulation entry point did not establish the scalar baseline");
            }
            check(!(boolean) PlayerAccess.get(secondCursor, "observing"), "registration started a simulation timer");
            GameServer.IDToPlayerMap.put(secondId, second);
            update.invoke(manager);
            check((long) PlayerAccess.get(secondCursor, "nextSample") > (long) PlayerAccess.get(cursor, "nextSample"),
                "players first observed on different ticks shared a sample deadline");
            GameServer.IDToPlayerMap.remove(secondId, second);
            PlayerRuntime.update();
            check(!PlayerMutations.hasPending() && scalarWrites.isEmpty(), "initial observation queued a scalar batch");

            player.setX(600f);
            update.invoke(manager);
            check(!PlayerMutations.hasPending(), "an intermediate value was queued before the window closed");
            player.setX(601f);
            awaitScalarWrite(update, manager, 1);

            player.setX(602f);
            awaitScalarWrite(update, manager, 2);
            String sql = "UPDATE player_rel.character_state SET x=? WHERE snapshot_id=? AND character_id=?";
            check(scalarWrites.equals(List.of(new ScalarWrite(sql, 601f, false), new ScalarWrite(sql, 602f, false))),
                "successive windows did not replay only the latest changed field on the worker: " + scalarWrites);

            long until = System.nanoTime() + 1_100_000_000L;
            while (System.nanoTime() < until) {
                update.invoke(manager);
                PlayerRuntime.update();
                Thread.sleep(10);
            }
            PlayerJournal.drain();
            check(scalarWrites.size() == 2 && !PlayerMutations.hasPending(), "unchanged window queued a scalar update");
        } finally {
            GameServer.IDToPlayerMap.remove(id, player);
            GameServer.IDToPlayerMap.remove(secondId, second);
            PlayerRuntime.discard(second);
        }
    }
    private static void awaitScalarWrite(Method update, Object manager, int count) throws Exception {
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (scalarWrites.size() < count && System.nanoTime() < deadline) {
            update.invoke(manager);
            PlayerRuntime.update();
            Thread.sleep(10);
        }
        check(scalarWrites.size() == count, "timed out waiting for scalar mutation " + count);
        PlayerJournal.drain();
    }
    static void awaitRequest() throws Exception {
        var field = ApocBRAsyncLogin.class.getDeclaredField("pending"); field.setAccessible(true);
        long deadline = System.nanoTime() + 5_000_000_000L;
        do { ApocBRAsyncLogin.update(); if (((Map<?, ?>) field.get(null)).isEmpty()) return; Thread.sleep(10); } while (System.nanoTime() < deadline);
        throw new AssertionError("profile continuation timed out");
    }
}
