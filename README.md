# Apocalipse [BR] - Project Zomboid Server Patches

Custom server-side Java patches used on the **[Apocalipse \[BR\]](https://apocalipse.cloud)** Project Zomboid server.

These patches fix bugs and improve multiplayer behavior that cannot be addressed through Lua mods alone. They work by placing a corrected `.class` file next to the game's JAR, which the JVM then loads instead of the original - **the game files are never modified or overwritten.**

---

## What Is This?

Project Zomboid runs on Java. The game ships with compiled `.class` files packed inside a `projectzomboid.jar` archive. Because of how Java's classpath works, you can place a replacement `.class` file loose in the game directory and the JVM will load yours first, silently overriding the original.

This repository contains:

- **Patch scripts** (`.ps1` for Windows, `.sh` for Linux) that compile and deploy those replacement classes automatically.
- A **version folder** for each game build (`42.17.0/`, etc.) containing the patches that apply to that version.
- A **main launcher script** (`patch.ps1` / `patch.sh`) at the root of the repository that guides you through the entire process step by step.

You do **not** need to know Java or programming to use these. Just follow the steps below.

---

## Patches Included

Each version of the game will have it's own patches, this happens because the way java patching works is replacing the entire original class with our newer one, so every single patch must be done without breaking the behaviour and signature of a class while addressing the issues the patch is intended to address.

---

## Requirements

| Requirement                          | Notes                                                                                         |
| ------------------------------------ | --------------------------------------------------------------------------------------------- |
| Project Zomboid dedicated server     | Windows or Linux                                                                              |
| Internet connection (first run only) | The script will automatically download a Java 25 compiler if one is not found on your machine |
| PowerShell 5.1+                      | Windows only - already included in Windows 10/11                                              |
| Bash + Python 3                      | Linux only - already present on most server distributions                                     |

> **You do not need to install Java yourself.** The launcher will find it if it exists, or download Azul Zulu JDK 25 automatically.

---

## How to Use - Windows

> These steps are for Windows servers or local installations. All commands are run in **PowerShell**.

### 1. Download the repository

Click the green **Code** button on this page and choose **Download ZIP**, then extract it anywhere you like.

Alternatively, if you have Git installed:
```powershell
git clone https://github.com/apocalipsebr/apocalipsebr-zomboid-patches.git
cd apocalipsebr-zomboid-patches
```

### 2. Open PowerShell

Press `Win + X` and choose **Windows PowerShell** (or **Terminal** on Windows 11). Run as administrator to avoid conflicts with permissions. 
Navigate to the folder where you extracted the repository:
```powershell
cd "C:\path\to\apocalipsebr-zomboid-patches"
```

### 3. Run the launcher

```powershell
.\patch.ps1
```

> If you see an error about script execution being blocked, run this first and then try again:
> ```powershell
> Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy Bypass
> ```

### 4. Follow the prompts

The launcher will ask you a series of questions:

1. **Select a version** - choose the number matching your server's game build (e.g. `42.17.0`)
2. **Path to your Project Zomboid installation** - paste either the folder path or the full path to `projectzomboid.jar`. Both work:
   ```
   Z:\SteamLibrary\steamapps\common\ProjectZomboid
   ```
   or
   ```
   Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar
   ```
   The script will tell you if the path is wrong so you can try again.
3. **Which patch to apply** - type `0` to apply all patches, or a number to apply just one.

The script then compiles and deploys the patch automatically. When it finishes you will see:
```
=== Done ===
```

### 5. Restart your server

The patch takes effect on the next server start. No further action is needed.

---

### Reverting (Windows)

To remove a patch and go back to the original game behavior:

```powershell
.\patch.ps1 -Revert
```

---

## How to Use - Linux

> These steps are for Linux dedicated servers. All commands are run in a **terminal / SSH session**.

### 1. Download the repository

```bash
git clone https://github.com/apocalipsebr/apocalipsebr-zomboid-patches.git
cd apocalipsebr-zomboid-patches
```

Or download the ZIP and extract it:
```bash
unzip apocalipsebr-zomboid-patches-main.zip
cd apocalipsebr-zomboid-patches-main
```

### 2. Make the launcher executable

```bash
chmod +x patch.sh
```

### 3. Run the launcher

```bash
./patch.sh
```

### 4. Follow the prompts

The launcher will ask:

1. **Select a version** - type the number matching your server's game build.
2. **Path to your Project Zomboid installation** - paste either the folder path or the full path to `projectzomboid.jar`. Both work:
   ```
   /opt/pzserver/java
   ```
   or
   ```
   /opt/pzserver/java/projectzomboid.jar
   ```
3. **Which patch to apply** - type `0` for all patches, or a number for one.

The script compiles and deploys automatically. When done you will see:
```
Session complete - 1 passed, 0 failed
```

### 5. Restart your server

The patch takes effect on the next server start.

---

### Reverting (Linux)

```bash
./patch.sh --revert
```

---

## Advanced Options

Both launchers accept optional flags if you want to skip the interactive prompts:

| Flag (Windows)                                                             | Flag (Linux)                                                                 | Description                                                      |
| -------------------------------------------------------------------------- | ---------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `-PZJar "path\to\ProjectZomboid"` or `-PZJar "path\to\projectzomboid.jar"` | `--pz-jar /path/to/ProjectZomboid` or `--pz-jar /path/to/projectzomboid.jar` | Skip the path prompt — accepts a folder or the JAR file directly |
| `-Version 42.17.0`                                                         | `--version 42.17.0`                                                          | Skip the version prompt                                          |
| `-DryRun`                                                                  | `--dry-run`                                                                  | Show what would happen without writing any files                 |
| `-Revert`                                                                  | `--revert`                                                                   | Remove deployed patches and restore original behavior            |

Example (Windows, non-interactive):
```powershell
.\patch.ps1 -PZJar "Z:\Steam\ProjectZomboid" -Version 42.17.0
# or with the full JAR path:
.\patch.ps1 -PZJar "Z:\Steam\ProjectZomboid\projectzomboid.jar" -Version 42.17.0
```

Example (Linux, non-interactive):
```bash
./patch.sh --pz-jar /opt/pzserver/java --version 42.17.0
# or with the full JAR path:
./patch.sh --pz-jar /opt/pzserver/java/projectzomboid.jar --version 42.17.0
```

---

## How It Works (Plain English)

When Project Zomboid starts, Java looks for class files in this order:

1. Loose `.class` files in the game directory (or `java/` subfolder on Linux)
2. Inside `projectzomboid.jar`

Because of this order, placing a patched `.class` file at the right path means the JVM loads the fixed version and never even looks at the one inside the JAR. **The JAR is never touched.** Removing the loose `.class` file at any time fully restores original behavior.

---

## Troubleshooting

**"Path not found" or "projectzomboid.jar not found in folder"**  
You can pass either the folder containing `projectzomboid.jar` or the full path to the file itself. On a default Steam install the folder is usually:
- Windows: `C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid`
- Linux: `/opt/pzserver/java` (may vary by hosting provider)

**"Compilation failed"**  
This usually means the patch was written for a different game build. Check that the version folder matches your server build number. If your build is newer than the latest version folder, the patch may not have been updated yet - open an issue on this repository.

**Patch applied but behavior unchanged after restart**  
Verify the `.class` file was actually deployed to the right location. Run the launcher with `-DryRun` / `--dry-run` to see where files would be placed, then compare with your actual game directory.

**The script downloaded a JDK but it takes up disk space**  
The downloaded JDK is stored in a `jdk/` folder inside this repository. You can delete it at any time; the next run will find your system Java or download it again.

---

## About Apocalipse [BR]

**[Apocalipse \[BR\]](https://apocalipse.cloud)** is a Brazilian Project Zomboid multiplayer community. These patches are maintained by the server team to improve the gameplay experience for all players. If you run your own server and find them useful, you are welcome to use them.
