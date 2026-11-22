"""Package Android source from an allowlist; never include local build or signing data."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = (
    '.gitignore', 'README.md', 'AUDIT.md', 'RELEASE_NOTES.md', 'build.gradle',
    'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat',
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'native-sources.json', 'native-patches/ctranslate2-android.patch', 'prepare_native.py',
    'app/build.gradle', 'app/src/main/AndroidManifest.xml',
    'app/src/main/assets/models.json', 'app/src/main/assets/third-party-licenses.txt',
    'app/src/androidTest/assets/speech.wav', 'fixture/build.gradle',
    'fixture/src/main/AndroidManifest.xml', 'fixture/src/main/res/raw/speech.wav',
    'scripts/build_release.py',
)
SOURCE_DIRS = {
    'app/src/main/java': {'.java'}, 'app/src/main/cpp': {'.cpp', '.txt'},
    'app/src/main/res': {'.xml'}, 'app/src/test/java': {'.java'},
    'app/src/androidTest/java': {'.java'}, 'fixture/src/main/java': {'.java'},
}


def source_files():
    for name in ROOT_FILES:
        yield ROOT / name
    for folder, suffixes in SOURCE_DIRS.items():
        for file in sorted((ROOT / folder).rglob('*')):
            if file.is_file() and file.suffix in suffixes:
                yield file


def build(output, apk):
    version = re.search(r"versionName '([0-9]+\.[0-9]+\.[0-9]+)-android-alpha'",
                        (ROOT / 'app/build.gradle').read_text(encoding='utf-8')).group(1)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f'LiveSubtitles-Android-v{version}-alpha-source.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as target:
        entries = [(p, p.relative_to(ROOT).as_posix()) for p in source_files()]
        entries.append((ROOT.parent / 'LICENSE', 'LICENSE'))
        for file, name in sorted(entries, key=lambda entry: entry[1]):
            info = zipfile.ZipInfo('LiveSubtitles-Android/' + name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name == 'gradlew' else 0o100644) << 16
            contents = file.read_bytes()
            if file.suffix not in {'.wav', '.jar'}:
                contents = contents.replace(b'\r\n', b'\n')
                if file.suffix == '.bat': contents = contents.replace(b'\n', b'\r\n')
            target.writestr(info, contents)
    with zipfile.ZipFile(archive) as source:
        if source.testzip() is not None: raise ValueError('Invalid source ZIP')
    outputs = [archive]
    if apk:
        destination = output / f'LiveSubtitles-Android-v{version}-alpha.apk'
        shutil.copyfile(apk, destination)
        outputs.append(destination)
    (output / 'SHA256SUMS.txt').write_text(''.join(
        hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in outputs), encoding='ascii')
    for path in outputs: print(path.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/distributions')
    parser.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--source-only', action='store_true')
    args = parser.parse_args()
    build(args.output_dir, None if args.source_only else args.apk)
