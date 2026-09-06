// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.characters.animals;

import java.util.ArrayList;
import java.util.HashMap;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.UsedFromLua;
import zombie.Lua.LuaManager;

@UsedFromLua
public class AnimalGenomeDefinitions {
    public String name;
    public float currentValue;
    public HashMap<String, Float> ratios;
    public float minValue = 0.2F;
    public float maxValue = 0.6F;
    private static final Object DEFINITIONS_LOCK = new Object();
    public static volatile HashMap<String, AnimalGenomeDefinitions> fullGenomeDef;
    public static volatile ArrayList<String> geneticDisorder;
    public boolean forcedValues;

    public static void loadGenomeDefinition() {
        synchronized (DEFINITIONS_LOCK) {
            HashMap<String, AnimalGenomeDefinitions> loadedGenome = new HashMap<>();
            ArrayList<String> loadedDisorders = new ArrayList<>();
            KahluaTableImpl definitions = (KahluaTableImpl)LuaManager.env.rawget("AnimalGenomeDefinitions");
            if (definitions != null) {
                KahluaTableImpl genes = (KahluaTableImpl)definitions.rawget("genes");
                if (genes != null) {
                    KahluaTableIterator iterator = genes.iterator();

                    while (iterator.advance()) {
                        AnimalGenomeDefinitions def = new AnimalGenomeDefinitions();
                        String name = iterator.getKey().toString().toLowerCase();
                        def.name = name;
                        KahluaTableIterator it2 = ((KahluaTableImpl)iterator.getValue()).iterator();

                        while (it2.advance()) {
                            String key = (String)it2.getKey();
                            Object value = it2.getValue();
                            String valueStr = value.toString().trim();
                            if ("minValue".equalsIgnoreCase(key)) {
                                def.minValue = Float.parseFloat(valueStr);
                            }

                            if ("maxValue".equalsIgnoreCase(key)) {
                                def.maxValue = Float.parseFloat(valueStr);
                            }

                            if ("forcedValues".equalsIgnoreCase(key)) {
                                def.forcedValues = Boolean.parseBoolean(valueStr);
                            }

                            if ("ratio".equalsIgnoreCase(key)) {
                                def.loadRatio((KahluaTableImpl)value);
                            }
                        }

                        loadedGenome.put(name, def);
                    }
                }

                KahluaTableImpl disorder = (KahluaTableImpl)definitions.rawget("geneticDisorder");
                if (disorder != null) {
                    KahluaTableIterator iterator = disorder.iterator();

                    while (iterator.advance()) {
                        String name = iterator.getKey().toString().toLowerCase();
                        if (!loadedDisorders.contains(name)) {
                            loadedDisorders.add(name);
                        }
                    }
                }
            }

            fullGenomeDef = loadedGenome;
            geneticDisorder = loadedDisorders;
        }
    }

    private void loadRatio(KahluaTableImpl def) {
        this.ratios = new HashMap<>();
        KahluaTableIterator it = def.iterator();

        while (it.advance()) {
            String name = it.getKey().toString().toLowerCase();
            String valueStr = it.getValue().toString().trim();
            this.ratios.put(name, Float.parseFloat(valueStr));
        }
    }

    public static ArrayList<String> getGeneticDisorderList() {
        ArrayList<String> disorders = geneticDisorder;
        if (disorders == null) {
            synchronized (DEFINITIONS_LOCK) {
                disorders = geneticDisorder;
                if (disorders == null) {
                    loadGenomeDefinition();
                    disorders = geneticDisorder;
                }
            }
        }

        return disorders;
    }

    public static HashMap<String, AnimalGenomeDefinitions> getFullGenomeDefs() {
        HashMap<String, AnimalGenomeDefinitions> defs = fullGenomeDef;
        if (defs == null) {
            synchronized (DEFINITIONS_LOCK) {
                defs = fullGenomeDef;
                if (defs == null) {
                    loadGenomeDefinition();
                    defs = fullGenomeDef;
                }
            }
        }

        return defs;
    }

    public static AnimalGenomeDefinitions getDefinition(String name) {
        return name == null ? null : getFullGenomeDefs().get(name);
    }

    public static void Reset() {
        synchronized (DEFINITIONS_LOCK) {
            fullGenomeDef = null;
            geneticDisorder = null;
        }
    }
}
