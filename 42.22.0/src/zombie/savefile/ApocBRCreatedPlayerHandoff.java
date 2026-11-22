package zombie.savefile;

import java.util.*;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/** Simulation-thread state machine; database I/O remains on the existing journal workers. */
public final class ApocBRCreatedPlayerHandoff {
    private static final int LIMIT=128;
    private static final Map<UdpConnection,Entry[]> waiting=new IdentityHashMap<>();
    private static int count;
    private static long nextPoll;
    private enum Phase { OLD_SAVE, NEW_SAVE, RESPONSE_SENT }
    private static final class Entry {
        final UdpConnection connection;
        final int slot;
        final long guid,expires;
        final String world,identity;
        final boolean coop;
        final UUID request=UUID.randomUUID();
        Runnable create,respond;
        IsoPlayer player;
        UUID creation;
        Phase phase=Phase.OLD_SAVE;
        Entry(UdpConnection connection,int slot,Runnable create,long now) {
            this.connection=connection;this.slot=slot;this.create=create;
            guid=connection.getConnectedGUID();world=Core.gameSaveWorld;
            coop=GameServer.coop&&SteamUtils.isSteamModeEnabled();identity=identity(connection,coop);
            expires=now+Math.max(1000,Math.min(120_000,Integer.getInteger("apocbr.postgres.createdPlayerTimeoutMillis",30_000)))*1_000_000L;
        }
    }
    /** Returns true when this packet is handled asynchronously (duplicates are coalesced). */
    public static boolean defer(UdpConnection connection,int slot,Runnable create) {
        if(!ApocBRPlayerDatabase.enabled()||!ApocBRPlayerRelationalSync.enabled()||!ApocBRPersistence.journaling())return false;
        if(slot<0||slot>=4){ApocBRLoginGuard.deny(connection);return true;}
        Entry[] slots=waiting.get(connection);
        if(slots!=null&&slots[slot]!=null)return true;
        if(count>=LIMIT){ApocBRLoginGuard.deny(connection);return true;}
        Entry entry=new Entry(connection,slot,create,System.nanoTime());
        if(entry.identity==null){ApocBRLoginGuard.deny(connection);return true;}
        if(slots==null){slots=new Entry[4];waiting.put(connection,slots);}
        slots[slot]=entry;count++;log(entry,"WAIT_OLD_SAVE");return true;
    }
    /** Called after vanilla creation/native-save enqueue, before the CreatePlayer response. */
    public static boolean created(UdpConnection connection,int slot,IsoPlayer player,Runnable respond) {
        Entry entry=find(connection,slot);
        if(entry==null)return false;
        if(entry.phase!=Phase.OLD_SAVE||entry.player!=null)throw new IllegalStateException("Duplicate created player handoff");
        entry.creation=ApocBRPlayerRelationalSync.offerCreatedPlayerForHandoff(player,entry.world,entry.identity,slot,IsoWorld.getWorldVersion());
        if(entry.creation==null) {
            fail(entry,"CAPTURE_REJECTED");return true;
        }
        // Keep the character outside simulation until its owner finishes connecting.
        player.remote=true;
        player.getCell().getAddList().remove(player);
        player.getCell().getObjectList().remove(player);
        player.removeFromSquare();
        entry.player=player;entry.respond=respond;entry.phase=Phase.NEW_SAVE;log(entry,"WAIT_NEW_SAVE");return true;
    }
    /** Called once per server tick. No sleeps, JDBC calls, or engine work on I/O threads. */
    public static void update() {update(System.nanoTime());}
    static void update(long now) {
        if(count==0||now<nextPoll)return;
        nextPoll=now+50_000_000L;
        List<Entry> entries=new ArrayList<>(count);
        for(Entry[] slots:waiting.values())for(Entry entry:slots)if(entry!=null)entries.add(entry);
        for(Entry entry:entries) {
            if(!current(entry)){remove(entry);log(entry,"CANCELLED");continue;}
            if(now>=entry.expires){fail(entry,"TIMEOUT");continue;}
            try {
                if(entry.phase==Phase.RESPONSE_SENT)continue;
                if(!ApocBRPostgresConnector.playerReadable(entry.world,entry.identity,entry.slot,entry.coop))continue;
                if(entry.phase==Phase.OLD_SAVE) {
                    Runnable create=entry.create;entry.create=null;create.run();
                    if(find(entry.connection,entry.slot)==entry&&entry.phase==Phase.OLD_SAVE)fail(entry,"CREATE_FAILED");
                } else {
                    if(!ApocBRPlayerRelationalSync.creationIsCommitted(entry.creation))continue;
                    // The accepted complete capture has left the per-slot pending guard.
                    // A quarantined/failed operation keeps that guard closed.
                    entry.phase=Phase.RESPONSE_SENT;
                    Runnable respond=entry.respond;entry.respond=null;respond.run();log(entry,"RESPONSE_SENT");
                }
            }catch(RuntimeException|LinkageError failure){fail(entry,"FAILED");}
        }
    }
    /** Consume only the committed server-created instance for this connection and slot. */
    public static IsoPlayer take(UdpConnection connection,int slot) {
        Entry entry=find(connection,slot);
        if(entry==null||entry.phase!=Phase.RESPONSE_SENT)return null;
        if(!current(entry)||System.nanoTime()>=entry.expires){remove(entry);return null;}
        IsoPlayer player=entry.player;player.serverPlayerIndex=slot;
        player.getCell().addMovingObject(player);
        player.setCurrentSquareFromPosition();
        remove(entry);log(entry,"ATTACHED");return player;
    }
    private static boolean current(Entry entry) {
        return entry.connection.getConnectedGUID()==entry.guid&&!GameServer.isDelayedDisconnect(entry.connection)
            &&GameServer.udpEngine.connections.contains(entry.connection)&&Objects.equals(Core.gameSaveWorld,entry.world)
            &&entry.coop==(GameServer.coop&&SteamUtils.isSteamModeEnabled())
            &&Objects.equals(entry.identity,identity(entry.connection,entry.coop));
    }
    private static String identity(UdpConnection connection,boolean coop){return coop?connection.getIDStr():connection.getUserName();}
    private static Entry find(UdpConnection connection,int slot){Entry[] slots=waiting.get(connection);return slots==null||slot<0||slot>=4?null:slots[slot];}
    private static void remove(Entry entry) {
        Entry[] slots=waiting.get(entry.connection);
        if(slots==null||slots[entry.slot]!=entry)return;
        slots[entry.slot]=null;count--;
        ApocBRPlayerRelationalSync.forgetCreation(entry.creation);
        if(Arrays.stream(slots).allMatch(Objects::isNull))waiting.remove(entry.connection);
    }
    private static void fail(Entry entry,String reason){remove(entry);log(entry,reason);ApocBRLoginGuard.deny(entry.connection);}
    private static void log(Entry entry,String event){DebugLog.log("[ApocBR][CreatedPlayer] "+event+"; request="+entry.request+"; slot="+entry.slot);}
    private ApocBRCreatedPlayerHandoff(){}
}
