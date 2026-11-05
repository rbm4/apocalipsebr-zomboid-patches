// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.ai.states;

import java.util.Map;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.ai.State;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.properties.IsoPropertyType;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoWindow;
import zombie.scripting.objects.SoundKey;

@UsedFromLua
public final class ClimbDownState extends State {
    private static final ClimbDownState INSTANCE = new ClimbDownState();
    public static final String LADDER_MOVE_DOWN = "ladderMoveDown";
    private static final float CLIMB_DOWN_FALL_CHANCE_SCALE = 300.0F;
    public static final State.Param<Float> SPEED = State.Param.ofFloat("speed", 0.0F);
    public static final State.Param<Boolean> CLIMB = State.Param.ofBool("climb", false);
    public static final State.Param<IsoObject> CLIMBING_LADDER = State.Param.of("climbingLadder", IsoObject.class);
    public static final State.Param<ClimbState.LadderDirection> DIRECTION = State.Param.of("direction", ClimbState.LadderDirection.class);
    public static final float CLIMBING_LADDER_SPEED_START_TOP = 0.065F;
    public static final float MUSCLE_STRAIN_FACTOR = 0.007F;
    private int numberOfFallingChecks;

    public static ClimbDownState instance() {
        return INSTANCE;
    }

    private ClimbDownState() {
        super(true, true, true, true);
        this.addAnimEventListener("PlayLadderFootstep", this::OnAnimEvent_PlayLadderFootstep);
    }

    @Override
    public boolean isIgnoreCollide(IsoGameCharacter owner, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return true;
    }

    @Override
    public void enter(IsoGameCharacter isoGameCharacter) {
        isoGameCharacter.setIgnoreMovement(true);
        isoGameCharacter.setHideWeaponModel(true);
        isoGameCharacter.setbClimbing(true);
        if (isoGameCharacter.getCurrentSquare() != null) {
            IsoObject ladder = isoGameCharacter.getCurrentSquare().getLadder();
            if (ladder != null) {
                isoGameCharacter.setVariableEnum("ladderDirection", ClimbState.LadderDirection.DOWN);
                isoGameCharacter.set(CLIMBING_LADDER, ladder);
            }
        }

        if (!isoGameCharacter.isClimbingDownLadder()) {
            isoGameCharacter.setVariable("ClimbRope", true);
            isoGameCharacter.getEmitter().playSoundImpl(SoundKey.CLIMB_SHEET_ROPE.getSoundName(), null);
        }

        this.setParams(isoGameCharacter, State.Stage.Enter);
        calculateClimbDown(isoGameCharacter);
        this.numberOfFallingChecks = 0;
        isoGameCharacter.setHeadCameraZ(true);
    }

    @Override
    public void execute(IsoGameCharacter isoGameCharacter) {
        isoGameCharacter.StopAllActionQueue();
        if (!ClimbSystem.isDirectionChanged(isoGameCharacter)) {
            ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
            isoGameCharacter.applyIdealDirection(isoGameCharacter.isClimbingDownLadder());
            float climbSpeed = isoGameCharacter.getClimbRopeSpeed(true);
            if (!isoGameCharacter.isLocal()) {
                climbSpeed = isoGameCharacter.get(SPEED, isoGameCharacter.getClimbRopeSpeed(true));
            }

            if (isoGameCharacter.getVariableBoolean("climbLadderLoop")) {
                climbSpeed = 0.07F;
            } else if (isoGameCharacter.isClimbingDownLadder() && !isoGameCharacter.getVariableBoolean("climbLadderLoop")) {
                climbSpeed = 0.065F;
            }

            isoGameCharacter.getSpriteDef().animFrameIncrease = climbSpeed;
            float minLevel = isoGameCharacter.getCurrentSquare().getChunk().getMinLevel();
            if (isoGameCharacter.isClimbingDownLadder()) {
                minLevel = climbData.targetClimbHeight;
            }

            float currentClimbHeight = isoGameCharacter.getZ() - climbSpeed / 10.0F * GameTime.instance.getMultiplier();
            currentClimbHeight = Math.max(currentClimbHeight, minLevel);
            if (!isoGameCharacter.isClimbingDownLadder()
                || isoGameCharacter.getVariableBoolean("climbLadderLoop")
                || isoGameCharacter.getVariableBoolean("ladderMoveDown")) {
                isoGameCharacter.setZ(currentClimbHeight);
            }

            if (currentClimbHeight <= climbData.targetClimbHeight) {
                if (climbData.exitBlocked == ClimbState.ClimbStatus.LADDER_IDLE) {
                    isoGameCharacter.climbIdleLadder();
                } else {
                    this.finishClimbing(isoGameCharacter);
                }
            } else {
                if (!isoGameCharacter.isLocal() && isoGameCharacter.getVariableBoolean("climbLadderLoop")) {
                    isoGameCharacter.setX(isoGameCharacter.realx);
                    isoGameCharacter.setY(isoGameCharacter.realy);
                    isoGameCharacter.setZ(isoGameCharacter.getClimbZ());
                }

                fallChanceCalculation(isoGameCharacter);
                boolean canFallCheck = isoGameCharacter.getClimbRopeTime() > climbData.fallChance * 10.0F;
                boolean fall = Rand.NextBool((int)climbData.fallChance);
                if (canFallCheck) {
                    DebugLog.log(DebugType.Action, "Checking For Fall #%d".formatted(this.numberOfFallingChecks++));
                }

                if (!isoGameCharacter.isClimbingDownLadder()) {
                    if (!IsoWindow.isSheetRopeHere(isoGameCharacter.getCurrentSquare())) {
                        isoGameCharacter.setCollidable(true);
                        isoGameCharacter.setbClimbing(false);
                        isoGameCharacter.setbFalling(true);
                        isoGameCharacter.clearVariable("ClimbRope");
                    } else if (canFallCheck && !SandboxOptions.instance.easyClimbing.getValue() && fall && isoGameCharacter.isLocal()) {
                        isoGameCharacter.fallFromRope();
                    }
                }

                isoGameCharacter.addBothArmMuscleStrain(ClimbSystem.getClimbingMuscleStrain(isoGameCharacter, 0.007F));
                if (isoGameCharacter instanceof IsoPlayer isoPlayer && isoPlayer.isLocalPlayer()) {
                    isoPlayer.dirtyRecalcGridStackTime = 2.0F;
                }
            }
        }
    }

    @Override
    public void exit(IsoGameCharacter isoGameCharacter) {
        isoGameCharacter.setHeadCameraZ(false);
        isoGameCharacter.setCollidable(true);
        isoGameCharacter.setIgnoreMovement(false);
        isoGameCharacter.setHideWeaponModel(false);
        isoGameCharacter.setbClimbing(false);
        isoGameCharacter.getEmitter().stopOrTriggerSoundByName(SoundKey.CLIMB_SHEET_ROPE.getSoundName());
        isoGameCharacter.clearVariable("ClimbRope");
        isoGameCharacter.set(CLIMBING_LADDER, null);
        isoGameCharacter.clearVariable("climbLadderEnd");
        isoGameCharacter.clearVariable("ladderMoveDown");
        isoGameCharacter.set(DIRECTION, ClimbState.LadderDirection.IDLE);
        this.setParams(isoGameCharacter, State.Stage.Exit);
    }

    @Override
    public void setParams(IsoGameCharacter isoGameCharacter, State.Stage stage) {
        if (isoGameCharacter.isLocal()) {
            isoGameCharacter.set(SPEED, isoGameCharacter.getClimbRopeSpeed(true));
            isoGameCharacter.set(CLIMB, isoGameCharacter.isClimbing());
            isoGameCharacter.set(DIRECTION, ClimbState.LadderDirection.DOWN);
        } else {
            isoGameCharacter.setVariableEnum("ladderDirection", isoGameCharacter.get(DIRECTION));
            super.setParams(isoGameCharacter, stage);
        }
    }

    private static void calculateClimbDown(IsoGameCharacter isoGameCharacter) {
        isoGameCharacter.setIdealDirection(isoGameCharacter.isClimbingDownLadder(), isoGameCharacter.get(CLIMBING_LADDER));
        isoGameCharacter.applyIdealDirection(isoGameCharacter.isClimbingDownLadder());
        ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
        IsoGridSquare currentSquare = isoGameCharacter.getCurrentSquare();
        int minLevel = currentSquare.getChunk().getMinLevel();
        IsoCell cell = IsoWorld.instance.getCell();
        int targetX = currentSquare.getX();
        int targetY = currentSquare.getY();
        int targetZ = currentSquare.getZ();
        if (isoGameCharacter.isClimbingDownLadder() && currentSquare.getLadder() == null) {
            IsoGridSquare adjacentSquare = currentSquare.getAdjacentSquare(isoGameCharacter.getForwardIsoDirection().Rot180());
            targetX = adjacentSquare.getX();
            targetY = adjacentSquare.getY();
            targetZ = adjacentSquare.getZ() - 1;
        }

        int z = PZMath.fastfloor((float)targetZ);

        while (z >= minLevel) {
            IsoGridSquare isoGridSquare = cell.getGridSquare(targetX, targetY, z);
            if (!isoGameCharacter.isClimbingDownLadder() || isoGridSquare != null && isoGridSquare.getLadder() != null && !isoGridSquare.isSolidTrans()) {
                if (isoGridSquare == null || !isoGridSquare.has(IsoFlagType.solidtrans) && !isoGridSquare.TreatAsSolidFloor()) {
                    z--;
                    continue;
                }

                climbData.targetGridSquare = isoGridSquare;
                climbData.targetClimbHeight = z;
                climbData.exitBlocked = ClimbState.ClimbStatus.UNDEFINED;
                break;
            }

            climbData.targetGridSquare = isoGridSquare;
            climbData.targetClimbHeight = z + 1;
            if (isoGridSquare.isSolidTrans()) {
                climbData.targetClimbHeight = z + 0.5F;
            }

            climbData.exitBlocked = ClimbState.ClimbStatus.LADDER_IDLE;
            break;
        }

        fallChanceCalculation(isoGameCharacter);
    }

    private static float fallChanceCalculation(IsoGameCharacter isoGameCharacter) {
        ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
        climbData.fallChance = isoGameCharacter.getClimbingFailChanceFloat() + 1.0F;
        isoGameCharacter.setClimbRopeTime(isoGameCharacter.getClimbRopeTime() + GameTime.instance.getMultiplier());
        climbData.fallChance *= 300.0F;
        climbData.fallChance = climbData.fallChance / (GameTime.instance.getMultiplier() < 1.0F ? 1.0F : (int)GameTime.instance.getMultiplier());
        return climbData.fallChance;
    }

    private void finishClimbing(IsoGameCharacter isoGameCharacter) {
        ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
        isoGameCharacter.setZ(climbData.targetClimbHeight);
        isoGameCharacter.clearVariable("ClimbRope");
        isoGameCharacter.setCollidable(true);
        if (isoGameCharacter.isClimbingDownLadder()) {
            isoGameCharacter.finishClimbingLadder();
        }

        isoGameCharacter.clear(this);
    }

    @Override
    public boolean isProcessedOnEnter() {
        return true;
    }

    @Override
    public void processOnEnter(IsoGameCharacter owner, Map<Object, Object> delegate) {
        owner.setbClimbing(CLIMB.fromDelegate(delegate));
        owner.setVariableEnum("ladderDirection", ClimbState.LadderDirection.DOWN);
    }

    @Override
    public boolean isProcessedOnExit() {
        return true;
    }

    @Override
    public void processOnExit(IsoGameCharacter owner, Map<Object, Object> delegate) {
        owner.setbClimbing(CLIMB.fromDelegate(delegate));
        owner.setVariableEnum("ladderDirection", ClimbState.LadderDirection.IDLE);
    }

    private void OnAnimEvent_PlayLadderFootstep(IsoGameCharacter owner, AnimEvent event) {
        IsoObject ladderObj = owner.get(CLIMBING_LADDER);
        SoundKey soundKey = ladderObj != null && !ladderObj.propertyEqualsIgnoreCase(IsoPropertyType.MATERIAL.getName(), "Wood")
            ? SoundKey.LADDER_FOOTSTEPS_METAL
            : SoundKey.LADDER_FOOTSTEPS_WOOD;
        owner.getEmitter().playSoundImpl(soundKey.getSoundName(), null);
    }
}
