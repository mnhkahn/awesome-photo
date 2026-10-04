"""Build a small model catalog and immutable standalone download assets.
Weights are excluded from APK assets. Publish the files in --output to --base-url.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from urllib.parse import urlparse

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
MODELS=[
    ('nima-mobile','NIMA MobileNet（轻量）','aesthetic','nima_mobilenet.onnx',224,'minus-one-one',4),
    ('topiq-res50','TOPIQ-IAA ResNet50','aesthetic','topiq_ava_res50.onnx',384,'zero-one',1),
    ('segformer-b0','SegFormer B0','segmentation','segformer_b0_ade512_int8.onnx',512,'imagenet',4),
]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/model-downloads')
    args=parser.parse_args()
    if urlparse(args.base_url).scheme!='https': parser.error('HTTPS asset base URL required')
    args.output.mkdir(parents=True,exist_ok=True)
    rows=[]
    for id,name,role,filename,size,normalization,batch in MODELS:
        source=ASSETS/filename
        if not source.is_file() or not source.stat().st_size: raise RuntimeError(f'Missing exported model: {filename}')
        digest=hashlib.sha256(source.read_bytes()).hexdigest()
        target=f'{id}-{digest}.onnx'
        shutil.copyfile(source,args.output/target)
        rows.append(dict(id=id,name=name,role=role,url=args.base_url.rstrip('/')+'/'+target+'?download=1',
                         sha256=digest,bytes=source.stat().st_size,inputSize=size,normalization=normalization,batchSize=batch))
    data=json.dumps({'schema':1,'models':rows},ensure_ascii=False,indent=2)+'\n'
    (ASSETS/'models.json').write_text(data)
    (args.output/'models.json').write_text(data)
    print('Packaged',len(rows),'models for separate download. Catalog:',ASSETS/'models.json')

if __name__=='__main__': main()
