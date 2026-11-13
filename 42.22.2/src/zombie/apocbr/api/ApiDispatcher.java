package zombie.apocbr.api;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Detached values only. One worker, no Lua/game references, no automatic retries. */
final class ApiDispatcher implements AutoCloseable {
    static final int CAPACITY = 32;
    static final long MIN_INTERVAL_MS = 1500;
    static final long MAX_QUEUE_AGE_MS = 30_000;
    record Request(String id, URI uri, String method, String body, long submittedNanos) {}
    record Result(String id, int status, String body, String error) {}
    record Reply(int status, String body, String error, long cooldownMillis) {}
    interface Transport extends AutoCloseable {
        Reply send(URI uri) throws InterruptedException;
        default Reply send(URI uri,String method) throws InterruptedException { return send(uri); }
        default Reply send(URI uri,String method,String body) throws InterruptedException { return send(uri,method); }
        @Override void close();
    }
    private final ArrayBlockingQueue<Request> requests = new ArrayBlockingQueue<>(CAPACITY);
    private final ArrayBlockingQueue<Result> results = new ArrayBlockingQueue<>(CAPACITY);
    private final Transport transport;
    private final LongSupplier clock;
    private final long intervalNanos;
    private volatile boolean closed;
    private volatile long cooldownUntil;
    private long nextAdmission;
    private boolean admitted;
    private int outstanding;
    private Thread worker;

    ApiDispatcher(Transport transport, long intervalMillis, LongSupplier clock) {
        if (intervalMillis < MIN_INTERVAL_MS || intervalMillis > 60_000)
            throw new IllegalArgumentException("INVALID_INTERVAL");
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
    }

    synchronized void start() {
        if (worker != null || closed) throw new IllegalStateException("ALREADY_STARTED");
        worker = Thread.ofVirtual().name("ApocBR-API").start(this::run);
    }

    /** Non-waiting admission. Outstanding includes callbacks not yet drained. */
    synchronized String submit(String id, URI uri) {
        return submit(id,uri,"GET");
    }

    synchronized String submit(String id,URI uri,String method) {
        return submit(id,uri,method,null);
    }

    synchronized String submit(String id,URI uri,String method,String body) {
        if (!"GET".equals(method) && !"POST".equals(method)) return "INVALID_METHOD";
        if (body!=null && (!"POST".equals(method) || body.length()>1024)) return "INVALID_BODY";
        long now = clock.getAsLong();
        if (closed) return "STOPPED";
        if (cooldownUntil != 0 && now - cooldownUntil < 0) return "BACKEND_COOLDOWN";
        if (admitted && now - nextAdmission < 0) return "THROTTLED";
        if (outstanding >= CAPACITY) return "QUEUE_FULL";
        if (!requests.offer(new Request(id, uri, method, body, now))) return "QUEUE_FULL";
        outstanding++;
        admitted = true;
        nextAdmission = now + intervalNanos;
        return null;
    }

    synchronized Result poll() {
        Result result = results.poll();
        if (result != null) outstanding--;
        return result;
    }

    private void run() {
        long nextSend = 0;
        boolean sent = false;
        try {
            while (!closed) {
                Request request = requests.take();
                // Wire pacing is separate from admission: a slow response cannot create a burst.
                while (!closed) {
                    long now = clock.getAsLong();
                    long wait = Math.max(sent ? nextSend - now : 0,
                            cooldownUntil == 0 ? 0 : cooldownUntil - now);
                    long remainingAge = TimeUnit.MILLISECONDS.toNanos(MAX_QUEUE_AGE_MS)
                            - (now - request.submittedNanos());
                    if (wait <= 0 || remainingAge <= 0) break;
                    TimeUnit.NANOSECONDS.sleep(Math.min(wait, remainingAge));
                }
                if (closed) return;
                if (clock.getAsLong() - request.submittedNanos() >= TimeUnit.MILLISECONDS.toNanos(MAX_QUEUE_AGE_MS)) {
                    complete(new Result(request.id(), 0, null, "QUEUE_TIMEOUT"));
                    continue;
                }
                nextSend = clock.getAsLong() + intervalNanos;
                sent = true;
                Reply reply;
                try { reply = transport.send(request.uri(),request.method(),request.body()); }
                catch (RuntimeException failure) { reply = new Reply(0, null, "TRANSPORT_ERROR", 0); }
                if (reply.cooldownMillis() > 0)
                    cooldownUntil = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(
                            Math.min(3_600_000, Math.max(60_000, reply.cooldownMillis())));
                complete(new Result(request.id(), reply.status(), reply.body(), reply.error()));
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            closed = true;
            transport.close();
        }
    }

    private synchronized void complete(Result result) {
        if (!closed && !results.offer(result)) throw new IllegalStateException("RESULT_CAPACITY_INVARIANT");
    }

    boolean isRunning() { return !closed && worker != null; }

    @Override public synchronized void close() {
        closed = true;
        requests.clear();
        results.clear();
        outstanding = 0;
        if (worker != null) worker.interrupt();
        else transport.close();
    }
}
