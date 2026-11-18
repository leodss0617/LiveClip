import unittest
from chat_activity import activity_bonus

class ActivityTests(unittest.TestCase):
    def test_chat_is_only_complement(self):
        self.assertEqual(activity_bonus([],0,10,[(1,0,20)],{1:[0,0,0,0,120]}),(0.0,0))

    def test_delay_aligns_viewer_reaction_to_video(self):
        t=[(1,0,30)];m={1:[0,0,0,0,130]}
        e=[{'registrado':125,'mensagens':10,'animacao':0.8,
            'atraso':5,'usuarios':5,'pico':True,'pontuacao':0.8}]
        bonus,num=activity_bonus(e,17,24,t,m)
        self.assertEqual(num,10)
        self.assertGreater(bonus,0.5)

    def test_no_spam_duplicate_timestamps(self):
        t=[(1,0,20)];m={1:[0,0,0,0,120]}
        e=[{'registrado':110,'mensagens':2,'animacao':0.1}]*3
        _,num=activity_bonus(e,0,20,t,m)
        self.assertEqual(num,2)

if __name__=='__main__': unittest.main()
