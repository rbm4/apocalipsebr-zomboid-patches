package zombie.apocalipse.humans;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import zombie.apocalipse.humans.brain.HumanObservation;
import zombie.apocalipse.humans.trading.HumanTradeOffer;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.TradingState;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.RequestTradingPacket;
import zombie.savefile.ApocBRHumanSales;
import zombie.savefile.ApocBRPayloadProvenance;
import zombie.savefile.ServerPlayerDB;

/** Native trade protocol adapter, owned exclusively by the server simulation thread.
 * NPCs never enter TradingManager, player ownership maps, or the account database. */
public final class ServerHumanTrading {
    private static final long TIMEOUT_NANOS = 180_000_000_000L;
    private static final Map<Short, Session> sessions = new HashMap<>();
    private static final class Session {
        final ServerHuman human;
        final IsoPlayer visitor;
        final UdpConnection connection;
        final HumanTradeOffer offer = new HumanTradeOffer();
        final long expires = System.nanoTime() + TIMEOUT_NANOS;
        Session(ServerHuman human, IsoPlayer visitor, UdpConnection connection) {
            this.human=human; this.visitor=visitor; this.connection=connection;
        }
    }

    /** Only the target of a trading packet may resolve an NPC. Other protocols stay untouched. */
    public static void resolveTarget(PlayerID target) {
        if (!GameServer.server || target.getPlayer() != null || target.getPlayerIndex() != -1) return;
        ServerHuman human = ServerHumanFactory.tradingHuman(target.getID());
        if (human != null) target.set(human.player);
    }

    private static ServerHuman human(IsoPlayer player) {
        if (player == null) return null;
        ServerHuman human = ServerHumanFactory.tradingHuman(player.getOnlineID());
        return human != null && human.player == player ? human : null;
    }

    private static boolean owned(IsoPlayer player, UdpConnection connection) {
        return player != null && connection != null && connection.isFullyConnected()
            && connection.hasPlayer(player.getOnlineID())
            && GameServer.IDToPlayerMap.get(player.getOnlineID()) == player
            && GameServer.getConnectionFromPlayer(player) == connection;
    }

    private static boolean nearby(ServerHuman human, IsoPlayer visitor) {
        double dx=visitor.getX()-human.player.getX(), dy=visitor.getY()-human.player.getY();
        return !visitor.isDead() && dx*dx+dy*dy <= 9.0
            && human.actuator.face(new HumanObservation.Identity(visitor.getOnlineID(), visitor.getID()));
    }

    private static Session session(IsoPlayer visitor) {
        for (Session session : sessions.values()) if (session.visitor == visitor) return session;
        return null;
    }

    /** Consume all packets involving NPCs, including invalid requests and spoofed roles. */
    public static boolean request(IsoPlayer visitor, IsoPlayer target, UdpConnection connection, boolean ask) {
        ServerHuman human=human(target);
        if (human == null) {
            if (human(visitor) != null) return true;
            Session active=session(visitor);
            if (active != null && owned(visitor, connection)) { finish(active, true); return true; }
            return false;
        }
        if (!owned(visitor, connection)) return true;
        if (!human.profile.trading()) { reply(human,visitor,connection,false); return true; }
        if (!ServerHumanEncounters.authorized(human,visitor)||human.activity.emergency(human.actuator.observe(System.nanoTime()))) {
            reply(human,visitor,connection,false);return true;
        }
        Session active=sessions.get(target.getOnlineID());
        if (!ask) {
            if (active != null && active.visitor == visitor) finish(active, true);
            return true;
        }
        if (active != null) {
            // Reliable retransmissions must not replace/reset an active offer.
            if (active.visitor == visitor && active.connection == connection) reply(human, visitor, connection, true);
            else { reply(human, visitor, connection, false); human.actuator.say("Estou atendendo outra pessoa. Aguarde um instante."); }
            return true;
        }
        if (session(visitor) != null || !nearby(human, visitor) || !ApocBRHumanSales.available()
            || !ServerPlayerDB.isAvailable()) {
            reply(human, visitor, connection, false);
            human.actuator.say("Não posso negociar agora. Tente novamente em instantes.");
            return true;
        }
        if (!human.activity.beginTrade(new HumanObservation.Identity(visitor.getOnlineID(),visitor.getID()), System.nanoTime(),human.actuator)) {
            reply(human, visitor, connection, false); return true;
        }
        sessions.put(target.getOnlineID(), new Session(human, visitor, connection));
        reply(human, visitor, connection, true);
        human.actuator.say("Aceito dinheiro e os itens de valor que você encontrou. Notas e maços de dinheiro serão registrados para crédito. Cada maço vale 100 notas. Os demais itens serão destruídos sem crédito. Coloque os itens na troca, sele a oferta e confirme.");
        return true;
    }

    private static void reply(ServerHuman human, IsoPlayer visitor, UdpConnection connection, boolean accepted) {
        RequestTradingPacket packet=new RequestTradingPacket();
        if (accepted) packet.accept(human.player,visitor); else packet.reject(human.player,visitor);
        var writer=connection.startPacket();
        PacketTypes.PacketType.RequestTrading.doPacket(writer);
        packet.write(writer);
        PacketTypes.PacketType.RequestTrading.send(connection);
    }

    private static boolean intercepted(IsoPlayer visitor, IsoPlayer target) {
        return human(visitor) != null || human(target) != null || session(visitor) != null;
    }

    private static Session validSession(IsoPlayer visitor, IsoPlayer target, UdpConnection connection) {
        if (!owned(visitor,connection)) return null;
        Session session=session(visitor);
        if (session == null) return null;
        if (session.human.player != target || session.connection != connection
            || System.nanoTime() >= session.expires || !nearby(session.human,visitor)
            || !ServerHumanEncounters.authorized(session.human,visitor)||session.human.activity.emergency(session.human.actuator.observe(System.nanoTime()))) {
            finish(session,true); return null;
        }
        return session;
    }

    public static boolean addItem(IsoPlayer visitor, IsoPlayer target, UdpConnection connection, InventoryItem item) {
        if (!intercepted(visitor,target)) return false;
        Session session=validSession(visitor,target,connection);
        if (session == null) return true;
        if (!ownedItem(visitor,item) || !session.offer.add(item.getID())) {
            session.human.actuator.say("Não consegui validar a oferta. Seus itens continuam com você.");
            finish(session,true);
        } else sendState(session,TradingState.DealWasUnsealed);
        return true;
    }

    public static boolean removeItem(IsoPlayer visitor, IsoPlayer target, UdpConnection connection, int itemId) {
        if (!intercepted(visitor,target)) return false;
        Session session=validSession(visitor,target,connection);
        if (session != null) { session.offer.remove(itemId); sendState(session,TradingState.DealWasUnsealed); }
        return true;
    }

    public static boolean state(IsoPlayer visitor, IsoPlayer target, UdpConnection connection, TradingState state) {
        if (!intercepted(visitor,target)) return false;
        Session session=validSession(visitor,target,connection);
        if (session == null) return true;
        switch (state) {
            case WindowWasClosed -> finish(session,false);
            case DealWasUnsealed -> { session.offer.unseal(); sendState(session,TradingState.DealWasUnsealed); }
            case DealWasSealed -> {
                if (offeredItems(session) != null && session.offer.seal()) sendState(session,TradingState.DealWasSealed);
                else { session.human.actuator.say("Coloque pelo menos um item na troca."); finish(session,true); }
            }
            case DealWasFinalized -> {
                if (session.offer.ready()) sell(session);
                else finish(session,true);
            }
        }
        return true;
    }

    private static boolean ownedItem(IsoPlayer visitor, InventoryItem item) {
        return item != null
            && item.getContainer() == visitor.getInventory()
            && visitor.getInventory().getItemWithID(item.getID()) == item;
    }

    private static List<InventoryItem> offeredItems(Session session) {
        List<InventoryItem> items=new ArrayList<>();
        for (int id : session.offer.items()) {
            InventoryItem item=session.visitor.getInventory().getItemWithID(id);
            if (!ownedItem(session.visitor,item)) return null;
            items.add(item);
        }
        return items.isEmpty() ? null : items;
    }

    private static void sell(Session session) {
        List<InventoryItem> items=offeredItems(session);
        if (items == null || !ApocBRHumanSales.available() || !ServerPlayerDB.isAvailable()) {
            session.human.actuator.say("Não foi possível confirmar a troca. Seus itens continuam com você.");
            finish(session,true); return;
        }
        UUID saleId=UUID.randomUUID();
        List<Integer> moneyIds=items.stream().filter(item -> "Base.Money".equals(item.getFullType())
            || "Base.MoneyBundle".equals(item.getFullType())).map(InventoryItem::getID).toList();
        List<Integer> bundleIds=items.stream().filter(item -> "Base.MoneyBundle".equals(item.getFullType())).map(InventoryItem::getID).toList();
        try {
            session.human.activity.recordingSale(System.nanoTime(),session.human.actuator);
            if (!moneyIds.isEmpty()) {
                var sale=new ApocBRHumanSales.Sale(saleId,ApocBRPayloadProvenance.configuredServerId(),Core.gameSaveWorld,
                    session.visitor.getUsername(),SteamUtils.isSteamModeEnabled() ? Long.toUnsignedString(session.visitor.getSteamID()) : null,
                    session.connection.getPlayerIndex(session.visitor),session.human.definition.key(),System.currentTimeMillis(),moneyIds,bundleIds);
                ApocBRHumanSales.enqueue(sale);
            }
        } catch (RuntimeException failure) {
            DebugLog.log("[ApocBR][HumanSales] OFFER_NOT_CONSUMED; sale="+saleId+"; issue="+failure.getClass().getSimpleName());
            session.human.actuator.say("Não consegui registrar a troca. Seus itens continuam com você.");
            finish(session,true); return;
        }
        // No actor/world objects ever cross into the database worker.
        session.offer.close();
        try {
            for (InventoryItem item : items) session.visitor.getInventory().Remove(item);
            INetworkPacket.send(session.connection,PacketTypes.PacketType.RemoveInventoryItemFromContainer,session.visitor.getInventory(),items);
            ServerPlayerDB.getInstance().serverUpdateNetworkCharacter(session.visitor,session.connection.getPlayerIndex(session.visitor),session.connection);
            sendState(session,TradingState.DealWasFinalized);
            session.human.actuator.say(moneyIds.isEmpty()
                ? "Itens recebidos e descartados. Como não havia notas ou maços de dinheiro, esta entrega não gera crédito."
                : "Entrega recebida! Registrei as notas e os maços de dinheiro para processamento do crédito. Os demais itens foram descartados. Obrigado!");
        } catch (RuntimeException failure) {
            DebugLog.log("[ApocBR][HumanSales] CONSUMPTION_REQUIRES_REVIEW; sale="+saleId+"; money="+!moneyIds.isEmpty()
                +"; issue="+failure.getClass().getSimpleName());
            sendState(session,TradingState.WindowWasClosed);
            session.human.actuator.say("A entrega precisa de conferência. Procure a administração antes de tentar novamente.");
        } finally {
            finish(session,false);
        }
    }

    private static void sendState(Session session, TradingState state) {
        INetworkPacket.send(session.connection,PacketTypes.PacketType.TradingUIUpdateState,session.human.player,session.visitor,state);
    }

    private static void finish(Session session, boolean notify) {
        sessions.remove(session.human.player.getOnlineID(),session);
        session.offer.close();
        if (notify && session.connection.isFullyConnected()) sendState(session,TradingState.WindowWasClosed);
        session.human.activity.finishTrade(System.nanoTime(),session.human.actuator);
    }

    static void update(ServerHuman human, long now) {
        Session session=sessions.get(human.player.getOnlineID());
        if (session != null && (now >= session.expires || !owned(session.visitor,session.connection) || !nearby(human,session.visitor)
            || !ServerHumanEncounters.authorized(human,session.visitor)||human.activity.emergency(human.actuator.observe(now)))) finish(session,true);
    }

    static void close(ServerHuman human) {
        Session session=sessions.get(human.player.getOnlineID());
        if (session != null) finish(session,true);
    }
    private ServerHumanTrading() { }
}
