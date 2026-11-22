"""Execute the production prepared-image completion policy with stub asset/settings effects."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent/'jdk/bin'


def completion_method():
    text = (ROOT/'src/zombie/core/textures/TextureIDAssetManager.java').read_text()
    start = text.index('private void onPreparedFileTaskFinished(')
    end = text.index('{', start)+1
    depth = 1
    while depth:
        depth += (text[end]=='{')-(text[end]=='}')
        end += 1
    return text[start:end]


class CompletionPolicyTest(unittest.TestCase):
    def test_setting_changes_and_adoption_failures(self):
        source = r'''
package zombie.core.textures;
import java.util.*;
class Asset {}
class Params {int flags=128;}
class ImageData {Object data=new Object();boolean disposed;void dispose(){if(disposed)throw new AssertionError("double dispose");disposed=true;data=null;}}
class TextureID extends Asset {
 Params assetParams=new Params();ImageData data;int failure;
 void setImageData(ImageData image){if(failure==1)throw new IllegalStateException("before");data=image;if(failure==2)throw new IllegalStateException("after");}
 ImageData getImageData(){return data;}
}
class ApocBRTexturePreprocessor {static void disposeOwned(ImageData image){image.dispose();}}
class ApocBRTextureLoadTask {record Plan(int flags,int maxSize,boolean debugColors){}}
public class CompletionHarness {
 int succeeded,failed,reloads,cleared;boolean failNotification;
 void onLoadingFailed(Asset asset){failed++;}
 void onLoadingSucceeded(Asset asset){succeeded++;if(failNotification)throw new IllegalStateException("notification");}
 void setTask(Asset asset,Object value){cleared++;}
 void startLoading(Asset asset){reloads++;}
 METHOD
 public static void main(String[] args){
  for(int scenario=0;scenario<8;scenario++){
   CompletionHarness manager=new CompletionHarness();TextureID asset=new TextureID();ImageData image=new ImageData();
   zombie.core.Core.limit=8;zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.value=false;
   if(scenario==1)asset.assetParams.flags=256;
   if(scenario==2)zombie.core.Core.limit=16;
   if(scenario==3)zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.value=true;
   if(scenario==4)asset.failure=1;
   if(scenario==5)asset.failure=2;
   if(scenario==6)manager.failNotification=true;
   if(scenario==7)image=null;
   boolean threw=false;
   try{manager.onPreparedFileTaskFinished(asset,new ApocBRTextureLoadTask.Plan(128,8,false),image);}
   catch(IllegalStateException expected){threw=true;}
   if(scenario>=1&&scenario<=3){
    if(!image.disposed||manager.reloads!=1||manager.cleared!=1||asset.data!=null||manager.succeeded!=0)throw new AssertionError("stale settings");
   }else if(scenario==7){if(manager.failed!=1)throw new AssertionError("failure notification");}
   else if(scenario==4){if(!threw||!image.disposed||asset.data!=null)throw new AssertionError("unadopted cleanup");}
   else{if(asset.data!=image||image.disposed)throw new AssertionError("adopted result freed");}
   if((scenario==5||scenario==6)&&!threw)throw new AssertionError("failure propagation");
  }
 }
}
'''.replace('METHOD', completion_method())
        with tempfile.TemporaryDirectory(prefix='apocbr-completion-') as temp:
            work = pathlib.Path(temp)
            harness = work/'CompletionHarness.java'
            harness.write_text(source)
            core = work/'Core.java'
            core.write_text('package zombie.core; public class Core { public static int limit; '
                            'public static Core getInstance(){return new Core();} '
                            'public int getMaxTextureSizeFromFlags(int flags){return limit;} }')
            debug = work/'DebugOptions.java'
            debug.write_text('package zombie.debug; public class DebugOptions {'
                             'public static DebugOptions instance=new DebugOptions();public Sprite isoSprite=new Sprite();'
                             'public static class Sprite {public Flag worldMipmapColors=new Flag();}'
                             'public static class Flag {public boolean value;public boolean getValue(){return value;}}}')
            subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-d', str(work),
                            str(ROOT/'src/zombie/ApocBRClientTelemetryLite.java'), str(harness), str(core), str(debug)], check=True)
            subprocess.run([str(JDK/'java.exe'), '-cp', str(work), 'zombie.core.textures.CompletionHarness'], check=True)


if __name__ == '__main__':
    unittest.main()
