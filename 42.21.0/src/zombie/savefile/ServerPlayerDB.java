// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.savefile;

import java.io.File;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.GameWindow;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

public final class ServerPlayerDB {
    public static final String PLAYER_FALLBACK_USERNAME = "Player%d";
    private static ServerPlayerDB instance;
    private static boolean allow;
    public Connection conn;
    private ConcurrentLinkedQueue<ServerPlayerDB.NetworkCharacterData> charactersToSave;

    public static void setAllow(boolean en) {
        allow = en;
    }

    public static boolean isAllow() {
        return allow;
    }

    public static synchronized ServerPlayerDB getInstance() {
        if (instance == null && allow) {
            instance = new ServerPlayerDB();
        }

        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public ServerPlayerDB() {
        if (!Core.getInstance().isNoSave()) {
            this.create();
        }
    }

    public void close() {
        instance = null;
        allow = false;
    }

    private void create() {
        if (ApocBRPlayerDatabase.enabled()) {
            this.charactersToSave = new ConcurrentLinkedQueue<>();
            try {
                new ApocBRPlayerDatabase().validate();
                DebugLog.log("[ApocBR][Postgres] player database ready; backend=PostgreSQL");
            } catch (SQLException failure) {
                String state=failure.getSQLState();
                String reason="42P01".equals(state)||"42703".equals(state)?"schema incompatible; verify backend migrations":"initialization failed; inspect connection availability and SQLState";
                throw new IllegalStateException("PostgreSQL player database "+reason,failure);
            }
            return;
        }
        this.conn = PlayerDBHelper.create();
        this.charactersToSave = new ConcurrentLinkedQueue<>();

        try {
            DatabaseMetaData md = this.conn.getMetaData();
            Statement stat = this.conn.createStatement();
            ResultSet rs = md.getColumns(null, null, "networkPlayers", "steamid");
            if (!rs.next()) {
                stat.executeUpdate("ALTER TABLE 'networkPlayers' ADD 'steamid' STRING NULL");
            }

            rs.close();
            stat.close();
        } catch (SQLException var4) {
            DebugType.General.printException(var4, LogSeverity.Error);
        }
    }

    public synchronized void process() {
        if (ApocBRPlayerDatabase.enabled()) {
            for (NetworkCharacterData data=charactersToSave.peek();data!=null;data=charactersToSave.peek()) {
                ApocBRPostgresConnector.Snapshot snapshot;
                try{snapshot=new ApocBRPostgresConnector.Snapshot(
                    Core.gameSaveWorld,data.username,data.steamid,data.playerIndex,data.playerName,
                    data.x,data.y,data.z,data.worldVersion,data.isDead,data.splitUsername,
                    GameServer.coop && SteamUtils.isSteamModeEnabled(),data.capturedAtMillis,data.buffer,data.origin);
                }catch(IllegalArgumentException invalid){
                    charactersToSave.poll();DebugLog.log("[ApocBR][Postgres] INVALID_PLAYER_SNAPSHOT; rejected=true");continue;
                }
                ApocBRPostgresConnector.enqueuePlayerSnapshot(snapshot);
                charactersToSave.poll();
            }
            return;
        }
        processSqlite();
    }

    private void processSqlite() {
        if (!this.charactersToSave.isEmpty()) {
            for (ServerPlayerDB.NetworkCharacterData characterData = this.charactersToSave.poll();
                characterData != null;
                characterData = this.charactersToSave.poll()
            ) {
                this.serverUpdateNetworkCharacterInt(characterData);
            }
        }
    }

    @Deprecated
    public void serverUpdateNetworkCharacter(ByteBuffer bb, UdpConnection connection) {
        this.charactersToSave.add(new ServerPlayerDB.NetworkCharacterData(bb, connection));
    }

    public void save() {
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            for (IsoPlayer player : connection.players) {
                if (player != null) {
                    this.serverUpdateNetworkCharacter(player, player.getIndex(), connection);
                }
            }
        }

        DebugLog.log("Saving players");
    }

    public void saveFinishWait() {
        while (!this.charactersToSave.isEmpty()) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException var2) {
                throw new RuntimeException(var2);
            }
        }
        synchronized (this) { } // Include a polled save still committing inside process().
    }

    public void serverUpdateNetworkCharacter(IsoPlayer player, int playerIndex, UdpConnection connection) {
        this.charactersToSave.add(new ServerPlayerDB.NetworkCharacterData(player, playerIndex, connection));
        if (ApocBRPlayerRelationalSync.enabled()) ApocBRPlayerRelationalMutations.finalSample(player);
    }

    private void serverUpdateNetworkCharacterInt(ServerPlayerDB.NetworkCharacterData data) {
        if (ApocBRPlayerDatabase.enabled()) {
            try {
                ApocBRPostgresConnector.savePlayerSnapshot(new ApocBRPostgresConnector.Snapshot(
                    Core.gameSaveWorld,data.username,data.steamid,data.playerIndex,data.playerName,
                    data.x,data.y,data.z,data.worldVersion,data.isDead,data.splitUsername,
                    GameServer.coop && SteamUtils.isSteamModeEnabled(),data.capturedAtMillis,data.buffer,data.origin));
            } catch (SQLException | IllegalArgumentException failure) { ExceptionLogger.logException(failure); }
            return;
        }
        if (data.playerIndex >= 0 && data.playerIndex < 4) {
            if (this.conn != null) {
                DebugType.Multiplayer.debugln("Save player \"%s\"", data.username);
                String sqlSelect;
                if (GameServer.coop && SteamUtils.isSteamModeEnabled()) {
                    sqlSelect = "SELECT id FROM networkPlayers WHERE steamid=? AND world=? AND playerIndex=?";
                } else {
                    sqlSelect = "SELECT id FROM networkPlayers WHERE username=? AND world=? AND playerIndex=?";
                }

                String sqlInsert = "INSERT INTO networkPlayers(world,username,steamid, playerIndex,name,x,y,z,worldversion,isDead,splitUsername,data) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
                String sqlUpdate = "UPDATE networkPlayers SET x=?, y=?, z=?, worldversion = ?, isDead = ?, data = ?, name = ?, splitUsername = ? WHERE id=?";

                try {
                    try (PreparedStatement pstmt = this.conn.prepareStatement(sqlSelect)) {
                        if (GameServer.coop && SteamUtils.isSteamModeEnabled()) {
                            pstmt.setString(1, data.steamid);
                        } else {
                            pstmt.setString(1, data.username);
                        }

                        pstmt.setString(2, Core.gameSaveWorld);
                        pstmt.setInt(3, data.playerIndex);
                        ResultSet rs = pstmt.executeQuery();
                        if (rs.next()) {
                            int sqlId = rs.getInt(1);

                            try (PreparedStatement pstmtUpdate = this.conn
                                    .prepareStatement(
                                        "UPDATE networkPlayers SET x=?, y=?, z=?, worldversion = ?, isDead = ?, data = ?, name = ?, splitUsername = ? WHERE id=?"
                                    )) {
                                pstmtUpdate.setFloat(1, data.x);
                                pstmtUpdate.setFloat(2, data.y);
                                pstmtUpdate.setFloat(3, data.z);
                                pstmtUpdate.setInt(4, data.worldVersion);
                                pstmtUpdate.setBoolean(5, data.isDead);
                                pstmtUpdate.setBytes(6, data.buffer);
                                pstmtUpdate.setString(7, data.playerName);
                                pstmtUpdate.setString(8, data.splitUsername);
                                pstmtUpdate.setInt(9, sqlId);
                                int rowAffected = pstmtUpdate.executeUpdate();
                                this.conn.commit();
                                return;
                            }
                        }
                    }

                    try (PreparedStatement pstmtInsert = this.conn
                            .prepareStatement(
                                "INSERT INTO networkPlayers(world,username,steamid, playerIndex,name,x,y,z,worldversion,isDead,splitUsername,data) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                            )) {
                        pstmtInsert.setString(1, Core.gameSaveWorld);
                        pstmtInsert.setString(2, data.username);
                        pstmtInsert.setString(3, data.steamid);
                        pstmtInsert.setInt(4, data.playerIndex);
                        pstmtInsert.setString(5, data.playerName);
                        pstmtInsert.setFloat(6, data.x);
                        pstmtInsert.setFloat(7, data.y);
                        pstmtInsert.setFloat(8, data.z);
                        pstmtInsert.setInt(9, data.worldVersion);
                        pstmtInsert.setBoolean(10, data.isDead);
                        pstmtInsert.setString(11, data.splitUsername);
                        pstmtInsert.setBytes(12, data.buffer);
                        int rowAffected = pstmtInsert.executeUpdate();
                        this.conn.commit();
                    }
                } catch (Exception var17) {
                    ExceptionLogger.logException(var17);
                    PlayerDBHelper.rollback(this.conn);
                }
            }
        }
    }

    public void serverConvertNetworkCharacter(String username, String steamIdStr) {
        if (ApocBRPlayerDatabase.enabled()) {
            try { new ApocBRPlayerDatabase().convert(Core.gameSaveWorld,username,steamIdStr); }
            catch (SQLException failure) { ExceptionLogger.logException(failure); }
            return;
        }
        try {
            String sqlUpdate = "UPDATE networkPlayers SET steamid=? WHERE username=? AND world=? AND (steamid is null or steamid = '')";

            try (PreparedStatement pstmt = this.conn
                    .prepareStatement("UPDATE networkPlayers SET steamid=? WHERE username=? AND world=? AND (steamid is null or steamid = '')")) {
                pstmt.setString(1, steamIdStr);
                pstmt.setString(2, username);
                pstmt.setString(3, Core.gameSaveWorld);
                int rowAffected = pstmt.executeUpdate();
                if (rowAffected > 0) {
                    DebugType.DetailedInfo
                        .warn("serverConvertNetworkCharacter: The steamid was set for the '" + username + "' for " + rowAffected + " players. ");
                }

                this.conn.commit();
            }
        } catch (SQLException var9) {
            ExceptionLogger.logException(var9);
        }
    }

    public IsoPlayer serverLoadNetworkCharacter(int playerIndex, String idStr) {
        if (ApocBRPlayerDatabase.enabled()) return this.loadPostgresPlayer(playerIndex,idStr);
        if (playerIndex < 0 || playerIndex >= 4) {
            return null;
        } else if (this.conn == null) {
            return null;
        } else {
            String sqlSelect;
            if (GameServer.coop && SteamUtils.isSteamModeEnabled()) {
                sqlSelect = "SELECT id, x, y, z, data, worldversion, isDead FROM networkPlayers WHERE steamid=? AND world=? AND playerIndex=?";
            } else {
                sqlSelect = "SELECT id, x, y, z, data, worldversion, isDead FROM networkPlayers WHERE username=? AND world=? AND playerIndex=?";
            }

            try {
                Object sqlId;
                try (PreparedStatement pstmt = this.conn.prepareStatement(sqlSelect)) {
                    pstmt.setString(1, idStr);
                    pstmt.setString(2, Core.gameSaveWorld);
                    pstmt.setInt(3, playerIndex);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        int sqlIdx = rs.getInt(1);
                        float x = rs.getFloat(2);
                        float y = rs.getFloat(3);
                        float z = rs.getFloat(4);
                        byte[] data = rs.getBytes(5);
                        int worldversion = rs.getInt(6);
                        boolean isDead = rs.getBoolean(7);

                        try {
                            ByteBuffer bufferForLoadPlayer = ByteBuffer.allocate(data.length);
                            bufferForLoadPlayer.rewind();
                            bufferForLoadPlayer.put(data);
                            bufferForLoadPlayer.rewind();
                            IsoPlayer player = new IsoPlayer(IsoWorld.instance.currentCell);
                            player.serverPlayerIndex = playerIndex;

                            java.util.List<String> relationalLoadIssues;
                            try (ApocBRPlayerLoadQuality quality = ApocBRPlayerRelationalSync.enabled() ? ApocBRPlayerLoadQuality.begin() : null) {
                                player.load(bufferForLoadPlayer, worldversion);
                                relationalLoadIssues = quality == null ? java.util.List.of() : quality.issues();
                            } catch (Exception var22) {
                                DebugType.General.printException(var22, "The server cannot load player data.", LogSeverity.Error);
                                rs.close();
                                pstmt.close();
                                if (GameServer.coop && SteamUtils.isSteamModeEnabled()) {
                                    sqlSelect = "DELETE FROM networkPlayers WHERE steamid=? AND world=? AND playerIndex=?";
                                } else {
                                    sqlSelect = "DELETE FROM networkPlayers WHERE username=? AND world=? AND playerIndex=?";
                                }

                                try (PreparedStatement pstmt2 = this.conn.prepareStatement(sqlSelect)) {
                                    pstmt2.setString(1, idStr);
                                    pstmt2.setString(2, Core.gameSaveWorld);
                                    pstmt2.setInt(3, playerIndex);
                                    pstmt2.executeUpdate();
                                    pstmt2.close();
                                }

                                return null;
                            }

                            if (isDead) {
                                player.getBodyDamage().setOverallBodyHealth(0.0F);
                                player.setHealth(0.0F);
                            }

                            player.remote = true;
                            ApocBRPlayerRelationalSync.offerLoadedPlayer(player,sqlIdx,Core.gameSaveWorld,idStr,
                                    playerIndex,worldversion,data,relationalLoadIssues);
                            return player;
                        } catch (Exception var23) {
                            ExceptionLogger.logException(var23);
                            return null;
                        }
                    }

                    sqlId = null;
                }

                return (IsoPlayer)sqlId;
            } catch (SQLException var25) {
                ExceptionLogger.logException(var25);
                return null;
            }
        }
    }

    public String getNetworkUserSteamID(String saveDir, String name, String world) throws SQLException {
        if (ApocBRPlayerDatabase.enabled()) return new ApocBRPlayerDatabase().steamId(world,name);
        if (this.conn == null) {
            return null;
        } else {
            File dbFile = new File(saveDir + File.separator + "players.db");
            if (!dbFile.exists()) {
                return null;
            } else {
                dbFile.setReadable(true, false);

                try {
                    String steamID = null;
                    String sql = "SELECT steamid FROM networkPlayers WHERE world = ? and username = ?";

                    String var9;
                    try (PreparedStatement pstmt = this.conn.prepareStatement("SELECT steamid FROM networkPlayers WHERE world = ? and username = ?")) {
                        pstmt.setString(1, world);
                        pstmt.setString(2, name);
                        ResultSet rs = pstmt.executeQuery();
                        if (rs.next()) {
                            steamID = rs.getString(1);
                        }

                        this.conn.commit();
                        var9 = steamID;
                    }

                    return var9;
                } catch (Exception var12) {
                    DebugType.Multiplayer.printException(var12, "Query execution failed", LogSeverity.Error);
                    return null;
                }
            }
        }
    }

    public int countOtherNetworkAccounts(String steamid,String username) throws SQLException {
        if (ApocBRPlayerDatabase.enabled()) return new ApocBRPlayerDatabase().countOtherAccounts(steamid,username);
        try (PreparedStatement statement=this.conn.prepareStatement("SELECT COUNT(id) FROM networkPlayers WHERE steamid=? AND username<>?")) {
            statement.setString(1,steamid); statement.setString(2,username);
            try (ResultSet rows=statement.executeQuery()) { return rows.next()?rows.getInt(1):0; }
        }
    }

    /** Build the vanilla client wire payload from authoritative rows, never a source-dictionary blob.
     * Packet dispatch invokes this on the server simulation thread. No import or live registration
     * is offered for this temporary player; the subsequent player-connect performs that setup. */
    public ApocBRPlayerDatabase.Profile serverLoadPostgresClientProfile(int slot,String identity,boolean coop,String username) throws SQLException,IOException {
        try(var trace=ApocBRPlayerReconstructionLog.begin("CLIENT_PROFILE",slot)) {
        trace.lookup(Core.gameSaveWorld,identity,coop);trace.stage("CHECK_READ_ADMISSION");
        try {
        if(!ApocBRPostgresConnector.playerReadable(Core.gameSaveWorld,identity,slot,coop)){
            trace.event("READ_BLOCKED","; availability="+ApocBRPostgresConnector.availability());throw new SQLException("Player persistence is pending","55000");
        }
        var profile=new ApocBRPlayerRelationalReader().load(Core.gameSaveWorld,identity,slot,coop);
        return this.buildPostgresClientProfile(profile,slot,username);
        }catch(SQLException|IOException|RuntimeException|LinkageError failure){trace.failure(failure);throw failure;}
        }
    }

    public ApocBRPlayerRelationalReader.Profile readPostgresProfile(String world,String identity,int slot,boolean coop) throws SQLException {
        if(!ApocBRPostgresConnector.playerReadable(world,identity,slot,coop))throw new SQLException("Player persistence is pending","55000");
        return new ApocBRPlayerRelationalReader().load(world,identity,slot,coop);
    }

    /** Simulation-thread continuation; all source dictionaries were prepared by the reader. */
    public ApocBRPlayerDatabase.Profile buildPostgresClientProfile(ApocBRPlayerRelationalReader.Profile profile,int slot,String username) throws IOException {
        try(var trace=ApocBRPlayerReconstructionLog.begin("CLIENT_PROFILE_ENCODE",slot)) {
        try {
        if(profile==null){trace.profile(null);return null;}
        trace.stage("RECONSTRUCT_PLAYER");
        IsoPlayer player=ApocBRPlayerRelationalReconstruction.reconstruct(profile,slot);
        trace.stage("ENCODE_CLIENT_PAYLOAD");
        for(int capacity=Math.min(32768,ApocBRPlayerRelationalLimits.SOURCE_BYTES);;) {
            ByteBuffer bytes=ByteBuffer.allocate(capacity);
            try {
                player.save(bytes);
                byte[] payload=new byte[bytes.position()];bytes.flip();bytes.get(payload);
                var destination=ApocBRPayloadProvenance.localEncoding();
                trace.event("PAYLOAD_READY","; bytes="+payload.length+"; worldVersion="+IsoWorld.getWorldVersion()
                    +"; destinationServer="+(destination==null?"NONE":destination.serverId())+"; namespace="+(destination==null?"NONE":destination.namespaceId())+"; view="+(destination==null?"NONE":destination.viewId()));
                String split=profile.one("snapshot").get("split_username") instanceof String saved?saved:username;
                return new ApocBRPlayerDatabase.Profile(0,player.getX(),player.getY(),player.getZ(),payload,
                        IsoWorld.getWorldVersion(),player.isDead(),split,destination);
            }catch(BufferOverflowException full) {
                trace.event("ENCODE_GROW","; capacity="+capacity);
                if(capacity>=ApocBRPlayerRelationalLimits.SOURCE_BYTES)throw new IOException("Relational client profile exceeds payload budget");
                capacity=Math.min(capacity*2,ApocBRPlayerRelationalLimits.SOURCE_BYTES);
            }
        }
        }catch(IOException|RuntimeException|LinkageError failure){trace.failure(failure);throw failure;}
        }
    }

    private IsoPlayer loadPostgresPlayer(int slot,String identity) {
        boolean coop=GameServer.coop && SteamUtils.isSteamModeEnabled();
        try(var trace=ApocBRPlayerReconstructionLog.begin("PLAYER_CONNECT",slot)) {
        trace.lookup(Core.gameSaveWorld,identity,coop);trace.stage("CHECK_READ_ADMISSION");
        if(!ApocBRPostgresConnector.playerReadable(Core.gameSaveWorld,identity,slot,coop)){trace.event("READ_BLOCKED","; availability="+ApocBRPostgresConnector.availability());return null;}
        try {
            ApocBRPlayerRelationalReader.Profile profile=new ApocBRPlayerRelationalReader().load(Core.gameSaveWorld,identity,slot,coop);
            if (profile==null){trace.profile(null);return null;}
            IsoPlayer player=finishPostgresPlayer(profile,slot,identity,coop);
            trace.event("PLAYER_READY","");
            return player;
        } catch (Exception failure) { trace.failure(failure);ExceptionLogger.logException(failure); return null; }
        }
    }

    public IsoPlayer finishPostgresPlayer(ApocBRPlayerRelationalReader.Profile profile,int slot,String identity,boolean coop) {
        if(!ApocBRPostgresConnector.playerReadable(Core.gameSaveWorld,identity,slot,coop))throw new IllegalStateException("Player persistence is pending");
        if(profile==null)return null;
        IsoPlayer player=ApocBRPlayerRelationalReconstruction.reconstruct(profile,slot);
        ApocBRPlayerRelationalSync.registerPostgresPlayer(player,Core.gameSaveWorld,identity,slot,coop,profile);
        return player;
    }

    private static final class NetworkCharacterData {
        final long capturedAtMillis = System.currentTimeMillis();
        ApocBRPayloadProvenance origin;
        byte[] buffer;
        String username;
        String steamid;
        int playerIndex;
        String playerName;
        float x;
        float y;
        float z;
        boolean isDead;
        String splitUsername;
        int worldVersion;

        public NetworkCharacterData(IsoPlayer player, int playerIndex, UdpConnection connection) {
            this.playerIndex = playerIndex;
            this.playerName = player.getDescriptor().getForename() + " " + player.getDescriptor().getSurname();
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.isDead = player.isDead();
            this.splitUsername = player.username;
            this.worldVersion = IsoWorld.getWorldVersion();
            int bufferSize = 32768;

            while (true) {
                try {
                    ByteBuffer sliceBuffer4NetworkPlayer = ByteBuffer.allocate(bufferSize);
                    ApocBRPayloadProvenance encodingOrigin=ApocBRPayloadProvenance.localEncoding();
                    player.save(sliceBuffer4NetworkPlayer);
                    this.origin=encodingOrigin;
                    this.buffer = new byte[sliceBuffer4NetworkPlayer.position()];
                    sliceBuffer4NetworkPlayer.rewind();
                    sliceBuffer4NetworkPlayer.get(this.buffer);
                    break;
                } catch (BufferOverflowException var6) {
                    if (bufferSize >= 1048576) {
                        DebugType.General.printException(var6, LogSeverity.Error);
                        break;
                    }

                    bufferSize += 32768;
                } catch (IOException var7) {
                    DebugType.General.printException(var7, LogSeverity.Error);
                    break;
                }
            }

            if (SteamUtils.isSteamModeEnabled()) {
                this.steamid = connection.getIDStr();
            } else {
                this.steamid = "";
            }

            this.username = connection.getUserName();
        }

        @Deprecated
        public NetworkCharacterData(ByteBuffer bb, UdpConnection connection) {
            this.playerIndex = bb.get();
            this.playerName = GameWindow.ReadString(bb);
            this.x = bb.getFloat();
            this.y = bb.getFloat();
            this.z = bb.getFloat();
            this.isDead = bb.get() != 0;
            this.splitUsername = String.format("Player%d", this.playerIndex + 1);
            this.worldVersion = bb.getInt();
            int size = bb.getInt();
            this.buffer = new byte[size];
            bb.get(this.buffer);
            if (GameServer.coop && SteamUtils.isSteamModeEnabled()) {
                this.steamid = connection.getIDStr();
            } else {
                this.steamid = "";
            }

            this.username = connection.getUserName();
        }
    }
}
