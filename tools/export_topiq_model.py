"""Export TOPIQ-IAA ResNet50 to ONNX; optional heavier offline aesthetic choice.

Uses the pinned pyiqa 0.1.13 architecture without importing its unrelated LLM,
CUDA and face-alignment dependencies. All weights are loaded with weights_only.
"""
import argparse
import ast
import copy
import hashlib
import importlib.metadata
from pathlib import Path
from tempfile import TemporaryDirectory
import urllib.request

import numpy as np
import onnxruntime as ort
import timm
import torch
from torch import nn
from torch.nn import functional as F
import torchvision.transforms.functional as TF

ROOT=Path(__file__).resolve().parents[1]
WEIGHTS=ROOT/'.cache/topiq/weights.pth'
URL='https://huggingface.co/chaofengc/IQA-PyTorch-Weights/resolve/main/cfanet_iaa_ava_res50-3cd62bb3.pth'
ASSET=ROOT/'app/src/main/assets/topiq_ava_res50.onnx'
SHA256='3cd62bb33f9933ed7c6e3d5e79129e81c898eba78b7a2af516a0b0b974616975'


def load_model():
    package=importlib.metadata.distribution('pyiqa')
    if package.version != '0.1.13': raise RuntimeError('Export requires pyiqa==0.1.13')
    source=Path(package.locate_file('pyiqa/archs/topiq_arch.py')).read_text()
    # Retain the upstream classes unchanged; omit registration/import side effects.
    tree=ast.parse(source)
    nodes=[n for n in tree.body if isinstance(n,(ast.ClassDef,ast.FunctionDef))]
    for node in nodes:
        node.decorator_list=[]
    module=ast.Module(body=nodes,type_ignores=[])
    namespace={'torch':torch,'nn':nn,'F':F,'TF':TF,'timm':timm,'copy':copy,'np':np,
               'IMAGENET_DEFAULT_MEAN':(.485,.456,.406),'IMAGENET_DEFAULT_STD':(.229,.224,.225)}
    exec(compile(ast.fix_missing_locations(module),'pyiqa-0.1.13-topiq','exec'),namespace)
    model=namespace['CFANet'](semantic_model_name='resnet50',model_name='cfanet_iaa_ava_res50',
        backbone_pretrain=False,pretrained=False,use_ref=False,inter_dim=512,num_heads=8,num_class=10,test_img_size=None)
    state=torch.load(WEIGHTS,map_location='cpu',weights_only=True)
    model.load_state_dict(state['params'],strict=True)
    return model.eval()


class Distribution(nn.Module):
    def __init__(self, model):
        super().__init__();self.model=model
    def forward(self,rgb):
        return self.model.forward_cross_attention(rgb)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download',action='store_true')
    args=parser.parse_args()
    if not WEIGHTS.exists():
        if not args.download: raise RuntimeError('Missing TOPIQ weights; pass --download')
        WEIGHTS.parent.mkdir(parents=True,exist_ok=True)
        with TemporaryDirectory(dir=WEIGHTS.parent) as temp:
            path=Path(temp)/'weights.pth';urllib.request.urlretrieve(URL,path)
            if hashlib.sha256(path.read_bytes()).hexdigest()!=SHA256: raise RuntimeError('TOPIQ checksum mismatch')
            path.replace(WEIGHTS)
    if hashlib.sha256(WEIGHTS.read_bytes()).hexdigest()!=SHA256: raise RuntimeError('TOPIQ checksum mismatch')
    torch.set_num_threads(2)
    model=Distribution(load_model()).eval()
    # Fixed one-image batch bounds memory on Android; the client chunks larger scans.
    torch.manual_seed(42); sample=torch.rand(1,3,384,384)
    with TemporaryDirectory(dir=ASSET.parent) as temp:
        target=Path(temp)/ASSET.name
        with torch.no_grad():
            torch.onnx.export(model,sample,target,input_names=['rgb'],output_names=['ratings'],opset_version=17)
        session=ort.InferenceSession(str(target),providers=['CPUExecutionProvider'])
        for x in (sample,torch.zeros_like(sample)):
            with torch.no_grad(): expected=model(x).numpy()
            actual=session.run(None,{'rgb':x.numpy()})[0]
            np.testing.assert_allclose(actual,expected,atol=3e-5,rtol=3e-4)
            np.testing.assert_allclose(actual.sum(1),1,atol=1e-5)
        target.replace(ASSET)
    print('TOPIQ ONNX validated:',ASSET.stat().st_size/1024**2,'MiB')

if __name__=='__main__': main()
