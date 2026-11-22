// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core;

import zombie.UsedFromLua;
import zombie.scripting.objects.Registries;
import zombie.scripting.objects.RegistryReset;
import zombie.scripting.objects.ResourceLocation;

@UsedFromLua
public class GameMode {
    public static final GameMode APOCALYPSE = registerBase("Apocalypse");
    public static final GameMode OUTBREAK = registerBase("Outbreak");
    public static final GameMode EXTINCTION = registerBase("Extinction");
    public static final GameMode RISING = registerBase("Rising");
    public static final GameMode SANDBOX = registerBase("Sandbox");
    public static final GameMode CHALLENGES = registerBase("Challenges");
    public static final GameMode TUTORIAL = registerBase("Tutorial", false);
    public static final GameMode MULTIPLAYER = registerBase("Multiplayer", false);
    public static final GameMode WINTER_IS_COMING = registerBase("Winter is Coming", false);
    public static final GameMode TWENTY_HEIGHT_MINUTES_LATER = registerBase("28 Minutes Later", false);
    public static final GameMode TOP_OF_THE_WORLD = registerBase("Top Of The World", false);
    private final String id;
    private final String title;
    private final String description;
    private final String thumbnail;
    private final String video;
    private final boolean displayed;

    private GameMode(String id, boolean isDisplayed) {
        this.id = id;
        this.title = "UI_NewGame_%s".formatted(id);
        this.description = "%s_desc".formatted(this.title);
        this.thumbnail = "media/ui/playstyleIcons/%s.png".formatted(id);
        this.video = "%s.bik".formatted(id);
        this.displayed = isDisplayed;
    }

    public static GameMode get(ResourceLocation id) {
        return Registries.GAME_MODE.get(id);
    }

    public String getTitle() {
        return this.title;
    }

    public String getDescription() {
        return this.description;
    }

    public String getThumbnail() {
        return this.thumbnail;
    }

    public String getVideo() {
        return this.video;
    }

    @Override
    public String toString() {
        return this.id;
    }

    public static GameMode register(String id, boolean isDisplayed) {
        return register(false, id, isDisplayed);
    }

    private static GameMode registerBase(String id) {
        return register(true, id, true);
    }

    private static GameMode registerBase(String id, boolean isDisplayed) {
        return register(true, id, isDisplayed);
    }

    private static GameMode register(boolean allowDefaultNamespace, String id, boolean isDisplayed) {
        return Registries.GAME_MODE.register(RegistryReset.createLocation(id, allowDefaultNamespace), new GameMode(id, isDisplayed));
    }

    static {
        DevValidation.translationKeys(Registries.GAME_MODE, (gameMode, validate) -> {
            if (gameMode.displayed) {
                validate.accept(gameMode.title);
                validate.accept(gameMode.description);
            }
        });
    }
}
