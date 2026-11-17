import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import ao_vivo
import bridge


class LiveQualityTest(unittest.TestCase):
    channel = 'https://kick.com/test'

    def resolve(self, info):
        resolver = MagicMock()
        resolver.return_value.__enter__.return_value.extract_info.return_value = info
        extractor = SimpleNamespace(YoutubeDL=resolver, utils=SimpleNamespace(DownloadError=OSError))
        with patch.dict(sys.modules, yt_dlp=extractor):
            return ao_vivo.live_playback(self.channel, mobile=True)

    def test_player_uses_adaptive_master_when_present(self):
        out = self.resolve({'is_live': True, 'url': 'https://edge.test/480.m3u8',
                            'manifest_url': 'https://edge.test/master.m3u8'})
        self.assertEqual(out['url'], 'https://edge.test/master.m3u8')

    def test_player_falls_back_to_selected_variant(self):
        out = self.resolve({'is_live': True, 'url': 'https://edge.test/source.m3u8',
                            'manifest_url': 'file:///tmp/local'})
        self.assertEqual(out['url'], 'https://edge.test/source.m3u8')

    def test_explicit_failure_is_not_hidden_by_previous_attempt(self):
        with TemporaryDirectory() as root:
            log = Path(root) / 'processo.log'
            log.write_text('ERROR: antigo\n')
            pos = log.stat().st_size
            with log.open('a') as output:
                output.write('ERROR: mensagem do processo\n')
            process_error = subprocess.CalledProcessError(1, ['python'])
            self.assertEqual(bridge.processing_error(log, pos, process_error), 'mensagem do processo')
            self.assertEqual(bridge.processing_error(log, pos, ValueError('falha específica')), 'falha específica')
            self.assertNotIn('antigo', bridge.processing_error(log, log.stat().st_size, process_error))
