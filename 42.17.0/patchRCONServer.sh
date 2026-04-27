#!/bin/bash
# filepath: patchRCONServer.sh
# Patches RCONServer.java to fix BufferOverflowException for non-ASCII player names
# via classpath override.
#
# RCON UTF-8 Buffer Overflow Fix:
#   The original handleResponse() allocates the response ByteBuffer using
#   String.length() (char count) but writes bytes via String.getBytes().
#   For non-ASCII player names (e.g. "Ricardão"), UTF-8 encodes some characters
#   as multiple bytes, so byte count > char count, causing a BufferOverflowException.
#
#   Fix: compute payloadBytes = s.getBytes(StandardCharsets.UTF_8) first, then
#   allocate ByteBuffer using payloadBytes.length, not s.length().
#   Also: explicit UTF-8 decode for incoming packet body, and a packet-size sanity
#   guard to reject malformed/oversized packets before allocating memory.
#
# Strategy: classpath override via "java/." before "java/projectzomboid.jar".
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.
#
# Game version targeted: 42.17.0

set -e

# --- Argument parsing ---
PZ_DIR="/opt/pzserver"
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pz-dir)    PZ_DIR="$2"; shift 2 ;;
        --dry-run)   DRY_RUN=true; shift ;;
        --revert|-r) REVERT=true; shift ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Usage: $0 [--pz-dir PATH] [--dry-run] [--revert]" >&2
            exit 1
            ;;
    esac
done

JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
CLASSPATH_DIR="$PZ_DIR/java"
WORK_DIR="/tmp/pzpatch_rconserver"
DEPLOY_DIR="$CLASSPATH_DIR/zombie/network"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="$SCRIPT_DIR/backups"
BACKUP_CLASS="$BACKUP_DIR/RCONServer.class.original"

# --- Find javac (prefer PATH set by main script, fall back to known location) ---
JAVAC=""
if command -v javac &>/dev/null; then
    JAVAC=$(command -v javac)
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-25-openjdk-amd64/bin/javac"
fi

echo "=== PZ Classpath Override Patch ==="
echo "=== RCONServer: RCON UTF-8 Buffer Overflow Fix ==="

# --- Handle --revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    if [ -f "$DEPLOY_DIR/RCONServer.class" ]; then
        rm -f "$DEPLOY_DIR/RCONServer.class"
        echo "    Removed RCONServer.class"
        reverted=true
    fi
    # Remove inner class files
    for f in "$DEPLOY_DIR"/RCONServer\$*.class; do
        if [ -f "$f" ]; then
            rm -f "$f"
            echo "    Removed $(basename "$f")"
            reverted=true
        fi
    done
    if [[ "$reverted" == "true" ]]; then
        echo ""
        echo "=== Patch reverted ==="
        echo "Original RCONServer from JAR will be used on next server start."
    else
        echo "    No patch files found to remove."
    fi
    exit 0
fi

# Verify tools exist
if [[ ! -x "$JAVAC" ]]; then
    echo "ERROR: javac not found or not executable: $JAVAC"
    echo "       Install JDK 25: apt install openjdk-25-jdk-headless"
    echo "       Or run the main patch.sh which handles JDK setup automatically."
    exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: JAR not found at $JAR_FILE"
    echo "       Set --pz-dir to your Project Zomboid server installation."
    exit 1
fi

# --- Backup original class ---
if [[ ! -f "$BACKUP_CLASS" ]]; then
    echo "[*] Extracting original RCONServer.class from JAR..."
    mkdir -p "$BACKUP_DIR"
    EXTRACT_TMP="$SCRIPT_DIR/tmp-extract-rcon"
    rm -rf "$EXTRACT_TMP"
    mkdir -p "$EXTRACT_TMP"
    pushd "$EXTRACT_TMP" > /dev/null
    jar xf "$JAR_FILE" "zombie/network/RCONServer.class" 2>/dev/null || true
    if [[ -f "$EXTRACT_TMP/zombie/network/RCONServer.class" ]]; then
        cp "$EXTRACT_TMP/zombie/network/RCONServer.class" "$BACKUP_CLASS"
        echo "    Backed up original: $BACKUP_CLASS"
    else
        echo "    WARNING: Could not extract original class (may not exist in JAR)."
    fi
    popd > /dev/null
    rm -rf "$EXTRACT_TMP"
else
    echo "[*] Backup already exists: $BACKUP_CLASS"
fi

# Clean work directory
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/src/zombie/network"
mkdir -p "$WORK_DIR/build"

echo "[1/5] Writing patched RCONServer.java..."
cat > "$WORK_DIR/src/zombie/network/RCONServer.java" << 'JAVAEOF'
// Patched RCONServer.java
// Fix: BufferOverflowException in handleResponse() when player names contain
// non-ASCII characters (e.g. "Ricardão" with ã, ç, é, etc.).
//
// Root cause: original code allocated ByteBuffer using String.length() (char count)
// but wrote bytes using String.getBytes(). For multi-byte UTF-8 characters,
// byte count > char count, causing a BufferOverflowException.
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
JAVAEOF

echo "[2/5] Compiling patched RCONServer.java..."
"$JAVAC" -cp "$JAR_FILE" \
    -d "$WORK_DIR/build" \
    -encoding UTF-8 \
    -source 25 \
    -target 25 \
    "$WORK_DIR/src/zombie/network/RCONServer.java"

if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed."
    rm -rf "$WORK_DIR"
    exit 1
fi

echo "    Compiled successfully."

echo "[3/5] Deploying classes to classpath override directory..."

if [[ "$DRY_RUN" == "true" ]]; then
    echo "    DRY RUN: would deploy to $DEPLOY_DIR/"
    for f in "$WORK_DIR/build/zombie/network"/RCONServer*.class; do
        [[ -f "$f" ]] && echo "    Would deploy: $(basename "$f")"
    done
else
    mkdir -p "$DEPLOY_DIR"

    # Deploy main class and all inner classes
    cp "$WORK_DIR/build/zombie/network/RCONServer.class" "$DEPLOY_DIR/"
    echo "    Deployed RCONServer.class"

    for f in "$WORK_DIR/build/zombie/network"/RCONServer\$*.class; do
        if [[ -f "$f" ]]; then
            cp "$f" "$DEPLOY_DIR/"
            echo "    Deployed $(basename "$f")"
        fi
    done
fi

echo "[4/5] Verifying deployment..."
if [[ "$DRY_RUN" != "true" ]]; then
    echo "  Checking classpath override files:"
    ls -la "$DEPLOY_DIR"/RCONServer*.class
else
    echo "  DRY RUN: skipping verification."
fi

echo ""
echo "[5/5] Cleanup..."
rm -rf "$WORK_DIR"

echo ""
echo "=== Classpath Override Patch deployed successfully ==="
echo ""
echo "Patch: RCON UTF-8 Buffer Overflow Fix - RCONServer"
echo ""
echo "How it works:"
echo "  The server config classpath is: [\"java/.\", \"java/projectzomboid.jar\"]"
echo "  Since 'java/.' is listed first, the JVM loads .class files from the"
echo "  filesystem before looking inside the JAR. The original JAR is untouched."
echo ""
echo "  handleResponse() now encodes the response string to UTF-8 bytes before"
echo "  allocating the ByteBuffer. This prevents overflow when player names contain"
echo "  multi-byte UTF-8 characters such as ã, ç, é, ñ, etc."
echo ""
echo "Deployed to:"
echo "  $DEPLOY_DIR/"
echo "    - RCONServer.class (+ inner classes)"
echo ""
echo "  To revert: $0 --revert"
echo "  (or delete all RCONServer*.class from: $DEPLOY_DIR)"
echo ""
echo "Restart the server to apply changes."
