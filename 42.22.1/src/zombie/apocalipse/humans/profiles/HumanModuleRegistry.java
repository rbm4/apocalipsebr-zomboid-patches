package zombie.apocalipse.humans.profiles;
import java.util.*;
import java.util.function.*;
import zombie.apocalipse.humans.brain.*;
/** Code owns implementations and validation. Database rows cannot load classes/scripts. */
public final class HumanModuleRegistry {
    private record Factory(BiConsumer<HumanProfile,HumanModuleBinding> validate,BiFunction<HumanProfile,HumanModuleBinding,HumanModule> create){}
    private final Map<String,Factory> factories=new HashMap<>();
    public void register(String id,BiConsumer<HumanProfile,HumanModuleBinding> validate,BiFunction<HumanProfile,HumanModuleBinding,HumanModule> create){
        HumanProfile.identifier(id);if(factories.putIfAbsent(id,new Factory(validate,create))!=null)throw new IllegalArgumentException("Duplicate module");
    }
    public void validate(HumanProfile profile){
        if(!profile.parameters().isEmpty())throw new IllegalArgumentException("Module profiles use per-module parameters");
        for(var binding:profile.modules()){
            var factory=factories.get(binding.moduleId());if(factory==null)throw new IllegalArgumentException("Unknown module: "+binding.moduleId());
            factory.validate.accept(profile,binding);
        }
    }
    public HumanModuleRuntime create(HumanProfile profile){
        validate(profile);
        return new HumanModuleRuntime(profile.modules().stream().map(b->new HumanModuleRuntime.Entry(b.moduleId(),b.priority(),b.cycleMs(),factories.get(b.moduleId()).create.apply(profile,b))).toList());
    }
}
