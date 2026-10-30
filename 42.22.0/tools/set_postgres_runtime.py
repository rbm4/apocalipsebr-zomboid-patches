"""Configure Linux native-launcher JSON for the bundled runtime dependencies."""
import argparse
import json
from pathlib import Path
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--pz-dir', required=True)
parser.add_argument('--dry-run', action='store_true')
parser.add_argument('--revert', action='store_true')
args = parser.parse_args()
root = Path(args.pz_dir).resolve()
version = Path(__file__).resolve().parents[1]
names = ['postgresql-42.7.14.jar', 'HikariCP-7.1.0.jar']
# Linux's stock start-server.sh launches ProjectZomboid64 with these JSON descriptors.
# Update whichever descriptors are shipped; do not create an unused descriptor.
launchers = [p for p in [root / 'ProjectZomboid64.json', root / 'ProjectZomboid32.json'] if p.is_file()]
if not launchers:
    raise SystemExit('No native launcher JSON found; custom java -cp launchers need their own classpath integration.')
entries = ['apocbr-lib/' + name for name in names]
updates = []
for launcher in launchers:
    original = launcher.read_bytes()
    config = json.loads(original.decode('utf-8-sig'))
    cp = config.get('classpath')
    if not isinstance(cp, list) or not all(isinstance(p, str) for p in cp):
        raise SystemExit(f'Invalid launcher classpath: {launcher}')
    config['classpath'] = [p for p in cp if p not in entries]
    if not args.revert:
        config['classpath'].extend(entries)
    updates.append((launcher, original, config))
if not args.revert:
    for name in names:
        if not (version / 'lib' / name).is_file():
            raise SystemExit(f'Missing bundled dependency: {name}')
if args.dry_run:
    print('[DRY RUN] Would configure native launcher classpath and apocbr-lib runtime JARs')
else:
    for launcher, original, config in updates:
        if config == json.loads(original.decode('utf-8-sig')):
            continue
        backup = launcher.with_name(launcher.name + '.apocbr-runtime.bak')
        if not backup.exists():
            backup.write_bytes(original)
        launcher.write_text(json.dumps(config, indent=4) + '\n', encoding='utf-8')
    runtime = root / 'apocbr-lib'
    if args.revert:
        for name in names:
            (runtime / name).unlink(missing_ok=True)
    else:
        runtime.mkdir(exist_ok=True)
        for name in names:
            shutil.copy2(version / 'lib' / name, runtime / name)
    print('Native launcher runtime dependencies configured')
