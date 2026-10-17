package zombie.savefile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.CRC32C;
import static java.nio.file.StandardOpenOption.*;

/** Local, single-process, ordered write-ahead journal. Never holds its lock during JDBC.
 * The owner supplies versioned detached operations; no JDBC/engine objects belong here. */
final class ApocBRPersistenceJournal implements AutoCloseable {
    interface Operation extends Serializable { String kind(); long estimatedBytes(); default String playerKey(){return null;} }
    record Entry(UUID id, long capturedAt, Operation operation) implements Serializable {}
    record Status(int staged, long stagedBytes, long durable, long bytes, long oldestAgeMs) {}
    private static final int MAGIC=0x41504f31, MAX_RECORD=256*1024*1024;
    private final Path directory;
    private final Consumer<String> log;
    private final long memoryLimit;
    private final ArrayDeque<Entry> staging=new ArrayDeque<>();
    private final Map<String,Integer> pendingPlayers=new HashMap<>();
    private final FileChannel owner;
    private final java.nio.channels.FileLock ownerLock;
    private UUID generation;
    private RandomAccessFile file;
    private long acknowledged, written, stagedBytes, durable, oldest;
    private boolean closed;

    ApocBRPersistenceJournal(Path directory,long memoryLimit,Consumer<String> log)throws IOException {
        this.directory=directory;this.memoryLimit=memoryLimit;this.log=log;
        Files.createDirectories(directory);
        owner=FileChannel.open(directory.resolve("owner.lock"),CREATE,WRITE);
        try{ownerLock=owner.tryLock();}catch(IOException|RuntimeException error){owner.close();throw error;}
        if(ownerLock==null){owner.close();throw new IOException("Journal already owned");}
        try {
            Path state=directory.resolve("checkpoint");
            if(Files.exists(state)) {
                byte[] bytes=Files.readAllBytes(state);
                if(bytes.length!=32)throw new IOException("Invalid journal checkpoint");
                ByteBuffer buffer=ByteBuffer.wrap(bytes);
                generation=new UUID(buffer.getLong(),buffer.getLong());acknowledged=buffer.getLong();
                if(buffer.getLong()!=checksum(Arrays.copyOf(bytes,24)))throw new IOException("Invalid checkpoint checksum");
                if(!Files.isRegularFile(path()))throw new IOException("Missing journal generation");
            } else {generation=UUID.randomUUID();try(var created=FileChannel.open(path(),CREATE_NEW,WRITE)){created.force(true);}checkpoint(generation,0);}
            file=new RandomAccessFile(path().toFile(),"rw");written=file.length();
            if(acknowledged<0||acknowledged>written)throw new IOException("Invalid journal offset");
            long position=acknowledged;
            while(position<written) {
                if(written-position<12){file.setLength(position);break;}
                file.seek(position);int magic=file.readInt(),length=file.readInt(),crc=file.readInt();
                if(magic!=MAGIC||length<0||length>MAX_RECORD)throw new IOException("Invalid journal record");
                if(written-position-12<length){file.setLength(position);break;}
                byte[] bytes=new byte[length];file.readFully(bytes);
                if((int)checksum(bytes)!=crc)throw new IOException("Journal checksum mismatch");
                Entry entry=decode(bytes);track(entry.operation(),1);if(durable++==0)oldest=entry.capturedAt();position+=12L+length;
            }
            written=file.length();file.getChannel().force(true);
        } catch(IOException|RuntimeException error){if(file!=null)file.close();ownerLock.release();owner.close();throw error;}
    }
    private Path path(){return directory.resolve(generation+".log");}
    synchronized UUID submit(Operation operation)throws IOException {
        if(closed)throw new IOException("Journal closed");
        // Overflow spills locally; it never waits for PostgreSQL or drops an operation.
        if(staging.size()>=256||stagedBytes+operation.estimatedBytes()>memoryLimit)flush();
        Entry entry=new Entry(UUID.randomUUID(),System.currentTimeMillis(),operation);
        staging.addLast(entry);stagedBytes+=operation.estimatedBytes();
        track(operation,1);
        if(stagedBytes>memoryLimit)try{flush();}
        catch(IOException|RuntimeException error){if(staging.remove(entry)){stagedBytes-=operation.estimatedBytes();track(operation,-1);}throw error;}
        notifyAll();return entry.id();
    }
    synchronized void flush()throws IOException {
        while(!staging.isEmpty()) {
            Entry entry=staging.peekFirst();byte[] bytes=encode(entry);
            try {
                file.seek(written);file.writeInt(MAGIC);file.writeInt(bytes.length);file.writeInt((int)checksum(bytes));file.write(bytes);
                file.getChannel().force(true);
            } catch(IOException error){file.setLength(written);throw error;}
            written+=12L+bytes.length;if(durable++==0)oldest=entry.capturedAt();
            staging.removeFirst();stagedBytes-=entry.operation().estimatedBytes();
        }
    }
    synchronized Entry head()throws IOException {
        if(acknowledged==written)return null;
        file.seek(acknowledged);if(file.readInt()!=MAGIC)throw new IOException("Invalid record magic");
        int length=file.readInt(),crc=file.readInt();
        if(length<0||length>MAX_RECORD)throw new IOException("Invalid record length");
        byte[] bytes=new byte[length];file.readFully(bytes);
        if((int)checksum(bytes)!=crc)throw new IOException("Journal checksum mismatch");return decode(bytes);
    }
    synchronized void acknowledge(UUID id)throws IOException {
        Entry entry=head();if(entry==null||!entry.id().equals(id))throw new IOException("Journal acknowledgement out of order");
        file.seek(acknowledged+4);long next=acknowledged+12L+file.readInt();
        checkpoint(generation,next);acknowledged=next;durable--;track(entry.operation(),-1);
        Entry following=head();oldest=following==null?0:following.capturedAt();
        if(durable==0&&staging.isEmpty())compact();
    }
    private void compact()throws IOException {
        UUID next=UUID.randomUUID();Path previous=path(),replacement=directory.resolve(next+".log");
        try(var created=FileChannel.open(replacement,CREATE_NEW,WRITE)){created.force(true);}
        checkpoint(next,0);file.close();generation=next;acknowledged=written=0;
        file=new RandomAccessFile(replacement.toFile(),"rw");Files.deleteIfExists(previous);
    }
    private void checkpoint(UUID id,long offset)throws IOException {
        ByteBuffer buffer=ByteBuffer.allocate(32);buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putLong(offset);
        buffer.putLong(checksum(Arrays.copyOf(buffer.array(),24)));Path temporary=directory.resolve("checkpoint.tmp");
        try(var channel=FileChannel.open(temporary,CREATE,TRUNCATE_EXISTING,WRITE)){buffer.flip();while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        Files.move(temporary,directory.resolve("checkpoint"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    synchronized Status status(){long first=oldest!=0?oldest:staging.isEmpty()?0:staging.peekFirst().capturedAt();return new Status(staging.size(),stagedBytes,durable,written-acknowledged,first==0?0:Math.max(0,System.currentTimeMillis()-first));}
    synchronized boolean empty(){return staging.isEmpty()&&durable==0;}
    synchronized boolean pendingPlayer(String key){return pendingPlayers.containsKey(key);}
    private void track(Operation operation,int change){String key=operation.playerKey();if(key!=null)pendingPlayers.compute(key,(k,count)->{int next=(count==null?0:count)+change;return next==0?null:next;});}
    private static long checksum(byte[] bytes){CRC32C crc=new CRC32C();crc.update(bytes);return crc.getValue();}
    private static byte[] encode(Entry entry)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeInt(1);out.writeObject(entry);}
        if(bytes.size()>MAX_RECORD)throw new IOException("Journal record exceeds limit");return bytes.toByteArray();
    }
    private static Entry decode(byte[] bytes)throws IOException {
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(info->{
                if(info.depth()>1024||info.references()>10_000_000||info.arrayLength()>MAX_RECORD)return ObjectInputFilter.Status.REJECTED;
                Class<?> type=info.serialClass();if(type==null)return ObjectInputFilter.Status.UNDECIDED;
                while(type.isArray())type=type.getComponentType();String name=type.getName();
                return type.isPrimitive()||name.startsWith("java.lang.")||name.startsWith("java.util.")||name.startsWith("java.time.")
                    ||name.startsWith("zombie.savefile.ApocBR")?ObjectInputFilter.Status.ALLOWED:ObjectInputFilter.Status.REJECTED;
            });
            if(in.readInt()!=1)throw new IOException("Unsupported journal version");return (Entry)in.readObject();
        }catch(ClassNotFoundException|ClassCastException error){throw new IOException("Unsupported journal operation",error);}
    }
    @Override public synchronized void close()throws IOException {if(closed)return;flush();closed=true;file.close();ownerLock.release();owner.close();}
}
