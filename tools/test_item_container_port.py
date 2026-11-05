"""42.22.0 extracted container methods and production sparse-update helpers.

Native items/world/telemetry are doubles. Fluid helper integration and full
ItemContainer linkage remain pending the InventoryItem and character ports.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_packet_algorithms import HARNESS as OLD_HARNESS, extract_method

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.0"


def fixtures():
    source = (VERSION / "src/zombie/inventory/ItemContainer.java").read_text(encoding="utf-8")
    signatures = (
        "public void emptyIt()", "public float getContentsWeight()",
        "public boolean isInside(InventoryItem item)", "public ItemContainer getOutermostContainer()",
        "public static boolean chainContainsContainingItem(ItemContainer start, int itemId)",
        "public boolean containsRecursive(InventoryItem item)",
        "private boolean containsRecursive(InventoryItem item, int depth, Set<ItemContainer> visited)",
        "public int getItemCountFromTypeRecurse(String type)",
        "private int getItemCountFromTypeRecurse(String type, int depth, Set<ItemContainer> visited)",
        "public InventoryItem getItemWithIDRecursiv(int id)",
        "private InventoryItem getItemWithIDRecursive(int id, int depth, Set<ItemContainer> visited)",
        "public boolean removeItemWithIDRecurse(int id)",
        "private boolean removeItemWithIDRecurse(int id, int depth, Set<ItemContainer> visited)",
    )
    field = "public ArrayList<InventoryItem> items = new ServerInventoryUpdateIndex.Items();"
    assert field in source
    container = """package zombie.inventory;
      import java.util.*;import zombie.inventory.types.InventoryContainer;import zombie.network.GameServer;
      public class ItemContainer {public int id;public InventoryItem containingItem;
    """ + field + """
      public ArrayList<InventoryItem> getItems(){return items;}
      public InventoryItem getContainingItem(){return containingItem;}
      public void Remove(InventoryItem item){items.remove(item);}
    """ + "\n".join(extract_method(source, s) for s in signatures) + "}"
    return {
        "zombie/inventory/ItemContainer.java": container,
        "zombie/inventory/InventoryItem.java": """package zombie.inventory;
          public class InventoryItem {public int id;public String type="item";public float weight;
           public RuntimeException failure;public ItemContainer container;
           public int getID(){return id;}public String getFullType(){return type;}
           public ItemContainer getContainer(){return container;}public void update(){}
           public float getUnequippedWeight(){if(failure!=null)throw failure;return weight;}}
        """,
        "zombie/inventory/types/InventoryContainer.java": """package zombie.inventory.types;
          public class InventoryContainer extends zombie.inventory.InventoryItem {
           public zombie.inventory.ItemContainer inventory=new zombie.inventory.ItemContainer();
           public zombie.inventory.ItemContainer getInventory(){return inventory;}
           public zombie.inventory.ItemContainer getItemContainer(){return inventory;}}
        """,
        "zombie/network/GameServer.java": "package zombie.network;public class GameServer {public static boolean server=true;}",
        "zombie/interfaces/IUpdater.java": "package zombie.interfaces;public interface IUpdater {}",
        "zombie/ApocBRServerTelemetryLite.java": """package zombie;
          public class ApocBRServerTelemetryLite {public static final java.util.Map<String,Long> counts=new java.util.HashMap<>();
           public static void count(String name,long value){counts.merge(name,value,Long::sum);}
           public static long count(String name){return counts.getOrDefault(name,0L);}
           public static void recordPhase(String name,long value){}}
        """,
    }


EXTRA = """
 static void containerMethods(){
  ItemContainer root=new ItemContainer();var old=root.items;root.items.add(new InventoryItem());root.emptyIt();
  check(root.items!=old&&root.items.isEmpty()&&root.items instanceof ServerInventoryUpdateIndex.Items,"emptyIt rebuilds tracked list");
  float sum=0;for(float weight:new float[]{.1f,.2f,100000f,-99999f}){InventoryItem item=new InventoryItem();item.weight=weight;root.items.add(item);sum+=weight;}
  check(Float.floatToIntBits(root.getContentsWeight())==Float.floatToIntBits(sum),"weight accumulation bits/order");
  root.emptyIt();InventoryItem broken=new InventoryItem();broken.failure=new IllegalStateException("sentinel");root.items.add(broken);
  try{root.getContentsWeight();throw new AssertionError("missing weight failure");}catch(IllegalStateException expected){check(expected==broken.failure,"weight failure unchanged");}
  root.emptyIt();InventoryContainer bag=new InventoryContainer();bag.id=20;bag.container=root;bag.inventory.containingItem=bag;
  root.items.add(bag);InventoryItem needle=new InventoryItem();needle.id=30;needle.type="needle";bag.inventory.items.add(needle);
  check(root.containsRecursive(needle)&&root.getItemWithIDRecursiv(30)==needle,"nested lookup");
  check(root.getItemCountFromTypeRecurse("needle")==1,"nested count");check(bag.inventory.isInside(bag),"containing item match");
  check(bag.inventory.getOutermostContainer()==root,"outermost parent");
  check(ItemContainer.chainContainsContainingItem(bag.inventory,20),"ancestor insertion rejection predicate");
  check(!ItemContainer.chainContainsContainingItem(bag.inventory,-1),"unassigned ID sentinel");
  InventoryContainer cycle=new InventoryContainer();cycle.inventory=root;bag.inventory.items.add(cycle);
  check(!root.containsRecursive(new InventoryItem())&&root.getItemWithIDRecursiv(404)==null,"cyclic lookup terminates");
  check(root.getItemCountFromTypeRecurse("needle")==1,"cyclic count visits once");
  check(!root.removeItemWithIDRecurse(404),"cyclic absent removal terminates");
  check(root.removeItemWithIDRecurse(30)&&!root.containsRecursive(needle),"nested removal");
  check(!ItemContainer.chainContainsContainingItem(bag.inventory,404),"missing ancestor");
  root.containingItem=cycle;cycle.container=bag.inventory;
  check(!ItemContainer.chainContainsContainingItem(root,404),"cyclic parent-chain lookup terminates");
  check(root.getOutermostContainer()!=null&&!root.isInside(new InventoryItem()),"bounded cyclic parent walks terminate");
  root=new ItemContainer();ItemContainer current=root,at16=null;
  for(int depth=1;depth<=17;depth++){InventoryContainer next=new InventoryContainer();next.id=depth;current.items.add(next);current=next.inventory;if(depth==16)at16=current;}
  needle=new InventoryItem();needle.id=900;needle.type="needle";current.items.add(needle);
  check(!root.containsRecursive(needle)&&root.getItemWithIDRecursiv(900)==null,"depth 17 excluded");
  check(root.getItemCountFromTypeRecurse("needle")==0&&!root.removeItemWithIDRecurse(900),"bounded count/removal");
  at16.items.add(needle);check(root.containsRecursive(needle)&&root.getItemWithIDRecursiv(900)==needle,"depth 16 included");
  check(root.getItemCountFromTypeRecurse("needle")==1,"depth 16 counted without traversing depth 17");
 }
 public static void main(String[] args){inventory();containerMethods();System.out.println("PASS: "+assertions+" target inventory/container assertions");}
}
"""


def main():
    harness = """import java.util.*;import zombie.*;import zombie.inventory.*;
      import zombie.inventory.types.*;import zombie.interfaces.*;import zombie.util.list.*;
      public class InventoryPortTest {static int assertions;static List<String> log=new ArrayList<>();
       static void check(boolean b,String why){assertions++;if(!b)throw new AssertionError(why);}
    """ + extract_method(OLD_HARNESS, "static class Updating") + extract_method(OLD_HARNESS, "static void inventory()") + EXTRA
    with tempfile.TemporaryDirectory(prefix="apocbr-inventory-port-") as directory:
        work = Path(directory)
        sources = []
        for name, content in {**fixtures(), "InventoryPortTest.java": harness}.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources += [VERSION / "src/zombie" / name for name in
                    ("inventory/ServerInventoryUpdateIndex.java", "util/list/MutationTrackedArrayList.java")]
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@"+str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-ea", "-cp", str(work / "classes"), "InventoryPortTest"], check=True)


if __name__ == "__main__":
    main()
