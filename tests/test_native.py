import json
import os
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


class NativeConfigTests(unittest.TestCase):
    def test_config_keeps_password_across_restarts(self):
        from liveclip.native import load_config

        with TemporaryDirectory() as d:
            path = Path(d) / "native.json"
            first = load_config(path)
            second = load_config(path)
            self.assertEqual(first["LIVECLIP_PASSWORD"], second["LIVECLIP_PASSWORD"])
            self.assertGreaterEqual(len(first["LIVECLIP_PASSWORD"]), 24)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_old_model_is_migrated_without_losing_password_or_limits(self):
        from liveclip.native import load_config

        with TemporaryDirectory() as d:
            path = Path(d) / "native.json"
            original = {
                "LIVECLIP_PASSWORD": "secret-password-123",
                "OLLAMA_MODEL": "qwen2.5:1.5b",
                "MAX_DATA_GB": "8",
                "WHISPER_MODEL": "tiny",
            }
            path.write_text(json.dumps(original))
            updated = load_config(path)
            self.assertEqual(updated["OLLAMA_MODEL"], "qwen3:4b-instruct-2507-q4_K_M")
            self.assertEqual(
                updated["LIVECLIP_PASSWORD"], original["LIVECLIP_PASSWORD"]
            )
            self.assertEqual(updated["MAX_DATA_GB"], "8")
            self.assertEqual(json.loads(path.read_text()), updated)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_lightweight_default_is_upgraded_and_transcription_is_preserved(self):
        from liveclip.native import load_config

        with TemporaryDirectory() as d:
            path = Path(d) / "native.json"
            config = {
                "LIVECLIP_PASSWORD": "secret-password-123",
                "OLLAMA_MODEL": "qwen3:0.6b",
                "WHISPER_MODEL": "tiny",
            }
            path.write_text(json.dumps(config))
            updated = load_config(path)
            self.assertEqual(updated["OLLAMA_MODEL"], "qwen3:4b-instruct-2507-q4_K_M")
            self.assertEqual(updated["WHISPER_MODEL"], "tiny")

    def test_custom_model_is_preserved(self):
        from liveclip.native import load_config

        with TemporaryDirectory() as d:
            path = Path(d) / "native.json"
            config = {
                "LIVECLIP_PASSWORD": "secret-password-123",
                "OLLAMA_MODEL": "custom:model",
            }
            path.write_text(json.dumps(config))
            self.assertEqual(load_config(path), config)

    def test_invalid_config_is_not_overwritten(self):
        from liveclip.native import load_config

        with TemporaryDirectory() as d:
            path = Path(d) / "native.json"
            path.write_text(json.dumps({"LIVECLIP_PASSWORD": "short"}))
            with self.assertRaises(ValueError):
                load_config(path)
            self.assertIn("short", path.read_text())

    def test_non_text_password_is_rejected_cleanly(self):
        from liveclip.native import load_config

        with TemporaryDirectory() as d:
            path = Path(d) / "native.json"
            path.write_text(json.dumps({"LIVECLIP_PASSWORD": 123}))
            with self.assertRaises(ValueError):
                load_config(path)

    def test_missing_dependencies_give_actionable_message(self):
        # Execute actual launcher with PATH restricted: it must stop before spawning jobs.
        process = subprocess.run(
            [sys.executable, "-m", "liveclip.native", "--check"],
            cwd=ROOT,
            env=dict(os.environ, PATH="/nonexistent"),
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(process.returncode, 0)
        self.assertIn("ffmpeg", process.stdout + process.stderr)
        self.assertNotIn("Traceback", process.stderr)


if __name__ == "__main__":
    unittest.main()
