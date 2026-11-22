// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.GameTime;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoGridSquare;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.BuildActionPacket;
import zombie.network.packets.FishingActionPacket;
import zombie.network.packets.NetTimedActionPacket;
import zombie.network.server.AnimEventEmulator;

public class ActionManager {
    private static ActionManager instance;
    private static final ConcurrentLinkedQueue<Action> actions = new ConcurrentLinkedQueue<>();

    public static ActionManager getInstance() {
        if (instance == null) {
            instance = new ActionManager();
        }

        return instance;
    }

    public static void start(Action action) {
        DebugType.Action.debugln("ActionManager start action %s", action.getDescription());
        action.start();
        add(action);
    }

    public static void add(Action action) {
        DebugType.Action.debugln("ActionManager add action %s", action.getDescription());
        actions.add(action);
    }

    public static void clear() {
        DebugType.Action.debugln("ActionManager clear actions");
        actions.clear();
    }

    public static void stop(Action action) {
        DebugType.Action.debugln("ActionManager stop action %s", action.getDescription());
        remove(action.player.getPlayer(), action.id);
    }

    public static void stopPlayerActions(IsoPlayer player) {
        for (Action action : actions) {
            if (isActionOf(action, player)) {
                stop(action);
            }
        }
    }

    public static void update() {
        if (GameServer.server) {
            for (Action action : actions) {
                if (action.state == Transaction.TransactionState.Accept && action.endTime <= GameTime.getServerTimeMills()) {
                    DebugType.Action.debugln("ActionManager complete %s", action.getDescription());
                    if (action.perform()) {
                        action.state = Transaction.TransactionState.Done;
                        UdpConnection connection = GameServer.getConnectionFromPlayer(action.player.getPlayer());
                        if (connection != null && connection.isFullyConnected()) {
                            if (action instanceof BuildAction) {
                                ByteBufferWriter bbw2 = connection.startPacket();
                                PacketTypes.PacketType.BuildAction.doPacket(bbw2);
                                action.write(bbw2);
                                PacketTypes.PacketType.BuildAction.send(connection);
                            }

                            if (action instanceof NetTimedAction) {
                                ByteBufferWriter bbw2 = connection.startPacket();
                                PacketTypes.PacketType.NetTimedAction.doPacket(bbw2);
                                action.write(bbw2);
                                PacketTypes.PacketType.NetTimedAction.send(connection);
                            }
                        }
                    } else {
                        action.state = Transaction.TransactionState.Reject;
                        DebugType.Action.noise("ActionManager reject %s", action.getDescription());
                        UdpConnection connection = GameServer.getConnectionFromPlayer(action.player.getPlayer());
                        if (connection != null && connection.isFullyConnected() && action instanceof NetTimedAction) {
                            ByteBufferWriter bbw2 = connection.startPacket();
                            PacketTypes.PacketType.NetTimedAction.doPacket(bbw2);
                            action.write(bbw2);
                            PacketTypes.PacketType.NetTimedAction.send(connection);
                        }
                    }
                } else {
                    action.update();
                }
            }

            List<Action> transactionForDelete = actions.stream()
                .filter(r -> r.state == Transaction.TransactionState.Done || r.state == Transaction.TransactionState.Reject)
                .collect(Collectors.toList());
            actions.removeAll(transactionForDelete);

            for (Action actionx : transactionForDelete) {
                DebugType.Action.debugln("ActionManager clear action %s", actionx.getDescription());
                if (actionx instanceof NetTimedAction netTimedAction) {
                    AnimEventEmulator.getInstance().remove(netTimedAction);
                }
            }
        } else if (GameClient.client) {
            actions.forEach(Action::update);
            List<Action> transactionForDelete = actions.stream()
                .filter(t -> t.isUsingTimeout() && GameTime.getServerTimeMills() > t.startTime + AnimEventEmulator.getInstance().getDurationMax())
                .collect(Collectors.toList());
            actions.removeAll(transactionForDelete);

            for (Action actionxx : transactionForDelete) {
                DebugType.Action.debugln("ActionManager clear action %s", actionxx.getDescription());
            }
        }
    }

    public static boolean isRejected(IsoPlayer player, byte transactionId) {
        return actions.stream()
            .filter(action -> transactionId == action.id && isActionOf(action, player))
            .allMatch(action -> action.state == Transaction.TransactionState.Reject);
    }

    public static boolean isDone(IsoPlayer player, byte transactionId) {
        List<Action> transactionActions = actions.stream().filter(action -> transactionId == action.id && isActionOf(action, player)).toList();
        return !transactionActions.isEmpty() && transactionActions.stream().allMatch(action -> action.state == Transaction.TransactionState.Done);
    }

    public static boolean isLooped(IsoPlayer player, byte transactionId) {
        Optional<Action> res = actions.stream().filter(r -> transactionId == r.id && isActionOf(r, player)).findFirst();
        if (res.isEmpty()) {
            return false;
        } else {
            Action t = res.get();
            return t.duration == -1L;
        }
    }

    public static int getDuration(IsoPlayer player, byte transactionId) {
        Optional<Action> res = actions.stream().filter(r -> transactionId == r.id && isActionOf(r, player)).findFirst();
        if (res.isEmpty()) {
            return -1;
        } else {
            Action t = res.get();
            return (int)(t.endTime - t.startTime);
        }
    }

    public static void remove(IsoPlayer player, byte transactionId) {
        if (GameClient.client) {
            if (transactionId != 0) {
                List<Action> transactionForDelete = actions.stream().filter(t -> t.id == transactionId && isActionOf(t, player)).toList();
                actions.removeAll(transactionForDelete);

                for (Action action : transactionForDelete) {
                    DebugType.Action.debugln("ActionManager remove action %s", action.getDescription());
                }
            }
        } else if (GameServer.server) {
            List<Action> transactionForDelete = actions.stream().filter(t -> t.id == transactionId && isActionOf(t, player)).toList();
            actions.removeAll(transactionForDelete);

            for (Action action : transactionForDelete) {
                DebugType.Action.debugln("ActionManager remove action %s", action.getDescription());
                action.stop();
                if (action instanceof NetTimedAction netTimedAction) {
                    AnimEventEmulator.getInstance().remove(netTimedAction);
                }
            }
        }
    }

    private byte sendAction(Action action, PacketTypes.PacketType packetType) {
        if (action.isConsistent(GameClient.connection)) {
            add(action);

            try {
                ByteBufferWriter bbw2 = GameClient.connection.startPacket();
                packetType.doPacket(bbw2);
                action.write(bbw2);
                packetType.send(GameClient.connection);
                DebugType.Action.noise("ActionManager send %s", action.getDescription());
                return action.id;
            } catch (Exception var4) {
                GameClient.connection.cancelPacket();
                DebugType.Multiplayer.printException(var4, "SendAction: failed", LogSeverity.Error);
                return 0;
            }
        } else {
            DebugType.Action.error("ActionManager send FAIL %s", action.getDescription());
            return 0;
        }
    }

    public byte createNetTimedAction(IsoPlayer player, KahluaTable actionTable) {
        NetTimedActionPacket action = new NetTimedActionPacket();
        action.set(player, actionTable);
        return this.sendAction(action, PacketTypes.PacketType.NetTimedAction);
    }

    public byte createBuildAction(IsoPlayer player, float x, float y, float z, boolean north, String spriteName, KahluaTable item) {
        BuildActionPacket action = new BuildActionPacket();
        action.set(player, x, y, z, north, spriteName, item);
        return this.sendAction(action, PacketTypes.PacketType.BuildAction);
    }

    public byte createFishingAction(IsoPlayer player, InventoryItem item, IsoGridSquare sq, KahluaTable bobber) {
        FishingActionPacket action = new FishingActionPacket();
        action.setStartFishing(player, item, sq, bobber);
        return this.sendAction(action, PacketTypes.PacketType.FishingAction);
    }

    public void setStateFromPacket(Action packet) {
        for (Action action : actions) {
            if (packet.id == action.id && isActionOf(action, packet.player.getPlayer())) {
                action.setState(packet.state);
                if (packet.state == Transaction.TransactionState.Accept) {
                    action.setDuration(packet.duration);
                }
                break;
            }
        }
    }

    public void disconnectPlayer(UdpConnection connection) {
        for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
            IsoPlayer player = connection.players[playerIndex];
            if (player != null) {
                for (Action action : actions) {
                    if (isActionOf(action, player)) {
                        action.state = Transaction.TransactionState.Reject;
                    }
                }
            }
        }
    }

    public void replaceObjectInQueuedActions(IsoPlayer player, Object oldItem, Object newItem) {
        KahluaTable table = (KahluaTable)LuaManager.env.rawget("ISTimedActionQueue");
        KahluaTable queues = (KahluaTable)table.rawget("queues");
        KahluaTable queue = (KahluaTable)queues.rawget(player);
        if (queue != null) {
            Set<KahluaTable> seenTables = new HashSet<>();
            KahluaTableIterator queueIt = queue.iterator();

            while (queueIt.advance()) {
                if (queueIt.getKey().equals("queue")) {
                    KahluaTable actions = (KahluaTable)queueIt.getValue();
                    KahluaTableIterator actionsIt = actions.iterator();

                    while (actionsIt.advance()) {
                        KahluaTable action = (KahluaTable)actionsIt.getValue();
                        this.replaceObjectInTableRecurse(action, oldItem, newItem, seenTables);
                    }
                }
            }

            seenTables.clear();
        }
    }

    private void replaceObjectInTableRecurse(KahluaTable table, Object oldItem, Object newItem, Set<KahluaTable> seenTables) {
        KahluaTableIterator iterator = table.iterator();

        while (iterator.advance()) {
            if (iterator.getValue() instanceof KahluaTable subTable) {
                if (seenTables.add(subTable)) {
                    this.replaceObjectInTableRecurse(subTable, oldItem, newItem, seenTables);
                }
            } else if (oldItem == null && this.isItemWithSameID(iterator.getValue(), newItem)) {
                table.rawset(iterator.getKey(), newItem);
            } else if (oldItem != null && iterator.getValue() == oldItem) {
                table.rawset(iterator.getKey(), newItem);
            }
        }
    }

    private boolean isItemWithSameID(Object actionObject, Object newObject) {
        return actionObject instanceof InventoryItem actionItem && newObject instanceof InventoryItem newItem && actionItem.getID() == newItem.getID();
    }

    private static boolean isActionOf(Action action, IsoPlayer player) {
        IsoPlayer actionPlayer = action.player.getPlayer();
        return actionPlayer != null && player != null && actionPlayer.getOnlineID() == player.getOnlineID();
    }

    public static NetTimedAction getAction(byte id, IsoPlayer player) {
        for (Action action : actions) {
            if (action instanceof NetTimedAction netAction && netAction.id == id && isActionOf(netAction, player)) {
                return netAction;
            }
        }

        return null;
    }
}
