import shutil
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]


class PythonBootstrapTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('uv'), 'uv required for bootstrap integration')
    def test_creates_python312_without_touching_previous_environment(self):
        with TemporaryDirectory() as d:
            root = Path(d)
            old = root / '.venv'
            old.mkdir()
            (old / 'keep.txt').write_text('preserve')
            source = ROOT / 'preparar-python.sh'
            self.assertTrue(source.exists(), 'Bootstrap Python 3.12 missing')
            shutil.copy(source, root / source.name)
            result = subprocess.run(['bash', str(root / source.name)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            version = subprocess.check_output([str(root / '.venv312/bin/python'), '-c',
                'import sys; print(sys.version_info[:2])'], text=True)
            self.assertEqual(version.strip(), '(3, 12)')
            self.assertEqual((old / 'keep.txt').read_text(), 'preserve')

if __name__ == '__main__':
    unittest.main()
