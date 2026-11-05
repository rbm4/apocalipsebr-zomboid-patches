package zombie.apocalipse.humans.healing;

/** Worker-facing detached observations and commands. No native references cross this port. */
public interface HumanHealingPort {
    enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }
    record Snapshot(boolean available, long sampledAt, boolean wounded, boolean supply, boolean recovering) { }
    interface Execution {
        Status update(long now);
        void cancel();
    }
    Snapshot snapshot();
    Execution begin(long now);
    HumanHealingPort UNSUPPORTED = new HumanHealingPort() {
        public Snapshot snapshot(){return new Snapshot(false,0,false,false,false);}
        public Execution begin(long now){return null;}
    };
}
