"""Verify schema 3 using the production 42.21.0 emitter and minimal engine stubs."""
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "zombie/core/Core.java": 'package zombie.core; public class Core { public static Core getInstance(){return new Core();} public String getVersionNumber(){return "42.21";} }',
    "zombie/debug/DebugLog.java": "package zombie.debug; public class DebugLog { public static void log(String s){} }",
    "zombie/network/GameServer.java": "package zombie.network; public class GameServer { public static final java.util.Map<Integer,Object> IDToPlayerMap=new java.util.HashMap<>(); public static final java.util.List<Object> Players=new java.util.ArrayList<>(); }",
    "zombie/network/ServerMap.java": "package zombie.network; public class ServerMap { public static final ServerMap instance=new ServerMap(); public final java.util.List<Object> loadedCells=new java.util.ArrayList<>(); public int telemetryPendingCells(){return 0;} }",
    "zombie/iso/IsoMovingObject.java": "package zombie.iso; public class IsoMovingObject {}",
    "zombie/iso/IsoWorld.java": "package zombie.iso; public class IsoWorld { public static final IsoWorld instance=new IsoWorld(); public Cell currentCell=new Cell(); public static class Cell { public java.util.List<Object> getZombieList(){return java.util.List.of();} public java.util.List<IsoMovingObject> getObjectList(){return java.util.List.of();} } }",
}
HARNESS = r'''
import java.lang.reflect.*;
import java.util.concurrent.ArrayBlockingQueue;
import zombie.ApocBRServerTelemetryLite;
public class TelemetryOutputTest {
    public static void main(String[] args) throws Exception {
        // Insert children before parents and siblings in reverse lexical order.
        ApocBRServerTelemetryLite.recordPhase("simulation.vehicles.update.checks", 250000);
        ApocBRServerTelemetryLite.recordPhase("simulation.vehicles.update", 1000000);
        ApocBRServerTelemetryLite.recordPhase("simulation.vehicles.update", 3000000);
        ApocBRServerTelemetryLite.recordPhase("simulation.update", 5000000);
        ApocBRServerTelemetryLite.recordPhase("simulationX.update", 7000000);
        ApocBRServerTelemetryLite.recordPhase("network.zombies.send", 0);
        ApocBRServerTelemetryLite.recordPhase("escaped.quo\"te", 1000000);
        ApocBRServerTelemetryLite.recordLuaEvent("OnTick", 2, 500000);
        ApocBRServerTelemetryLite.count("vehicles.updated", 3);
        ApocBRServerTelemetryLite.recordTick(8000000);
        Method emit = ApocBRServerTelemetryLite.class.getDeclaredMethod("emit", long.class);
        emit.setAccessible(true);
        Field queueField = ApocBRServerTelemetryLite.class.getDeclaredField("outputQueue");
        queueField.setAccessible(true);
        ArrayBlockingQueue<?> queue = (ArrayBlockingQueue<?>) queueField.get(null);
        long now = System.currentTimeMillis() + 60000;
        emit.invoke(null, now);
        System.out.print(queue.remove());
        // No measurements should leak into the next window.
        emit.invoke(null, now + 60000);
        System.out.print(queue.remove());
        // A previously omitted child can reappear without its measured parent.
        ApocBRServerTelemetryLite.recordPhase("simulation.vehicles.update.checks", 500000);
        emit.invoke(null, now + 120000);
        System.out.print(queue.remove());
    }
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-telemetry-tests-") as directory:
        work = Path(directory)
        sources = [ROOT / "42.21.0/src/zombie/ApocBRServerTelemetryLite.java"]
        for relative, content in {**STUBS, "TelemetryOutputTest.java": HARNESS}.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        result = subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-Dapocbr.telemetry.lua.enabled=true", "-cp", str(output), "TelemetryOutputTest"], check=True, capture_output=True, text=True)
        records = [json.loads(line) for line in result.stdout.splitlines()]
        assert len(records) == 3
        first, empty, child_only = records
        assert first["schemaVersion"] == 3
        phases = first["phases"]
        assert list(phases) == sorted(phases)
        assert list(phases["simulation"]) == ["update", "vehicles"]
        update = phases["simulation"]["vehicles"]["update"]
        assert update == {"calls": 2, "totalMs": 4.0, "avgMs": 2.0, "maxMs": 3.0,
                          "checks": {"calls": 1, "totalMs": 0.25, "avgMs": 0.25, "maxMs": 0.25}}
        assert "calls" not in phases["simulation"]
        assert phases["simulationX"]["update"]["totalMs"] == 7.0
        assert phases["network"]["zombies"]["send"]["calls"] == 1
        assert phases["escaped"]['quo"te']["totalMs"] == 1.0
        assert first["counters"] == {"vehicles.updated": 3}
        assert first["tick"]["count"] == 1
        assert first["lua"]["events"][0]["callbacks"] == 2
        assert empty["phases"] == {} and empty["tick"]["count"] == 0
        assert empty["counters"]["vehicles.updated"] == 0 and empty["lua"]["events"] == []
        assert child_only["phases"] == {"simulation": {"vehicles": {"update": {
            "checks": {"calls": 1, "totalMs": 0.5, "avgMs": 0.5, "maxMs": 0.5}}}}}
        assert [record["seq"] for record in records] == [1, 2, 3]
    print("Passed telemetry output checks: nested parents/children, grouping, ordering, escaping, reset, and existing fields.")


if __name__ == "__main__":
    main()
