package zombie.apocalipse.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded diagnostic sink. The worker receives strings, never native objects. */
final class ZombieCaptureWriter implements AutoCloseable {
    private final ArrayBlockingQueue<String> queue;
    private final AtomicLong dropped=new AtomicLong();
    private final long maxBytes, maxLines;
    private final long deadline;
    private final Thread worker;
    private volatile boolean accepting=true;
    private volatile String failure;
    private long written, bytes;

    ZombieCaptureWriter(Path path,int capacity,long maxLines,long maxBytes,long durationNanos,String metadata) throws IOException {
        queue=new ArrayBlockingQueue<>(capacity);this.maxLines=maxLines;this.maxBytes=maxBytes;
        deadline=System.nanoTime()+durationNanos;
        Files.createDirectories(path.toAbsolutePath().getParent());
        BufferedWriter output=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        output.write(metadata);output.newLine();output.flush();
        worker=new Thread(()->drain(output),"ApocBR-ZombieCapture");worker.setDaemon(true);worker.start();
    }
    synchronized boolean offer(String row){
        if(!accepting)return false;
        if(!queue.offer(row)){dropped.incrementAndGet();return false;}
        return true;
    }
    boolean accepting(){return accepting;}
    long dropped(){return dropped.get();}
    String failure(){return failure;}
    private void drain(BufferedWriter output){
        long lastFlush=System.nanoTime();
        try(output){
            while(accepting || !queue.isEmpty()){
                if(System.nanoTime()>=deadline)finish();
                String row=queue.poll(250,TimeUnit.MILLISECONDS);
                if(row!=null){
                    long size=row.getBytes(StandardCharsets.UTF_8).length+2L;
                    if(written>=maxLines || bytes+size>maxBytes){finish();dropped.addAndGet(queue.size()+1L);queue.clear();break;}
                    output.write(row);output.newLine();written++;bytes+=size;
                }
                if(System.nanoTime()-lastFlush>=1_000_000_000L){output.flush();lastFlush=System.nanoTime();}
            }
            output.write("{\"event\":\"summary\",\"rows\":"+written+",\"dropped\":"+dropped.get()+",\"dataBytes\":"+bytes+"}");
            output.newLine();
        }catch(Exception error){failure=error.getClass().getSimpleName()+": "+error.getMessage();}
        finally{finish();}
    }
    synchronized void finish(){accepting=false;}
    public void close(){
        finish();
        if(Thread.currentThread()!=worker)try{worker.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
