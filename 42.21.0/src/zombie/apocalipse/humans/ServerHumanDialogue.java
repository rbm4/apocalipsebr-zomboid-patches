package zombie.apocalipse.humans;

import java.util.HashMap;
import java.util.Map;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.apocalipse.humans.brain.HumanObservation;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.apocalipse.humans.dialogue.HumanDialogueCatalog;
import zombie.apocalipse.humans.dialogue.HumanDialogueSession;
import zombie.apocalipse.humans.dialogue.HumanLanguage;

/** Simulation-thread conversation authority. Lua transports identifiers and resolved presentation. */
public final class ServerHumanDialogue {
    private static final long APPROACH_NANOS = 30_000_000_000L;
    private static final long TALK_NANOS = 60_000_000_000L;
    private static final Map<Short, Session> sessions = new HashMap<>();
    private static KahluaTable environment;
    private static int sequence;
    private static long contentGeneration;
    private static final class Session {
        final ServerHuman human;
        final IsoPlayer visitor;
        final UdpConnection connection;
        final int token;
        long deadline, emoteUntil;
        boolean arrived;
        HumanDialogueSession graph;
        long nextChoice;
        Session(ServerHuman human, IsoPlayer visitor, int token, long now) {
            this.human = human; this.visitor = visitor; this.token = token;
            connection = GameServer.getConnectionFromPlayer(visitor);
            deadline = now + APPROACH_NANOS;
        }
    }
    private ServerHumanDialogue() { }

    /** Install Lua functions without replacing the game's enormous LuaManager class. */
    static void install() {
        var content=zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.current();
        if(environment!=null && content!=null && contentGeneration!=content.generation()) {
            contentGeneration=content.generation();rosterChanged();
        }
        if (LuaManager.env == null || environment == LuaManager.env) return;
        environment = LuaManager.env;
        LuaEventManager.AddEvent("OnApocBRHumanDialogueReserved");
        LuaEventManager.AddEvent("OnApocBRHumanDialogueOpened");
        LuaEventManager.AddEvent("OnApocBRHumanDialogueClosed");
        LuaEventManager.AddEvent("OnApocBRHumanRosterChanged");
        KahluaTable api = LuaManager.platform.newTable();
        bind(api, "list", 1, f -> list(player(f)));
        bind(api, "roster", 0, f -> roster());
        bind(api, "dialogues", 0, f -> dialogues());
        bind(api, "language", 0, f -> HumanLanguage.locale());
        bind(api, "text", 1, f -> f.get(0) instanceof String key?HumanLanguage.text(key):null);
        bind(api, "dialogueRoster", 1, f -> graphRoster(player(f)));
        bind(api, "dialogueBegin", 5, f -> graphBegin(player(f),id(f),integer(f.get(2)),f.get(3),f.get(4),System.nanoTime()));
        bind(api, "dialogueView", 3, f -> graphView(player(f),id(f),token(f),System.nanoTime()));
        bind(api, "dialogueSelect", 7, f -> graphSelect(player(f),id(f),token(f),f.get(3),integer(f.get(4)),f.get(5),integer(f.get(6)),System.nanoTime()));
        bind(api, "dialogueFlags", 3, f -> dialogueFlags(player(f),id(f),f.get(2)));
        bind(api, "remember", 5, f -> remember(player(f),id(f),token(f),f.get(3),f.get(4)));
        bind(api, "begin", 2, f -> begin(player(f), id(f), System.nanoTime()));
        bind(api, "arrive", 3, f -> arrive(player(f), id(f), token(f), System.nanoTime()));
        bind(api, "finish", 3, f -> finish(player(f), id(f), token(f)));
        bind(api, "react", 5, f -> react(player(f), id(f), token(f),
            f.get(3) instanceof String text ? text : null,
            f.get(4) instanceof String emote ? emote : null, System.nanoTime()));
        api.rawset("version", 4.0);
        zombie.debug.DebugLog.log("[ApocBR][NPC] language="+HumanLanguage.locale()+"; source=apocbr.npc.language; default=PTBR");
        environment.rawset("ApocBRHumanAPI", api);
    }
    private interface Call { Object run(LuaCallFrame frame); }
    private static zombie.savefile.ApocBRNpcStore.DialogueIdentity identity(IsoPlayer p,ServerHuman h,String dialogue) {
        long steam=p.getSteamID();
        return new zombie.savefile.ApocBRNpcStore.DialogueIdentity(h.definition.key(),dialogue,
            steam==0?"username":"steam",steam==0?p.getUsername():Long.toUnsignedString(steam));
    }
    private static KahluaTable dialogueFlags(IsoPlayer p,int id,Object dialogue) {
        var result=LuaManager.platform.newTable();var store=zombie.savefile.ApocBRNpcStore.current();
        var h=human(id);
        if(store==null||!owned(p)||h==null||!(dialogue instanceof String key))return result;
        for(String flag:store.dialogueFlags(identity(p,h,key)))result.rawset(flag,true);
        return result;
    }
    private static boolean remember(IsoPlayer p,int id,int token,Object dialogue,Object flag) {
        var session=session(p,id,token,System.nanoTime());
        var store=zombie.savefile.ApocBRNpcStore.current();
        if(store==null||session==null||!session.arrived||!near(session.human,p,2.5)
            ||!(dialogue instanceof String key)||!(flag instanceof String fact))return false;
        return store.remember(identity(p,session.human,key),fact);
    }
    private static KahluaTable dialogues() {
        var snapshot=zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.current();
        var result=LuaManager.platform.newTable();
        boolean database=Boolean.getBoolean("apocbr.npc.persistence.enabled");
        result.rawset("database",database);
        result.rawset("ready",!database || snapshot!=null);
        if(database && snapshot!=null) {
            result.rawset("generation",(double)snapshot.generation());
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> graph(ServerHuman h) {
        var catalog=HumanDialogueCatalog.current();
        return h==null||catalog==null?null:(Map<String,Object>)catalog.profiles().get(h.profile.id());
    }
    private static java.util.Set<String> flags(IsoPlayer p,ServerHuman h,HumanDialogueSession graph) {
        var store=zombie.savefile.ApocBRNpcStore.current();
        return store==null?java.util.Set.of():store.dialogueFlags(identity(p,h,graph.id()));
    }
    static KahluaTable graphRoster(IsoPlayer p) {
        var result=LuaManager.platform.newTable();if(!owned(p))return result;int i=0;
        for(var h:ServerHumanFactory.dialogueHumans()) {
            var content=graph(h);if(!h.profile.dialogue()||content==null)continue;
            var session=new HumanDialogueSession(content);
            // Roster discovery intentionally excludes actor coordinates and session authority.
            var actor=LuaManager.platform.newTable();actor.rawset("id",(double)h.player.getOnlineID());
            actor.rawset("key",h.definition.key());actor.rawset("name",h.definition.displayName());
            var view=session.view(flags(p,h,session));actor.rawset("dialogue",luaValue(view));
            var nodes=(Map<?,?>)view.get("nodes");var root=(Map<?,?>)nodes.get(view.get("entryNode"));
            actor.rawset("options",luaValue(root.get("options")));result.rawset((double)++i,actor);
        }
        return result;
    }
    static KahluaTable graphBegin(IsoPlayer p,int id,int revision,Object node,Object option,long now) {
        var h=human(id);var content=graph(h);
        if(!owned(p)||content==null||!(node instanceof String current)||!(option instanceof String selected))return rejected("unavailable");
        var graph=new HumanDialogueSession(content);
        if(graph.choose(current,revision,selected,1,flags(p,h,graph))==null)return rejected("unavailable");
        var result=begin(p,id,now);if(result.rawget("error")!=null)return result;
        sessions.get((short)id).graph=graph;return result;
    }
    static KahluaTable graphView(IsoPlayer p,int id,int token,long now) {
        var s=session(p,id,token,now);
        if(s==null||!s.arrived||s.graph==null||!near(s.human,p,2.5))return rejected("unavailable");
        return (KahluaTable)luaValue(s.graph.view(flags(p,s.human,s.graph)));
    }
    static KahluaTable graphSelect(IsoPlayer p,int id,int token,Object node,int revision,Object option,int sequence,long now) {
        var s=session(p,id,token,now);
        if(s==null||!s.arrived||s.graph==null||now<s.nextChoice||!near(s.human,p,2.5)
            ||!(node instanceof String current)||!(option instanceof String selected))return rejected("unavailable");
        var choice=s.graph.choose(current,revision,selected,sequence,flags(p,s.human,s.graph));
        if(choice==null)return rejected("unavailable");
        String speech=(String)choice.text().get("speech"),emote=(String)choice.option().get("emote");
        if(speech==null||speech.length()>256||emote!=null&&!emote.matches("[A-Za-z0-9_]{1,40}"))return rejected("unavailable");
        var task=choice.option().get("task") instanceof zombie.apocalipse.humans.tasks.HumanTaskCommand command?command:null;
        if(task!=null&&(!Boolean.TRUE.equals(choice.option().get("close"))||!ServerHumanTasks.accepts(s.human,task,p)))return rejected("unavailable");
        Object flag=choice.option().get("setFlag");
        if(flag!=null&&!remember(p,id,token,s.graph.id(),flag))return rejected("unavailable");
        if(!react(p,id,token,speech.isBlank()?null:speech,emote,now))return rejected("unavailable");
        if(task!=null)ServerHumanTasks.apply(s.human,task,p);
        s.graph.advance(choice);s.nextChoice=now+500_000_000L;
        var result=LuaManager.platform.newTable();result.rawset("dialogue",luaValue(s.graph.view(flags(p,s.human,s.graph))));
        result.rawset("sequence",(double)sequence);result.rawset("option",selected);
        result.rawset("response",choice.text().get("response"));result.rawset("close",s.graph.completed());return result;
    }
    private static Object luaValue(Object value) {
        if(value instanceof Map<?,?> map) {
            var table=LuaManager.platform.newTable();
            map.forEach((key,item)->table.rawset(key,luaValue(item)));return table;
        }
        if(value instanceof java.util.List<?> list) {
            var table=LuaManager.platform.newTable();int i=0;
            for(Object item:list)table.rawset((double)++i,luaValue(item));return table;
        }
        return value instanceof Number n ? n.doubleValue() : value;
    }
    private static void bind(KahluaTable api, String name, int arguments, Call call) {
        api.rawset(name, (JavaFunction)(frame, count) -> {
            Object result = count == arguments ? call.run(frame) : null;
            frame.push(result);
            return 1;
        });
    }
    private static IsoPlayer player(LuaCallFrame frame) { return frame.get(0) instanceof IsoPlayer p ? p : null; }
    private static int integer(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
            || n.doubleValue() != n.intValue()) return -1;
        return n.intValue();
    }
    private static int id(LuaCallFrame f) { return integer(f.get(1)); }
    private static int token(LuaCallFrame f) { return integer(f.get(2)); }
    private static ServerHuman human(int id) {
        return id < 0 || id > Short.MAX_VALUE ? null : ServerHumanFactory.tradingHuman((short)id);
    }
    private static boolean owned(IsoPlayer p) {
        if (p == null || p.isDead() || GameServer.IDToPlayerMap.get(p.getOnlineID()) != p) return false;
        UdpConnection c = GameServer.getConnectionFromPlayer(p);
        return c != null && c.isFullyConnected() && c.hasPlayer(p.getOnlineID()) && !GameServer.isDelayedDisconnect(p);
    }
    private static boolean near(ServerHuman h, IsoPlayer p, double radius) {
        if (!owned(p) || p.getZi() != h.player.getZi()) return false;
        double dx = p.getX() - h.player.getX(), dy = p.getY() - h.player.getY();
        return dx * dx + dy * dy <= radius * radius
            && h.actuator.face(new HumanObservation.Identity(p.getOnlineID(), p.getID()));
    }
    private static KahluaTable description(ServerHuman h) {
        KahluaTable t = LuaManager.platform.newTable();
        t.rawset("id", (double)h.player.getOnlineID()); t.rawset("key", h.definition.key());
        t.rawset("name", h.definition.displayName());
        t.rawset("profileId", h.profile.id());
        t.rawset("x", (double)h.player.getX()); t.rawset("y", (double)h.player.getY()); t.rawset("z", (double)h.player.getZi());
        return t;
    }
    static KahluaTable list(IsoPlayer p) {
        KahluaTable result = LuaManager.platform.newTable();
        if (!owned(p)) return result;
        int i = 0;
        for (ServerHuman h : ServerHumanFactory.dialogueHumans()) {
            if (!h.profile.dialogue()) continue;
            double dx = p.getX() - h.player.getX(), dy = p.getY() - h.player.getY();
            if (p.getZi() == h.player.getZi() && dx * dx + dy * dy <= 32 * 32) result.rawset((double)++i, description(h));
        }
        return result;
    }

    /** Stable public identity only; cached Lua menus must not depend on player proximity. */
    static KahluaTable roster() {
        KahluaTable result = LuaManager.platform.newTable();
        int i = 0;
        for (ServerHuman h : ServerHumanFactory.dialogueHumans()) {
            if (!h.profile.dialogue()) continue;
            KahluaTable actor = LuaManager.platform.newTable();
            actor.rawset("id", (double)h.player.getOnlineID());
            actor.rawset("key", h.definition.key());
            actor.rawset("name", h.definition.displayName());
            actor.rawset("profileId", h.profile.id());
            result.rawset((double)++i, actor);
        }
        return result;
    }

    static void rosterChanged() {
        LuaEventManager.triggerEvent("OnApocBRHumanRosterChanged");
    }
    private static KahluaTable rejected(String reason) {
        KahluaTable t = LuaManager.platform.newTable(); t.rawset("error", reason); return t;
    }
    static KahluaTable begin(IsoPlayer p, int id, long now) {
        ServerHuman h = human(id);
        if (h == null || !h.profile.dialogue() || !owned(p)) return rejected("unavailable");
        if (sessions.containsKey((short)id) || sessions.values().stream().anyMatch(s -> s.visitor == p)) return rejected("busy");
        if (h.activity.emergency(h.actuator.observe(now))) return rejected("unavailable");
        if (!near(h, p, 8)) return rejected("unavailable");
        if (!h.activity.beginDialogue(new HumanObservation.Identity(p.getOnlineID(), p.getID()), now, h.actuator)) return rejected("busy");
        // Tokens only need process-lifetime uniqueness within this bounded lease registry.
        if (++sequence <= 0) sequence = 1;
        Session s = new Session(h, p, sequence, now); sessions.put((short)id, s);
        KahluaTable t = description(h); t.rawset("token", (double)s.token);
        LuaEventManager.triggerEvent("OnApocBRHumanDialogueReserved", p, t);
        return t;
    }
    private static Session session(IsoPlayer p, int id, int token, long now) {
        if (id < 0 || id > Short.MAX_VALUE) return null;
        Session s = sessions.get((short)id);
        if (s == null || s.visitor != p || s.token != token) return null;
        if (s.human.activity.emergency(s.human.actuator.observe(now))) {
            close(s.human, "threat", now); return null;
        }
        if (now >= s.deadline || !owned(p) || GameServer.getConnectionFromPlayer(p) != s.connection) {
            close(s.human, "expired"); return null;
        }
        return s;
    }
    static boolean arrive(IsoPlayer p, int id, int token, long now) {
        Session s = session(p, id, token, now);
        if (s == null || !near(s.human, p, 2.5)) return false;
        if (s.arrived) return true;
        s.arrived = true; s.deadline = now + TALK_NANOS;
        KahluaTable opened = description(s.human); opened.rawset("token", (double)s.token);
        LuaEventManager.triggerEvent("OnApocBRHumanDialogueOpened", p, opened);
        return true;
    }
    static boolean react(IsoPlayer p, int id, int token, String text, String emote, long now) {
        Session s = session(p, id, token, now);
        if (s == null || !s.arrived || !near(s.human, p, 2.5)
            || text != null && (text.isBlank() || text.length() > 256)
            || emote != null && !emote.matches("[A-Za-z0-9_]{1,40}")) return false;
        s.human.actuator.stopEmote();
        if (text != null) s.human.actuator.say(text);
        if (emote != null) { s.human.actuator.startEmote(emote); s.emoteUntil = now + 2_500_000_000L; }
        s.deadline = now + TALK_NANOS;
        return true;
    }
    private static boolean finish(IsoPlayer p, int id, int token) {
        Session s = session(p, id, token, System.nanoTime());
        if (s == null) return false;
        close(s.human, "cancelled"); return true;
    }
    static void update(ServerHuman h, long now) {
        Session s = sessions.get(h.player.getOnlineID());
        if (s == null) return;
        if (session(s.visitor, h.player.getOnlineID(), s.token, now) == null) return;
        if (!near(h, s.visitor, s.arrived ? 3 : 8)) { close(h, "visitor_lost"); return; }
        if (s.emoteUntil != 0 && now >= s.emoteUntil) { h.actuator.stopEmote(); s.emoteUntil = 0; }
    }
    static void close(ServerHuman h, String reason) {
        close(h,reason,System.nanoTime());
    }
    private static void close(ServerHuman h, String reason, long now) {
        Session s = sessions.remove(h.player.getOnlineID());
        if (s == null) return;
        h.actuator.stopEmote(); h.activity.finishDialogue(now, h.actuator);
        KahluaTable t = description(h); t.rawset("token", (double)s.token); t.rawset("reason", reason);
        LuaEventManager.triggerEvent("OnApocBRHumanDialogueClosed", s.visitor, t);
    }
}
