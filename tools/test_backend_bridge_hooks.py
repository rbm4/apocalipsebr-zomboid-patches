"""Run production capture hooks with small engine-state stubs; verify call-site contracts."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.21.0"
FILES = {
    "zombie/GameTime.java": """package zombie; public class GameTime {
        static final GameTime instance=new GameTime(); public static GameTime getInstance(){return instance;}
        public int year=1993,month=6,day=8;public float hour=13.333f;
        public int getYear(){return year;} public int getMonth(){return month;} public int getDay(){return day;}
        public float getTimeOfDay(){return hour;} public int getMinutes(){return 20;}
        public double getWorldAgeHours(){return 25.5;}}
    """,
    "zombie/network/GameServer.java": """package zombie.network; import zombie.characters.IsoPlayer;
        public class GameServer {public static boolean server; public static final java.util.Map<Short,IsoPlayer>
        IDToPlayerMap=new java.util.HashMap<>();}
    """,
    "zombie/network/IConnection.java": """package zombie.network; import zombie.characters.IsoPlayer;
        public interface IConnection {IsoPlayer getPlayerAt(int slot);}
    """,
    "zombie/characters/IsoGameCharacter.java": """package zombie.characters; import zombie.characters.skills.PerkFactory;
        public class IsoGameCharacter {public final java.util.ArrayList<PerkInfo> perks=new java.util.ArrayList<>();
        public java.util.ArrayList<PerkInfo> getPerkList(){return perks;}
        public static class PerkInfo {public PerkFactory.Perk perk;public int level;
        public PerkInfo(PerkFactory.Perk perk,int level){this.perk=perk;this.level=level;}}}
    """,
    "zombie/characters/IsoPlayer.java": """package zombie.characters; public class IsoPlayer extends IsoGameCharacter {
        public short id;public String name; public boolean dead,animal;
        public IsoPlayer(int id,String name){this.id=(short)id;this.name=name;}
        public boolean isAnimal(){return animal;}public boolean isDead(){return dead;}
        public short getOnlineID(){return id;}public String getUsername(){return name;}
        public String getDisplayName(){return "display:"+name;}}
    """,
    "zombie/characters/skills/PerkFactory.java": """package zombie.characters.skills; public class PerkFactory {
        public record Perk(String id) {public String getId(){return id;}}}
    """,
    "zombie/debug/DebugLog.java": """package zombie.debug; public class DebugLog {
        public static int logs; public static void log(String text){logs++;}}
    """,
    "zombie/iso/IsoWorld.java": """package zombie.iso; public class IsoWorld {
        public static IsoWorld instance=new IsoWorld(); public Helicopter helicopter=new Helicopter();
        public static class Helicopter {public boolean isActive(){return false;}}}
    """,
    "zombie/erosion/season/ErosionSeason.java": """package zombie.erosion.season;public class ErosionSeason {
        public float getDawn(){return 5.5f;}public float getDusk(){return 20.5f;}public float getDaylight(){return 15;}}
    """,
    "zombie/iso/weather/ClimateManager.java": """package zombie.iso.weather;public class ClimateManager {
        static final ClimateManager instance=new ClimateManager();public static ClimateManager getInstance(){return instance;}
        public zombie.erosion.season.ErosionSeason getSeason(){return new zombie.erosion.season.ErosionSeason();}
        public float temperature=27,rain=.7f,snow=0,fog=.2f,wind=15,angle=90;public boolean thunder=true;
        public float getTemperature(){return temperature;}public float getRainIntensity(){return rain;}
        public float getSnowIntensity(){return snow;}public float getFogIntensity(){return fog;}
        public float getWindspeedKph(){return wind;}public float getWindAngleDegrees(){return angle;}
        public boolean getIsThunderStorming(){return thunder;}public WeatherPeriod getWeatherPeriod(){return new WeatherPeriod();}
        public String getSeasonName(){return "Summer";}public static class WeatherPeriod{public boolean isBlizzard(){return false;}}}
    """,
    "BridgeHooksTest.java": r"""
import zombie.network.*;
import zombie.network.ApocBRBackendBridge.*;
import zombie.characters.*;
import zombie.characters.skills.PerkFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
public class BridgeHooksTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static long count(List<Event> events,EventType type){return events.stream().filter(e->e.type()==type).count();}
    public static void main(String[] args)throws Exception {
        IsoPlayer alice=new IsoPlayer(10,"Alice"),bob=new IsoPlayer(11,"Bob"),offline=new IsoPlayer(12,"Offline");
        IConnection aliceConnection=slot->slot==0?alice:null;
        ApocBRBackendBridgeHooks.onConnection(alice,true);
        check(ApocBRBackendBridgeHooks.current()==null,"no implicit capture/thread startup");
        GameServer.server=true;
        var bridge=new ApocBRBackendBridge("pve",new Settings(64,64,Duration.ofMillis(1),
                Duration.ofMillis(1),Duration.ofMillis(2)));
        ApocBRBackendBridgeHooks.install(bridge);
        GameServer.IDToPlayerMap.put(alice.id,alice);GameServer.IDToPlayerMap.put(bob.id,bob);
        GameServer.server=false;
        ApocBRBackendBridgeHooks.onConnection(alice,true);
        check(!ApocBRRadioBridge.recordAiredLine("amp","announcer","client text"),"client radio publication ignored");
        GameServer.server=true;
        check(bridge.status().accepted()==0,"client capture ignored");
        ApocBRBackendBridgeHooks.onConnection(alice,true);
        ApocBRBackendBridgeHooks.onConnection(offline,true);
        ApocBRBackendBridgeHooks.onChat(aliceConnection,"Bob","spoofed","general");
        ApocBRBackendBridgeHooks.onChat(aliceConnection,"Alice","private","whisper");
        ApocBRBackendBridgeHooks.onChat(aliceConnection,"Alice","hello","general");
        ApocBRBackendBridgeHooks.onChat(aliceConnection,"display:Alice","world hello","world");
        ApocBRBackendBridgeHooks.onDeath(alice,bob);
        var perk=new PerkFactory.Perk("Woodwork");
        ApocBRBackendBridgeHooks.onLevelUp(alice,perk,3,4,false);
        ApocBRBackendBridgeHooks.onLevelUp(alice,perk,4,4,false);
        ApocBRBackendBridgeHooks.onLevelUp(alice,perk,4,3,false);
        ApocBRBackendBridgeHooks.onLevelUp(alice,perk,10,11,false);
        ApocBRBackendBridgeHooks.onLevelUp(offline,perk,0,5,false);
        alice.perks.add(new IsoGameCharacter.PerkInfo(perk,5));
        ApocBRBackendBridgeHooks.onPerksLoaded(alice,Map.of(perk,4));
        ApocBRBackendBridgeHooks.onPerksLoaded(alice,Map.of(perk,5));
        ApocBRBackendBridgeHooks.onPopulation(2,null);
        ApocBRBackendBridgeHooks.onPopulation(2,321);
        ApocBRBackendBridgeHooks.onTick();
        ApocBRBackendBridgeHooks.onTick();
        ApocBRBackendBridgeHooks.onHelicopter(true);
        ApocBRBackendBridgeHooks.onHelicopter(true);
        ApocBRBackendBridgeHooks.onHelicopter(false);
        check(ApocBRRadioBridge.recordMusicState("amp","song1","song","started",123,"Song"),"radio content accepted");
        check(ApocBRRadioBridge.recordListenerRequest("amp","Attribution","song1,song2","Hello",2,2),"listener request accepted");
        check(ApocBRRadioBridge.recordAiredLine("amp","announcer","Good evening"),"aired line accepted");
        check(!ApocBRRadioBridge.recordMusicState("amp","song1","song","started",Double.NaN,"Song"),"invalid sequence ignored");
        check(!ApocBRRadioBridge.recordListenerRequest("amp","Attribution","song1","",1.5,2),"fractional count ignored");
        check(!ApocBRRadioBridge.recordAiredLine("amp","private","Secret"),"unknown line type ignored");
        ApocBRBackendBridgeHooks.onConnection(alice,false);
        int expected=(int)bridge.status().accepted();
        List<Event> events=new ArrayList<>();CountDownLatch done=new CountDownLatch(expected);
        bridge.start(batch->{events.addAll(batch);for(Event ignored:batch)done.countDown();});
        check(done.await(5,TimeUnit.SECONDS),"events delivered");
        ApocBRBackendBridgeHooks.shutdown();
        check(bridge.awaitTermination(Duration.ofSeconds(5)),"shutdown wired");
        check(count(events,EventType.PLAYER_CHAT)==2,"public owned chat only");
        check(count(events,EventType.PLAYER_CONNECTED)==1&&count(events,EventType.PLAYER_DISCONNECTED)==1,"online presence only");
        check(count(events,EventType.CHARACTER_LEVEL_UP)==2,"increases only, no initial load or repeats");
        check(count(events,EventType.PLAYER_DEATH)==1,"death capture");
        check(count(events,EventType.POPULATION_SNAPSHOT)==1,"telemetry samples throttled to two minutes");
        check(count(events,EventType.WORLD_CLOCK_SNAPSHOT)==1&&count(events,EventType.WEATHER_SNAPSHOT)==1,"snapshot cadence");
        check(count(events,EventType.HELICOPTER_STATE)==2,"helicopter transitions only, no initial story or repeated state");
        check(count(events,EventType.RADIO_CONTENT_STATE)==1&&count(events,EventType.RADIO_LISTENER_REQUEST)==1
                &&count(events,EventType.RADIO_LINE_AIRED)==1,"typed radio observations");
        for(Event event:events){
            check(event.gameTime().equals("1993-07-09T13:20"),"calendar indexes normalized");
            check(!event.publicPayload().containsKey("characterId")&&!event.publicPayload().containsKey("steamId")
                    &&!event.publicPayload().containsKey("x"),"public payload has no IDs/private positions");
        }
        Event weather=events.stream().filter(e->e.type()==EventType.WEATHER_SNAPSHOT).findFirst().orElseThrow();
        check(weather.publicPayload().get("temperatureC").equals(27d)
                &&weather.publicPayload().get("climateSeason").equals("Summer")
                &&weather.publicPayload().get("dawnHour").equals(5.5d)&&weather.expiresAt()>weather.occurredAt(),"weather units, season, light and expiry");
        Event request=events.stream().filter(e->e.type()==EventType.RADIO_LISTENER_REQUEST).findFirst().orElseThrow();
        check(request.publicPayload().get("observationBasis").equals("supplied_request_name"),"request attribution not claimed online");
        check(ApocBRBackendBridgeHooks.captureFailures()==1,"invalid chat capture isolated from original flow");
        System.out.println("Passed "+checks+" production bridge-hook assertions.");
    }
}
""",
}


def check_call_sites():
    src = VERSION / "src/zombie"
    game_server = (src / "network/GameServer.java").read_text(encoding="utf-8")
    assert game_server.index("ApocBRBackendBridgeHooks.initialize();") < game_server.index(
        'LuaEventManager.triggerEvent("OnServerStarted")')
    assert "ApocBRBackendBridgeHooks.onTick();" in game_server
    assert "ApocBRBackendBridgeHooks.onHelicopter(active);" in game_server
    connect = game_server[game_server.index("public static void receivePlayerConnect("):]
    assert connect.index("player.username = username;") < connect.index("ApocBRBackendBridgeHooks.onConnection(player, true);")
    disconnect = game_server[game_server.index("public static void disconnectPlayer("):]
    assert disconnect.index("ApocBRBackendBridgeHooks.onConnection(player, false);") < disconnect.index("IDToPlayerMap.remove")
    player = (src / "characters/IsoPlayer.java").read_text(encoding="utf-8")
    assert player.count("ApocBRBackendBridgeHooks.onDeath(this, killer);") == 1
    character = (src / "characters/IsoGameCharacter.java").read_text(encoding="utf-8")
    assert "if (!this.isOnKillDone())" in character
    assert "ApocBRBackendBridgeHooks.onLevelUp(this, perk, previousLevel, info.level, false);" in character
    assert "ApocBRBackendBridgeHooks.onPerksLoaded(IsoGameCharacter.this, oldPerkLevels);" in character
    chat = (src / "network/chat/ChatServer.java").read_text(encoding="utf-8")
    assert 'if (chat.getType() == ChatType.general)' in chat
    assert chat.index("message.setText(WordsFilter") < chat.index("ApocBRBackendBridgeHooks.onChat")
    telemetry = (src / "ApocBRServerTelemetryLite.java").read_text(encoding="utf-8")
    assert telemetry.count("getZombieList().size()") == 1
    assert "ApocBRBackendBridgeHooks.onPopulation(players, loadedZombieCount);" in telemetry
    lua = (src / "Lua/LuaManager.java").read_text(encoding="utf-8")
    assert "this.setExposed(zombie.network.ApocBRRadioBridge.class);" in lua
    assert "ApocBRBackendBridgeHooks.shutdown();" in (src / "network/ServerMap.java").read_text(encoding="utf-8")


def main():
    check_call_sites()
    with tempfile.TemporaryDirectory(prefix="apocbr-bridge-hooks-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in FILES.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(VERSION / "src/zombie/network" / name for name in (
            "ApocBRBackendBridge.java", "ApocBRBackendBridgeHooks.java", "ApocBRRadioBridge.java",
            "ApocBRBackendHttpDelivery.java"))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
        output = work / "classes"
        game_jar = Path("Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-cp", str(game_jar), "-d", str(output),
                        "@" + str(args)], check=True, timeout=60)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output) + ";" + str(game_jar), "BridgeHooksTest"],
                       check=True, timeout=60)


if __name__ == "__main__":
    main()
