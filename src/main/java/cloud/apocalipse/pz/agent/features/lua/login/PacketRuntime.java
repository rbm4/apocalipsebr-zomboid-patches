package cloud.apocalipse.pz.agent.features.lua.login;

import cloud.apocalipse.pz.agent.features.clientmenu.SteamLoginPacketPayload;
import java.util.ArrayList;
import zombie.characters.*;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.network.*;
import zombie.network.packets.INetworkPacket;
import zombie.network.anticheats.PacketValidator;

/** Snapshot packet inputs before returning to native packet pools; continuations own all network state. */
public final class PacketRuntime {
    private static final ThreadLocal<ArrayList<Userlog>> USERLOG=new ThreadLocal<>();
    private PacketRuntime() { }
    private static Object field(Object object,String name) {
        try { var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object); }
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Packet input contract changed",failure);}
    }
    private static String text(Object object,String name){return (String)field(object,name);}
    private static String takeSteamToken(Object packet) {
        try {
            var credential = packet.getClass().getDeclaredField("password");
            credential.setAccessible(true);
            String token = (String) credential.get(packet);
            credential.set(packet, null);
            return token;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Login packet credential contract changed", failure);
        }
    }
    public static boolean omitLoginPacketLog(Object kind, Object connection) {
        if (!GameServer.server || kind != PacketTypes.PacketType.Login) return false;
        Object packet = ((IConnection) connection).getPacket(PacketTypes.PacketType.Login);
        return packet != null && (Integer) field(packet, "authType") == SteamLoginPacketPayload.AUTH_TYPE;
    }

    public static boolean process(Object packet,Object kind,Object object) {
        if (!ApocBRAsyncLogin.enabled()) {
            if (!"LoginPacket".equals(packet.getClass().getSimpleName())
                    || (Integer) field(packet, "authType") != SteamLoginPacketPayload.AUTH_TYPE) return false;
            takeSteamToken(packet);
            ApocBRLoginGuard.deny((UdpConnection) object);
            return true;
        }
        UdpConnection connection=(UdpConnection)object;
        try {
            if(!ApocBRLoginGuard.ready(connection)) {
                if ("LoginPacket".equals(packet.getClass().getSimpleName())
                        && (Integer) field(packet, "authType") == SteamLoginPacketPayload.AUTH_TYPE) takeSteamToken(packet);
                return true;
            }
            switch(packet.getClass().getSimpleName()) {
                case "LoginPacket" -> {
                    LoginFlow flow=new LoginFlow();flow.username=text(packet,"username");
                    flow.clientVersion=text(packet,"clientVersion");flow.authType=(Integer)field(packet,"authType");
                    flow.password = flow.authType == SteamLoginPacketPayload.AUTH_TYPE
                        ? takeSteamToken(packet) : text(packet,"password");
                    flow.processServer((PacketTypes.PacketType)kind,connection);
                }
                case "GoogleAuthPacket" -> google(text(packet,"username"),text(packet,"code"),connection);
                case "GoogleAuthKeyPacket" -> googleKey(packet,connection);
                case "RequestUserLogPacket" -> userlogRequest(text(packet,"username"),connection);
                default -> throw new IllegalStateException("Unsupported account packet");
            }
        }catch(RuntimeException|LinkageError failure){ApocBRLoginGuard.deny(connection);}
        return true;
    }
    private static void google(String user,String code,UdpConnection connection) {
        if(!ApocBRAsyncLogin.takeSecondFactor(connection,user)){ApocBRLoginGuard.deny(connection);return;}
        ApocBRAsyncLogin.submit(connection,"google-auth",user,()->ServerWorldDatabase.instance.googleAuthClient(user,code),result->{
            Role current=connection.getRole()==null?null:Roles.getRoleById(connection.getRole().getId());
            if(current==null||!current.hasCapability(Capability.LoginOnServer)||!result.authorized){ApocBRLoginGuard.deny(connection);return;}
            result.role=current;connection.setUserName(user);connection.usernames[0]=user;connection.googleAuth=false;
            GameServer.receiveClientConnect(connection,result);
        });
    }
    private static void googleKey(Object packet,UdpConnection connection) {
        String user=text(packet,"username"),pass=text(packet,"password"),secret=text(packet,"key"),version=text(packet,"clientVersion");
        int type=(Integer)field(packet,"authType");
        if(!Core.getInstance().getVersionNumber().equals(version)) {ApocBRLoginGuard.deny(connection);return;}
        connection.setIP(connection.getInetSocketAddress().getHostString());
        String ip=connection.getIP();long steam=connection.getSteamId(),owner=connection.getOwnerId();
        boolean steamMode=SteamUtils.isSteamModeEnabled(),coop=CoopSlave.instance!=null;
        ApocBRAsyncLogin.submit(connection,"google-key",user,()->{
            var db=ServerWorldDatabase.instance;
            if(steamMode&&!coop&&(steam<=0||!db.authClient(steam).authorized||!db.authOwner(steam,owner).authorized))throw new IllegalStateException("Steam admission denied");
            if(!db.authClient(user,pass,ip,steam,type).authorized)throw new IllegalStateException("Authentication denied");
            if(!db.containsUser(user))db.addUser(user,pass,type);
            String saved=db.getUserGoogleKey(user);
            if(saved!=null&&!saved.isEmpty())return "Registration failed. Google key already exists";
            if(!db.setUserGoogleKey(user,secret))throw new IllegalStateException("Google key persistence failed");
            return "QR key registered successfully";
        },message->{
            INetworkPacket.send(connection,PacketTypes.PacketType.GoogleAuthKey,message);
            connection.forceDisconnect("google-key-saved");
        });
    }
    private static void userlogRequest(String name,UdpConnection connection) {
        if(!connection.getRole().hasCapability(Capability.ReadUserLog))return;
        ApocBRAsyncServerDatabase.submit(connection,()->ServerWorldDatabase.instance.getUserlog(name),rows->{
            if(!connection.getRole().hasCapability(Capability.ReadUserLog))return;
            IsoPlayer player=GameServer.getPlayerByUserName(name);
            UdpConnection target=player==null?null:GameServer.getConnectionFromPlayer(player);
            PacketValidator validator=target==null?null:target.getValidator();
            USERLOG.set(rows);
            try{INetworkPacket.send(connection,PacketTypes.PacketType.RequestUserLog,name,validator);}
            finally{USERLOG.remove();}
        });
    }
    /** Replaces only setData's query call; serialization and suspicious-activity capture remain native. */
    public static ArrayList<Userlog> userlog(Object database,String name) {
        var rows=USERLOG.get();
        return rows!=null?new ArrayList<>(rows):((ServerWorldDatabase)database).getUserlog(name);
    }
}
