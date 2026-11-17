"""Teste local do serviço: autenticação, envio, fila e download de MP4."""
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


class CloudServiceTest(unittest.TestCase):
    def test_http_workflow(self):
        import socket
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = os.environ.copy()
            env["EDITOR_CLOUD_DATA"] = str(root / "work")
            with socket.socket() as reserve:
                reserve.bind(("127.0.0.1", 0))
                port = reserve.getsockname()[1]
            proc = subprocess.Popen(
                [sys.executable, str(Path(__file__).with_name("service.py")), "--port", str(port)],
                env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE
            )
            base = f"http://127.0.0.1:{port}"
            try:
                for _ in range(80):
                    if (root / "work" / "token").is_file():
                        try:
                            with urlopen(Request(base + "/health"), timeout=1):
                                pass
                        except HTTPError as exc:
                            if exc.code == 401:
                                break
                        except OSError:
                            pass
                    time.sleep(.1)
                token = (root / "work" / "token").read_text().strip()
                headers = {"Authorization": "Bearer " + token}
                with self.assertRaises(HTTPError) as exc:
                    urlopen(base + "/health", timeout=5)
                self.assertEqual(exc.exception.code, 401)
                exc.exception.close()
                with urlopen(Request(base + "/health", headers=headers), timeout=5) as response:
                    self.assertEqual(json.load(response)["status"], "ok")
                video = root / "sample.mp4"
                subprocess.run([
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=128x224:rate=10",
                    "-t", "1", "-c:v", "libx264", str(video)
                ], check=True, timeout=45)
                request = Request(base + "/jobs", video.read_bytes(),
                                  headers={**headers, "Content-Type": "video/mp4"}, method="POST")
                with urlopen(request, timeout=10) as response:
                    job = json.load(response)
                    self.assertEqual(response.status, 202)
                for _ in range(100):
                    with urlopen(Request(base + "/jobs/" + job["id"], headers=headers), timeout=5) as response:
                        state = json.load(response)
                    if state["status"] in ("done", "error"):
                        break
                    time.sleep(.2)
                self.assertEqual(state["status"], "done", state)
                with urlopen(Request(base + "/jobs/" + job["id"] + "/output", headers=headers), timeout=15) as response:
                    self.assertEqual(response.headers.get_content_type(), "video/mp4")
                    self.assertGreater(len(response.read()), 1000)
            finally:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
