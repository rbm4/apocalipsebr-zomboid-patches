package zombie.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import zombie.GameWindow;
import zombie.core.utils.ByteBlock;

/** Detached V6 mirror input from the completed vanilla dictionary; never owns native allocations. */
public record ApocBRWorldDictionarySnapshot(List<Registry> registries,List<Entry> entries) {
    public record Registry(String family,String name,int nextId,Integer maxLength) {
        public String key(){return family+":"+name;}
    }
    public record Entry(String family,String register,int id,String kind,String key,String module,String name,
                        boolean loaded,String mod,Boolean vanilla,Boolean modded,Boolean obsolete,Boolean removed,
                        Boolean overridesPresent,Long version,List<String> overrides) {
        public Entry {overrides=List.copyOf(overrides);}
        public String identity(){return family+":"+register+":"+id;}
    }
    public ApocBRWorldDictionarySnapshot {
        registries=List.copyOf(registries);entries=List.copyOf(entries);
        if(registries.size()>128||entries.size()>200_000)throw new IllegalArgumentException("Dictionary snapshot exceeds budget");
        Set<String> domains=new HashSet<>(),ids=new HashSet<>(),keys=new HashSet<>();
        for(var registry:registries)if(!domains.add(registry.key()))throw new IllegalArgumentException("Duplicate dictionary registry");
        for(var entry:entries)if(!domains.contains(entry.family()+":"+entry.register())||!ids.add(entry.identity())
                ||!keys.add(entry.family()+":"+entry.register()+":"+entry.key()))throw new IllegalArgumentException("Invalid dictionary identity");
    }
    static ApocBRWorldDictionarySnapshot capture(DictionaryData data)throws IOException {
        if(data.serverDataCache==null)throw new IOException("Vanilla dictionary cache not ready");
        return decode(data.serverDataCache,data);
    }
    /** Native encoder remains the authority for private named-register fields. No binary is stored in SQL. */
    static ApocBRWorldDictionarySnapshot decode(byte[] bytes,DictionaryData live)throws IOException {
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
                DictionaryInfo<?> info=kind.equals("item")?new ItemInfo():new EntityInfo();info.load(input,version,mods,modules);
                DictionaryInfo<?> current=live==null?null:kind.equals("item")?live.itemIdToInfoMap.get(info.registryId):live.entityIdToInfoMap.get(info.registryId);
                entries.add(new Entry("info","",info.registryId,kind,info.fullType,info.moduleName,info.name,
                        current!=null&&current.isLoaded,info.modId,info.existsAsVanilla,info.isModded,info.obsolete,info.removed,
                        info.modOverrides!=null,null,info.modOverrides==null?List.of():info.modOverrides));
            }
            for(String family:List.of("object","sprite"))for(int i=0,n=count(input,200_000);i<n;i++) {
                int id=family.equals("object")?input.get():input.getInt();
                entries.add(new Entry(family,"",id,family,GameWindow.ReadString(input),null,null,false,null,null,null,null,null,null,null,List.of()));
            }
            for(String family:List.of("string","script"))for(int i=0,n=count(input,128);i<n;i++) {
                String name=GameWindow.ReadString(input);
                // The 42.21.0 engine declares exactly one string register, Generic(maxLen=64).
                if(family.equals("string")&&!name.equals("Generic"))throw new IOException("Unknown string register descriptor");
                ByteBlock block=ByteBlock.Start(input,ByteBlock.Mode.Load);
                registries.add(new Registry(family,name,input.getShort(),family.equals("string")?64:null));
                for(int j=0,size=count(input,32767);j<size;j++) {
                    if(family.equals("string")) {
                        DictionaryStringInfo info=new DictionaryStringInfo();info.load(input,version);
                        entries.add(new Entry(family,name,info.registryId,family,info.string,null,null,info.isLoaded,null,null,null,null,null,null,null,List.of()));
                    }else {
                        DictionaryScriptInfo<?> info=new DictionaryScriptInfo<>();info.load(input,version);
                        entries.add(new Entry(family,name,info.registryId,family,info.name,null,null,info.isLoaded,null,null,null,null,null,null,info.version,List.of()));
                    }
                }
                ByteBlock.End(input,block);
            }
            if(input.hasRemaining())throw new IOException("Unexpected dictionary trailing bytes");
            return new ApocBRWorldDictionarySnapshot(registries,entries);
        }catch(RuntimeException failure){throw new IOException("Invalid vanilla dictionary snapshot",failure);}
    }
    private static int count(ByteBuffer input,int max)throws IOException {int value=input.getInt();if(value<0||value>max)throw new IOException("Dictionary count exceeds budget");return value;}
    private static List<String> strings(ByteBuffer input)throws IOException {
        List<String> result=new ArrayList<>();for(int i=0,n=count(input,32768);i<n;i++)result.add(GameWindow.ReadString(input));return result;
    }
}
