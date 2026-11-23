package cloud.apocalipse.pz.agent.features.hotfixes;

import cloud.apocalipse.pz.agent.AgentModules;
import gnu.trove.list.array.TFloatArrayList;
import jassimp.AiAnimation;
import jassimp.AiNodeAnim;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.vector.Matrix4f;
import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.IsoPropertyType;
import zombie.core.random.RandStandard;
import zombie.core.skinnedmodel.model.SkeletonBone;
import zombie.core.skinnedmodel.model.SkinningBoneHierarchy;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.model.jassimp.ImportedSkeleton;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.entity.Component;
import zombie.entity.GameEntity;
import zombie.entity.components.spriteconfig.SpriteConfig;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.network.GameServer;
import zombie.scripting.objects.ModelScript;

/** Exercises vanilla asset paths under the installed agent in a disposable JVM. */
public final class AssetLogOriginalMain {
    public static void main(String[] args) throws Exception {
        boolean enabled = Boolean.parseBoolean(args[1]);
        var module = AgentModules.all().stream().filter(AssetLogModule.class::isInstance).findFirst().orElseThrow();
        check((module.disabledReason() == null) == enabled, "Hotfix setting");
        check(module.hookChecks().size() == 6, "Six registered call sites");
        for (var hook : module.hookChecks()) {
            var target = Class.forName(hook.targetClass(), false, AssetLogOriginalMain.class.getClassLoader());
            var jar = Path.of(target.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(Files.isSameFile(Path.of(args[0]), jar), "Expected original game class " + target);
        }

        GameServer.server = true;
        RandStandard.INSTANCE.init();
        for (var category : List.of(DebugType.General, DebugType.Ragdoll, DebugType.Script, DebugType.WorldGen, DebugType.Sprite)) {
            category.setLogSeverity(LogSeverity.General);
        }
        var log = DebugLog.getInstance();
        var output = new ByteArrayOutputStream();
        log.setStdOut(output);
        log.setStdErr(output);
        try {
            spriteConfig();
            ragdoll();
            mesh();
            importedChannels();
            tileProperties();
            duplicateSprite();
        } finally {
            log.setStdOut(System.out);
            log.setStdErr(System.err);
        }

        String diagnostics = output.toString();
        for (String message : List.of("Invalid SpriteConfig object!", "SkeletonBone not resolved for bone:",
                "no such mesh", "Could not find bone index for node name:", "Property Name not found: disableFade",
                "duplicate texture")) {
            check(diagnostics.contains(message) == !enabled, message + System.lineSeparator() + diagnostics);
        }
        check(diagnostics.contains("Sprite config has no script saved."), "Other SpriteConfig diagnostics still log");
        System.out.println("Original-JAR asset log probe passed with hotfix enabled=" + enabled);
    }

    private static void spriteConfig() throws Exception {
        var constructor = SpriteConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var spriteConfig = constructor.newInstance();
        method(Component.class, "setOwner", GameEntity.class).invoke(spriteConfig, new IsoObject());
        method(SpriteConfig.class, "initObjectInfo").invoke(spriteConfig);
        check(!spriteConfig.isValid() && spriteConfig.getObjectInfo() == null
            && spriteConfig.getFaceInfo() == null && spriteConfig.getTileInfo() == null, "Invalid sprite config stays reset");
        method(SpriteConfig.class, "load", ByteBuffer.class, int.class)
            .invoke(spriteConfig, ByteBuffer.wrap(new byte[]{0}), 0);
    }

    private static void ragdoll() {
        var bones = new HashMap<>(Map.of("Bip01_HeadNub", 0));
        var data = new SkinningData(new HashMap<>(), List.of(), List.of(), List.of(new Matrix4f()), List.of(-1), bones);
        var hierarchy = new SkinningBoneHierarchy();
        hierarchy.buildBoneHierarchy(data);
        check(hierarchy.isValid() && hierarchy.getRootBoneAt(0).skeletonBone == SkeletonBone.None,
            "Ragdoll retains the fallback root");
    }

    private static void mesh() throws Exception {
        String name = "asset_log_probe_missing_mesh";
        method(ModelScript.class, "checkMesh", String.class, String.class).invoke(null, "Base.AssetLogProbe", name);
        var field = ModelScript.class.getDeclaredField("reported");
        field.setAccessible(true);
        check(((Set<?>) field.get(null)).contains(name), "Mesh stays recorded as missing");
    }

    private static void importedChannels() throws Exception {
        var importedConstructor = ImportedSkeleton.class.getDeclaredConstructor();
        importedConstructor.setAccessible(true);
        var skeleton = importedConstructor.newInstance();
        var indices = ImportedSkeleton.class.getDeclaredField("boneIndices");
        indices.setAccessible(true);
        indices.set(skeleton, new HashMap<>(Map.of("Bip01", 0)));

        var animationConstructor = AiAnimation.class.getDeclaredConstructor(String.class, double.class, double.class);
        animationConstructor.setAccessible(true);
        var animation = animationConstructor.newInstance("asset-log-probe", 1.0, 1.0);
        var channelConstructor = AiNodeAnim.class.getDeclaredConstructor(
            String.class, int.class, int.class, int.class, int.class, int.class);
        channelConstructor.setAccessible(true);
        var matched = channelConstructor.newInstance("Bip01", 0, 0, 0, 0, 0);
        animation.getChannels().add(channelConstructor.newInstance("HLPR=RemapSpace", 0, 0, 0, 0, 0));
        animation.getChannels().add(matched);
        var channelsByBone = new ArrayList<List<AiNodeAnim>>();
        channelsByBone.add(null);
        var times = new TFloatArrayList[1];
        method(ImportedSkeleton.class, "collectBoneFrames", AiAnimation.class, TFloatArrayList[].class, ArrayList.class)
            .invoke(skeleton, animation, times, channelsByBone);
        check(List.of(matched).equals(channelsByBone.getFirst()) && times[0] != null,
            "Import drops the unmapped channel and collects the matched channel");
    }

    private static void tileProperties() throws Exception {
        var aliases = new TilePropertyAliasMap();
        method(TilePropertyAliasMap.class, "register", String.class, ArrayList.class)
            .invoke(aliases, "disableFade", new ArrayList<>(List.of("true")));
        int id = aliases.getIDFromPropertyName("disableFade");
        check(id >= 0 && aliases.getPropertyValueString(id, 0).equals("true"), "Custom tile property is registered");
        String knownProperty = IsoPropertyType.ENTITY_SCRIPT_NAME.getName();
        check(knownProperty.equals(IsoPropertyType.lookupOrDefaultStr(knownProperty)), "Known property resolves");
        try {
            IsoPropertyType.lookup("disableFade");
            throw new AssertionError("Strict lookup should still throw");
        } catch (IsoPropertyType.IsoPropertyTypeNotFoundException expected) {
            check(expected.getMessage().contains("disableFade"), "Strict lookup failure");
        }
    }

    private static void duplicateSprite() {
        var sprites = new IsoSpriteManager();
        String texture = "asset_log_probe_duplicate_texture";
        sprites.AddSprite(texture, 100);
        var replacement = sprites.AddSprite(texture, 200);
        check(sprites.getSprite(texture) == replacement && sprites.getSprite(100) == replacement
            && sprites.getSprite(200) == null, "Duplicate texture reuses original sprite ID");
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        var method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
