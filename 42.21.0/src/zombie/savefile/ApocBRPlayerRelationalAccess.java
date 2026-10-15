package zombie.savefile;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Version-pinned field access, including private native state without a giant player patch.
 * Missing accessors fail capture, never become silent zero/default values. */
final class ApocBRPlayerRelationalAccess {
    private record Key(Class<?> type,String name) {}
    private static final Map<Key,AccessibleObject> CACHE=new ConcurrentHashMap<>();
    private record CallKey(Class<?> type,String name,List<Class<?>> arguments) {}
    private static final Map<CallKey,Method> METHODS=new ConcurrentHashMap<>();
    static Object get(Object value,String path) {
        if(value instanceof ApocBRPlayerRelationalSnapshot.Node node)return node.get(path);
        if(value instanceof ApocBRPlayerRelationalSnapshot.Table table&&path.equals("isEmpty"))return table.entries().isEmpty();
        for (String part:path.split("\\.")) {
            if (value==null) return null;
            try {
                Key key=new Key(value.getClass(),part);
                AccessibleObject accessor=CACHE.get(key);
                if (accessor==null) {
                    for (Class<?> type=value.getClass(); type!=null && accessor==null; type=type.getSuperclass()) {
                        try { accessor=type.getDeclaredField(part); } catch (NoSuchFieldException ignored) { }
                    }
                    if (accessor==null) {
                        String cap=Character.toUpperCase(part.charAt(0))+part.substring(1);
                        for (String name:new String[]{part,"get"+cap,"is"+cap,"Get"+cap,"Is"+cap}) {
                            try { accessor=value.getClass().getMethod(name); break; } catch (NoSuchMethodException ignored) { }
                        }
                    }
                    if (accessor==null) throw new IllegalStateException("Missing capture field "+key.type.getName()+"."+part);
                    if(accessor instanceof Method method) {
                        accessor=invocable(method,value);
                        if(accessor==null)throw new IllegalStateException("Inaccessible capture method "+key.type.getName()+"."+part);
                    } else accessor.setAccessible(true);
                    CACHE.put(key,accessor);
                }
                value=accessor instanceof Field f?f.get(value):((Method)accessor).invoke(value);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot capture "+part,error); }
        }
        return value;
    }
    static Object call(Object object,String name,Object... args) {
        if(object instanceof ApocBRPlayerRelationalSnapshot.Node node&&name.equals("getComponentForIndex"))
            return ((List<?>)node.get("components")).get(((Number)args[0]).intValue());
        try {
            CallKey key=new CallKey(object.getClass(),name,Arrays.stream(args).map(a->a==null?Void.class:a.getClass()).toList());
            Method cached=METHODS.get(key);if(cached!=null)return cached.invoke(object,args);
            for (Method method:object.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterCount()==args.length) {
                Method accessible=invocable(method,object);if(accessible==null)continue;
                try { Object result=accessible.invoke(object,args);METHODS.putIfAbsent(key,accessible);return result; } catch (IllegalArgumentException mismatch) { }
            }
            throw new IllegalStateException("Missing capture method "+object.getClass().getName()+"."+name);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot capture "+name,error); }
    }
    private static Method invocable(Method method,Object target) {
        Object receiver=Modifier.isStatic(method.getModifiers())?null:target;
        if(method.canAccess(receiver))return method;
        // JDK collections can have public overrides on package-private implementation classes.
        // Invoke the public API declaration so virtual dispatch reaches the same override.
        Method api=publicDeclaration(target.getClass(),method,receiver);
        if(api!=null)return api;
        // Retain access to nonpublic engine classes. Closed JDK modules stay closed;
        // an erased bridge (e.g. EnumSet.add(Object)) can supply the public contract instead.
        return method.trySetAccessible()?method:null;
    }
    private static Method publicDeclaration(Class<?> type,Method method,Object receiver) {
        if(type==null)return null;
        if(Modifier.isPublic(type.getModifiers()))try {
            Method api=type.getMethod(method.getName(),method.getParameterTypes());
            if(api.canAccess(receiver))return api;
        }catch(NoSuchMethodException ignored) { }
        for(Class<?> contract:type.getInterfaces()) {
            Method api=publicDeclaration(contract,method,receiver);if(api!=null)return api;
        }
        return publicDeclaration(type.getSuperclass(),method,receiver);
    }
    static List<Object> list(Object value) {
        ArrayList<Object> result=new ArrayList<>();
        if (value==null) return result;
        if (value instanceof Iterable<?> iterable) { for (Object item:iterable) {if(result.size()>=10_000)throw new IllegalStateException("CAPTURE_SIZE_BUDGET");result.add(item);} }
        else if (value.getClass().isArray()) { int size=Array.getLength(value);if(size>10_000)throw new IllegalStateException("CAPTURE_SIZE_BUDGET");for (int i=0;i<size;i++) result.add(Array.get(value,i)); }
        else { int size=((Number)get(value,"size")).intValue();if(size>10_000)throw new IllegalStateException("CAPTURE_SIZE_BUDGET");for (int i=0;i<size;i++) result.add(call(value,"get",i)); }
        return result;
    }
    static Map<Object,Object> map(Object value) {
        LinkedHashMap<Object,Object> result=new LinkedHashMap<>();
        if (value==null) return result;
        if (value instanceof Map<?,?> map) {if(map.size()>10_000)throw new IllegalStateException("CAPTURE_SIZE_BUDGET");result.putAll(map);}
        else { for(Object key:list(call(call(value,"keySet"),"toArray"))) result.put(key,call(value,"get",key)); }
        return result;
    }
    static String camel(String name) {
        StringBuilder out=new StringBuilder(); boolean cap=false;
        for(char c:name.toCharArray()) if(c=='_') cap=true; else { out.append(cap?Character.toUpperCase(c):c); cap=false; }
        return out.toString();
    }
    static String key(Object value) {
        if(value==null) return null;
        if(value instanceof Enum<?> e) return e.name();
        if(value instanceof String s) return s;
        if(Set.of("CharacterTrait","CharacterProfession","ItemBodyLocation","WeaponPartType","ResourceLocation").contains(value.getClass().getSimpleName())) return value.toString();
        // Registry records expose their stable ResourceLocation via getRegistryId().
        for(String method:new String[]{"getRegistryId","getId"}) {
            try { Object id=value.getClass().getMethod(method).invoke(value); if(id!=null) return id.toString(); }
            catch(NoSuchMethodException ignored) { }
            catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        throw new IllegalStateException("No stable key codec for "+value.getClass().getName());
    }
    static int number(Object value) { return ((Number)value).intValue(); }
    static String typeName(Object value) {
        return value instanceof ApocBRPlayerRelationalSnapshot.Node node?node.types.getFirst():value.getClass().getSimpleName();
    }
    static List<String> typeNames(Object value) {
        if(value instanceof ApocBRPlayerRelationalSnapshot.Node node)return node.types;
        ArrayList<String> names=new ArrayList<>();
        for(Class<?> type=value.getClass();type!=null;type=type.getSuperclass())names.add(type.getSimpleName());
        return names;
    }
}
