package zombie.apocalipse.humans;

import java.nio.file.*;
import java.util.Locale;
import java.util.HashSet;
import zombie.ZomboidFileSystem;
import zombie.apocalipse.humans.seating.StockSeatingAssets;

/** One bounded cold verification. Unsupported animation overrides disable this capability. */
final class ServerHumanSeatingAssets {
    private ServerHumanSeatingAssets() { }
    private static final class Verified {
        static final boolean SUPPORTED=verify();
    }
    private static boolean verify(){
        try{
            var inventory=new HashSet<String>();
            for(String directory:StockSeatingAssets.INVENTORIES)for(String file:ZomboidFileSystem.instance.resolveAllFiles(directory,f->f.getName().toLowerCase(Locale.ROOT).endsWith(".xml"),true))
                inventory.add(file.replace('\\','/').toLowerCase(Locale.ROOT));
            if(!StockSeatingAssets.inventorySupported(inventory))return false;
            return StockSeatingAssets.supported(relative->{
            var fs=ZomboidFileSystem.instance;
            if(relative.toLowerCase(Locale.ROOT).endsWith(".x")){
                var stem=relative.substring(0,relative.length()-2);
                for(String extension:new String[]{".fbx",".glb"})if(Files.isRegularFile(Path.of(fs.getString((stem+extension).toLowerCase(Locale.ROOT)))))return null;
            }
            var path=Path.of(fs.getString(relative.toLowerCase(Locale.ROOT)));
            try(var input=Files.newInputStream(path)){return input.readNBytes(2*1024*1024+1);}
            });
        }catch(RuntimeException unavailable){return false;}
    }
    static boolean supported(){return Verified.SUPPORTED;}
}
