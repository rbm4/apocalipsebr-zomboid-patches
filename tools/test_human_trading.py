"""Run the production trade adapter against inventory/network/persistence ports."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS = r"""
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.trading.*;
import zombie.characters.*;
import zombie.core.*;
import zombie.core.raknet.*;
import zombie.inventory.*;
import zombie.iso.*;
import zombie.network.*;
import zombie.network.fields.character.*;
import zombie.network.packets.*;
import zombie.savefile.*;
public class TradingTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static IsoPlayer visitor(short id, UdpConnection connection) {
  IsoPlayer player=new IsoPlayer(IsoWorld.instance.currentCell,new SurvivorDesc(false),0,0,0);
  player.setOnlineID(id);player.instanceId=id;player.username="visitante"+id;player.x=101;player.y=200;
  GameServer.IDToPlayerMap.put(id,player);GameServer.Players.add(player);GameServer.owners.put(player,connection);
  connection.players[0]=player;GameServer.udpEngine.connections.add(connection);return player;
 }
 static InventoryItem item(IsoPlayer visitor,int id,String type){var item=new InventoryItem(id,type);visitor.inventory.AddItem(item);return item;}
 static boolean open(IsoPlayer visitor,ServerHuman npc,UdpConnection connection){return ServerHumanTrading.request(visitor,npc.player,connection,true);}
 static void seal(IsoPlayer visitor,ServerHuman npc,UdpConnection connection){ServerHumanTrading.state(visitor,npc.player,connection,TradingState.DealWasSealed);}
 static void accept(IsoPlayer visitor,ServerHuman npc,UdpConnection connection){ServerHumanTrading.state(visitor,npc.player,connection,TradingState.DealWasFinalized);}
 public static void main(String[] args) {
  System.clearProperty("apocbr.guide.enabled");
  ServerHumanFactory factory=ServerHumanFactory.getInstance();factory.tick(System.nanoTime());
  short id=factory.spawn(new ServerHumanDefinition("guide","Guia","Guia",100,200,0,IsoDirections.E,false,"Tourist"));
  ServerHuman npc=ServerHumanFactory.tradingHuman(id);
  UdpConnection connection=new UdpConnection(),other=new UdpConnection(),attacker=new UdpConnection();
  IsoPlayer visitor=visitor((short)1,connection),second=visitor((short)2,other);
  PlayerID target=new PlayerID();target.id=id;ServerHumanTrading.resolveTarget(target);
  check(target.player==npc.player,"trading target resolves outside normal player map");
  check(!GameServer.IDToPlayerMap.containsKey(id),"NPC remains outside player/account ownership maps");
  PlayerID spoof=new PlayerID();spoof.id=id;spoof.index=0;ServerHumanTrading.resolveTarget(spoof);
  check(spoof.player==null,"local index cannot masquerade as remote NPC");
  check(ServerHumanTrading.request(visitor,second,connection,true)==false,"ordinary player trades fall through");
  open(visitor,npc,attacker);check((npc.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)npc.activity.behavior()).selectedModule()==null),"foreign connection cannot open offer");
  check(open(visitor,npc,connection)&&npc.activity.mode()==HumanActivityController.Mode.TRADE,"native open pauses brain");
  npc.activity.update(new HumanObservation(System.nanoTime(),List.of()),npc.actuator);
  check(npc.activity.mode()==HumanActivityController.Mode.TRADE&&!npc.movement.moving(),"trade action keeps attention without roaming");
  open(second,npc,other);check(!RequestTradingPacket.replies.getLast().accepted(),"one visitor per guide");
  InventoryItem first=item(visitor,11,"Base.Money"),next=item(visitor,12,"Base.Money");
  ServerHumanTrading.addItem(visitor,npc.player,connection,first);seal(visitor,npc,connection);
  ServerHumanTrading.addItem(visitor,npc.player,connection,next);accept(visitor,npc,connection);
  check(visitor.inventory.items.size()==2&&ApocBRHumanSales.sales.isEmpty(),"changing sealed offer invalidates acceptance");
  open(visitor,npc,connection);
  ServerHumanTrading.addItem(visitor,npc.player,connection,first);ServerHumanTrading.addItem(visitor,npc.player,connection,next);
  open(visitor,npc,connection); // retransmitted request must preserve offer
  seal(visitor,npc,connection);accept(visitor,npc,attacker);
  check(visitor.inventory.items.size()==2&&ApocBRHumanSales.sales.isEmpty(),"foreign connection cannot finalize");
  accept(visitor,npc,connection);
  check(visitor.inventory.items.isEmpty()&&ApocBRHumanSales.sales.size()==1,"accepted exact money offer removed and journaled");
  var sale=ApocBRHumanSales.sales.getFirst();
  check(sale.itemIds().equals(List.of(11,12))&&sale.username().equals(visitor.username)&&sale.playerIndex()==0,"receipt records validated server item IDs and player identity");
  check(sale.steamId().equals("76561198000000000")&&sale.npcKey().equals("guide"),"receipt includes Steam and NPC identity");
  check(ServerPlayerDB.saves==1,"authoritative post-sale inventory captured");
  check(INetworkPacket.messages.stream().anyMatch(m->m.type()==PacketTypes.PacketType.RemoveInventoryItemFromContainer),"native inventory removal sent to owner");
  accept(visitor,npc,connection);check(ApocBRHumanSales.sales.size()==1,"duplicate finalize cannot sell twice");
  check((npc.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)npc.activity.behavior()).selectedModule()==null),"sale returns brain to ordinary lifecycle");
  InventoryItem valuable=item(visitor,13,"Base.Diamond");open(visitor,npc,connection);
  ServerHumanTrading.addItem(visitor,npc.player,connection,valuable);seal(visitor,npc,connection);accept(visitor,npc,connection);
  check(!visitor.inventory.items.contains(valuable)&&ApocBRHumanSales.sales.size()==1,"non-money offer destroyed without creating money receipt");
  first=item(visitor,20,"Base.Money");valuable=item(visitor,21,"Base.Diamond");
  open(visitor,npc,connection);ServerHumanTrading.addItem(visitor,npc.player,connection,first);
  ServerHumanTrading.addItem(visitor,npc.player,connection,valuable);seal(visitor,npc,connection);accept(visitor,npc,connection);
  check(!visitor.inventory.items.contains(first)&&!visitor.inventory.items.contains(valuable),"mixed offer destroys all confirmed items");
  check(ApocBRHumanSales.sales.size()==2&&ApocBRHumanSales.sales.getLast().itemIds().equals(List.of(20)),"mixed offer registers only exact Base.Money instances");
  first=item(visitor,14,"Base.Money");open(visitor,npc,connection);ServerHumanTrading.addItem(visitor,npc.player,connection,first);
  seal(visitor,npc,connection);visitor.inventory.Remove(first);accept(visitor,npc,connection);
  check(ApocBRHumanSales.sales.size()==2,"item ownership revalidated at finalization");
  first=item(visitor,15,"Base.Money");open(visitor,npc,connection);ServerHumanTrading.addItem(visitor,npc.player,connection,first);seal(visitor,npc,connection);
  ApocBRHumanSales.fail=true;accept(visitor,npc,connection);ApocBRHumanSales.fail=false;
  check(visitor.inventory.items.contains(first)&&ApocBRHumanSales.sales.size()==2,"failed journal leaves inventory untouched");
  open(visitor,npc,connection);ServerHumanTrading.addItem(visitor,npc.player,connection,first);seal(visitor,npc,connection);
  visitor.x=200;accept(visitor,npc,connection);visitor.x=101;
  check(visitor.inventory.items.contains(first)&&ApocBRHumanSales.sales.size()==2,"out of range finalization cancels safely");
  open(visitor,npc,connection);connection.connected=false;ServerHumanTrading.update(npc,System.nanoTime());connection.connected=true;
  check((npc.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)npc.activity.behavior()).selectedModule()==null),"disconnect releases guide");
  open(visitor,npc,connection);ServerHumanTrading.update(npc,System.nanoTime()+181_000_000_000L);
  check((npc.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)npc.activity.behavior()).selectedModule()==null),"inactive trade times out");
  ApocBRHumanSales.enabled=false;open(visitor,npc,connection);ApocBRHumanSales.enabled=true;
  check(!RequestTradingPacket.replies.getLast().accepted(),"unavailable persistence rejects before acceptance");
  var bundle=item(visitor,30,"Base.MoneyBundle");var note=item(visitor,31,"Base.Money");
  var ignored=item(visitor,32,"Other.MoneyBundle");
  open(visitor,npc,connection);ServerHumanTrading.addItem(visitor,npc.player,connection,bundle);
  ServerHumanTrading.addItem(visitor,npc.player,connection,note);ServerHumanTrading.addItem(visitor,npc.player,connection,ignored);
  seal(visitor,npc,connection);accept(visitor,npc,connection);
  var mixed=ApocBRHumanSales.sales.getLast();
  check(mixed.itemIds().equals(List.of(30,31))&&mixed.bundleItemIds().equals(List.of(30))&&mixed.quantity()==101,"mixed currency values bundle as 100 notes with exact IDs");
  check(!visitor.inventory.items.contains(bundle)&&!visitor.inventory.items.contains(note)&&!visitor.inventory.items.contains(ignored),"mixed bundle sale consumes only confirmed inventory once");
  int receipts=ApocBRHumanSales.sales.size();accept(visitor,npc,connection);
  check(ApocBRHumanSales.sales.size()==receipts,"bundle finalize replay cannot credit twice");
  bundle=item(visitor,33,"Base.MoneyBundle");open(visitor,npc,connection);
  ServerHumanTrading.addItem(visitor,npc.player,connection,bundle);seal(visitor,npc,connection);
  ApocBRHumanSales.fail=true;accept(visitor,npc,connection);ApocBRHumanSales.fail=false;
  check(visitor.inventory.items.contains(bundle)&&ApocBRHumanSales.sales.size()==receipts,"bundle journal failure preserves inventory");
  open(visitor,npc,connection);ServerHumanTrading.addItem(visitor,npc.player,connection,bundle);seal(visitor,npc,connection);accept(visitor,npc,connection);
  check(ApocBRHumanSales.sales.getLast().quantity()==100&&ApocBRHumanSales.sales.getLast().bundleItemIds().equals(List.of(33)),"bundle-only offer receives 100-note value");
  open(visitor,npc,connection);factory.remove("guide");
  check(npc.activity.mode()==HumanActivityController.Mode.STOPPED,"actor removal closes trading and brain");
  HumanTradeOffer offer=new HumanTradeOffer();
  check(!offer.seal()&&!offer.ready(),"empty offer cannot be sealed");
  check(offer.add(1)&&offer.seal()&&offer.ready(),"nonempty offer can be sealed");
  check(!offer.add(1)&&!offer.ready(),"duplicate item cannot count twice or retain seal");
  offer.remove(1);check(offer.items().isEmpty(),"removing offered item changes immutable snapshot");
  for(int i=0;i<HumanTradeOffer.LIMIT;i++)check(offer.add(i),"bounded valid offer accepted");
  check(!offer.add(9999),"item budget enforced");offer.close();check(!offer.add(9999)&&!offer.ready(),"closed offer is terminal");
  System.out.println("Passed "+checks+" trade protocol, ownership, failure and offer assertions.");
 }
}
"""


def main():
    # NPC hooks must leave the ordinary trade parser/handlers byte-for-byte intact.
    hooks = {
        "network/fields/Trading.java": "        zombie.apocalipse.humans.ServerHumanTrading.resolveTarget(this.playerB);\n",
        "network/packets/RequestTradingPacket.java": (
            "        zombie.apocalipse.humans.ServerHumanTrading.resolveTarget(this.playerB);\n",
            "        if (zombie.apocalipse.humans.ServerHumanTrading.request(this.playerA.getPlayer(), this.playerB.getPlayer(),\n"
            "            connection, this.type == RequestType.Ask)) return;\n",
        ),
        "network/packets/TradingUIAddItemPacket.java": "        if (zombie.apocalipse.humans.ServerHumanTrading.addItem(this.playerA.getPlayer(), this.playerB.getPlayer(), connection, this.item)) return;\n",
        "network/packets/TradingUIRemoveItemPacket.java": "        if (zombie.apocalipse.humans.ServerHumanTrading.removeItem(this.playerA.getPlayer(), this.playerB.getPlayer(), connection, this.itemId)) return;\n",
        "network/packets/TradingUIUpdateStatePacket.java": "        if (zombie.apocalipse.humans.ServerHumanTrading.state(this.playerA.getPlayer(), this.playerB.getPlayer(), connection, this.state)) return;\n",
    }
    for relative, additions in hooks.items():
        patched = (ROOT / "42.21.0/src/zombie" / relative).read_text(encoding="utf-8")
        vanilla = (ROOT / "42.21.0/decompiled/zombie" / relative).read_text(encoding="utf-8")
        for hook in (additions if isinstance(additions, tuple) else (additions,)):
            assert patched.count(hook) == 1, relative
            patched = patched.replace(hook, "")
        assert patched == vanilla, relative
    with tempfile.TemporaryDirectory(prefix="apocbr-trade-tests-") as directory:
        work = Path(directory)
        sources = []
        fixture = dict(FILES)
        fixture["zombie/apocalipse/humans/TradingTest.java"] = HARNESS
        for relative, content in fixture.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.TradingTest"], check=True)


if __name__ == "__main__":
    main()
