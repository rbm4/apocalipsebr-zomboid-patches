package zombie.Lua;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.core.logger.ExceptionLogger;

public final class ApocBRMainThreadLuaQueue {
    private static final ConcurrentLinkedQueue<ApocBRMainThreadLuaQueue.QueuedLuaCall> queue = new ConcurrentLinkedQueue<>();
    private static final int DRAIN_LIMIT = Integer.getInteger("apocbr.lua.mainThreadDrainLimit", 512);

    private ApocBRMainThreadLuaQueue() {
    }

    public static boolean enqueueVoid(String functionName, Object... args) {
        if (functionName == null || functionName.isBlank()) {
            return false;
        }

        queue.offer(new ApocBRMainThreadLuaQueue.QueuedLuaCall(functionName, null, args));
        return true;
    }

    public static boolean enqueueVoid(Object functionObj, Object... args) {
        if (functionObj == null) {
            return false;
        }

        queue.offer(new ApocBRMainThreadLuaQueue.QueuedLuaCall(null, functionObj, args));
        return true;
    }

    public static int drain() {
        if (LuaManager.thread == null || LuaManager.caller == null) {
            return 0;
        }

        int drained = 0;
        int limit = Math.max(1, DRAIN_LIMIT);
        for (ApocBRMainThreadLuaQueue.QueuedLuaCall call = queue.poll(); call != null && drained < limit; call = queue.poll()) {
            call.invoke();
            drained++;
        }

        return drained;
    }

    public static int size() {
        return queue.size();
    }

    private static final class QueuedLuaCall {
        private final String functionName;
        private final Object functionObj;
        private final Object[] args;

        private QueuedLuaCall(String functionName, Object functionObj, Object[] args) {
            this.functionName = functionName;
            this.functionObj = functionObj;
            this.args = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
        }

        private void invoke() {
            Object target = this.functionObj;
            if (target == null && this.functionName != null) {
                target = LuaManager.getFunctionObject(this.functionName);
            }

            if (target == null) {
                return;
            }

            try {
                LuaManager.caller.protectedCallVoid(LuaManager.thread, target, this.args);
            } catch (RuntimeException ex) {
                ExceptionLogger.logException(ex);
            }
        }
    }
}
