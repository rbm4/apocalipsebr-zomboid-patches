package zombie.core.znet;

/** Steam boundary fixture for the installed Workshop instrumentation. */
public final class SteamWorkshop {
    public static final SteamWorkshop instance = new SteamWorkshop();
    public ISteamWorkshopCallback callback;
    public int created;
    public int released;
    public int removed;
    public int reads;
    public int results = 18;
    public boolean failCreate;
    public boolean failQuery;
    public boolean failRead;
    public boolean complete = true;
    public int updateAt = -1;
    private boolean delivered;

    public long CreateQueryUGCDetailsRequest(long[] ids, ISteamWorkshopCallback listener) {
        if (ids.length != 2 || ids[0] != 1 || ids[1] != 2) throw new AssertionError("wrong Workshop IDs");
        callback = listener;
        created++;
        delivered = false;
        return failCreate ? 0 : created;
    }

    public void pump() {
        if (callback == null || delivered || !complete) return;
        delivered = true;
        callback.onItemQueryCompleted(created + 1, 1);
        if (failQuery) callback.onItemQueryNotCompleted(created, 21);
        else callback.onItemQueryCompleted(created, results);
    }

    public SteamUGCDetails GetQueryUGCResult(long handle, int index) {
        if (handle != created || callback == null) throw new AssertionError("read outside live query");
        reads++;
        if (failRead) throw new IllegalStateException("query read failed");
        return index == updateAt ? new SteamUGCDetails(42, "test", 1, 5, 0, null) : null;
    }

    public long GetItemState(long id) { return SteamWorkshopItem.ItemState.Installed.getValue(); }
    public long GetItemInstallTimeStamp(long id) { return 4; }

    public void RemoveCallback(ISteamWorkshopCallback listener) {
        if (listener != callback) throw new AssertionError("unexpected callback");
        callback = null;
        removed++;
    }

    public boolean ReleaseQueryUGCRequest(long handle) {
        if (handle != created || callback != null) throw new AssertionError("bad handle cleanup");
        released++;
        return true;
    }
}
