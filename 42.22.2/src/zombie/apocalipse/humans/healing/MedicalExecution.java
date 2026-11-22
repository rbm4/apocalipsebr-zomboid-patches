package zombie.apocalipse.humans.healing;

/** Server-thread medical transaction. Native mutation is injected and committed once at its deadline. */
public final class MedicalExecution {
    public interface World {
        boolean valid();
        boolean start();
        long durationNanos();
        boolean commit();
        void stop();
    }
    private final World world;
    private HumanHealingPort.Status status=HumanHealingPort.Status.RUNNING;
    private long deadline;
    private boolean started;
    public MedicalExecution(World world){this.world=java.util.Objects.requireNonNull(world);}
    public HumanHealingPort.Status status(){return status;}
    public HumanHealingPort.Status update(long now) {
        if(status!=HumanHealingPort.Status.RUNNING)return status;
        try {
            if(!world.valid())return finish(HumanHealingPort.Status.FAILED);
            if(!started){
                started=true;
                if(!world.start())return finish(HumanHealingPort.Status.FAILED);
                deadline=now+world.durationNanos();
            }
            if(now>=deadline){
                // Mark terminal before side effects; a failed/polled commit can never replay consumption.
                status=HumanHealingPort.Status.FAILED;
                try {if(world.valid()&&world.commit())status=HumanHealingPort.Status.COMPLETED;}
                finally {world.stop();}
            }
            return status;
        } catch(RuntimeException ex){
            if(status==HumanHealingPort.Status.RUNNING)finish(HumanHealingPort.Status.FAILED);
            throw ex;
        }
    }
    private HumanHealingPort.Status finish(HumanHealingPort.Status result){
        status=result;if(started)world.stop();return status;
    }
    public void cancel(){if(status==HumanHealingPort.Status.RUNNING)finish(HumanHealingPort.Status.CANCELLED);}
}
