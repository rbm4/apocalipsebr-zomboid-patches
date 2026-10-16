package zombie.savefile;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;

/** Relational graph projection. Production calls this on the worker with a detached snapshot. */
final class ApocBRPlayerRelationalCapture {
    static final int CODEC_VERSION=4, SCHEMA_VERSION=1, MAX_BYTES=ApocBRPlayerRelationalLimits.GRAPH_BYTES;
    private static final AtomicLong equipmentRefsSkipped=new AtomicLong(),lastEquipmentWarning=new AtomicLong();
    static long skippedEquipmentReferences(){return equipmentRefsSkipped.get();}
    private static final AtomicLong duplicateItemsSkipped=new AtomicLong(),lastDuplicateWarning=new AtomicLong();
    static long skippedDuplicateItems(){return duplicateItemsSkipped.get();}
    record Row(String table,Map<String,Object> values) {
        Row { values=Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    }
    record Graph(UUID snapshot,UUID character,Instant capturedAt,List<Row> rows,List<String> issues,int bytes,int objects,int items,int containers,int edges) {
        Graph { rows=List.copyOf(rows); issues=List.copyOf(issues); }
    }
    private final UUID snapshot=UUID.randomUUID(), character;
    private final Instant captured=Instant.now();
    private final ArrayList<Row> rows=new ArrayList<>();
    private final ArrayList<String> issues=new ArrayList<>();
    private final IdentityHashMap<Object,UUID> itemIds=new IdentityHashMap<>();
    private final Set<Integer> nativeIds=new HashSet<>();
    private final Set<Object> containersSeen=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> tableStack=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer,UUID> aliases;
    private final LongSupplier clock;
    private final long started,budgetNanos;
    private int bytes,objects,containers,edges,nodes;

    static final class TimeBudgetExceeded extends IllegalStateException {
        final long elapsedNanos,budgetNanos;
        final int rows,items,bytes;
        TimeBudgetExceeded(long elapsedNanos,long budgetNanos,int rows,int items,int bytes) {
            super("CAPTURE_TIME_BUDGET");
            this.elapsedNanos=elapsedNanos;this.budgetNanos=budgetNanos;
            this.rows=rows;this.items=items;this.bytes=bytes;
        }
        String diagnostic() {
            return "CAPTURE_TIME_BUDGET; elapsedMs="+TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
                    +"; budgetMs="+TimeUnit.NANOSECONDS.toMillis(budgetNanos)
                    +"; rows="+rows+"; items="+items+"; bytes="+bytes;
        }
    }

    private static final Map<String,Map<String,String>> BINDINGS=new HashMap<>();
    static {
        bind("character_state","last_hour_slept=lastHourSleeped","total_xp=xp.totalXp","xp_level=xp.level","xp_last_level=xp.lastlevel","direction=forwardIsoDirection","is_dead=isDead","source_offset_x=offsetX","source_offset_y=offsetY");
        bind("character_descriptor","native_descriptor_id=id","gender=characterGender","profession_key=characterProfession");
        bind("character_presentation","tag_r=tagColor.r","tag_g=tagColor.g","tag_b=tagColor.b");
        bind("item","native_id=id","full_type=getFullType","source_registry_id=registryId","remote_control_id=remoteControlId","recorded_media_id=recordedMediaIndex","source_recorded_media_index=recordedMediaIndex","texture_name=texture.name","initialised=isInitialised","color_r=col.r","color_g=col.g","color_b=col.b","color_a=col.a");
        bind("container","native_id=id","has_been_looted=hasBeenLooted");
        bind("body_damage","has_a_cold=hasACold");
        bind("body_part","fake_infected=IsFakeInfected","infected=IsInfected","cut=isCut","have_bullet=haveBullet");
        bind("clothing_patch","tailor_lvl=tailorLvl");
        bind("weapon_state","jammed=isJammed");
        bind("food_state","frozen=isFrozen","fertilized=isFertilized");
        bind("radio_state","mic_muted=micIsMuted","portable=isPortable","television=isTelevision","high_tier=isHighTier","turned_on=isTurnedOn","battery_powered=isBatteryPowered","source_media_index=mediaIndex","recorded_media_id=mediaIndex");
        bind("animal_track","direction=dir");
    }
    private static void bind(String table,String... mappings) {
        Map<String,String> map=BINDINGS.computeIfAbsent(table,k->new HashMap<>());
        for(String mapping:mappings) { String[] pair=mapping.split("=",2); map.put(pair[0],pair[1]); }
    }
    static void prepare() {
        if(ApocBRPlayerRelationalSchema.TABLES.isEmpty()) throw new IllegalStateException("Missing capture schema");
    }
    static String path(String table,String column) {
        return BINDINGS.getOrDefault(table,Map.of()).getOrDefault(column,camel(column));
    }
    ApocBRPlayerRelationalCapture(UUID character,Map<Integer,UUID> aliases,long budgetNanos) {
        this(character,aliases,budgetNanos,System::nanoTime);
    }
    ApocBRPlayerRelationalCapture(UUID character,Map<Integer,UUID> aliases,long budgetNanos,LongSupplier clock) {
        this.character=character; this.aliases=Map.copyOf(aliases);
        this.clock=clock;this.budgetNanos=budgetNanos;started=clock.getAsLong();
    }
    Graph capture(ApocBRPlayerRelationalSnapshot.Snapshot source,List<String> loadIssues) {
        Graph graph=capture(source.player(),loadIssues);
        return new Graph(graph.snapshot(),graph.character(),source.capturedAt(),graph.rows(),graph.issues(),graph.bytes(),graph.objects(),graph.items(),graph.containers(),graph.edges());
    }
    Graph capture(Object player,List<String> loadIssues) {
        issues.addAll(loadIssues); object(character,"character");
        UUID inventory=UUID.nameUUIDFromBytes((character+":inventory").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        container(get(player,"inventory"),inventory,character,null,0);
        project("character_state",player,"character_id",character,"inventory_container_id",inventory);
        Object descriptor=get(player,"descriptor");
        if(descriptor!=null) {
            project("character_descriptor",descriptor,"character_id",character);
            sequence("descriptor_extra","value",get(descriptor,"extra"));
        }
        project("character_effects",player,"character_id",character);
        project("character_presentation",player,"character_id",character,
                "tag_r",get(player,"tagColor.r"),"tag_g",get(player,"tagColor.g"),"tag_b",get(player,"tagColor.b"));
        int seat=number(get(player,"savedVehicleSeat"));
        if(seat>=0) row("character_vehicle_return","character_id",character,"x",get(player,"savedVehicleX"),"y",get(player,"savedVehicleY"),"seat",seat,"running",get(player,"savedVehicleRunning"));
        project("character_nutrition",get(player,"nutrition"),"character_id",character);
        for(var stat:ApocBRPlayerRelationalSnapshot.stats(player))
            row("character_stat","character_id",character,"stat_key",stat.key(),"value",stat.value());
        for(Object trait:list(get(player,"characterTraits.knownTraits"))) row("character_trait","character_id",character,"trait_key",key(trait));
        perks(player,descriptor);
        sequence("character_recipe","recipe_key",get(player,"knownRecipes"));
        sequence("character_read_book","full_type",get(player,"alreadyReadBook"));
        sequence("character_print_media","media_id",get(player,"readPrintMedia"));
        sequence("character_media_line","line_guid",get(player,"knownMediaLines"));
        int position=0;
        for(Object book:list(get(player,"readBooks"))) project("character_book_progress",book,"character_id",character,"position",position++);
        for(var e:map(get(player,"readLiterature")).entrySet()) row("character_literature","character_id",character,"title",e.getKey(),"day",e.getValue());
        for(var e:map(get(player,"mechanicsItem")).entrySet()) row("character_mechanics","character_id",character,"native_item_key",e.getKey(),"native_value",e.getValue());
        for(var e:map(get(player,"craftHistory.craftHistory")).entrySet()) project("character_craft_history",e.getValue(),"character_id",character,"recipe_key",e.getKey());
        Object fitness=get(player,"fitness");
        for(String name:new String[]{"stiffnessIncMap","stiffnessTimerMap","regularityMap","exeTimer"}) for(var e:map(get(fitness,name)).entrySet()) {
            boolean integral=name.equals("stiffnessTimerMap")||name.equals("exeTimer");
            row("fitness_value","character_id",character,"map_name",name,"key",e.getKey(),"real_value",integral?null:e.getValue(),"integer_value",integral?e.getValue():null);
        }
        sequence("fitness_body_part","body_part_key",get(fitness,"bodypartToIncStiffness"));
        for(String flag:new String[]{"UnlimitedCarry","BuildCheat","HealthCheat","MechanicsCheat","MovablesCheat","FarmingCheat","FishingCheat","CanUseBrushTool","FastMoveCheat","TimedActionInstantCheat","UnlimitedEndurance","UnlimitedAmmo","KnowAllRecipes"}) {
            Object enabled=get(player,Character.toLowerCase(flag.charAt(0))+flag.substring(1));
            row("character_cheat","character_id",character,"cheat_key","legacy."+flag,"enabled",enabled);
        }
        int extraFlags=number(get(player,"extraInfoFlags"));int bit=1;
        for(String flag:List.of("GodMod","GhostMode","Invisible","NoClip","ShowAdminTag","CanHearAll")) {row("character_cheat","character_id",character,"cheat_key","extra."+flag,"enabled",(extraFlags&bit)!=0);bit<<=1;}
        for(Object cheat:list(get(player,"cheats.cheats"))) row("character_cheat","character_id",character,"cheat_key","set."+key(cheat),"enabled",true);
        health(get(player,"bodyDamage")); human(get(player,"visual"));
        equipment(player); lua(character,get(player,"table"));
        check();
        return new Graph(snapshot,character,captured,rows,issues,bytes,objects,itemIds.size(),containers,edges);
    }
    private void perks(Object player,Object descriptor) {
        Map<String,Map<String,Object>> values=new LinkedHashMap<>();
        for(var e:map(get(player,"xp.xpMap")).entrySet()) values.computeIfAbsent(key(e.getKey()),k->new HashMap<>()).put("xp",e.getValue());
        for(Object perk:list(get(player,"perkList"))) values.computeIfAbsent(key(get(perk,"perk")),k->new HashMap<>()).put("level",get(perk,"level"));
        if(descriptor!=null) for(var e:map(get(descriptor,"XPBoostMap")).entrySet()) values.computeIfAbsent(key(e.getKey()),k->new HashMap<>()).put("descriptor_xp_boost",e.getValue());
        for(var e:map(get(player,"xp.xpMapMultiplier")).entrySet()) {
            var value=values.computeIfAbsent(key(e.getKey()),k->new HashMap<>());
            value.put("multiplier",get(e.getValue(),"multiplier")); value.put("multiplier_min_level",get(e.getValue(),"minLevel")); value.put("multiplier_max_level",get(e.getValue(),"maxLevel"));
        }
        for(var e:values.entrySet()) row("character_perk","character_id",character,"perk_key",e.getKey(),"xp",e.getValue().get("xp"),"level",e.getValue().get("level"),"descriptor_xp_boost",e.getValue().get("descriptor_xp_boost"),"multiplier",e.getValue().get("multiplier"),"multiplier_min_level",e.getValue().get("multiplier_min_level"),"multiplier_max_level",e.getValue().get("multiplier_max_level"));
    }
    private void health(Object damage) {
        project("body_damage",damage,"character_id",character);
        for(Object part:list(get(damage,"bodyParts"))) project("body_part",part,"character_id",character,"body_part_key",key(get(part,"type")));
        Object thermo=get(damage,"thermoregulator");
        if(thermo!=null) {
            project("thermoregulator",thermo,"character_id",character);
            for(Object node:list(get(thermo,"nodes"))) project("thermal_node",node,"character_id",character,"body_part_key",key(get(node,"bodyPartType")));
        }
    }
    private void human(Object visual) {
        LinkedHashMap<String,Object> colors=new LinkedHashMap<>();
        for(String channel:new String[]{"hair","beard","skin","naturalHair","naturalBeard"}) {
            Object color=get(visual,channel+"Color"); String sql=channel.replace("natural","natural_");
            sql=sql.toLowerCase(java.util.Locale.ROOT);
            for(String axis:new String[]{"r","g","b"}) colors.put(sql+"_"+axis,color==null?null:colorByte(get(color,axis)));
        }
        colors.put("character_id",character); projectMap("human_visual",visual,colors);
        bodyVisual(visual,character,null);
        int pos=0; for(Object itemVisual:list(get(visual,"bodyVisuals"))) visual(itemVisual,character,null,pos++);
    }
    private void visual(Object visual,UUID ownerCharacter,UUID ownerItem,int position) {
        if(visual==null) return;
        UUID id=ApocBRPlayerRelationalDatabase.named("visual:"+(ownerCharacter!=null?ownerCharacter:ownerItem)+":"+position); object(id,"visual"); Object tint=get(visual,"tint");
        Object hue=get(visual,"hue"); if(hue instanceof Number n && !Double.isFinite(n.doubleValue())) hue=null;
        project("visual",visual,"id",id,"owner_character_id",ownerCharacter,"owner_item_id",ownerItem,"position",position,
                "tint_r",tint==null?null:colorByte(get(tint,"r")),"tint_g",tint==null?null:colorByte(get(tint,"g")),"tint_b",tint==null?null:colorByte(get(tint,"b")),"hue",hue);
        bodyVisual(visual,null,id);
    }
    private void bodyVisual(Object visual,UUID ownerCharacter,UUID visualId) {
        for(String name:ownerCharacter!=null?new String[]{"blood","dirt","holes"}:new String[]{"blood","dirt","holes","basicPatches","denimPatches","leatherPatches"}) {
            Object array=get(visual,name); int position=0;
            for(Object value:list(array)) {
                String part=bodyName(position++,true);
                row("visual_body_value","character_id",ownerCharacter,"visual_id",visualId,"channel",name,"body_part_key",part,"value",number(value)&255);
            }
        }
    }
    private static String bodyName(int index,boolean blood) {
        try {
            Class<?> type=Class.forName(blood?"zombie.characterTextures.BloodBodyPartType":"zombie.characters.BodyDamage.BodyPartType");
            return key(type.getMethod("FromIndex",int.class).invoke(null,index));
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Body part codec",e); }
    }
    private void equipment(Object player) {
        equipped("hand","primary",get(player,"primaryHandItem"),0); equipped("hand","secondary",get(player,"secondaryHandItem"),1);
        int pos=0;
        for(Object worn:list(get(player,"wornItems"))) equipped("worn",key(get(worn,"location")),get(worn,"item"),pos++);
        pos=0; for(Object attached:list(get(player,"attachedItems"))) equipped("attached",Objects.toString(get(attached,"location")),get(attached,"item"),pos++);
    }
    private void equipped(String kind,String slot,Object item,int position) {
        if(item==null) return; UUID id=itemIds.get(item);
        if(id==null) {
            // Native IsoPlayer.save writes indexOf(savedInventoryItems), i.e. -1 for
            // unreachable hand/worn pointers; load ignores that link. Never invent
            // ownership, or bind a different object just because its native ID matches.
            equipmentRefsSkipped.incrementAndGet();
            long now=System.nanoTime(),last=lastEquipmentWarning.get();
            if(zombie.network.GameServer.server&&(last==0||now-last>=30_000_000_000L)&&lastEquipmentWarning.compareAndSet(last,now)) {
                int nativeId=number(get(item,"id"));
                String safeSlot=slot!=null&&slot.matches("[A-Za-z0-9_:.-]{1,100}")?slot:"CUSTOM_SLOT";
                zombie.debug.DebugLog.log("[ApocBR][PlayerRelational] EQUIPMENT_REFERENCE_SKIPPED; kind="+kind+"; slot="+safeSlot
                        +"; nativeItemId="+nativeId+"; sameNativeIdOwned="+nativeIds.contains(nativeId));
            }
            return;
        }
        row("equipment","character_id",character,"kind",kind,"slot",slot,"item_id",id,"position",position); edges++;
    }
    private void container(Object container,UUID id,UUID ownerCharacter,UUID ownerItem,int depth) {
        check(); if(depth>64 || !containersSeen.add(container)) throw new IllegalStateException("Cyclic or shared inventory container");
        object(id,"container"); containers++;
        project("container",container,"id",id,"owner_character_id",ownerCharacter,"owner_item_id",ownerItem);
        int position=0; for(Object item:list(get(container,"items"))) item(item,id,null,position++,depth+1);
    }
    private void item(Object item,UUID container,UUID parent,int position,int depth) {
        check(); if(item==null || depth>64 || itemIds.size()>=10_000) throw new IllegalStateException("Invalid/oversized item graph");
        if(itemIds.containsKey(item)) throw new IllegalStateException("Cyclic or duplicate owned item");
        int nativeId=number(get(item,"id"));
        if(!nativeIds.add(nativeId)) {
            // Match vanilla ItemContainer.AddItem's keep-existing/reject-incoming rule.
            // Projection order is inventory order, descending into bags/attachments in place.
            // Do not alias this distinct object to the retained item, overwrite its state,
            // or traverse a rejected bag/weapon's children into the retained ownership tree.
            duplicateItemsSkipped.incrementAndGet();
            long now=System.nanoTime(),last=lastDuplicateWarning.get();
            if(zombie.network.GameServer.server&&(last==0||now-last>=30_000_000_000L)&&lastDuplicateWarning.compareAndSet(last,now))
                zombie.debug.DebugLog.log("[ApocBR][PlayerRelational] DUPLICATE_NATIVE_ITEM_SKIPPED; nativeItemId="+nativeId
                        +"; relation="+(parent==null?"container":"weapon_attachment")+"; position="+position+"; depth="+depth);
            return;
        }
        UUID id=aliases.getOrDefault(nativeId,ApocBRPlayerRelationalDatabase.named("item:"+character+":"+nativeId)); itemIds.put(item,id); object(id,"item");
        row("item_location","item_id",id,"container_id",container,"parent_item_id",parent,"relation",parent==null?"container":"weapon_attachment","position",position,"attachment_slot",parent==null?null:key(get(item,"partType"))); edges++;
        String media=resolvedMedia(item,"recordedMediaIndex");
        project("item",item,"id",id,"runtime_class",item instanceof ApocBRPlayerRelationalSnapshot.Node n?n.runtimeClass:item.getClass().getName(),"recorded_media_id",media,"save_type",-1);
        visual(get(item,"visual"),null,id,0); lua(id,get(item,"table"));
        int extra=0; for(Object fullType:list(get(item,"extraItems"))) row("item_extra","item_id",id,"position",extra++,"full_type",fullType);
        Object byteData=get(item,"byteData");
        if(byteData!=null) {
            ByteBuffer source=byteData instanceof byte[] array?ByteBuffer.wrap(array):((ByteBuffer)byteData).duplicate(); source.rewind();
            if(source.remaining()>MAX_BYTES) throw new IllegalStateException("Oversized byteData");
            byte[] copy=new byte[source.remaining()]; source.get(copy); ApocBRPayloadProvenance.copy(byteData,copy); componentValue(id,"item.byteData",copy,"complete");
        }
        Object track=get(item,"animalTracks"); if(track!=null) project("animal_track",track,"item_id",id);
        if(item instanceof ApocBRPlayerRelationalSnapshot.Node n) {
            if(!(boolean)n.get("supportedWriter")){componentValue(id,"item.unknownSubtype",null,"unsupported");issue("UNSUPPORTED_ITEM_SUBTYPE");}
        } else if(item instanceof zombie.inventory.InventoryItem) try {
            String writer=item.getClass().getMethod("save",ByteBuffer.class,boolean.class).getDeclaringClass().getSimpleName();
            if(!Set.of("InventoryItem","InventoryContainer","HandWeapon","Food","Clothing","Literature","AlarmClock","AlarmClockClothing","Key","Moveable","MapItem","Radio","AnimalInventoryItem").contains(writer)) {
                componentValue(id,"item.unknownSubtype",null,"unsupported");issue("UNSUPPORTED_ITEM_SUBTYPE");
            }
        } catch(NoSuchMethodException error) { throw new IllegalStateException("Missing item writer",error); }
        for(String type:typeNames(item)) switch(type) {
            case "InventoryContainer" -> {
                UUID bag=UUID.nameUUIDFromBytes((id+":inventory").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                row("bag_state","item_id",id,"container_id",bag,"weight_reduction",get(item,"weightReduction"));
                container(get(item,"container"),bag,null,id,depth+1);
            }
            case "HandWeapon" -> {
                project("weapon_state",item,"item_id",id); int index=0;
                for(Object attachment:list(get(item,"attachmentList"))) item(attachment,null,id,index++,depth+1);
            }
            case "Food" -> food(item,id);
            case "Clothing" -> {
                project("clothing_state",item,"item_id",id);
                for(var e:map(get(item,"patches")).entrySet()) project("clothing_patch",e.getValue(),"item_id",id,"body_part_key",bodyName(number(e.getKey()),true));
            }
            case "Literature" -> {
                project("literature_state",item,"item_id",id); int index=0;
                for(var e:map(get(item,"customPages")).entrySet()) row("literature_page","item_id",id,"page_key",e.getKey(),"position",index++,"text",e.getValue());
                index=0; for(Object recipe:list(get(item,"learnedRecipes"))) row("literature_recipe","item_id",id,"position",index++,"recipe_key",recipe);
            }
            case "AlarmClock", "AlarmClockClothing" -> project("alarm_state",item,"item_id",id);
            case "Key" -> project("key_state",item,"item_id",id);
            case "Moveable" -> project("moveable_state",item,"item_id",id);
            case "MapItem" -> mapItem(item,id);
            case "Radio" -> radio(get(item,"deviceData"),id);
            case "AnimalInventoryItem" -> componentValue(id,"item.embeddedAnimal",item instanceof ApocBRPlayerRelationalSnapshot.Node node?node.get("animalState"):ApocBRPlayerRelationalCodecs.animal(get(item,"animal"),((Number)get(item,"animal.timeSinceLastUpdate")).longValue()),"complete");
            default -> { }
        }
        int count=number(get(item,"componentSize"));
        for(int index=0;index<count;index++) {
            Object c=call(item,"getComponentForIndex",index); String kind=typeName(c);
            if(kind.equals("FluidContainer")) fluid(c,id);
            else if(kind.equals("AttributeContainer")||kind.equals("EntityScriptInfo")) {
                Object state=c instanceof ApocBRPlayerRelationalSnapshot.Node node?node.get("state"):ApocBRPlayerRelationalSnapshot.componentState(c);
                componentValue(id,"entity."+kind,state,"complete");
            }
            else if(kind.equals("Durability")) {
                Map<String,Object> value=new LinkedHashMap<>();
                value.put("currentHitPoints",get(c,"currentHitPoints"));value.put("maxHitPoints",get(c,"maxHitPoints"));value.put("material",get(c,"material").toString());
                componentValue(id,"entity.Durability",value,"complete");
            }
            else if(ApocBRPlayerRelationalCodecs.supports(c))componentValue(id,"entity."+kind,c instanceof ApocBRPlayerRelationalSnapshot.Node node?node.get("state"):ApocBRPlayerRelationalCodecs.component(c),"complete");
            else { componentValue(id,"entity."+kind,null,"unsupported"); issue("UNSUPPORTED_COMPONENT_"+kind); }
        }
    }
    private void food(Object food,UUID id) {
        project("food_state",food,"item_id",id); int position=0;
        for(Object spice:list(get(food,"spices"))) row("food_spice","item_id",id,"position",position++,"full_type",spice);
        for(var e:map(get(food,"eggGenome")).entrySet()) {
            String gene=e.getKey().toString(); Object value=e.getValue(); row("egg_gene","item_id",id,"gene_key",gene,"native_gene_id",get(value,"id"));
            for(int i=1;i<=2;i++) project("egg_allele",get(value,"allele"+i),"item_id",id,"gene_key",gene,"allele_index",i);
        }
    }
    private void mapItem(Object item,UUID id) {
        row("map_state","item_id",id,"map_id",get(item,"mapId"),"source_symbols_format",2); int position=0;
        for(Object symbol:list(get(item,"symbols.symbols"))) {
            if(!(boolean)get(symbol,"isUserDefined") || get(symbol,"networkInfo")!=null) continue;
            boolean text=typeName(symbol).equals("WorldMapTextSymbol");
            project("map_symbol",symbol,"item_id",id,"position",position++,"kind",text?"text":"texture",
                "color_r",colorByte(get(symbol,"r")),"color_g",colorByte(get(symbol,"g")),"color_b",colorByte(get(symbol,"b")),"color_a",colorByte(get(symbol,"a")),
                "text",text?get(symbol,"text"):null,"translated",text?get(symbol,"translated"):null,"layer_id",text?get(symbol,"layerId"):null,"symbol_id",text?null:get(symbol,"symbolId"));
        }
    }
    private void radio(Object device,UUID id) {
        if(device==null) return; Object presets=get(device,"presets");
        project("radio_state",device,"item_id",id,"max_presets",presets==null?null:get(presets,"maxPresets"),"recorded_media_id",resolvedMedia(device,"mediaIndex"));
        if(presets!=null) { int position=0; for(Object preset:list(get(presets,"presets"))) project("radio_preset",preset,"item_id",id,"position",position++); }
    }
    private static String resolvedMedia(Object source,String path) {
        return source instanceof ApocBRPlayerRelationalSnapshot.Node node?(String)node.get("resolvedMedia"):media(get(source,path));
    }
    static String media(Object index) {
        if(number(index)<0) return null;
        try {
            Object recorded=Class.forName("zombie.radio.ZomboidRadio").getMethod("getRecordedMedia").invoke(null);
            Object data=call(recorded,"getMediaDataFromIndex",((Number)index).shortValue());
            if(data==null) throw new IllegalStateException("Unresolved recorded media index");
            return get(data,"id").toString();
        } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void fluid(Object fluid,UUID owner) {
        Map<String,Object> data=new LinkedHashMap<>();
        for(String field:new String[]{"capacity","inputLocked","canPlayerEmpty","containerName","hiddenAmount","rainCatcher"}) data.put(field,get(fluid,field));
        List<Object> fluids=new ArrayList<>();
        for(Object instance:list(get(fluid,"fluids"))) {
            Object type=get(instance,"fluid"); Map<String,Object> value=new LinkedHashMap<>();
            value.put("type",get(type,"fluidTypeString")); value.put("amount",get(instance,"amount"));
            Object color=get(instance,"color"); value.put("colorOverride",Map.of("r",get(color,"r"),"g",get(color,"g"),"b",get(color,"b"))); fluids.add(value);
        }
        data.put("fluids",fluids);
        for(String name:new String[]{"whitelist","blacklist"}) {
            Object filter=get(fluid,name); if(filter==null) {data.put(name,null);continue;}
            Map<String,Object> f=new LinkedHashMap<>(); f.put("filterType",key(get(filter,"filterType")));
            f.put("fluidTypes",list(get(filter,"fluidEnums")).stream().map(ApocBRPlayerRelationalAccess::key).toList());
            f.put("fluidStrings",list(get(filter,"fluidStrings")));
            f.put("categories",list(get(filter,"categories")).stream().map(ApocBRPlayerRelationalAccess::key).toList()); data.put(name,f);
        }
        componentValue(owner,"entity.FluidContainer",data,"complete");
    }
    private void lua(UUID owner,Object table) {
        if(table==null || (boolean)get(table,"isEmpty")) return;
        componentValue(owner,"lua.modData",table,"complete");
    }
    private void componentValue(UUID owner,String kind,Object value,String coverage) {
        UUID id=ApocBRPlayerRelationalDatabase.named("component:"+owner+":"+kind); object(id,"component");
        row("component","id",id,"owner_object_id",owner,"kind",kind,"codec_version",CODEC_VERSION,"position",0,"coverage",coverage);
        tableStack.clear();
        node(id,null,0,null,value,0);
    }
    private void node(UUID component,UUID parent,int position,Object key,Object value,int depth) {
        check(); if(depth>64) throw new IllegalStateException("Oversized structured state");
        if(++nodes>ApocBRPlayerRelationalLimits.NODES)throw new ApocBRPlayerRelationalLimits.SizeExceeded("CAPTURE_SIZE_BUDGET",bytes,MAX_BYTES,rows.size(),itemIds.size(),nodes);
        UUID id=ApocBRPlayerRelationalDatabase.named("node:"+component+":"+parent+":"+(key==null?"position:"+position:key.getClass().getName()+":"+key)); Map<String,Object> values=new LinkedHashMap<>();
        values.put("component_id",component); values.put("id",id); values.put("parent_id",parent); values.put("position",position);
        if(key!=null) {
            String type=key instanceof String?"string":key instanceof Number?"number":key instanceof Boolean?"boolean":null;
            if(type==null) throw new IllegalStateException("Unsupported Lua key"); values.put("key_kind",type); values.put("key_"+(type.equals("string")?"text":type),key);
        }
        String type=value==null?"null":value instanceof String?"string":value instanceof Boolean?"boolean":value instanceof Float||value instanceof Double?"number":value instanceof Number?"integer":value instanceof byte[]?"opaque":value instanceof List<?>?"array":value instanceof Map<?,?>?"object":value instanceof ApocBRPlayerRelationalSnapshot.Table table?table.kind:"table";
        values.put("value_kind",type);
        switch(type) {
            case "string" -> values.put("text_value",value);
            case "boolean","integer","number" -> values.put(type+"_value",value);
            case "opaque" -> {
                values.put("opaque_value",((byte[])value).clone());
                var metadata=ApocBRPayloadProvenance.metadata(value);
                if(metadata!=null) {
                    values.put("payload_world_version",metadata.worldVersion());
                    var origin=metadata.origin();
                    if(origin!=null) {
                        values.put("source_server_id",origin.serverId());
                        values.put("source_dictionary_namespace_id",origin.namespaceId());
                        values.put("source_dictionary_view_id",origin.viewId());
                    }
                }
            }
            default -> { }
        }
        completeRow("value_node",values); int next=0;
        if(Set.of("object","array","table").contains(type)) {
            if(!tableStack.add(value)) throw new IllegalStateException("Cyclic structured table requires reference codec");
            if(value instanceof ApocBRPlayerRelationalSnapshot.Table table) { for(var e:table.entries().entrySet()) node(component,id,next++,e.getKey(),e.getValue(),depth+1); }
            else if(value instanceof Map<?,?> map) { for(var e:map.entrySet()) node(component,id,next++,e.getKey(),e.getValue(),depth+1); }
            else if(value instanceof List<?> list) { for(Object child:list) node(component,id,next++,null,child,depth+1); }
            else {
                Object iterator=call(value,"iterator");
                while((boolean)call(iterator,"advance")) node(component,id,next++,call(iterator,"getKey"),call(iterator,"getValue"),depth+1);
            }
            // Native Kahlua serialization writes shared acyclic tables by value at each path.
            tableStack.remove(value);
        }
    }
    private void sequence(String table,String column,Object source) {
        int position=0; for(Object value:list(source)) row(table,"character_id",character,"position",position++,column,value);
    }
    private void object(UUID id,String kind) { row("object","id",id,"kind",kind); objects++; }
    private void issue(String code) { if(!issues.contains(code) && issues.size()<32) issues.add(code); }
    private void check() {
        long elapsed=clock.getAsLong()-started;
        if(elapsed>budgetNanos) throw new TimeBudgetExceeded(elapsed,budgetNanos,rows.size(),itemIds.size(),bytes);
        if(bytes>MAX_BYTES || rows.size()>ApocBRPlayerRelationalLimits.ROWS) throw new ApocBRPlayerRelationalLimits.SizeExceeded("CAPTURE_SIZE_BUDGET",bytes,MAX_BYTES,rows.size(),itemIds.size(),nodes);
    }
    private static int colorByte(Object value) { return Math.max(0,Math.min(255,Math.round(((Number)value).floatValue()*255))); }
    private static Map<String,Object> pairs(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result;
    }
    private void project(String table,Object source,Object... overrides) { projectMap(table,source,pairs(overrides)); }
    private void projectMap(String table,Object source,Map<String,Object> overrides) {
        Map<String,Object> values=new LinkedHashMap<>(overrides);
        for(var column:ApocBRPlayerRelationalSchema.TABLES.get(table)) {
            if(column.name().equals("snapshot_id") || values.containsKey(column.name())) continue;
            String path=path(table,column.name());
            values.put(column.name(),get(source,path));
        }
        completeRow(table,values);
    }
    private void row(String table,Object... pairs) { completeRow(table,pairs(pairs)); }
    private void completeRow(String table,Map<String,Object> source) {
        Map<String,Object> values=new LinkedHashMap<>(); source.put("snapshot_id",snapshot);
        bytes+=512; // Conservative accounting for row/map/object overhead, not just wire bytes.
        for(var column:ApocBRPlayerRelationalSchema.TABLES.get(table)) {
            Object value=source.get(column.name());
            if(value==null && column.required()) throw new IllegalStateException("Missing required "+table+"."+column.name());
            if(value!=null) value=switch(column.type()) {
                case "text" -> value instanceof String?value:key(value);
                case "integer" -> number(value);
                case "bigint" -> ((Number)value).longValue();
                case "real" -> ((Number)value).floatValue();
                case "double precision" -> ((Number)value).doubleValue();
                default -> value;
            };
            if(value instanceof String text && text.length()>65536) throw new IllegalStateException("Oversized text field");
            bytes+=64+(value instanceof String text?text.length()*2:value instanceof byte[] array?array.length:0);
            values.put(column.name(),value);
        }
        rows.add(new Row(table,values)); check();
    }
}
