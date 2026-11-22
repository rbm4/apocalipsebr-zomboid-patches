package zombie.apocalipse.humans.healing;

import zombie.apocalipse.humans.state.HumanState;

/** Server-owned, per-incarnation convalescence latch; no native injury or durable timer. */
public final class HumanRecoveryState {
    private boolean recovering,initialized;
    public boolean recovering(){return recovering;}
    public void update(HumanState state,boolean wounded){
        float entry=initialized?.65F:.85F;initialized=true;
        if(state.dead()){recovering=false;return;}
        if(wounded||state.vitals().health()<entry)recovering=true;
        else if(state.vitals().health()>=.85F&&state.injuries().leg()==0&&state.injuries().arms()==0)recovering=false;
    }
}
