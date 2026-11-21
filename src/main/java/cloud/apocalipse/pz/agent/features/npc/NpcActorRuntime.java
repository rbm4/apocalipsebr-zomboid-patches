package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.npc.persistence.ApocBRNpcStore;
import cloud.apocalipse.pz.agent.features.npc.planning.NpcDecisionPlanner;
import cloud.apocalipse.pz.agent.features.postgres.NpcPersistence;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import zombie.network.GameServer;
import zombie.core.Core;
import zombie.ZomboidFileSystem;

/** Server-owned native session. Detached store/journal shutdown always precedes shared-pool shutdown. */
final class NpcActorRuntime {
    private static Object world;
    private static volatile boolean started;
    static void beforeWorldChange(Object next){if(started&&world!=next)shutdown();}
    static void update(Object current,NpcDecisionPlanner planner){
        if(!started){
            NpcWorldAccess.requireMembership(current);
            if(ApocBRNpcStore.enabled()){
                var settings=AgentConfiguration.postgresSettings();
                if(!settings.enabled()||settings.poolSize()<3)throw new IllegalStateException("NPC persistence requires PostgreSQL and at least three pool leases");
                String server=AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_NPC_SERVERKEY,GameServer.serverName);
                String scope;
                try{scope=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((server+"\0"+Core.gameSaveWorld).getBytes(StandardCharsets.UTF_8)));}
                catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
                var directory=Path.of(AgentConfiguration.systemProperty(AgentConfiguration.NPC_JOURNAL_DIRECTORY_PROPERTY,
                    Path.of(ZomboidFileSystem.instance.getCacheDir(),"apocbr-agent","npc-journal").toString())).resolve(scope);
                NpcPersistence.start(directory);
            }
            ServerHumanFactory.startSession(planner);world=current;started=true;
        }
        ServerHumanFactory.update();
    }
    static void shutdown(){
        if(!started&&!NpcPersistence.enabled())return;
        try{ServerHumanFactory.shutdown();}
        finally{
            try{ApocBRNpcStore.shutdown();}
            finally{NpcPersistence.shutdown();}
            started=false;world=null;
        }
    }
}
