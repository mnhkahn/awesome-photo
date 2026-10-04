"""Optional conversion audit against original Keras-2.1.6 layer topology.
Install keras==3.8.0 (torch backend); not needed for normal app builds.
"""
import os
os.environ['KERAS_BACKEND'] = 'torch'
import keras
import numpy as np
import onnxruntime as ort
import torch
from export_aesthetic_model import WEIGHTS, ASSET

torch.set_num_threads(2)
layers = keras.layers
inputs = layers.Input((224,224,3))
x = inputs

def block(x, filters, kernel, stride=1, depthwise=False):
    if kernel == 3:
        x = layers.ZeroPadding2D(1)(x)
    if depthwise:
        x = layers.DepthwiseConv2D(kernel,strides=stride,padding='valid',use_bias=False)(x)
    else:
        x = layers.Conv2D(filters,kernel,strides=stride,padding='valid',use_bias=False)(x)
    return layers.ReLU(max_value=6)(layers.BatchNormalization(epsilon=.001)(x))

x = block(x,32,3,2)
for i, cout in enumerate([64,128,128,256,256,512,512,512,512,512,512,1024,1024],1):
    x=block(x,None,3,2 if i in (2,4,6,12) else 1,True)
    x=block(x,cout,1)
x=layers.GlobalAveragePooling2D()(x)
x=layers.Dropout(0)(x)
x=layers.Dense(10,activation='softmax')(x)
model=keras.Model(inputs,x)
model.load_weights(WEIGHTS)
session=ort.InferenceSession(str(ASSET),providers=['CPUExecutionProvider'])
rng=np.random.default_rng(42)
for batch in (1,2):
    image=rng.uniform(-1,1,(batch,224,224,3)).astype('float32')
    reference=model.predict(image,verbose=0)
    actual=session.run(None,{'rgb':image.transpose(0,3,1,2)})[0]
    np.testing.assert_allclose(actual,reference,atol=2e-5,rtol=1e-4)
    print('Keras reference batch',batch,'max absolute error',float(abs(actual-reference).max()))
