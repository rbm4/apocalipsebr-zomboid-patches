"""Create a disposable unsigned runtime JAR; the publisher JAR is read-only."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

RUNTIME_NAME = 'projectzomboid-apocbr.jar'


def signature(name):
    # JAR signing metadata lives directly inside META-INF, not its subdirectories.
    return bool(re.fullmatch(r'META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)', name, re.I))


def digest(path):
    with path.open('rb') as stream:
        value = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
        return value.hexdigest()


def find_game_jar(root):
    for path in (root / 'projectzomboid.jar', root / 'java/projectzomboid.jar',
                 root / 'java/ProjectZomboid.jar'):
        if path.is_file():
            return path
    raise ValueError(f'Original projectzomboid.jar not found under {root}')


def prepare(game, dry_run=False):
    target = game.with_name(RUNTIME_NAME)
    receipt = target.with_suffix('.jar.source.json')
    if target.is_symlink() or receipt.is_symlink():
        raise ValueError('Managed runtime JAR/receipt must not be symbolic links')
    if target.exists() and os.path.samefile(game, target):
        raise ValueError('Managed runtime JAR must not alias the original JAR')
    # Validate even on a dry run, before any launcher changes.
    with zipfile.ZipFile(game) as source:
        if source.getinfo('META-INF/MANIFEST.MF').is_dir():
            raise ValueError('Original JAR manifest is invalid')
    if dry_run:
        print(f'[DRY RUN] Would prepare unsigned {target}; original remains untouched')
        return
    source_hash = digest(game)
    if target.is_file() and receipt.is_file():
        try:
            cached = json.loads(receipt.read_text(encoding='utf-8-sig'))
            if cached == {'source_sha256': source_hash, 'runtime_sha256': digest(target)}:
                print(f'Unsigned runtime JAR already current: {target}')
                return
        except (ValueError, OSError):
            pass
    # Stage on the same filesystem; publish only a complete, validated archive.
    fd, temp_name = tempfile.mkstemp(prefix=target.name + '.', suffix='.tmp', dir=target.parent)
    os.close(fd)
    temp = Path(temp_name)
    try:
        with zipfile.ZipFile(game) as source, zipfile.ZipFile(temp, 'w') as output:
            output.comment = source.comment
            for entry in source.infolist():
                if not signature(entry.filename):
                    with source.open(entry) as src, output.open(entry, 'w') as dst:
                        shutil.copyfileobj(src, dst)
        with zipfile.ZipFile(temp) as output:
            if output.testzip() is not None:
                raise ValueError('Unsigned runtime JAR failed CRC validation')
        if digest(game) != source_hash:
            raise ValueError('Original JAR changed during preparation; retry after the game update finishes')
        runtime_hash = digest(temp)
        shutil.copymode(game, temp)
        # Never open either the original or an existing destination for writing.
        os.replace(temp, target)
        data = {'source_sha256': source_hash, 'runtime_sha256': runtime_hash}
        # Replace receipt entries too, so a stale hard link cannot modify its source.
        fd, name = tempfile.mkstemp(prefix=receipt.name + '.', suffix='.tmp', dir=receipt.parent)
        receipt_temp = Path(name)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                stream.write(json.dumps(data, indent=2) + '\n')
            os.replace(receipt_temp, receipt)
        finally:
            receipt_temp.unlink(missing_ok=True)
    finally:
        temp.unlink(missing_ok=True)
    print(f'Prepared unsigned runtime JAR: {target}')
