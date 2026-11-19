"""Build reproducible source packages without traversing local runtime data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = [
    '.gitignore', '.gitattributes', 'README.md', 'LICENSE', 'PRIVACY.md',
    'THIRD_PARTY_NOTICES.md', 'CHANGELOG.md', 'RELEASE_NOTES.md',
    'START.cmd', 'ПРОЧИТАЙ-СНАЧАЛА.txt',
]
SOURCE_DIRS = ['extension', 'tests', 'scripts', '.github']
SERVER_FILES = ['app.py', 'engine.py', 'streaming.py', 'requirements.txt', 'start.ps1']


def source_files():
    for name in ROOT_FILES:
        yield ROOT / name
    for name in SERVER_FILES:
        yield ROOT / 'server' / name
    for folder in SOURCE_DIRS:
        for file in sorted((ROOT / folder).rglob('*')):
            if file.is_file() and '__pycache__' not in file.parts and file.suffix != '.pyc':
                yield file


def build(output):
    manifest = json.loads((ROOT / 'extension/manifest.json').read_text(encoding='utf-8'))
    version = manifest.get('version_name', manifest['version'])
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.-]+)?', version):
        raise ValueError('Invalid release version')
    output.mkdir(parents=True, exist_ok=True)
    archive_path = output / f'LiveSubtitles-v{version}-Windows.zip'
    prefix = f'LiveSubtitles-v{version}'
    with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(source_files()):
            relative = file.relative_to(ROOT).as_posix()
            contents = file.read_bytes().replace(b'\r\n', b'\n')
            if file.suffix in {'.cmd', '.ps1'}:
                contents = contents.replace(b'\n', b'\r\n')
            info = zipfile.ZipInfo(f'{prefix}/{relative}', date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, contents)
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None:
            raise ValueError('Archive integrity check failed')
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    (output / 'SHA256SUMS.txt').write_text(f'{digest}  {archive_path.name}\n', encoding='ascii')
    print(f'{archive_path.name}: {digest}')
    return archive_path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'dist')
    build(parser.parse_args().output_dir)
