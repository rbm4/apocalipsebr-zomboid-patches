package zombie.savefile;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.inventory.types.Moveable;
import zombie.iso.IsoWorld;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;

/** Game-thread half of relational login. Never calls a native binary load method. */
final class ApocBRPlayerRelationalReconstruction {
    private final ApocBRPlayerRelationalReader.Profile source;
    private final Map<Integer,Object> objects=new HashMap<>();
    private final Set<String> handled=new HashSet<>();
    private final ApocBRPlayerReconstructionLog.Attempt trace;
    private ApocBRPlayerRelationalReconstruction(ApocBRPlayerRelationalReader.Profile source,ApocBRPlayerReconstructionLog.Attempt trace){this.source=source;this.trace=trace;}
    static IsoPlayer reconstruct(ApocBRPlayerRelationalReader.Profile source,int slot) {
        try(var trace=ApocBRPlayerReconstructionLog.begin("RESTORE_PLAYER",slot)) {
            trace.profile(source);if(source==null)return null;
            try {
                IsoPlayer result=new ApocBRPlayerRelationalReconstruction(source,trace).player(slot);
                trace.event("RESTORED","");return result;
            }catch(RuntimeException|LinkageError failure){trace.failure(failure);throw failure;}
        }
    }
    private List<Map<String,Object>> rows(String table) {
        handled.add(table);var result=new ArrayList<>(source.rows(table));
        ApocBRPlayerReconstructionLog.table(table,result.size());
        result.sort(Comparator.comparingInt(r->r.get("position") instanceof Number n?n.intValue():0));return result;
    }
    private static int id(Object id){return ((Number)Objects.requireNonNull(id,"Missing relational reference")).intValue();}
    private Object ref(Object id){return Objects.requireNonNull(objects.get(id(id)),"Unresolved relational reference");}
    private void register(Object id,Object object){if(objects.putIfAbsent(id(id),Objects.requireNonNull(object))!=null)throw new IllegalStateException("Duplicate relational identity");}
    private IsoPlayer player(int slot) {
        trace.stage("VALIDATE_OWNERSHIP");
        validateOwnership(source);
        trace.stage("CREATE_PLAYER");
        IsoPlayer player=new IsoPlayer(IsoWorld.instance.currentCell);player.serverPlayerIndex=slot;
        var state=source.one("character_state");register(state.get("character_id"),player);
        var descriptor=new SurvivorDesc(true);set(player,"descriptor",descriptor);
        trace.stage("RESTORE_CHARACTER_FIELDS");
        apply("character_descriptor",descriptor,"character_id");descriptor.setInstance(player);
        apply("character_state",player,"character_id inventory_container_id is_dead source_offset_x source_offset_y on_fire direction");
        // Native load consumes world coordinates directly; source offsets are provenance, not a second translation.
        player.setX(player.setLastX(player.setNextX(((Number)state.get("x")).floatValue())));player.setY(player.setLastY(player.setNextY(((Number)state.get("y")).floatValue())));player.setZ(player.setLastZ(((Number)state.get("z")).floatValue()));
        player.setForwardIsoDirection(zombie.iso.IsoDirections.valueOf((String)state.get("direction")));
        apply("character_effects",player,"character_id");apply("character_presentation",player,"character_id");
        apply("character_nutrition",get(player,"nutrition"),"character_id");
        for(var r:rows("descriptor_extra"))add(get(descriptor,"extra"),r.get("value"));
        for(var r:rows("character_stat"))call(get(player,"stats"),"set",staticCall("zombie.characters.CharacterStat","getById",r.get("stat_key")),((Number)r.get("value")).floatValue());
        traits(player);
        trace.stage("RESTORE_PERKS");perks(player,descriptor);
        trace.stage("RESTORE_SEQUENCES");sequences(player);
        trace.stage("RESTORE_HEALTH");health(player);
        trace.stage("RESTORE_VISUALS");visuals(player);
        trace.stage("RESTORE_INVENTORY");inventory(player);
        trace.stage("RESTORE_EQUIPMENT");equipment(player);
        trace.stage("RESTORE_COMPONENTS");components();
        trace.stage("FINALIZE_PLAYER");
        for(var r:rows("character_vehicle_return")) {
            for(String name:List.of("x","y","seat","running"))set(player,"savedVehicle"+Character.toUpperCase(name.charAt(0))+name.substring(1),r.get(name));
            player.setZ(0);
        }
        player.setFemale(descriptor.isFemale());player.InitSpriteParts(descriptor);player.initSpritePartsEmpty();
        player.onWornItemsChanged();
        call(player,"setVariable","Weapon",call(staticCall("zombie.inventory.types.WeaponType","getWeaponType",player),"getType"));
        call(player.getBodyDamage(),"calculateOverallHealth");
        player.setHealth(player.getBodyDamage().getOverallBodyHealth());
        if(Boolean.TRUE.equals(state.get("is_dead"))){player.getBodyDamage().setOverallBodyHealth(0);player.setHealth(0);}
        if(Boolean.TRUE.equals(state.get("on_fire")))player.SetOnFire();
        player.remote=true;
        handled.addAll(List.of("snapshot","object"));
        trace.stage("VERIFY_TABLE_COVERAGE");
        for(String table:source.tables().keySet())if(!handled.contains(table)&&!source.rows(table).isEmpty()){
            ApocBRPlayerReconstructionLog.table(table,source.rows(table).size());throw new IllegalStateException("Unrestored relational table: "+table);
        }
        return player;
    }
    private void traits(IsoPlayer player) {
        for(var r:rows("character_trait")) {
            ApocBRPlayerReconstructionLog.field("trait_key");
            String key=(String)Objects.requireNonNull(r.get("trait_key"),"Missing trait key");
            Object trait=staticCall("zombie.scripting.objects.CharacterTrait","get",staticCall("zombie.scripting.objects.ResourceLocation","of",key));
            if(trait==null){ApocBRPlayerReconstructionLog.missingTrait(key);continue;}
            // Native load uses CharacterTraits.add to restore both the flags and known list.
            call(get(player,"characterTraits"),"add",trait);
        }
    }
    private void perks(IsoPlayer player,SurvivorDesc descriptor) {
        for(var r:rows("character_perk")) {
            Object perk=staticCall("zombie.characters.skills.PerkFactory$Perks","FromString",r.get("perk_key"));
            if(perk==null||key(perk).equals("MAX"))throw new IllegalStateException("Missing saved perk");
            if(r.get("xp")!=null)put(get(player,"xp.xpMap"),perk,((Number)r.get("xp")).floatValue());
            if(r.get("level")!=null){Object info=create("zombie.characters.IsoGameCharacter$PerkInfo",player);set(info,"perk",perk);set(info,"level",r.get("level"));add(get(player,"perkList"),info);}
            if(r.get("descriptor_xp_boost")!=null)put(get(descriptor,"XPBoostMap"),perk,r.get("descriptor_xp_boost"));
            if(r.get("multiplier")!=null)call(get(player,"xp"),"addXpMultiplier",perk,((Number)r.get("multiplier")).floatValue(),id(r.get("multiplier_min_level")),id(r.get("multiplier_max_level")));
        }
    }
    private void sequences(IsoPlayer player) {
        String[][] sequences={{"character_recipe","knownRecipes","recipe_key"},{"character_read_book","alreadyReadBook","full_type"},
            {"character_print_media","readPrintMedia","media_id"},{"character_media_line","knownMediaLines","line_guid"},{"fitness_body_part","fitness.bodypartToIncStiffness","body_part_key"}};
        for(var sequence:sequences)for(var r:rows(sequence[0]))add(get(player,sequence[1]),r.get(sequence[2]));
        for(var r:rows("character_book_progress")){Object book=create("zombie.characters.IsoGameCharacter$ReadBook");project("character_book_progress",book,r,"character_id position");add(get(player,"readBooks"),book);}
        for(var r:rows("character_literature"))put(get(player,"readLiterature"),r.get("title"),r.get("day"));
        for(var r:rows("character_mechanics"))put(get(player,"mechanicsItem"),((Number)r.get("native_item_key")).longValue(),((Number)r.get("native_value")).longValue());
        for(var r:rows("character_craft_history")){Object entry=create("zombie.characters.PlayerCraftHistory$CraftHistoryEntry");project("character_craft_history",entry,r,"character_id recipe_key");put(get(player,"craftHistory.craftHistory"),r.get("recipe_key"),entry);}
        for(var r:rows("fitness_value")) {
            String name=(String)r.get("map_name");if(!Set.of("stiffnessIncMap","stiffnessTimerMap","regularityMap","exeTimer").contains(name))throw new IllegalStateException("Unknown fitness map");
            Object value;
            if(name.equals("exeTimer"))value=((Number)r.get("integer_value")).longValue();
            else if(name.equals("stiffnessTimerMap"))value=id(r.get("integer_value"));
            else value=((Number)r.get("real_value")).floatValue();
            put(get(player,"fitness."+name),r.get("key"),value);
        }
        for(var r:rows("character_cheat")) {
            String name=(String)r.get("cheat_key");boolean enabled=(Boolean)r.get("enabled");
            if(name.startsWith("legacy."))call(player,"set"+name.substring(7),enabled);
            else if(name.startsWith("extra.")){String flag=name.substring(6);if(Set.of("GodMod","GhostMode","Invisible","NoClip").contains(flag))call(player,"set"+flag,enabled,true);else call(player,"set"+flag,enabled);}
            else if(name.startsWith("set.")){if(enabled)add(get(player,"cheats.cheats"),convert(type("zombie.characters.CheatType"),name.substring(4)));}
            else throw new IllegalStateException("Unknown saved cheat");
        }
    }
    private void health(IsoPlayer player) {
        Object body=player.getBodyDamage();apply("body_damage",body,"character_id");
        for(var r:rows("body_part")) {
            Object part=list(get(body,"bodyParts")).stream().filter(p->key(get(p,"type")).equals(r.get("body_part_key"))).findFirst().orElseThrow();
            project("body_part",part,r,"character_id body_part_key");
        }
        if(!source.rows("thermoregulator").isEmpty()) {
            Object thermo=get(body,"thermoregulator");if(thermo==null){thermo=create("zombie.characters.BodyDamage.Thermoregulator",body);set(body,"thermoregulator",thermo);}
            apply("thermoregulator",thermo,"character_id");
            for(var r:rows("thermal_node")) {
                Object node=list(get(thermo,"nodes")).stream().filter(n->key(get(n,"bodyPartType")).equals(r.get("body_part_key"))).findFirst().orElseThrow();
                project("thermal_node",node,r,"character_id body_part_key");
            }
        } else {rows("thermoregulator");rows("thermal_node");}
    }
    private void visuals(IsoPlayer player) {
        Object human=player.getVisual();
        for(var r:rows("human_visual")) {
            project("human_visual",human,r,"character_id hair_r hair_g hair_b beard_r beard_g beard_b skin_r skin_g skin_b natural_hair_r natural_hair_g natural_hair_b natural_beard_r natural_beard_g natural_beard_b");
            for(String color:List.of("hair","beard","skin","natural_hair","natural_beard"))set(human,camel(color)+"Color",color(r,color));
        }
        for(var r:rows("visual")) {
            Object visual=create("zombie.core.skinnedmodel.visual.ItemVisual");register(r.get("id"),visual);
            project("visual",visual,r,"id owner_character_id owner_item_id position tint_r tint_g tint_b hue full_type");
            call(visual,"setItemType",r.get("full_type"));set(visual,"tint",color(r,"tint"));
            set(visual,"hue",r.get("hue")==null?Float.POSITIVE_INFINITY:r.get("hue"));
            if(r.get("owner_character_id")!=null)add(get(human,"bodyVisuals"),visual);
        }
        for(var r:rows("visual_body_value")) {
            Object owner=r.get("character_id")!=null?human:ref(r.get("visual_id"));String channel=(String)r.get("channel");
            if(!Set.of("blood","dirt","holes","basicPatches","denimPatches","leatherPatches").contains(channel))throw new IllegalStateException("Unknown visual channel");
            Object part=convert(type("zombie.characterTextures.BloodBodyPartType"),r.get("body_part_key"));int index=((Number)staticCall("zombie.characterTextures.BloodBodyPartType","ToIndex",part)).intValue();
            byte[] array=(byte[])get(owner,channel);if(array==null){array=new byte[zombie.characterTextures.BloodBodyPartType.MAX.ordinal()];set(owner,channel,array);}
            array[index]=(byte)id(r.get("value"));
        }
    }
    private static Object color(Map<String,Object> row,String prefix) {
        if(row.get(prefix+"_r")==null)return null;
        return new zombie.core.ImmutableColor(id(row.get(prefix+"_r")),id(row.get(prefix+"_g")),id(row.get(prefix+"_b")));
    }
    private void inventory(IsoPlayer player) {
        int inventory=id(source.one("character_state").get("inventory_container_id"));
        for(var r:rows("container")) {
            Object container=id(r.get("id"))==inventory?player.getInventory():new ItemContainer();
            project("container",container,r,"id owner_character_id owner_item_id");register(r.get("id"),container);
        }
        Set<Integer> nativeIds=new HashSet<>();
        Map<Integer,Map<String,Object>> moveables=new HashMap<>();
        for(var r:rows("moveable_state"))if(moveables.put(id(r.get("item_id")),r)!=null)throw new IllegalStateException("Duplicate Moveable state");
        for(var r:rows("item")) {
            ApocBRPlayerReconstructionLog.subject("item",r.get("id"),r.get("full_type"));
            InventoryItem item=createSavedItem(r,moveables.get(id(r.get("id"))),InventoryItemFactory::CreateItem);
            // One line only when the saved definition cannot be resolved locally.
            if(item==null||!item.getClass().getName().equals(r.get("runtime_class")))trace.event("ITEM_TYPE_MISMATCH","; fullType="+ApocBRPlayerReconstructionLog.token(r.get("full_type"))+"; expectedClass="+ApocBRPlayerReconstructionLog.token(r.get("runtime_class"))+"; actualClass="+(item==null?"MISSING":item.getClass().getName()));
            validateItemType(item,r);
            if(!nativeIds.add(id(r.get("native_id"))))throw new IllegalStateException("Invalid saved item type or identity");
            register(r.get("id"),item);
            project("item",item,r,"id full_type runtime_class save_type source_registry_id texture_name recorded_media_id source_recorded_media_index");
            if(r.get("texture_name")!=null)set(item,"texture",staticCall("zombie.core.textures.Texture","getSharedTexture",r.get("texture_name")));
            set(item,"recordedMediaIndex",mediaIndex(r.get("recorded_media_id")));
        }
        for(var r:rows("bag_state"))restoreBag((InventoryContainer)ref(r.get("item_id")),(ItemContainer)ref(r.get("container_id")),id(r.get("weight_reduction")));
        for(var r:rows("item_location")) {
            InventoryItem item=(InventoryItem)ref(r.get("item_id"));
            if("container".equals(r.get("relation"))) {
                ItemContainer container=(ItemContainer)ref(r.get("container_id"));
                restoreContainerItem(container,item);
            }
            else if("weapon_attachment".equals(r.get("relation"))) {
                set(item,"partType",r.get("attachment_slot"));call(ref(r.get("parent_item_id")),"attachWeaponPart",item,false);
            }
            else throw new IllegalStateException("Unknown inventory relation");
        }
        for(var r:rows("visual"))if(r.get("owner_item_id")!=null){Object item=ref(r.get("owner_item_id")),visual=ref(r.get("id"));set(item,"visual",visual);call(visual,"setInventoryItem",item);}
        for(String table:List.of("weapon_state","food_state","clothing_state","literature_state","alarm_state","key_state","moveable_state"))for(var r:rows(table)) {
            InventoryItem item=(InventoryItem)ref(r.get("item_id"));
            ApocBRPlayerReconstructionLog.subject("item",r.get("item_id"),item.getFullType());
            project(table,item,r,"item_id");
        }
        String[][] extras={{"item_extra","extraItems","full_type"},{"food_spice","spices","full_type"},{"literature_recipe","learnedRecipes","recipe_key"}};
        for(var e:extras) {
            Set<Object> initialized=new HashSet<>();
            for(var r:rows(e[0]))restoreItemSequence(e,r,initialized.add(r.get("item_id")));
        }
        for(var r:rows("literature_page")){Object item=ref(r.get("item_id"));Object pages=get(item,"customPages");if(pages==null){pages=new HashMap<>();set(item,"customPages",pages);}put(pages,r.get("page_key"),r.get("text"));}
        for(var r:rows("clothing_patch")){Object item=ref(r.get("item_id")),patch=create("zombie.inventory.types.Clothing$ClothingPatch");project("clothing_patch",patch,r,"item_id body_part_key");Object patches=get(item,"patches");if(patches==null){patches=new HashMap<>();set(item,"patches",patches);}Object part=convert(type("zombie.characterTextures.BloodBodyPartType"),r.get("body_part_key"));put(patches,staticCall("zombie.characterTextures.BloodBodyPartType","ToIndex",part),patch);}
        for(var r:rows("animal_track")){Object track=create("zombie.characters.animals.AnimalTracks");project("animal_track",track,r,"item_id");set(ref(r.get("item_id")),"animalTracks",track);}
        itemMaps();radios();eggs();
        for(var r:rows("container"))set(ref(r.get("id")),"dirty",false);
        call(get(player,"savedInventoryItems"),"addAll",player.getInventory().items);
    }
    static InventoryItem createSavedItem(Map<String,Object> row,Map<String,Object> state,
                                         java.util.function.Function<String,InventoryItem> factory) {
        String fullType=(String)row.get("full_type");
        String sprite=state==null?null:(String)state.get("world_sprite");
        // Moveable.load changes Base.Moveable's runtime full type to Base.<worldSprite>.
        // That derived name is not a script definition and must not be sent to the factory.
        boolean dynamic=Moveable.class.getName().equals(row.get("runtime_class"))
            &&sprite!=null&&!sprite.isBlank()&&("Base."+sprite).equals(fullType);
        InventoryItem item=factory.apply(dynamic?"Base.Moveable":fullType);
        if(dynamic&&item!=null&&item.getClass()==Moveable.class) {
            Moveable furniture=(Moveable)item;
            if(!furniture.ReadFromWorldSprite(sprite)) {
                ApocBRPlayerReconstructionLog.moveableFailure(fullType,sprite);
                throw new IllegalStateException("Invalid saved Moveable sprite");
            }
            // Initialize sprite/grid/name properties before projecting saved scalar, light,
            // visual and component state, so sprite defaults cannot overwrite saved values.
            set(furniture,"type",sprite);set(furniture,"fullType",fullType);
        }
        return item;
    }
    private void restoreItemSequence(String[] mapping,Map<String,Object> row,boolean first) {
        InventoryItem item=(InventoryItem)ref(row.get("item_id"));Object list=null,value=row.get(mapping[2]);
        ApocBRPlayerReconstructionLog.subject("item",row.get("item_id"),item.getFullType());
        ApocBRPlayerReconstructionLog.field(mapping[1]);
        try {
            list=get(item,mapping[1]);
            // Factory/script defaults may be immutable. Native load replaces saved sequences;
            // build each item's own list once, without appending duplicate constructor defaults.
            if(first||list==null){list=new ArrayList<>();set(item,mapping[1],list);}
            add(list,value);
        }catch(RuntimeException failure) {
            trace.event("ITEM_SEQUENCE_FAILED","; table="+mapping[0]+"; field="+mapping[1]
                +"; itemOrdinal="+id(row.get("item_id"))+"; nativeId="+item.getID()
                +"; fullType="+ApocBRPlayerReconstructionLog.token(item.getFullType())
                +"; collectionClass="+(list==null?"NONE":list.getClass().getName())
                +"; valueClass="+(value==null?"NONE":value.getClass().getName()));
            throw failure;
        }
    }
    static void restoreBag(InventoryContainer bag,ItemContainer contents,int weightReduction) {
        bag.setItemContainer(contents);
        contents.containingItem=bag;contents.inventoryContainer=bag;
        bag.setWeightReduction(weightReduction);
    }
    static void restoreContainerItem(ItemContainer parent,InventoryItem item) {
        parent.items.add(item);parent.includingObsoleteItems.add(item);
        // InventoryContainer.container is its contents, hiding InventoryItem.container (its parent).
        item.setContainer(parent);
    }
    static void validateItemType(InventoryItem item,Map<String,Object> row) {
        if(item==null||!item.getClass().getName().equals(row.get("runtime_class")))throw new IllegalStateException("Invalid saved item type or identity");
        // 42.21.0 InventoryItem.save writes a constant -1 byte; no item saveType accessor exists.
        // Capture persists that sentinel. The factory result's runtime class validates the subtype.
        if(id(row.get("save_type"))!=-1)throw new IllegalStateException("Saved item type changed");
    }
    /** Validate single ownership and bounded root reachability before creating engine objects. */
    static void validateOwnership(ApocBRPlayerRelationalReader.Profile source) {
        int root=id(source.one("character_state").get("inventory_container_id"));
        Map<Integer,Integer> parents=new HashMap<>();Set<Integer> items=new HashSet<>(),containers=new HashSet<>();
        for(var r:source.rows("item"))if(!items.add(id(r.get("id"))))throw new IllegalStateException("Duplicate item");
        for(var r:source.rows("container")) {
            int container=id(r.get("id"));if(!containers.add(container)||items.contains(container))throw new IllegalStateException("Duplicate container");
            if(container!=root){int owner=id(r.get("owner_item_id"));if(!items.contains(owner)||parents.put(container,owner)!=null)throw new IllegalStateException("Invalid container owner");}
        }
        if(!containers.contains(root))throw new IllegalStateException("Missing root inventory");
        Set<Integer> bags=new HashSet<>(),bagContainers=new HashSet<>();
        for(var r:source.rows("bag_state")) {
            int bag=id(r.get("item_id")),contents=id(r.get("container_id"));
            if(!items.contains(bag)||contents==root||!containers.contains(contents)||!Objects.equals(parents.get(contents),bag)
                    ||!bags.add(bag)||!bagContainers.add(contents))throw new IllegalStateException("Invalid bag contents ownership");
        }
        if(bagContainers.size()!=containers.size()-1)throw new IllegalStateException("Missing bag contents ownership");
        Set<String> positions=new HashSet<>();
        for(var r:source.rows("item_location")) {
            int item=id(r.get("item_id")),parent;
            if("container".equals(r.get("relation"))&&r.get("parent_item_id")==null){parent=id(r.get("container_id"));if(!containers.contains(parent))throw new IllegalStateException("Unknown container");}
            else if("weapon_attachment".equals(r.get("relation"))&&r.get("container_id")==null){parent=id(r.get("parent_item_id"));if(!items.contains(parent))throw new IllegalStateException("Unknown weapon");}
            else throw new IllegalStateException("Invalid inventory relation");
            if(!items.contains(item)||parents.put(item,parent)!=null||!positions.add(parent+":"+id(r.get("position"))))throw new IllegalStateException("Duplicate inventory ownership or position");
        }
        for(int item:items) {
            Set<Integer> seen=new HashSet<>();int current=item;
            while(current!=root){if(!seen.add(current)||seen.size()>64)throw new IllegalStateException("Cyclic or oversized inventory");current=Objects.requireNonNull(parents.get(current),"Unreachable inventory item");}
        }
    }
    private static short mediaIndex(Object media) {
        if(media==null)return -1;
        Object recorded=call(staticCall("zombie.radio.ZomboidRadio","getInstance"),"getRecordedMedia");
        Object data=call(recorded,"getMediaData",media);if(data==null)throw new IllegalStateException("Missing saved recorded media");
        return ((Number)get(data,"index")).shortValue();
    }
    private void itemMaps() {
        for(var r:rows("map_state"))set(ref(r.get("item_id")),"mapId",r.get("map_id"));
        for(var r:rows("map_symbol")) {
            Object symbols=get(ref(r.get("item_id")),"symbols"),symbol;
            float x=((Number)r.get("x")).floatValue(),y=((Number)r.get("y")).floatValue();
            float red=id(r.get("color_r"))/255f,green=id(r.get("color_g"))/255f,blue=id(r.get("color_b"))/255f,alpha=id(r.get("color_a"))/255f;
            if("text".equals(r.get("kind")))symbol=call(symbols,Boolean.TRUE.equals(r.get("translated"))?"addTranslatedText":"addUntranslatedText",r.get("text"),r.get("layer_id"),x,y,red,green,blue,alpha);
            else if("texture".equals(r.get("kind")))symbol=call(symbols,"addTexture",r.get("symbol_id"),x,y,red,green,blue,alpha);
            else throw new IllegalStateException("Unknown map symbol");
            project("map_symbol",symbol,r,"item_id position kind color_r color_g color_b color_a text translated layer_id symbol_id");
        }
    }
    private void radios() {
        for(var r:rows("radio_state")) {
            Object device=get(ref(r.get("item_id")),"deviceData");
            project("radio_state",device,r,"item_id max_presets recorded_media_id source_media_index");set(device,"mediaIndex",mediaIndex(r.get("recorded_media_id")));
            if(r.get("max_presets")!=null){Object presets=get(device,"presets");call(presets,"clearPresets");set(presets,"maxPresets",r.get("max_presets"));}
        }
        for(var r:rows("radio_preset"))call(get(ref(r.get("item_id")),"deviceData.presets"),"addPreset",r.get("name"),id(r.get("frequency")));
    }
    private void eggs() {
        for(var r:rows("egg_gene")){Object food=ref(r.get("item_id")),genome=get(food,"eggGenome");if(genome==null){genome=new HashMap<>();set(food,"eggGenome",genome);}Object gene=create("zombie.characters.animals.AnimalGene");set(gene,"id",r.get("native_gene_id"));set(gene,"name",r.get("gene_key"));put(genome,r.get("gene_key"),gene);}
        for(var r:rows("egg_allele")){Object gene=call(get(ref(r.get("item_id")),"eggGenome"),"get",r.get("gene_key"));Object allele=create("zombie.characters.animals.AnimalAllele");project("egg_allele",allele,r,"item_id gene_key allele_index");set(gene,"allele"+id(r.get("allele_index")),allele);}
    }
    private void equipment(IsoPlayer player) {
        for(var r:rows("equipment")) {
            InventoryItem item=(InventoryItem)ref(r.get("item_id"));String slot=(String)r.get("slot");
            switch((String)r.get("kind")) {
                // Match IsoPlayer.load: deserialization must not fire OnEquip* callbacks,
                // which may replace restored attachments and reapply gameplay modifiers.
                case "hand" -> {if(slot.equals("primary"))set(player,"primaryHandItem",item);else if(slot.equals("secondary"))set(player,"secondaryHandItem",item);else throw new IllegalStateException("Unknown hand slot");}
                case "worn" -> call(player.getWornItems(),"setItem",registry("zombie.scripting.objects.ItemBodyLocation",slot),item);
                case "attached" -> call(player.getAttachedItems(),"setItem",slot,item);
                default -> throw new IllegalStateException("Unknown equipment relation");
            }
        }
    }
    private void components() {
        Map<Integer,List<Map<String,Object>>> children=new HashMap<>();Map<Integer,Map<String,Object>> roots=new HashMap<>();
        Map<InventoryItem,Object> weights=new IdentityHashMap<>();
        for(var r:rows("value_node")){if(r.get("parent_id")==null){if(roots.put(id(r.get("component_id")),r)!=null)throw new IllegalStateException("Duplicate component root");}else children.computeIfAbsent(id(r.get("parent_id")),k->new ArrayList<>()).add(r);}
        for(var r:rows("component")) {
            ApocBRPlayerReconstructionLog.subject("component",r.get("id"),r.get("kind"));
            if(!"complete".equals(r.get("coverage"))||id(r.get("codec_version"))!=ApocBRPlayerRelationalCapture.CODEC_VERSION)throw new IllegalStateException("Incomplete component");
            Object owner=ref(r.get("owner_object_id")),value=value(Objects.requireNonNull(roots.get(id(r.get("id")))),children,new HashSet<>(),0,objects);
            String kind=(String)r.get("kind");
            if(kind.equals("lua.modData"))set(owner,"table",value);
            else if(kind.equals("item.weight"))weights.put((InventoryItem)owner,value);
            else if(kind.equals("item.byteData")) {
                ByteBuffer buffer=ByteBuffer.wrap((byte[])value);
                ApocBRPayloadProvenance.copy(value,buffer);set(owner,"byteData",buffer);
            }
            else if(kind.startsWith("entity."))restoreComponent(owner,kind.substring(7),value);
            else if(kind.equals("item.embeddedAnimal"))restoreAnimal(owner,value);
            else throw new IllegalStateException("Unsupported saved component");
        }
        // Embedded animals and other components may initialize derived item state.
        // The explicit live weight override is applied after all of them, once.
        weights.forEach(ApocBRPlayerRelationalReconstruction::restoreItemWeight);
    }
    static void restoreItemWeight(InventoryItem item,Object value) {
        if(!(value instanceof Number number)||!Float.isFinite(number.floatValue())||number.floatValue()<0||!item.isCustomWeight())
            throw new IllegalStateException("Invalid saved item weight");
        item.setWeight(number.floatValue());
    }
    private static Object value(Map<String,Object> row,Map<Integer,List<Map<String,Object>>> children,Set<Integer> visited,int depth) {
        return value(row,children,visited,depth,Map.of());
    }
    private static Object value(Map<String,Object> row,Map<Integer,List<Map<String,Object>>> children,Set<Integer> visited,int depth,Map<Integer,Object> references) {
        if(depth>64||!visited.add(id(row.get("id"))))throw new IllegalStateException("Cyclic structured value");
        String kind=(String)row.get("value_kind");
        switch(kind) {
            case "null":return null;case "string":return row.get("text_value");case "boolean":return row.get("boolean_value");
            case "integer":return ((Number)row.get("integer_value")).longValue();case "number":return ((Number)row.get("number_value")).doubleValue();
            case "reference":return Objects.requireNonNull(references.get(id(row.get("reference_object_id"))),"Unresolved structured reference");
            case "opaque": {
                byte[] bytes=HexFormat.of().parseHex((String)row.get("opaque_value"));
                var origin=ApocBRPayloadProvenance.of(row.get("source_server_id"),row.get("source_dictionary_namespace_id"),row.get("source_dictionary_view_id"));
                Integer version=row.get("payload_world_version")==null?null:((Number)row.get("payload_world_version")).intValue();
                ApocBRPayloadProvenance.remember(bytes,new ApocBRPayloadProvenance.Metadata(origin,version));
                return bytes;
            }
            case "object", "array", "table": {
                Object result=kind.equals("array")?new ArrayList<>():kind.equals("table")?zombie.Lua.LuaManager.platform.newTable():new LinkedHashMap<>();
                for(var child:children.getOrDefault(id(row.get("id")),List.of())) {
                    Object v=value(child,children,visited,depth+1,references);
                    if(result instanceof List<?>){add(result,v);continue;}
                    Object key=switch((String)child.get("key_kind")){case "string"->child.get("key_text");case "number"->((Number)child.get("key_number")).doubleValue();case "boolean"->child.get("key_boolean");default->throw new IllegalStateException("Invalid structured key");};
                    if(kind.equals("table"))call(result,"rawset",key,v);else put(result,key,v);
                }
                return result;
            }
            default:throw new IllegalStateException("Unknown structured value");
        }
    }
    private static void restoreComponent(Object owner,String name,Object state) {
        Object component=null;
        for(int i=0;i<number(get(owner,"componentSize"));i++){Object c=call(owner,"getComponentForIndex",i);if(c.getClass().getSimpleName().equals(name)){component=c;break;}}
        if(component==null) {
            for(var kind:zombie.entity.ComponentType.values())if(kind.GetComponentClass()!=null&&kind.GetComponentClass().getSimpleName().equals(name)){component=kind.CreateComponent();invokeDeclared(owner,"addComponent",component);break;}
        }
        if(component==null)throw new IllegalStateException("Unknown saved component: "+name);
        restoreComponentState(component,name,state);
        invokeDeclared(owner,"connectComponents");
    }
    @SuppressWarnings("unchecked")
    private static void restoreComponentState(Object component,String name,Object state) {
        Map<String,Object> fields=(Map<String,Object>)state;
        if(name.equals("SpriteConfig")){restoreSpriteConfig(component,fields);return;}
        if(name.equals("AttributeContainer")){restoreAttributes(component,(List<Object>)fields.get("attributes"));return;}
        if(name.equals("FluidContainer")){restoreFluid(component,fields);return;}
        for(var e:fields.entrySet()) {
            if(name.equals("CraftBench")&&e.getValue() instanceof Map<?,?> channel){Object target=get(component,e.getKey());set(target,"bits",channel.get("nativeBits"));}
            else set(component,e.getKey(),e.getValue());
        }
    }
    private static void restoreSpriteConfig(Object component,Map<String,Object> state) {
        if(!(state.get("hasScript") instanceof Boolean present))throw new IllegalStateException("Missing SpriteConfig script presence");
        Object script=null;
        boolean master=false;
        if(present) {
            if(!(state.get("scriptFullType") instanceof String fullType)||fullType.isBlank()
                    ||!(state.get("scriptVersion") instanceof Long)||!(state.get("wasLoadedAsMaster") instanceof Boolean))
                throw new IllegalStateException("Invalid saved SpriteConfig state");
            script=zombie.world.ScriptsDictionary.spriteConfigs.get(fullType);
            if(script==null)throw new IllegalStateException("Missing SpriteConfig script: "+fullType);
            master=(Boolean)state.get("wasLoadedAsMaster");
        }
        // Match native load: resolve the current script and retain the saved master flag.
        // Native 42.22.0 reads scriptVersion but does not reject a version mismatch.
        if(script!=null)invokeDeclared(component,"readFromScript",script);
        else set(component,"configScript",null);
        set(component,"wasLoadedAsMaster",master);
        // Restore into an existing component as well as a newly allocated one. These
        // caches belong to the current owner/sprite and must never come from SQL.
        invokeDeclared(component,"initObjectInfo");
    }
    @SuppressWarnings("unchecked")
    private static void restoreAttributes(Object component,List<Object> attributes) {
        call(component,"clear");
        for(Object entry:attributes) {
            var a=(Map<String,Object>)entry;Object attribute=staticCall("zombie.entity.components.attributes.Attribute","TypeFromName",a.get("name"));
            if(attribute==null||!key(get(attribute,"valueType")).equals(a.get("value_type")))throw new IllegalStateException("Attribute registry changed");
            Object value=a.get("value");String kind=(String)a.get("value_type");
            if(kind.equals("Enum"))value=convert(type((String)a.get("enum_class")),((Map<?,?>)value).get("name"));
            else if(kind.equals("EnumSet"))value=enumSet((String)a.get("enum_class"),(List<?>)value);
            else if(kind.equals("EnumStringSet")) {
                Map<?,?> saved=(Map<?,?>)value;Object collection=create("zombie.entity.components.attributes.EnumStringObj");
                set(collection,"enumValues",enumSet((String)a.get("enum_class"),(List<?>)saved.get("enums")));
                for(Object text:(List<?>)saved.get("strings"))add(get(collection,"stringValues"),text);value=collection;
            }
            if(kind.equals("Byte"))value=((Number)value).byteValue();if(kind.equals("Short"))value=((Number)value).shortValue();if(kind.equals("Int"))value=((Number)value).intValue();if(kind.equals("Float"))value=((Number)value).floatValue();
            call(component,"put",attribute,value);
        }
    }
    @SuppressWarnings("unchecked")
    private static void restoreFluid(Object component,Map<String,Object> state) {
        call(component,"Empty",false);
        for(var e:state.entrySet())if(!Set.of("fluids","whitelist","blacklist").contains(e.getKey()))set(component,e.getKey(),e.getValue());
        for(Object entry:(List<?>)state.get("fluids")) {
            var f=(Map<String,Object>)entry;Object fluid=staticCall("zombie.entity.components.fluids.Fluid","Get",f.get("type"));if(fluid==null)throw new IllegalStateException("Missing fluid type");
            Object instance=create("zombie.entity.components.fluids.FluidInstance");set(instance,"fluid",fluid);set(instance,"amount",f.get("amount"));set(instance,"parent",component);
            var color=(Map<String,Object>)f.get("colorOverride");for(var channel:color.entrySet())set(instance,"color."+channel.getKey(),channel.getValue());add(get(component,"fluids"),instance);
        }
        for(String name:List.of("whitelist","blacklist")) {
            Object filter=null;
            if(state.get(name) instanceof Map<?,?> f) {
                filter=create("zombie.entity.components.fluids.FluidFilter");set(filter,"filterType",f.get("filterType"));
                for(Object value:(List<?>)f.get("fluidTypes"))add(get(filter,"fluidEnums"),convert(type("zombie.entity.components.fluids.FluidType"),value));
                for(Object value:(List<?>)f.get("fluidStrings"))add(get(filter,"fluidStrings"),value);
                for(Object value:(List<?>)f.get("categories"))add(get(filter,"categories"),convert(type("zombie.entity.components.fluids.FluidCategory"),value));
            }
            set(component,name,filter);
        }
        invokeDeclared(component,"recalculateCaches",true);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static Object enumSet(String name,List<?> values) {
        Class type=type(name);EnumSet set=EnumSet.noneOf(type);
        for(Object entry:values)set.add((Enum)convert(type,((Map<?,?>)entry).get("name")));return set;
    }
    @SuppressWarnings("unchecked")
    private static void restoreAnimal(Object owner,Object state) {
        Map<String,Object> saved=(Map<String,Object>)state;
        Object animal=create("zombie.characters.animals.IsoAnimal",IsoWorld.instance.currentCell,
                ((Number)saved.get("x")).intValue(),((Number)saved.get("y")).intValue(),((Number)saved.get("z")).intValue(),saved.get("type"),saved.get("breed"));
        for(var e:saved.entrySet())if(!Set.of("breed","female","stats","modData","genome","data","zoneId","direction","savedAtGameTimeMillis","motherAnimalId","attachedTree","hook","playerAcceptance","onlineID").contains(e.getKey()))set(animal,e.getKey(),e.getValue());
        call(get(animal,"descriptor"),"setFemale",saved.get("female"));set(animal,"table",saved.get("modData"));
        call(animal,"setForwardIsoDirection",convert(type("zombie.iso.IsoDirections"),saved.get("direction")));
        Object zone=saved.get("zoneId");set(animal,"animalZone",zone==null?null:call(IsoWorld.instance.metaGrid.animalZoneHandler,"getZone",UUID.fromString((String)zone)));
        for(var stat:((Map<String,Object>)saved.get("stats")).entrySet())call(get(animal,"stats"),"set",staticCall("zombie.characters.CharacterStat","getById",stat.getKey()),((Number)stat.getValue()).floatValue());
        Object genome=get(animal,"fullGenome");call(genome,"clear");
        for(var e:((Map<?,?>)saved.get("genome")).entrySet()) {
            var g=(Map<String,Object>)e.getValue();Object gene=create("zombie.characters.animals.AnimalGene");set(gene,"id",g.get("id"));set(gene,"name",g.get("name"));
            for(String name:List.of("allele1","allele2")){Object allele=create("zombie.characters.animals.AnimalAllele");for(var field:((Map<String,Object>)g.get(name)).entrySet())set(allele,field.getKey(),field.getValue());set(gene,name,allele);}put(genome,e.getKey(),gene);
        }
        staticCall("zombie.characters.animals.AnimalGene","checkGeneticDisorder",animal);
        Map<String,Object> data=(Map<String,Object>)saved.get("data");Object target=get(animal,"data");
        call(target,"setAge",((Number)data.get("age")).intValue());call(target,"setSize",((Number)data.get("size")).floatValue());
        for(var e:data.entrySet())if(!e.getKey().equals("age")&&!e.getKey().equals("size"))set(target,e.getKey(),e.getValue());
        set(animal,"timeSinceLastUpdate",saved.get("savedAtGameTimeMillis"));
        if(saved.get("motherAnimalId")!=null)set(animal,"attachBackToMother",saved.get("motherAnimalId"));
        var tree=(Map<String,Object>)saved.get("attachedTree");set(animal,"attachBackToTreeX",tree==null?Integer.MAX_VALUE:tree.get("x"));set(animal,"attachBackToTreeY",tree==null?Integer.MAX_VALUE:tree.get("y"));
        var hook=(Map<String,Object>)saved.get("hook");call(animal,"setOnHook",hook!=null);
        if(hook!=null)for(var e:hook.entrySet())set(animal,"attachBackToHook"+e.getKey().toUpperCase(Locale.ROOT),e.getValue());
        Object acceptance=get(animal,"playerAcceptanceList");call(acceptance,"clear");
        for(var e:((Map<?,?>)saved.get("playerAcceptance")).entrySet())put(acceptance,((Number)e.getKey()).shortValue(),((Number)e.getValue()).floatValue());
        set(owner,"animal",animal);invokeDeclared(owner,"initAnimalData");
    }
    private void apply(String table,Object target,String skip){for(var row:rows(table))project(table,target,row,skip);}
    static void project(String table,Object target,Map<String,Object> row,String skip) {
        ApocBRPlayerReconstructionLog.table(table,1);
        Set<String> ignored=new HashSet<>(Arrays.asList(skip.split(" ")));ignored.add("snapshot_id");
        // Reader supplies this server-only identity seed for Sync.registerPostgresPlayer.
        // Keep it in the profile; InventoryItem has no corresponding native field.
        if(table.equals("item"))ignored.add("persisted_item_id");
        if(table.equals("item"))ignored.addAll(List.of("current_uses","actual_weight","custom_weight","condition"));
        for(var entry:row.entrySet())if(!ignored.contains(entry.getKey()))set(target,ApocBRPlayerRelationalCapture.path(table,entry.getKey()),entry.getValue());
        if(table.equals("item"))restoreItemScalars((InventoryItem)target,row);
    }
    static void restoreItemScalars(InventoryItem item,Map<String,Object> row) {
        // Follow native InventoryItem.load ordering. Drainable setters derive mass
        // from uses; a saved custom override must win regardless of JSON key order.
        item.setCurrentUses(1);
        item.setCustomWeight(false);
        int uses=((Number)Objects.requireNonNull(row.get("current_uses"))).intValue();
        if(uses!=1)item.setCurrentUses(uses);
        item.setConditionWhileLoading(((Number)Objects.requireNonNull(row.get("condition"))).intValue());
        if(Boolean.TRUE.equals(row.get("custom_weight"))) {
            float weight=((Number)Objects.requireNonNull(row.get("actual_weight"))).floatValue();
            if(!Float.isFinite(weight)||weight<0)throw new IllegalStateException("Invalid saved custom item weight");
            item.setActualWeight(weight);item.setWeight(weight);item.setCustomWeight(true);
        }
        // Non-custom mass follows current factory/script defaults, as native load
        // does. Food's remaining hunger/thirst and subtype getters stay authoritative.
    }
    static void set(Object object,String path,Object value) {
        ApocBRPlayerReconstructionLog.field(path);
        int dot=path.lastIndexOf('.');if(dot>=0){object=get(object,path.substring(0,dot));path=path.substring(dot+1);}
        Objects.requireNonNull(object,"Missing reconstruction target: "+path);
        String name=path;
        // Capture can address getters; reconstruction resolves the corresponding storage or setter.
        for(String prefix:List.of("get","is","Is"))if(name.startsWith(prefix)&&name.length()>prefix.length()&&Character.isUpperCase(name.charAt(prefix.length()))){name=Character.toLowerCase(name.charAt(prefix.length()))+name.substring(prefix.length()+1);break;}
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())for(String candidate:List.of(path,name))try {
            Field field=c.getDeclaredField(candidate);field.setAccessible(true);
            if(value==null&&field.getType().isPrimitive())return; // Nullable native conditional fields retain constructor defaults.
            field.set(object,convert(field.getType(),value));return;
        }catch(NoSuchFieldException ignored){}catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot reconstruct "+path,error);}
        String cap=Character.toUpperCase(name.charAt(0))+name.substring(1);
        for(String setter:List.of("set"+cap,"Set"+cap))for(Method method:object.getClass().getMethods())if(method.getName().equals(setter)&&method.getParameterCount()==1)try {
            method.invoke(object,convert(method.getParameterTypes()[0],value));return;
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot reconstruct "+path,error);}
        throw new IllegalStateException("Missing reconstruction field "+object.getClass().getName()+"."+path);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static Object convert(Class<?> type,Object value) {
        if(value==null||type.isInstance(value))return value;
        if(value instanceof Number n){if(type==byte.class||type==Byte.class)return n.byteValue();if(type==short.class||type==Short.class)return n.shortValue();if(type==int.class||type==Integer.class)return n.intValue();if(type==long.class||type==Long.class)return n.longValue();if(type==float.class||type==Float.class)return n.floatValue();if(type==double.class||type==Double.class)return n.doubleValue();}
        if(type.isEnum())return Enum.valueOf((Class)type,value.toString());
        if(value instanceof String&&type.getName().startsWith("zombie.")) {
            return registry(type.getName(),value);
        }
        return value;
    }
    private static Object invokeDeclared(Object target,String name,Object... args) {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())for(Method m:type.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length)try{m.setAccessible(true);return m.invoke(target,args);}catch(IllegalArgumentException mismatch){}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot restore native state: "+name,e);}
        throw new IllegalStateException("Missing native method: "+name);
    }
    private static Object registry(String name,Object key){
        if(key==null)return null;
        Object location=staticCall("zombie.scripting.objects.ResourceLocation","of",key);
        Object result=staticCall(name,"get",location);
        if(result==null){
            ApocBRPlayerReconstructionLog.subject("registry",null,name);
            ApocBRPlayerReconstructionLog.missingRegistry(name,location);
            throw new IllegalStateException("Missing registry key");
        }
        return result;
    }
    private static Class<?> type(String name){try{return Class.forName(name);}catch(ClassNotFoundException e){throw new IllegalStateException(e);}}
    static Object create(String name,Object... args) {
        for(Constructor<?> constructor:type(name).getDeclaredConstructors())if(constructor.getParameterCount()==args.length)try {
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!constructor.getParameterTypes()[i].isPrimitive()&&!constructor.getParameterTypes()[i].isInstance(args[i]))throw new IllegalArgumentException("Constructor type mismatch");
            Object[] converted=new Object[args.length];for(int i=0;i<args.length;i++)converted[i]=convert(constructor.getParameterTypes()[i],args[i]);constructor.setAccessible(true);return constructor.newInstance(converted);
        }catch(IllegalArgumentException mismatch){}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot construct "+name,e);}
        throw new IllegalStateException("Missing constructor: "+name);
    }
    private static Object staticCall(String name,String method,Object... args) {
        for(Method m:type(name).getMethods())if(m.getName().equals(method)&&Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==args.length)try{return m.invoke(null,args);}catch(IllegalArgumentException mismatch){}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot resolve "+name+"."+method,e);}
        throw new IllegalStateException("Missing registry method "+name+"."+method);
    }
    private static void add(Object target,Object value){call(target,"add",value);}
    private static void put(Object target,Object key,Object value){call(target,"put",key,value);}
}
