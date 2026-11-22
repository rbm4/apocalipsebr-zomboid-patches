package zombie.apocbr.api;

import java.nio.ByteBuffer;
import java.util.Base64;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoWorld;

/** Native item save format, bounded to one MiB. Called only by the owning server facade. */
final class CourierItemCodec {
    private static final int MAX=1_048_576;
    static String encode(InventoryItem item) {
        if (item==null) return null;
        try {
            ByteBuffer buffer=ByteBuffer.allocate(MAX);
            buffer.putInt(IsoWorld.getWorldVersion());
            item.saveWithSize(buffer,false);
            byte[] bytes=new byte[buffer.position()];
            buffer.flip(); buffer.get(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception invalid) { return null; }
    }
    static InventoryItem decode(String blob) {
        if (blob==null || blob.length()>((MAX+2)/3)*4) return null;
        try {
            byte[] bytes=Base64.getDecoder().decode(blob);
            if (bytes.length<8 || bytes.length>MAX) return null;
            ByteBuffer buffer=ByteBuffer.wrap(bytes);
            int version=buffer.getInt();
            if (version<1 || version>IsoWorld.getWorldVersion()) return null;
            InventoryItem item=InventoryItem.loadItem(buffer,version);
            return item!=null && !buffer.hasRemaining()?item:null;
        } catch (Exception invalid) { return null; }
    }
}
