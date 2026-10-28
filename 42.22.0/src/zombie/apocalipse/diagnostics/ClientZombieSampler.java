package zombie.apocalipse.diagnostics;

import java.nio.file.Path;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Locale;
import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.debug.DebugLog;
import zombie.network.GameClient;
import zombie.network.packets.character.ZombiePacket;

/** Opt-in multiplayer-client observation only. Hooks never consume root motion or modify packets. */
public final class ClientZombieSampler {
    private static final boolean ENABLED=Boolean.getBoolean("apocbr.zombieSampler.enabled");
    private static final int MAX_BODIES=integer("maxBodies",4,1,16);
    private static final int SECONDS=integer("seconds",120,5,1800);
    private static final int ONLINE_ID=integer("onlineId",-1,-1,32767);
    private static final int RADIUS=integer("radius",20,1,100);
    private static final String ROLE=role();
    private static final IdentityHashMap<IsoZombie,Body> bodies=new IdentityHashMap<>();
    private static ZombieCaptureWriter writer;
    private static long started, sequence, generation;
    private static volatile boolean stopped;
    private static String transport="unknown";
    private static final class Body {
        final short online;final int instance;final long generation;
        long seen, postAt, update;float postX,postY;boolean remote;
        Body(IsoZombie z,long now){online=z.onlineId;instance=z.getID();generation=++ClientZombieSampler.generation;
            seen=now;postX=z.getX();postY=z.getY();remote=z.isRemoteZombie();}
    }
    private ClientZombieSampler(){ }
    public static boolean enabled(){return GameClient.client && ENABLED && !stopped;}
    private static String role(){String value=System.getProperty("apocbr.zombieSampler.role","both").toLowerCase(Locale.ROOT);
        return value.equals("owner") || value.equals("remote")?value:"both";}
    private static int integer(String key,int fallback,int min,int max){return Math.clamp(Integer.getInteger("apocbr.zombieSampler."+key,fallback),min,max);}
    private static Body select(IsoGameCharacter character){
        // No worker, file, native read or entity retention on a server or disabled client.
        if(!GameClient.client || !ENABLED || stopped || !(character instanceof IsoZombie z))return null;
        long now=System.nanoTime();
        if(started!=0 && now-started>SECONDS*1_000_000_000L){stop();return null;}
        if(writer!=null && !writer.accepting()){
            stopped=true;bodies.clear();
            if(writer.failure()!=null)DebugLog.log("[ApocBR][ZombieSampler] Write failed: "+writer.failure());
            return null;
        }
        var player=IsoPlayer.getInstance();
        if(player==null || z.onlineId==-1 || ONLINE_ID!=-1 && z.onlineId!=ONLINE_ID
            || player.getZi()!=z.getZi() || Math.hypot(player.getX()-z.getX(),player.getY()-z.getY())>RADIUS)return null;
        boolean remote=z.isRemoteZombie();
        if(ROLE.equals("owner") && remote || ROLE.equals("remote") && !remote)return null;
        var body=bodies.get(z);
        if(body!=null && (body.online!=z.onlineId || body.instance!=z.getID())){bodies.remove(z);body=null;}
        if(body==null){
            bodies.entrySet().removeIf(e->now-e.getValue().seen>3_000_000_000L);
            if(bodies.size()>=MAX_BODIES)return null;
            body=new Body(z,now);bodies.put(z,body);
        }
        body.seen=now;
        if(writer==null){
            try{
                started=now;
                Path directory=Path.of(System.getProperty("apocbr.zombieSampler.directory",
                    Path.of(ZomboidFileSystem.instance.getCacheDir(),"zombie-captures").toString()));
                Path file=directory.resolve("zombies-"+Instant.now().toString().replace(':','-')+"-"+Long.toUnsignedString(now)+".ndjson");
                writer=new ZombieCaptureWriter(file,4096,integer("maxRows",200000,100,2000000),
                    integer("maxMiB",32,1,256)*1024L*1024L,SECONDS*1_000_000_000L,
                    "{\"event\":\"metadata\",\"schema\":1,\"build\":\"42.21.0\",\"utc\":"+quote(Instant.now().toString())
                    +",\"role\":"+quote(ROLE)+",\"seconds\":"+SECONDS+",\"maxBodies\":"+MAX_BODIES
                    +",\"radius\":"+RADIUS+",\"clock\":\"local_monotonic_ns\"}");
                Runtime.getRuntime().addShutdownHook(new Thread(ClientZombieSampler::shutdown,"ApocBR-ZombieCaptureClose"));
                DebugLog.log("[ApocBR][ZombieSampler] Capturing to "+file.toAbsolutePath());
            }catch(Exception error){stopped=true;DebugLog.log("[ApocBR][ZombieSampler] Capture disabled: "+error);return null;}
        }
        if(body.remote!=remote){body.remote=remote;emit(z,body,"ownership",",\"remote\":"+remote);}
        return body;
    }
    private static void emit(IsoZombie z,Body body,String event,String fields){
        var time=GameTime.getInstance();
        String state=z.getCurrentState()==null?"none":z.getCurrentState().getClass().getSimpleName();
        writer.offer("{\"event\":"+quote(event)+",\"seq\":"+(++sequence)+",\"ns\":"+(System.nanoTime()-started)
            +",\"id\":"+body.online+",\"instance\":"+body.instance+",\"generation\":"+body.generation+",\"sampleUpdate\":"+body.update
            +",\"role\":"+quote(z.isRemoteZombie()?"remote":"owner")+",\"state\":"+quote(state)
            +",\"walkType\":"+quote(z.getWalkType())+",\"speedType\":"+z.getSpeedType()+",\"speedMod\":"+number(z.getSpeedMod())
            +",\"running\":"+z.running+",\"crawling\":"+z.isCrawling()+",\"inactive\":"+z.inactive
            +",\"x\":"+number(z.getX())+",\"y\":"+number(z.getY())+",\"z\":"+number(z.getZ())
            +",\"angle\":"+number(z.getDirectionAngleRadians())+",\"simDt\":"+number(time.getTimeDelta())
            +",\"wallDt\":"+number(time.getRealworldSecondsSinceLastUpdate())+",\"animDt\":"+number(z.getAnimationTimeDelta())
            +",\"multiplier\":"+number(time.getMultiplier())+",\"objectMultiplier\":"+number(time.perObjectMultiplier)
            +fields+"}");
    }
    /** Observe an existing getDeferredMovement call; reset describes the original caller. */
    public static synchronized void deferred(IsoGameCharacter character,float x,float y,boolean reset){
        try{var body=select(character);if(body==null)return;var z=(IsoZombie)character;
            emit(z,body,"root_read",",\"dx\":"+number(x)+",\"dy\":"+number(y)+",\"consumed\":"+reset);
        }catch(Exception error){fail(error);}
    }
    public static synchronized void movement(IsoZombie z,String stage,float x,float y,float scale){
        try{var body=select(z);if(body==null)return;
            emit(z,body,stage,",\"dx\":"+number(x)+",\"dy\":"+number(y)+",\"correctionScale\":"+number(scale)
                +",\"nextX\":"+number(z.getNextX())+",\"nextY\":"+number(z.getNextY()));
        }catch(Exception error){fail(error);}
    }
    public static synchronized void post(IsoZombie z){
        try{var body=select(z);if(body==null)return;long now=System.nanoTime();
            StringBuilder tracks=new StringBuilder("[");
            var animation=z.getAnimationPlayer();
            if(animation!=null){
                var all=animation.getMultiTrack().getTracks();
                for(int i=0;i<Math.min(8,all.size());i++){var track=all.get(i);if(i>0)tracks.append(',');
                    tracks.append("{\"clip\":").append(quote(track.getName())).append(",\"time\":").append(number(track.getCurrentTimeValue()))
                        .append(",\"duration\":").append(number(track.getDuration())).append(",\"speed\":").append(number(track.getSpeedDelta()))
                        .append(",\"weight\":").append(number(track.getBlendWeight())).append('}');
                }
            }
            tracks.append(']');
            var ai=z.getNetworkCharacterAI();
            emit(z,body,"post",",\"elapsedNs\":"+(body.postAt==0?0:now-body.postAt)+",\"actualDx\":"+number(z.getX()-body.postX)
                +",\"actualDy\":"+number(z.getY()-body.postY)+",\"firstPost\":"+(body.postAt==0)
                +",\"collision\":"+z.isCollidedThisFrame()+",\"vehicleCollision\":"+z.isCollidedWithVehicle()
                +",\"turning\":"+z.isTurning()+",\"onFloor\":"+z.isOnFloor()+",\"knockedDown\":"+z.isKnockedDown()
                +",\"realX\":"+number(z.realx)+",\"realY\":"+number(z.realy)
                +",\"networkX\":"+number(ai.targetX)+",\"networkY\":"+number(ai.targetY)
                +",\"pathX\":"+number(z.getPathFindBehavior2().getTargetX())+",\"pathY\":"+number(z.getPathFindBehavior2().getTargetY())
                +",\"predictionType\":"+ai.predictionType+",\"separated\":"+ai.wasSeparated
                +",\"animState\":"+quote(z.getAdvancedAnimator().getCurrentStateName())+",\"tracks\":"+tracks);
            body.postAt=now;body.postX=z.getX();body.postY=z.getY();body.update++;
        }catch(Exception error){fail(error);}
    }
    public static synchronized void animation(IsoGameCharacter character,AnimEvent event){
        if(!GameClient.client || !ENABLED || stopped || event==null)return;
        String name=event.eventName;
        if(name==null || !(name.equalsIgnoreCase("SetAttackOutcome") || name.equalsIgnoreCase("AttackCollisionCheck")
            || name.equalsIgnoreCase("ZombieBiteDone") || name.equalsIgnoreCase("ActiveAnimFinished")
            || name.equalsIgnoreCase("SetOnFloor") || name.equalsIgnoreCase("SetKnockedDown")))return;
        try{var body=select(character);if(body!=null)emit((IsoZombie)character,body,"animation",
            ",\"name\":"+quote(name)+",\"parameter\":"+quote(event.parameterValue)+",\"timePc\":"+number(event.timePc));
        }catch(Exception error){fail(error);}
    }
    public static synchronized void packet(ZombiePacket packet,String stage){
        if(!GameClient.client || !ENABLED || stopped)return;
        try{var z=GameClient.getZombie(packet.id);if(z==null)return;var body=select(z);if(body==null)return;
            emit(z,body,stage,",\"packetX\":"+number(packet.x)+",\"packetY\":"+number(packet.y)+",\"packetZ\":"+packet.z
                +",\"packetRealX\":"+number(packet.realX)+",\"packetRealY\":"+number(packet.realY)+",\"packetRealZ\":"+packet.realZ
                +",\"packetPrediction\":"+packet.predictionType+",\"packetState\":"+quote(String.valueOf(packet.realState))
                +",\"packetWalkType\":"+quote(String.valueOf(packet.walkType))+",\"packetSpeedMod\":"+packet.speedMod
                +",\"transport\":"+quote(stage.equals("packet_out")?transport:"server_sync")
                +",\"packetDirection\":"+number(packet.dirAngleRads)+",\"target\":"+packet.target
                +",\"flags\":"+packet.booleanVariables+",\"update\":"+Byte.toUnsignedInt(packet.update)
                +",\"pathGoal\":"+quote(String.valueOf(packet.pfb.goal))+",\"health\":"+packet.health);
        }catch(Exception error){fail(error);}
    }
    public static synchronized void received(IsoZombie z,ZombiePacket packet,String stage){
        try{var body=select(z);if(body!=null)emit(z,body,stage,",\"packetRealX\":"+number(packet.realX)
            +",\"packetRealY\":"+number(packet.realY)+",\"error\":"+number((float)Math.hypot(z.getX()-packet.realX,z.getY()-packet.realY)));
        }catch(Exception error){fail(error);}
    }
    public static synchronized void transport(String name,int count){
        if(!GameClient.client || !ENABLED || stopped)return;
        transport=name;
        if(writer!=null)writer.offer("{\"event\":\"batch_encode\",\"seq\":"+(++sequence)+",\"ns\":"+(System.nanoTime()-started)
            +",\"transport\":"+quote(name)+",\"zombies\":"+count+"}");
    }
    public static synchronized void cadence(boolean neighbor){
        if(GameClient.client && ENABLED && !stopped && writer!=null)writer.offer("{\"event\":\"upload_cadence\",\"seq\":"+(++sequence)
            +",\"ns\":"+(System.nanoTime()-started)+",\"neighbor\":"+neighbor+",\"periodMs\":"+(neighbor?200:4000)+"}");
    }
    private static void fail(Exception error){
        if(!stopped){stopped=true;if(writer!=null)writer.finish();bodies.clear();DebugLog.log("[ApocBR][ZombieSampler] Stopped: "+error);}
    }
    public static synchronized void stop(){
        if(stopped && writer==null)return;
        stopped=true;bodies.clear();if(writer!=null)writer.finish();
    }
    private static void shutdown(){ZombieCaptureWriter output;synchronized(ClientZombieSampler.class){stop();output=writer;}if(output!=null)output.close();}
    private static String number(float value){return Float.isFinite(value)?Float.toString(value):"null";}
    private static String quote(String value){
        if(value==null)return "null";
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<Math.min(256,value.length());i++){char c=value.charAt(i);
            if(c=='"' || c=='\\')out.append('\\').append(c);
            else if(c<' ')out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
        }
        return out.append('"').toString();
    }
}
