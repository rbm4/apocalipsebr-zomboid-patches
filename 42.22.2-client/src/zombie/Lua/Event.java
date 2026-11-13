// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.Lua;

import java.util.ArrayList;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Platform;
import zombie.GameProfiler;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;

public final class Event {
    public static final int ADD = 0;
    public static final int NUM_FUNCTIONS = 1;
    private final Event.Add add;
    private final Event.Remove remove;
    public final ArrayList<LuaClosure> callbacks = new ArrayList<>();
    public String name;
    private final int index;

    public boolean trigger(KahluaTable env, LuaCaller caller, Object[] params) {
        if (this.callbacks.isEmpty()) {
            return false;
        } else {
            GameProfiler profiler = GameProfiler.getInstance();
            if (DebugOptions.instance.checks.slowLuaEvents.getValue()) {
                for (int n = 0; n < this.callbacks.size(); n++) {
                    LuaClosure closure = this.callbacks.get(n);

                    try (GameProfiler.ProfileArea var20 = profiler.profile("Lua - " + this.name)) {
                        long start = System.nanoTime();
                        long apocCallbackStarted = zombie.ApocBRClientTelemetryLite.beginLuaCallback(this.name);
                        try {
                            caller.protectedCallVoid(LuaManager.thread, closure, params);
                        } finally {
                            if (apocCallbackStarted != 0L) {
                                var apocPrototype = closure == null ? null : closure.prototype;
                                zombie.ApocBRClientTelemetryLite.luaCallback(apocCallbackStarted,
                                    apocPrototype == null ? null : apocPrototype.file != null ? apocPrototype.file : apocPrototype.filename,
                                    apocPrototype == null ? null : apocPrototype.name,
                                    apocPrototype != null && apocPrototype.lines != null && apocPrototype.lines.length > 0
                                        ? apocPrototype.lines[0] : -1, System.identityHashCode(closure));
                            }
                        }
                        double delayMS = (System.nanoTime() - start) / 1000000.0;
                        if (delayMS > 250.0) {
                            DebugType.Lua.warn("SLOW Lua event callback %s %s %dms", closure.prototype.file, closure, (int)delayMS);
                        }
                    } catch (Exception var15) {
                        ExceptionLogger.logException(var15);
                    }

                    if (!this.callbacks.contains(closure)) {
                        n--;
                    }
                }

                return true;
            } else {
                for (int n = 0; n < this.callbacks.size(); n++) {
                    LuaClosure closure = this.callbacks.get(n);

                    try (GameProfiler.ProfileArea ex = profiler.profile("Lua - " + this.name)) {
                        long apocCallbackStarted = zombie.ApocBRClientTelemetryLite.beginLuaCallback(this.name);
                        try {
                            caller.protectedCallVoid(LuaManager.thread, closure, params);
                        } finally {
                            if (apocCallbackStarted != 0L) {
                                var apocPrototype = closure == null ? null : closure.prototype;
                                zombie.ApocBRClientTelemetryLite.luaCallback(apocCallbackStarted,
                                    apocPrototype == null ? null : apocPrototype.file != null ? apocPrototype.file : apocPrototype.filename,
                                    apocPrototype == null ? null : apocPrototype.name,
                                    apocPrototype != null && apocPrototype.lines != null && apocPrototype.lines.length > 0
                                        ? apocPrototype.lines[0] : -1, System.identityHashCode(closure));
                            }
                        }
                    } catch (Exception var17) {
                        ExceptionLogger.logException(var17);
                    }

                    if (!this.callbacks.contains(closure)) {
                        n--;
                    }
                }

                return true;
            }
        }
    }

    public Event(String name, int index) {
        this.index = index;
        this.name = name;
        this.add = new Event.Add(this);
        this.remove = new Event.Remove(this);
    }

    public void register(Platform platform, KahluaTable environment) {
        KahluaTable table = platform.newTable();
        table.rawset("Add", this.add);
        table.rawset("Remove", this.remove);
        environment.rawset(this.name, table);
    }

    public static final class Add implements JavaFunction {
        Event e;

        public Add(Event e) {
            this.e = e;
        }

        /**
         * Description copied from interface: se.krka.kahlua.vm.JavaFunction
         * @return N, number of return values. The top N objects on the stack are considered the return values.
         */
        @Override
        public int call(LuaCallFrame callFrame, int nArguments) {
            if (LuaCompiler.rewriteEvents) {
                return 0;
            } else {
                if (callFrame.get(0) instanceof LuaClosure tab) {
                    this.e.callbacks.add(tab);
                }

                return 0;
            }
        }
    }

    public static final class Remove implements JavaFunction {
        Event e;

        public Remove(Event e) {
            this.e = e;
        }

        /**
         * Description copied from interface: se.krka.kahlua.vm.JavaFunction
         * @return N, number of return values. The top N objects on the stack are considered the return values.
         */
        @Override
        public int call(LuaCallFrame callFrame, int nArguments) {
            if (LuaCompiler.rewriteEvents) {
                return 0;
            } else {
                if (callFrame.get(0) instanceof LuaClosure tab) {
                    this.e.callbacks.remove(tab);
                }

                return 0;
            }
        }
    }
}
