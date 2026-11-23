package cloud.apocalipse.pz.agent.features.npc;

import java.lang.reflect.Field;

/** Lazy original public-field adapter; initialization never defines game classes during premain. */
final class NpcWorldAccess {
    private static final class Fields {
        static final Field SERVER = field("zombie.network.GameServer", "server");
        static final Field TRANSPORT = field("zombie.network.GameServer", "udpEngine");
        static final Field WORLD = field("zombie.iso.IsoWorld", "instance");
        static final Field CELL = field("zombie.iso.IsoWorld", "currentCell");
    }

    static Object currentWorld() {
        try {
            if (!Fields.SERVER.getBoolean(null) || Fields.TRANSPORT.get(null) == null) return null;
            Object world = Fields.WORLD.get(null);
            return world == null ? null : Fields.CELL.get(world);
        } catch (IllegalAccessException failure) { throw new IllegalStateException("NPC world readiness unavailable", failure); }
    }

    static void requireMembership(Object cell){
        try{
            if(!(cell.getClass().getMethod("getObjectList").invoke(cell) instanceof NpcMembershipSet))
                throw new IllegalStateException("NPC cell membership hook is not installed");
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Original cell membership boundary unavailable",failure);}
    }

    private static Field field(String owner, String name) {
        try { return Class.forName(owner, false, NpcWorldAccess.class.getClassLoader()).getField(name); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Missing original NPC lifecycle field: " + owner + "." + name, failure); }
    }
}
