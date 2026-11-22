package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.NpcSettings;

/** Public linkage surface used by injected original-game bytecode. */
public final class NpcRuntime {
    private static volatile NpcEngine engine;
    private static boolean actors;
    private NpcRuntime() { }

    public static void initialize(NpcSettings settings) {
        if (engine != null) throw new IllegalStateException("NPC runtime already initialized");
        engine = new NpcEngine(settings);
        actors=AgentConfiguration.npcActorsEnabled();
    }

    public static void update() {
        var current = engine;
        if (current == null || !current.status().enabled() || current.status().stopped()) return;
        try {
            var world=NpcWorldAccess.currentWorld();
            if(actors)NpcActorRuntime.beforeWorldChange(world);
            current.update(world, System.nanoTime());
            if(actors){
                if(current.status().started())NpcActorRuntime.update(world,current.planner());
                else if(current.status().stopped())NpcActorRuntime.shutdown();
            }
            if (current.status().failed()) System.err.println("[ApocBR-Agent][NPC] Detached planning failed; engine stopped until restart.");
        } catch (RuntimeException | LinkageError failure) {
            if(actors)try{NpcActorRuntime.shutdown();}catch(RuntimeException|LinkageError cleanup){failure.addSuppressed(cleanup);}
            try { current.fail(failure); }
            catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            System.err.println("[ApocBR-Agent][NPC] Engine stopped after lifecycle failure: " + failure);
        }
    }

    public static void shutdown() {
        var current = engine;
        if (current == null) return;
        try { current.close(); }
        catch (RuntimeException | LinkageError failure) {
            System.err.println("[ApocBR-Agent][NPC] Detached worker cleanup failed: " + failure);
        }
        try { if(actors)NpcActorRuntime.shutdown(); }
        catch (RuntimeException | LinkageError failure) { System.err.println("[ApocBR-Agent][NPC] Actor cleanup failed: "+failure.getClass().getSimpleName()); }
    }

    /** Owner-thread teardown before native world/cell disposal; next ready cell gets a fresh session. */
    public static void worldReset(){
        var current=engine;if(current==null||current.status().stopped())return;
        try{if(actors)NpcActorRuntime.shutdown();current.update(null,System.nanoTime());}
        catch(RuntimeException|LinkageError failure){try{current.fail(failure);}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            System.err.println("[ApocBR-Agent][NPC] World reset failed; NPC runtime stopped.");}
    }

    public static NpcEngine.Status status() { return engine == null ? null : engine.status(); }
}
