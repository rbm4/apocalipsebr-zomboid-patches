"""Configure native launcher JSON and the unsigned loose-patch runtime."""
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile

from unsigned_game_jar import RUNTIME_NAME, digest, find_game_jar, prepare


def atomic_write(path, data):
    fd, name = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
        shutil.copymode(path, name)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pz-dir', required=True)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--revert', action='store_true')
    args = parser.parse_args()
    root = Path(args.pz_dir).resolve()
    version = Path(__file__).resolve().parents[1]
    names = ['postgresql-42.7.14.jar', 'HikariCP-7.1.0.jar']
    game = find_game_jar(root)
    loose = game.parent.relative_to(root).as_posix()
    loose = '.' if loose == '.' else loose + '/.'
    # Update whichever descriptors ship, including Windows native launcher JSON.
    launchers = [p for p in [root / 'ProjectZomboid64.json', root / 'ProjectZomboid32.json'] if p.is_file()]
    if not launchers:
        raise ValueError('No native launcher JSON found; custom java -cp launchers need their own classpath integration.')
    entries = ['apocbr-lib/' + name for name in names]
    updates = []
    for launcher in launchers:
        original = launcher.read_bytes()
        config = json.loads(original.decode('utf-8-sig'))
        cp = config.get('classpath')
        if not isinstance(cp, list) or not all(isinstance(p, str) for p in cp):
            raise ValueError(f'Invalid launcher classpath: {launcher}')
        updated = []
        found = False
        for entry in cp:
            normalized = entry.replace('\\', '/')
            path = root / normalized
            if path.name.lower() in ('projectzomboid.jar', RUNTIME_NAME):
                # Never silently switch a descriptor to a different game install.
                if path.parent.resolve() != game.parent.resolve():
                    raise ValueError(f'Launcher references another game JAR directory: {launcher}')
                if not found:
                    updated.append(str(Path(normalized).with_name(
                        game.name if args.revert else RUNTIME_NAME)).replace('\\', '/'))
                found = True
            elif normalized not in entries:
                updated.append(entry)
        if not found:
            raise ValueError(f'No original or managed game JAR in launcher classpath: {launcher}')
        if not args.revert:
            # The directory containing our loose classes must precede the game JAR.
            updated = [p for p in updated if (root / p.replace('\\', '/')).resolve() != game.parent.resolve()]
            updated.insert(0, loose)
            updated.extend(entries)
        if updated != cp:
            config['classpath'] = updated
            updates.append((launcher, original, config))
    if not args.revert:
        for name in names:
            if not (version / 'lib' / name).is_file():
                raise ValueError(f'Missing bundled dependency: {name}')
        prepare(game, dry_run=args.dry_run)
    if args.dry_run:
        print('[DRY RUN] Would configure native launcher classpath and apocbr-lib runtime JARs')
        return
    dependencies = root / 'apocbr-lib'
    if not args.revert:
        dependencies.mkdir(exist_ok=True)
        for name in names:
            source, target = version / 'lib' / name, dependencies / name
            if not target.is_file() or digest(source) != digest(target):
                shutil.copy2(source, target)
    # The complete runtime is installed before launchers can reference it.
    for launcher, original, config in updates:
        backup = launcher.with_name(launcher.name + '.apocbr-runtime.bak')
        if not backup.exists():
            backup.write_bytes(original)
        atomic_write(launcher, (json.dumps(config, indent=4) + '\n').encode('utf-8'))
    if args.revert:
        for name in names:
            (dependencies / name).unlink(missing_ok=True)
    print('Native launcher runtime configured; original game JAR untouched')


if __name__ == '__main__':
    main()
