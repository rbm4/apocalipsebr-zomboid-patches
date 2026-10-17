"""42.21.0 keep-first capture/JDBC regressions; no game JAR or live database.

Reuse the relational fixture's models and assertions without executing its native
accessor suite. Compile the complete production capture/snapshot/queue/delta/JDBC
classes against engine boundary stubs. Also run vanilla AddItem itself to compare
its duplicate-insertion result with capture's keep-first behavior.
"""
from postgres_test_support import inactive_persistence_stubs

import ast
from pathlib import Path
import subprocess
import tempfile

from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.21.0"
SRC = VERSION / "src/zombie/savefile"


def fixture():
    tree = ast.parse((ROOT / "tools/test_postgres_player_relational.py").read_text())
    harness = next(ast.literal_eval(node.value) for node in tree.body
                   if isinstance(node, ast.Assign)
                   and any(isinstance(t, ast.Name) and t.id == "HARNESS" for t in node.targets))
    models = harness[harness.index(" static class Color"):harness.index(" static void captures()")]
    members = [method(harness, signature) for signature in (
        "static void check(", "static List<String> canonical(",
        "static ApocBRPlayerRelationalDatabase.Candidate candidate(",
        "static class LiveFake", "static Object zero(",
        "static ApocBRPlayerRelationalCapture.Graph liveGraph(",
        "static void duplicateNativeItems(", "static void orphanEquipment(",
        "static void graphQueue(", "static void mutationQueue(",
        "static void mutationDatabase(", "static void extendedMutations(",
        "static void aliasGenerations(",
    )]
    return """package zombie.savefile;
import java.util.*; import java.lang.reflect.*; import java.sql.*;
public class RelationalTest {
static int checks;
""" + models + "\n".join(members) + r'''
 public static void main(String[] args)throws Exception {
  mutationQueue();mutationDatabase();extendedMutations();graphQueue();aliasGenerations();orphanEquipment();
  duplicateNativeItems();
  var vanilla=new zombie.inventory.ItemContainer();
  var first=new zombie.inventory.InventoryItem(7,"original");
  var second=new zombie.inventory.InventoryItem(7,"duplicate");
  check(vanilla.AddItem(first)==first,"vanilla establishes first item");
  check(vanilla.AddItem(second)==first&&vanilla.items.equals(List.of(first)),"vanilla rejects later ID without replacing the existing item");
  P player=new P();Item original=new Item(first.id),duplicate=new Item(second.id);
  original.name=first.name;duplicate.name=second.name;player.inventory.items.addAll(List.of(original,duplicate));
  var graph=liveGraph(player,UUID.randomUUID());
  var items=graph.rows().stream().filter(r->r.table().equals("item")).toList();
  check(items.size()==vanilla.items.size()&&items.getFirst().values().get("name").equals(vanilla.items.getFirst().name),"projection agrees with native AddItem keep-first outcome");
  check(zombie.debug.DebugLog.messages.stream().filter(s->s.contains("DUPLICATE_NATIVE_ITEM_SKIPPED;")).count()==1,"duplicate warning is rate limited independently of skipped-item count");
  System.out.println("Duplicate item checks="+checks);
 }
}
'''


STUBS = {
    "zombie/network/GameServer.java": "package zombie.network; public class GameServer {public static boolean server=true;}",
    "zombie/debug/DebugLog.java": "package zombie.debug; public class DebugLog {public static final java.util.List<String> messages=new java.util.ArrayList<>();public static void log(String s){messages.add(s);}}",
    "zombie/debug/DebugType.java": "package zombie.debug; public class DebugType {public static final DebugType General=new DebugType();public void error(String s){}}",
    "zombie/characters/CharacterStat.java": 'package zombie.characters; public class CharacterStat {public static final CharacterStat[] ORDERED_STATS={new CharacterStat()};public String id="fixture";}',
    "zombie/characters/IsoPlayer.java": "package zombie.characters; public class IsoPlayer extends zombie.savefile.RelationalTest.P {}",
    "zombie/characters/IsoGameCharacter.java": "package zombie.characters; public class IsoGameCharacter extends zombie.iso.IsoObject {}",
    "zombie/characters/animals/IsoAnimal.java": "package zombie.characters.animals; public class IsoAnimal {}",
    "zombie/GameTime.java": "package zombie; public class GameTime {public static GameTime getInstance(){return new GameTime();}public java.util.Calendar getCalender(){return java.util.Calendar.getInstance();}}",
    "zombie/iso/IsoObject.java": "package zombie.iso; public class IsoObject {public void DirtySlice(){}public void flagForHotSave(){}}",
    "zombie/iso/IsoWorld.java": "package zombie.iso; public class IsoWorld {public static final IsoWorld instance=new IsoWorld();public Cell currentCell;public static class Cell {public void addToProcessItems(Object item){}}}",
    "zombie/iso/objects/IsoFeedingTrough.java": "package zombie.iso.objects; public class IsoFeedingTrough extends zombie.iso.IsoObject {public void onFoodAdded(){}}",
    "zombie/inventory/InventoryItem.java": """package zombie.inventory; public class InventoryItem {
public int id;public String name;public ItemContainer container;
public InventoryItem(int id,String name){this.id=id;this.name=name;}
public void OnAddedToContainer(ItemContainer container){}
} """,
}


def main():
    vanilla = (VERSION / "decompiled/zombie/inventory/ItemContainer.java").read_text()
    container = """package zombie.inventory;
import zombie.debug.DebugType;import zombie.iso.IsoWorld;import zombie.iso.objects.IsoFeedingTrough;import zombie.characters.IsoGameCharacter;
public class ItemContainer {
public final java.util.ArrayList<InventoryItem> items=new java.util.ArrayList<>();
public zombie.iso.IsoObject parent;public boolean drawDirty,dirty;
public zombie.iso.IsoObject getParent(){return parent;}
public void Remove(InventoryItem item){items.remove(item);}
""" + "\n".join(method(vanilla, signature) for signature in (
        "public InventoryItem AddItem(InventoryItem item)",
        "public boolean containsID(int id)", "public InventoryItem getItemWithID(int id)",
    )) + "\n}"
    sources = [SRC / (name + ".java") for name in (
        "ApocBRPayloadProvenance", "ApocBRPlayerRelationalLimits", "ApocBRPlayerRelationalSchema",
        "ApocBRPlayerRelationalAccess", "ApocBRPlayerRelationalSnapshot", "ApocBRPlayerRelationalCodecs",
        "ApocBRPlayerRelationalCapture", "ApocBRPlayerRelationalMutations", "ApocBRPlayerRelationalDelta",
        "ApocBRPlayerRelationalMetrics", "ApocBRPlayerRelationalDatabase",
    )]
    with tempfile.TemporaryDirectory(prefix="apocbr-duplicate-items-") as folder:
        work = Path(folder)
        files = {**STUBS, "zombie/inventory/ItemContainer.java": container,
                 "zombie/savefile/RelationalTest.java": fixture()}
        for relative, content in files.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        sources += inactive_persistence_stubs(work)
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output),
                        *map(str, sources)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output),
                        "zombie.savefile.RelationalTest"], check=True)


if __name__ == "__main__":
    main()
