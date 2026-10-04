"""Run exported ONNX models locally and export inputs for Kotlin score verification.
Example: python tools/benchmark_local_scoring.py --output /tmp/photo-samples photo1.jpg photo2.jpg
Desktop timings are not Android performance estimates. No images are uploaded.
"""
import argparse
import json
import struct
import time
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('images',nargs='+',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--runs',type=int,default=10)
    parser.add_argument('--aesthetic-model',choices=['nima-mobile','topiq-res50'],default='nima-mobile')
    parser.add_argument('--controls',action='store_true',help='Evaluate synthetic degradations in memory; never write changed photos')
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    options=ort.SessionOptions();options.intra_op_num_threads=2
    def session(name):
        return ort.InferenceSession(str(ROOT/'app/src/main/assets'/name),sess_options=options,providers=['CPUExecutionProvider'])
    catalog=json.loads((ROOT/'app/src/main/assets/models.json').read_text())
    spec=next(m for m in catalog['models'] if m['id']==args.aesthetic_model)
    nima=session('nima_mobilenet.onnx' if spec['id']=='nima-mobile' else 'topiq_ava_res50.onnx')
    seg=session('segformer_b0_ade512_int8.onnx')
    report=[]
    def cases():
        for path in args.images:
            with Image.open(path) as image:
                full=np.asarray(ImageOps.exif_transpose(image).convert('RGB'))
            h,w=full.shape[:2]
            sample_size=1
            while max(w,h)//sample_size>2048: sample_size*=2
            decoded=cv2.resize(full,(max(1,w//sample_size),max(1,h//sample_size)),interpolation=cv2.INTER_AREA)
            yield path, 'original', decoded
            if args.controls:
                yield path, 'blur', cv2.GaussianBlur(decoded,(0,0),max(decoded.shape[:2])*.01)
                yield path, 'dark', (decoded.astype('float32')*.25).astype('uint8')
                yield path, 'overexposed', np.minimum(decoded.astype('float32')*2,255).astype('uint8')
                noise=np.random.default_rng(42).normal(0,55,decoded.shape)
                yield path, 'noise', np.clip(decoded.astype('float32')+noise,0,255).astype('uint8')
    for index,(path,variant,decoded) in enumerate(cases()):
        def rgb(size): return cv2.resize(decoded,(size,size),interpolation=cv2.INTER_LINEAR).astype('float32')
        pixels=rgb(spec['inputSize'])
        normalized=pixels/127.5-1 if spec['normalization']=='minus-one-one' else pixels/255
        x=np.ascontiguousarray(normalized.transpose(2,0,1)[None])
        ratings=nima.run(None,{'rgb':x})[0][0]
        durations=[]
        for _ in range(args.runs):
            t=time.perf_counter();nima.run(None,{'rgb':x});durations.append((time.perf_counter()-t)*1000)
        batch=np.repeat(x,spec['batchSize'],axis=0)
        t=time.perf_counter()
        for _ in range(4//spec['batchSize']): nima.run(None,{'rgb':batch})
        batch_ms=(time.perf_counter()-t)*1000
        y=np.ascontiguousarray(((rgb(512)/255-np.array([.485,.456,.406],dtype='float32'))/np.array([.229,.224,.225],dtype='float32')).transpose(2,0,1)[None])
        t=time.perf_counter();labels=seg.run(None,{seg.get_inputs()[0].name:y})[0][0].argmax(0);seg_ms=(time.perf_counter()-t)*1000
        dh,dw=decoded.shape[:2];scale=min(1,1024/max(dw,dh));sw,sh=max(1,int(dw*scale)),max(1,int(dh*scale))
        sharp=cv2.resize(decoded,(sw,sh),interpolation=cv2.INTER_LINEAR).astype('float32')
        luma=(sharp[:,:,0]*np.float32(.299)+sharp[:,:,1]*np.float32(.587)+sharp[:,:,2]*np.float32(.114))/np.float32(255)
        with (args.output/f'{index}.bin').open('wb') as f:
            f.write(struct.pack('>iiii',sw,sh,labels.shape[1],labels.shape[0]))
            f.write(luma.astype('>f4').tobytes());f.write(labels.astype('>i4').tobytes());f.write(ratings.astype('>f4').tobytes())
        row={'id':index,'file':path.name,'variant':variant,'aestheticModel':spec['id'],'nimaMean':float(ratings@np.arange(1,11)),'nimaMedianMs':float(np.median(durations)) if durations else None,'nimaBatch4Ms':batch_ms,'segmentationMs':seg_ms}
        report.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
    (args.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
