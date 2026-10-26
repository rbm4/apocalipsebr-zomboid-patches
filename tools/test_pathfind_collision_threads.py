"""Exercise real collision scratch objects against the exact game JAR, without a world.

This verifies thread isolation and geometry scratch reuse, not concurrent world reads.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r"""
package zombie.pathfind;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import zombie.iso.Vector2;

public class CollisionThreadsTest {
    static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    public static void main(String[] args) throws Exception {
        PolygonalMap2 map = PolygonalMap2.instance;
        Field field = PolygonalMap2.class.getDeclaredField("lccMain");
        field.setAccessible(true);
        ThreadLocal<?> local = (ThreadLocal<?>) field.get(map);
        Set<Object> helpers = ConcurrentHashMap.newKeySet();
        Set<Object> scratch = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        var start = new CyclicBarrier(4);
        var executor = Executors.newFixedThreadPool(4);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int thread = 0; thread < 4; thread++) {
                final int id = thread;
                results.add(executor.submit(() -> {
                    try {
                        var helper = (LineClearCollideMain) local.get();
                        check(helpers.add(helper), "different threads share collision helper");
                        for (Field f : LineClearCollideMain.class.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                            f.setAccessible(true);
                            Object value = f.get(helper);
                            check(scratch.add(value), "shared scratch field " + f.getName());
                            if (value instanceof Object[] array)
                                for (Object element : array)
                                    check(scratch.add(element), "shared nested vector");
                        }
                        Field result = LineClearCollideMain.class.getDeclaredField("collidePoint");
                        result.setAccessible(true);
                        Vector2 borrowed = (Vector2) result.get(helper);
                        check(borrowed != PolygonalMap2.temp, "collision result uses global vector");
                        borrowed.set(id, -id);
                        start.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < 20000; i++) {
                            check(local.get() == helper, "helper not cached on caller thread");
                            for (Point p : helper.pts) helper.pointPool.release(p);
                            helper.pts.clear();
                            int x = id * 100;
                            map.supercover(x + .5f, .5f, x + 8.5f, .5f, 0, helper.pointPool, helper.pts);
                            check(helper.pts.size() == 9, "supercover lost or gained points");
                            for (int j = 0; j < 9; j++) {
                                Point p = helper.pts.get(j);
                                check(p.x == x + j && p.y == 0, "point overwritten by competing query");
                            }
                            check(helper.lb.lineRectIntersect(x, .5f, 10, 0, x+2, 0, x+3, 1), "expected intersection");
                            check(!helper.lb.lineRectIntersect(x, 2, 10, 0, x+2, 0, x+3, 1), "unexpected intersection");
                            check(borrowed.x == id && borrowed.y == -id, "borrowed result overwritten across threads");
                        }
                        return null;
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                }));
            }
            for (Future<?> result : results) result.get(30, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        check(helpers.size() == 4, "expected four independent helpers");
        System.out.println("PASS: four threads, 80000 pooled supercover queries, intersection and result isolation");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pz-dir", type=Path, default=Path("Z:/SteamLibrary/steamapps/common/ProjectZomboid"))
    args = parser.parse_args()
    jar = args.pz_dir / "projectzomboid.jar"
    jdk = ROOT / "jdk" / "bin"
    src = ROOT / "42.21.0" / "src" / "zombie" / "pathfind"
    # Cover every production boundary, including the debug query and standing check.
    source = (src / "PolygonalMap2.java").read_text(encoding="utf-8")
    assert source.count("this.lccMain.get().") == 4
    assert "this.lccMain.isNotClear" not in source
    assert "PolygonalMap2.temp" not in (src / "LineClearCollideMain.java").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="pz_collision_threads_") as tmp:
        tmp = Path(tmp)
        harness = tmp / "CollisionThreadsTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "-implicit:none",
                        "-cp", str(jar), "-d", str(tmp),
                        str(src / "PolygonalMap2.java"), str(src / "LineClearCollideMain.java"),
                        str(harness)], check=True)
        subprocess.run([str(jdk / "java.exe"), "-ea", "-cp", os.pathsep.join([str(tmp), str(jar)]),
                        "zombie.pathfind.CollisionThreadsTest"], check=True, timeout=60)


if __name__ == "__main__":
    main()
