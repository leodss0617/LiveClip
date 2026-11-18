import unittest
from chat_intelligence import ChatHeatDetector, priority_boost


def snapshot(first, n, *, live=True, author_mode='diverse', body='kkkk que jogada!'):
    return {'ao_vivo':live, 'mensagens':[
        {'id':str(i), 'autor': ('p'+str(i % 6) if author_mode=='diverse' else 'bot'),
         'mensagem':body} for i in range(first, first+n)]}


class PulseTests(unittest.TestCase):
    def test_initial_read_is_not_a_live_spike(self):
        m=ChatHeatDetector(); p=m.analyze(snapshot(0,40),now=100)
        self.assertEqual(p.messages,0)
        self.assertFalse(p.peak)

    def test_seen_messages_not_counted_twice(self):
        m=ChatHeatDetector(); m.analyze(snapshot(0,20), now=100)
        p=m.analyze(snapshot(0,20), now=110)
        self.assertEqual(p.messages,0)

    def test_chat_surge_with_distinct_authors(self):
        m=ChatHeatDetector(chat_delay_seconds=5)
        m.analyze(snapshot(0,30), now=100)
        for i in range(1,6):
            m.analyze(snapshot(30+i,1),now=100+i*10)
        p=m.analyze(snapshot(50,15),now=170)
        self.assertTrue(p.peak)
        self.assertLessEqual(p.score,1)
        self.assertEqual(p.suggested_video_time,165)
        self.assertGreater(priority_boost(p),0.5)

    def test_single_bot_is_not_sufficient(self):
        m=ChatHeatDetector(); m.analyze(snapshot(0,30),now=100)
        for i in range(1,6):
            m.analyze(snapshot(30+i,1),now=100+i*10)
        p=m.analyze(snapshot(50,20,author_mode='same'),now=170)
        self.assertFalse(p.peak)

    def test_spam_commands_not_promoted(self):
        m=ChatHeatDetector(); m.analyze(snapshot(0,10),now=100)
        p=m.analyze(snapshot(10,10,body='!follow'),now=110)
        self.assertEqual(p.messages,0)

    def test_stale_chat_does_not_count(self):
        m=ChatHeatDetector();m.analyze(snapshot(0,5),now=100)
        s=snapshot(5,7);s['atrasado']=True
        self.assertEqual(m.analyze(s,now=110).messages,0)

    def test_no_priority_without_transcript(self):
        m=ChatHeatDetector(); m.analyze(snapshot(0,5),now=100)
        p=m.analyze(snapshot(10,6),now=110)
        self.assertEqual(priority_boost(p,False),0)

    def test_time_reversal_resets_baseline(self):
        m=ChatHeatDetector();m.analyze(snapshot(0,5),now=100)
        m.analyze(snapshot(5,3),now=110)
        p=m.analyze(snapshot(8,4),now=70)
        self.assertFalse(p.peak)


if __name__=='__main__':unittest.main()
