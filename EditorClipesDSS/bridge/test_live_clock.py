import unittest
from live_clock import capture_offsets, manifest_times, playback_mark


class LiveClockTest(unittest.TestCase):
    def test_manifest_times_use_program_clock(self):
        sample = """#EXTM3U
#EXTINF:5,
f1.ts
#EXT-X-PROGRAM-DATE-TIME:2026-06-07T10:00:05.000Z
#EXTINF:5.0,
f2.ts
#EXTINF:5.0,
f3.ts
"""
        times=manifest_times(sample,'https://media.example/live/a.m3u8')
        self.assertEqual(times['/live/f2.ts']-times['/live/f1.ts'],5)
        self.assertEqual(times['/live/f3.ts']-times['/live/f2.ts'],5)

    def test_mark_uses_played_time_with_stream_clock(self):
        mark=playback_mark({'video_ms':1_780_000_010_000,'atraso_ms':30_000},1_780_000_045)
        self.assertEqual(capture_offsets([mark],90,1_780_000_041,35,1_780_000_000),[100])
        self.assertEqual(capture_offsets([mark],90,1_780_000_041,35,None),[99])
        self.assertFalse(capture_offsets([mark],90,1_780_000_041,35,1_780_000_026))

    def test_invalid_played_time_is_rejected(self):
        for payload in ({'video_ms':True},{'atraso_ms':-1},{'atraso_ms':1000000000},
                        {'video_ms':1_780_100_000_000}):
            with self.assertRaises(ValueError):
                playback_mark(payload,1_780_000_000)

    def test_capture_clock_uses_timeline_instead_of_file_write_time(self):
        from live_clock import CaptureClock
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from threading import Event
        from unittest.mock import patch
        with TemporaryDirectory() as temp:
            folder=Path(temp)
            (folder/'captura.log').write_text("Opening 'https://edge.live/series/f2.ts' for reading\n")
            (folder/'captura.csv').write_text('000000000.ts,0.500,35.750\n000000001.ts,35.750,71.000\n')
            source={'url':'https://edge.live/series/live.m3u8','headers':{}}
            sample='#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2026-06-07T10:00:05Z\n#EXTINF:5,\nf2.ts\n'
            clock=CaptureClock(folder,source,Event())
            with patch('live_clock.fetch_manifest',return_value=(sample,source['url'])):
                clock.find_anchor()
            self.assertEqual(clock.start(1)-clock.start(0),35.25)

    def test_phone_player_requires_a_kick_channel_and_uses_reduced_resolution(self):
        import json
        import threading
        from http.server import ThreadingHTTPServer
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from unittest.mock import patch
        from urllib.error import HTTPError
        from urllib.request import Request,urlopen
        import bridge

        with TemporaryDirectory() as temp:
            root=Path(temp)
            token=root/'token'
            token.write_text('test')
            live=root/('a'*32)
            live.mkdir()
            (live/'pedido.json').write_text('{"modo":"ao_vivo","url":"https://kick.com/test"}')
            (live/'estado.json').write_text('{"status":"running"}')
            response={'url':'https://edge.test/live.m3u8','headers':{'User-Agent':'test','Cookie':'private'},
                      'canal':'https://kick.com/test'}
            with patch.multiple(bridge,ROOT=root,TOKEN_FILE=token), \
                 patch('bridge.live_playback',return_value=response) as extractor:
                with ThreadingHTTPServer(('127.0.0.1',0),bridge.Handler) as server:
                    thread=threading.Thread(target=server.serve_forever,daemon=True)
                    thread.start()
                    try:
                        def post(path,payload):
                            req=Request(f'http://127.0.0.1:{server.server_port}'+path,
                                        data=json.dumps(payload).encode(),
                                        headers={'Authorization':'Bearer test','Content-Type':'application/json'})
                            with urlopen(req,timeout=4) as f:
                                return json.load(f)
                        with self.assertRaises(HTTPError):
                            post('/live/player',{'url':'https://example.com/ch'})
                        result=post('/live/player',{'url':'https://kick.com/test'})
                        self.assertEqual(result['headers'],{'User-Agent':'test'})
                        extractor.assert_called_once_with('https://kick.com/test',mobile=True)
                        with self.assertRaises(HTTPError):
                            post(f'/jobs/{live.name}/mark',{'canal':'https://kick.com/other','atraso_ms':35000})
                        post(f'/jobs/{live.name}/mark',{'canal':'https://kick.com/test','atraso_ms':35000})
                        saved=json.loads((live/'marcacoes.json').read_text())[0]
                        self.assertEqual(saved['registrado']-saved['epoch'],35)
                    finally:
                        server.shutdown()
                        thread.join()
