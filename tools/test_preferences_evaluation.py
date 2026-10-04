import unittest
from evaluate_wallpaper_preferences import summarize

class PreferenceMetricsTest(unittest.TestCase):
    def row(self, group, score):
        return {'group': group, 'score':score, 'path': f'{group}-{score}'}

    def test_ties_receive_half_credit(self):
        result=summarize([self.row('liked',80),self.row('disliked',70),self.row('disliked',80)])
        self.assertEqual(.75,result['pairwise']['liked > disliked']['concordance'])

    def test_reversed_ranking_is_not_hidden_by_mean(self):
        result=summarize([self.row('liked',40),self.row('disliked',90)])
        entry=result['pairwise']['liked > disliked']
        self.assertEqual(0,entry['concordance'])
        self.assertEqual(40,entry['worstInversions'][0]['preferredScore'])

    def test_positive_samples_alone_do_not_claim_accuracy(self):
        result=summarize([self.row('liked',60),self.row('liked',80)])
        self.assertEqual({},result['pairwise'])
        self.assertEqual(70,result['groups']['liked']['median'])

if __name__=='__main__': unittest.main()
