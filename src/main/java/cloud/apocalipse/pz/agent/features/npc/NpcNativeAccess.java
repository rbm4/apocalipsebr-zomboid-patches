package cloud.apocalipse.pz.agent.features.npc;

import java.lang.reflect.Field;
import java.util.*;

/** Cached access to original private/package fields; never supplies patched members. */
public final class NpcNativeAccess {
    private static final ClassValue<Map<String,Field>> FIELDS=new ClassValue<>(){
        protected Map<String,Field> computeValue(Class<?> type){
            Map<String,Field> fields=new HashMap<>();
            for(Class<?> c=type;c!=null;c=c.getSuperclass())for(var field:c.getDeclaredFields()){
                if(!fields.containsKey(field.getName())){field.setAccessible(true);fields.put(field.getName(),field);}
            }
            return Map.copyOf(fields);
        }
    };
    public static Object get(Object object,String name){
        try{return field(object,name).get(object);}
        catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
    }
    public static void set(Object object,String name,Object value){
        try{field(object,name).set(object,value);}
        catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
    }
    private static Field field(Object object,String name){
        var field=FIELDS.get(object.getClass()).get(name);
        if(field==null)throw new IllegalStateException("Missing original field: "+object.getClass().getName()+"."+name);
        return field;
    }
}
