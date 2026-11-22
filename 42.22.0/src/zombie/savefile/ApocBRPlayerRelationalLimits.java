package zombie.savefile;

/** Initial-import capacity is independent of the small live-field queue. JVM startup settings. */
final class ApocBRPlayerRelationalLimits {
    private static final String PREFIX="apocbr.postgres.playerRelational.";
    static final int GRAPH_BYTES=mib("maxGraphMiB",128,512);
    static final int SNAPSHOT_BYTES=mib("maxSnapshotMiB",32,128);
    static final int SOURCE_BYTES=mib("maxSourceMiB",8,64);
    static final long QUEUE_BYTES=Math.max(mib("queuedMiB",64,512),(long)SNAPSHOT_BYTES+SOURCE_BYTES);
    static final int ROWS=setting("maxRows",500_000,1,5_000_000);
    static final int NODES=setting("maxNodes",1_000_000,1,5_000_000);
    static final int IMPORT_WRITE_MS=setting("importWriteBudgetMs",60_000,1_000,300_000);
    static final int IMPORT_QUERY_SECONDS=(IMPORT_WRITE_MS+999)/1000;
    private static int mib(String name,int fallback,int ceiling){return setting(name,fallback,1,ceiling)*1024*1024;}
    private static int setting(String name,int fallback,int floor,int ceiling){return Math.max(floor,Math.min(ceiling,Integer.getInteger(PREFIX+name,fallback)));}
    static final class SizeExceeded extends IllegalStateException {
        final long bytes,byteLimit;
        final int rows,items,nodes;
        SizeExceeded(String code,long bytes,long byteLimit,int rows,int items,int nodes) {
            super(code);this.bytes=bytes;this.byteLimit=byteLimit;this.rows=rows;this.items=items;this.nodes=nodes;
        }
        String diagnostic(){return getMessage()+"; estimatedBytes="+bytes+"; byteLimit="+byteLimit
                +"; rows="+rows+"; rowLimit="+ROWS+"; items="+items+"; nodes="+nodes+"; nodeLimit="+NODES;}
    }
    private ApocBRPlayerRelationalLimits() {}
}
