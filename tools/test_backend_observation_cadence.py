"""Deterministic production-hook cadence/change tests; no server or HTTP calls."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_backend_bridge_hooks import FILES, ROOT, VERSION

HARNESS = r"""
package zombie.network;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import zombie.GameTime;
import zombie.iso.weather.ClimateManager;
import zombie.network.ApocBRBackendBridge.*;

public class ObservationCadenceTest {
    static int checks;
    static ApocBRBackendBridge bridge;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void delta(int expected,Runnable action,String message){
        long before=bridge.status().accepted();action.run();check(bridge.status().accepted()-before==expected,message);
    }
    public static void main(String[] args)throws Exception {
        GameServer.server=true;
        bridge=new ApocBRBackendBridge("pve",new Settings(128,100,Duration.ofMillis(1),Duration.ofMillis(1),Duration.ofMillis(2)));
        ApocBRBackendBridgeHooks.install(bridge);
        long t=1_000_000_000L,s=1_000_000_000L;
        var climate=ClimateManager.getInstance();var time=GameTime.getInstance();
        delta(2,()->ApocBRBackendBridgeHooks.onTick(t),"initial clock/weather state, no helicopter story");
        delta(0,()->ApocBRBackendBridgeHooks.onTick(t+s),"unchanged state not published every poll");
        climate.temperature+=.01f;climate.wind+=.01f;climate.angle+=.01f;climate.rain+=.001f;
        delta(0,()->ApocBRBackendBridgeHooks.onTick(t+2*s),"insignificant float fluctuations suppressed");
        climate.rain=0;
        delta(1,()->ApocBRBackendBridgeHooks.onTick(t+3*s),"rain stopping is news");
        climate.rain=.03f;
        delta(0,()->ApocBRBackendBridgeHooks.onTick(t+4*s),"rain start hysteresis rejects near-zero noise");
        climate.rain=.06f;
        delta(1,()->ApocBRBackendBridgeHooks.onTick(t+5*s),"rain starting is news");
        climate.rain=.03f;
        delta(0,()->ApocBRBackendBridgeHooks.onTick(t+6*s),"rain stop hysteresis avoids oscillation");
        climate.rain=.005f;
        delta(1,()->ApocBRBackendBridgeHooks.onTick(t+7*s),"rain below stop threshold is news");
        climate.temperature=29.02f;
        delta(1,()->ApocBRBackendBridgeHooks.onTick(t+8*s),"accumulated two-degree temperature change is news");
        climate.thunder=false;
        delta(1,()->ApocBRBackendBridgeHooks.onTick(t+9*s),"storm transition immediate");
        time.hour=18;
        delta(1,()->ApocBRBackendBridgeHooks.onTick(t+10*s),"time-of-day boundary refreshes clock only");
        time.day++;
        delta(1,()->ApocBRBackendBridgeHooks.onTick(t+11*s),"calendar change refreshes clock only");
        delta(2,()->ApocBRBackendBridgeHooks.onTick(t+312*s),"five-minute freshness refreshes clock and weather");
        delta(1,()->ApocBRBackendBridgeHooks.onPopulation(2,100,t),"initial population state");
        delta(0,()->ApocBRBackendBridgeHooks.onPopulation(20,900,t+119*s),"telemetry samples throttled before two minutes");
        delta(1,()->ApocBRBackendBridgeHooks.onPopulation(2,101,t+120*s),"minor population changes refresh state");
        delta(1,()->ApocBRBackendBridgeHooks.onPopulation(5,102,t+240*s),"significant player increase is news");
        delta(1,()->ApocBRBackendBridgeHooks.onPopulation(5,103,t+360*s),"unchanged population refresh is state");
        delta(1,()->ApocBRBackendBridgeHooks.onHelicopter(true),"helicopter arrival");
        delta(0,()->ApocBRBackendBridgeHooks.onHelicopter(true),"helicopter unchanged suppressed");
        delta(1,()->ApocBRBackendBridgeHooks.onHelicopter(false),"helicopter departure");
        delta(2,()->{bridge.publishDeath(new Context(1000,null,null),new PublicCharacter("Ana","Ana"),null);
            bridge.publishDeath(new Context(1000,null,null),new PublicCharacter("Ana","Ana"),null);},"distinct events with identical facts retained");
        List<Event> events=new ArrayList<>();var done=new CountDownLatch((int)bridge.status().accepted());
        bridge.start(batch->{events.addAll(batch);batch.forEach(e->done.countDown());});
        check(done.await(5,TimeUnit.SECONDS),"all events drained");bridge.close();
        check(bridge.awaitTermination(Duration.ofSeconds(2)),"worker stopped");
        check(events.stream().filter(e->e.type()==EventType.WEATHER_SNAPSHOT).count()==2,"weather only initial and heartbeat state");
        check(events.stream().filter(e->e.type()==EventType.WEATHER_CHANGED).count()==5,"five meaningful weather changes");
        check(events.stream().filter(e->e.type()==EventType.WORLD_CLOCK_SNAPSHOT).count()==4,"clock initial, period, date, heartbeat");
        check(events.stream().filter(e->e.type()==EventType.POPULATION_CHANGED).count()==1,"only significant population story");
        check(events.stream().filter(e->e.type()==EventType.POPULATION_SNAPSHOT).count()==3,"population freshness states");
        var news=events.stream().filter(e->e.type()==EventType.POPULATION_CHANGED).findFirst().orElseThrow();
        check(news.publicPayload().get("previousPlayersOnline").equals(2)&&news.publicPayload().get("playersOnline").equals(5),"population news includes prior count");
        var weather=events.stream().filter(e->e.type()==EventType.WEATHER_CHANGED).findFirst().orElseThrow();
        check(weather.publicPayload().containsKey("previousRainIntensity"),"weather news retains previous facts");
        check(events.stream().filter(e->e.type()==EventType.WEATHER_CHANGED||e.type()==EventType.POPULATION_CHANGED).allMatch(e->e.expiresAt()==null),"news retained independently of snapshot freshness");
        check(events.stream().map(Event::sourceEventId).distinct().count()==events.size(),"distinct observations have distinct transport IDs");
        check(ApocBRBackendBridgeHooks.captureFailures()==0,"no capture failures");
        System.out.println("Passed "+checks+" deterministic observation cadence assertions.");
    }
}
"""

def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-observation-cadence-") as directory:
        work=Path(directory)
        sources=[]
        for relative,content in FILES.items():
            if relative == "BridgeHooksTest.java": continue
            path=work/relative;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(content,encoding="utf-8");sources.append(path)
        harness=work/"ObservationCadenceTest.java";harness.write_text(HARNESS,encoding="utf-8");sources.append(harness)
        sources.extend(VERSION/"src/zombie/network"/name for name in ("ApocBRBackendBridge.java","ApocBRBackendBridgeHooks.java","ApocBRBackendHttpDelivery.java"))
        args=work/"javac.args"
        args.write_text("\n".join('"'+str(path).replace("\\","/")+'"' for path in sources),encoding="utf-8")
        output=work/"classes";jar=Path("Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")
        subprocess.run([str(ROOT/"jdk/bin/javac.exe"),"--release","25","-cp",str(jar),"-d",str(output),"@"+str(args)],check=True,timeout=60)
        subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-cp",str(output)+";"+str(jar),"zombie.network.ObservationCadenceTest"],check=True,timeout=60)

if __name__ == "__main__": main()
