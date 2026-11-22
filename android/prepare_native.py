"""Download pinned public native sources. No models or private build configuration."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent
NATIVE = Path(os.environ.get('LIVESUBTITLES_NATIVE_DIR', ROOT / 'native'))

def git(folder, *args, check=True):
    return subprocess.run(['git', '-C', str(folder), *args], check=check, text=True, capture_output=True)

for name, spec in json.loads((ROOT / 'native-sources.json').read_text()).items():
    folder = NATIVE / name
    if not (folder / '.git').exists():
        folder.mkdir(parents=True, exist_ok=True)
        git(folder, 'init')
        git(folder, 'remote', 'add', 'origin', spec['url'])
        git(folder, 'fetch', '--depth', '1', 'origin', spec['commit'])
        git(folder, 'checkout', '--detach', 'FETCH_HEAD')
    if git(folder, 'rev-parse', 'HEAD').stdout.strip() != spec['commit']:
        raise RuntimeError(f'{name}: unexpected source revision, refusing to overwrite it')
    if name == 'ctranslate2':
        git(folder, 'submodule', 'update', '--init', '--depth', '1', '--recursive', '--',
            'third_party/cpu_features', 'third_party/ruy', 'third_party/spdlog')
        patch = ROOT / 'native-patches/ctranslate2-android.patch'
        if git(folder, 'apply', '--reverse', '--check', str(patch), check=False).returncode != 0:
            git(folder, 'apply', '--check', str(patch))
            git(folder, 'apply', str(patch))
    print(f'{name} {spec["version"]}: pinned source ready')
