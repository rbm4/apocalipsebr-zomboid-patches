"""Execute extracted vanilla/patched limitMaxSize bodies against identical task-local buffers.
External image allocation/mipmap calls are stubs; the production control flow is
extracted unchanged and compared, including identity, pixels, disposal and errors.
"""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent / 'jdk/bin'


def method(text):
    start = text.index('private ImageData limitMaxSize(ImageData data)')
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


class TextureLimitProbeTest(unittest.TestCase):
    def test_preserves_vanilla_results_and_failure_order(self):
        original = method((ROOT/'decompiled/zombie/core/textures/TextureID.java').read_text())
        patched = method((ROOT/'src/zombie/core/textures/TextureID.java').read_text())
        source = r'''
package zombie.core.textures;
import java.util.*;
import java.nio.ByteBuffer;
import zombie.ApocBRClientTelemetryLite;
class ProbeState {static ArrayList<String> calls;static int fail;}
class WrappedBuffer {
    final ByteBuffer buffer;
    WrappedBuffer(int size){buffer=ByteBuffer.allocate(size);}
    ByteBuffer getBuffer(){return buffer;}
}
class DirectBufferAllocator {static WrappedBuffer allocate(int size){ProbeState.calls.add("allocate:"+size);return new WrappedBuffer(size);}}
class MipMapLevel {
    final int width,height;final ByteBuffer buffer;
    MipMapLevel(int w,int h){width=w;height=h;buffer=ByteBuffer.allocate(w*h*4);for(int i=0;i<buffer.capacity();i++)buffer.put((byte)i);buffer.rewind();}
    ByteBuffer getBuffer(){return buffer;}
}
class ImageData {
    int width,height;boolean preserveTransparentColor,alphaPaddingDone,disposed;final MipMapLevel data;
    ImageData(int w,int h){width=w;height=h;data=new MipMapLevel(w,h);}
    ImageData(int w,int h,WrappedBuffer buffer){this(w,h);data.buffer.put(buffer.getBuffer());data.buffer.rewind();ProbeState.calls.add("newImage:"+w+":"+h);}
    int getWidth(){return width;}int getHeight(){return height;}
    MipMapLevel getData(){ProbeState.calls.add("getData");return data;}
    MipMapLevel getMipMapData(int level){ProbeState.calls.add("mip:"+level);if(ProbeState.fail==1)throw new IllegalStateException("mip");return new MipMapLevel(width>>level,height>>level);}
    void dispose(){ProbeState.calls.add("dispose");if(ProbeState.fail==2)throw new IllegalStateException("dispose");disposed=true;}
}
class Params {int flags;Params(int flags){this.flags=flags;}}
class Core {static Core getInstance(){return new Core();}int getMaxTextureSizeFromFlags(int flags){ProbeState.calls.add("limit:"+flags);return 2;}}
class Vanilla {Params assetParams;VANILLA ImageData run(ImageData data){return limitMaxSize(data);}}
class Patched {Params assetParams;PATCHED ImageData run(ImageData data){return limitMaxSize(data);}}
public class TextureProbeHarness {
    static String run(boolean patched,int mode,int failure){
        ProbeState.calls=new ArrayList<>();ProbeState.fail=failure;
        ImageData input=new ImageData(mode==2?2:8,mode==2?1:4),output=null;
        Params params=mode==0?null:new Params(mode==1?0:128);
        String error="";
        try{if(patched){Patched p=new Patched();p.assetParams=params;output=p.run(input);}
            else{Vanilla p=new Vanilla();p.assetParams=params;output=p.run(input);}}
        catch(Exception e){error=e.getMessage();}
        return ProbeState.calls+"/"+(output==null?"null":output.getWidth()+":"+output.getHeight()+":"+(output==input)+":"+Arrays.toString(output.data.buffer.array()))
            +"/"+input.disposed+"/"+input.preserveTransparentColor+"/"+(output!=null&&output.alphaPaddingDone)+"/"+error;
    }
    public static void main(String[] args){
        for(int mode=0;mode<4;mode++)for(int failure=0;failure<3;failure++){
            String expected=run(false,mode,failure);
            ApocBRClientTelemetryLite.beginFrame(true);
            String actual=run(true,mode,failure);
            ApocBRClientTelemetryLite.endFrame(0,0);
            if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);
        }
        ApocBRClientTelemetryLite.shutdown();
    }
}
'''.replace('VANILLA', original).replace('PATCHED', patched)
        with tempfile.TemporaryDirectory(prefix='apocbr-texture-probe-') as temp:
            work = pathlib.Path(temp)
            harness = work/'TextureProbeHarness.java'
            harness.write_text(source)
            subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-d', str(work),
                            str(ROOT/'src/zombie/ApocBRClientTelemetryLite.java'), str(harness)], check=True)
            for enabled in ('false', 'true'):
                subprocess.run([str(JDK/'java.exe'), f'-Dapocbr.clientTelemetry.enabled={enabled}',
                                f'-Dapocbr.clientTelemetry.path={work/"capture.ndjson"}',
                                '-cp', str(work), 'zombie.core.textures.TextureProbeHarness'], check=True)


if __name__ == '__main__':
    unittest.main()
