package zombie.apocalipse.humans.profiles;
import java.util.Map;
/** Immutable, allowlisted module configuration. Lower priorities run first. */
public record HumanModuleBinding(String moduleId, int priority, int cycleMs, Map<String,Long> parameters) {
    public HumanModuleBinding {
        HumanProfile.identifier(moduleId);
        if(priority<0||priority>10000||cycleMs<100||cycleMs>60000)throw new IllegalArgumentException("Invalid module scheduling");
        parameters=Map.copyOf(parameters);
        if(parameters.size()>16)throw new IllegalArgumentException("Too many module parameters");
        parameters.forEach((key,value)->{HumanProfile.identifier(key);if(value<0||value>86400000L)throw new IllegalArgumentException("Invalid module parameter");});
    }
}
