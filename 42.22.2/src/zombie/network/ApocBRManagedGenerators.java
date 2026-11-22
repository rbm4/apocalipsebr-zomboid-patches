package zombie.network;

import java.util.*;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoGenerator;

/** Server-thread owner of registered native generator sources. No custom client class or packet. */
public final class ApocBRManagedGenerators {
    private static final String TAG = "ApocBRManagedGeneratorId";
    private static final Map<String, Source> sources = new LinkedHashMap<>();
    private static final Set<IsoGenerator> loaded = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<IsoGenerator> retiring = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Source> warming = new LinkedHashSet<>();
    private static final Map<IsoGenerator, Source> bindings = new IdentityHashMap<>();
    private static Thread owner;
    private static boolean startupFinished;
    private static int cursor;
    private static final class Source {
        final String id;
        final int x, y, z;
        long deadline;
        boolean materialized, prewarmed, denied;
        String state = "QUEUED_FOR_PREWARM";
        IsoGenerator object;
        long nextDenialLog;
        Source(String id, int x, int y, int z) { this.id=id; this.x=x; this.y=y; this.z=z; }
        boolean matches(IsoGenerator g) {
            IsoGridSquare sq=g.getSquare();
            return sq!=null && sq.getX()==x && sq.getY()==y && sq.getZ()==z;
        }
    }
    private ApocBRManagedGenerators() {}
    public static void initialize() {
        if (!GameServer.server) return;
        owner=Thread.currentThread(); sources.clear(); loaded.clear(); retiring.clear(); bindings.clear(); warming.clear();
        startupFinished=false; cursor=0;
        log("INITIALIZED native_type=IsoGenerator");
    }
    private static void guard() {
        if (!GameServer.server || Thread.currentThread()!=owner) throw new IllegalStateException("Server Lua thread required");
    }
    private static String label(String id) { return id==null?"<null>":id.substring(0,Math.min(128,id.length())).replaceAll("[\\p{Cntrl}]","_"); }
    private static void log(String text) { DebugLog.log("[ApocBRManagedGenerators] "+text); }
    private static String tag(IsoGenerator g) {
        Object value=g.getModData().rawget(TAG);
        return value instanceof String id && !id.isBlank() && id.length()<=128 ? id : null;
    }
    public static boolean isTagged(IsoGenerator g) { return GameServer.server && Thread.currentThread()==owner && (bindings.containsKey(g) || tag(g)!=null); }
    /** Admission only. READY is logged after a real source square has been integrated and initialized. */
    public static boolean registerGenerator(String id, int x, int y, int z) {
        guard();
        if (id==null || id.isBlank() || id.length()>128) return reject(id,x,y,z,"invalid_id");
        if (x<0 || y<0 || x>1000000 || y>1000000 || z< -32 || z>31)
            return reject(id,x,y,z,"coordinate_out_of_range x=0..1000000 y=0..1000000 z=-32..31");
        if (IsoWorld.instance==null || IsoWorld.instance.getMetaGrid()==null)
            return reject(id,x,y,z,"world_metadata_not_ready");
        if (!IsoWorld.instance.isValidSquare(x,y,z)) return reject(id,x,y,z,"outside_world_bounds");
        Source previous=sources.get(id);
        if (previous!=null) {
            boolean same=previous.x==x && previous.y==y && previous.z==z;
            log("REGISTER_"+(same && !previous.denied?"UNCHANGED":"REJECTED")+" id="+label(id)+" tile="+x+","+y+","+z
                +" reason="+(previous.denied?"previous_creation_denied_unregister_before_retry":same?"already_registered":"unregister_before_move")+" status="+previous.state);
            return same && !previous.denied;
        }
        if (sources.size()>=256) return reject(id,x,y,z,"definition_limit_256");
        for(Source other:sources.values()) if(other.x==x && other.y==y && other.z==z)
            return reject(id,x,y,z,"duplicate_source_tile owner="+label(other.id));
        sources.put(id,new Source(id,x,y,z));
        log("REGISTERED id="+label(id)+" tile="+x+","+y+","+z+" active_definitions="+sources.size()+" status=QUEUED_FOR_PREWARM");
        return true;
    }
    private static boolean reject(String id,int x,int y,int z,String reason) {
        log("REGISTRATION_REJECTED id="+label(id)+" tile="+x+","+y+","+z+" reason="+reason);
        return false;
    }
    private static void denyPlacement(Source s,String reason) {
        s.denied=true;
        state(s,"CREATION_DENIED_"+reason);
    }
    public static boolean unregisterGenerator(String id) {
        guard(); Source source=sources.remove(id);
        if(source==null) return false;
        warming.remove(source);
        for (IsoGenerator g:new ArrayList<>(loaded)) if(bindings.get(g)==source || id.equals(tag(g))) retire(g,"unregistered");
        log("UNREGISTERED id="+label(id)); return true;
    }
    /** Called once after startup catalog success or terminal failure; retires saved sources no longer desired. */
    public static void finishStartup() { guard(); startupFinished=true; log("CATALOG_FINISHED definitions="+sources.size()); }
    public static String getStatus(String id) { guard(); Source s=sources.get(id); return s==null?"NOT_REGISTERED":s.state; }
    public static boolean isManagedGenerator(IsoObject object) {
        if (!GameServer.server || Thread.currentThread()!=owner || !(object instanceof IsoGenerator g)) return false;
        Source s=bindings.get(g);
        return s!=null && s.prewarmed && !s.denied && sources.get(s.id)==s && s.matches(g) && g.getObjectIndex()!=-1 && !retiring.contains(g);
    }
    /** Native registration hooks are invoked only during server main-thread integration, not chunk deserialization. */
    public static void onAdded(IsoGenerator g) {
        if(GameServer.server && Thread.currentThread()==owner && isTagged(g)) loaded.add(g);
    }
    public static void onRemoved(IsoGenerator g) {
        if(GameServer.server && Thread.currentThread()==owner) {
            loaded.remove(g); bindings.remove(g);
            for(Source s:sources.values()) if(s.object==g) s.object=null;
        }
    }
    public static boolean denyChange(IsoGenerator g, String operation) {
        if(!isManagedGenerator(g)) return false;
        Source s=bindings.get(g); long now=System.nanoTime();
        if(now>=s.nextDenialLog) {
            s.nextDenialLog=now+2_000_000_000L;
            log("CHANGE_REJECTED id="+label(s.id)+" operation="+operation);
            g.sync(); // Correct the sender too; generic remote-sync forwarding excludes it.
        }
        return true;
    }
    public static boolean denyRemoval(IsoObject object) {
        return object instanceof IsoGenerator g && !retiring.contains(g) && denyChange(g,"remove");
    }
    /** Replaces consumption/wear for tagged native objects; never advances vanilla depletion then repairs it. */
    public static void updateGenerator(IsoGenerator g) {
        guard(); loaded.add(g);
        if(isManagedGenerator(g)) maintain(g);
    }
    private static void maintain(IsoGenerator g) {
        Source source=bindings.get(g);
        boolean changed=g.fuel!=10.0F || g.condition!=100 || !g.connected || source!=null && !source.id.equals(tag(g));
        if(source!=null && !source.id.equals(tag(g))) g.getModData().rawset(TAG,source.id);
        g.fuel=10.0F; g.condition=100; g.connected=true;
        if(!g.isActivated()) { g.setActivated(true); changed=true; }
        if(changed) { g.flagForHotSave(); g.sync(); }
    }
    private static void suspend(IsoGenerator g) {
        if(g.activated) {
            g.setActivated(false); g.flagForHotSave(); g.sync();
            log("SUSPENDED id="+label(tag(g))+" reason=awaiting_catalog_or_no_matching_definition");
        }
    }
    private static void retire(IsoGenerator g, String reason) {
        retiring.add(g);
        try {
            suspend(g);
            if(g.getObjectIndex()!=-1) g.remove();
            log("REMOVED id="+label(tag(g))+" reason="+reason);
        } finally { retiring.remove(g); loaded.remove(g); }
    }
    private static void state(Source s,String next) {
        if(!s.state.equals(next)) { s.state=next; log(next+" id="+label(s.id)+" tile="+s.x+","+s.y+","+s.z); }
    }
    private static void reconcile(Source s) {
        IsoGridSquare square=ServerMap.instance.getGridSquare(s.x,s.y,s.z);
        if(square==null) {
            s.object=null;
            if(!s.materialized) denyPlacement(s,"SQUARE_MISSING_AFTER_PREWARM");
            else state(s,"WAITING_FOR_SQUARE");
            return;
        }
        IsoGenerator found=null;
        for(IsoObject o:new ArrayList<>(square.getObjects())) if(o instanceof IsoGenerator g) {
            if(s.id.equals(tag(g))) {
                if(found!=null) retire(g,"duplicate_saved_source"); else found=g;
            } else { denyPlacement(s,"EXISTING_GENERATOR"); return; }
        }
        // A saved instance still needs a supported floor; its own collision flags are allowed.
        if(!square.TreatAsSolidFloor()) { denyPlacement(s,"NO_SOLID_FLOOR"); return; }
        if(found==null) {
            // Mirror the native floor-placement checks; ignore transient player occupancy.
            if(square.isSolid()) { denyPlacement(s,"SOLID_TILE"); return; }
            if(square.isSolidTrans()) { denyPlacement(s,"SOLID_TRANSPARENT_TILE"); return; }
            InventoryItem item=InventoryItemFactory.CreateItem("Base.Generator");
            if(item==null) { denyPlacement(s,"GENERATOR_ITEM_UNAVAILABLE"); return; }
            item.setCondition(100); item.getModData().rawset("fuel",10.0);
            found=new IsoGenerator(item,IsoWorld.instance.getCell(),square);
            found.getModData().rawset(TAG,s.id); loaded.add(found);
            log("CREATED id="+label(s.id)+" object_index="+found.getObjectIndex()+" native_type=IsoGenerator");
        }
        s.object=found; bindings.put(found,s); s.materialized=true;
        maintain(found);
        if(!s.state.equals("READY")) {
            found.setSurroundingElectricity(); found.sync();
            log("READY id="+label(s.id)+" tile="+s.x+","+s.y+","+s.z+" fuel="+found.fuel+" condition="+found.condition
                +" activated="+found.activated+" connected="+found.connected+" transport=vanilla_generator");
            s.state="READY";
        }
    }
    private static void runPrewarm(Source s) {
        if(System.nanoTime()>=s.deadline) { denyPlacement(s,"PREWARM_TIMEOUT"); return; }
        int status=ApocBRLobbyStreaming.keepCellLoaded(s.x,s.y);
        if(status<0) { denyPlacement(s,"INVALID_MAP_CELL"); return; }
        if(status==0) { state(s,"PREWARM_LOADING"); return; }
        s.prewarmed=true;
        state(s,"PREWARM_READY");
        reconcile(s);
    }
    private static void runSafely(Source s,boolean prewarm) {
        try { if(prewarm) runPrewarm(s); else reconcile(s); }
        catch(RuntimeException failure) {
            denyPlacement(s,"EXCEPTION_"+failure.getClass().getSimpleName());
            log("FAILURE_DETAIL id="+label(s.id)+" tile="+s.x+","+s.y+","+s.z+" detail="+label(failure.getMessage()));
        }
    }
    /** Bounded reconciliation, including paused/empty servers; no permanent chunk loading lease. */
    public static void update() {
        guard();
        for(IsoGenerator g:new ArrayList<>(loaded)) {
            if(g.getObjectIndex()==-1) { loaded.remove(g); bindings.remove(g); continue; }
            if(!isManagedGenerator(g)) {
                Source desired=sources.get(tag(g));
                if(desired!=null && !desired.denied && desired.matches(g)) {
                    if(desired.prewarmed) { bindings.put(g,desired); desired.object=g; }
                    else suspend(g);
                }
                else if(startupFinished) retire(g,"absent_or_moved_definition"); else suspend(g);
            }
        }
        // Renew every active request every server frame, exactly as the cleanup pre-warm does.
        // At most four distinct source jobs own these frame-local loading requests.
        for(Source s:new ArrayList<>(warming)) {
            runSafely(s,true);
            if(s.denied || s.prewarmed) warming.remove(s);
        }
        List<Source> batch=new ArrayList<>(sources.values());
        for(int i=0;i<Math.min(4,batch.size());i++) {
            if(cursor>=batch.size()) cursor=0;
            Source s=batch.get(cursor++);
            if(s.denied) continue;
            if(!s.prewarmed) {
                if(warming.size()<4 && warming.add(s)) {
                    s.deadline=System.nanoTime()+120_000_000_000L;
                    state(s,"PREWARM_BEGIN");
                }
            } else runSafely(s,false);
        }
    }
}
