// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.ai.states;

import java.util.Map;
import zombie.GameTime;
import zombie.IndieGL;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.ai.State;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;
import zombie.core.math.PZMath;
import zombie.core.properties.IsoPropertyType;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.debug.DebugType;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.scripting.objects.CharacterTrait;
import zombie.scripting.objects.MoodleType;
import zombie.scripting.objects.SoundKey;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

@UsedFromLua
public final class ClimbState extends State {
    public static final String CLIMB_ROPE = "ClimbRope";
    public static final float CLIMB_SPEED_FACTOR = 10.0F;
    public static final float CLIMB_DIRTY_RECALC_GRID_STACK_TIME = 2.0F;
    public static final float CLIMBING_LADDER_SPEED_END_TOP = 0.06F;
    public static final float CLIMBING_LADDER_SPEED = 0.07F;
    private static final ClimbState INSTANCE = new ClimbState();
    public static final float MUSCLE_STRAIN_FACTOR = 0.02F;
    public static final float THRESHOLD_CLIMB_LOOP = 0.75F;
    public static final float THRESHOLD_CLIMB_BLOCKED = 0.6F;
    public static final float FALL_CHANCE_BASE = 1.0F;
    public static final float FALL_CHANCE_MULTIPLIER = 10.0F;
    private static final float FALL_CHANCE_SCALE = 100.0F;
    public static final float CLIMB_SPEED = 0.16F;
    public static final float CLIMB_SLOWDOWN = 0.5F;
    public static final State.Param<Float> SPEED = State.Param.ofFloat("speed", 0.0F);
    public static final State.Param<Boolean> CLIMB = State.Param.ofBool("climb", false);
    public static final State.Param<IsoObject> CLIMBING_LADDER = State.Param.of("climbingLadder", IsoObject.class);
    public static final State.Param<ClimbState.LadderDirection> DIRECTION = State.Param.of("direction", ClimbState.LadderDirection.class);
    public static final String LADDER_DIRECTION = "ladderDirection";
    public static final String CLIMB_LADDER_LOOP = "climbLadderLoop";
    public static final String CLIMB_LADDER_END = "climbLadderEnd";
    private int numberOfFallingChecks;

    public static ClimbState instance() {
        return INSTANCE;
    }

    private ClimbState() {
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
                isoGameCharacter.setVariableEnum("ladderDirection", ClimbState.LadderDirection.UP);
                isoGameCharacter.set(CLIMBING_LADDER, ladder);
            }
        }

        if (!isoGameCharacter.isClimbingLadder()) {
            isoGameCharacter.setVariable("ClimbRope", true);
            isoGameCharacter.getEmitter().playSoundImpl(SoundKey.CLIMB_SHEET_ROPE.getSoundName(), null);
        }

        this.setParams(isoGameCharacter, State.Stage.Enter);
        calculateClimb(isoGameCharacter);
        this.numberOfFallingChecks = 0;
        isoGameCharacter.setHeadCameraZ(true);
    }

    @Override
    public void execute(IsoGameCharacter isoGameCharacter) {
        isoGameCharacter.StopAllActionQueue();
        if (!ClimbSystem.isDirectionChanged(isoGameCharacter)) {
            ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
            isoGameCharacter.applyIdealDirection(isoGameCharacter.isClimbingLadder());
            if (!shouldSkipMovingUp(isoGameCharacter)) {
                float climbSpeed = isoGameCharacter.getClimbRopeSpeed(false);
                if (!isoGameCharacter.isLocal()) {
                    climbSpeed = isoGameCharacter.get(SPEED, isoGameCharacter.getClimbRopeSpeed(false));
                }

                if (isoGameCharacter.getVariableBoolean("climbLadderEnd")) {
                    climbSpeed = 0.06F;
                } else if (isoGameCharacter.getVariableBoolean("climbLadderLoop")) {
                    climbSpeed = 0.07F;
                }

                isoGameCharacter.getSpriteDef().animFrameIncrease = climbSpeed;
                float currentClimbHeight = isoGameCharacter.getZ() + climbSpeed / 10.0F * GameTime.instance.getMultiplier();
                isoGameCharacter.setZ(currentClimbHeight);
                if (currentClimbHeight < climbData.targetFallHeight && currentClimbHeight > climbData.targetClimbHeight) {
                    this.finishClimbing(isoGameCharacter);
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
                        DebugType.Action.println("Checking For Fall #%d", this.numberOfFallingChecks++);
                    }

                    if (!isoGameCharacter.isClimbingLadder()) {
                        if (!IsoWindow.isSheetRopeHere(isoGameCharacter.getCurrentSquare())) {
                            isoGameCharacter.setCollidable(true);
                            isoGameCharacter.setbClimbing(false);
                            isoGameCharacter.setbFalling(true);
                            isoGameCharacter.clearVariable("ClimbRope");
                        } else if (canFallCheck && !SandboxOptions.instance.easyClimbing.getValue() && fall && isoGameCharacter.isLocal()) {
                            isoGameCharacter.fallFromRope();
                        }
                    }

                    isoGameCharacter.addBothArmMuscleStrain(ClimbSystem.getClimbingMuscleStrain(isoGameCharacter, 0.02F));
                    if (isoGameCharacter instanceof IsoPlayer isoPlayer && isoPlayer.isLocalPlayer()) {
                        isoPlayer.dirtyRecalcGridStackTime = 2.0F;
                    }
                }
            }
        }
    }

    private static boolean shouldSkipMovingUp(IsoGameCharacter isoGameCharacter) {
        return isoGameCharacter.isClimbingLadder()
            && !isoGameCharacter.getVariableBoolean("climbLadderLoop")
            && !isoGameCharacter.getVariableBoolean("climbLadderEnd");
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
        isoGameCharacter.set(DIRECTION, ClimbState.LadderDirection.IDLE);
        this.setParams(isoGameCharacter, State.Stage.Exit);
    }

    @Override
    public void setParams(IsoGameCharacter isoGameCharacter, State.Stage stage) {
        if (isoGameCharacter.isLocal()) {
            isoGameCharacter.set(SPEED, isoGameCharacter.getClimbRopeSpeed(false));
            isoGameCharacter.set(CLIMB, isoGameCharacter.isClimbing());
            isoGameCharacter.set(DIRECTION, ClimbState.LadderDirection.UP);
        } else {
            isoGameCharacter.setVariableEnum("ladderDirection", isoGameCharacter.get(DIRECTION));
            super.setParams(isoGameCharacter, stage);
        }
    }

    private static ClimbState.ClimbStatus calculateClimbOutcome(IsoGameCharacter isoGameCharacter) {
        ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
        IsoGridSquare exitDirectionIsqGridSquare = climbData.targetGridSquare.getAdjacentSquare(isoGameCharacter.getForwardIsoDirection());
        if (isoGameCharacter.isClimbingLadder()) {
            return exitDirectionIsqGridSquare != null
                    && (exitDirectionIsqGridSquare.TreatAsSolidFloor() || exitDirectionIsqGridSquare.getLadder() != null)
                    && !exitDirectionIsqGridSquare.isSolidTrans()
                    && (!climbData.targetGridSquare.isWallTo(exitDirectionIsqGridSquare) || climbData.targetGridSquare.isHoppableTo(exitDirectionIsqGridSquare))
                ? ClimbState.ClimbStatus.LADDER_OFF_TOP
                : ClimbState.ClimbStatus.LADDER_IDLE;
        } else if (!exitDirectionIsqGridSquare.TreatAsSolidFloor()) {
            return ClimbState.ClimbStatus.BLOCKED;
        } else {
            IsoWindow window = climbData.targetGridSquare.getWindowTo(exitDirectionIsqGridSquare);
            if (window != null) {
                if (!window.IsOpen()) {
                    window.ToggleWindow(isoGameCharacter);
                }

                if (!window.canClimbThrough(isoGameCharacter)) {
                    return ClimbState.ClimbStatus.BLOCKED;
                } else {
                    climbData.climbTargetIsoObject = window;
                    return ClimbState.ClimbStatus.OPEN_WINDOW;
                }
            } else {
                IsoThumpable isoThumpable = climbData.targetGridSquare.getWindowThumpableTo(exitDirectionIsqGridSquare);
                if (isoThumpable != null) {
                    if (!isoThumpable.canClimbThrough(isoGameCharacter)) {
                        return ClimbState.ClimbStatus.BLOCKED;
                    } else {
                        climbData.climbTargetIsoObject = isoThumpable;
                        return ClimbState.ClimbStatus.OPEN_WINDOW;
                    }
                } else {
                    isoThumpable = climbData.targetGridSquare.getHoppableThumpableTo(exitDirectionIsqGridSquare);
                    if (isoThumpable != null) {
                        return !IsoWindow.canClimbThroughHelper(
                                isoGameCharacter,
                                climbData.targetGridSquare,
                                exitDirectionIsqGridSquare,
                                isoGameCharacter.getForwardIsoDirection() == IsoDirections.N || isoGameCharacter.getForwardIsoDirection() == IsoDirections.S
                            )
                            ? ClimbState.ClimbStatus.BLOCKED
                            : ClimbState.ClimbStatus.FENCE;
                    } else {
                        IsoWindowFrame isoWindowFrame = climbData.targetGridSquare.getWindowFrameTo(exitDirectionIsqGridSquare);
                        if (isoWindowFrame != null) {
                            if (!isoWindowFrame.canClimbThrough(isoGameCharacter)) {
                                return ClimbState.ClimbStatus.BLOCKED;
                            } else {
                                climbData.climbTargetIsoObject = isoWindowFrame;
                                return ClimbState.ClimbStatus.WINDOW_FRAME;
                            }
                        } else {
                            IsoObject hoppableWall = climbData.targetGridSquare.getWallHoppableTo(exitDirectionIsqGridSquare);
                            if (hoppableWall == null) {
                                return ClimbState.ClimbStatus.UNDEFINED;
                            } else {
                                return !IsoWindow.canClimbThroughHelper(
                                        isoGameCharacter,
                                        climbData.targetGridSquare,
                                        exitDirectionIsqGridSquare,
                                        isoGameCharacter.getForwardIsoDirection() == IsoDirections.N
                                            || isoGameCharacter.getForwardIsoDirection() == IsoDirections.S
                                    )
                                    ? ClimbState.ClimbStatus.BLOCKED
                                    : ClimbState.ClimbStatus.FENCE;
                            }
                        }
                    }
                }
            }
        }
    }

    private void finishClimbing(IsoGameCharacter isoGameCharacter) {
        ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
        isoGameCharacter.setZ(climbData.targetClimbHeight);
        isoGameCharacter.setCurrent(climbData.targetGridSquare);
        isoGameCharacter.setCollidable(true);
        switch (climbData.exitBlocked) {
            case UNDEFINED:
                DebugType.General.debugln("climbing exit is undefined");
                break;
            case BLOCKED:
                isoGameCharacter.climbDown();
                break;
            case OPEN_WINDOW:
                isoGameCharacter.climbThroughWindow(climbData.climbTargetIsoObject);
                break;
            case WINDOW_FRAME:
                isoGameCharacter.climbThroughWindowFrame((IsoWindowFrame)climbData.climbTargetIsoObject);
                break;
            case FENCE:
                isoGameCharacter.climbOverFence(isoGameCharacter.getForwardIsoDirection());
                break;
            case LADDER_IDLE:
                isoGameCharacter.climbIdleLadder();
                break;
            case LADDER_OFF_TOP:
                isoGameCharacter.finishClimbingLadder();
                climbData.targetClimbHeight += 0.6F;
        }
    }

    private static void calculateClimb(IsoGameCharacter isoGameCharacter) {
        isoGameCharacter.setIdealDirection(isoGameCharacter.isClimbingLadder(), isoGameCharacter.get(CLIMBING_LADDER));
        isoGameCharacter.applyIdealDirection(isoGameCharacter.isClimbingLadder());
        ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
        IsoGridSquare currentSquare = isoGameCharacter.getCurrentSquare();
        int maxLevel = currentSquare.getChunk().getMaxLevel() + 1;
        IsoCell cell = IsoWorld.instance.getCell();

        for (int z = PZMath.fastfloor(isoGameCharacter.getZ()); z <= maxLevel; z++) {
            IsoGridSquare isoGridSquare = cell.getGridSquare((double)isoGameCharacter.getX(), (double)isoGameCharacter.getY(), (double)z);
            if (IsoWindow.isTopOfSheetRopeHere(isoGridSquare)) {
                climbData.targetGridSquare = isoGridSquare;
                climbData.targetClimbHeight = z;
                climbData.exitBlocked = calculateClimbOutcome(isoGameCharacter);
                break;
            }

            if (isoGameCharacter.isClimbingLadder()) {
                if (isoGridSquare != null && isoGridSquare.z > currentSquare.z && isoGridSquare.isSolidFloor()) {
                    climbData.targetGridSquare = cell.getGridSquare((double)isoGameCharacter.getX(), (double)isoGameCharacter.getY(), (double)z);
                    climbData.targetClimbHeight = z - 0.75F;
                    climbData.exitBlocked = ClimbState.ClimbStatus.LADDER_IDLE;
                    break;
                }

                if (isoGridSquare == null || isoGridSquare.getLadder() == null) {
                    climbData.targetGridSquare = cell.getGridSquare((double)isoGameCharacter.getX(), (double)isoGameCharacter.getY(), (double)z);
                    if (climbData.targetGridSquare == null) {
                        climbData.targetGridSquare = IsoGridSquare.getNew(IsoCell.getInstance(), null, currentSquare.getX(), currentSquare.getY(), z);
                        IsoCell.getInstance().ConnectNewSquare(climbData.targetGridSquare, true);
                    }

                    climbData.targetClimbHeight = z - 0.6F;
                    climbData.exitBlocked = calculateClimbOutcome(isoGameCharacter);
                    break;
                }
            }
        }

        fallChanceCalculation(isoGameCharacter);
    }

    private static float fallChanceCalculation(IsoGameCharacter isoGameCharacter) {
        ClimbState.ClimbData climbData = isoGameCharacter.getClimbData();
        climbData.fallChance = isoGameCharacter.getClimbingFailChanceFloat() + 1.0F;
        isoGameCharacter.setClimbRopeTime(isoGameCharacter.getClimbRopeTime() + GameTime.instance.getMultiplier());
        climbData.fallChance *= 100.0F;
        climbData.fallChance = climbData.fallChance / (GameTime.instance.getMultiplier() < 1.0F ? 1.0F : (int)GameTime.instance.getMultiplier());
        return climbData.fallChance;
    }

    public void debug(IsoGameCharacter isoGameCharacter) {
        ClimbState.ClimbData debugClimbData = isoGameCharacter.getClimbData();
        IsoGridSquare currentIsoGridSquare = isoGameCharacter.getCurrentSquare();
        if (currentIsoGridSquare.haveSheetRope) {
            IndieGL.glBlendFunc(770, 771);
            IndieGL.disableDepthTest();
            IndieGL.StartShader(0);
            int sx = (int)IsoUtils.XToScreenExact(currentIsoGridSquare.getX(), currentIsoGridSquare.getY(), currentIsoGridSquare.getZ(), 0);
            int sy = (int)IsoUtils.YToScreenExact(currentIsoGridSquare.getX(), currentIsoGridSquare.getY(), currentIsoGridSquare.getZ() + 1.5F, 0);
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("Climb Sheet Rope").append("\n");
            stringBuilder.append("Fail Chance: ").append(isoGameCharacter.getClimbingFailChanceFloat()).append("\n");
            stringBuilder.append("Fall Chance: ").append(debugClimbData.fallChance).append("\n");
            stringBuilder.append("Distance: ").append(debugClimbData.targetClimbHeight + 1.0F).append("\n");
            stringBuilder.append("Climbing Skills:").append("\n");
            float chance = isoGameCharacter.getPerkLevel(PerkFactory.Perks.Strength) * 2.0F;
            stringBuilder.append(
                String.format("   %s: %d %.2f%%%n", PerkFactory.Perks.Strength.name, isoGameCharacter.getPerkLevel(PerkFactory.Perks.Strength), chance)
            );
            chance = isoGameCharacter.getPerkLevel(PerkFactory.Perks.Fitness) * 2.0F;
            stringBuilder.append(
                String.format("   %s: %d %.2f%%%n", PerkFactory.Perks.Fitness.name, isoGameCharacter.getPerkLevel(PerkFactory.Perks.Fitness), chance)
            );
            chance = isoGameCharacter.getPerkLevel(PerkFactory.Perks.Nimble) * 2.0F;
            stringBuilder.append(
                String.format("   %s: %d %.2f%%%n", PerkFactory.Perks.Nimble.name, isoGameCharacter.getPerkLevel(PerkFactory.Perks.Nimble), chance)
            );
            stringBuilder.append("Climbing Bonus:").append("\n");
            chance = !isoGameCharacter.isWearingAwkwardGloves() && isoGameCharacter.isWearingGloves() ? 4.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %.2f%%%n", "Wearing Gloves", chance));
            chance = isoGameCharacter.hasTrait(CharacterTrait.DEXTROUS) ? 4.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %.2f%%%n", CharacterTrait.DEXTROUS.toString(), chance));
            chance = isoGameCharacter.hasTrait(CharacterTrait.BURGLAR) ? 4.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %.2f%%%n", CharacterTrait.BURGLAR.toString(), chance));
            chance = isoGameCharacter.hasTrait(CharacterTrait.GYMNAST) ? 4.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %.2f%%%n", CharacterTrait.GYMNAST.toString(), chance));
            stringBuilder.append("Climbing Penalty:").append("\n");
            chance = isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.ENDURANCE) * -5.0F;
            stringBuilder.append(
                String.format("   %s: %d %.2f%%%n", MoodleType.ENDURANCE.toString(), isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.ENDURANCE), chance)
            );
            chance = isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.DRUNK) * -8.0F;
            stringBuilder.append(
                String.format("   %s: %d %.2f%%%n", MoodleType.DRUNK.toString(), isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.DRUNK), chance)
            );
            chance = isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.HEAVY_LOAD) * -8.0F;
            stringBuilder.append(
                String.format(
                    "   %s: %d %.2f%%%n", MoodleType.HEAVY_LOAD.toString(), isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.HEAVY_LOAD), chance
                )
            );
            chance = isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.PAIN) * -5.0F;
            stringBuilder.append(
                String.format("   %s: %d %.2f%%%n", MoodleType.PAIN.toString(), isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.PAIN), chance)
            );
            chance = isoGameCharacter.hasTrait(CharacterTrait.OBESE) ? -25.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %.2f%%%n", CharacterTrait.OBESE.toString(), chance));
            chance = isoGameCharacter.hasTrait(CharacterTrait.OVERWEIGHT) ? -15.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %.2f%%%n", CharacterTrait.OVERWEIGHT.toString(), chance));
            chance = isoGameCharacter.hasTrait(CharacterTrait.CLUMSY) ? 2.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %s%n", CharacterTrait.CLUMSY.toString(), chance == 2.0F ? "Half" : ""));
            chance = isoGameCharacter.isWearingAwkwardGloves() ? 2.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %s%n", "Wearing Awkward Gloves", chance == 2.0F ? "Half" : ""));
            chance = isoGameCharacter.hasTrait(CharacterTrait.ALL_THUMBS) ? -4.0F : 0.0F;
            stringBuilder.append(String.format("   %s: %.2f%%%n", CharacterTrait.ALL_THUMBS.toString(), chance));
            chance = isoGameCharacter.nearbyZombieClimbPenalty();
            stringBuilder.append(String.format("   %s: %.2f%%%n", "Nearby Zombies", chance));
            TextManager.instance.DrawString(UIFont.NewMedium, sx, sy, 1.5, stringBuilder.toString(), 1.0, 1.0, 1.0, 1.0);
        }
    }

    @Override
    public boolean isProcessedOnEnter() {
        return true;
    }

    @Override
    public void processOnEnter(IsoGameCharacter owner, Map<Object, Object> delegate) {
        owner.setbClimbing(CLIMB.fromDelegate(delegate));
        owner.setVariableEnum("ladderDirection", ClimbState.LadderDirection.UP);
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

    public static class ClimbData {
        public static final float LADDER_IDEAL_X = 0.54F;
        public static final float LADDER_IDEAL_Y = 0.6F;
        public static final float ROPE_IDEAL_X_NORTH = 0.54F;
        public static final float ROPE_IDEAL_Y_NORTH = 0.39F;
        public static final float ROPE_IDEAL_X_WEST = 0.4F;
        public static final float ROPE_IDEAL_Y_WEST = 0.7F;
        public static final float ROPE_IDEAL_X_SOUTH = 0.118F;
        public static final float ROPE_IDEAL_Y_SOUTH = 0.5756F;
        public static final float ROPE_IDEAL_X_EAST = 0.5417F;
        public static final float ROPE_IDEAL_Y_EAST = 0.3144F;
        public float targetClimbHeight;
        public float fallChance;
        public float idealx;
        public float idealy;
        public float targetFallHeight = Float.MAX_VALUE;
        public IsoObject climbTargetIsoObject;
        public IsoGridSquare targetGridSquare;
        public ClimbState.ClimbStatus exitBlocked = ClimbState.ClimbStatus.UNDEFINED;
    }

    public static enum ClimbStatus {
        UNDEFINED,
        BLOCKED,
        OPEN_WINDOW,
        WINDOW_FRAME,
        FENCE,
        LADDER_IDLE,
        LADDER_OFF_TOP;
    }

    public static enum LadderDirection {
        UP,
        DOWN,
        IDLE;
    }
}
