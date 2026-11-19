import unittest

from momentos import select_moments


def speech(start, text):
    return [
        {'s': start + i * 0.27, 'e': start + i * 0.27 + 0.21, 'w': word}
        for i, word in enumerate(text.split())
    ]


class MomentSelectionTest(unittest.TestCase):
    def test_title_describes_payoff_instead_of_repeating_hook(self):
        words = speech(5, 'Será que consigo ganhar sozinho contra os três?')
        words += speech(12, 'Consegui ganhar a rodada sozinho!')
        cuts = select_moments(30, 1, 25, words, [-40]*30)
        self.assertEqual(len(cuts), 1)
        self.assertIn('Consegui ganhar a rodada sozinho', cuts[0].title)
        self.assertNotIn('Será que', cuts[0].title)

    def test_title_uses_final_event_without_generic_catchphrase(self):
        words = speech(4, 'Será que dá pra ganhar sozinho contra os três?')
        words += speech(10, 'Peguei os três!')
        words += speech(14, 'Ganhei a rodada!')
        cuts = select_moments(29, 1, 24, words, [-40]*29)
        self.assertEqual(len(cuts), 1)
        self.assertIn('Ganhei a rodada', cuts[0].title)
        self.assertNotEqual(cuts[0].title, 'Ganhei!')

    def test_does_not_include_start_of_unrelated_next_topic(self):
        words = speech(4, 'Será que consigo vencer essa partida sozinho?')
        words += speech(10, 'Consegui vencer essa partida sozinho!')
        words += speech(13, 'Mudando de assunto vou explicar um filme diferente.')
        cuts = select_moments(29, 1, 24, words, [-40]*29)
        self.assertEqual(len(cuts), 1)
        self.assertLess(cuts[0].end, 13)

    def test_story_beats_louder_filler_and_opens_at_the_hook(self):
        words = speech(8, 'Mano olha mano olha mano olha mano olha!')
        words += speech(45, 'Será que dá pra ganhar sozinho contra os três?')
        words += speech(52, 'Vou esperar eles entrarem pela esquerda.')
        words += speech(60, 'Peguei os três! Ganhei a rodada!')
        energy = [-43] * 100
        energy[9] = -4
        cuts = select_moments(100, 1, 40, words, energy)
        self.assertEqual(len(cuts), 1)
        cut = cuts[0]
        self.assertAlmostEqual(cut.start, 44.8, delta=0.2)
        self.assertGreaterEqual(cut.end, words[-1]['e'])
        self.assertLessEqual(cut.end - cut.start, 40)
        self.assertIn('sozinho', cut.title.lower())

    def test_does_not_pad_count_with_identical_or_empty_moments(self):
        words = speech(10, 'Será que dá pra ganhar sozinho contra os três?')
        words += speech(14, 'Vou esperar eles entrarem pela esquerda.')
        words += speech(20, 'Peguei os três! Ganhei a rodada!')
        words += [{**w, 's': w['s']+80, 'e': w['e']+80} for w in words[:]]
        self.assertEqual(len(select_moments(150, 5, 60, words, [-40]*150)), 1)
        self.assertEqual(select_moments(150, 5, 60, speech(5,'mano mano mano!'), [-40]*150), [])

    def test_keeps_explanation_and_reaction_after_first_payoff(self):
        words = speech(5, 'Será que dá pra ganhar sozinho contra os três?')
        words += speech(11, 'Consegui ganhar a rodada sozinho!')
        words += speech(19, 'Só que o segredo foi esperar a equipe inteira sair.')
        words += speech(25, 'Por isso consegui virar a partida com um único tiro.')
        end = words[-1]['e']
        words += speech(30, 'Mudando de assunto vamos falar sobre o torneio de amanhã.')
        clipped = select_moments(45, 1, 40, words, [-38]*45)
        self.assertEqual(len(clipped), 1)
        self.assertGreaterEqual(clipped[0].end, end)
        self.assertLess(clipped[0].end, 30)

    def test_drops_a_story_whose_conclusion_cannot_fit(self):
        words = speech(5, 'Será que dá pra ganhar sozinho contra os três?')
        words += speech(12, 'Consegui ganhar a rodada sozinho!')
        words += speech(22, 'Só que o segredo foi esperar a equipe inteira sair.')
        words += speech(34, 'Por isso consegui virar a partida com um único tiro.')
        self.assertEqual(select_moments(50, 1, 25, words, [-38]*50), [])

    def test_question_and_unrelated_sentence_do_not_make_a_complete_scene(self):
        words = speech(5, 'Será que dá pra ganhar sozinho contra os três?')
        words += speech(15, 'O campeonato vai começar no próximo sábado.')
        self.assertEqual(select_moments(35, 1, 30, words, [-40]*35), [])

    def test_can_follow_the_same_story_past_eighty_five_seconds(self):
        words = speech(5, 'Será que dá pra virar essa partida sozinho?')
        for start in range(12, 92, 7):
            words += speech(start, 'Só que a partida ainda estava longe de terminar.')
        words += speech(94, 'Por isso eu esperei a chance e ganhei a partida!')
        cuts = select_moments(115, 1, 110, words, [-40]*115)
        self.assertEqual(len(cuts), 1)
        self.assertGreaterEqual(cuts[0].end, words[-1]['e'])

    def test_live_window_holds_ending_until_lookahead_is_available(self):
        words = speech(5, 'Será que dá pra ganhar sozinho contra os três?')
        words += speech(11, 'Consegui ganhar a rodada sozinho!')
        self.assertEqual(select_moments(20, 1, 40, words, [-38]*20, end_before=9), [])
        words += speech(22, 'Por isso o segredo foi esperar a equipe inteira sair.')
        cuts = select_moments(50, 1, 40, words, [-38]*50, end_after=9, end_before=34)
        self.assertEqual(len(cuts), 1)
        self.assertGreaterEqual(cuts[0].end, words[-1]['e'])

    def test_includes_the_scene_setup_before_the_hook(self):
        words = speech(12, 'A regra desse desafio é ficar sem colete até o final.')
        words += speech(19, 'Será que consigo ganhar sozinho contra os três?')
        words += speech(25, 'Consegui ganhar a rodada sozinho!')
        cuts = select_moments(50, 1, 45, words, [-40]*50)
        self.assertEqual(len(cuts), 1)
        self.assertLessEqual(cuts[0].start, 12)
        self.assertGreaterEqual(cuts[0].end, words[-1]['e'])

    def test_waits_for_a_short_reaction_after_the_result(self):
        words = speech(5, 'Será que dá pra pegar aquela equipe inteira sozinho?')
        words += speech(12, 'Ganhei a rodada sozinho!')
        words += speech(19, 'Caraca, eu achei que ia morrer com aquele último tiro!')
        end = words[-1]['e']
        words += speech(26, 'Mudando de assunto vamos falar de outro jogo.')
        cuts = select_moments(50, 1, 45, words, [-40]*50)
        self.assertEqual(len(cuts), 1)
        self.assertGreaterEqual(cuts[0].end, end)
        self.assertLess(cuts[0].end, 26)

    def test_live_does_not_publish_a_result_when_linked_context_is_pending(self):
        words = speech(5, 'Será que dá pra pegar aquela equipe inteira sozinho?')
        words += speech(12, 'Ganhei a rodada sozinho!')
        words += speech(29, 'O que aconteceu foi que eles nem viram a armadilha.')
        self.assertEqual(select_moments(45, 1, 40, words, [-40]*45, end_before=25), [])
        cuts = select_moments(60, 1, 40, words, [-40]*60, end_after=25, end_before=45)
        self.assertEqual(len(cuts), 1)
        self.assertGreaterEqual(cuts[0].end, words[-1]['e'])

class ReactionBoundaryTest(unittest.TestCase):
    def test_prefers_the_later_reaction_and_defers_live_publication(self):
        words = speech(3, 'Será que eu consigo ganhar a última rodada sozinho?')
        words += speech(12, 'Consegui ganhar a última rodada sozinho!')
        words += speech(17, 'Caraca, eu achei que ia perder tudo!')
        words += speech(24, 'Mudando de assunto, vamos conversar sobre futebol.')
        cuts = select_moments(32, 1, 27, words, [-40] * 32)
        self.assertEqual(len(cuts), 1)
        self.assertGreater(cuts[0].end, 18)
        self.assertLess(cuts[0].end, 24)
        self.assertIn((17, 'exclamacao'), cuts[0].effects)
        self.assertEqual(
            select_moments(32, 1, 27, words, [-40] * 32, end_before=16), []
        )

class ClipTitleTest(unittest.TestCase):
    def test_title_skips_transition_and_uses_event_from_the_clip(self):
        from momentos import scene_title, tokens

        transcript = [
            "Agora a gente não está mais, ó, calma, perfeito?",
            "O carro atravessou a pista na contramão.",
            "Tá bom, perfeito!",
        ]
        scene = [(i, i + 1, phrase, tokens(phrase)) for i, phrase in enumerate(transcript)]
        self.assertEqual(scene_title(scene), "O carro atravessou a pista na contramão.")
