from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('build_release', ROOT / 'scripts/build_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def test_source_allowlist_excludes_runtime_and_credentials(self):
        files = list(release.source_files())
        self.assertTrue(files)
        forbidden = {'data', '.venv', '.bootstrap', '.python', '.cache', '__pycache__', '.git'}
        for file in files:
            relative = file.relative_to(ROOT)
            self.assertFalse(forbidden.intersection(relative.parts), str(relative))
            self.assertNotIn('connection-code', file.name)
            self.assertNotEqual(file.suffix, '.log')
            self.assertTrue(file.is_file(), str(relative))
        server_files = {f.name for f in files if f.parent == ROOT / 'server'}
        self.assertEqual(server_files, set(release.SERVER_FILES))

    def test_archive_is_reproducible_and_checksum_matches(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            with redirect_stdout(io.StringIO()):
                first = release.build(folder / 'one')
                second = release.build(folder / 'two')
            self.assertEqual(first.read_bytes(), second.read_bytes())
            expected = hashlib.sha256(first.read_bytes()).hexdigest()
            self.assertEqual((first.parent / 'SHA256SUMS.txt').read_text().split()[0], expected)
            with zipfile.ZipFile(first) as archive:
                self.assertIsNone(archive.testzip())
                names = archive.namelist()
                self.assertTrue(any(name.endswith('/PRIVACY.md') for name in names))
                self.assertTrue(any(name.endswith('/extension/manifest.json') for name in names))
                self.assertFalse(any('/server/data/' in name for name in names))
                self.assertFalse(any('/.git/' in name for name in names))


if __name__ == '__main__':
    unittest.main()
