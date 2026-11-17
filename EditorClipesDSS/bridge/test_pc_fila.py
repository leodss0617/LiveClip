import os
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

os.environ.setdefault('EDITOR_TERMUX_HOME', str(Path.home()))
try:
    import pc_client
except ModuleNotFoundError as exc:
    if exc.name != 'paramiko':
        raise
    raise unittest.SkipTest('Paramiko indisponível fora do ambiente Termux de processamento') from exc


class PcQueueTest(unittest.TestCase):
    def run_on_pair(self, folder, run):
        state = Mock()
        with patch.object(pc_client, 'configured_pcs', return_value=('baodm22','go3kspe')), \
             patch.object(pc_client, 'execute_on_pc', side_effect=run) as worker:
            pc_client.execute(folder, {}, state, Mock())
            return [call.args[-1] for call in worker.call_args_list]

    def test_uses_second_worker_if_first_is_busy(self):
        with TemporaryDirectory() as tmp, pc_client.PC_SLOTS['baodm22']:
            folder=Path(tmp)
            self.assertEqual(self.run_on_pair(folder, lambda *args: None), ['go3kspe'])
            self.assertEqual((folder/'pc-alvo').read_text().strip(), 'go3kspe')

    def test_uses_second_worker_if_first_is_unreachable(self):
        def fail_first(*args):
            if args[-1]=='baodm22':
                raise pc_client.PCUnavailable('offline')
        with TemporaryDirectory() as tmp:
            self.assertEqual(self.run_on_pair(Path(tmp), fail_first), ['baodm22','go3kspe'])

    def test_resumes_only_on_worker_that_already_received_job(self):
        with TemporaryDirectory() as tmp:
            folder=Path(tmp)
            (folder/'pc-iniciado').touch()
            (folder/'pc-alvo').write_text('go3kspe')
            self.assertEqual(self.run_on_pair(folder, lambda *args: None), ['go3kspe'])

    def test_never_starts_on_second_worker_when_remote_execution_uncertain(self):
        with TemporaryDirectory() as tmp:
            folder=Path(tmp)
            def fail_after_send(*args):
                (folder/'pc-iniciado').touch()
                raise pc_client.PCUnavailable('conexão perdida')
            with patch.object(pc_client, 'configured_pcs', return_value=('baodm22','go3kspe')), \
                 patch.object(pc_client, 'execute_on_pc', side_effect=fail_after_send) as worker:
                with self.assertRaises(pc_client.PCUnavailable):
                    pc_client.execute(folder,{},Mock(),Mock())
                self.assertEqual(worker.call_count,1)
