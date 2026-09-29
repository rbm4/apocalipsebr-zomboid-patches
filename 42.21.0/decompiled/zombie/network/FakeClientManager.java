// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import com.google.common.collect.Sets;
import fmod.fmod.FMODManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONObject;
import zombie.AttackType;
import zombie.characters.Capability;
import zombie.characters.CharacterGender;
import zombie.characters.NetworkPlayerVariables;
import zombie.characters.Role;
import zombie.characters.SurvivorDesc;
import zombie.core.Color;
import zombie.core.Colors;
import zombie.core.Core;
import zombie.core.ThreadGroups;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.RakVoice;
import zombie.core.raknet.VoiceManager;
import zombie.core.random.Rand;
import zombie.core.random.RandLua;
import zombie.core.random.RandStandard;
import zombie.core.secure.PZcrypt;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoDirections;
import zombie.iso.IsoUtils;
import zombie.iso.Vector2;
import zombie.network.fields.hit.AttackVars;
import zombie.network.fields.hit.Fall;
import zombie.network.fields.hit.HitInfo;
import zombie.network.fields.hit.Weapon;
import zombie.network.fields.hit.WeaponHit;
import zombie.network.packets.character.PlayerPacket;
import zombie.network.packets.character.ZombiePacket;
import zombie.pathfind.PathFindBehavior2;
import zombie.util.flags.ShortFlags;
import zombie.vehicles.VehicleManager;

public class FakeClientManager {
    private static int serverPort = 16261;
    private static final int CLIENT_PORT = 17500;
    private static final String CLIENT_ADDRESS = "0.0.0.0";
    private static final String versionNumber = Core.getInstance().getGameAndBuildVersion();
    private static final DateFormat logDateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private static int logLevel;
    private static final long startTime = System.currentTimeMillis();
    private static final HashSet<FakeClientManager.Player> players = new HashSet<>();

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException var3) {
            DebugType.General.printException(var3, LogSeverity.Error);
        }
    }

    private static HashMap<Integer, FakeClientManager.Movement> load(String filename) {
        HashMap<Integer, FakeClientManager.Movement> movements = new HashMap<>();

        try {
            Path path = Paths.get(filename);
            String jsonString = new String(Files.readAllBytes(path));
            JSONObject jsonObject = new JSONObject(jsonString);
            FakeClientManager.Movement.version = jsonObject.getString("version");
            JSONObject jsonConfig = jsonObject.getJSONObject("config");
            JSONObject jsonClient = jsonConfig.getJSONObject("client");
            JSONObject jsonConnection = jsonClient.getJSONObject("connection");
            if (jsonConnection.has("serverHost")) {
                FakeClientManager.Client.connectionServerHost = jsonConnection.getString("serverHost");
            }

            if (jsonConnection.has("serverPort")) {
                serverPort = jsonConnection.getInt("serverPort");
            }

            FakeClientManager.Client.connectionInterval = jsonConnection.getLong("interval");
            FakeClientManager.Client.connectionTimeout = jsonConnection.getLong("timeout");
            FakeClientManager.Client.connectionDelay = jsonConnection.getLong("delay");
            JSONObject jsonStatistics = jsonClient.getJSONObject("statistics");
            FakeClientManager.Client.statisticsPeriod = jsonStatistics.getInt("period");
            FakeClientManager.Client.statisticsClientID = Math.max(jsonStatistics.getInt("id"), -1);
            if (jsonClient.has("checksum")) {
                JSONObject jsonChecksum = jsonClient.getJSONObject("checksum");
                FakeClientManager.Client.luaChecksum = jsonChecksum.getString("lua");
                FakeClientManager.Client.scriptChecksum = jsonChecksum.getString("script");
                if (jsonChecksum.has("anim")) {
                    FakeClientManager.Client.animChecksum = jsonChecksum.getString("anim");
                }
            }

            if (jsonConfig.has("zombies")) {
                jsonConnection = jsonConfig.getJSONObject("zombies");
                FakeClientManager.ZombieSimulator.Behaviour behaviour = FakeClientManager.ZombieSimulator.Behaviour.Normal;
                if (jsonConnection.has("behaviour")) {
                    behaviour = FakeClientManager.ZombieSimulator.Behaviour.valueOf(jsonConnection.getString("behaviour"));
                }

                FakeClientManager.ZombieSimulator.behaviour = behaviour;
                if (jsonConnection.has("maxZombiesPerUpdate")) {
                    FakeClientManager.ZombieSimulator.maxZombiesPerUpdate = jsonConnection.getInt("maxZombiesPerUpdate");
                }

                if (jsonConnection.has("deleteZombieDistance")) {
                    int val = jsonConnection.getInt("deleteZombieDistance");
                    FakeClientManager.ZombieSimulator.deleteZombieDistanceSquared = val * val;
                }

                if (jsonConnection.has("forgotZombieDistance")) {
                    int val = jsonConnection.getInt("forgotZombieDistance");
                    FakeClientManager.ZombieSimulator.forgotZombieDistanceSquared = val * val;
                }

                if (jsonConnection.has("canSeeZombieDistance")) {
                    int val = jsonConnection.getInt("canSeeZombieDistance");
                    FakeClientManager.ZombieSimulator.canSeeZombieDistanceSquared = val * val;
                }

                if (jsonConnection.has("seeZombieDistance")) {
                    int val = jsonConnection.getInt("seeZombieDistance");
                    FakeClientManager.ZombieSimulator.seeZombieDistanceSquared = val * val;
                }

                if (jsonConnection.has("canChangeTarget")) {
                    FakeClientManager.ZombieSimulator.canChangeTarget = jsonConnection.getBoolean("canChangeTarget");
                }
            }

            jsonConnection = jsonConfig.getJSONObject("player");
            FakeClientManager.Player.fps = jsonConnection.getInt("fps");
            FakeClientManager.Player.predictInterval = jsonConnection.getInt("predict");
            if (jsonConnection.has("damage")) {
                FakeClientManager.Player.damage = (float)jsonConnection.getDouble("damage");
            }

            if (jsonConnection.has("voip")) {
                FakeClientManager.Player.isVOIPEnabled = jsonConnection.getBoolean("voip");
            }

            jsonStatistics = jsonConfig.getJSONObject("movement");
            FakeClientManager.Movement.defaultRadius = jsonStatistics.getInt("radius");
            JSONObject jsonMotion = jsonStatistics.getJSONObject("motion");
            FakeClientManager.Movement.aimSpeed = jsonMotion.getInt("aim");
            FakeClientManager.Movement.sneakSpeed = jsonMotion.getInt("sneak");
            FakeClientManager.Movement.sneakRunSpeed = jsonMotion.getInt("sneakrun");
            FakeClientManager.Movement.walkSpeed = jsonMotion.getInt("walk");
            FakeClientManager.Movement.runSpeed = jsonMotion.getInt("run");
            FakeClientManager.Movement.sprintSpeed = jsonMotion.getInt("sprint");
            JSONObject jsonPedestrianSpeed = jsonMotion.getJSONObject("pedestrian");
            FakeClientManager.Movement.pedestrianSpeedMin = jsonPedestrianSpeed.getInt("min");
            FakeClientManager.Movement.pedestrianSpeedMax = jsonPedestrianSpeed.getInt("max");
            JSONObject jsonVehicleSpeed = jsonMotion.getJSONObject("vehicle");
            FakeClientManager.Movement.vehicleSpeedMin = jsonVehicleSpeed.getInt("min");
            FakeClientManager.Movement.vehicleSpeedMax = jsonVehicleSpeed.getInt("max");
            JSONArray jsonMovements = jsonObject.getJSONArray("movements");

            for (int i = 0; i < jsonMovements.length(); i++) {
                jsonStatistics = jsonMovements.getJSONObject(i);
                int id = jsonStatistics.getInt("id");
                String description = null;
                if (jsonStatistics.has("description")) {
                    description = jsonStatistics.getString("description");
                }

                int spawnX = (int)Math.round(Math.random() * 6000.0 + 6000.0);
                int spawnY = (int)Math.round(Math.random() * 6000.0 + 6000.0);
                if (jsonStatistics.has("spawn")) {
                    JSONObject jsonSpawn = jsonStatistics.getJSONObject("spawn");
                    spawnX = jsonSpawn.getInt("x");
                    spawnY = jsonSpawn.getInt("y");
                }

                FakeClientManager.Movement.Motion motion = Math.random() > 0.8F
                    ? FakeClientManager.Movement.Motion.Vehicle
                    : FakeClientManager.Movement.Motion.Pedestrian;
                if (jsonStatistics.has("motion")) {
                    motion = FakeClientManager.Movement.Motion.valueOf(jsonStatistics.getString("motion"));
                }

                int speed = 0;
                if (jsonStatistics.has("speed")) {
                    speed = jsonStatistics.getInt("speed");
                } else {
                    switch (motion) {
                        case Aim:
                            speed = FakeClientManager.Movement.aimSpeed;
                            break;
                        case Sneak:
                            speed = FakeClientManager.Movement.sneakSpeed;
                            break;
                        case Walk:
                            speed = FakeClientManager.Movement.walkSpeed;
                            break;
                        case SneakRun:
                            speed = FakeClientManager.Movement.sneakRunSpeed;
                            break;
                        case Run:
                            speed = FakeClientManager.Movement.runSpeed;
                            break;
                        case Sprint:
                            speed = FakeClientManager.Movement.sprintSpeed;
                            break;
                        case Pedestrian:
                            speed = (int)Math.round(
                                Math.random() * (FakeClientManager.Movement.pedestrianSpeedMax - FakeClientManager.Movement.pedestrianSpeedMin)
                                    + FakeClientManager.Movement.pedestrianSpeedMin
                            );
                            break;
                        case Vehicle:
                            speed = (int)Math.round(
                                Math.random() * (FakeClientManager.Movement.vehicleSpeedMax - FakeClientManager.Movement.vehicleSpeedMin)
                                    + FakeClientManager.Movement.vehicleSpeedMin
                            );
                    }
                }

                FakeClientManager.Movement.Type type = FakeClientManager.Movement.Type.Line;
                if (jsonStatistics.has("type")) {
                    type = FakeClientManager.Movement.Type.valueOf(jsonStatistics.getString("type"));
                }

                int radius = FakeClientManager.Movement.defaultRadius;
                if (jsonStatistics.has("radius")) {
                    radius = jsonStatistics.getInt("radius");
                }

                IsoDirections direction = IsoDirections.getRandom();
                if (jsonStatistics.has("direction")) {
                    direction = IsoDirections.valueOf(jsonStatistics.getString("direction"));
                }

                boolean ghost = false;
                if (jsonStatistics.has("ghost")) {
                    ghost = jsonStatistics.getBoolean("ghost");
                }

                long connectDelay = id * FakeClientManager.Client.connectionInterval;
                if (jsonStatistics.has("connect")) {
                    connectDelay = jsonStatistics.getLong("connect");
                }

                long disconnectDelay = 0L;
                if (jsonStatistics.has("disconnect")) {
                    disconnectDelay = jsonStatistics.getLong("disconnect");
                }

                long reconnectDelay = 0L;
                if (jsonStatistics.has("reconnect")) {
                    reconnectDelay = jsonStatistics.getLong("reconnect");
                }

                long teleportDelay = 0L;
                if (jsonStatistics.has("teleport")) {
                    teleportDelay = jsonStatistics.getLong("teleport");
                }

                int destinationX = (int)Math.round(Math.random() * 6000.0 + 6000.0);
                int destinationY = (int)Math.round(Math.random() * 6000.0 + 6000.0);
                if (jsonStatistics.has("destination")) {
                    JSONObject jsonSpawn = jsonStatistics.getJSONObject("destination");
                    destinationX = jsonSpawn.getInt("x");
                    destinationY = jsonSpawn.getInt("y");
                }

                FakeClientManager.HordeCreator hordeCreator = null;
                if (jsonStatistics.has("createHorde")) {
                    JSONObject jsonCreateHorde = jsonStatistics.getJSONObject("createHorde");
                    int hordeCount = jsonCreateHorde.getInt("count");
                    int hordeRadius = jsonCreateHorde.getInt("radius");
                    long hordeInterval = jsonCreateHorde.getLong("interval");
                    if (hordeInterval != 0L) {
                        hordeCreator = new FakeClientManager.HordeCreator(hordeRadius, hordeCount, hordeInterval);
                    }
                }

                FakeClientManager.SoundMaker soundMaker = null;
                if (jsonStatistics.has("makeSound")) {
                    JSONObject jsonMakeSound = jsonStatistics.getJSONObject("makeSound");
                    int makeSoundInterval = jsonMakeSound.getInt("interval");
                    int makeSoundRadius = jsonMakeSound.getInt("radius");
                    String makeSoundMessage = jsonMakeSound.getString("message");
                    if (makeSoundInterval != 0) {
                        soundMaker = new FakeClientManager.SoundMaker(makeSoundInterval, makeSoundRadius, makeSoundMessage);
                    }
                }

                FakeClientManager.Movement movement = new FakeClientManager.Movement(
                    id,
                    description,
                    spawnX,
                    spawnY,
                    motion,
                    speed,
                    type,
                    radius,
                    destinationX,
                    destinationY,
                    direction,
                    ghost,
                    connectDelay,
                    disconnectDelay,
                    reconnectDelay,
                    teleportDelay,
                    hordeCreator,
                    soundMaker
                );
                if (movements.containsKey(id)) {
                    error(id, String.format("Client %d already exists", movement.id));
                } else {
                    movements.put(id, movement);
                }
            }

            return movements;
        } catch (Exception var38) {
            error(-1, "Scenarios file load failed");
            DebugType.General.printException(var38, LogSeverity.Error, "Scenarios file load failed");
            return movements;
        } finally {
            ;
        }
    }

    private static void error(int id, String message) {
        System.out.print(String.format("%5s : %s , [%2d] > %s\n", "ERROR", logDateFormat.format(Calendar.getInstance().getTime()), id, message));
    }

    private static void info(int id, String message) {
        if (logLevel >= 0) {
            System.out.print(String.format("%5s : %s , [%2d] > %s\n", "INFO", logDateFormat.format(Calendar.getInstance().getTime()), id, message));
        }
    }

    private static void log(int id, String message) {
        if (logLevel >= 1) {
            System.out.print(String.format("%5s : %s , [%2d] > %s\n", "LOG", logDateFormat.format(Calendar.getInstance().getTime()), id, message));
        }
    }

    private static void trace(int id, String message) {
        if (logLevel >= 2) {
            System.out.print(String.format("%5s : %s , [%2d] > %s\n", "TRACE", logDateFormat.format(Calendar.getInstance().getTime()), id, message));
        }
    }

    public static boolean isVOIPEnabled() {
        return FakeClientManager.Player.isVOIPEnabled && getOnlineID() != -1L && getConnectedGUID() != -1L;
    }

    public static long getConnectedGUID() {
        return players.isEmpty() ? -1L : players.iterator().next().client.connectionGuid;
    }

    public static long getOnlineID() {
        return players.isEmpty() ? -1L : players.iterator().next().onlineId;
    }

    public static String getUsername(int clientID) {
        return String.format("Client%d", clientID);
    }

    public static void main(String[] args) {
        String filename = null;
        int clientID = -1;

        for (int n = 0; n < args.length; n++) {
            if (args[n].startsWith("-scenarios=")) {
                filename = args[n].replace("-scenarios=", "").trim();
            } else if (args[n].startsWith("-id=")) {
                clientID = Integer.parseInt(args[n].replace("-id=", "").trim());
            } else if (args[n].startsWith("-loglevel=")) {
                logLevel = Integer.parseInt(args[n].replace("-loglevel=", "").trim());
            }
        }

        if (filename == null || filename.isBlank()) {
            error(-1, "Invalid scenarios file name");
            System.exit(0);
        }

        RandStandard.INSTANCE.init();
        RandLua.INSTANCE.init();
        VehicleManager.instance = new VehicleManager();
        System.loadLibrary("RakNet64");
        System.loadLibrary("ZNetNoSteam64");
        DebugLog.setLogEnabled(DebugType.General, false);
        HashMap<Integer, FakeClientManager.Movement> movements = load(filename);
        if (FakeClientManager.Player.isVOIPEnabled) {
            FMODManager.instance.init();
            VoiceManager.instance.InitVMClient();
            VoiceManager.instance.setMode(1);
        }

        FakeClientManager.Network network;
        int port;
        if (clientID != -1) {
            port = 17500 + clientID;
            network = new FakeClientManager.Network(movements.size(), port);
        } else {
            port = 17500;
            network = new FakeClientManager.Network(movements.size(), port);
        }

        if (network.isStarted()) {
            int connectionIndex = 0;
            if (clientID != -1) {
                FakeClientManager.Movement movement = movements.get(clientID);
                if (movement != null) {
                    players.add(new FakeClientManager.Player(movement, network, connectionIndex, port));
                } else {
                    error(clientID, "Client movement not found");
                }
            } else {
                for (FakeClientManager.Movement movement : movements.values()) {
                    players.add(new FakeClientManager.Player(movement, network, connectionIndex++, port));
                }
            }

            while (!players.isEmpty()) {
                sleep(1000L);
            }
        }
    }

    private static class ChunkMap {
        private static final int GRID_WIDTH = 13;
        private static final int CHUNKS_PER_WIDTH = 8;
        private static final long OBJECT_STATE_PERIOD = 100L;
        private static final int NO_REQUEST = -1;
        private static final long CHUNK_NOT_CACHED_CRC = 0L;
        private final FakeClientManager.Player player;
        private final HashMap<Integer, FakeClientManager.ChunkMap.Chunk> chunks = new HashMap<>();
        private final HashMap<Integer, FakeClientManager.ChunkMap.Chunk> sentRequests = new HashMap<>();
        private final ArrayList<FakeClientManager.ChunkMap.Chunk> requestQueue = new ArrayList<>();
        private final ArrayList<FakeClientManager.ChunkMap.Chunk> objectStateQueue = new ArrayList<>();
        private final ArrayList<Integer> cancelQueue = new ArrayList<>();
        private final UpdateLimit objectStateLimiter = new UpdateLimit(100L);
        private int worldX;
        private int worldY;
        private int requestNumber;
        private int receivedChunks;
        private boolean isLoadingArea;
        private boolean isStarted;

        public ChunkMap(FakeClientManager.Player player) {
            this.player = player;
        }

        private static int getKey(int wx, int wy) {
            return wx << 16 | wy & 65535;
        }

        public synchronized void reset() {
            this.unload();
            this.cancelQueue.clear();
            this.requestNumber = 0;
            this.isStarted = false;
        }

        public synchronized void update() {
            this.processPosition();
            this.sendRequests();
            this.sendCancelled();
            this.sendObjectStates();
            if (this.isLoadingArea && this.requestQueue.isEmpty() && this.sentRequests.isEmpty()) {
                this.isLoadingArea = false;
                FakeClientManager.info(
                    this.player.movement.id,
                    String.format("Player %3d received %d chunks around (%d,%d)", this.player.onlineId, this.receivedChunks, this.worldX, this.worldY)
                );
            }
        }

        private void processPosition() {
            int x = PZMath.fastfloor(this.player.x / 8.0F);
            int y = PZMath.fastfloor(this.player.y / 8.0F);
            if (!this.isStarted) {
                this.isStarted = true;
                this.worldX = x;
                this.worldY = y;
                this.loadArea();
            } else if (x != this.worldX || y != this.worldY) {
                if (Math.abs(x - this.worldX) >= 13 || Math.abs(y - this.worldY) >= 13) {
                    this.unload();
                    this.worldX = x;
                    this.worldY = y;
                    this.loadArea();
                } else if (x != this.worldX) {
                    if (x < this.worldX) {
                        this.loadLeft();
                    } else {
                        this.loadRight();
                    }
                } else if (y < this.worldY) {
                    this.loadUp();
                } else {
                    this.loadDown();
                }
            }
        }

        private void loadArea() {
            this.receivedChunks = 0;
            this.isLoadingArea = true;

            for (int wx = this.worldX - 6; wx <= this.worldX + 6; wx++) {
                for (int wy = this.worldY - 6; wy <= this.worldY + 6; wy++) {
                    this.loadChunkForLater(wx, wy);
                }
            }
        }

        private void loadLeft() {
            for (int wy = this.worldY - 6; wy <= this.worldY + 6; wy++) {
                this.releaseChunk(this.worldX + 6, wy);
            }

            this.worldX--;

            for (int wy = this.worldY - 6; wy <= this.worldY + 6; wy++) {
                this.loadChunkForLater(this.worldX - 6, wy);
            }
        }

        private void loadRight() {
            for (int wy = this.worldY - 6; wy <= this.worldY + 6; wy++) {
                this.releaseChunk(this.worldX - 6, wy);
            }

            this.worldX++;

            for (int wy = this.worldY - 6; wy <= this.worldY + 6; wy++) {
                this.loadChunkForLater(this.worldX + 6, wy);
            }
        }

        private void loadUp() {
            for (int wx = this.worldX - 6; wx <= this.worldX + 6; wx++) {
                this.releaseChunk(wx, this.worldY + 6);
            }

            this.worldY--;

            for (int wx = this.worldX - 6; wx <= this.worldX + 6; wx++) {
                this.loadChunkForLater(wx, this.worldY - 6);
            }
        }

        private void loadDown() {
            for (int wx = this.worldX - 6; wx <= this.worldX + 6; wx++) {
                this.releaseChunk(wx, this.worldY - 6);
            }

            this.worldY++;

            for (int wx = this.worldX - 6; wx <= this.worldX + 6; wx++) {
                this.loadChunkForLater(wx, this.worldY + 6);
            }
        }

        private void loadChunkForLater(int wx, int wy) {
            int key = getKey(wx, wy);
            if (!this.chunks.containsKey(key)) {
                FakeClientManager.ChunkMap.Chunk chunk = new FakeClientManager.ChunkMap.Chunk(wx, wy);
                this.chunks.put(key, chunk);
                this.requestQueue.add(chunk);
            }
        }

        private void releaseChunk(int wx, int wy) {
            FakeClientManager.ChunkMap.Chunk chunk = this.chunks.remove(getKey(wx, wy));
            if (chunk != null) {
                this.cancelRequest(chunk);
                this.requestQueue.remove(chunk);
                this.objectStateQueue.remove(chunk);
            }
        }

        private void cancelRequest(FakeClientManager.ChunkMap.Chunk chunk) {
            if (chunk.requestNumber != -1) {
                this.sentRequests.remove(chunk.requestNumber);
                this.cancelQueue.add(chunk.requestNumber);
                chunk.requestNumber = -1;
                chunk.parts = null;
            }
        }

        private void unload() {
            for (FakeClientManager.ChunkMap.Chunk chunk : this.chunks.values()) {
                this.cancelRequest(chunk);
            }

            this.chunks.clear();
            this.sentRequests.clear();
            this.requestQueue.clear();
            this.objectStateQueue.clear();
            this.isLoadingArea = false;
        }

        private float getDistanceSquared(FakeClientManager.ChunkMap.Chunk chunk) {
            return IsoUtils.DistanceToSquared(this.player.x, this.player.y, chunk.wx * 8 + 4.0F, chunk.wy * 8 + 4.0F);
        }

        private void sendRequests() {
            if (!this.requestQueue.isEmpty()) {
                this.requestQueue.sort((a, bx) -> Float.compare(this.getDistanceSquared(a), this.getDistanceSquared(bx)));
                ByteBufferWriter b = this.player.client.network.startPacket();
                this.player.client.doPacket(PacketTypes.PacketType.RequestZipList.getId(), b);
                b.putInt(this.requestQueue.size());

                for (FakeClientManager.ChunkMap.Chunk chunk : this.requestQueue) {
                    chunk.requestNumber = this.requestNumber++;
                    this.sentRequests.put(chunk.requestNumber, chunk);
                    b.putInt(chunk.requestNumber);
                    b.putInt(chunk.wx);
                    b.putInt(chunk.wy);
                    b.putLong(0L);
                }

                this.player.client.network.endPacketImmediate(this.player.client.connectionGuid);
                FakeClientManager.trace(
                    this.player.movement.id, String.format("Player %3d requested %d chunks", this.player.onlineId, this.requestQueue.size())
                );
                this.requestQueue.clear();
            }
        }

        private void sendCancelled() {
            if (!this.cancelQueue.isEmpty()) {
                ByteBufferWriter b = this.player.client.network.startPacket();
                this.player.client.doPacket(PacketTypes.PacketType.NotRequiredInZip.getId(), b);
                b.putInt(this.cancelQueue.size());

                for (Integer number : this.cancelQueue) {
                    b.putInt(number);
                }

                this.player.client.network.endPacketImmediate(this.player.client.connectionGuid);
                FakeClientManager.trace(this.player.movement.id, String.format("Player %3d cancelled %d chunks", this.player.onlineId, this.cancelQueue.size()));
                this.cancelQueue.clear();
            }
        }

        private void sendObjectStates() {
            if (!this.objectStateQueue.isEmpty() && this.objectStateLimiter.Check()) {
                ByteBufferWriter b = this.player.client.network.startPacket();
                this.player.client.doPacket(PacketTypes.PacketType.ChunkObjectStateRequest.getId(), b);
                b.putInt(this.objectStateQueue.size() * 2);

                for (FakeClientManager.ChunkMap.Chunk chunk : this.objectStateQueue) {
                    b.putShort(chunk.wx);
                    b.putShort(chunk.wy);
                }

                this.player.client.network.endPacket(this.player.client.connectionGuid);
                this.objectStateQueue.clear();
            }
        }

        public synchronized void receiveChunkPart(ByteBufferReader bb) {
            int number = bb.getInt();
            int numChunks = bb.getInt();
            int chunkIndex = bb.getInt();
            int fileSize = bb.getInt();
            int bytesSent = bb.getInt();
            int bytesToSend = bb.getInt();
            FakeClientManager.ChunkMap.Chunk chunk = this.sentRequests.get(number);
            if (chunk != null) {
                if (chunk.parts == null) {
                    chunk.parts = new boolean[numChunks];
                }

                chunk.parts[chunkIndex] = true;
                if (chunk.isReceived()) {
                    this.setReceived(chunk);
                }
            }
        }

        public synchronized void receiveNotRequired(ByteBufferReader bb) {
            int count = bb.getInt();

            for (int n = 0; n < count; n++) {
                int number = bb.getInt();
                boolean isSameOnServer = bb.getBoolean();
                FakeClientManager.ChunkMap.Chunk chunk = this.sentRequests.get(number);
                if (chunk != null) {
                    this.setReceived(chunk);
                }
            }
        }

        public synchronized void receiveChunkNotReady(ByteBufferReader bb) {
            int count = bb.getInt();

            for (int n = 0; n < count; n++) {
                FakeClientManager.ChunkMap.Chunk chunk = this.sentRequests.remove(bb.getInt());
                if (chunk != null) {
                    FakeClientManager.info(
                        this.player.movement.id,
                        String.format("Player %3d did not get the chunk (%d,%d) in time, requests it again", this.player.onlineId, chunk.wx, chunk.wy)
                    );
                    chunk.requestNumber = -1;
                    chunk.parts = null;
                    this.requestQueue.add(chunk);
                }
            }
        }

        private void setReceived(FakeClientManager.ChunkMap.Chunk chunk) {
            this.sentRequests.remove(chunk.requestNumber);
            chunk.requestNumber = -1;
            chunk.parts = null;
            this.objectStateQueue.add(chunk);
            this.receivedChunks++;
            FakeClientManager.trace(this.player.movement.id, String.format("Player %3d received the chunk (%d,%d)", this.player.onlineId, chunk.wx, chunk.wy));
        }

        private static final class Chunk {
            private final int wx;
            private final int wy;
            private int requestNumber = -1;
            private boolean[] parts;

            private Chunk(int wx, int wy) {
                this.wx = wx;
                this.wy = wy;
            }

            private boolean isReceived() {
                if (this.parts == null) {
                    return false;
                } else {
                    for (boolean part : this.parts) {
                        if (!part) {
                            return false;
                        }
                    }

                    return true;
                }
            }
        }
    }

    private static class Client {
        private static final byte PLAYER_INDEX = 0;
        private static final byte GHOST_MODE_FLAG = 2;
        private static final float PREDICTION_DISTANCE_SCALE = 1.2F;
        private static final String WEAPON = "Base.Axe";
        private static final String UNKNOWN_SPAWN_REGION = "";
        private static final int GAME_TIME_SIZE_WITHOUT_LUA_TABLE = 44;
        private static final byte TABLE_STRING = 0;
        private static final byte TABLE_DOUBLE = 1;
        private static final byte TABLE_TABLE = 2;
        private static final byte TABLE_BOOLEAN = 3;
        private static String connectionServerHost = "127.0.0.1";
        private static long connectionInterval = 1500L;
        private static long connectionTimeout = 10000L;
        private static long connectionDelay = 15000L;
        private static int statisticsClientID = -1;
        private static int statisticsPeriod = 1;
        private static long serverTimeShift;
        private static boolean serverTimeShiftIsSet;
        private final Role role = new Role("");
        private final zombie.network.fields.hit.Player wielder = new zombie.network.fields.hit.Player();
        private final zombie.network.fields.hit.Zombie hitTarget = new zombie.network.fields.hit.Zombie();
        private final HitInfo hitInfo = new HitInfo();
        private final Weapon hitWeapon = new Weapon();
        private final WeaponHit weaponHit = new WeaponHit();
        private final Fall hitFall = new Fall();
        private final FakeClientManager.Player player;
        private final FakeClientManager.Network network;
        private final int connectionIndex;
        private final int port;
        private boolean isCharacterCreated;
        private boolean isPlaceChosen;
        private long connectionGuid = -1L;
        private long stateTime;
        private FakeClientManager.Client.State state;
        private String host;
        public static String luaChecksum = "";
        public static String scriptChecksum = "";
        public static String animChecksum = "";

        private Client(FakeClientManager.Player player, FakeClientManager.Network network, int connectionIndex, int port) {
            this.connectionIndex = connectionIndex;
            this.network = network;
            this.player = player;
            this.port = port;

            try {
                this.host = InetAddress.getByName(connectionServerHost).getHostAddress();
                this.state = FakeClientManager.Client.State.CONNECT;
                Thread thread = new Thread(ThreadGroups.Workers, this::updateThread, this.player.username);
                thread.setDaemon(true);
                thread.start();
            } catch (UnknownHostException var6) {
                this.state = FakeClientManager.Client.State.QUIT;
                DebugType.General.printException(var6, LogSeverity.Error);
            }
        }

        private void updateThread() {
            FakeClientManager.info(
                this.player.movement.id,
                String.format(
                    "Start client (%d) %s:%d => %s:%d / \"%s\"",
                    this.connectionIndex,
                    "0.0.0.0",
                    this.port,
                    this.host,
                    FakeClientManager.serverPort,
                    this.player.movement.description
                )
            );
            FakeClientManager.sleep(this.player.movement.connectDelay);
            switch (this.player.movement.type) {
                case Line:
                    this.player.lineMovement();
                    break;
                case Circle:
                    this.player.circleMovement();
                    break;
                case AIAttackZombies:
                    this.player.aiAttackZombiesMovement();
                    break;
                case AIRunAwayFromZombies:
                    this.player.aiRunAwayFromZombiesMovement();
                    break;
                case AIRunToAnotherPlayers:
                    this.player.aiRunToAnotherPlayersMovement();
                    break;
                case AINormal:
                    this.player.aiNormalMovement();
            }

            while (this.state != FakeClientManager.Client.State.QUIT) {
                this.update();
                FakeClientManager.sleep(1L);
            }

            FakeClientManager.info(
                this.player.movement.id,
                String.format(
                    "Stop client (%d) %s:%d => %s:%d / \"%s\"",
                    this.connectionIndex,
                    "0.0.0.0",
                    this.port,
                    this.host,
                    FakeClientManager.serverPort,
                    this.player.movement.description
                )
            );
        }

        private void updateTime() {
            this.stateTime = System.currentTimeMillis();
        }

        private long getServerTime() {
            return serverTimeShiftIsSet ? System.nanoTime() + serverTimeShift : 0L;
        }

        private boolean checkConnectionTimeout() {
            return System.currentTimeMillis() - this.stateTime > connectionTimeout;
        }

        private boolean checkConnectionDelay() {
            return System.currentTimeMillis() - this.stateTime > connectionDelay;
        }

        private void changeState(FakeClientManager.Client.State newState) {
            this.updateTime();
            FakeClientManager.log(this.player.movement.id, String.format("%s >> %s", this.state, newState));
            if (FakeClientManager.Client.State.RUN == newState) {
                this.player.movement.connect(this.player.onlineId);
                if (this.player.teleportLimiter == null) {
                    this.player.teleportLimiter = new UpdateLimit(this.player.movement.teleportDelay);
                }

                if (this.player.movement.id == statisticsClientID) {
                    this.sendTimeSync();
                }
            } else if (FakeClientManager.Client.State.DISCONNECT == newState && FakeClientManager.Client.State.DISCONNECT != this.state) {
                this.player.movement.disconnect(this.player.onlineId);
            }

            this.state = newState;
        }

        private void update() {
            switch (this.state) {
                case CONNECT:
                    this.player.movement.timestamp = System.currentTimeMillis();
                    this.isCharacterCreated = false;
                    this.isPlaceChosen = false;
                    this.network.connect(this.player.movement.id, this.host);
                    this.changeState(FakeClientManager.Client.State.WAIT);
                    break;
                case LOGIN:
                    this.sendPlayerLogin();
                    this.sendPlayerLoginRequest();
                    this.changeState(FakeClientManager.Client.State.WAIT);
                    break;
                case CHECKSUM:
                    this.sendChecksum();
                    this.changeState(FakeClientManager.Client.State.WAIT);
                    break;
                case LOAD_PROFILE:
                    this.sendLoadPlayerProfile();
                    this.changeState(FakeClientManager.Client.State.WAIT);
                    break;
                case CREATE_PLAYER:
                    this.sendCreatePlayer();
                    this.changeState(FakeClientManager.Client.State.WAIT);
                    break;
                case PLAYER_CONNECT:
                    this.sendPlayerConnect();
                    this.changeState(FakeClientManager.Client.State.WAIT);
                    break;
                case RUN:
                    if (this.player.movement.doDisconnect() && this.player.movement.checkDisconnect()) {
                        this.changeState(FakeClientManager.Client.State.DISCONNECT);
                    } else {
                        this.player.run();
                    }
                    break;
                case WAIT:
                    if (this.checkConnectionTimeout()) {
                        this.changeState(FakeClientManager.Client.State.DISCONNECT);
                    }
                    break;
                case DISCONNECT:
                    if (this.network.isConnected()) {
                        this.player.movement.timestamp = System.currentTimeMillis();
                        this.network.disconnect(this.connectionGuid, this.player.movement.id, this.host);
                    }

                    if (this.player.movement.doReconnect() && this.player.movement.checkReconnect()
                        || !this.player.movement.doReconnect() && this.checkConnectionDelay()) {
                        this.changeState(FakeClientManager.Client.State.CONNECT);
                    }
                case QUIT:
            }
        }

        private void receive(short type, ByteBufferReader bb) {
            PacketTypes.PacketType packetType = PacketTypes.packetTypes.get(type);
            FakeClientManager.Network.logUserPacket(this.player.movement.id, type);
            switch (packetType) {
                case ConnectedPlayer:
                    if (this.receiveConnectedPlayer(bb)) {
                        if (luaChecksum.isEmpty()) {
                            this.requestMap();
                            this.changeState(FakeClientManager.Client.State.RUN);
                        } else {
                            this.changeState(FakeClientManager.Client.State.CHECKSUM);
                        }
                    }
                    break;
                case ExtraInfo:
                    this.receiveExtraInfo(bb);
                    break;
                case AddItemInInventory:
                    this.receiveAddItemInInventory(bb);
                    break;
                case AccessDenied:
                    this.receiveAccessDenied(bb);
                    break;
                case LoginQueueRequest:
                    if (this.receiveLoginQueueRequest(bb)) {
                        this.sendLoginQueueDone();
                        this.sendLoadPlayerProfile();
                        this.changeState(FakeClientManager.Client.State.WAIT);
                    }
                    break;
                case LoadPlayerProfile:
                    if (this.receiveLoadPlayerProfile(bb)) {
                        this.changeState(FakeClientManager.Client.State.PLAYER_CONNECT);
                    } else if (this.isCharacterCreated) {
                        FakeClientManager.error(this.player.movement.id, String.format("Character of \"%s\" was not created", this.player.username));
                        this.changeState(FakeClientManager.Client.State.DISCONNECT);
                    } else {
                        this.isCharacterCreated = true;
                        this.sendCreatePlayer();
                        this.changeState(FakeClientManager.Client.State.WAIT);
                    }
                    break;
                case CreatePlayer:
                    this.sendLoadPlayerProfile();
                    this.changeState(FakeClientManager.Client.State.WAIT);
                    break;
                case SentChunk:
                    this.player.chunkMap.receiveChunkPart(bb);
                    break;
                case NotRequiredInZip:
                    this.player.chunkMap.receiveNotRequired(bb);
                    break;
                case ChunkNotReady:
                    this.player.chunkMap.receiveChunkNotReady(bb);
                case PlayerHitSquare:
                case PlayerHitVehicle:
                case PlayerHitZombie:
                case PlayerHitPlayer:
                case PlayerHitAnimal:
                case ZombieHitPlayer:
                case AnimalHitPlayer:
                case AnimalHitAnimal:
                case AnimalHitThumpable:
                case VehicleHitZombie:
                case VehicleHitPlayer:
                default:
                    break;
                case TimeSync:
                    this.receiveTimeSync(bb);
                    break;
                case SyncClock:
                    this.receiveSyncClock(bb);
                    break;
                case ZombieSynchronizationUnreliable:
                case ZombieSynchronizationReliable:
                    this.receiveZombieSimulation(bb);
                    break;
                case ZombieList:
                    this.player.simulator.receiveZombieList(bb);
                    break;
                case ZombieDeleteOnClient:
                    this.player.simulator.receiveDeletedZombies(bb);
                    break;
                case PlayerUpdateUnreliable:
                case PlayerUpdateReliable:
                    this.player.playerManager.parsePlayer(bb);
                    break;
                case PlayerTimeout:
                    this.player.playerManager.parsePlayerTimeout(bb);
                    break;
                case Kicked:
                    this.receiveKicked(bb);
                    break;
                case Checksum:
                    this.receiveChecksum(bb);
                    break;
                case Teleport:
                    this.receiveTeleport(bb);
            }

            bb.clear();
        }

        private void doPacket(short type, ByteBufferWriter bb) {
            bb.putByte(134);
            bb.putShort(type);
        }

        private void sendPlayerLogin() {
            ByteBufferWriter bb = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.Login.getId(), bb);
            bb.putUTF(this.player.username);
            bb.putUTF(this.player.username);
            bb.putUTF(FakeClientManager.versionNumber);
            bb.putInt(1);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void sendPlayerLoginRequest() {
            ByteBufferWriter bb = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.LoginQueueRequest.getId(), bb);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void sendLoginQueueDone() {
            ByteBufferWriter bb = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.LoginQueueDone.getId(), bb);
            bb.putLong(100L);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void sendLoadPlayerProfile() {
            ByteBufferWriter bb = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.LoadPlayerProfile.getId(), bb);
            bb.putByte((byte)0);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void sendCreatePlayer() {
            FakeClientManager.info(this.player.movement.id, String.format("Creating a character for \"%s\"", this.player.username));
            ByteBufferWriter bb = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.CreatePlayer.getId(), bb);
            bb.putByte((byte)0);
            bb.putUTF("");

            try {
                this.player.survivorDesc.save(bb.bb);
                this.player.survivorDesc.getWornItems().save(bb.bb);
                this.player.survivorDesc.getHumanVisual().save(bb.bb);
            } catch (IOException var3) {
                DebugType.General.printException(var3, LogSeverity.Error);
            }

            short traitCount = 0;
            bb.putShort((short)0);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private boolean receiveLoadPlayerProfile(ByteBufferReader bb) {
            boolean isExist = bb.getBoolean();
            int playerIndex = bb.getByte();
            if (!isExist) {
                return false;
            } else {
                float x = bb.getFloat();
                float y = bb.getFloat();
                float z = bb.getFloat();
                int worldVersion = bb.getInt();
                boolean isDead = bb.getBoolean();
                String splitScreenUsername = bb.getUTF();
                FakeClientManager.info(
                    this.player.movement.id,
                    String.format("Character of \"%s\" is at (%d,%d,%d)%s", this.player.username, (int)x, (int)y, (int)z, isDead ? ", dead" : "")
                );
                if (isDead) {
                    return false;
                } else {
                    this.player.x = x;
                    this.player.y = y;
                    return true;
                }
            }
        }

        private void sendPlayerConnect() {
            ByteBufferWriter bb = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.PlayerConnect.getId(), bb);
            this.writePlayerConnectData(bb);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void writePlayerConnectData(ByteBufferWriter b) {
            b.putByte((byte)0);
            b.putByte(13);
            b.putByte((byte)(this.player.movement.ghost ? 2 : 0));
        }

        private void sendSyncRadioData() {
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.SyncRadioData.getId(), b);
            b.putBoolean(FakeClientManager.Player.isVOIPEnabled);
            b.putInt(4);
            b.putInt(0);
            b.putInt((int)RakVoice.GetMaxDistance());
            b.putInt((int)this.player.x);
            b.putInt((int)this.player.y);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private ShortFlags getBooleanVariables() {
            ShortFlags booleanVariables = ShortFlags.alloc();
            if (this.player.isMoving()) {
                booleanVariables.set(NetworkPlayerVariables.Flags.hasDeferredMovement);
                switch (this.player.movement.motion) {
                    case Sneak:
                        booleanVariables.set(NetworkPlayerVariables.Flags.isSneaking);
                    case Walk:
                    default:
                        break;
                    case SneakRun:
                        booleanVariables.set(NetworkPlayerVariables.Flags.isSneaking);
                        booleanVariables.set(NetworkPlayerVariables.Flags.isRunning);
                        break;
                    case Run:
                        booleanVariables.set(NetworkPlayerVariables.Flags.isRunning);
                        break;
                    case Sprint:
                        booleanVariables.set(NetworkPlayerVariables.Flags.isSprinting);
                }
            }

            return booleanVariables;
        }

        private void sendPlayer() {
            PlayerPacket packet = new PlayerPacket();
            packet.id.setID(this.player.onlineId);
            packet.prediction.x = this.player.x;
            packet.prediction.y = this.player.y;
            packet.prediction.z = (byte)this.player.z;
            packet.prediction.direction = this.player.direction.getDirection();
            if (this.player.isMoving()) {
                float speed = this.player.getSpeed();
                float distance = speed * FakeClientManager.Player.predictInterval * 0.001F * 1.2F;
                packet.prediction.moveDirection = this.player.direction.getDirection();
                packet.prediction.speed = speed;
                packet.prediction.distance = (byte)PZMath.min(distance * 8.0F, 127.0F);
                packet.prediction.type = 1;
            } else {
                packet.prediction.type = 0;
            }

            packet.booleanVariables = this.getBooleanVariables().asShort();
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.PlayerUpdateReliable.getId(), b);
            packet.write(b);
            this.network.endPacket(this.connectionGuid);
        }

        private boolean receiveConnectedPlayer(ByteBufferReader bb) {
            if (bb.getShort() != -1) {
                return false;
            } else {
                int playerIndex = bb.getByte();
                this.player.onlineId = bb.getShort();
                this.skipGameTime(bb);
                this.player.bareHandsId = bb.getInt();
                FakeClientManager.info(this.player.movement.id, String.format("Player %3d connected as \"%s\"", this.player.onlineId, this.player.username));
                return true;
            }
        }

        private void skipGameTime(ByteBufferReader bb) {
            bb.bb.position(bb.bb.position() + 44);
            if (bb.getBoolean()) {
                this.skipTable(bb);
            }
        }

        private void skipTable(ByteBufferReader bb) {
            int count = bb.getInt();

            for (int n = 0; n < count; n++) {
                this.skipTableValue(bb, bb.getByte());
                this.skipTableValue(bb, bb.getByte());
            }
        }

        private void skipTableValue(ByteBufferReader bb, byte type) {
            switch (type) {
                case 0:
                    bb.getUTF();
                    break;
                case 1:
                    bb.getDouble();
                    break;
                case 2:
                    this.skipTable(bb);
                    break;
                case 3:
                    bb.getByte();
                    break;
                default:
                    throw new IllegalStateException("Unknown lua table type " + type);
            }
        }

        private void receiveAccessDenied(ByteBufferReader bb) {
            FakeClientManager.error(this.player.movement.id, String.format("Access denied: %s", bb.getUTF()));
        }

        private void receiveExtraInfo(ByteBufferReader bb) {
            short id = bb.getShort();
            byte playerIndex = bb.getByte();
            if (id == this.player.onlineId && !this.isPlaceChosen) {
                this.isPlaceChosen = true;
                this.role.parse(bb);
                if (this.player.movement.hordeCreator != null && !this.canCreateHorde()) {
                    FakeClientManager.info(
                        this.player.movement.id, String.format("Player %3d as \"%s\" cannot create hordes", this.player.onlineId, this.role.getName())
                    );
                }

                if (this.role.hasCapability(Capability.AddItem)) {
                    this.sendCommand(String.format("/additem %s", "Base.Axe"));
                } else {
                    FakeClientManager.info(
                        this.player.movement.id,
                        String.format("Player %3d as \"%s\" cannot spawn a weapon, it hits with bare hands", this.player.onlineId, this.role.getName())
                    );
                }

                if (this.role.hasCapability(Capability.TeleportToCoordinates)) {
                    FakeClientManager.info(
                        this.player.movement.id,
                        String.format(
                            "Player %3d as \"%s\" teleports to (%d,%d)",
                            this.player.onlineId,
                            this.role.getName(),
                            (int)this.player.movement.spawn.x,
                            (int)this.player.movement.spawn.y
                        )
                    );
                    this.player.x = this.player.movement.spawn.x;
                    this.player.y = this.player.movement.spawn.y;
                    this.sendTeleport(this.player.x, this.player.y, this.player.z);
                } else {
                    FakeClientManager.info(
                        this.player.movement.id,
                        String.format(
                            "Player %3d as \"%s\" cannot teleport, moves the scenario to (%d,%d)",
                            this.player.onlineId,
                            this.role.getName(),
                            (int)this.player.x,
                            (int)this.player.y
                        )
                    );
                    this.player.movement.moveTo(this.player.x, this.player.y);
                }
            }
        }

        private void receiveAddItemInInventory(ByteBufferReader bb) {
            short id = bb.getShort();
            byte playerIndex = bb.getByte();
            if (id == this.player.onlineId && bb.getShort() > 0) {
                short itemRegistryId = bb.getShort();
                byte itemSaveType = bb.getByte();
                this.player.weaponId = bb.getInt();
                FakeClientManager.info(this.player.movement.id, String.format("Player %3d got %s (%d)", this.player.onlineId, "Base.Axe", this.player.weaponId));
                this.sendEquip();
            }
        }

        private void sendEquip() {
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.Equip.getId(), b);
            b.putShort(this.player.onlineId);
            b.putByte((byte)0);
            int primaryHandItem = this.player.weaponId;
            int secondaryHandItem = -1;
            b.putInt(primaryHandItem);
            b.putInt(-1);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private boolean canCreateHorde() {
            return this.role.hasCapability(Capability.CreateHorde);
        }

        private void sendTeleport(float x, float y, float z) {
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.Teleport.getId(), b);
            b.putShort(this.player.onlineId);
            b.putByte((byte)0);
            b.putFloat(x);
            b.putFloat(y);
            b.putFloat(z);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private boolean receiveLoginQueueRequest(ByteBufferReader bb) {
            return bb.getBoolean();
        }

        private void requestMap() {
            this.player.chunkMap.reset();
            this.requestFullUpdate();
        }

        private void requestFullUpdate() {
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.IsoRegionClientRequestFullUpdate.getId(), b);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void receiveStatistics(ByteBufferReader bb) {
            long min = bb.getLong();
            long max = bb.getLong();
            long avg = bb.getLong();
            long fps = bb.getLong();
            long tps = bb.getLong();
            long con = bb.getLong();
            long c1 = bb.getLong();
            long c2 = bb.getLong();
            long c3 = bb.getLong();
            FakeClientManager.info(
                this.player.movement.id,
                String.format("ServerStats: con=[%2d] fps=[%2d] tps=[%2d] upt=[%4d-%4d/%4d], c1=[%d] c2=[%d] c3=[%d]", con, fps, tps, min, max, avg, c1, c2, c3)
            );
        }

        private void sendTimeSync() {
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.TimeSync.getId(), b);
            long timeNsClient = System.nanoTime();
            b.putLong(timeNsClient);
            b.putLong(0L);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void receiveTimeSync(ByteBufferReader bb) {
            long timeClientSend = bb.getLong();
            long timeServer = bb.getLong();
            long timeClientReceive = System.nanoTime();
            long localPing = timeClientReceive - timeClientSend;
            long localServerTimeShift = timeServer - timeClientReceive + localPing / 2L;
            long serverTimeShiftLast = serverTimeShift;
            if (!serverTimeShiftIsSet) {
                serverTimeShift = localServerTimeShift;
            } else {
                serverTimeShift = (long)((float)serverTimeShift + (float)(localServerTimeShift - serverTimeShift) * 0.05F);
            }

            long serverTimeuQality = 10000000L;
            if (Math.abs(serverTimeShift - serverTimeShiftLast) > 10000000L) {
                this.sendTimeSync();
            } else {
                serverTimeShiftIsSet = true;
            }
        }

        private void receiveSyncClock(ByteBufferReader bb) {
            FakeClientManager.trace(this.player.movement.id, String.format("Player %3d sync clock", this.player.onlineId));
        }

        private void receiveKicked(ByteBufferReader bb) {
            String chat = bb.getUTF();
            FakeClientManager.info(this.player.movement.id, String.format("Client kicked. Reason: %s", chat));
        }

        private void receiveChecksum(ByteBufferReader bb) {
            FakeClientManager.trace(this.player.movement.id, String.format("Player %3d receive Checksum", this.player.onlineId));
            short packetTotalChecksum = bb.getShort();
            boolean isLuaOk = bb.getBoolean();
            boolean isScriptOk = bb.getBoolean();
            boolean isAnimOk = bb.getBoolean();
            if (packetTotalChecksum != 1 || !isLuaOk || !isScriptOk || !isAnimOk) {
                FakeClientManager.info(this.player.movement.id, String.format("checksum lua: %b, script: %b, anim: %b", isLuaOk, isScriptOk, isAnimOk));
            }

            this.requestMap();
            this.changeState(FakeClientManager.Client.State.RUN);
        }

        private void receiveTeleport(ByteBufferReader bb) {
            short id = bb.getShort();
            byte playerIndex = bb.getByte();
            float x = bb.getFloat();
            float y = bb.getFloat();
            float z = bb.getFloat();
            if (id == this.player.onlineId) {
                FakeClientManager.info(this.player.movement.id, String.format("Player %3d teleport to (%d, %d)", this.player.onlineId, (int)x, (int)y));
                this.player.x = x;
                this.player.y = y;
            }
        }

        private void receiveZombieSimulation(ByteBufferReader bb) {
            boolean hasNeighborPlayer = bb.getBoolean();
            this.player.simulator.receivePacket(bb);
        }

        private void sendChecksum() {
            if (!luaChecksum.isEmpty()) {
                FakeClientManager.trace(this.player.movement.id, String.format("Player %3d sendChecksum", this.player.onlineId));
                ByteBufferWriter b = this.network.startPacket();
                this.doPacket(PacketTypes.PacketType.Checksum.getId(), b);
                b.putShort(1);
                b.putUTF(luaChecksum);
                b.putUTF(scriptChecksum);
                b.putUTF(animChecksum);
                this.network.endPacketImmediate(this.connectionGuid);
            }
        }

        public void sendCommand(String command) {
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.ReceiveCommand.getId(), b);
            b.putUTF(command);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void sendHitZombie(FakeClientManager.Zombie zombie, float damage) {
            ByteBufferWriter b = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.PlayerHitZombie.getId(), b);
            boolean isBareHands = this.player.weaponId == -1;
            this.wielder.set(this.player.onlineId, (byte)1, (byte)0);
            this.wielder.setCharacterFlags((short)0);
            this.wielder.setPosition(this.player.x, this.player.y, this.player.z);
            this.wielder.setDirection(this.player.direction.x, this.player.direction.y);
            this.wielder.set((short)0, 1.0F, 1.0F, 1.0F, isBareHands ? AttackType.UPPERCUT : AttackType.MELEE_SWING);
            this.hitInfo.object.set((byte)3, zombie.onlineId);
            this.hitInfo.x = zombie.x;
            this.hitInfo.y = zombie.y;
            this.hitInfo.z = zombie.z;
            this.hitInfo.dot = 1.0F;
            this.hitInfo.distSq = IsoUtils.DistanceToSquared(this.player.x, this.player.y, zombie.x, zombie.y);
            this.hitInfo.chance = 100;
            AttackVars attackVars = this.wielder.getAttackVars();
            attackVars.setBareHeadsWeapon(isBareHands);
            attackVars.targetOnGround.set((byte)0, (short)-1);
            attackVars.useChargeDelta = 1.0F;
            attackVars.recoilDelay = 0;
            attackVars.targetsStanding.clear();
            attackVars.targetsStanding.add(this.hitInfo);
            attackVars.targetsProne.clear();
            this.wielder.getHitList().clear();
            this.wielder.getHitList().add(this.hitInfo);
            this.wielder.write(b);
            this.hitWeapon.setID(isBareHands ? this.player.bareHandsId : this.player.weaponId);
            this.hitWeapon.write(b);
            byte shotId = 0;
            byte tracerCount = 0;
            byte hitCount = 1;
            b.putByte((byte)0);
            b.putByte((byte)0);
            b.putByte((byte)1);
            this.weaponHit.set(damage, 1.0F, 1.0F, this.player.direction.x, this.player.direction.y, false, false, false, false);
            this.weaponHit.write(b);
            Vector2 zombieDirection = zombie.dir.ToVector();
            this.hitTarget.set(zombie.onlineId, (byte)2);
            this.hitTarget.setCharacterFlags((short)(damage >= zombie.health ? 3 : 0));
            this.hitTarget.setPosition(zombie.x, zombie.y, zombie.z);
            this.hitTarget.setDirection(zombieDirection.x, zombieDirection.y);
            this.hitTarget.set((short)0, "", "FRONT");
            this.hitTarget.write(b);
            this.hitFall.set(zombie.x + this.player.direction.x, zombie.y + this.player.direction.y, (byte)zombie.z, zombie.dir.toAngle());
            this.hitFall.write(b);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private void sendWorldSound(int x, int y, int z, int radius, int volume) {
            ByteBufferWriter bb = this.network.startPacket();
            this.doPacket(PacketTypes.PacketType.WorldSoundPacket.getId(), bb);
            bb.putInt(x);
            bb.putInt(y);
            bb.putInt(z);
            bb.putInt(radius);
            bb.putInt(volume);
            boolean stressHumans = true;
            float zombieIgnoreDistance = 0.0F;
            float stressModifier = 1.0F;
            boolean sourceIsZombie = false;
            boolean repeating = false;
            boolean stressAnimals = true;
            boolean stressZombies = true;
            bb.putBoolean(true);
            bb.putFloat(0.0F);
            bb.putFloat(1.0F);
            bb.putBoolean(false);
            bb.putBoolean(false);
            bb.putBoolean(true);
            bb.putBoolean(true);
            bb.putByte((byte)1);
            bb.putShort(this.player.onlineId);
            bb.putByte((byte)0);
            this.network.endPacketImmediate(this.connectionGuid);
        }

        private static enum State {
            CONNECT,
            LOGIN,
            CHECKSUM,
            LOAD_PROFILE,
            CREATE_PLAYER,
            PLAYER_CONNECT,
            RUN,
            WAIT,
            DISCONNECT,
            QUIT;
        }
    }

    private static class HordeCreator {
        private final int radius;
        private final int count;
        private final long interval;
        private final UpdateLimit hordeCreatorLimiter;

        public HordeCreator(int radius, int count, long interval) {
            this.radius = radius;
            this.count = count;
            this.interval = interval;
            this.hordeCreatorLimiter = new UpdateLimit(interval);
        }

        public String getCommand(int x, int y, int z) {
            return String.format(
                "/createhorde2 -x %d -y %d -z %d -count %d -radius %d -crawler false -isFallOnFront false -isFakeDead false -knockedDown false -health 1 -outfit",
                x,
                y,
                z,
                this.count,
                this.radius
            );
        }
    }

    private static class Movement {
        static String version;
        static int defaultRadius = 150;
        static int aimSpeed = 4;
        static int sneakSpeed = 6;
        static int walkSpeed = 7;
        static int sneakRunSpeed = 10;
        static int runSpeed = 13;
        static int sprintSpeed = 19;
        static int pedestrianSpeedMin = 5;
        static int pedestrianSpeedMax = 20;
        static int vehicleSpeedMin = 40;
        static int vehicleSpeedMax = 80;
        static final float zombieLungeDistanceSquared = 100.0F;
        static final float zombieWalkSpeed = 3.0F;
        static final float zombieLungeSpeed = 6.0F;
        final int id;
        final String description;
        final Vector2 spawn;
        FakeClientManager.Movement.Motion motion;
        float speed;
        final FakeClientManager.Movement.Type type;
        final int radius;
        final IsoDirections direction;
        final Vector2 destination;
        final boolean ghost;
        final long connectDelay;
        final long disconnectDelay;
        final long reconnectDelay;
        final long teleportDelay;
        final FakeClientManager.HordeCreator hordeCreator;
        FakeClientManager.SoundMaker soundMaker;
        long timestamp;

        public Movement(
            int id,
            String description,
            int spawnX,
            int spawnY,
            FakeClientManager.Movement.Motion motion,
            int speed,
            FakeClientManager.Movement.Type type,
            int radius,
            int destinationX,
            int destinationY,
            IsoDirections direction,
            boolean ghost,
            long connectDelay,
            long disconnectDelay,
            long reconnectDelay,
            long teleportDelay,
            FakeClientManager.HordeCreator hordeCreator,
            FakeClientManager.SoundMaker soundMaker
        ) {
            this.id = id;
            this.description = description;
            this.spawn = new Vector2(spawnX, spawnY);
            this.motion = motion;
            this.speed = speed;
            this.type = type;
            this.radius = radius;
            this.direction = direction;
            this.destination = new Vector2(destinationX, destinationY);
            this.ghost = ghost;
            this.connectDelay = connectDelay;
            this.disconnectDelay = disconnectDelay;
            this.reconnectDelay = reconnectDelay;
            this.teleportDelay = teleportDelay;
            this.hordeCreator = hordeCreator;
            this.soundMaker = soundMaker;
        }

        public void connect(int playerId) {
            long currentTime = System.currentTimeMillis();
            if (this.disconnectDelay != 0L) {
                FakeClientManager.info(
                    this.id,
                    String.format(
                        "Player %3d connect in %.3fs, disconnect in %.3fs",
                        playerId,
                        (float)(currentTime - this.timestamp) / 1000.0F,
                        (float)this.disconnectDelay / 1000.0F
                    )
                );
            } else {
                FakeClientManager.info(this.id, String.format("Player %3d connect in %.3fs", playerId, (float)(currentTime - this.timestamp) / 1000.0F));
            }

            this.timestamp = currentTime;
        }

        public void disconnect(int playerId) {
            long currentTime = System.currentTimeMillis();
            if (this.reconnectDelay != 0L) {
                FakeClientManager.info(
                    this.id,
                    String.format(
                        "Player %3d disconnect in %.3fs, reconnect in %.3fs",
                        playerId,
                        (float)(currentTime - this.timestamp) / 1000.0F,
                        (float)this.reconnectDelay / 1000.0F
                    )
                );
            } else {
                FakeClientManager.info(this.id, String.format("Player %3d disconnect in %.3fs", playerId, (float)(currentTime - this.timestamp) / 1000.0F));
            }

            this.timestamp = currentTime;
        }

        public void moveTo(float x, float y) {
            this.destination.set(this.destination.x + x - this.spawn.x, this.destination.y + y - this.spawn.y);
            this.spawn.set(x, y);
        }

        public boolean doTeleport() {
            return this.teleportDelay != 0L;
        }

        public boolean doDisconnect() {
            return this.disconnectDelay != 0L;
        }

        public boolean checkDisconnect() {
            return System.currentTimeMillis() - this.timestamp > this.disconnectDelay;
        }

        public boolean doReconnect() {
            return this.reconnectDelay != 0L;
        }

        public boolean checkReconnect() {
            return System.currentTimeMillis() - this.timestamp > this.reconnectDelay;
        }

        private static enum Motion {
            Aim,
            Sneak,
            Walk,
            SneakRun,
            Run,
            Sprint,
            Pedestrian,
            Vehicle;
        }

        private static enum Type {
            Stay,
            Line,
            Circle,
            AIAttackZombies,
            AIRunAwayFromZombies,
            AIRunToAnotherPlayers,
            AINormal;
        }
    }

    private static class Network {
        private final HashMap<Integer, FakeClientManager.Client> createdClients = new HashMap<>();
        private final HashMap<Long, FakeClientManager.Client> connectedClients = new HashMap<>();
        private final ByteBufferReader rb = new ByteBufferReader(ByteBuffer.allocate(1000000));
        private final ByteBufferWriter wb = new ByteBufferWriter(ByteBuffer.allocate(1000000));
        private final Lock bufferLock = new ReentrantLock();
        private final RakNetPeerInterface peer;
        private final int started;
        private int connected = -1;
        private static final HashMap<Integer, String> systemPacketTypeNames = new HashMap<>();

        boolean isConnected() {
            return this.connected == 0;
        }

        boolean isStarted() {
            return this.started == 0;
        }

        private Network(int maxConnections, int port) {
            this.peer = new RakNetPeerInterface();
            this.peer.Init(false);
            this.peer.SetMaximumIncomingConnections(0);
            this.peer.SetClientPort(port);
            this.peer.SetOccasionalPing(true);
            this.started = this.peer.Startup(maxConnections);
            if (this.started == 0) {
                Thread thread = new Thread(ThreadGroups.Network, this::receiveThread, "PeerInterfaceReceive");
                thread.setDaemon(true);
                thread.start();
                FakeClientManager.log(-1, "Network start ok");
            } else {
                FakeClientManager.error(-1, String.format("Network start failed: %d", this.started));
            }
        }

        private void connect(int clientID, String address) {
            this.connected = this.peer.Connect(address, FakeClientManager.serverPort, PZcrypt.hash("", true), false);
            if (this.connected == 0) {
                FakeClientManager.log(clientID, String.format("Client connected to %s:%d", address, FakeClientManager.serverPort));
            } else {
                FakeClientManager.error(clientID, String.format("Client connection to %s:%d failed: %d", address, FakeClientManager.serverPort, this.connected));
            }
        }

        private void disconnect(long connectedGUID, int clientID, String address) {
            if (connectedGUID != 0L) {
                this.peer.disconnect(connectedGUID, "");
                this.connected = -1;
            }

            if (this.connected == -1) {
                FakeClientManager.log(clientID, String.format("Client disconnected from %s:%d", address, FakeClientManager.serverPort));
            } else {
                FakeClientManager.log(
                    clientID, String.format("Client disconnection from %s:%d failed: %d", address, FakeClientManager.serverPort, connectedGUID)
                );
            }
        }

        private ByteBufferWriter startPacket() {
            this.bufferLock.lock();
            this.wb.clear();
            return this.wb;
        }

        private void sendPacket(int priority, int reliability, long connectionGUID) {
            try {
                this.wb.flip();
                this.peer.Send(this.wb.bb, priority, reliability, (byte)0, connectionGUID, false);
            } finally {
                this.bufferLock.unlock();
            }
        }

        private void endPacket(long connectionGUID) {
            this.sendPacket(1, 3, connectionGUID);
        }

        private void endPacketImmediate(long connectionGUID) {
            this.sendPacket(0, 3, connectionGUID);
        }

        private void endPacketSuperHighUnreliable(long connectionGUID) {
            this.sendPacket(0, 1, connectionGUID);
        }

        private void receiveThread() {
            while (true) {
                if (this.peer.Receive(this.rb.bb)) {
                    try {
                        this.decode(this.rb);
                    } catch (Exception var5) {
                        FakeClientManager.error(-1, String.format("Packet decoding failed: %s", var5));
                    } finally {
                        this.rb.clear();
                    }
                } else {
                    FakeClientManager.sleep(1L);
                }
            }
        }

        private static void logUserPacket(int id, short type) {
            PacketTypes.PacketType packetType = PacketTypes.packetTypes.get(type);
            String packet = packetType == null ? "unknown user packet" : packetType.name();
            FakeClientManager.trace(id, String.format("## %s (%d)", packet, type));
        }

        private static void logSystemPacket(int id, int type) {
            String packet = systemPacketTypeNames.getOrDefault(type, "unknown system packet");
            FakeClientManager.trace(id, String.format("## %s (%d)", packet, type));
        }

        private void decode(ByteBufferReader buf) {
            int type = buf.getByte() & 255;
            int connectionIndex = -1;
            logSystemPacket(connectionIndex, type);
            switch (type) {
                case 0:
                case 1:
                case 20:
                case 25:
                case 31:
                case 33:
                default:
                    break;
                case 16: {
                    connectionIndex = buf.getByte() & 255;
                    long connectionGUID = this.peer.getGuidOfPacket();
                    FakeClientManager.Client clientx = this.createdClients.get(connectionIndex);
                    if (clientx != null) {
                        clientx.connectionGuid = connectionGUID;
                        this.connectedClients.put(connectionGUID, clientx);
                        VoiceManager.instance.VoiceConnectReq(connectionGUID);
                        clientx.changeState(FakeClientManager.Client.State.LOGIN);
                    }

                    FakeClientManager.log(-1, String.format("Connected clients: %d (connection index %d)", this.connectedClients.size(), connectionIndex));
                    break;
                }
                case 17:
                case 18:
                case 23:
                case 24:
                case 32:
                    FakeClientManager.error(-1, "Connection failed: " + type);
                    break;
                case 19:
                    connectionIndex = buf.getByte() & 255;
                case 44:
                case 45: {
                    long connectionGUID = this.peer.getGuidOfPacket();
                    break;
                }
                case 21: {
                    connectionIndex = buf.getByte() & 255;
                    long connectionGUID = this.peer.getGuidOfPacket();
                    FakeClientManager.Client clientx = this.connectedClients.get(connectionGUID);
                    if (clientx != null) {
                        this.connectedClients.remove(connectionGUID);
                        clientx.changeState(FakeClientManager.Client.State.DISCONNECT);
                    }

                    FakeClientManager.log(-1, String.format("Connected clients: %d (connection index %d)", this.connectedClients.size(), connectionIndex));
                    break;
                }
                case 22:
                    connectionIndex = buf.getByte() & 255;
                    FakeClientManager.Client clientx = this.createdClients.get(connectionIndex);
                    if (clientx != null) {
                        clientx.changeState(FakeClientManager.Client.State.DISCONNECT);
                    }
                    break;
                case 134: {
                    int userPacketId = buf.getShort();
                    long connectionGUID = this.peer.getGuidOfPacket();
                    FakeClientManager.Client client = this.connectedClients.get(connectionGUID);
                    if (client != null) {
                        client.receive((short)userPacketId, buf);
                    }
                }
            }
        }

        static {
            systemPacketTypeNames.put(22, "connection lost");
            systemPacketTypeNames.put(21, "disconnected");
            systemPacketTypeNames.put(23, "connection banned");
            systemPacketTypeNames.put(17, "connection failed");
            systemPacketTypeNames.put(20, "no free connections");
            systemPacketTypeNames.put(16, "connection accepted");
            systemPacketTypeNames.put(18, "already connected");
            systemPacketTypeNames.put(44, "voice request");
            systemPacketTypeNames.put(45, "voice reply");
            systemPacketTypeNames.put(25, "wrong protocol version");
            systemPacketTypeNames.put(0, "connected ping");
            systemPacketTypeNames.put(1, "unconnected ping");
            systemPacketTypeNames.put(33, "new remote connection");
            systemPacketTypeNames.put(31, "remote disconnection");
            systemPacketTypeNames.put(32, "remote connection lost");
            systemPacketTypeNames.put(24, "invalid password");
            systemPacketTypeNames.put(19, "new connection");
            systemPacketTypeNames.put(134, "user packet");
        }
    }

    private static class Player {
        private static final int cellSize = 50;
        private static final int spawnMinX = 3550;
        private static final int spawnMaxX = 14450;
        private static final int spawnMinY = 5050;
        private static final int spawnMaxY = 12950;
        private static final String SURNAME = "Fake";
        private static final String FEMALE_TORSO = "Kate";
        private static final String MALE_TORSO = "Male";
        private static int fps = 60;
        private static int predictInterval = 1000;
        private static float damage = 1.0F;
        private static boolean isVOIPEnabled;
        private final UpdateLimit updateLimiter;
        private final UpdateLimit predictLimiter;
        private final UpdateLimit timeSyncLimiter;
        private final FakeClientManager.Client client;
        private final FakeClientManager.Movement movement;
        private final String username;
        private final boolean isFemale;
        private final Color tagColor;
        private final Color speakColor;
        private UpdateLimit teleportLimiter;
        private short onlineId;
        private float x;
        private float y;
        private final float z;
        private final Vector2 direction;
        private float angle;
        private final FakeClientManager.ZombieSimulator simulator;
        private final FakeClientManager.PlayerManager playerManager;
        private final FakeClientManager.ChunkMap chunkMap;
        private final SurvivorDesc survivorDesc;
        private int weaponId = -1;
        private int bareHandsId = -1;
        static float distance;
        private int lastPlayerForHello = -1;

        private Player(FakeClientManager.Movement movement, FakeClientManager.Network network, int connectionIndex, int port) {
            this.username = FakeClientManager.getUsername(movement.id);
            this.tagColor = Colors.SkyBlue;
            this.speakColor = Colors.GetRandomColor();
            this.isFemale = Rand.NextBool(2);
            this.onlineId = -1;
            this.movement = movement;
            this.z = 0.0F;
            this.angle = 0.0F;
            this.x = movement.spawn.x;
            this.y = movement.spawn.y;
            this.direction = movement.direction.ToVector();
            this.simulator = new FakeClientManager.ZombieSimulator(this);
            this.playerManager = new FakeClientManager.PlayerManager(this);
            this.chunkMap = new FakeClientManager.ChunkMap(this);
            CharacterGender gender = this.isFemale ? CharacterGender.FEMALE : CharacterGender.MALE;
            this.survivorDesc = new SurvivorDesc(true);
            this.survivorDesc.setForename(this.username);
            this.survivorDesc.setSurname("Fake");
            this.survivorDesc.setTorso(this.isFemale ? "Kate" : "Male");
            this.survivorDesc.setCharacterGender(gender);
            this.survivorDesc.setVoicePrefix("Voice" + gender.getGenderStr());
            HumanVisual humanVisual = this.survivorDesc.getHumanVisual();
            humanVisual.setSkinColor(null);
            humanVisual.setBodyHairIndex(0);
            humanVisual.setSkinTextureIndex(0);
            humanVisual.zombieRotStage = 0;
            this.client = new FakeClientManager.Client(this, network, connectionIndex, port);
            network.createdClients.put(connectionIndex, this.client);
            this.updateLimiter = new UpdateLimit(1000 / fps);
            this.predictLimiter = new UpdateLimit((long)(predictInterval * 0.6F));
            this.timeSyncLimiter = new UpdateLimit(10000L);
        }

        private float getDistance(float speed) {
            return this.getSpeed(speed) / fps;
        }

        private float getSpeed(float speed) {
            return speed / 3.6F;
        }

        private float getSpeed() {
            return this.getSpeed(this.movement.speed);
        }

        private boolean isMoving() {
            return this.movement.speed > 0.0F && FakeClientManager.Movement.Type.Stay != this.movement.type;
        }

        private void teleportMovement() {
            float nx = this.movement.destination.x;
            float ny = this.movement.destination.y;
            FakeClientManager.info(
                this.movement.id,
                String.format(
                    "Player %3d teleport (%9.3f,%9.3f) => (%9.3f,%9.3f) / %9.3f, next in %.3fs",
                    this.onlineId,
                    this.x,
                    this.y,
                    nx,
                    ny,
                    Math.sqrt(Math.pow(nx - this.x, 2.0) + Math.pow(ny - this.y, 2.0)),
                    (float)this.movement.teleportDelay / 1000.0F
                )
            );
            this.x = nx;
            this.y = ny;
            this.angle = 0.0F;
            this.teleportLimiter.Reset(this.movement.teleportDelay);
        }

        private void lineMovement() {
            distance = this.getDistance(this.movement.speed);
            this.direction.set(this.movement.destination.x - this.x, this.movement.destination.y - this.y);
            this.direction.normalize();
            float nx = this.x + distance * this.direction.x;
            float ny = this.y + distance * this.direction.y;
            if (this.x < this.movement.destination.x && nx > this.movement.destination.x
                || this.x > this.movement.destination.x && nx < this.movement.destination.x
                || this.y < this.movement.destination.y && ny > this.movement.destination.y
                || this.y > this.movement.destination.y && ny < this.movement.destination.y) {
                nx = this.movement.destination.x;
                ny = this.movement.destination.y;
            }

            this.x = nx;
            this.y = ny;
        }

        private void circleMovement() {
            this.angle = (this.angle + (float)(2.0 * Math.asin(this.getDistance(this.movement.speed) / 2.0F / this.movement.radius))) % 360.0F;
            float nx = this.movement.spawn.x + (float)(this.movement.radius * Math.sin(this.angle));
            float ny = this.movement.spawn.y + (float)(this.movement.radius * Math.cos(this.angle));
            this.x = nx;
            this.y = ny;
        }

        private FakeClientManager.Zombie getNearestZombie() {
            FakeClientManager.Zombie targetZombie = null;
            float distanceSquared = Float.POSITIVE_INFINITY;

            for (FakeClientManager.Zombie z : this.simulator.zombies.values()) {
                float ds = IsoUtils.DistanceToSquared(this.x, this.y, z.x, z.y);
                if (ds < distanceSquared) {
                    targetZombie = z;
                    distanceSquared = ds;
                }
            }

            return targetZombie;
        }

        private FakeClientManager.Zombie getNearestZombie(FakeClientManager.PlayerManager.RemotePlayer targetPlayer) {
            FakeClientManager.Zombie targetZombie = null;
            float distanceSquared = Float.POSITIVE_INFINITY;

            for (FakeClientManager.Zombie z : this.simulator.zombies.values()) {
                float ds = IsoUtils.DistanceToSquared(targetPlayer.x, targetPlayer.y, z.x, z.y);
                if (ds < distanceSquared) {
                    targetZombie = z;
                    distanceSquared = ds;
                }
            }

            return targetZombie;
        }

        private FakeClientManager.PlayerManager.RemotePlayer getNearestPlayer() {
            FakeClientManager.PlayerManager.RemotePlayer targetPlayer = null;
            float distanceSquared = Float.POSITIVE_INFINITY;
            synchronized (this.playerManager.players) {
                for (FakeClientManager.PlayerManager.RemotePlayer p : this.playerManager.players.values()) {
                    float ds = IsoUtils.DistanceToSquared(this.x, this.y, p.x, p.y);
                    if (ds < distanceSquared) {
                        targetPlayer = p;
                        distanceSquared = ds;
                    }
                }

                return targetPlayer;
            }
        }

        private void aiAttackZombiesMovement() {
            FakeClientManager.Zombie targetZombie = this.getNearestZombie();
            float distance = this.getDistance(this.movement.speed);
            if (targetZombie != null) {
                this.direction.set(targetZombie.x - this.x, targetZombie.y - this.y);
                this.direction.normalize();
            }

            float nx = this.x + distance * this.direction.x;
            float ny = this.y + distance * this.direction.y;
            this.x = nx;
            this.y = ny;
        }

        private void aiRunAwayFromZombiesMovement() {
            FakeClientManager.Zombie targetZombie = this.getNearestZombie();
            float distance = this.getDistance(this.movement.speed);
            if (targetZombie != null) {
                this.direction.set(this.x - targetZombie.x, this.y - targetZombie.y);
                this.direction.normalize();
            }

            float nx = this.x + distance * this.direction.x;
            float ny = this.y + distance * this.direction.y;
            this.x = nx;
            this.y = ny;
        }

        private void aiRunToAnotherPlayersMovement() {
            FakeClientManager.PlayerManager.RemotePlayer targetPlayer = this.getNearestPlayer();
            float distance = this.getDistance(this.movement.speed);
            float nx = this.x + distance * this.direction.x;
            float ny = this.y + distance * this.direction.y;
            if (targetPlayer != null) {
                this.direction.set(targetPlayer.x - this.x, targetPlayer.y - this.y);
                float length = this.direction.normalize();
                if (length > 2.0F) {
                    this.x = nx;
                    this.y = ny;
                } else if (this.lastPlayerForHello != targetPlayer.onlineId) {
                    this.lastPlayerForHello = targetPlayer.onlineId;
                }
            }
        }

        private void aiNormalMovement() {
            float distance = this.getDistance(this.movement.speed);
            FakeClientManager.PlayerManager.RemotePlayer targetPlayer = this.getNearestPlayer();
            if (targetPlayer == null) {
                this.aiRunAwayFromZombiesMovement();
            } else {
                float dsp = IsoUtils.DistanceToSquared(this.x, this.y, targetPlayer.x, targetPlayer.y);
                if (dsp > 36.0F) {
                    this.movement.speed = 13.0F;
                    this.movement.motion = FakeClientManager.Movement.Motion.Run;
                } else {
                    this.movement.speed = 4.0F;
                    this.movement.motion = FakeClientManager.Movement.Motion.Walk;
                }

                FakeClientManager.Zombie targetZombie = this.getNearestZombie();
                float dsz = Float.POSITIVE_INFINITY;
                if (targetZombie != null) {
                    dsz = IsoUtils.DistanceToSquared(this.x, this.y, targetZombie.x, targetZombie.y);
                }

                FakeClientManager.Zombie targetZombieForPlayer = this.getNearestZombie(targetPlayer);
                float dsz4player = Float.POSITIVE_INFINITY;
                if (targetZombieForPlayer != null) {
                    dsz4player = IsoUtils.DistanceToSquared(targetPlayer.x, targetPlayer.y, targetZombieForPlayer.x, targetZombieForPlayer.y);
                }

                if (dsz4player < 25.0F) {
                    targetZombie = targetZombieForPlayer;
                    dsz = dsz4player;
                }

                if (dsp > 25.0F || targetZombie == null) {
                    this.direction.set(targetPlayer.x - this.x, targetPlayer.y - this.y);
                    float length = this.direction.normalize();
                    if (length > 4.0F) {
                        float nx = this.x + distance * this.direction.x;
                        float ny = this.y + distance * this.direction.y;
                        this.x = nx;
                        this.y = ny;
                    } else if (this.lastPlayerForHello != targetPlayer.onlineId) {
                        this.lastPlayerForHello = targetPlayer.onlineId;
                    }
                } else if (dsz < 25.0F) {
                    this.direction.set(targetZombie.x - this.x, targetZombie.y - this.y);
                    this.direction.normalize();
                    this.x = this.x + distance * this.direction.x;
                    this.y = this.y + distance * this.direction.y;
                }
            }
        }

        private void hit() {
            FakeClientManager.info(this.movement.id, String.format("Player %3d hit", this.onlineId));
        }

        private void run() {
            this.simulator.update();
            if (this.updateLimiter.Check()) {
                if (isVOIPEnabled) {
                    FMODManager.instance.tick();
                    VoiceManager.instance.update();
                }

                if (this.movement.doTeleport() && this.teleportLimiter.Check()) {
                    this.teleportMovement();
                }

                switch (this.movement.type) {
                    case Line:
                        this.lineMovement();
                        break;
                    case Circle:
                        this.circleMovement();
                        break;
                    case AIAttackZombies:
                        this.aiAttackZombiesMovement();
                        break;
                    case AIRunAwayFromZombies:
                        this.aiRunAwayFromZombiesMovement();
                        break;
                    case AIRunToAnotherPlayers:
                        this.aiRunToAnotherPlayersMovement();
                        break;
                    case AINormal:
                        this.aiNormalMovement();
                }

                this.chunkMap.update();
                if (this.predictLimiter.Check()) {
                    this.client.sendPlayer();
                }

                if (this.timeSyncLimiter.Check()) {
                    this.client.sendTimeSync();
                    this.client.sendSyncRadioData();
                }

                if (this.movement.hordeCreator != null && this.client.canCreateHorde() && this.movement.hordeCreator.hordeCreatorLimiter.Check()) {
                    this.client
                        .sendCommand(this.movement.hordeCreator.getCommand(PZMath.fastfloor(this.x), PZMath.fastfloor(this.y), PZMath.fastfloor(this.z)));
                }

                if (this.movement.soundMaker != null && this.movement.soundMaker.soundMakerLimiter.Check()) {
                    this.client
                        .sendWorldSound(
                            PZMath.fastfloor(this.x),
                            PZMath.fastfloor(this.y),
                            PZMath.fastfloor(this.z),
                            this.movement.soundMaker.radius,
                            this.movement.soundMaker.radius
                        );
                }
            }
        }
    }

    private static class PlayerManager {
        private final FakeClientManager.Player player;
        private final PlayerPacket playerPacket = new PlayerPacket();
        public final HashMap<Integer, FakeClientManager.PlayerManager.RemotePlayer> players = new HashMap<>();

        public PlayerManager(FakeClientManager.Player player) {
            this.player = player;
        }

        private void parsePlayer(ByteBufferReader b) {
            PlayerPacket packet = this.playerPacket;
            packet.parse(b, null);
            short onlineId = packet.id.getID();
            synchronized (this.players) {
                FakeClientManager.PlayerManager.RemotePlayer p = this.players.get(Integer.valueOf(onlineId));
                if (p == null) {
                    p = new FakeClientManager.PlayerManager.RemotePlayer(onlineId);
                    this.players.put(Integer.valueOf(onlineId), p);
                    FakeClientManager.trace(this.player.movement.id, String.format("New player %s", onlineId));
                }

                p.x = packet.prediction.position.x;
                p.y = packet.prediction.position.y;
                p.z = packet.prediction.position.z;
            }
        }

        private void parsePlayerTimeout(ByteBufferReader b) {
            short onlineId = b.getShort();
            synchronized (this.players) {
                this.players.remove(onlineId);
            }

            FakeClientManager.trace(this.player.movement.id, String.format("Remove player %s", onlineId));
        }

        private class RemotePlayer {
            public float x;
            public float y;
            public float z;
            public short onlineId;
            public PlayerPacket playerPacket;

            public RemotePlayer(final short onlineId) {
                Objects.requireNonNull(PlayerManager.this);
                super();
                this.playerPacket = new PlayerPacket();
                this.onlineId = onlineId;
            }
        }
    }

    private static class SoundMaker {
        private final int radius;
        private final int interval;
        private final String message;
        private final UpdateLimit soundMakerLimiter;

        public SoundMaker(int interval, int radius, String message) {
            this.radius = radius;
            this.message = message;
            this.interval = interval;
            this.soundMakerLimiter = new UpdateLimit(interval);
        }
    }

    private static class Zombie {
        public long lastUpdate;
        public float x;
        public float y;
        public float z;
        public short onlineId;
        public boolean localOwnership;
        public ZombiePacket zombiePacket;
        public IsoDirections dir = IsoDirections.N;
        public float health = 1.0F;
        public byte walkType = (byte)Rand.Next(NetworkVariables.WalkType.values().length);
        public float dropPositionX;
        public float dropPositionY;
        public boolean isMoving;

        public Zombie(short onlineId) {
            this.zombiePacket = new ZombiePacket();
            this.zombiePacket.id = onlineId;
            this.onlineId = onlineId;
            this.localOwnership = false;
        }
    }

    private static class ZombieSimulator {
        public static FakeClientManager.ZombieSimulator.Behaviour behaviour = FakeClientManager.ZombieSimulator.Behaviour.Stay;
        public static int deleteZombieDistanceSquared = 10000;
        public static int forgotZombieDistanceSquared = 225;
        public static int canSeeZombieDistanceSquared = 100;
        public static int seeZombieDistanceSquared = 25;
        private static boolean canChangeTarget = true;
        private static final int updatePeriod = 100;
        private static final int attackPeriod = 1000;
        public static int maxZombiesPerUpdate = 300;
        private final ByteBuffer bb = ByteBuffer.allocate(1000000);
        private final UpdateLimit updateLimiter = new UpdateLimit(100L);
        private final UpdateLimit attackLimiter = new UpdateLimit(1000L);
        private final FakeClientManager.Player player;
        private final ZombiePacket zombiePacket = new ZombiePacket();
        private HashSet<Short> authoriseZombiesCurrent = new HashSet<>();
        private HashSet<Short> authoriseZombiesLast = new HashSet<>();
        private final ArrayList<Short> unknownZombies = new ArrayList<>();
        private final HashMap<Integer, FakeClientManager.Zombie> zombies = new HashMap<>();
        private final ArrayDeque<FakeClientManager.Zombie> zombies4Add = new ArrayDeque<>();
        private final ArrayDeque<FakeClientManager.Zombie> zombies4Delete = new ArrayDeque<>();
        private final HashSet<Short> authoriseZombies = new HashSet<>();
        private final ArrayDeque<FakeClientManager.Zombie> sendQueue = new ArrayDeque<>();
        private int ownedZombies;
        private static final Vector2 tmpDir = new Vector2();

        public ZombieSimulator(FakeClientManager.Player player) {
            this.player = player;
        }

        public void becomeLocal(FakeClientManager.Zombie z) {
            z.localOwnership = true;
        }

        public void becomeRemote(FakeClientManager.Zombie z) {
            z.localOwnership = false;
        }

        public void clear() {
            HashSet<Short> temp = this.authoriseZombiesCurrent;
            this.authoriseZombiesCurrent = this.authoriseZombiesLast;
            this.authoriseZombiesLast = temp;
            this.authoriseZombiesLast.removeIf(zombieId -> this.zombies.get(Integer.valueOf(zombieId)) == null);
            this.authoriseZombiesCurrent.clear();
        }

        public void add(short onlineId) {
            this.authoriseZombiesCurrent.add(onlineId);
        }

        public void receivePacket(ByteBufferReader b) {
            short num = b.getShort();

            for (short n = 0; n < num; n++) {
                this.parseZombie(b);
            }
        }

        public void receiveZombieList(ByteBufferReader b) {
            this.clear();
            short count = b.getShort();

            for (short n = 0; n < count; n++) {
                this.add(b.getShort());
            }

            this.process();
        }

        public void receiveDeletedZombies(ByteBufferReader b) {
            short count = b.getShort();

            for (short n = 0; n < count; n++) {
                FakeClientManager.Zombie zombie = this.zombies.get(Integer.valueOf(b.getShort()));
                if (zombie != null) {
                    this.zombies4Delete.add(zombie);
                }
            }
        }

        private void parseZombie(ByteBufferReader b) {
            ZombiePacket packet = this.zombiePacket;
            packet.parse(b, null);
            FakeClientManager.Zombie z = this.zombies.get(Integer.valueOf(packet.id));
            if (!this.authoriseZombies.contains(packet.id) || z == null) {
                if (z == null) {
                    z = new FakeClientManager.Zombie(packet.id);
                    this.zombies4Add.add(z);
                    FakeClientManager.trace(this.player.movement.id, String.format("New zombie %s", z.onlineId));
                }

                z.lastUpdate = System.currentTimeMillis();
                z.zombiePacket.copy(packet);
                z.x = packet.realX;
                z.y = packet.realY;
                z.z = packet.realZ;
                FakeClientManager.trace(this.player.movement.id, String.format("Zombie %d at (%.2f,%.2f)", z.onlineId, z.x, z.y));
            }
        }

        public void process() {
            for (Short zombieId : Sets.difference(this.authoriseZombiesCurrent, this.authoriseZombiesLast)) {
                FakeClientManager.Zombie z = this.zombies.get(Integer.valueOf(zombieId));
                if (z != null) {
                    this.becomeLocal(z);
                } else if (!this.unknownZombies.contains(zombieId)) {
                    this.unknownZombies.add(zombieId);
                }
            }

            for (Short zombieIdx : Sets.difference(this.authoriseZombiesLast, this.authoriseZombiesCurrent)) {
                FakeClientManager.Zombie z = this.zombies.get(Integer.valueOf(zombieIdx));
                if (z != null) {
                    this.becomeRemote(z);
                }
            }

            synchronized (this.authoriseZombies) {
                this.authoriseZombies.clear();
                this.authoriseZombies.addAll(this.authoriseZombiesCurrent);
            }

            if (this.ownedZombies != this.authoriseZombies.size()) {
                this.ownedZombies = this.authoriseZombies.size();
                FakeClientManager.info(
                    this.player.movement.id,
                    String.format("Player %3d owns %d zombies of %d known", this.player.onlineId, this.ownedZombies, this.zombies.size())
                );
            }
        }

        public void send() {
            if (!this.authoriseZombies.isEmpty() || !this.unknownZombies.isEmpty()) {
                if (!this.unknownZombies.isEmpty()) {
                    this.sendZombieRequest();
                }

                if (this.sendQueue.isEmpty()) {
                    synchronized (this.authoriseZombies) {
                        for (Short zombieId : this.authoriseZombies) {
                            FakeClientManager.Zombie z = this.zombies.get(Integer.valueOf(zombieId));
                            if (z != null && z.onlineId != -1) {
                                this.sendQueue.add(z);
                            }
                        }
                    }
                }

                this.bb.clear();
                int countPosition = this.bb.position();
                this.bb.putShort((short)0);
                short count = 0;

                while (!this.sendQueue.isEmpty() && count < maxZombiesPerUpdate) {
                    FakeClientManager.Zombie z = this.sendQueue.poll();
                    if (z.onlineId != -1) {
                        z.zombiePacket.write(this.bb);
                        count++;
                    }
                }

                if (count != 0) {
                    int endPosition = this.bb.position();
                    this.bb.position(countPosition);
                    this.bb.putShort(count);
                    this.bb.position(endPosition);
                    ByteBufferWriter b = this.player.client.network.startPacket();
                    this.player.client.doPacket(PacketTypes.PacketType.ZombieSimulationUnreliable.getId(), b);
                    b.put(this.bb.array(), 0, this.bb.position());
                    this.player.client.network.endPacketSuperHighUnreliable(this.player.client.connectionGuid);
                }
            }
        }

        private void sendZombieRequest() {
            ByteBufferWriter b = this.player.client.network.startPacket();
            this.player.client.doPacket(PacketTypes.PacketType.ZombieRequest.getId(), b);
            b.putShort(this.unknownZombies.size());

            for (Short zombieId : this.unknownZombies) {
                b.putShort(zombieId);
            }

            this.unknownZombies.clear();
            this.player.client.network.endPacket(this.player.client.connectionGuid);
        }

        private static void updatePacketFlags(FakeClientManager.Zombie zombie) {
            ZombiePacket packet = zombie.zombiePacket;
            packet.update = 0;
            if (!packet.pfb.isCanceled()) {
                packet.update = (byte)(packet.update | 16);
            }

            if (packet.predictionType != 0) {
                packet.update = (byte)(packet.update | 4);
            }

            if (packet.target != -1) {
                packet.update = (byte)(packet.update | 2);
            }

            if (packet.realState != NetworkVariables.ZombieState.Idle) {
                packet.update = (byte)(packet.update | 1);
            }

            if (packet.z != 0) {
                packet.update = (byte)(packet.update | 64);
            }

            if (packet.booleanVariables != 0) {
                packet.update = (byte)(packet.update | 8);
            }
        }

        private void simulate(Integer id, FakeClientManager.Zombie zombie) {
            float distanceSquared = IsoUtils.DistanceToSquared(this.player.x, this.player.y, zombie.x, zombie.y);
            if (!(distanceSquared > deleteZombieDistanceSquared) && (zombie.localOwnership || zombie.lastUpdate + 5000L >= System.currentTimeMillis())) {
                tmpDir.set(-zombie.x + this.player.x, -zombie.y + this.player.y);
                if (zombie.isMoving) {
                    float a = 0.2F;
                    zombie.x = PZMath.lerp(zombie.x, zombie.zombiePacket.x, 0.2F);
                    zombie.y = PZMath.lerp(zombie.y, zombie.zombiePacket.y, 0.2F);
                    zombie.z = 0.0F;
                    zombie.dir = IsoDirections.fromAngle(tmpDir);
                }

                if (canChangeTarget) {
                    synchronized (this.player.playerManager.players) {
                        for (FakeClientManager.PlayerManager.RemotePlayer remotePlayer : this.player.playerManager.players.values()) {
                            float remotePlayerDistanceSquared = IsoUtils.DistanceToSquared(remotePlayer.x, remotePlayer.y, zombie.x, zombie.y);
                            if (remotePlayerDistanceSquared < seeZombieDistanceSquared) {
                                zombie.zombiePacket.target = remotePlayer.onlineId;
                                break;
                            }
                        }
                    }
                } else {
                    zombie.zombiePacket.target = this.player.onlineId;
                }

                if (behaviour == FakeClientManager.ZombieSimulator.Behaviour.Stay) {
                    zombie.isMoving = false;
                } else if (behaviour == FakeClientManager.ZombieSimulator.Behaviour.Normal) {
                    if (distanceSquared > forgotZombieDistanceSquared) {
                        zombie.isMoving = false;
                    }

                    if (distanceSquared < canSeeZombieDistanceSquared && (Rand.Next(100) < 1 || zombie.dir == IsoDirections.fromAngle(tmpDir))) {
                        zombie.isMoving = true;
                    }

                    if (distanceSquared < seeZombieDistanceSquared) {
                        zombie.isMoving = true;
                    }
                } else {
                    zombie.isMoving = true;
                }

                if (zombie.isMoving) {
                    Vector2 chrDir = zombie.dir.ToVector();
                    float speed;
                    if (distanceSquared < 100.0F) {
                        speed = 6.0F;
                    } else {
                        speed = 3.0F;
                    }

                    long dt = System.currentTimeMillis() - zombie.lastUpdate;
                    zombie.zombiePacket.x = zombie.x + chrDir.x * (float)dt * 0.001F * speed;
                    zombie.zombiePacket.y = zombie.y + chrDir.y * (float)dt * 0.001F * speed;
                    zombie.zombiePacket.z = (byte)zombie.z;
                    zombie.zombiePacket.predictionType = 1;
                } else {
                    zombie.zombiePacket.x = zombie.x;
                    zombie.zombiePacket.y = zombie.y;
                    zombie.zombiePacket.z = (byte)zombie.z;
                    zombie.zombiePacket.predictionType = 0;
                }

                zombie.zombiePacket.booleanVariables = 0;
                if (distanceSquared < 100.0F) {
                    zombie.zombiePacket.booleanVariables = (short)(zombie.zombiePacket.booleanVariables | 2);
                }

                zombie.zombiePacket.timeSinceSeenFlesh = (short)(zombie.isMoving ? 0 : 32767);
                zombie.zombiePacket.smParamTargetAngle = 0;
                zombie.zombiePacket.speedMod = 1000;
                zombie.zombiePacket.walkType = NetworkVariables.WalkType.values()[zombie.walkType];
                zombie.zombiePacket.realX = zombie.x;
                zombie.zombiePacket.realY = zombie.y;
                zombie.zombiePacket.realZ = (byte)PZMath.fastfloor(zombie.z);
                zombie.zombiePacket.health = (byte)(zombie.health * 100.0F);
                zombie.zombiePacket.realState = zombie.isMoving ? NetworkVariables.ZombieState.WalkToward : NetworkVariables.ZombieState.Idle;
                if (zombie.isMoving) {
                    zombie.zombiePacket.pfb.goal = PathFindBehavior2.Goal.Character;
                    zombie.zombiePacket.pfb.target.setID(this.player.onlineId);
                } else {
                    zombie.zombiePacket.pfb.goal = PathFindBehavior2.Goal.None;
                }

                updatePacketFlags(zombie);
                if (distanceSquared < 2.0F && this.attackLimiter.Check()) {
                    zombie.health = zombie.health - FakeClientManager.Player.damage;
                    this.player.client.sendHitZombie(zombie, FakeClientManager.Player.damage);
                    if (zombie.health <= 0.0F) {
                        this.zombies4Delete.add(zombie);
                    }
                }

                zombie.lastUpdate = System.currentTimeMillis();
            } else {
                this.zombies4Delete.add(zombie);
            }
        }

        private void sendSendDeadZombie(FakeClientManager.Zombie zombie) {
            ByteBufferWriter bb = this.player.client.network.startPacket();
            this.player.client.doPacket(PacketTypes.PacketType.ZombieDeath.getId(), bb);
            bb.putShort(zombie.onlineId);
            bb.putFloat(zombie.x);
            bb.putFloat(zombie.y);
            bb.putFloat(zombie.z);
            bb.putFloat(zombie.dir.toAngle());
            bb.putByte(zombie.dir.ordinal());
            bb.putByte(0);
            bb.putByte(0);
            bb.putByte(0);
            this.player.client.network.endPacketImmediate(this.player.client.connectionGuid);
        }

        public void simulateAll() {
            while (!this.zombies4Add.isEmpty()) {
                FakeClientManager.Zombie z = this.zombies4Add.poll();
                this.zombies.put(Integer.valueOf(z.onlineId), z);
            }

            this.zombies.forEach(this::simulate);

            while (!this.zombies4Delete.isEmpty()) {
                FakeClientManager.Zombie z = this.zombies4Delete.poll();
                this.zombies.remove(Integer.valueOf(z.onlineId));
            }
        }

        public void update() {
            if (this.updateLimiter.Check()) {
                this.simulateAll();
                this.send();
            }
        }

        private static enum Behaviour {
            Stay,
            Normal,
            Attack;
        }
    }
}
