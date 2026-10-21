package zombie.apocalipse.humans.brain;

/** Stationary policy proving that the runtime does not require a guide brain. */
public final class IdleBehavior implements HumanBehavior {
    private boolean stopped;
    public boolean needsPerception() { return false; }
    public void update(HumanObservation observation, HumanActuator actuator) { }
    public void suspend(long now, HumanActuator actuator) { actuator.stopMoving(); }
    public void resume(HumanObservation.Identity visitor, long now, HumanActuator actuator) { }
    public void stop(long now, HumanActuator actuator) { stopped = true; actuator.stopMoving(); }
    public boolean available() { return !stopped; }
}
