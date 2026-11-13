"""Review42 candidate with native identity/pair/remove methods and actual NPC offer.

Uses the review41 fixture ports; no live journal/SQL, Lua UI, real item encoding,
complete NPC/world runtime or game JAR is exercised.
"""
import sys

sys.dont_write_bytecode = True
import test_trading_add_item_port as add

DIFF = add.V / "docs/reports/2026-10-28-merge-42-trading-remove-item.diff"

TEST = r'''
 static TradingUIRemoveItemPacket remove(TradingUIRemoveItemPacket packet,int slot,int target,int item,UdpConnection c){var b=ByteBuffer.allocate(8).putShort((short)slot).putShort((short)target).putInt(item).flip();packet.parse(new ByteBufferReader(b),c);check(b.position()==8);if(packet.isConsistent(c))packet.processServer(PacketTypes.PacketType.TradingUIRemoveItem,c);return packet;}
 public static void main(String[]args){
  // Remove only from the offer. A later authorized sale consumes the remaining item.
  reset();open();item(11);item(12);add(new TradingUIAddItemPacket(),2,768,11,connection);add(new TradingUIAddItemPacket(),2,768,12,connection);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);var packet=remove(new TradingUIRemoveItemPacket(),2,768,11,connection);check(packet.isConsistent(connection));check(visitor.inventory.items.size()==2&&ApocBRHumanSales.sales.isEmpty());check(TradingManager.pairs()==0&&TradingManager.items()==0);check(last().type()==PacketTypes.PacketType.TradingUIUpdateState&&last().values()[2]==TradingState.DealWasUnsealed);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(ApocBRHumanSales.sales.size()==1&&ApocBRHumanSales.sales.getFirst().money().equals(List.of(12)));check(visitor.inventory.getItemWithID(11)!=null&&visitor.inventory.getItemWithID(12)==null);
  // Absent, duplicate and negative removals keep the conservative consent rule.
  for(int id:new int[]{999,-1,11}){reset();open();item(11);item(12);add(new TradingUIAddItemPacket(),2,768,11,connection);add(new TradingUIAddItemPacket(),2,768,12,connection);if(id==11)remove(new TradingUIRemoveItemPacket(),2,768,11,connection);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);remove(new TradingUIRemoveItemPacket(),2,768,id,connection);check(visitor.inventory.items.size()==2);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(ApocBRHumanSales.sales.isEmpty()&&visitor.inventory.items.size()==2);}
  // The last offered item can be withdrawn without being deleted from inventory.
  reset();open();item(13);add(new TradingUIAddItemPacket(),2,768,13,connection);remove(new TradingUIRemoveItemPacket(),2,768,13,connection);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);check(human.activity.finishes==1&&visitor.inventory.items.size()==1&&ApocBRHumanSales.sales.isEmpty());
  reset();item(14);packet=remove(new TradingUIRemoveItemPacket(),2,768,14,connection);check(packet.isConsistent(connection)&&INetworkPacket.SENT.isEmpty());check(TradingManager.pairs()==0);
  // Each sender slot addresses its own actor; global online IDs cannot be used as slots.
  for(int slot=0;slot<4;slot++){reset();connection.players=new IsoPlayer[4];connection.players[slot]=visitor;dispatch(new RequestTradingPacket(),slot,768,connection,PacketTypes.PacketType.RequestTrading);item(15);add(new TradingUIAddItemPacket(),slot,768,15,connection);packet=remove(new TradingUIRemoveItemPacket(),slot,768,15,connection);check(packet.isConsistent(connection)&&human.activity.finishes==0);check(visitor.inventory.items.size()==1);}
  for(int bad:new int[]{-1,0,1,3,4,50,768,1024}){reset();open();item(16);packet=remove(new TradingUIRemoveItemPacket(),bad,768,16,connection);check(!packet.isConsistent(connection));check(human.activity.finishes==0&&visitor.inventory.items.size()==1);}
  reset();open();packet=remove(new TradingUIRemoveItemPacket(),2,769,1,connection);check(!packet.isConsistent(connection)&&human.activity.finishes==0);
  // Foreign session visitors cannot remove our offer; their packet is consumed.
  reset();open();item(17);add(new TradingUIAddItemPacket(),2,768,17,connection);remove(new TradingUIRemoveItemPacket(),1,768,17,other);check(human.activity.finishes==0&&visitor.inventory.items.size()==1);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(ApocBRHumanSales.sales.size()==1);
  reset();open();item(18);add(new TradingUIAddItemPacket(),2,768,18,connection);connection.claims=false;remove(new TradingUIRemoveItemPacket(),2,768,18,connection);connection.claims=true;ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(ApocBRHumanSales.sales.size()==1);
  reset();open();item(19);visitor.x=10;remove(new TradingUIRemoveItemPacket(),2,768,19,connection);check(human.activity.finishes==1&&visitor.inventory.items.size()==1);
  // Unpaired real players and forged destinations fail the native exact-pair check.
  reset();item(20);packet=remove(new TradingUIRemoveItemPacket(),2,60,20,connection);check(packet.isConsistent(connection)&&INetworkPacket.SENT.isEmpty());dispatch(new RequestTradingPacket(),2,60,connection,PacketTypes.PacketType.RequestTrading);dispatch(new AcceptTradingPacket(),1,50,other,PacketTypes.PacketType.AcceptTrading);TradingManager.getInstance().addItem(visitor,visitor.inventory.getItemWithID(20));var theirs=new InventoryItem(21,"Base.Money");peer.inventory.add(theirs);TradingManager.getInstance().addItem(peer,theirs);check(TradingManager.items()==2);remove(packet,2,60,21,connection);check(TradingManager.items()==2);var stranger=player(70);GameServer.IDToPlayerMap.put(stranger.id,stranger);int sent=INetworkPacket.SENT.size();remove(packet,2,70,20,connection);check(TradingManager.items()==2&&INetworkPacket.SENT.size()==sent);remove(packet,2,60,20,connection);check(TradingManager.items()==1&&last().type()==PacketTypes.PacketType.TradingUIRemoveItem&&last().connection()==other);check(visitor.inventory.getItemWithID(20)!=null&&peer.inventory.getItemWithID(21)!=null);
  // The native body is always sender/destination shorts plus one int, in both directions.
  reset();GameServer.server=false;GameClient.client=true;visitor.index=2;packet=new TradingUIRemoveItemPacket();packet.setData(visitor,peer,123456);var b=ByteBuffer.allocate(8);packet.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==2&&b.getShort()==60&&b.getInt()==123456&&b.remaining()==0);GameServer.server=true;GameClient.client=false;packet.parse(new ByteBufferReader(ByteBuffer.allocate(8).putShort((short)2).putShort((short)60).putInt(-1).flip()),connection);b=ByteBuffer.allocate(8);packet.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==50&&b.getShort()==60&&b.getInt()==-1);b.position(0);GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(visitor.id,visitor);GameClient.IDToPlayerMap.put(peer.id,peer);var received=new TradingUIRemoveItemPacket();received.parse(new ByteBufferReader(b),null);check(received.isConsistent(null));received.processClient(null);check(zombie.Lua.LuaEventManager.event.equals("TradingUIRemoveItem"));check(zombie.Lua.LuaEventManager.args[0]==visitor&&zombie.Lua.LuaEventManager.args[1]==peer&&zombie.Lua.LuaEventManager.args[2].equals(-1));
  System.out.println("Trading remove-item candidate: "+checks+" assertions passed (native fields/pair methods, actual NPC offer adapter, engine doubles)");
 }
'''


if __name__ == "__main__":
    prefix = add.TEST.split(" public static void main(String[]args){")[0]
    add.main(extra_diff=DIFF, harness=prefix + TEST)
