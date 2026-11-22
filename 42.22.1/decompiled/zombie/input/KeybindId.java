// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.input;

import zombie.UsedFromLua;
import zombie.scripting.objects.Registries;
import zombie.scripting.objects.RegistryReset;
import zombie.scripting.objects.ResourceLocation;

@UsedFromLua
public final class KeybindId {
    public static final KeybindId FORWARD = registerBase("Forward");
    public static final KeybindId BACKWARD = registerBase("Backward");
    public static final KeybindId LEFT = registerBase("Left");
    public static final KeybindId RIGHT = registerBase("Right");
    public static final KeybindId RUN = registerBase("Run");
    public static final KeybindId INTERACT = registerBase("Interact");
    public static final KeybindId SPRINT = registerBase("Sprint");
    public static final KeybindId ROTATE_BUILDING = registerBase("Rotate building");
    public static final KeybindId TOGGLE_MODE = registerBase("Toggle mode");
    public static final KeybindId TOGGLE_SEARCH_MODE = registerBase("Toggle Search Mode");
    public static final KeybindId CANCEL_ACTION = registerBase("CancelAction");
    public static final KeybindId WALK_TO = registerBase("WalkTo");
    public static final KeybindId RELEASE_ROPE = registerBase("ReleaseRope");
    public static final KeybindId SIT_ON_GROUND = registerBase("SitOnGround");
    public static final KeybindId ATTACK_CLICK = registerBase("Attack/Click");
    public static final KeybindId CROUCH = registerBase("Crouch");
    public static final KeybindId AIM = registerBase("Aim");
    public static final KeybindId MELEE = registerBase("Melee");
    public static final KeybindId RACK_FIREARM = registerBase("Rack Firearm");
    public static final KeybindId RELOAD_WEAPON = registerBase("ReloadWeapon");
    public static final KeybindId SHARPEN_WEAPON = registerBase("SharpenWeapon");
    public static final KeybindId MANUAL_FLOOR_ATTACK = registerBase("ManualFloorAtk");
    public static final KeybindId START_VEHICLE_ENGINE = registerBase("StartVehicleEngine");
    public static final KeybindId BRAKE = registerBase("Brake");
    public static final KeybindId CRUISE_CONTROL = registerBase("CruiseControl");
    public static final KeybindId TOGGLE_VEHICLE_HEADLIGHTS = registerBase("ToggleVehicleHeadlights");
    public static final KeybindId VEHICLE_HEATER = registerBase("VehicleHeater");
    public static final KeybindId VEHICLE_MECHANICS = registerBase("VehicleMechanics");
    public static final KeybindId VEHICLE_HORN = registerBase("VehicleHorn");
    public static final KeybindId VEHICLE_RADIAL_MENU = registerBase("VehicleRadialMenu");
    public static final KeybindId VEHICLE_SWITCH_SEAT = registerBase("VehicleSwitchSeat");
    public static final KeybindId TOGGLE_UI = registerBase("Toggle UI");
    public static final KeybindId CRAFTING_UI = registerBase("Crafting UI");
    public static final KeybindId BUILDING_UI = registerBase("Building UI");
    public static final KeybindId MAIN_MENU = registerBase("Main Menu");
    public static final KeybindId TOGGLE_INVENTORY = registerBase("Toggle Inventory");
    public static final KeybindId TOGGLE_SKILL_PANEL = registerBase("Toggle Skill Panel");
    public static final KeybindId TOGGLE_HEALTH_PANEL = registerBase("Toggle Health Panel");
    public static final KeybindId TOGGLE_INFO_PANEL = registerBase("Toggle Info Panel");
    public static final KeybindId TOGGLE_CLOTHING_PROTECTION_PANEL = registerBase("Toggle Clothing Protection Panel");
    public static final KeybindId TOGGLE_MOVABLE_PANEL_MODE = registerBase("Toggle Moveable Panel Mode");
    public static final KeybindId MAP = registerBase("Map");
    public static final KeybindId ANIMAL_RADIAL_MENU = registerBase("AnimalRadialMenu");
    public static final KeybindId TAKE_SCREENSHOT = registerBase("Take screenshot");
    public static final KeybindId TOGGLE_SURVIVAL_GUIDE = registerBase("Toggle Survival Guide");
    public static final KeybindId DISPLAY_FPS = registerBase("Display FPS");
    public static final KeybindId PAUSE = registerBase("Pause");
    public static final KeybindId NORMAL_SPEED = registerBase("Normal Speed");
    public static final KeybindId FAST_FORWARD_X1 = registerBase("Fast Forward x1");
    public static final KeybindId FAST_FORWARD_X2 = registerBase("Fast Forward x2");
    public static final KeybindId FAST_FORWARD_X3 = registerBase("Fast Forward x3");
    public static final KeybindId PAN_CAMERA = registerBase("PanCamera");
    public static final KeybindId ZOOM_IN = registerBase("Zoom in");
    public static final KeybindId ZOOM_OUT = registerBase("Zoom out");
    public static final KeybindId HOTBAR_1 = registerBase("Hotbar 1");
    public static final KeybindId HOTBAR_2 = registerBase("Hotbar 2");
    public static final KeybindId HOTBAR_3 = registerBase("Hotbar 3");
    public static final KeybindId HOTBAR_4 = registerBase("Hotbar 4");
    public static final KeybindId HOTBAR_5 = registerBase("Hotbar 5");
    public static final KeybindId HOTBAR_6 = registerBase("Hotbar 6");
    public static final KeybindId HOTBAR_7 = registerBase("Hotbar 7");
    public static final KeybindId HOTBAR_8 = registerBase("Hotbar 8");
    public static final KeybindId LIGHT_SOURCE = registerBase("Equip/Turn On/Off Light Source");
    public static final KeybindId DROP_BOTH_HELD_ITEMS = registerBase("DropBothHeldItems");
    public static final KeybindId DROP_PRIMARY_HELD_ITEM = registerBase("DropPrimaryHeldItem");
    public static final KeybindId DROP_SECONDARY_HELD_ITEM = registerBase("DropSecondaryHeldItem");
    public static final KeybindId DROP_WORN_BAG = registerBase("DropWornBag");
    public static final KeybindId DROP_BOTH_HELD_ITEMS_AND_WORN_BAG = registerBase("DropBothHeldItemsAndWornBag");
    public static final KeybindId GRAB_CORPSE = registerBase("GrabCorpse");
    public static final KeybindId TOGGLE_SAFETY = registerBase("Toggle Safety");
    public static final KeybindId TOGGLE_CHAT = registerBase("Toggle chat");
    public static final KeybindId ALT_TOGGLE_CHAT = registerBase("Alt toggle chat");
    public static final KeybindId SWITCH_CHAT_STREAM = registerBase("Switch chat stream");
    public static final KeybindId ENABLE_VOICE_TRANSMIT = registerBase("Enable voice transmit");
    public static final KeybindId SHOUT = registerBase("Shout");
    public static final KeybindId EMOTE = registerBase("Emote");
    public static final KeybindId TOGGLE_MUSIC = registerBase("Toggle Music");
    public static final KeybindId TOGGLE_LUA_DEBUGGER = registerBase("Toggle Lua Debugger");
    public static final KeybindId TOGGLE_LUA_CONSOLE = registerBase("ToggleLuaConsole");
    public static final KeybindId TOGGLE_GOD_MODE = registerBase("ToggleGodModeInvisible");
    public static final KeybindId TOGGLE_MODELS_ENABLED = registerBase("ToggleModelsEnabled");
    public static final KeybindId TOGGLE_ANIMATION_TEXT = registerBase("ToggleAnimationText");
    public static final KeybindId TOGGLE_OLD_RENDERER = registerBase("ToggleOldRenderer");
    private final String id;

    private KeybindId(String id) {
        this.id = id;
    }

    public String getId() {
        return this.id;
    }

    public static KeybindId register(String id) {
        return register(false, id);
    }

    private static KeybindId registerBase(String id) {
        return register(true, id);
    }

    private static KeybindId register(boolean allowDefaultNamespace, String id) {
        return Registries.KEYBIND_ID.register(RegistryReset.createLocation(id, allowDefaultNamespace), new KeybindId(id));
    }

    public static KeybindId get(ResourceLocation id) {
        return Registries.KEYBIND_ID.get(id);
    }

    @Override
    public String toString() {
        return this.id;
    }
}
