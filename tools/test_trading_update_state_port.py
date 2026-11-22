"""Review43 state packet + actual NPC session/offer with recording engine ports.

Native manager state methods and reader/identity fields are used; journal, SQL,
Lua UI, item encoding, NPC composition and live runtime remain excluded.
"""
import sys

sys.dont_write_bytecode = True
import test_trading_add_item_port as add

DIFF = add.V / "docs/reports/2026-10-28-merge-43-trading-update-state.diff"
REL = "zombie/network/packets/TradingUIUpdateStatePacket.java"


def extend(files):
    manager = "zombie/core/TradingManager.java"
    source = (add.V / "decompiled" / manager).read_text(encoding="utf-8-sig")
    s = files[manager].replace("private final List<Trading>tradings=new ArrayList<>();", "private final List<Trading>tradings=new ArrayList<>();private final List<Trading>finalisingTradings=new ArrayList<>();")
    s = s.replace("instance.tradings.clear();", "instance.tradings.clear();instance.finalisingTradings.clear();")
    s = s.replace("IsoPlayer playerA,playerB;", "IsoPlayer playerA,playerB;boolean playerAAgreement,playerBAgreement;")
    s = s.replace("void resetAgreements(){}", "void resetAgreements(){playerAAgreement=false;playerBAgreement=false;}")
    s = s[:-1] + "public static int queued(){return instance.finalisingTradings.size();}" + "\n".join(add.method(source, signature) for signature in [
        "private boolean dealSealStatusChanged(", "private boolean cancelTrading(IsoPlayer",
        "private boolean finishTrading(", "public boolean processState(",
    ]) + "}"
    files[manager] = s
    files["zombie/core/network/ByteBufferReader.java"] = (add.V / "decompiled/zombie/core/network/ByteBufferReader.java").read_text()
    files["zombie/core/network/ByteBufferWriter.java"] = files["zombie/core/network/ByteBufferWriter.java"].rstrip()[:-1] + "public void putEnum(Enum<?> v){bb.put((byte)v.ordinal());}}"
    files["zombie/core/Core.java"] = files["zombie/core/Core.java"].replace("public static String", "public static boolean IS_DEV;public static String")
    files["zombie/inventory/ItemContainer.java"] = files["zombie/inventory/ItemContainer.java"].replace("public void Remove(InventoryItem i){", "public static boolean failRemove,removedBeforeQueue;public void Remove(InventoryItem i){if((i.type.equals(\"Base.Money\")||i.type.equals(\"Base.MoneyBundle\"))&&zombie.savefile.ApocBRHumanSales.sales.isEmpty())removedBeforeQueue=true;if(failRemove)throw new IllegalStateException(\"fixture remove failure\");")
    files["zombie/savefile/ServerPlayerDB.java"] = files["zombie/savefile/ServerPlayerDB.java"].replace("public static int saves;", "public static int saves;public static boolean fail;").replace("{saves++;}", "{if(fail)throw new IllegalStateException(\"fixture snapshot failure\");saves++;}")
    files["zombie/debug/DebugLog.java"] = files["zombie/debug/DebugLog.java"].replace("public static void log(String s){}", "public static java.util.List<String>lines=new java.util.ArrayList<>();public static void log(String s){lines.add(s);}")


TEST = r'''
 static void fresh(){reset();ItemContainer.failRemove=false;ItemContainer.removedBeforeQueue=false;ServerPlayerDB.fail=false;zombie.debug.DebugLog.lines.clear();}
 static TradingUIUpdateStatePacket state(int slot,int target,TradingState state,UdpConnection c){var p=new TradingUIUpdateStatePacket();var b=ByteBuffer.allocate(5).putShort((short)slot).putShort((short)target).put((byte)state.ordinal()).flip();p.parse(new ByteBufferReader(b),c);check(b.position()==5);if(p.isConsistent(c))p.processServer(PacketTypes.PacketType.TradingUIUpdateState,c);return p;}
 static void offer(int id){item(id);add(new TradingUIAddItemPacket(),2,768,id,connection);}
 public static void main(String[]args){
  // Money/bundle/other classification uses the real adapter, with recording receipt ports.
  fresh();open();offer(10);var bundle=new InventoryItem(11,"Base.MoneyBundle");visitor.inventory.add(bundle);add(new TradingUIAddItemPacket(),2,768,11,connection);var otherItem=new InventoryItem(12,"Base.Other");visitor.inventory.add(otherItem);add(new TradingUIAddItemPacket(),2,768,12,connection);state(2,768,TradingState.DealWasSealed,connection);check(last().values()[2]==TradingState.DealWasSealed);state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.size()==1&&visitor.inventory.items.isEmpty());check(ApocBRHumanSales.sales.getFirst().money().equals(List.of(10,11)));check(ApocBRHumanSales.sales.getFirst().bundles().equals(List.of(11)));check(!ItemContainer.removedBeforeQueue&&ServerPlayerDB.saves==1);check(last().values()[2]==TradingState.DealWasFinalized&&human.activity.finishes==1);state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.size()==1&&ServerPlayerDB.saves==1&&human.activity.finishes==1);check(TradingManager.pairs()==0&&TradingManager.queued()==0);
  // Close cancels exactly once, without inventory or persistence effects.
  fresh();open();offer(13);state(2,768,TradingState.WindowWasClosed,connection);check(human.activity.finishes==1&&visitor.inventory.items.size()==1&&ApocBRHumanSales.sales.isEmpty());state(2,768,TradingState.WindowWasClosed,connection);check(human.activity.finishes==1);
  // Missing consent and edits/unseal cannot finalize or consume possessions.
  for(int mode=0;mode<4;mode++){fresh();open();offer(14);if(mode>0)state(2,768,TradingState.DealWasSealed,connection);if(mode==1)state(2,768,TradingState.DealWasUnsealed,connection);if(mode==2)offer(15);if(mode==3){ServerHumanTrading.removeItem(visitor,human.player,connection,14);state(2,768,TradingState.DealWasSealed,connection);}state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.isEmpty());check(visitor.inventory.items.size()==(mode==2?2:1));}
  fresh();open();state(2,768,TradingState.DealWasSealed,connection);check(human.activity.finishes==1&&ApocBRHumanSales.sales.isEmpty());
  // Journal rejection happens before any item removal, and terminates this offer.
  fresh();open();offer(16);state(2,768,TradingState.DealWasSealed,connection);ApocBRHumanSales.fail=true;state(2,768,TradingState.DealWasFinalized,connection);check(visitor.inventory.items.size()==1&&ApocBRHumanSales.sales.isEmpty());check(!ItemContainer.removedBeforeQueue&&ServerPlayerDB.saves==0&&human.activity.finishes==1);check(zombie.debug.DebugLog.lines.stream().anyMatch(s->s.contains("OFFER_NOT_CONSUMED")));ApocBRHumanSales.fail=false;state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.isEmpty());
  // Existing post-handoff failure policy requires review; no automatic second sale.
  for(boolean snapshot:new boolean[]{false,true}){fresh();open();offer(17);state(2,768,TradingState.DealWasSealed,connection);ItemContainer.failRemove=!snapshot;ServerPlayerDB.fail=snapshot;state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.size()==1&&!ItemContainer.removedBeforeQueue);check(visitor.inventory.items.size()==(snapshot?0:1));check(last().values()[2]==TradingState.WindowWasClosed&&human.activity.finishes==1);check(zombie.debug.DebugLog.lines.stream().anyMatch(s->s.contains("CONSUMPTION_REQUIRES_REVIEW")));ItemContainer.failRemove=false;ServerPlayerDB.fail=false;state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.size()==1);}
  // Missing/moved item evidence or unavailable persistence never becomes a sale.
  for(int mode=0;mode<3;mode++){fresh();open();offer(18);state(2,768,TradingState.DealWasSealed,connection);if(mode==0)visitor.inventory.items.clear();if(mode==1)ApocBRHumanSales.available=false;if(mode==2)ServerPlayerDB.available=false;state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.isEmpty()&&human.activity.finishes==1);}
  for(int bad:new int[]{-1,0,1,3,4,50,768,1024}){fresh();open();offer(19);state(2,768,TradingState.DealWasSealed,connection);var p=state(bad,768,TradingState.DealWasFinalized,connection);check(!p.isConsistent(connection));check(visitor.inventory.items.size()==1&&ApocBRHumanSales.sales.isEmpty()&&human.activity.finishes==0);}
  fresh();open();offer(20);state(2,768,TradingState.DealWasSealed,connection);state(1,768,TradingState.DealWasFinalized,other);check(ApocBRHumanSales.sales.isEmpty()&&human.activity.finishes==0);connection.claims=false;state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.isEmpty());connection.claims=true;state(2,768,TradingState.DealWasFinalized,connection);check(ApocBRHumanSales.sales.size()==1);
  fresh();open();var invalid=state(2,769,TradingState.WindowWasClosed,connection);check(!invalid.isConsistent(connection)&&human.activity.finishes==0);
  // Ordinary close clears the actual native pending request even without a trade pair.
  fresh();dispatch(new RequestTradingPacket(),2,60,connection,PacketTypes.PacketType.RequestTrading);state(2,60,TradingState.WindowWasClosed,connection);check(!TradingRequests.getInstance().response(visitor,peer));check(TradingManager.pairs()==0);
  fresh();state(2,60,TradingState.DealWasFinalized,connection);check(TradingManager.queued()==0&&INetworkPacket.SENT.isEmpty());dispatch(new RequestTradingPacket(),2,60,connection,PacketTypes.PacketType.RequestTrading);dispatch(new AcceptTradingPacket(),1,50,other,PacketTypes.PacketType.AcceptTrading);state(2,60,TradingState.DealWasSealed,connection);state(2,60,TradingState.DealWasFinalized,connection);check(TradingManager.queued()==0);state(1,50,TradingState.DealWasSealed,other);state(2,60,TradingState.DealWasFinalized,connection);check(TradingManager.queued()==1&&TradingManager.pairs()==0);check(last().type()==PacketTypes.PacketType.TradingUIUpdateState&&last().connection()==other);
  fresh();dispatch(new RequestTradingPacket(),2,60,connection,PacketTypes.PacketType.RequestTrading);dispatch(new AcceptTradingPacket(),1,50,other,PacketTypes.PacketType.AcceptTrading);var stranger=player(70);GameServer.IDToPlayerMap.put(stranger.id,stranger);int sent=INetworkPacket.SENT.size();state(2,70,TradingState.WindowWasClosed,connection);check(TradingManager.pairs()==1&&INetworkPacket.SENT.size()==sent);state(2,60,TradingState.WindowWasClosed,connection);check(TradingManager.pairs()==0);
  // Native state enum wire width and event argument order, for every native state.
  for(var value:TradingState.values()){fresh();GameServer.server=false;GameClient.client=true;visitor.index=2;var p=new TradingUIUpdateStatePacket();p.setData(visitor,peer,value);var b=ByteBuffer.allocate(5);p.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==2&&b.getShort()==60&&b.get()==(byte)value.ordinal());GameServer.server=true;GameClient.client=false;p.setData(human.player,visitor,value);b.clear();p.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==768&&b.getShort()==50&&b.get()==(byte)value.ordinal());b.position(0);GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(human.player.id,human.player);GameClient.IDToPlayerMap.put(visitor.id,visitor);var received=new TradingUIUpdateStatePacket();received.parse(new ByteBufferReader(b),null);check(received.isConsistent(null));received.processClient(null);check(zombie.Lua.LuaEventManager.event.equals("TradingUIUpdateState"));check(zombie.Lua.LuaEventManager.args[0]==human.player&&zombie.Lua.LuaEventManager.args[1]==visitor&&zombie.Lua.LuaEventManager.args[2]==value);}
  System.out.println("Trading state candidate: "+checks+" assertions passed (native fields/state methods, actual NPC adapter, recording persistence doubles)");
 }
'''


if __name__ == "__main__":
    prefix = add.TEST.split(" public static void main(String[]args){")[0]
    add.main(extra_diff=DIFF, extra_relative=REL, harness=prefix + TEST, transform_files=extend)
