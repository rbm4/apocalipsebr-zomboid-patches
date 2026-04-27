<#
.SYNOPSIS
    Compiles patched RCONServer.java and deploys the .class to Project Zomboid.

.DESCRIPTION
    RCON UTF-8 Buffer Overflow Fix - Fixes BufferOverflowException in RCONServer.

    The original handleResponse() allocates the response ByteBuffer using
    String.length() (char count) but writes bytes with String.getBytes().
    For non-ASCII player names (e.g. "Ricardão"), UTF-8 encodes multi-byte
    characters, so byte count > char count, overflowing the buffer.

    This patch:
    1. Computes payload bytes via getBytes(StandardCharsets.UTF_8) first.
    2. Allocates ByteBuffer using payloadBytes.length, not s.length().
    3. Explicitly decodes incoming packet body as UTF-8 for protocol consistency.
    4. Guards against malformed/negative packet sizes in runInner().
    Packet structure and protocol framing are unchanged.

    This script:
    1. Locates or downloads a JDK 25+ compiler (javac)
    2. Backs up the original RCONServer.class from projectzomboid.jar
    3. Compiles the patched source against projectzomboid.jar
    4. Deploys the resulting .class files to the PZ game directory
       (classpath override: loose .class files in game root take precedence over JAR)

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes the deployed .class override (restoring original JAR behavior).

.NOTES
    PZ uses Azul Zulu JDK 25.0.1. The bundled JRE has no javac, so we need a full JDK.
    The script will auto-download Azul Zulu JDK 25 if no suitable compiler is found.

    Game version targeted: 42.17.0
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName     = "RCON UTF-8 Buffer Overflow Fix - RCONServer"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$DeployDir     = Join-Path $PZDir "zombie\network"
$DeployClass   = Join-Path $DeployDir "RCONServer.class"
$BackupDir     = Join-Path $ToolsDir "backups"
$BackupClass   = Join-Path $BackupDir "RCONServer.class.original"
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_rconserver"
$OutputDir     = Join-Path $WorkDir "classes"
$RequiredMajor = 25

# Inner classes produced by compilation
$InnerClassPattern = 'RCONServer$*.class'

# Azul Zulu JDK 25 download (Windows x64 zip)
$ZuluApiUrl = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$RequiredMajor&os=windows&arch=x64&archive_type=zip&java_package_type=jdk&latest=true"

# --- Functions ---
function Get-JavacVersion {
    param([string]$JavacPath)
    try {
        $output = & $JavacPath -version 2>&1 | Out-String
        if ($output -match "javac\s+(\d+)") {
            return [int]$Matches[1]
        }
    } catch {}
    return 0
}

function Find-Javac {
    Write-Host "[*] Searching for javac >= $RequiredMajor..." -ForegroundColor Cyan

    # 1. Check local JDK folder (from previous download)
    $localJavac = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $localJavac) {
        $ver = Get-JavacVersion $localJavac
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found local JDK: javac $ver at $localJavac" -ForegroundColor Green
            return $localJavac
        }
    }

    # 2. Check PATH
    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $ver = Get-JavacVersion $pathJavac.Source
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found in PATH: javac $ver at $($pathJavac.Source)" -ForegroundColor Green
            return $pathJavac.Source
        } else {
            Write-Host "    Found javac $ver in PATH (need >= $RequiredMajor, skipping)" -ForegroundColor Yellow
        }
    }

    # 3. Check common install locations
    $searchPaths = @(
        "C:\Program Files\Zulu\zulu-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Eclipse Adoptium\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Java\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Microsoft\jdk-$RequiredMajor*\bin\javac.exe"
    )
    foreach ($pattern in $searchPaths) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $ver = Get-JavacVersion $found.FullName
            if ($ver -ge $RequiredMajor) {
                Write-Host "    Found installed: javac $ver at $($found.FullName)" -ForegroundColor Green
                return $found.FullName
            }
        }
    }

    return $null
}

function Install-Jdk {
    Write-Host "[*] Downloading Azul Zulu JDK $RequiredMajor..." -ForegroundColor Cyan

    try {
        $response = Invoke-RestMethod -Uri $ZuluApiUrl -TimeoutSec 30
        $pkg = $response | Select-Object -First 1
        $downloadUrl = $pkg.download_url
    } catch {
        Write-Host "ERROR: Failed to query Azul API: $_" -ForegroundColor Red
        Write-Host "       Download JDK $RequiredMajor manually from https://www.azul.com/downloads/" -ForegroundColor Yellow
        exit 1
    }

    if (-not $downloadUrl) {
        Write-Host "ERROR: No JDK $RequiredMajor package found from Azul API." -ForegroundColor Red
        exit 1
    }

    Write-Host "    URL: $downloadUrl" -ForegroundColor Gray
    $zipPath = Join-Path $ToolsDir "jdk-download.zip"

    Write-Host "    Downloading..." -ForegroundColor Gray
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing
    Write-Host "    Download complete: $([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB" -ForegroundColor Gray

    Write-Host "    Extracting..." -ForegroundColor Gray
    $extractDir = Join-Path $ToolsDir "jdk-extract"
    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    $innerDir = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
    if (-not $innerDir) {
        Write-Host "ERROR: Extracted archive is empty." -ForegroundColor Red
        exit 1
    }

    if (Test-Path $LocalJdkDir) { Remove-Item $LocalJdkDir -Recurse -Force }
    Move-Item $innerDir.FullName $LocalJdkDir

    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue

    $javacPath = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $javacPath) {
        $ver = Get-JavacVersion $javacPath
        Write-Host "    Installed: javac $ver at $javacPath" -ForegroundColor Green
        return $javacPath
    } else {
        Write-Host "ERROR: javac not found in downloaded JDK." -ForegroundColor Red
        exit 1
    }
}

function Backup-OriginalClass {
    if (Test-Path $BackupClass) {
        Write-Host "[*] Backup already exists: $BackupClass" -ForegroundColor Gray
        return
    }

    Write-Host "[*] Extracting original RCONServer.class from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) {
        New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null
    }

    $tempDir = Join-Path $ToolsDir "tmp-extract"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null

    Push-Location $tempDir
    try {
        $jarExe = Join-Path $PZDir "jre64\bin\jar.exe"
        if (-not (Test-Path $jarExe)) {
            $jarExe = "jar"
        }
        & $jarExe xf $GameJar "zombie/network/RCONServer.class"
        $extracted = Join-Path $tempDir "zombie\network\RCONServer.class"
        if (Test-Path $extracted) {
            Copy-Item $extracted $BackupClass
            Write-Host "    Backed up original: $BackupClass" -ForegroundColor Green
        } else {
            Write-Host "    WARNING: Could not extract original class (may not exist in JAR)." -ForegroundColor Yellow
        }
    } finally {
        Pop-Location
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- Main ---
Write-Host ""
Write-Host "=== $PatchName - Build & Deploy ===" -ForegroundColor White
Write-Host ""

# Handle revert
if ($Revert) {
    $reverted = $false
    if (Test-Path $DeployClass) {
        Remove-Item $DeployClass -Force
        Write-Host "    Removed: $DeployClass" -ForegroundColor Green
        $reverted = $true
    }

    # Also remove inner class files
    $innerClasses = Get-ChildItem -Path $DeployDir -Filter "RCONServer`$*.class" -ErrorAction SilentlyContinue
    foreach ($ic in $innerClasses) {
        Remove-Item $ic.FullName -Force
        Write-Host "    Removed: $($ic.Name)" -ForegroundColor Green
        $reverted = $true
    }

    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted ===" -ForegroundColor White
        Write-Host ""
        Write-Host "Original RCONServer from JAR will be used on next server start." -ForegroundColor Gray
    } else {
        Write-Host "    No patch files found to remove." -ForegroundColor Yellow
    }
    Write-Host ""
    exit 0
}

# Validate inputs
if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: Game JAR not found: $GameJar" -ForegroundColor Red
    Write-Host "       Set -PZDir to your ProjectZomboid installation" -ForegroundColor Yellow
    exit 1
}

# Step 1: Backup original class from JAR
Backup-OriginalClass

# Step 2: Find or install JDK
$javac = Find-Javac
if (-not $javac) {
    $javac = Install-Jdk
}

# Step 3: Write patched source to temp directory
Write-Host ""
Write-Host "[*] Writing patched RCONServer.java..." -ForegroundColor Cyan
$TempSrcDir = Join-Path $WorkDir "src\zombie\network"
if (-not (Test-Path $TempSrcDir)) {
    New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null
}
$TempSourceFile = Join-Path $TempSrcDir "RCONServer.java"

$JavaSource = @'
// Patched RCONServer.java
// Fix: BufferOverflowException in handleResponse() when player names contain
// non-ASCII characters (e.g. "Ricardão" with ã, ç, é, etc.).
//
// Root cause: original code allocated ByteBuffer using String.length() (char count)
// but wrote bytes using String.getBytes() (UTF-8 byte count). For multi-byte UTF-8
// characters, byte count > char count, causing a BufferOverflowException.
//
// Fix: compute payloadBytes = s.getBytes(StandardCharsets.UTF_8) first, then
// allocate ByteBuffer(12 + payloadBytes.length + 2).
//
// Also: explicit UTF-8 decode for incoming packet body, and a packet-size sanity
// guard to avoid allocating arbitrarily large byte arrays from malformed packets.
//
// Original: zombie.network.RCONServer (Build 42.17.0)
package zombie.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

public class RCONServer {
    public static final int SERVERDATA_RESPONSE_VALUE = 0;
    public static final int SERVERDATA_AUTH_RESPONSE = 2;
    public static final int SERVERDATA_EXECCOMMAND = 2;
    public static final int SERVERDATA_AUTH = 3;
    private static RCONServer instance;
    private ServerSocket welcomeSocket;
    private RCONServer.ServerThread thread;
    private final String password;
    private final ConcurrentLinkedQueue<RCONServer.ExecCommand> toMain = new ConcurrentLinkedQueue<>();

    private RCONServer(int port, String password, boolean isLocal) {
        this.password = password;

        try {
            this.welcomeSocket = new ServerSocket();
            if (isLocal) {
                this.welcomeSocket.bind(new InetSocketAddress("127.0.0.1", port));
            } else if (GameServer.ipCommandline != null) {
                this.welcomeSocket.bind(new InetSocketAddress(GameServer.ipCommandline, port));
            } else {
                this.welcomeSocket.bind(new InetSocketAddress(port));
            }

            DebugLog.log("RCON: listening on port " + port);
        } catch (IOException var7) {
            DebugLog.log("RCON: error creating socket on port " + port);
            DebugType.General.printException(var7, LogSeverity.Error);

            try {
                this.welcomeSocket.close();
                this.welcomeSocket = null;
            } catch (IOException var6) {
                DebugType.General.printException(var6, LogSeverity.Error);
            }

            return;
        }

        this.thread = new RCONServer.ServerThread();
        this.thread.start();
    }

    private void updateMain() {
        for (RCONServer.ExecCommand command = this.toMain.poll(); command != null; command = this.toMain.poll()) {
            command.update();
        }
    }

    public void quit() {
        if (this.welcomeSocket != null) {
            try {
                this.welcomeSocket.close();
            } catch (IOException var2) {
            }

            this.welcomeSocket = null;
            this.thread.quit();
            this.thread = null;
        }
    }

    public static void init(int port, String password, boolean isLocal) {
        instance = new RCONServer(port, password, isLocal);
    }

    public static void update() {
        if (instance != null) {
            instance.updateMain();
        }
    }

    public static void shutdown() {
        if (instance != null) {
            instance.quit();
        }
    }

    private static class ClientThread extends Thread {
        public Socket socket;
        public boolean auth;
        public boolean quit;
        private final String password;
        private InputStream in;
        private OutputStream out;
        private final ConcurrentLinkedQueue<RCONServer.ExecCommand> toThread = new ConcurrentLinkedQueue<>();
        private int pendingCommands;

        public ClientThread(Socket socket, String password) {
            this.socket = socket;
            this.password = password;

            try {
                this.in = socket.getInputStream();
                this.out = socket.getOutputStream();
            } catch (IOException var4) {
                DebugType.General.printException(var4, LogSeverity.Error);
            }

            this.setName("RCONClient" + socket.getLocalPort());
        }

        @Override
        public void run() {
            if (this.in != null) {
                if (this.out != null) {
                    while (!this.quit) {
                        try {
                            this.runInner();
                        } catch (SocketException var3) {
                            this.quit = true;
                        } catch (Exception var4) {
                            DebugType.General.printException(var4, LogSeverity.Error);
                        }
                    }

                    try {
                        this.socket.close();
                    } catch (IOException var2) {
                        DebugType.General.printException(var2, LogSeverity.Error);
                    }

                    DebugType.DetailedInfo.trace("RCON: connection closed " + this.socket.toString());
                }
            }
        }

        private void runInner() throws IOException {
            byte[] bytes = new byte[4];
            int receivedBytes = this.in.read(bytes, 0, 4);
            if (receivedBytes < 0) {
                this.quit = true;
            } else {
                ByteBuffer bb = ByteBuffer.wrap(bytes);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                int packetSize = bb.getInt();

                // --- PATCHED: guard against malformed or oversized packet sizes ---
                if (packetSize <= 0 || packetSize > 65536) {
                    this.quit = true;
                    return;
                }
                // --- END PATCHED ---

                int remainingBytes = packetSize;
                byte[] packetData = new byte[packetSize];

                do {
                    receivedBytes = this.in.read(packetData, packetSize - remainingBytes, remainingBytes);
                    if (receivedBytes < 0) {
                        this.quit = true;
                        return;
                    }

                    remainingBytes -= receivedBytes;
                } while (remainingBytes > 0);

                bb = ByteBuffer.wrap(packetData);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                int id = bb.getInt();
                int type = bb.getInt();
                // --- PATCHED: explicit UTF-8 decode for protocol consistency ---
                String body = new String(bb.array(), bb.position(), bb.limit() - bb.position() - 2, StandardCharsets.UTF_8);
                // --- END PATCHED ---
                this.handlePacket(id, type, body);
            }
        }

        private void handlePacket(int id, int type, String body) throws IOException {
            if (!"players".equals(body)) {
                DebugType.DetailedInfo.trace("RCON: ID=" + id + " Type=" + type + " Body='" + body + "' " + this.socket.toString());
            }

            switch (type) {
                case 0:
                    if (this.checkAuth()) {
                        ByteBuffer bb = ByteBuffer.allocate(14);
                        bb.order(ByteOrder.LITTLE_ENDIAN);
                        bb.putInt(bb.capacity() - 4);
                        bb.putInt(id);
                        bb.putInt(0);
                        bb.putShort((short)0);
                        this.out.write(bb.array());
                        this.out.write(bb.array());
                    }
                    break;
                case 1:
                default:
                    DebugLog.log("RCON: unknown packet Type=" + type);
                    break;
                case 2:
                    if (!this.checkAuth()) {
                        break;
                    }

                    RCONServer.ExecCommand command = new RCONServer.ExecCommand(id, body, this);
                    this.pendingCommands++;
                    RCONServer.instance.toMain.add(command);

                    while (this.pendingCommands > 0) {
                        command = this.toThread.poll();
                        if (command != null) {
                            this.pendingCommands--;
                            this.handleResponse(command);
                        } else {
                            try {
                                Thread.sleep(50L);
                            } catch (InterruptedException var7) {
                                if (this.quit) {
                                    return;
                                }
                            }
                        }
                    }
                    break;
                case 3:
                    this.auth = body.equals(this.password);
                    if (!this.auth) {
                        DebugLog.log("RCON: password doesn't match");
                        this.quit = true;
                    }

                    ByteBuffer bb = ByteBuffer.allocate(14);
                    bb.order(ByteOrder.LITTLE_ENDIAN);
                    bb.putInt(bb.capacity() - 4);
                    bb.putInt(id);
                    bb.putInt(0);
                    bb.putShort((short)0);
                    this.out.write(bb.array());
                    bb.clear();
                    bb.putInt(bb.capacity() - 4);
                    bb.putInt(this.auth ? id : -1);
                    bb.putInt(2);
                    bb.putShort((short)0);
                    this.out.write(bb.array());
            }
        }

        public void handleResponse(RCONServer.ExecCommand command) {
            String s = command.response;
            if (s == null) {
                s = "";
            }

            // --- PATCHED: fix BufferOverflowException for non-ASCII player names ---
            // UTF-8 encodes some characters as multiple bytes (e.g. ã = 2 bytes).
            // The original code used s.length() (char count) for ByteBuffer allocation
            // but wrote bytes via s.getBytes(), causing overflow for multi-byte chars.
            // Fix: encode to bytes first, then allocate using the actual byte count.
            byte[] payloadBytes = s.getBytes(StandardCharsets.UTF_8);
            ByteBuffer bb = ByteBuffer.allocate(12 + payloadBytes.length + 2);
            // --- END PATCHED ---
            bb.order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(bb.capacity() - 4);
            bb.putInt(command.id);
            bb.putInt(0);
            bb.put(payloadBytes);
            bb.putShort((short)0);

            try {
                this.out.write(bb.array());
            } catch (IOException e) {
                DebugType.General.printException(e, LogSeverity.Error);
            }
        }

        private boolean checkAuth() throws IOException {
            if (this.auth) {
                return true;
            } else {
                this.quit = true;
                ByteBuffer bb = ByteBuffer.allocate(14);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                bb.putInt(bb.capacity() - 4);
                bb.putInt(-1);
                bb.putInt(2);
                bb.putShort((short)0);
                this.out.write(bb.array());
                return false;
            }
        }

        public void quit() {
            if (this.socket != null) {
                try {
                    this.socket.close();
                } catch (IOException var3) {
                }
            }

            this.quit = true;
            this.interrupt();

            while (this.isAlive()) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException var2) {
                    DebugType.General.printException(var2, LogSeverity.Error);
                }
            }
        }
    }

    private static class ExecCommand {
        public int id;
        public String command;
        public String response;
        public RCONServer.ClientThread thread;

        public ExecCommand(int id, String command, RCONServer.ClientThread thread) {
            this.id = id;
            this.command = command;
            this.thread = thread;
        }

        public void update() {
            this.response = GameServer.rcon(this.command);
            if (this.thread.isAlive()) {
                this.thread.toThread.add(this);
            }
        }
    }

    private class ServerThread extends Thread {
        private final ArrayList<RCONServer.ClientThread> connections;
        public boolean quit;

        public ServerThread() {
            this.connections = new ArrayList<>();
            this.setName("RCONServer");
        }

        @Override
        public void run() {
            while (!this.quit) {
                this.runInner();
            }
        }

        private void runInner() {
            try {
                Socket socket = RCONServer.this.welcomeSocket.accept();

                for (int i = 0; i < this.connections.size(); i++) {
                    RCONServer.ClientThread connection = this.connections.get(i);
                    if (!connection.isAlive()) {
                        this.connections.remove(i--);
                    }
                }

                if (this.connections.size() >= 5) {
                    socket.close();
                    return;
                }

                DebugType.DetailedInfo.trace("RCON: new connection " + socket.toString());
                RCONServer.ClientThread connection = new RCONServer.ClientThread(socket, RCONServer.this.password);
                this.connections.add(connection);
                connection.start();
            } catch (IOException var4) {
                if (!this.quit) {
                    DebugType.General.printException(var4, LogSeverity.Error);
                }
            }
        }

        public void quit() {
            this.quit = true;

            while (this.isAlive()) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException var3) {
                    DebugType.General.printException(var3, LogSeverity.Error);
                }
            }

            for (int i = 0; i < this.connections.size(); i++) {
                RCONServer.ClientThread connection = this.connections.get(i);
                connection.quit();
            }
        }
    }
}
'@

[System.IO.File]::WriteAllText($TempSourceFile, $JavaSource, [System.Text.UTF8Encoding]::new($false))
Write-Host "    Written to: $TempSourceFile" -ForegroundColor Green

# Step 4: Compile
Write-Host ""
Write-Host "[*] Compiling patched RCONServer.java..." -ForegroundColor Cyan
if (-not (Test-Path $OutputDir)) {
    New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null
}

$javacArgs = @(
    "-cp", $GameJar,
    "-d", $OutputDir,
    "-encoding", "UTF-8",
    "-source", "25",
    "-target", "25",
    $TempSourceFile
)

Write-Host "    javac $($javacArgs -join ' ')" -ForegroundColor Gray
& $javac @javacArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Compilation failed (exit code $LASTEXITCODE)." -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

$compiledClass = Join-Path $OutputDir "zombie\network\RCONServer.class"
if (-not (Test-Path $compiledClass)) {
    Write-Host "ERROR: Expected output not found: $compiledClass" -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 5: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy to $DeployDir\" -ForegroundColor Yellow
    $compiledDir = Join-Path $OutputDir "zombie\network"
    Get-ChildItem -Path $compiledDir -Filter "RCONServer*.class" | ForEach-Object {
        Write-Host "    Would copy: $($_.Name)" -ForegroundColor Yellow
    }
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan

    if (-not (Test-Path $DeployDir)) {
        New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null
    }

    # Backup existing override if present
    if (Test-Path $DeployClass) {
        $ts = Get-Date -Format "yyyyMMdd_HHmmss"
        $prev = Join-Path $BackupDir "RCONServer.class.prev_$ts"
        Copy-Item $DeployClass $prev
        Write-Host "    Previous override backed up to: $prev" -ForegroundColor Gray
    }

    # Deploy patched class and all inner classes
    $compiledDir = Join-Path $OutputDir "zombie\network"
    Get-ChildItem -Path $compiledDir -Filter "RCONServer*.class" | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $DeployDir $_.Name) -Force
        Write-Host "    Deployed: $($_.Name)" -ForegroundColor Green
    }
}

# Done
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host ""
Write-Host "Patch deployed: $PatchName" -ForegroundColor Green
Write-Host ""
Write-Host "How it works:" -ForegroundColor Gray
Write-Host "  PZ classpath is ['.', 'projectzomboid.jar'], so the loose .class" -ForegroundColor Gray
Write-Host "  at '$DeployDir' takes precedence over the one inside the JAR." -ForegroundColor Gray
Write-Host ""
Write-Host "  handleResponse() now encodes the response string to UTF-8 bytes" -ForegroundColor Gray
Write-Host "  before allocating the ByteBuffer. This prevents overflow when" -ForegroundColor Gray
Write-Host "  player names contain multi-byte UTF-8 characters (ã, ç, é, etc.)." -ForegroundColor Gray
Write-Host ""
Write-Host "  To revert entirely:" -ForegroundColor Yellow
Write-Host "    .\patchRCONServer.ps1 -Revert" -ForegroundColor Yellow
Write-Host "    (or delete all RCONServer*.class from: $DeployDir)" -ForegroundColor Yellow
Write-Host ""

# Cleanup
Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
