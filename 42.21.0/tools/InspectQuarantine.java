import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32C;

/** Read-only inspection of a copied ApocBR .record using the matching deployed classes. */
public class InspectQuarantine {
    private static Object field(Object value, String name) throws Exception {
        if (value == null) return null;
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }
    private static Object node(Object value, String name) throws Exception {
        Object fields = field(value, "fields");
        return fields instanceof Map<?, ?> map ? map.get(name) : null;
    }
    private static String safe(Object value) {
        String text = Objects.toString(value, "null").replaceAll("[\\p{Cntrl}]", "_");
        return text.substring(0, Math.min(text.length(), 500));
    }
    private static final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private static void container(Object value, String path, int depth) throws Exception {
        if (value == null || !seen.add(value)) return;
        if (depth > 64) throw new IOException("Inventory depth exceeds 64");
        Object items = node(value, "items");
        if (items instanceof List<?> list)
            for (int i = 0; i < list.size(); i++) item(list.get(i), path + "/items[" + i + "]", depth + 1);
    }
    private static void item(Object value, String path, int depth) throws Exception {
        if (value == null || !seen.add(value)) return;
        if (depth > 64) throw new IOException("Item depth exceeds 64");
        List<String> components = new ArrayList<>();
        Object raw = node(value, "components");
        if (raw instanceof List<?> list)
            for (Object component : list) components.add(safe(field(component, "runtimeClass")));
        boolean sprite = components.contains("zombie.entity.components.spriteconfig.SpriteConfig");
        System.out.println((sprite ? "SPRITECONFIG_ITEM" : "ITEM") + "; path=" + path
            + "; fullType=" + safe(node(value, "getFullType"))
            + "; nativeItemId=" + safe(node(value, "id"))
            + "; persistentItemId=" + safe(field(value, "persistentItemId"))
            + "; runtimeClass=" + safe(field(value, "runtimeClass"))
            + "; components=" + components);
        container(node(value, "container"), path + "/container", depth + 1);
        Object attachments = node(value, "attachmentList");
        if (attachments instanceof List<?> list)
            for (int i = 0; i < list.size(); i++) item(list.get(i), path + "/attachments[" + i + "]", depth + 1);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: InspectQuarantine.java /path/to/copied.record");
        Path path = Path.of(args[0]);
        byte[] payload;
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != 0x41504f31) throw new IOException("Invalid quarantine magic");
            int length = input.readInt(), expected = input.readInt();
            if (length < 0 || length > 256 * 1024 * 1024 || Files.size(path) != 12L + length)
                throw new IOException("Invalid quarantine length");
            payload = input.readNBytes(length);
            CRC32C checksum = new CRC32C(); checksum.update(payload);
            if (payload.length != length || (int) checksum.getValue() != expected)
                throw new IOException("Invalid quarantine checksum");
        }
        Object entry;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            input.setObjectInputFilter(info -> {
                if (info.depth() > 1024 || info.references() > 10_000_000 || info.arrayLength() > 256 * 1024 * 1024)
                    return ObjectInputFilter.Status.REJECTED;
                Class<?> type = info.serialClass();
                if (type == null) return ObjectInputFilter.Status.UNDECIDED;
                while (type.isArray()) type = type.getComponentType();
                String name = type.getName();
                return type.isPrimitive() || name.startsWith("java.lang.") || name.startsWith("java.util.")
                    || name.startsWith("java.time.") || name.startsWith("zombie.savefile.ApocBR")
                    ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
            });
            if (input.readInt() != 1) throw new IOException("Unsupported journal version");
            entry = input.readObject();
        }
        Object operation = field(entry, "operation");
        String kind = operation.getClass().getSimpleName();
        System.out.println("operation=" + field(entry, "id") + "; capturedAt="
            + Instant.ofEpochMilli((Long) field(entry, "capturedAt")) + "; kind=" + kind);
        if (!kind.equals("Live") && !kind.equals("Initial")) {
            System.out.println("No relational inventory snapshot for this operation kind."); return;
        }
        Object owner = kind.equals("Live") ? field(operation, "batch") : operation;
        Object candidate = field(owner, "candidate");
        for (String key : List.of("world", "identity", "coop", "slot"))
            System.out.println(key + "=" + safe(field(candidate, key)));
        if (kind.equals("Live")) System.out.println("character=" + safe(field(owner, "character")));
        Object snapshot = field(owner, "snapshot");
        if (snapshot == null) { System.out.println("No inventory snapshot (scalar-only operation)."); return; }
        container(node(field(snapshot, "player"), "inventory"), "inventory", 0);
    }
}
