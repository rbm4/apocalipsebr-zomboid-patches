package cloud.apocalipse.pz.agent.features.lua.login;

import java.util.*;
import zombie.characters.*;
import zombie.network.*;
import zombie.core.Core;
import zombie.core.znet.SteamUtils;

/** Captured before admission; workers never traverse mutable role or option registries. */
public final class WorkerContext {
    public record Snapshot(Map<Integer,Role> roles,Role banned,Role user,Role newUser,
            Map<Object,Object> options,String world,boolean steam,String admin,int maximum,String username,boolean validUsername) { }
    private static final ThreadLocal<Snapshot> CURRENT=new ThreadLocal<>();
    public static Snapshot capture(String username) {
        Map<Integer,Role> roles=new HashMap<>();
        for(Role role:Roles.getRoles())roles.put(role.getId(),copy(role));
        Map<Object,Object> options=new IdentityHashMap<>();
        for(var option:ServerOptions.instance.getOptions()) {
            var config=option.asConfigOption();
            if(config instanceof zombie.config.BooleanConfigOption bool)options.put(config,bool.getValue());
            if(config instanceof zombie.config.IntegerConfigOption number)options.put(config,number.getValue());
        }
        return new Snapshot(Map.copyOf(roles),copy(Roles.getDefaultForBanned()),copy(Roles.getDefaultForUser()),
            copy(Roles.getDefaultForNewUser()),Collections.unmodifiableMap(options),Core.gameSaveWorld,
            SteamUtils.isSteamModeEnabled(),ServerWorldDatabase.instance.commandLineAdminUsername,ServerOptions.instance.maxAccountsPerUser.getValue(),username,username!=null && ServerWorldDatabase.isValidUserName(username));
    }
    private static Role copy(Role original) {
        if(original==null)return null;
        Role next=new Role(original.getName());next.setId(original.getId());next.setPosition(original.getPosition());
        for(Capability capability:original.getCapabilities())next.addCapability(capability);return next;
    }
    static void install(Snapshot snapshot){CURRENT.set(snapshot);}
    static void clear(){CURRENT.remove();}
    public static boolean validName(String name) {
        var s=CURRENT.get();
        if(s==null)return ServerWorldDatabase.isValidUserName(name);
        if(!java.util.Objects.equals(name,s.username))throw new IllegalStateException("Uncaptured login identity");
        return s.validUsername;
    }
    public static boolean active(){return CURRENT.get()!=null;}
    public static Integer maximum(){var s=CURRENT.get();return s==null?null:s.maximum;}
    public static Role getRoleById(int id){var s=CURRENT.get();return s==null?Roles.getRoleById(id):s.roles.get(id);}
    public static Role getDefaultForBanned(){var s=CURRENT.get();return s==null?Roles.getDefaultForBanned():s.banned;}
    public static Role getDefaultForUser(){var s=CURRENT.get();return s==null?Roles.getDefaultForUser():s.user;}
    public static Role getDefaultForNewUser(){var s=CURRENT.get();return s==null?Roles.getDefaultForNewUser():s.newUser;}
    public static String world(){var s=CURRENT.get();return s==null?Core.gameSaveWorld:s.world;}
    public static String admin(Object original){var s=CURRENT.get();return s==null?((ServerWorldDatabase)original).commandLineAdminUsername:s.admin;}
    public static boolean steam(){var s=CURRENT.get();return s==null?SteamUtils.isSteamModeEnabled():s.steam;}
    public static boolean booleanOption(Object option){var s=CURRENT.get();return s==null?((zombie.config.BooleanConfigOption)option).getValue():(Boolean)s.options.get(option);}
    public static int integerOption(Object option){var s=CURRENT.get();return s==null?((zombie.config.IntegerConfigOption)option).getValue():(Integer)s.options.get(option);}
    public static java.text.DateFormat dateFormat(java.text.DateFormat original){return active()?new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss"):original;}
    public static java.nio.charset.CharsetEncoder ascii(java.nio.charset.CharsetEncoder original){return active()?java.nio.charset.StandardCharsets.US_ASCII.newEncoder():original;}
}
