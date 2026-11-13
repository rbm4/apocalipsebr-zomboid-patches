"""Differential execution of actual vanilla/patched updateAsyncTransactions bodies.
Only external game/task dependencies are stubbed; queue/admission/callback control
flow is extracted from the production sources, not reimplemented by the fixture.
"""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent / 'jdk/bin'


def method(text):
    start = text.index('public void updateAsyncTransactions()')
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


class FileCompletionProbeTest(unittest.TestCase):
    def test_original_call_order_and_exception_behavior(self):
        vanilla = method((ROOT / 'decompiled/zombie/fileSystem/FileSystemImpl.java').read_text())
        # Repair the decompiler's local-slot type artifact exactly as in production.
        vanilla = vanilla.replace('boolean priority = (boolean)1;', 'int priority = 1;')
        vanilla = vanilla.replace('priority = (boolean)(16 - this.inProgress.size());',
                                  'priority = 16 - this.inProgress.size();')
        patched = method((ROOT / 'src/zombie/fileSystem/FileSystemImpl.java').read_text())
        source = r'''
package zombie.fileSystem;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import zombie.ApocBRClientTelemetryLite;
class FileSystemImpl {
    static ArrayList<String> events;
    static class Task {
        final String name; int priority; int failure;
        Task(String name, int priority, int failure) { this.name=name; this.priority=priority; this.failure=failure; }
        void handleResult(Object result) { events.add(name+":result:"+result); if(failure==1)throw new IllegalStateException("result"); }
        void done() { events.add(name+":done"); if(failure==2)throw new IllegalStateException("done"); }
        String getErrorMessage() {return name;}
    }
    static class AsyncItem {Task task; FutureTask<Object> future;}
    static class TextureTask extends Task implements zombie.core.textures.ApocBRTextureLoadTask {
        TextureTask(){super("texture",7,0);}
    }
    final ArrayList<AsyncItem> inProgress=new ArrayList<>(), pending=new ArrayList<>(), added=new ArrayList<>();
    final AtomicBoolean lock=new AtomicBoolean();
    final Recorder executor=new Recorder();
    static class Recorder {void submit(FutureTask<Object> f){events.add("submit");}}
    void updateAsyncTransactions() {}
    static AsyncItem item(String name,int failure,boolean cancelled,boolean done) {
        AsyncItem i=new AsyncItem();i.task=new Task(name,name.length(),failure);
        i.future=new FutureTask<>(()->name); if(cancelled)i.future.cancel(false);else if(done)i.future.run();return i;
    }
}
class ExceptionLogger {static void logException(Throwable error,String message){FileSystemImpl.events.add("error:"+message);}}
class Vanilla extends FileSystemImpl { VANILLA }
class Patched extends FileSystemImpl { PATCHED }
public class FileCompletionHarness {
    static String run(FileSystemImpl fs,int scenario){
        FileSystemImpl.events=new ArrayList<>();
        fs.inProgress.add(FileSystemImpl.item("completed",scenario,false,true));
        fs.inProgress.add(FileSystemImpl.item("cancelled",0,true,true));
        fs.inProgress.add(FileSystemImpl.item("unfinished",0,false,false));
        fs.added.add(FileSystemImpl.item("low",0,false,false));
        fs.added.add(FileSystemImpl.item("highpriority",0,false,false));
        fs.pending.add(FileSystemImpl.item("pendingcancel",0,true,false));
        String thrown="";
        try {fs.updateAsyncTransactions();}catch(Exception e){thrown=e.getMessage();}
        return FileSystemImpl.events+"/"+fs.inProgress.size()+"/"+fs.pending.size()+"/"+fs.added.size()+"/"+fs.lock.get()+"/"+thrown;
    }
    public static void main(String[] args){
        for(int scenario=0;scenario<3;scenario++) {
            String expected=run(new Vanilla(),scenario);
            ApocBRClientTelemetryLite.beginFrame(true);
            String actual=run(new Patched(),scenario);
            ApocBRClientTelemetryLite.endFrame(0,0);
            if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);
        }
        Patched fs=new Patched();FileSystemImpl.events=new ArrayList<>();
        FileSystemImpl.AsyncItem texture=new FileSystemImpl.AsyncItem();texture.task=new FileSystemImpl.TextureTask();texture.future=new FutureTask<>(()->"pixels");
        fs.pending.add(texture);
        zombie.core.textures.ApocBRTextureLoadTask.ACCEPT.set(false);fs.updateAsyncTransactions();
        if(fs.pending.size()!=1 || !fs.inProgress.isEmpty() || texture.future.isDone())throw new AssertionError("rejected task lost or ran on main");
        zombie.core.textures.ApocBRTextureLoadTask.ACCEPT.set(true);fs.updateAsyncTransactions();
        if(!fs.pending.isEmpty() || fs.inProgress.size()!=1)throw new AssertionError("deferred task not readmitted");
        texture.future.run();fs.updateAsyncTransactions();
        if(!fs.inProgress.isEmpty() || !FileSystemImpl.events.equals(List.of("texture:result:pixels","texture:done")))throw new AssertionError("deferred completion ordering");
        ApocBRClientTelemetryLite.shutdown();
    }
}
'''.replace('VANILLA', vanilla).replace('PATCHED', patched)
        with tempfile.TemporaryDirectory(prefix='apocbr-file-probe-') as temp:
            work = pathlib.Path(temp)
            harness = work / 'FileCompletionHarness.java'
            harness.write_text(source)
            # External task/executor double; production executor saturation is tested with real native image tasks separately.
            task = work/'ApocBRTextureLoadTask.java'
            task.write_text('package zombie.core.textures; public interface ApocBRTextureLoadTask {'
                            'java.util.concurrent.atomic.AtomicBoolean ACCEPT=new java.util.concurrent.atomic.AtomicBoolean();'
                            'static boolean submit(java.util.concurrent.FutureTask<Object> future){return ACCEPT.get();}}')
            subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-d', str(work),
                            str(ROOT/'src/zombie/ApocBRClientTelemetryLite.java'), str(task), str(harness)], check=True)
            for enabled in ('false', 'true'):
                subprocess.run([str(JDK/'java.exe'), f'-Dapocbr.clientTelemetry.enabled={enabled}',
                                f'-Dapocbr.clientTelemetry.path={work / "capture.ndjson"}',
                                '-cp', str(work), 'zombie.fileSystem.FileCompletionHarness'], check=True)


if __name__ == '__main__':
    unittest.main()
