// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.ai.states;

import zombie.UsedFromLua;
import zombie.ai.State;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameClient;

@UsedFromLua
public final class FakeDeadZombieState extends State {
    private static final FakeDeadZombieState INSTANCE = new FakeDeadZombieState();

    public static FakeDeadZombieState instance() {
        return INSTANCE;
    }

    private FakeDeadZombieState() {
        super(false, false, false, false);
    }

    @Override
    public void enter(IsoGameCharacter owner) {
        owner.setVisibleToNPCs(false);
        owner.setCollidable(false);
        ((IsoZombie)owner).setFakeDead(true);
        owner.setOnFloor(true);
    }

    @Override
    public void execute(IsoGameCharacter owner) {
        if (owner.isDead() && !GameClient.client) {
            new IsoDeadBody(owner);
        }
    }

    @Override
    public void exit(IsoGameCharacter owner) {
        ((IsoZombie)owner).setFakeDead(false);
    }
}
