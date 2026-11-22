package cloud.apocalipse.pz.agent.features.lua.api;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryOrigin;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryRuntime;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.PayloadProvenance;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoWorld;

/** Native item save format, bounded to one MiB. Called only by the owning server facade. */
public final class CourierItemCodec {
    private static final int MAX=1_048_576;
    // A legacy payload begins with a positive world version.
    private static final int PORTABLE_V1 = 0xA90C0001;
    private static final int ORIGIN_BYTES = 2 * Integer.BYTES + 4 * Long.BYTES;

    /** Read only the portable envelope. No native objects or game thread are required. */
    public static DictionaryOrigin origin(String blob) {
        if (blob == null || blob.length() < 64) return null;
        try {
            var header = ByteBuffer.wrap(Base64.getDecoder().decode(blob.substring(0, 64)));
            return readHeader(header, Integer.MAX_VALUE).origin();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static String tagLocal(String blob) {
        try {
            var origin = AgentConfiguration.dictionaryDecodeEnabled() ? DictionaryRuntime.localEncoding() : null;
            if (origin == null || blob == null || blob.length() > ((MAX + 2) / 3) * 4) return null;
            var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(blob));
            var legacy = readHeader(buffer, IsoWorld.getWorldVersion());
            if (legacy.origin() != null) return null;
            var tagged = ByteBuffer.allocate(buffer.remaining() + Integer.BYTES + ORIGIN_BYTES);
            writeHeader(tagged, legacy.worldVersion(), origin);
            tagged.put(buffer);
            return Base64.getEncoder().encodeToString(tagged.array());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static String encode(InventoryItem item) {
        if (item==null) return null;
        try {
            var origin = AgentConfiguration.dictionaryDecodeEnabled() ? DictionaryRuntime.localEncoding() : null;
            ByteBuffer buffer = ByteBuffer.allocate(MAX + (origin == null ? 0 : ORIGIN_BYTES));
            writeHeader(buffer, IsoWorld.getWorldVersion(), origin);
            item.saveWithSize(buffer,false);
            byte[] bytes=new byte[buffer.position()];
            buffer.flip(); buffer.get(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception invalid) { return null; }
    }
    static InventoryItem decode(String blob) {
        if (blob==null || blob.length()>((MAX+ORIGIN_BYTES+2)/3)*4) return null;
        try {
            byte[] bytes=Base64.getDecoder().decode(blob);
            ByteBuffer buffer=ByteBuffer.wrap(bytes);
            var metadata = readHeader(buffer, IsoWorld.getWorldVersion());
            var origin = metadata.origin();
            boolean foreign = origin != null && !sameNamespace(origin, DictionaryRuntime.localEncoding());
            if (foreign && !AgentConfiguration.dictionaryDecodeEnabled()) return null;
            try {
                if (foreign) PayloadProvenance.remember(buffer, metadata);
                InventoryItem item=InventoryItem.loadItem(buffer,metadata.worldVersion());
                return item!=null && !buffer.hasRemaining()?item:null;
            } finally {
                if (foreign) PayloadProvenance.remember(buffer, null);
            }
        } catch (Exception invalid) { return null; }
    }

    static boolean sameNamespace(DictionaryOrigin source, DictionaryOrigin local) {
        return local != null && source.serverId() == local.serverId() && source.namespaceId().equals(local.namespaceId());
    }

    static boolean matches(InventoryItem item, String original) {
        String current = encode(item);
        if (current == null || original == null || original.length() > ((MAX + ORIGIN_BYTES + 2) / 3) * 4) return false;
        if (current.equals(original)) return true;
        try {
            return sameNativeState(ByteBuffer.wrap(Base64.getDecoder().decode(current)),
                    ByteBuffer.wrap(Base64.getDecoder().decode(original)), IsoWorld.getWorldVersion());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    // Pickup reconciliation compares local native bytes across envelope upgrades and dictionary publications.
    static boolean sameNativeState(ByteBuffer current, ByteBuffer original, int currentVersion) {
        var currentHeader = readHeader(current, currentVersion);
        var originalHeader = readHeader(original, currentVersion);
        return currentHeader.worldVersion().equals(originalHeader.worldVersion()) && current.equals(original);
    }

    static void writeHeader(ByteBuffer buffer, int version, DictionaryOrigin origin) {
        if (origin != null) {
            buffer.putInt(PORTABLE_V1).putInt(origin.serverId());
            putUuid(buffer, origin.namespaceId());
            putUuid(buffer, origin.viewId());
        }
        buffer.putInt(version);
    }

    static PayloadProvenance.Metadata readHeader(ByteBuffer buffer, int currentVersion) {
        int length = buffer.remaining();
        int version = buffer.getInt();
        DictionaryOrigin origin = null;
        if (version == PORTABLE_V1) {
            origin = new DictionaryOrigin(buffer.getInt(), readUuid(buffer), readUuid(buffer));
            version = buffer.getInt();
        }
        if (version < 1 || version > currentVersion || buffer.remaining() < Integer.BYTES
                || length > MAX + (origin == null ? 0 : ORIGIN_BYTES)) {
            throw new IllegalArgumentException("Invalid courier item payload");
        }
        return new PayloadProvenance.Metadata(origin, version);
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
