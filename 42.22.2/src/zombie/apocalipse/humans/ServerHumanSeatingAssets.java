package zombie.apocalipse.humans;

import java.nio.file.*;
import java.util.Locale;
import zombie.ZomboidFileSystem;
import zombie.apocalipse.humans.seating.StockSeatingAssets;

/** One bounded cold verification. Unsupported animation overrides disable this capability. */
final class ServerHumanSeatingAssets {
    private ServerHumanSeatingAssets() { }
    private static final class Verified {
        static final boolean SUPPORTED=StockSeatingAssets.supported(relative->{
            var fs=ZomboidFileSystem.instance;
            if(relative.toLowerCase(Locale.ROOT).endsWith(".x")){
                var stem=relative.substring(0,relative.length()-2);
                for(String extension:new String[]{".fbx",".glb"})if(Files.isRegularFile(Path.of(fs.getString((stem+extension).toLowerCase(Locale.ROOT)))))return null;
            }
            var path=Path.of(fs.getString(relative.toLowerCase(Locale.ROOT)));
            try(var input=Files.newInputStream(path)){return input.readNBytes(2*1024*1024+1);}
        });
    }
    static boolean supported(){return Verified.SUPPORTED;}
}
