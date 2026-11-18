import unittest
from live_chat_monitor import signal_from_updates


def message(ident, content):
    return {'id': ident, 'autor': 'alguem', 'mensagem': content}


class ChatSignalTests(unittest.TestCase):
    def test_initial_snapshot_is_baseline_not_spike(self):
        ids, count, excitation = signal_from_updates(None, {
            'ao_vivo': True, 'mensagens': [message('a', 'oi')]})
        self.assertEqual((ids, count, excitation), ({'a'}, 0, 0.0))

    def test_new_messages_count_once_and_emojis_raise_signal(self):
        earlier = {'a'}
        snapshot = {'ao_vivo': True, 'mensagens': [
            message('a', 'antiga'), message('b', '🔥🔥!'),
            message('c', 'que lance'), message('d', 'KKKK!')]}
        seen, count, excitation = signal_from_updates(earlier, snapshot)
        self.assertEqual(count, 3)
        self.assertAlmostEqual(excitation, 2/3, delta=0.01)
        _, duplicate_count, _ = signal_from_updates(seen, snapshot)
        self.assertEqual(duplicate_count, 0)

    def test_offline_or_stale_chat_never_adds_score(self):
        _, count, _ = signal_from_updates({'a'}, {
            'ao_vivo': False, 'mensagens': [message('b', '🔥')]})
        self.assertEqual(count, 0)
        _, count, _ = signal_from_updates({'a'}, {
            'ao_vivo': True, 'atrasado': True,
            'mensagens': [message('b', '🔥')]})
        self.assertEqual(count, 0)


if __name__ == '__main__':
    unittest.main()
