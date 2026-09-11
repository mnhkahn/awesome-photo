"""Export the locally cached SegFormer model to the Android ONNX asset.

Run inside the existing Python virtualenv:
    pip install onnx onnxruntime
    python tools/export_android_model.py

The source model is the same `nvidia/segformer-b0-finetuned-ade-512-512` used by
the web prototype. It creates an INT8 model for app/src/main/assets/.
"""

from pathlib import Path

import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from transformers import SegformerForSemanticSegmentation

MODEL_ID = "nvidia/segformer-b0-finetuned-ade-512-512"
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
FP32 = ASSETS / "segformer_b0_ade512_fp32.onnx"
INT8 = ASSETS / "segformer_b0_ade512_int8.onnx"


class LogitsOnly(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixel_values):
        return self.model(pixel_values=pixel_values).logits


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    model = SegformerForSemanticSegmentation.from_pretrained(MODEL_ID, local_files_only=True).eval()
    sample = torch.randn(1, 3, 512, 512)
    torch.onnx.export(
        LogitsOnly(model), sample, FP32,
        input_names=["pixel_values"], output_names=["logits"],
        dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=17,
    )
    quantize_dynamic(FP32, INT8, weight_type=QuantType.QUInt8)
    FP32.unlink()
    print(f"Android model written to {INT8}")


if __name__ == "__main__":
    main()
