# Experimental client Lua preparation

Enable with `-Dapocbr.experimental.asyncLuaPreparation.enabled=true` on the client JVM. The agent defaults to vanilla loading. The experiment targets the original Build 42.22.2 JAR.

`experimental.luapreparation.AsyncLuaPreparationModule` starts base Lua preparation at `ConnectToServerState.enter`. The successful `ZomboidFileSystem.loadModsAux` call for the server's mod list supplies dependency roots after Workshop checks. `loadMods(List)` supplies any additional roots from the effective load list, including client-only additions. The worker walks the `shared` and `client` folders and receives paths without references to game objects.

`Core.ResetLua(String, String)` opens a reader scope for the `ConnectedToServer` reset. Inside that scope, `IndieFileLoader.getStreamReader(String)` asks the worker's cache for bytes under the path resolved by vanilla and decodes them as UTF-8. Cache hits are checked against the file's size, modification time and file identity when provided by the filesystem. A cache miss, changed file, or unreadable file falls through to the original reader. Vanilla remains responsible for Lua compilation and execution, mod precedence, callbacks, multiplayer checksums and connection ordering. Reads never wait for the worker.

The worker has one daemon thread that retires when idle, a bounded queue, and a per-join limit of 32 MiB, 16,384 files and 1 MiB per file. Leaving the join cancels its pending work and clears its files. A new join gets a fresh cache. The client reports reset duration in milliseconds and counts prepared reads and reads that fell through to vanilla within the reader scope. These counts cover loader calls, so the same file can appear more than once.

The focused Maven tests verify the hook registrations and their coexistence with client mod activation on original class bytes. A forked original-JAR fixture exercises the connection and reader hooks and checks the fallback and scope cleanup. A graphical multiplayer join, Workshop updates and login/frame-time measurements remain to be validated on a real client.
