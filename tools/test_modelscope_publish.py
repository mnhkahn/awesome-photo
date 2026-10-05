import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

import package_models
from publish_models_modelscope import configuration, download_url, verify_download


class ModelScopePublishTest(unittest.TestCase):
    def test_configuration_and_url(self):
        self.assertEqual(configuration({'MODELSCOPE_API_KEY': 'secret'}), 'mnhkahn/awesome-photo-models')
        self.assertEqual(configuration({'MODELSCOPE_API_KEY': 'secret', 'MODELSCOPE_MODEL_REPO': 'owner/models'}), 'owner/models')
        url = urlparse(download_url('owner/models', 'nima-123.onnx'))
        self.assertEqual(url.scheme, 'https')
        self.assertEqual(url.hostname, 'modelscope.cn')
        self.assertEqual(url.path, '/api/v1/models/owner/models/repo')
        self.assertEqual(parse_qs(url.query), {'Revision': ['master'], 'FilePath': ['nima-123.onnx']})

    def test_invalid_configuration(self):
        with self.assertRaises(ValueError):
            configuration({})
        with self.assertRaises(ValueError) as error:
            configuration({'MODELSCOPE_API_KEY': 'do-not-print', 'MODELSCOPE_MODEL_REPO': 'https://invalid'})
        self.assertNotIn('do-not-print', str(error.exception))

    def test_packaging_preserves_original_catalog_and_content_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory) / 'assets'
            assets.mkdir()
            (assets / 'weights.onnx').write_bytes(b'weights')
            (assets / 'models.json').write_text('original')
            descriptor = [('test', 'Test', 'aesthetic', 'weights.onnx', 224, 'zero-one', 1)]
            with patch.object(package_models, 'ASSETS', assets), patch.object(package_models, 'MODELS', descriptor):
                a = json.loads(package_models.package('https://github.com/test/releases/download/v1', Path(directory) / 'a'))['models'][0]
                b = json.loads(package_models.package('https://modelscope.cn/models/owner/models', Path(directory) / 'b'))['models'][0]
            self.assertEqual(a['sha256'], b['sha256'])
            self.assertEqual(a['bytes'], b['bytes'])
            self.assertEqual((assets / 'models.json').read_text(), 'original')

    def test_public_download_rejects_corruption_and_html(self):
        class Response(io.BytesIO):
            status = 200
            url = 'https://modelscope.cn/model.onnx'

        model = dict(id='test', url=Response.url, bytes=7, sha256=hashlib.sha256(b'weights').hexdigest())
        for payload in [b'weights', b'corrupt', b'<html>error</html>', b'partial']:
            with patch('urllib.request.urlopen', return_value=Response(payload)):
                if payload == b'weights':
                    verify_download(model)
                else:
                    with self.assertRaises(ValueError):
                        verify_download(model)


if __name__ == '__main__':
    unittest.main()
