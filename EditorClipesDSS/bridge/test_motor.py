import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import Mock, patch

from bridge import perform


class ComputerUnavailable(Exception):
    pass


class EngineTest(unittest.TestCase):
    def setUp(self):
        self.spec = {'motor': 'pc', 'modo': 'ao_vivo', 'url': 'https://kick.com/test',
                     'quantidade': 1, 'duracao': 60}

    def run_case(self, error=None, started=False):
        with TemporaryDirectory() as directory:
            folder = Path(directory)
            client = SimpleNamespace(PCUnavailable=ComputerUnavailable, execute=Mock(side_effect=error))
            if started:
                (folder/'pc-iniciado').touch()
            with patch.dict(sys.modules, {'pc_client': client}), patch('bridge.process_live') as capture:
                perform(folder,self.spec)
                state = json.loads((folder/'estado.json').read_text()) if (folder/'estado.json').exists() else {}
            return capture.called, state

    def test_network_failure_uses_phone(self):
        called, state = self.run_case(ComputerUnavailable('tempo esgotado'))
        self.assertTrue(called)
        self.assertEqual(state['motor_ativo'], 'termux')

    def test_successful_remote_never_also_runs_on_phone(self):
        self.assertFalse(self.run_case()[0])

    def test_remote_processing_failure_is_reported_without_restarting(self):
        called, state = self.run_case(RuntimeError('Falha na captura remota'))
        self.assertFalse(called)
        self.assertEqual(state['status'], 'error')

    def test_uncertain_previous_remote_execution_does_not_restart_on_phone(self):
        called, state = self.run_case(ComputerUnavailable('PC perdeu a conexão'), started=True)
        self.assertFalse(called)
        self.assertEqual(state['status'], 'error')

    def test_confirmed_takeover_preserves_original_job(self):
        error = ComputerUnavailable('Tempo de reconexão expirou')
        error.retomar = True
        called, state = self.run_case(error, started=True)
        self.assertTrue(called)
        self.assertEqual(state['motor_ativo'], 'termux')
