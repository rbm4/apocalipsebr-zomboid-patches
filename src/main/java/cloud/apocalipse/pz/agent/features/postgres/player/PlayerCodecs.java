package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.features.postgres.dictionary.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.*;
import zombie.entity.Component;
import zombie.iso.IsoWorld;
import static cloud.apocalipse.pz.agent.features.postgres.player.PlayerAccess.*;

/** Native component and embedded animal save contracts. Snapshot detaches state before replay. */
final class PlayerCodecs {
    private static final Map<String,String> EMPTY=Map.of(
        "LuaComponent","zombie.entity.components.lua.LuaComponent",
        "Parts","zombie.entity.components.parts.Parts",
        "Signals","zombie.entity.components.signals.Signals",
        "TestComponent","zombie.entity.components.test.TestComponent",
        "CraftRecipeComponent","zombie.entity.components.crafting.CraftRecipeComponent",
        "ContextMenuConfig","zombie.entity.components.contextmenuconfig.ContextMenuConfig",
        "CraftBenchSounds","zombie.entity.components.sounds.CraftBenchSounds",
        "WallCoveringConfig","zombie.entity.components.crafting.WallCoveringConfig");
    private static final Set<String> NATIVE = Set.of(
        "zombie.entity.components.resources.Resources", "zombie.entity.components.crafting.DryingCraftLogic");
    static boolean supports(Object component) {
        String name=component instanceof PlayerSnapshot.Node node?node.runtimeClass:component.getClass().getName();
        return EMPTY.containsValue(name)||name.equals("zombie.entity.components.ui.UiConfig")||name.equals("zombie.entity.components.crafting.CraftBench")
            ||name.equals("zombie.entity.components.spriteconfig.SpriteConfig")
            ||name.equals("zombie.entity.components.spriteconfig.SpriteOverlayConfig")||NATIVE.contains(name);
    }
    static Map<String,Object> component(Object component) {
        if(!supports(component))throw new IllegalArgumentException("UNSUPPORTED_COMPONENT_STATE");
        Map<String,Object> state=new LinkedHashMap<>();
        switch(typeName(component)) {
            case "SpriteConfig" -> {
                // Native save writes a script reference/version and the current master flag.
                // Persist a stable name, never a process-local dictionary short or live script.
                Object script=get(component,"configScript");
                state.put("hasScript",script!=null);
                if(script!=null) {
                    state.put("scriptFullType",call(script,"getScriptObjectFullType"));
                    state.put("scriptVersion",call(script,"getScriptVersion"));
                    state.put("wasLoadedAsMaster",call(component,"isMultiSquareMaster"));
                }
            }
            case "SpriteOverlayConfig" -> {
                Object script=get(component,"configScript");
                state.put("scriptFullType",script==null?null:call(script,"getScriptObjectFullType"));
                state.put("appliedStyle",script==null?null:get(component,"appliedStyle"));
            }
            case "Resources", "DryingCraftLogic" -> state.put("nativePayload", saveComponent(component));
            case "UiConfig" -> fields(state,component,"xuiSkinName","entityStyleName","uiEnabled");
            case "CraftBench" -> {
                fields(state,component,"recipeTagQuery");
                for(String field:List.of("fluidInputChannels","energyInputChannels")) {
                    Object channels=get(component,field);Class<?> type=(Class<?>)get(channels,"elementType");
                    List<String> names=new ArrayList<>();for(Object value:type.getEnumConstants())if((boolean)call(channels,"contains",value))names.add(key(value));
                    state.put(field,Map.of("enumClass",type.getName(),"nativeBits",get(channels,"bits"),"names",names));
                }
            }
            default -> { /* Explicit whitelist: these inherit Component.save/load with zero payload. */ }
        }
        return state;
    }
    static Map<String,Object> fallbackComponent(Object value) {
        if (!(value instanceof Component component) || component.getComponentType().GetComponentClass()!=component.getClass())
            throw new IllegalStateException("UNREGISTERED_NATIVE_COMPONENT");
        return Map.of("nativeFallbackType",component.getComponentType().name(),"nativePayload",saveComponent(component));
    }
    private static byte[] saveComponent(Object component) {
        var origin = DictionaryRuntime.localEncoding();
        if(origin==null)throw new IllegalStateException("NATIVE_COMPONENT_DICTIONARY_UNAVAILABLE");
        int worldVersion = IsoWorld.getWorldVersion();
        checkEmbeddedItems(component, origin, worldVersion, Collections.newSetFromMap(new IdentityHashMap<>()));
        int limit = PlayerLimits.SOURCE_BYTES;
        for (int capacity = 4096;;capacity = Math.min(limit, capacity * 2)) {
            ByteBuffer output = ByteBuffer.allocate(capacity);
            try {
                componentMethod(component, "save", new Class<?>[]{ByteBuffer.class}, output);
                byte[] bytes = Arrays.copyOf(output.array(), output.position());
                PayloadProvenance.remember(bytes, new PayloadProvenance.Metadata(origin, worldVersion));
                return bytes;
            } catch (BufferOverflowException overflow) {
                if (capacity == limit) throw new IllegalStateException("NATIVE_COMPONENT_PAYLOAD_TOO_LARGE", overflow);
            }
        }
    }
    private static void checkEmbeddedItems(Object component, DictionaryOrigin origin, int version, Set<Object> seen) {
        if(typeName(component).equals("Resources")){
            for(Object resource:list(call(component,"getResources")))if(typeName(resource).equals("ResourceItem"))
                for(Object item:list(call(resource,"getStoredItems")))checkEmbeddedItem(item,origin,version,seen);
        } else if(typeNames(component).contains("CraftLogic")) {
            for(Object recipe:list(get(component,"craftDataInProgress")))checkRecipeItems(recipe,origin,version,seen);
        } else if(Set.of("FurnaceLogic","DryingLogic","MashingLogic").contains(typeName(component))
                && get(component,"currentRecipe")!=null) {
            checkRecipeItems(get(component,"craftData"),origin,version,seen);
        }
    }
    private static void checkRecipeItems(Object recipe,DictionaryOrigin origin,int version,Set<Object> seen) {
        for(Object input:list(get(recipe,"inputs"))){
            for(Object item:list(get(input,"appliedItems")))checkEmbeddedItem(item,origin,version,seen);
            checkEmbeddedItem(get(input,"mostRecentItem"),origin,version,seen);
        }
    }
    private static void checkEmbeddedItem(Object item,DictionaryOrigin origin,int version,Set<Object> seen) {
        if(item==null)return;
        if(seen.size()>=64||!seen.add(item))throw new IllegalStateException("NATIVE_COMPONENT_INVALID_EMBEDDED_ITEM_GRAPH");
        try {
            Object bytes=get(item,"byteData");
            if(bytes!=null&&!new PayloadProvenance.Metadata(origin,version).equals(PayloadProvenance.metadata(bytes)))
                throw new IllegalStateException("NATIVE_COMPONENT_EMBEDDED_ITEM_PROVENANCE");
            var types=typeNames(item);
            if(types.contains("InventoryContainer"))for(Object child:list(get(get(item,"container"),"items")))
                checkEmbeddedItem(child,origin,version,seen);
            if(types.contains("HandWeapon"))for(Object child:list(get(item,"attachmentList")))
                checkEmbeddedItem(child,origin,version,seen);
            for(int index=0;index<number(get(item,"componentSize"));index++)
                checkEmbeddedItems(call(item,"getComponentForIndex",index),origin,version,seen);
        } finally { seen.remove(item); }
    }
    static void restoreNativeComponent(Object component, byte[] payload) {
        if(!NATIVE.contains(component.getClass().getName()))throw new IllegalStateException("Unknown native component codec");
        loadComponent(component,payload,false);
    }
    static void restoreFallbackComponent(Object value,String type,byte[] payload) {
        if(!(value instanceof Component component)||component.getComponentType().GetComponentClass()!=component.getClass()
                ||!component.getComponentType().name().equals(type))
            throw new IllegalStateException("Native fallback component type mismatch");
        loadComponent(component,payload,true);
    }
    private static void loadComponent(Object component,byte[] payload,boolean sameServer) {
        var metadata = PayloadProvenance.metadata(payload);
        if (payload == null || payload.length > PlayerLimits.SOURCE_BYTES
                || metadata == null || metadata.origin() == null || metadata.worldVersion() == null)
            throw new IllegalStateException("Invalid native component payload");
        var local = DictionaryRuntime.localEncoding();
        if(sameServer&&(local==null||local.serverId()!=metadata.origin().serverId()))
            throw new IllegalStateException("Native fallback component requires its source server");
        ByteBuffer input = ByteBuffer.wrap(payload);
        PayloadProvenance.copy(payload, input);
        try (var scope = DictionaryDecode.enter(input)) {
            componentMethod(component, "load", new Class<?>[]{ByteBuffer.class, int.class}, input, metadata.worldVersion());
            if (input.hasRemaining()) throw new IllegalStateException("Native component did not consume its saved payload");
        }
    }
    private static void componentMethod(Object component, String name, Class<?>[] signature, Object... args) {
        try {
            // Invoking the base declaration dispatches to the concrete or inherited vanilla codec.
            var method = Component.class.getDeclaredMethod(name, signature);
            method.setAccessible(true);
            method.invoke(component, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof BufferOverflowException overflow) throw overflow;
            if(name.equals("save"))throw new IllegalStateException("NATIVE_COMPONENT_SAVE_FAILED",error.getCause());
            throw new IllegalStateException("Native component load failed", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Missing native component " + name, error);
        }
    }
    static Map<String,Object> animal(Object animal,long gameTimeMillis) {
        Objects.requireNonNull(animal,"Missing embedded animal");
        if(animal instanceof zombie.characters.animals.IsoAnimal)try {
            if(animal.getClass().getMethod("save",java.nio.ByteBuffer.class,boolean.class,boolean.class).getDeclaringClass()!=zombie.characters.animals.IsoAnimal.class)
                throw new IllegalStateException("UNSUPPORTED_ANIMAL_WRITER");
        }catch(NoSuchMethodException failure){throw new IllegalStateException("Missing animal writer",failure);}
        Map<String,Object> state=new LinkedHashMap<>();
        fields(state,animal,"x","y","z","type","customName","itemId","animalId","hoursSurvived","attachBackToMother",
            "milkRemoved","stressLevel","health","virtualId","migrationGroup","petTimer","wild","onlineID");
        state.put("direction",key(get(animal,"forwardIsoDirection")));
        Object zone=get(animal,"animalZone");state.put("zoneId",zone==null?null:get(zone,"id").toString());
        Object data=get(animal,"data"),breed=get(data,"breed");state.put("breed",get(breed,"name"));
        state.put("female",get(get(animal,"descriptor"),"isFemale"));
        state.put("savedAtGameTimeMillis",gameTimeMillis);
        Map<String,Object> stats=new LinkedHashMap<>();for(var stat:PlayerSnapshot.stats(animal))stats.put(stat.key(),stat.value());state.put("stats",stats);
        state.put("modData",get(animal,"table"));
        Map<Object,Object> genes=new LinkedHashMap<>();
        for(var entry:map(get(animal,"fullGenome")).entrySet()) {
            Object gene=entry.getValue();Map<String,Object> value=new LinkedHashMap<>();fields(value,gene,"id","name");
            for(String name:List.of("allele1","allele2")){Map<String,Object> allele=new LinkedHashMap<>();fields(allele,get(gene,name),"name","currentValue","trueRatioValue","dominant","geneticDisorder");value.put(name,allele);}
            genes.put(entry.getKey(),value);
        }
        state.put("genome",genes);
        Map<String,Object> values=new LinkedHashMap<>();
        fields(values,data,"age","size","pregnant","canHaveMilk","milkQuantity","maxMilkActual","preferredHutchPosition",
            "fertilizedTime","fertilized","weight","lastPregnancyTime","lastMilkTimer","lastImpregnateTime","clutchSize");
        values.put("pregnantTime",(boolean)get(data,"pregnant")?get(data,"pregnantTime"):null);
        boolean wool=get(breed,"woolType")!=null&&((Number)get(data,"maxWool")).floatValue()>0;
        values.put("woolQty",wool?get(data,"woolQty"):null);
        values.put("eggsToday",number(get(animal,"adef.eggsPerDay"))>0?get(data,"eggsToday"):null);
        state.put("data",values);
        Object mother=get(animal,"mother");state.put("motherAnimalId",mother==null?null:get(mother,"animalId"));
        Object tree=get(data,"attachedTree"),treeLocation=null;
        if(tree!=null)treeLocation=Map.of("x",get(data,"attachedTreeX"),"y",get(data,"attachedTreeY"));
        else if(number(get(animal,"attachBackToTreeX"))!=Integer.MAX_VALUE&&number(get(animal,"attachBackToTreeY"))!=Integer.MAX_VALUE)
            treeLocation=Map.of("x",get(animal,"attachBackToTreeX"),"y",get(animal,"attachBackToTreeY"));
        state.put("attachedTree",treeLocation);
        Object hook=get(animal,"hook"),square=hook==null?null:get(hook,"square");Object location=null;
        if((boolean)get(animal,"onHook")) {
            // load() leaves only pending coordinates until the referenced square/hook is mounted.
            boolean saved=square==null||number(get(animal,"attachBackToHookX"))>0&&number(get(animal,"attachBackToHookY"))>0;
            location=Map.of("x",get(saved?animal:square,saved?"attachBackToHookX":"x"),"y",get(saved?animal:square,saved?"attachBackToHookY":"y"),"z",get(saved?animal:square,saved?"attachBackToHookZ":"z"));
        }
        state.put("hook",location);state.put("playerAcceptance",map(get(animal,"playerAcceptanceList")));
        return state;
    }
    private static void fields(Map<String,Object> state,Object source,String... fields){for(String field:fields)state.put(field,get(source,field));}
    private PlayerCodecs() {}
}
