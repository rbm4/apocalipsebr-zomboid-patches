package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import zombie.GameWindow;
import zombie.core.utils.ByteBlock;

/** Detached V6 mirror input from the completed vanilla dictionary; never owns native allocations. */
public record DictionarySnapshot(List<Registry> registries,List<Entry> entries) {
    public record Registry(String family,String name,int nextId,Integer maxLength) {
        public String key(){return family+":"+name;}
    }
    public record Entry(String family,String register,int id,String kind,String key,String module,String name,
                        boolean loaded,String mod,Boolean vanilla,Boolean modded,Boolean obsolete,Boolean removed,
                        Boolean overridesPresent,Long version,List<String> overrides) {
        public Entry {overrides=List.copyOf(overrides);}
        public String identity(){return family+":"+register+":"+id;}
    }
    public DictionarySnapshot {
        registries=List.copyOf(registries);entries=List.copyOf(entries);
        if(registries.size()>128||entries.size()>200_000)throw new IllegalArgumentException("Dictionary snapshot exceeds budget");
        Set<String> domains=new HashSet<>(),ids=new HashSet<>(),keys=new HashSet<>();
        for(var registry:registries)if(!domains.add(registry.key()))throw new IllegalArgumentException("Duplicate dictionary registry");
        for(var entry:entries)if(!domains.contains(entry.family()+":"+entry.register())||!ids.add(entry.identity())
                ||!keys.add(entry.family()+":"+entry.register()+":"+entry.key()))throw new IllegalArgumentException("Invalid dictionary identity");
    }
    static DictionarySnapshot capture(Object data)throws IOException {
        byte[] cache=(byte[])NativeDictionaryAccess.get(data,"serverDataCache");
        if(cache==null)throw new IOException("Vanilla dictionary cache not ready");
        return decode(cache,data);
    }
    /** Native encoder remains the authority for private named-register fields. No binary is stored in SQL. */
    static DictionarySnapshot decode(byte[] bytes,Object live)throws IOException {
        if(bytes.length>5*1024*1024)throw new IOException("Dictionary cache exceeds budget");
        ByteBuffer input=ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        try {
            int version=input.getInt();if(version!=1)throw new IOException("Unsupported dictionary format");
            List<Registry> registries=new ArrayList<>();List<Entry> entries=new ArrayList<>();
            registries.add(new Registry("info","",input.getShort(),null));
            registries.add(new Registry("object","",input.get(),null));
            registries.add(new Registry("sprite","",input.getInt(),null));
            List<String> mods=strings(input),modules=strings(input);
            for(String kind:List.of("item","entity"))for(int i=0,n=count(input,32767);i<n;i++) {
                Object info=NativeDictionaryAccess.create(kind.equals("item")?"ItemInfo":"EntityInfo");
                NativeDictionaryAccess.load(info,input,version,mods,modules);
                Object current=live==null?null:((Map<?,?>)NativeDictionaryAccess.get(live,kind.equals("item")?"itemIdToInfoMap":"entityIdToInfoMap")).get(NativeDictionaryAccess.get(info,"registryId"));
                entries.add(new Entry("info","",((Number)NativeDictionaryAccess.get(info,"registryId")).intValue(),kind,(String)NativeDictionaryAccess.get(info,"fullType"),(String)NativeDictionaryAccess.get(info,"moduleName"),(String)NativeDictionaryAccess.get(info,"name"),
                        current!=null&&(boolean)NativeDictionaryAccess.get(current,"isLoaded"),(String)NativeDictionaryAccess.get(info,"modId"),(boolean)NativeDictionaryAccess.get(info,"existsAsVanilla"),(boolean)NativeDictionaryAccess.get(info,"isModded"),(boolean)NativeDictionaryAccess.get(info,"obsolete"),(boolean)NativeDictionaryAccess.get(info,"removed"),
                        NativeDictionaryAccess.overrides(info)!=null,null,NativeDictionaryAccess.overrides(info)==null?List.of():NativeDictionaryAccess.overrides(info)));
            }
            for(String family:List.of("object","sprite"))for(int i=0,n=count(input,200_000);i<n;i++) {
                int id=family.equals("object")?input.get():input.getInt();
                entries.add(new Entry(family,"",id,family,GameWindow.ReadString(input),null,null,false,null,null,null,null,null,null,null,List.of()));
            }
            for(String family:List.of("string","script"))for(int i=0,n=count(input,128);i<n;i++) {
                String name=GameWindow.ReadString(input);
                // The original 42.22.2 engine declares one string register, Generic(maxLen=64).
                if(family.equals("string")&&!name.equals("Generic"))throw new IOException("Unknown string register descriptor");
                ByteBlock block=ByteBlock.Start(input,ByteBlock.Mode.Load);
                registries.add(new Registry(family,name,input.getShort(),family.equals("string")?64:null));
                for(int j=0,size=count(input,32767);j<size;j++) {
                    if(family.equals("string")) {
                        Object info=NativeDictionaryAccess.create("DictionaryStringInfo");NativeDictionaryAccess.load(info,input,version);
                        entries.add(new Entry(family,name,((Number)NativeDictionaryAccess.get(info,"registryId")).intValue(),family,(String)NativeDictionaryAccess.get(info,"string"),null,null,(boolean)NativeDictionaryAccess.get(info,"isLoaded"),null,null,null,null,null,null,null,List.of()));
                    }else {
                        Object info=NativeDictionaryAccess.create("DictionaryScriptInfo");NativeDictionaryAccess.load(info,input,version);
                        entries.add(new Entry(family,name,((Number)NativeDictionaryAccess.get(info,"registryId")).intValue(),family,(String)NativeDictionaryAccess.get(info,"name"),null,null,(boolean)NativeDictionaryAccess.get(info,"isLoaded"),null,null,null,null,null,null,(Long)NativeDictionaryAccess.get(info,"version"),List.of()));
                    }
                }
                ByteBlock.End(input,block);
            }
            if(input.hasRemaining())throw new IOException("Unexpected dictionary trailing bytes");
            return new DictionarySnapshot(registries,entries);
        }catch(RuntimeException failure){throw new IOException("Invalid vanilla dictionary snapshot",failure);}
    }
    private static int count(ByteBuffer input,int max)throws IOException {int value=input.getInt();if(value<0||value>max)throw new IOException("Dictionary count exceeds budget");return value;}
    private static List<String> strings(ByteBuffer input)throws IOException {
        List<String> result=new ArrayList<>();for(int i=0,n=count(input,32768);i<n;i++)result.add(GameWindow.ReadString(input));return result;
    }
}
