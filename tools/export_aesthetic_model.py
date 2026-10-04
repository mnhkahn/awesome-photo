"""Export pinned idealo NIMA MobileNet weights to a local-only Android ONNX asset."""
import argparse
import hashlib
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory

import h5py
import numpy as np
import onnxruntime as ort
import torch
from torch import nn

ROOT = Path(__file__).resolve().parents[1]
REVISION = 'dceaf7c2d218bc6e80b21d6e147e3b56a21b7f31'
URL = f'https://raw.githubusercontent.com/idealo/image-quality-assessment/{REVISION}/models/MobileNet/weights_mobilenet_aesthetic_0.07.hdf5'
WEIGHTS = ROOT / '.cache/nima/weights.h5'
ASSET = ROOT / 'app/src/main/assets/nima_mobilenet.onnx'
# Filled with the verified upstream artifact digest below.
SHA256 = 'e563ad91b3d47410e45f7238f07ab8f6abd1bd0c4b18a4b0af9c681a21a91cb2'

class Nima(nn.Module):
    def __init__(self, weights):
        super().__init__()
        layers = []
        with h5py.File(weights) as f:
            def array(name, key):
                found = []
                f[name].visititems(lambda n, v: found.append(np.array(v)) if isinstance(v, h5py.Dataset) and n.split('/')[-1] == key + ':0' else None)
                if len(found) != 1:
                    raise ValueError(f'Invalid weight {name}/{key}')
                return torch.from_numpy(found[0])

            def conv(name, cin, cout, kernel, stride=1, depthwise=False):
                # Original checkpoint is Keras 2.1: symmetric padding, including stride 2.
                layer = nn.Conv2d(cin, cout, kernel, stride, kernel // 2, groups=cin if depthwise else 1, bias=False)
                source = array(name, 'depthwise_kernel' if depthwise else 'kernel')
                layer.weight.data.copy_(source.permute(2, 3, 0, 1) if depthwise else source.permute(3, 2, 0, 1))
                bn = nn.BatchNorm2d(cout, eps=.001)
                for target, key in [('weight','gamma'),('bias','beta'),('running_mean','moving_mean'),('running_var','moving_variance')]:
                    getattr(bn, target).data.copy_(array(name + '_bn', key))
                layers.extend([layer, bn, nn.ReLU6()])

            conv('conv1', 3, 32, 3, 2)
            cin = 32
            for i, cout in enumerate([64,128,128,256,256,512,512,512,512,512,512,1024,1024], 1):
                conv(f'conv_dw_{i}', cin, cin, 3, 2 if i in (2,4,6,12) else 1, True)
                conv(f'conv_pw_{i}', cin, cout, 1)
                cin = cout
            self.features = nn.Sequential(*layers)
            self.head = nn.Linear(1024, 10)
            self.head.weight.data.copy_(array('dense_1', 'kernel').T)
            self.head.bias.data.copy_(array('dense_1', 'bias'))

    def forward(self, rgb):
        return torch.softmax(self.head(self.features(rgb).mean((2,3))), dim=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true')
    args = parser.parse_args()
    if not WEIGHTS.exists():
        if not args.download:
            raise RuntimeError('Missing NIMA weights; pass --download at build time')
        WEIGHTS.parent.mkdir(parents=True, exist_ok=True)
        with TemporaryDirectory(dir=WEIGHTS.parent) as temp:
            path = Path(temp) / 'weights.h5'
            urllib.request.urlretrieve(URL, path)
            if hashlib.sha256(path.read_bytes()).hexdigest() != SHA256:
                raise RuntimeError('NIMA checksum mismatch')
            path.replace(WEIGHTS)
    if hashlib.sha256(WEIGHTS.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError('NIMA checksum mismatch')
    torch.set_num_threads(2)
    model = Nima(WEIGHTS).eval()
    torch.manual_seed(42)
    sample = torch.rand(2,3,224,224) * 2 - 1
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=ASSET.parent) as temp:
        target = Path(temp) / ASSET.name
        torch.onnx.export(model, sample[:1], target, input_names=['rgb'], output_names=['ratings'],
            dynamic_axes={'rgb': {0:'batch'}, 'ratings': {0:'batch'}}, opset_version=17)
        session = ort.InferenceSession(str(target), providers=['CPUExecutionProvider'])
        for batch in [sample[:1], sample]:
            actual = session.run(None, {'rgb':batch.numpy()})[0]
            np.testing.assert_allclose(actual, model(batch).detach().numpy(), atol=2e-5, rtol=1e-4)
            np.testing.assert_allclose(actual.sum(1), 1, atol=1e-5)
        target.replace(ASSET)
    print(f'NIMA validated: {ASSET.stat().st_size / 1024**2:.2f} MiB -> {ASSET}')

if __name__ == '__main__':
    main()
