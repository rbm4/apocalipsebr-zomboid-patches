package cloud.apocalipse.pz.agent.features.npc;

import java.util.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import zombie.network.packets.hit.HitCharacter;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;

/** Executes the transformed original default processing owner with a connection-free packet double. */
public final class NpcOrdinaryHitSmokeMain {
    public static final List<String> CALLS=new ArrayList<>();
    public static class Packet implements HitCharacter {
        public boolean fail;
        public void parse(zombie.core.network.ByteBufferReader input,zombie.network.IConnection connection){}
        public void write(zombie.core.network.ByteBufferWriter output){}
        public zombie.iso.IsoObject getTarget(){return null;}
        public float getX(){return 0;}public float getY(){return 0;}public float getZ(){return 0;}
        public void log(UdpConnection c){CALLS.add("log");}
        public void update(){CALLS.add("update");}
        public void sendToRelativeClients(PacketTypes.PacketType type,UdpConnection c,float x,float y){CALLS.add("relay");}
        public void preProcess(){CALLS.add("pre");}
        public void process(){CALLS.add("process");if(fail)throw new IllegalArgumentException("native processing failure");}
        public void postProcess(){CALLS.add("post");}
        public void attack(){CALLS.add("attack");}
        public void react(){CALLS.add("react");}
    }
    public static void main(String[] args)throws Exception {
        var definitions=new HashMap<String,byte[]>();
        definitions.put(HitCharacter.class.getName(),NpcModule.actorTransform(HitCharacter.class.getName(),new ByteBuddy().redefine(HitCharacter.class)).make().getBytes());
        definitions.put(Packet.class.getName(),new ByteBuddy().redefine(Packet.class).make().getBytes());
        var loader=new ByteArrayClassLoader.ChildFirst(NpcOrdinaryHitSmokeMain.class.getClassLoader(),definitions);
        var type=loader.loadClass(Packet.class.getName());var packet=type.getConstructor().newInstance();
        var process=type.getMethod("processServer",PacketTypes.PacketType.class,UdpConnection.class);
        process.invoke(packet,new Object[]{null,null});
        if(!CALLS.equals(List.of("log","update","relay","pre","process","post","attack","react")))throw new AssertionError("Ordinary native call order changed: "+CALLS);
        CALLS.clear();type.getField("fail").setBoolean(packet,true);
        try{process.invoke(packet,new Object[]{null,null});throw new AssertionError("Native failure swallowed");}
        catch(java.lang.reflect.InvocationTargetException failure){if(!(failure.getCause() instanceof IllegalArgumentException))throw failure;}
        if(!CALLS.equals(List.of("log","update","relay","pre","process")))throw new AssertionError("Native failure flow changed: "+CALLS);
        System.out.println("Original default hit processing preserved ordinary call order and exceptions.");
    }
}
