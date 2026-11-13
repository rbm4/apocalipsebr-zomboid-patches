"""Actual ImageData/native buffers: compare vanilla resize pixels and exercise owned-task cancellation.
Uses an unsigned disposable copy of the exact JAR for mixed loose class loading.
No renderer or game process is started; all files and Java home/cache paths are temporary.
"""
import pathlib
import subprocess
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent/'jdk/bin'
GAME = pathlib.Path(r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar')


def original_method():
    text = (ROOT/'decompiled/zombie/core/textures/TextureID.java').read_text()
    start = text.index('private ImageData limitMaxSize(ImageData data)')
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end].replace('Core.getInstance()', 'Limits.getInstance()')


def cancel_method():
    text = (ROOT/'src/zombie/fileSystem/FileSystemImpl.java').read_text()
    start = text.index('public void cancelAsync(int id)')
    end = text.index('{', start)+1
    depth = 1
    while depth:
        depth += (text[end]=='{')-(text[end]=='}')
        end += 1
    return text[start:end].replace('FileSystemImpl.AsyncItem', 'CancelFixture.AsyncItem')


class TextureWorkerTest(unittest.TestCase):
    def test_pixels_ownership_and_cancellation(self):
        source = r'''
package zombie.core.textures;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import zombie.ApocBROwnedResult;
import zombie.core.utils.*;
class Limits {static int size;static Limits getInstance(){return new Limits();}int getMaxTextureSizeFromFlags(int flags){return size;}}
class Params {int flags=128;}
class OriginalResize {
    Params assetParams=new Params();
    ORIGINAL
    ImageData run(ImageData image){return limitMaxSize(image);}
}
class CancelFixture {
 static class AsyncItem {int id;zombie.fileSystem.FileTask task;FutureTask<Object> future;}
 final ArrayList<AsyncItem> pending=new ArrayList<>(),inProgress=new ArrayList<>(),added=new ArrayList<>();
 final AtomicBoolean lock=new AtomicBoolean();
 CANCEL_METHOD
}
public class TextureWorkerHarness {
    static ImageData image(int w,int h,long seed) {
        int hw=1,hh=1;while(hw<w)hw*=2;while(hh<h)hh*=2;
        WrappedBuffer buffer=DirectBufferAllocator.allocate(hw*hh*4);
        byte[] bytes=new byte[buffer.capacity()];new Random(seed).nextBytes(bytes);
        buffer.getBuffer().put(bytes).rewind();
        return new ImageData(w,h,buffer);
    }
    static byte[] bytes(ImageData image){ByteBuffer b=image.getData().getBuffer();byte[] out=new byte[b.remaining()];b.get(out);b.rewind();return out;}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Limits.size=8;
        zombie.core.Core.debug=true; // worldMipmapColors is debug-only; test the effective option, not just its stored value.
        // The original method body and real worker helper use the same production ImageData mip/alpha math.
        for(boolean debugColors:new boolean[]{false,true})
        for(int[] dimensions:new int[][]{{1,1},{8,8},{16,8},{31,17},{64,64},{128,64},{1024,1024}}) {
            zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.setValue(debugColors);
            require(zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.getValue()==debugColors,"debug policy setup");
            ImageData original=image(dimensions[0],dimensions[1],42);
            ImageData input=image(dimensions[0],dimensions[1],42);
            ImageData expected=new OriginalResize().run(original);
            AtomicReference<ImageData> prepared=new AtomicReference<>();
            Thread worker=new Thread(()->prepared.set(ApocBRTexturePreprocessor.prepare(input,8,debugColors)));
            worker.start();worker.join();
            ImageData actual=prepared.get();
            require(actual!=null,"worker result");
            require(expected.getWidth()==actual.getWidth()&&expected.getHeight()==actual.getHeight(),"dimensions");
            require(Arrays.equals(bytes(expected),bytes(actual)),"actual RGBA/mipmap output mismatch debug="+debugColors+" effective="+zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.getValue()+" dimensions="+Arrays.toString(dimensions));
            require(expected.alphaPaddingDone==actual.alphaPaddingDone,"alpha-padding policy");
            require((original.data==null)==(input.data==null),"source disposal");
            expected.dispose();actual.dispose();
        }
        zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.setValue(false);
        ImageData animated=image(16,16,9);ImageDataFrame extraFrame=new ImageDataFrame();extraFrame.data=new MipMapLevel(4,4);
        animated.frames.add(extraFrame);
        require(ApocBRTexturePreprocessor.prepare(animated,8,false)==animated,"animated images must stay vanilla");
        ApocBRTexturePreprocessor.disposeOwned(animated);
        // A deliberately tiny configured budget must serialize an oversized image, never return it for main-thread resize.
        ImageData tooLarge=image(2048,1024,7);
        ImageData resized=ApocBRTexturePreprocessor.prepare(tooLarge,256,false);
        require(resized!=tooLarge && resized.getWidth()<=256 && resized.getHeight()<=256,"oversized worker resize");
        resized.dispose();
        // The dedicated queue is bounded and never uses a caller-runs rejection policy.
        CountDownLatch enteredQueue=new CountDownLatch(2),releaseQueue=new CountDownLatch(1);
        ArrayList<FutureTask<Object>> queued=new ArrayList<>();
        for(int i=0;i<2;i++) {
            FutureTask<Object> blocker=new FutureTask<>(()->{enteredQueue.countDown();releaseQueue.await();return null;});
            require(ApocBRTextureLoadTask.submit(blocker),"worker admission");queued.add(blocker);
        }
        enteredQueue.await();
        for(int i=0;i<16;i++){FutureTask<Object> pending=new FutureTask<>(()->null);require(ApocBRTextureLoadTask.submit(pending),"queue admission");queued.add(pending);}
        FutureTask<Object> overflow=new FutureTask<>(()->{throw new AssertionError("overflow ran");});
        require(!ApocBRTextureLoadTask.submit(overflow),"queue must defer overflow to filesystem pending");
        for(int i=2;i<queued.size();i++)queued.get(i).cancel(false);
        FutureTask<Object> afterPurge=new FutureTask<>(()->null);require(ApocBRTextureLoadTask.submit(afterPurge),"cancelled queue entries purged");
        releaseQueue.countDown();queued.get(0).get();queued.get(1).get();afterPurge.get();
        // Occupied slots defer work. Cancel a waiter without entering preprocessing or leaking a permit.
        java.lang.reflect.Field slotsField=ApocBRTexturePreprocessor.class.getDeclaredField("SLOTS");slotsField.setAccessible(true);
        Semaphore slots=(Semaphore)slotsField.get(null);slots.acquire(2);
        AtomicBoolean cancelWait=new AtomicBoolean();AtomicBoolean cancelledWait=new AtomicBoolean();
        ImageData waiting=image(64,64,9);
        Thread waiter=new Thread(()->{try{ApocBRTexturePreprocessor.prepare(waiting,8,false,cancelWait::get);}
            catch(CancellationException expected){cancelledWait.set(true);}});
        waiter.start();Thread.sleep(60);require(waiter.isAlive(),"busy worker must defer");cancelWait.set(true);
        waiter.join(2000);require(!waiter.isAlive()&&cancelledWait.get(),"cancel capacity wait");slots.release(2);waiting.dispose();
        File png=new File("owned-image.png");
        BufferedImage pixels=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)pixels.setRGB(x,y,(x*y*17)|0x7f000000);
        ImageIO.write(pixels,"png",png);
        ApocBRTextureLoadTask.Plan plan=new ApocBRTextureLoadTask.Plan(128,8,false);
        AtomicInteger accepted=new AtomicInteger();Thread main=Thread.currentThread();
        ApocBRTextureLoadTask successful=new ApocBRTextureLoadTask(png.getPath(),null,plan,(task,image)->{
            require(Thread.currentThread()==main,"callback left owner");accepted.incrementAndGet();image.dispose();
        });
        FutureTask<Object> completed=new FutureTask<>(successful);new Thread(completed).start();Object ready=completed.get();
        successful.handleResult(ready);successful.done();successful.done();require(accepted.get()==1,"one adoption");
        AtomicInteger failed=new AtomicInteger();
        ApocBRTextureLoadTask missing=new ApocBRTextureLoadTask("missing-image.png",null,plan,(task,image)->{
            require(image==null,"failed decode should report null");failed.incrementAndGet();
        });
        FutureTask<Object> failure=new FutureTask<>(missing);new Thread(failure).start();
        try{failure.get();throw new AssertionError("missing file succeeded");}catch(ExecutionException expected){}
        missing.handleResult(null);missing.handleResult(null);missing.done();require(failed.get()==1,"failure notification");
        // Future cancellation alone FAILS after completion; explicit token must still revoke adoption.
        ApocBRTextureLoadTask stale=new ApocBRTextureLoadTask(png.getPath(),null,plan,(task,image)->{throw new AssertionError("stale adoption");});
        FutureTask<Object> finished=new FutureTask<>(stale);new Thread(finished).start();Object abandoned=finished.get();
        require(!finished.cancel(false),"completed future cancellation assumption");
        stale.cancelResult();stale.handleResult(abandoned);stale.done();
        for(int route=0;route<3;route++){
            ApocBRTextureLoadTask job=new ApocBRTextureLoadTask(png.getPath(),null,plan,(task,image)->{throw new AssertionError("filesystem failed to revoke");});
            FutureTask<Object> future=new FutureTask<>(job);new Thread(future).start();Object result=future.get();
            CancelFixture fixture=new CancelFixture();CancelFixture.AsyncItem item=new CancelFixture.AsyncItem();item.id=7;item.task=job;item.future=future;
            (route==0?fixture.pending:route==1?fixture.inProgress:fixture.added).add(item);
            fixture.cancelAsync(7);job.handleResult(result);job.done();
        }
        for(int i=0;i<20;i++) {
            ApocBRTextureLoadTask cancelled=new ApocBRTextureLoadTask(png.getPath(),null,plan,(task,image)->{throw new AssertionError("cancelled adoption");});
            FutureTask<Object> f=new FutureTask<>(cancelled);Thread worker=new Thread(f);worker.start();
            cancelled.cancelResult();f.cancel(false);worker.join();cancelled.done();
        }
        // Race the pure ownership protocol: exactly one producer/claimant/canceller disposes the payload.
        for(int i=0;i<1000;i++) {
            ApocBROwnedResult<Object> gate=new ApocBROwnedResult<>();Object payload=new Object();AtomicInteger disposals=new AtomicInteger();
            Thread producer=new Thread(()->{if(!gate.publish(payload))disposals.incrementAndGet();});
            Thread cancel=new Thread(()->{if(gate.cancel()!=null)disposals.incrementAndGet();});
            producer.start();cancel.start();if(gate.claim(payload))disposals.incrementAndGet();producer.join();cancel.join();
            if(gate.cancel()!=null)disposals.incrementAndGet();require(disposals.get()==1,"ownership race");
        }
        require(DirectBufferAllocator.getBytesAllocated()==0,"native image buffer leak");
        System.out.println("Actual pixel equality, oversized worker resize, bounded queue, deferred cancellation and 1000 ownership races passed");
    }
}
'''.replace('ORIGINAL', original_method()).replace('CANCEL_METHOD', cancel_method())
        with tempfile.TemporaryDirectory(prefix='apocbr-worker-') as temp:
            work = pathlib.Path(temp)
            jar = work/'unsigned.jar'
            with zipfile.ZipFile(GAME) as original, zipfile.ZipFile(jar, 'w') as unsigned:
                for entry in original.infolist():
                    name = entry.filename.upper()
                    if name.startswith('META-INF/') and name.count('/') == 1 and (
                        name.endswith(('.SF', '.RSA', '.DSA', '.EC')) or name.startswith('META-INF/SIG-')):
                        continue
                    unsigned.writestr(entry, original.read(entry))
            harness = work/'TextureWorkerHarness.java'
            harness.write_text(source)
            sources = [ROOT/'src/zombie'/p for p in (
                'ApocBRClientTelemetryLite.java', 'ApocBROwnedResult.java',
                'core/textures/ImageData.java', 'core/textures/ApocBRTexturePreprocessor.java',
                'core/textures/ApocBRTextureLoadTask.java')]
            subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-cp', str(jar), '-d', str(work),
                            *map(str, sources), str(harness)], check=True)
            subprocess.run([str(JDK/'java.exe'), '--enable-native-access=ALL-UNNAMED',
                            '-Dapocbr.clientTextures.preprocessMiB=1', f'-Duser.home={work}', f'-Dzomboid.cachedir={work}', '-cp', f'{work};{jar}',
                            'zombie.core.textures.TextureWorkerHarness'], cwd=work, check=True)


if __name__ == '__main__':
    unittest.main()
