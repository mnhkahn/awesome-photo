import copy
import unittest
from fit_aesthetic_calibration import fit


class CalibrationTest(unittest.TestCase):
    def fixture(self):
        return {'model': {'id': 'test', 'sha256': 'a' * 64}, 'samples': [
            {'sha256': str(i), 'scene_group': str(i), 'split': 'train' if i < 30 else 'validation',
             'raw_mean': 3 + i / 20, 'teacher_aesthetic': 15 * (3 + i / 20) - 10}
            for i in range(40)]}

    def test_holdout_labels_never_fit_coefficients(self):
        a = self.fixture()
        b = copy.deepcopy(a)
        for row in b['samples'][30:]:
            row['teacher_aesthetic'] = 0
        first, second = fit(a), fit(b)
        self.assertAlmostEqual(first['slope'], 15)
        self.assertEqual(first['slope'], second['slope'])
        self.assertEqual(first['intercept'], second['intercept'])
        self.assertFalse(second['candidateAccepted'])

    def test_duplicate_scene_or_photo_leakage_rejected(self):
        for key in ['sha256', 'scene_group']:
            data = self.fixture()
            data['samples'][-1][key] = data['samples'][0][key]
            with self.assertRaises(ValueError):
                fit(data)

    def test_three_liked_samples_cannot_be_exported_as_calibration(self):
        data = self.fixture()
        data['samples'] = data['samples'][:3]
        with self.assertRaises(ValueError):
            fit(data)


if __name__ == '__main__':
    unittest.main()
