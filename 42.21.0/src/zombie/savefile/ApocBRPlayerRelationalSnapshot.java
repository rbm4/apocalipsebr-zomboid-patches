package zombie.savefile;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;

/** Copies only export-owned state on the load thread. No game object escapes to the worker. */
final class ApocBRPlayerRelationalSnapshot {
    record Snapshot(Node player,Instant capturedAt,int bytes) {}
    record Stat(String key,Object value) {}
    static final class Node {
        final String runtimeClass;
        final List<String> types;
        private Map<String,Object> fields=new LinkedHashMap<>();
        Node(Object source) {
            runtimeClass=source.getClass().getName();
            ArrayList<String> names=new ArrayList<>();
            for(Class<?> type=source.getClass();type!=null;type=type.getSuperclass())names.add(type.getSimpleName());
            types=List.copyOf(names);
        }
        Object get(String path) {
            if(!fields.containsKey(path))throw new IllegalStateException("Missing snapshot field "+path);
            return fields.get(path);
        }
    }
    // Preserve Lua table identity and kind, including shared/cyclic references for codec rejection.
    static final class Table {
        final String kind;
        private Map<Object,Object> entries=new LinkedHashMap<>();
        Table(String kind){this.kind=kind;}
        Map<Object,Object> entries(){return entries;}
    }
    private static final Set<String> GENERATED=Set.of("snapshot_id","id","character_id","item_id",
            "owner_character_id","owner_item_id","position","body_part_key");
    private final IdentityHashMap<Object,Object> copies=new IdentityHashMap<>();
    private int bytes,nodes;
    private void charge(int amount) {
        if(amount<0||amount>ApocBRPlayerRelationalLimits.SNAPSHOT_BYTES-bytes)throw new ApocBRPlayerRelationalLimits.SizeExceeded("SNAPSHOT_SIZE_BUDGET",(long)bytes+amount,ApocBRPlayerRelationalLimits.SNAPSHOT_BYTES,0,0,nodes);
        bytes+=amount;
        if(++nodes>ApocBRPlayerRelationalLimits.NODES)throw new ApocBRPlayerRelationalLimits.SizeExceeded("SNAPSHOT_SIZE_BUDGET",bytes,ApocBRPlayerRelationalLimits.SNAPSHOT_BYTES,0,0,nodes);
    }
    private Object scalar(Object value) {
        charge(32);
        if(value==null||value instanceof Boolean||value instanceof Byte||value instanceof Short||value instanceof Integer
                ||value instanceof Long||value instanceof Float||value instanceof Double)return value;
        if(value instanceof String text) {
            if(text.length()>65536)throw new IllegalStateException("Oversized text field");
            charge(text.length()*2);return text;
        }
        return scalar(key(value));
    }
    private void put(Node node,String path,Object value){charge(64+path.length()*2);node.fields.put(path,value);}
    private void fields(Node node,Object source,String... paths){for(String path:paths)put(node,path,scalar(get(source,path)));}
    private Node begin(Object source) {
        charge(128);Node node=new Node(source);copies.put(source,node);return node;
    }
    private void project(Node node,Object source,String table,String... excluded) {
        Set<String> skip=new HashSet<>(Arrays.asList(excluded));
        for(var column:ApocBRPlayerRelationalSchema.TABLES.get(table)) {
            if(GENERATED.contains(column.name())||skip.contains(column.name()))continue;
            String path=ApocBRPlayerRelationalCapture.path(table,column.name());
            put(node,path,scalar(get(source,path)));
        }
    }
    private Node projected(Object source,String table,String... excluded) {
        if(source==null)return null;
        Node node=begin(source);project(node,source,table,excluded);return node;
    }
    private Object sequence(Object source,java.util.function.Function<Object,Object> copy) {
        ArrayList<Object> result=new ArrayList<>();
        for(Object value:list(source)){charge(16);result.add(copy.apply(value));}
        return Collections.unmodifiableList(result);
    }
    private Object mapping(Object source,java.util.function.Function<Object,Object> copy) {
        Map<Object,Object> result=new LinkedHashMap<>();
        for(var entry:map(source).entrySet()){charge(64);result.put(scalar(entry.getKey()),copy.apply(entry.getValue()));}
        return Collections.unmodifiableMap(result);
    }
    private Object structured(Object source,int depth) {
        if(depth>64)throw new IllegalStateException("SNAPSHOT_DEPTH_BUDGET");
        if(source==null||source instanceof String||source instanceof Number||source instanceof Boolean)return scalar(source);
        Object existing=copies.get(source);if(existing!=null)return existing;
        if(source instanceof byte[] array){charge(array.length);return array.clone();}
        if(source instanceof List<?> values){
            ArrayList<Object> result=new ArrayList<>();List<Object> view=Collections.unmodifiableList(result);copies.put(source,view);
            if(values.size()>10_000)throw new IllegalStateException("SNAPSHOT_SIZE_BUDGET");
            for(Object value:values){charge(16);result.add(structured(value,depth+1));}return view;
        }
        Table result=new Table(source instanceof Map<?,?>?"object":"table");copies.put(source,result);charge(128);
        if(source instanceof Map<?,?> map) {
            if(map.size()>10_000)throw new IllegalStateException("SNAPSHOT_SIZE_BUDGET");
            for(var entry:map.entrySet()){charge(64);result.entries.put(tableKey(entry.getKey()),structured(entry.getValue(),depth+1));}
        } else {
            Object iterator=call(source,"iterator");int count=0;
            while((boolean)call(iterator,"advance")) {
                if(++count>10_000)throw new IllegalStateException("SNAPSHOT_SIZE_BUDGET");
                charge(64);result.entries.put(tableKey(call(iterator,"getKey")),structured(call(iterator,"getValue"),depth+1));
            }
        }
        result.entries=Collections.unmodifiableMap(result.entries);return result;
    }
    private Object tableKey(Object key) {
        if(key!=null&&!(key instanceof String||key instanceof Number||key instanceof Boolean))throw new IllegalStateException("Unsupported Lua key");
        return scalar(key);
    }
    private Object color(Object source) {
        if(source==null)return null;Node node=begin(source);fields(node,source,"r","g","b");return node;
    }
    private Object visual(Object source,boolean human) {
        if(source==null)return null;Node node=begin(source);
        if(human) {
            project(node,source,"human_visual","hair_r","hair_g","hair_b","beard_r","beard_g","beard_b",
                    "skin_r","skin_g","skin_b","natural_hair_r","natural_hair_g","natural_hair_b",
                    "natural_beard_r","natural_beard_g","natural_beard_b");
            for(String channel:new String[]{"hair","beard","skin","naturalHair","naturalBeard"})put(node,channel+"Color",color(get(source,channel+"Color")));
            put(node,"bodyVisuals",sequence(get(source,"bodyVisuals"),v->visual(v,false)));
        } else {
            project(node,source,"visual","tint_r","tint_g","tint_b","hue");fields(node,source,"hue");put(node,"tint",color(get(source,"tint")));
        }
        for(String path:human?new String[]{"blood","dirt","holes"}:new String[]{"blood","dirt","holes","basicPatches","denimPatches","leatherPatches"})
            put(node,path,sequence(get(source,path),this::scalar));
        return node;
    }
    private Object container(Object source,int depth) {
        if(source==null)return null;
        if(depth>64)throw new IllegalStateException("SNAPSHOT_DEPTH_BUDGET");
        if(copies.containsKey(source))return copies.get(source);
        Node node=projected(source,"container");put(node,"items",sequence(get(source,"items"),v->item(v,depth+1)));return node;
    }
    private Object item(Object source,int depth) {
        if(source==null)return null;
        if(depth>64)throw new IllegalStateException("SNAPSHOT_DEPTH_BUDGET");
        if(copies.containsKey(source))return copies.get(source);
        Node node=projected(source,"item","runtime_class","save_type","recorded_media_id");
        fields(node,source,"id","recordedMediaIndex","componentSize");
        put(node,"resolvedMedia",ApocBRPlayerRelationalCapture.media(get(source,"recordedMediaIndex")));
        put(node,"visual",visual(get(source,"visual"),false));put(node,"table",structured(get(source,"table"),0));
        put(node,"extraItems",sequence(get(source,"extraItems"),this::scalar));
        ByteBuffer data=(ByteBuffer)get(source,"byteData");
        if(data==null)put(node,"byteData",null);
        else {ByteBuffer view=data.duplicate();view.rewind();charge(view.remaining());byte[] copy=new byte[view.remaining()];view.get(copy);put(node,"byteData",copy);}
        put(node,"animalTracks",projected(get(source,"animalTracks"),"animal_track"));
        boolean supported=true;
        if(source instanceof zombie.inventory.InventoryItem)try {
            supported=Set.of("InventoryItem","InventoryContainer","HandWeapon","Food","Clothing","Literature","AlarmClock","AlarmClockClothing","Key","Moveable","MapItem","Radio","AnimalInventoryItem")
                    .contains(source.getClass().getMethod("save",ByteBuffer.class,boolean.class).getDeclaringClass().getSimpleName());
        }catch(NoSuchMethodException error){throw new IllegalStateException("Missing item writer",error);}
        put(node,"supportedWriter",supported);
        for(String type:node.types)switch(type) {
            case "InventoryContainer" -> {fields(node,source,"weightReduction");put(node,"container",container(get(source,"container"),depth+1));}
            case "HandWeapon" -> {project(node,source,"weapon_state");put(node,"attachmentList",sequence(get(source,"attachmentList"),v->attachment(v,depth+1)));}
            case "Food" -> {
                project(node,source,"food_state");put(node,"spices",sequence(get(source,"spices"),this::scalar));
                put(node,"eggGenome",mapping(get(source,"eggGenome"),v->{Node gene=begin(v);fields(gene,v,"id");for(String allele:new String[]{"allele1","allele2"})put(gene,allele,projected(get(v,allele),"egg_allele","gene_key","allele_index"));return gene;}));
            }
            case "Clothing" -> {project(node,source,"clothing_state");put(node,"patches",mapping(get(source,"patches"),v->projected(v,"clothing_patch")));}
            case "Literature" -> {project(node,source,"literature_state");put(node,"customPages",mapping(get(source,"customPages"),this::scalar));put(node,"learnedRecipes",sequence(get(source,"learnedRecipes"),this::scalar));}
            case "AlarmClock","AlarmClockClothing" -> project(node,source,"alarm_state");
            case "Key" -> project(node,source,"key_state");
            case "Moveable" -> project(node,source,"moveable_state");
            case "MapItem" -> {
                fields(node,source,"mapId");
                put(node,"symbols.symbols",sequence(get(source,"symbols.symbols"),v->{
                    Node symbol=begin(v);fields(symbol,v,"isUserDefined");put(symbol,"networkInfo",get(v,"networkInfo")==null?null:true);
                    if(!(boolean)get(v,"isUserDefined")||get(v,"networkInfo")!=null)return symbol;
                    project(symbol,v,"map_symbol","kind","color_r","color_g","color_b","color_a","text","translated","layer_id","symbol_id");fields(symbol,v,"r","g","b","a");
                    fields(symbol,v,symbol.types.contains("WorldMapTextSymbol")?new String[]{"text","translated","layerId"}:new String[]{"symbolId"});return symbol;
                }));
            }
            case "Radio" -> {
                Object device=get(source,"deviceData");Node radio=projected(device,"radio_state","max_presets","recorded_media_id");
                if(radio!=null){fields(radio,device,"mediaIndex");put(radio,"resolvedMedia",ApocBRPlayerRelationalCapture.media(get(device,"mediaIndex")));
                    Object presets=get(device,"presets");Node p=presets==null?null:begin(presets);
                    if(p!=null){fields(p,presets,"maxPresets");put(p,"presets",sequence(get(presets,"presets"),v->projected(v,"radio_preset")));}put(radio,"presets",p);}
                put(node,"deviceData",radio);
            }
            default -> { }
        }
        ArrayList<Object> components=new ArrayList<>();
        for(int i=0;i<number(get(source,"componentSize"));i++) {
            Object component=call(source,"getComponentForIndex",i);Node c=begin(component);
            switch(component.getClass().getSimpleName()) {
                case "AttributeContainer","EntityScriptInfo" -> put(c,"state",structured(componentState(component),0));
                case "Durability" -> {fields(c,component,"currentHitPoints","maxHitPoints");put(c,"material",scalar(get(component,"material").toString()));}
                case "FluidContainer" -> {
                    fields(c,component,"capacity","inputLocked","canPlayerEmpty","containerName","hiddenAmount","rainCatcher");
                    put(c,"fluids",sequence(get(component,"fluids"),v->{Node f=begin(v);fields(f,v,"amount");Node type=begin(get(v,"fluid"));fields(type,get(v,"fluid"),"fluidTypeString");put(f,"fluid",type);put(f,"color",color(get(v,"color")));return f;}));
                    for(String filter:new String[]{"whitelist","blacklist"}) {
                        Object value=get(component,filter);Node f=value==null?null:begin(value);
                        if(f!=null){fields(f,value,"filterType");for(String path:new String[]{"fluidEnums","fluidStrings","categories"})put(f,path,sequence(get(value,path),this::scalar));}put(c,filter,f);
                    }
                }
                default -> { }
            }
            components.add(c);
        }
        put(node,"components",List.copyOf(components));return node;
    }
    private Object attachment(Object source,int depth) {
        Object result=item(source,depth);if(result instanceof Node node)fields(node,source,"partType");return result;
    }
    /** Native saved fields only. Called before enqueue; the worker reads the copied state. */
    static Map<String,Object> componentState(Object component) {
        if(component instanceof Node){throw new IllegalArgumentException("Snapshot state is already detached");}
        var budget=new ApocBRPlayerRelationalSnapshot();
        Map<String,Object> state=new LinkedHashMap<>();
        if(typeName(component).equals("EntityScriptInfo")) {
            state.put("originalIsItem",budget.scalar((Boolean)Objects.requireNonNull(get(component,"originalIsItem"))));
            state.put("originalScript",budget.scalar((String)get(component,"originalScript")));
            return state;
        }
        if(!typeName(component).equals("AttributeContainer"))throw new IllegalStateException("UNSUPPORTED_COMPONENT_STATE");
        int size=number(get(component,"size"));
        if(size<0||size>10_000)throw new IllegalStateException("SNAPSHOT_SIZE_BUDGET");
        List<Object> attributes=new ArrayList<>();Set<String> names=new HashSet<>();Set<Integer> ids=new HashSet<>();
        for(int i=0;i<size;i++) {
            budget.charge(512);
            Object attribute=call(component,"getAttribute",i),type=get(attribute,"type");
            String name=(String)get(type,"name"),kind=key(get(type,"valueType"));int id=number(get(type,"id"));
            if(name==null||!names.add(name)||!ids.add(id))throw new IllegalStateException("INVALID_ATTRIBUTE_IDENTITY");
            Object value=get(attribute,"value");
            Map<String,Object> entry=new LinkedHashMap<>();
            entry.put("name",budget.scalar(name));entry.put("native_id",id);entry.put("value_type",budget.scalar(kind));
            Object saved=switch(kind) {
                case "Boolean" -> (Boolean)Objects.requireNonNull(value);
                case "String" -> (String)value;
                case "Byte" -> (Byte)Objects.requireNonNull(value);
                case "Short" -> (Short)Objects.requireNonNull(value);
                case "Int" -> (Integer)Objects.requireNonNull(value);
                case "Long" -> (Long)Objects.requireNonNull(value);
                case "Float" -> (Float)Objects.requireNonNull(value);
                case "Double" -> (Double)Objects.requireNonNull(value);
                case "Enum" -> enumValue(value,budget);
                case "EnumSet" -> enumValues(value,budget);
                case "EnumStringSet" -> {
                    Map<String,Object> values=new LinkedHashMap<>();
                    values.put("enums",enumValues(get(value,"enumValues"),budget));
                    ArrayList<String> strings=new ArrayList<>();
                    for(Object text:list(get(value,"stringValues")))strings.add((String)budget.scalar((String)Objects.requireNonNull(text)));
                    values.put("strings",strings);yield values;
                }
                default -> throw new IllegalStateException("UNSUPPORTED_ATTRIBUTE_VALUE_TYPE");
            };
            if(kind.startsWith("Enum"))entry.put("enum_class",budget.scalar(((Class<?>)get(type,"enumClass")).getName()));
            else budget.scalar(saved);
            entry.put("value",saved);attributes.add(entry);
        }
        state.put("attributes",attributes);return state;
    }
    private static Map<String,Object> enumValue(Object value,ApocBRPlayerRelationalSnapshot budget) {
        budget.charge(256);
        Enum<?> option=(Enum<?>)Objects.requireNonNull(value);
        return Map.of("name",budget.scalar(option.name()),"native_byte_id",number(call(option,"getByteId")));
    }
    private static List<Object> enumValues(Object value,ApocBRPlayerRelationalSnapshot budget) {
        ArrayList<Object> values=new ArrayList<>();
        for(Object option:list(Objects.requireNonNull(value)))values.add(enumValue(option,budget));
        return values;
    }
    static List<Stat> stats(Object player) {
        if(player instanceof Node node){@SuppressWarnings("unchecked") List<Stat> stats=(List<Stat>)node.get("stats");return stats;}
        try {
            ArrayList<Stat> result=new ArrayList<>();Object stats=get(player,"stats");
            Class<?> stat=Class.forName("zombie.characters.CharacterStat");
            for(Object type:list(stat.getField("ORDERED_STATS").get(null)))result.add(new Stat(key(get(type,"id")),call(stats,"get",type)));
            return List.copyOf(result);
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    static Snapshot copy(Object player) {
        var copy=new ApocBRPlayerRelationalSnapshot();Instant captured=Instant.now();Node p=copy.begin(player);
        copy.project(p,player,"character_state","inventory_container_id","source_offset_x","source_offset_y");
        copy.project(p,player,"character_effects");copy.project(p,player,"character_presentation","tag_r","tag_g","tag_b");
        copy.fields(p,player,"tagColor.r","tagColor.g","tagColor.b","savedVehicleSeat");
        if(number(get(player,"savedVehicleSeat"))>=0)copy.fields(p,player,"savedVehicleX","savedVehicleY","savedVehicleRunning");
        copy.put(p,"inventory",copy.container(get(player,"inventory"),0));
        Object descriptor=get(player,"descriptor");Node desc=copy.projected(descriptor,"character_descriptor");
        if(desc!=null){copy.put(desc,"extra",copy.sequence(get(descriptor,"extra"),copy::scalar));copy.put(desc,"XPBoostMap",copy.mapping(get(descriptor,"XPBoostMap"),copy::scalar));}copy.put(p,"descriptor",desc);
        copy.put(p,"nutrition",copy.projected(get(player,"nutrition"),"character_nutrition"));
        ArrayList<Stat> stats=new ArrayList<>();for(Stat stat:stats(player))stats.add(new Stat((String)copy.scalar(stat.key()),copy.scalar(stat.value())));copy.put(p,"stats",List.copyOf(stats));
        for(String path:new String[]{"characterTraits.knownTraits","knownRecipes","alreadyReadBook","readPrintMedia","knownMediaLines","cheats.cheats"})copy.put(p,path,copy.sequence(get(player,path),copy::scalar));
        for(String path:new String[]{"readLiterature","mechanicsItem","xp.xpMap"})copy.put(p,path,copy.mapping(get(player,path),copy::scalar));
        copy.put(p,"xp.xpMapMultiplier",copy.mapping(get(player,"xp.xpMapMultiplier"),v->{Node n=copy.begin(v);copy.fields(n,v,"multiplier","minLevel","maxLevel");return n;}));
        copy.put(p,"perkList",copy.sequence(get(player,"perkList"),v->{Node n=copy.begin(v);copy.fields(n,v,"perk","level");return n;}));
        copy.put(p,"readBooks",copy.sequence(get(player,"readBooks"),v->copy.projected(v,"character_book_progress")));
        copy.put(p,"craftHistory.craftHistory",copy.mapping(get(player,"craftHistory.craftHistory"),v->copy.projected(v,"character_craft_history","recipe_key")));
        Object fitness=get(player,"fitness");Node fit=copy.begin(fitness);
        for(String path:new String[]{"stiffnessIncMap","stiffnessTimerMap","regularityMap","exeTimer"})copy.put(fit,path,copy.mapping(get(fitness,path),copy::scalar));
        copy.put(fit,"bodypartToIncStiffness",copy.sequence(get(fitness,"bodypartToIncStiffness"),copy::scalar));copy.put(p,"fitness",fit);
        for(String flag:new String[]{"UnlimitedCarry","BuildCheat","HealthCheat","MechanicsCheat","MovablesCheat","FarmingCheat","FishingCheat","CanUseBrushTool","FastMoveCheat","TimedActionInstantCheat","UnlimitedEndurance","UnlimitedAmmo","KnowAllRecipes"})copy.fields(p,player,Character.toLowerCase(flag.charAt(0))+flag.substring(1));
        Object damage=get(player,"bodyDamage");Node body=copy.projected(damage,"body_damage");
        copy.put(body,"bodyParts",copy.sequence(get(damage,"bodyParts"),v->{Node n=copy.projected(v,"body_part");copy.fields(n,v,"type");return n;}));
        Object thermo=get(damage,"thermoregulator");Node therm=copy.projected(thermo,"thermoregulator");
        if(therm!=null)copy.put(therm,"nodes",copy.sequence(get(thermo,"nodes"),v->{Node n=copy.projected(v,"thermal_node");copy.fields(n,v,"bodyPartType");return n;}));copy.put(body,"thermoregulator",therm);copy.put(p,"bodyDamage",body);
        copy.put(p,"visual",copy.visual(get(player,"visual"),true));
        for(String hand:new String[]{"primaryHandItem","secondaryHandItem"})copy.put(p,hand,copy.item(get(player,hand),0));
        for(String path:new String[]{"wornItems","attachedItems"})copy.put(p,path,copy.sequence(get(player,path),v->{Node n=copy.begin(v);copy.fields(n,v,"location");copy.put(n,"item",copy.item(get(v,"item"),0));return n;}));
        copy.put(p,"table",copy.structured(get(player,"table"),0));
        for(Object value:copy.copies.values())if(value instanceof Node node)node.fields=Collections.unmodifiableMap(node.fields);
        return new Snapshot(p,captured,copy.bytes);
    }
    private ApocBRPlayerRelationalSnapshot() {}
}
