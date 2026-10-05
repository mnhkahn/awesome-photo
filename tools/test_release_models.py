import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from prepare_release_models import fetch, validate_calibrations


class ReleaseModelsTest(unittest.TestCase):
    def test_reexported_model_cannot_silently_lose_calibration(self):
        catalog = {'models': [{'id': 'nima-mobile', 'role': 'aesthetic', 'sha256': 'original'}]}
        report = {'profiles': [{'modelId': 'nima-mobile', 'modelSha256': 'original', 'candidateAccepted': True}]}
        validate_calibrations(catalog, report)
        catalog['models'][0]['sha256'] = 'ci-reexport'
        with self.assertRaises(ValueError):
            validate_calibrations(catalog, report)

    def test_download_verify_and_offline_reuse(self):
        class Response(io.BytesIO):
            status = 200
            url = 'https://example.com/model.onnx'
        model = {'id': 'test', 'url': Response.url, 'bytes': 7, 'sha256': hashlib.sha256(b'weights').hexdigest()}
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'model.onnx'
            with patch('urllib.request.urlopen', return_value=Response(b'weights')):
                fetch(model, target)
            with patch('urllib.request.urlopen', side_effect=AssertionError('Unexpected network')):
                fetch(model, target)
            self.assertEqual(target.read_bytes(), b'weights')
            target.write_bytes(b'old')
            with patch('urllib.request.urlopen', return_value=Response(b'corrupt')):
                with self.assertRaises(ValueError):
                    fetch(model, target)
            self.assertEqual(target.read_bytes(), b'old')
            self.assertFalse(target.with_suffix('.onnx.part').exists())
