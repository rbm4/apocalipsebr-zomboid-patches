package zombie.savefile;

import java.util.*;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;

/** Reviewed 42.21.0 native save contracts. Temporary native reads are detached by Snapshot. */
final class ApocBRPlayerRelationalCodecs {
    private static final Map<String,String> EMPTY=Map.of(
        "LuaComponent","zombie.entity.components.lua.LuaComponent",
        "Parts","zombie.entity.components.parts.Parts",
        "Signals","zombie.entity.components.signals.Signals",
        "TestComponent","zombie.entity.components.test.TestComponent",
        "CraftRecipeComponent","zombie.entity.components.crafting.CraftRecipeComponent",
        "ContextMenuConfig","zombie.entity.components.contextmenuconfig.ContextMenuConfig",
        "CraftBenchSounds","zombie.entity.components.sounds.CraftBenchSounds",
        "WallCoveringConfig","zombie.entity.components.crafting.WallCoveringConfig");
    static boolean supports(Object component) {
        String name=component instanceof ApocBRPlayerRelationalSnapshot.Node node?node.runtimeClass:component.getClass().getName();
        return EMPTY.containsValue(name)||name.equals("zombie.entity.components.ui.UiConfig")||name.equals("zombie.entity.components.crafting.CraftBench");
    }
    static Map<String,Object> component(Object component) {
        if(!supports(component))throw new IllegalArgumentException("UNSUPPORTED_COMPONENT_STATE");
        Map<String,Object> state=new LinkedHashMap<>();
        switch(typeName(component)) {
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
        Map<String,Object> stats=new LinkedHashMap<>();for(var stat:ApocBRPlayerRelationalSnapshot.stats(animal))stats.put(stat.key(),stat.value());state.put("stats",stats);
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
    private ApocBRPlayerRelationalCodecs() {}
}
