package cloud.apocalipse.pz.agent.features.npc;

import zombie.network.LoginQueue;

/** Executed only after transformation, in a disposable test JVM. */
public class NpcHookFixture {
    public static int iterations, simulations, quits;
    public static void main(String[] args) {
        // Retain a real exit instruction for visitor validation; tests never select this branch.
        if (args.length == 100) System.exit(0);
        for (int i=0;i<5;i++) {
            iterations++;
            if ((i & 1) == 0) {
                LoginQueue.update();
                simulations++;
                if (!NpcRuntime.status().started()) throw new AssertionError("Native simulation did not start production NPC engine");
                if(args[0].equals("factory")&&!ServerHumanFactory.ready())throw new AssertionError("Production factory was not reached");
            }
        }
        if(args[0].equals("factory")){
            var factory=ServerHumanFactory.getInstance();
            var definition=new ServerHumanDefinition("fixture-dormant","fixture","Fixture",1,1,0,zombie.iso.IsoDirections.E,false,"Tourist","stationary");
            factory.registerDormant(definition);
            if(factory.lifecycle(definition.key()).state()!=HumanLifecycle.State.DORMANT)throw new AssertionError("Logical registration failed");
            if(factory.mutate(definition.key(),0,0,new cloud.apocalipse.pz.agent.features.npc.state.HumanMutation.Kill()).status()
                    !=cloud.apocalipse.pz.agent.features.npc.state.HumanStateStore.Status.INACTIVE)throw new AssertionError("Dormant mutation was accepted");
            if(!factory.remove(definition.key())||factory.lifecycle(definition.key())!=null)throw new AssertionError("Logical retirement failed");
            try{factory.registerDormant(definition);throw new AssertionError("Retired logical key reused");}catch(IllegalArgumentException expected){}
            NpcRuntime.worldReset();
            if(NpcRuntime.status().started()||ServerHumanFactory.ready())throw new AssertionError("World reset retained actor authority");
            zombie.iso.IsoWorld.instance.currentCell=new zombie.iso.IsoWorld.Cell();
            NpcRuntime.update();
            if(!ServerHumanFactory.ready()||ServerHumanFactory.getInstance()==factory||NpcRuntime.status().generation()!=2)
                throw new AssertionError("World replacement did not create a fresh factory/planner generation");
            new NpcQuitFixture().QueuedQuit();
        }
        if (args[0].equals("quit")) new NpcQuitFixture().QueuedQuit();
        if (args[0].equals("console")) new NpcQuitFixture().preupdate();
        if (args[0].equals("exception")) throw new IllegalArgumentException("fixture failure");
    }
}
