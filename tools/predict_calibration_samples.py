"""Read fixed teacher labels and original photos; export local model predictions for coefficient fitting."""
import json,hashlib
from pathlib import Path
import cv2,numpy as np,onnxruntime as ort
from PIL import Image,ImageOps
import argparse
parser=argparse.ArgumentParser(description='Run local aesthetic predictions for visually labelled high-resolution photos. No uploads.')
parser.add_argument('--labels',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[1];d=json.loads(args.labels.read_text());catalog=json.loads((root/'app/src/main/assets/models.json').read_text());opt=ort.SessionOptions();opt.intra_op_num_threads=2
for mid,fn in [('nima-mobile','nima_mobilenet.onnx'),('topiq-res50','topiq_ava_res50.onnx')]:
 spec=next(x for x in catalog['models'] if x['id']==mid);model_path=root/'app/src/main/assets'/fn
 if hashlib.sha256(model_path.read_bytes()).hexdigest()!=spec['sha256']:raise ValueError('Model weights do not match catalog')
 s=ort.InferenceSession(str(model_path),sess_options=opt,providers=['CPUExecutionProvider']);rows=[]
 for i,r in enumerate(d['samples']):
  if hashlib.sha256(Path(r['path']).read_bytes()).hexdigest()!=r['sha256']:raise ValueError('Photo changed since labelling')
  with Image.open(r['path']) as im:full=np.asarray(ImageOps.exif_transpose(im).convert('RGB'))
  h,w=full.shape[:2]
  if min(w,h)<2000 or w*h<6000000:raise ValueError('Requires original high-resolution photos: 6 MP and 2000 px short edge')
  sample=1
  while max(w,h)//sample>2048:sample*=2
  decoded=cv2.resize(full,(w//sample,h//sample),interpolation=cv2.INTER_AREA);pixels=cv2.resize(decoded,(spec['inputSize'],spec['inputSize']),interpolation=cv2.INTER_LINEAR).astype('float32');x=pixels/127.5-1 if spec['normalization']=='minus-one-one' else pixels/255
  ratings=s.run(None,{'rgb':np.ascontiguousarray(x.transpose(2,0,1)[None])})[0][0]
  rows.append({**r,'raw_mean':float(ratings@np.arange(1,11)),'ratings':ratings.tolist()})
  if (i+1)%15==0:print(mid,i+1,'/',len(d['samples']),flush=True)
 (args.output/f'{mid}-predictions.json').write_text(json.dumps({'model':spec,'samples':rows},ensure_ascii=False,indent=2));print(mid,'done',flush=True)
