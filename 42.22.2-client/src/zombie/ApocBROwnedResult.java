package zombie;

/** One unpublished result, exactly one claimant or cancelling disposer. No callbacks under the monitor. */
public final class ApocBROwnedResult<T> {
    private boolean closed, available;
    private T value;
    public synchronized boolean isClosed() { return closed; }
    public synchronized boolean publish(T result) {
        if (closed) return false;
        if (available) throw new IllegalStateException("Result already published");
        value = result; available = true; return true;
    }
    public synchronized boolean claim(T expected) {
        if (closed || !available || value != expected) return false;
        value = null; available = false; closed = true; return true;
    }
    /** A failed FutureTask has no published value, but its owner must still receive the failure once. */
    public synchronized boolean claimFailure() {
        if (closed || available) return false;
        closed = true; return true;
    }
    public synchronized T cancel() {
        closed = true;
        T result = available ? value : null;
        value = null; available = false; return result;
    }
}
