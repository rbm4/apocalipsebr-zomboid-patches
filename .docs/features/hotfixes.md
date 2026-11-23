# Server hotfixes

The `hotfixes` feature package contains bounded compatibility repairs for the
original 42.22.2 game JAR. Hotfixes are independently registered in
`AgentModules` and remain reversible through explicit JVM properties.

## XML case-insensitive asset lookup

`XmlCaseModule` instruments `zombie.util.PZXmlUtil.parseXmlInternal(String)`,
the original method that opens a resolved XML filename for parsing. It mutates
the filename argument before vanilla `FileInputStream` opens the file.

The lookup keeps exact paths unchanged. If the path is missing, it resolves each
path component case-insensitively only when exactly one filesystem entry matches.
Missing, unreadable and ambiguous paths are preserved so vanilla reports the
original error.

Enabled by default. Disable with:

```text
-Dapocbr.hotfixes.xmlCaseInsensitive.enabled=false
```

The legacy 42.22.2 patch also implements this behavior in
`PZXmlUtil`; the agent port supplies the same intent through Byte Buddy rather
than replacing or compiling the vanilla class.

## Optional animation folders

`OptionalAnimationFoldersModule` ports the 42.22.2 legacy guard in
`AdvancedAnimator.searchFolders(URI, Path)`. The original checksum scan uses
this method for `media/AnimSets` and `media/actiongroups` in the base game and
in each mod's common and version directories. When `Files.notExists` confirms
a scan root is absent, the hook returns an empty list before the file walker
can log `NoSuchFileException`. Existing roots and paths whose existence cannot
be determined continue through vanilla scanning and error reporting.

The hotfix is enabled by default. Set
`-Dapocbr.hotfixes.optionalAnimationFolders.enabled=false` to restore vanilla
scanning.

`OptionalAnimationFoldersTest` exercises the original mod scan with and without
the transformation. A separate Windows JVM check with the shaded agent and
original 42.22.2 JAR verified both settings and discovery of existing XML files.
Live dedicated-server startup with Workshop mods remains to be verified.

## Recoverable asset diagnostics

`AssetLogModule` replaces six logging calls in the original 42.22.2 JAR.
The legacy `SpriteConfig` patch removed the invalid-object warning before the
reset. The other sites follow the same pattern and keep the existing fallback.

| Original method | Silenced diagnostic | Vanilla behavior |
| --- | --- | --- |
| `SpriteConfig.initObjectInfo` | Invalid SpriteConfig object | Clears invalid object, face, and tile info |
| `SkinningBoneHierarchy.buildBoneHierarchy` | Unresolved skeleton bone | Uses `SkeletonBone.None` and builds the hierarchy |
| `ModelScript.checkMesh` | Missing mesh | Records the mesh in the reported set |
| `ImportedSkeleton.collectBoneFrames` | Missing bone index for an animation channel | Skips the unmapped channel and processes the remaining channels |
| `IsoPropertyType.lookupOrDefaultStr` | Unknown property entering the string fallback | Returns the original name for alias registration, including `disableFade` |
| `IsoSpriteManager.AddSprite(String, int)` | Duplicate texture | Reuses the original sprite ID |

The hotfix is enabled by default. Set `-Dapocbr.hotfixes.assetLogs.enabled=false`
to restore the six logs.

`AssetLogTest` exercises all six original methods through the installed agent
with both settings. The same probe passed on Windows with the shaded agent and
original 42.22.2 JAR. Live dedicated-server validation remains pending.
