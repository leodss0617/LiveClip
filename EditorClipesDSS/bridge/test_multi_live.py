import json
import sys
import threading
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

import bridge


class MultiLiveTest(unittest.TestCase):
    def spec(self, name):
        return {'motor': 'pc', 'modo': 'ao_vivo', 'url': 'https://kick.com/' + name,
                'quantidade': 2, 'duracao': 60}

    def test_two_live_jobs_can_reach_the_pc_together(self):
        barrier = threading.Barrier(2)
        reached = []
        def execute(folder, spec, state, check_stop):
            barrier.wait(2)
            reached.append(spec['url'])
        with TemporaryDirectory() as root:
            folders = [Path(root) / name for name in ('one', 'two')]
            for folder in folders:
                folder.mkdir()
            with patch.dict(sys.modules, {'pc_client': SimpleNamespace(execute=execute, PCUnavailable=OSError)}):
                futures = [bridge.submit_job(folder, self.spec(folder.name)) for folder in folders]
                for future in futures:
                    future.result(5)
        self.assertCountEqual(reached, [self.spec(name)['url'] for name in ('one', 'two')])

    def test_phone_fallback_waits_until_the_running_capture_releases_it(self):
        phone = threading.BoundedSemaphore(1)
        entered = threading.Event()
        phone.acquire()
        with TemporaryDirectory() as root:
            folder = Path(root)
            def unavailable(*args):
                raise OSError('offline')
            with patch.object(bridge, 'PHONE_SLOT', phone), patch.dict(sys.modules, {
                'pc_client': SimpleNamespace(execute=unavailable, PCUnavailable=OSError),
            }), patch.object(bridge, 'process_live', side_effect=lambda *args: entered.set()):
                future = bridge.submit_job(folder, self.spec('one'))
                try:
                    self.assertFalse(entered.wait(0.2))
                finally:
                    phone.release()
                future.result(4)
        self.assertTrue(entered.is_set())

    def test_two_phone_fallback_captures_can_start_together(self):
        import time
        barrier = threading.Barrier(2)
        entered = []
        def unavailable(*args):
            raise OSError('PC offline')
        def simulate(*args):
            barrier.wait(timeout=3)
            entered.append(args[1]['url'])
        with TemporaryDirectory() as root:
            folders = [Path(root) / name for name in ('one', 'two')]
            for folder in folders:
                folder.mkdir()
            with patch.object(bridge, 'PHONE_SLOT', threading.BoundedSemaphore(2)), patch.dict(sys.modules, {
                'pc_client': SimpleNamespace(execute=unavailable, PCUnavailable=OSError),
            }), patch.object(bridge, 'process_live', side_effect=simulate):
                futures = [bridge.submit_job(folder, self.spec(folder.name)) for folder in folders]
                for future in futures:
                    future.result(timeout=5)
        self.assertCountEqual(entered, [self.spec(name)['url'] for name in ('one', 'two')])

    def test_list_keeps_a_running_job_over_an_older_completed_job(self):
        with TemporaryDirectory() as root:
            root = Path(root)
            for ident, status in (('a' * 32, 'done'), ('b' * 32, 'running')):
                folder = root / ident
                folder.mkdir()
                (folder / 'pedido.json').write_text(json.dumps(self.spec('one')))
                (folder / 'estado.json').write_text(json.dumps({'status': status}))
            result = bridge.live_jobs(root, root)
        self.assertEqual([job['id'] for job in result], ['b' * 32])
