// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import zombie.core.logger.ExceptionLogger;
import zombie.savefile.ServerPlayerDB;
import zombie.vehicles.VehiclesDB2;

public class ServerPlayersVehicles {
    public static final ServerPlayersVehicles instance = new ServerPlayersVehicles();
    private ServerPlayersVehicles.SPVThread thread;

    public void init() {
        this.thread = new ServerPlayersVehicles.SPVThread();
        this.thread.setName("ServerPlayersVehicles");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void stop() {
        if (this.thread != null) {
            this.thread.stop = true;

            while (this.thread.isAlive()) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException var2) {
                }
            }

            this.thread = null;
        }
    }

    private static final class SPVThread extends Thread {
        volatile boolean stop;

        @Override
        public void run() {
            zombie.savefile.ApocBRPostgresConnector.runBackgroundDatabaseWork(this::runDatabaseLoop);
        }

        private void runDatabaseLoop() {
            long lastFailureLog=0;boolean failed=false;
            try {
                while (!this.stop) {
                    try {
                        this.runInner();
                        if(failed){zombie.debug.DebugLog.log("[ApocBR][Postgres] PLAYER_VEHICLE_WORKER_RECOVERED");failed=false;lastFailureLog=0;}
                    } catch (Throwable var2) {
                        failed=true;long now=System.nanoTime();
                        if(lastFailureLog==0||now-lastFailureLog>=30_000_000_000L){lastFailureLog=now;ExceptionLogger.logException(var2);}
                    } finally {
                        // A failed initialization must not bypass the worker's normal pacing.
                        try{Thread.sleep(500L);}catch(InterruptedException ignored){}
                    }
                }
            } finally {
                zombie.savefile.ApocBRPostgresConnector.shutdown();
            }
        }

        void runInner() {
            ServerPlayerDB.getInstance().process();
            zombie.savefile.ApocBRPostgresConnector.process();
            VehiclesDB2.instance.updateWorldStreamer();

        }
    }
}
