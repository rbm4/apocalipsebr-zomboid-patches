# Unsigned runtime JAR for loose server patches

Implemented on 2026-11-04 for the Windows and Linux 42.22.1 deployment scripts.
The publisher's `projectzomboid.jar` is **read-only input**: deployment never
edits, renames, replaces or deletes it. Compilers continue using that original.

## Why startup changed

42.22.1 ships a signed game JAR. Java rejects signed vanilla classes sharing a
package with our unsigned loose classes, before `GameServer.main()` executes:

```text
SecurityException: class "zombie.network.IConnection"'s signer information
does not match signer information of other classes in the same package
```

Deployment now creates `projectzomboid-apocbr.jar` alongside the original, with
only top-level JAR signature metadata removed from `META-INF/`: `.SF`, `.RSA`,
`.DSA`, `.EC` and `SIG-*` files. Manifest, class bytecode, resources and nested
resources bearing those extensions are preserved. Replacement classes continue
to be deployed loose, outside both archives.

This addresses Java's class-loading restriction. The upstream `IntegrityReport`
and its startup hook remain unchanged and report the unsigned/modified runtime.
See the [42.22.1 integrity investigation](../reports/2026-11-04-port-42-22-0-to-42-22-1.md#integrity-behavior-that-affects-our-installation).

## Deployment and repeated application

Use the existing entry points:

```powershell
.\42.22.1\patchApocalipseBr.ps1 -PZDir 'C:\path\to\ProjectZomboid'
```

```bash
bash ./42.22.1/patchApocalipseBr.sh --pz-dir /opt/pzserver
```

Both scripts invoke their existing runtime helper, now extended to:

1. Find the original in the installation root or `java/`. When both contain an
   original, the root wins consistently for compilation, loose-class deployment
   and launcher editing. A missing original is an error; the derived JAR is
   never used as the source for another deployment.
2. Validate every discovered native launcher descriptor before modifying files.
3. Create the unsigned copy through a temporary file beside its destination,
   reading the original only. Publish the complete copy through replacement of
   the managed destination, without writing into an existing JAR in place.
4. Install PostgreSQL/HikariCP dependencies and redirect `ProjectZomboid64.json`
   and `ProjectZomboid32.json`, when present, to the derived JAR. The loose-class
   directory (`.` or `java/.`) is first. Remove duplicate original/derived game
   JAR references and managed dependencies; preserve unrelated classpath entries,
   JVM arguments, platform settings and extra configuration fields.
5. On Windows, also update `ProjectZomboidServer.bat` when present. Its existing
   managed block replaces the original game-JAR filename in `PZ_CLASSPATH` at
   launch time and adds dependencies. Vanilla assignments remain intact.

Windows supports JSON-only, batch-only and combined installations without adding
a Python requirement. Linux requires Python 3.8+ and a native launcher JSON.
Paths with spaces and both original-JAR layouts are supported. Descriptors that
reference a different game-JAR directory fail rather than silently changing the
game installation they target.

The adjacent `projectzomboid-apocbr.jar.source.json` records SHA-256 hashes for
both original and derived archives. Every deployment verifies those hashes:

- An unchanged original and valid derived copy reuse the existing runtime.
- A game update, missing copy, damaged copy or invalid receipt rebuilds from the
  current original. An original changed during preparation fails the deployment.
- Every deployment rereads the **current** launcher files, even when the derived
  copy is reused. Steam updates that reset JSON to `projectzomboid.jar` are
  repaired; future independent settings survive. No previous launcher backup is
  restored over current settings.
- Unchanged JSON, batch files, runtime archives and dependencies are not rewritten.
  The first `.apocbr-runtime.bak` launcher backup is retained across redeployments.

Deploy while the server is stopped, as with existing loose-class deployment.
Publication is atomic per managed JAR/launcher file; the entire class deployment
is not a transaction. The scripts do not start or restart the server.

## Dry run, build only and revert

`-DryRun` / `--dry-run` validates launchers and the original archive and performs
the existing compilation without changing installation files. Windows
`-BuildOnly` retains compilation artifacts without configuring any launcher or
runtime JAR.

`-Revert` / `--revert` removes the managed batch block/dependency entries, changes
native launcher references back to the original game JAR, then removes the loose
class families using the existing revert logic. Independent launcher edits are
preserved. The disposable unsigned JAR and hash receipt are retained as unused
artifacts; the signed original remains in place throughout. Repeating revert is
safe. Existing stale-class retirement limitations are outside this change.

Custom `java -cp` commands, including IDE tasks, must use the derived filename
instead of the original and keep the loose-class directory first. They are not
automatically discovered or edited by deployment. For a root-layout installation:

```text
.;projectzomboid-apocbr.jar;apocbr-lib/postgresql-42.7.14.jar;apocbr-lib/HikariCP-7.1.0.jar
```

Linux uses `:` separators. Normal native launchers are configured automatically.

## Verification and limits

`tools/test_unsigned_runtime_deployment.py` executes both real helpers in
temporary installations, including root/`java/` layouts and paths with spaces.
It builds genuinely signed disposable Java archives, reproduces the original
signer mismatch and verifies loose patched and unsigned vanilla classes load
together afterward. It checks all retained archive payloads, unchanged original
bytes/timestamps, dry run, repeat deployment, game-update refresh, damaged-copy
repair, duplicate entries, JSON reset and independent settings, both native
descriptors, validation before writes, missing-original failure and repeat revert.
Windows execution requires PowerShell and a JDK with `keytool`/`jarsigner`; Linux
execution of the complete fixture also needs a JDK, not only a JRE.

Verification on 2026-11-04: the full fixture passed on Windows; native Ubuntu/WSL
Python 3.10 helper checks passed for both layouts (signature metadata removal,
payload preservation, dry run, reapply, JSON reset, revert and original
preservation). Bash syntax validation passed. The Windows script's full dry run
compiled all 305 current sources against the installed signed original, producing
903 classes. The previous 902-class receipt predates unrelated working-tree
changes; it remains historical. No installed game JAR, launcher, IDE task, server
process or live database was changed by this verification.

Full dedicated-server startup, native launcher execution and gameplay remain
runtime validation steps. This deployment change does not suppress integrity
diagnostics or establish client/server runtime compatibility by itself.
