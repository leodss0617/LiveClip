import unittest
from live_chat_monitor import signal_from_updates

class MonitorTests(unittest.TestCase):
    def test_first_snapshot_is_history(self):
        old,n,exc=signal_from_updates(None,{'ao_vivo':True,'mensagens':[{'id':'1','mensagem':'kkkk'}]})
        self.assertEqual((n,exc),(0,0))
        self.assertEqual(old,{'1'})

    def test_only_new_messages(self):
        previous={'1'}
        ids,n,exc=signal_from_updates(previous,{'ao_vivo':True,'mensagens':[
            {'id':'1','mensagem':'aaa'},{'id':'2','mensagem':'kkkk'}]})
        self.assertEqual((ids,n,exc),({'1','2'},1,1))

    def test_stale_chat_does_not_trigger(self):
        _,n,_=signal_from_updates({'1'}, {'ao_vivo':True,'atrasado':True,
            'mensagens':[{'id':'2','mensagem':'kkkk'}]})
        self.assertEqual(n,0)

if __name__=='__main__': unittest.main()
