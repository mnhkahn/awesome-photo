"""Export the locally cached SegFormer model to the Android ONNX asset.

Run inside the existing Python virtualenv:
    pip install -r tools/requirements-model.txt
    python tools/export_android_model.py

The source model is the same `nvidia/segformer-b0-finetuned-ade-512-512` used by
the web prototype. It creates an INT8 model for app/src/main/assets/.
"""

import argparse
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from transformers import SegformerForSemanticSegmentation

MODEL_ID = "nvidia/segformer-b0-finetuned-ade-512-512"
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
INT8 = ASSETS / "segformer_b0_ade512_int8.onnx"


class LogitsOnly(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixel_values):
        return self.model(pixel_values=pixel_values).logits


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Allow downloading source weights when not cached (for CI).")
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    model = SegformerForSemanticSegmentation.from_pretrained(MODEL_ID, local_files_only=not args.download).eval()
    sample = torch.randn(1, 3, 512, 512)
    # Keep incomplete exports out of assets and preserve the previous model on failure.
    with TemporaryDirectory(dir=ASSETS.parent) as temporary:
        fp32 = Path(temporary) / "model.onnx"
        int8 = Path(temporary) / INT8.name
        torch.onnx.export(
            LogitsOnly(model), sample, fp32,
            input_names=["pixel_values"], output_names=["logits"],
            dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}},
            opset_version=17,
        )
        quantize_dynamic(fp32, int8, weight_type=QuantType.QUInt8)
        session = ort.InferenceSession(str(int8), providers=["CPUExecutionProvider"])
        logits = session.run(["logits"], {"pixel_values": sample.numpy()})[0]
        if logits.shape != (1, 150, 128, 128) or not np.isfinite(logits).all():
            raise RuntimeError("Exported model returned invalid segmentation logits")
        int8.replace(INT8)
    print(f"Android model written to {INT8}")


if __name__ == "__main__":
    main()
