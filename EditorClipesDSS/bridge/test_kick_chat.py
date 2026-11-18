import json
import unittest
from unittest.mock import patch

import kick_chat


class PublicKickChatTests(unittest.TestCase):
    def setUp(self):
        kick_chat._METADATA.clear()
        kick_chat._MESSAGES.clear()

    def test_recent_public_messages_parsed_without_credentials(self):
        received = []
        responses = [
            {'id': 33, 'slug': 'streamer', 'livestream': {'id': 10}},
            {'data': {'messages': [
                {'id': 'new', 'type': 'message', 'content': 'Nova mensagem!',
                 'sender': {'username': 'bia'}, 'created_at': '2026-11-18T06:00:03Z'},
                {'id': 'old', 'type': 'message', 'content': 'Mensagem antiga',
                 'sender': {'username': 'ana'}, 'created_at': '2026-11-18T06:00:01Z'},
                {'id': 'system', 'type': 'system', 'content': 'não mostrar',
                 'sender': {'username': 'sistema'}}
            ]}},
        ]
        def fake(url):
            received.append(url)
            return responses.pop(0)
        with patch.object(kick_chat,'_get',side_effect=fake):
            result = kick_chat.recent_messages('streamer')
            cache = kick_chat.recent_messages('streamer')
        self.assertEqual(result['mensagens'][0]['autor'],'ana')
        self.assertEqual(result['mensagens'][-1]['autor'],'bia')
        self.assertEqual(len(result['mensagens']),2)
        self.assertTrue(result['ao_vivo'])
        self.assertEqual(len(received),2)
        self.assertEqual(cache,result)
        self.assertTrue(all(url.startswith('https://kick.com/api/v2/') for url in received))

    def test_channel_validation(self):
        for slug in ('../../etc', 'http://other.site', 'A B', ''):
            with self.assertRaises(ValueError):
                kick_chat.recent_messages(slug)

    def test_cached_result_can_be_returned_temporarily_on_rate_limit(self):
        kick_chat._MESSAGES['streamer'] = (
            kick_chat.time.monotonic()-7, {'canal':'streamer','mensagens':[],'ao_vivo':False}
        )
        with patch.object(kick_chat,'_get',side_effect=ValueError('temporário')):
            result = kick_chat.recent_messages('streamer')
        self.assertTrue(result['atrasado'])

if __name__ == '__main__':
    unittest.main()
