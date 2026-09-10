"""Local wallpaper-photo analyser.

Run with: python app.py
The first semantic analysis downloads SegFormer-B0 weights from Hugging Face and
caches them locally. Photos are processed only on this computer.
"""

from __future__ import annotations

import math
from functools import lru_cache

import cv2
import gradio as gr
import numpy as np
from PIL import Image, ImageDraw, ImageFont

MODEL_ID = "nvidia/segformer-b0-finetuned-ade-512-512"
MAX_EDGE = 1280

SAFE_LABELS = ("sky", "cloud", "water", "sea", "river", "lake")
PROTECT_LABELS = (
    "person", "building", "tower", "bridge", "mountain", "animal", "bird",
    "minaret", "church", "castle", "monument", "statue",
)
LABEL_ZH = {
    "sky": "天空", "cloud": "云", "mountain": "山体", "tree": "树木",
    "water": "水面", "sea": "海面", "river": "河流", "lake": "湖泊",
    "building": "建筑", "tower": "塔", "bridge": "桥", "person": "人物",
    "road": "道路", "grass": "草地", "plant": "植物", "sand": "沙地",
    "field": "田野", "rock": "岩石", "wall": "墙面",
}


@lru_cache(maxsize=1)
def load_segmenter():
    """Load the model only when the first image is analysed."""
    import torch
    from transformers import AutoImageProcessor, SegformerForSemanticSegmentation

    processor = AutoImageProcessor.from_pretrained(MODEL_ID)
    model = SegformerForSemanticSegmentation.from_pretrained(MODEL_ID)
    model.eval()
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model.to(device)
    return processor, model, device


def downscale(image: Image.Image) -> Image.Image:
    image = image.convert("RGB")
    width, height = image.size
    scale = min(1.0, MAX_EDGE / max(width, height))
    if scale == 1:
        return image
    return image.resize((round(width * scale), round(height * scale)), Image.Resampling.LANCZOS)


def label_kind(label: str) -> str:
    label = label.lower()
    if any(term in label for term in SAFE_LABELS):
        return "safe"
    if any(term in label for term in PROTECT_LABELS):
        return "protect"
    return "neutral"


def segment(image: Image.Image) -> tuple[np.ndarray, dict[int, str]]:
    import torch

    processor, model, device = load_segmenter()
    inputs = processor(images=image, return_tensors="pt")
    inputs = {name: value.to(device) for name, value in inputs.items()}
    with torch.no_grad():
        logits = model(**inputs).logits
    logits = torch.nn.functional.interpolate(
        logits, size=(image.height, image.width), mode="bilinear", align_corners=False
    )
    labels = logits.argmax(dim=1)[0].cpu().numpy().astype(np.int32)
    return labels, {int(key): value for key, value in model.config.id2label.items()}


def masks_from_labels(labels: np.ndarray, names: dict[int, str]) -> tuple[np.ndarray, np.ndarray]:
    safe = np.zeros(labels.shape, dtype=bool)
    protect = np.zeros(labels.shape, dtype=bool)
    for class_id, name in names.items():
        kind = label_kind(name)
        if kind == "safe":
            safe |= labels == class_id
        elif kind == "protect":
            protect |= labels == class_id
    return safe, protect


def make_overlay(image: Image.Image, safe: np.ndarray, protect: np.ndarray) -> Image.Image:
    base = np.asarray(image).astype(np.float32)
    tint = base.copy()
    tint[safe] = tint[safe] * 0.45 + np.array([48, 150, 255]) * 0.55
    tint[protect] = tint[protect] * 0.45 + np.array([255, 84, 84]) * 0.55
    result = Image.fromarray(np.uint8(np.clip(tint, 0, 255)))
    draw = ImageDraw.Draw(result)
    draw.rectangle((12, 12, 430, 72), fill=(0, 0, 0))
    try:
        font = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 16)
        legend = "蓝：天空/水面等留白区  红：人物/建筑/山体等主体"
    except OSError:
        font = ImageFont.load_default()
        legend = "Blue: open background  |  Red: protected subject"
    draw.text((24, 30), legend, fill="white", font=font)
    return result


def semantic_summary(labels: np.ndarray, names: dict[int, str]) -> str:
    counts = np.bincount(labels.ravel())
    entries = []
    for class_id, pixels in enumerate(counts):
        if class_id not in names or pixels == 0:
            continue
        fraction = pixels / labels.size
        if fraction < 0.02:
            continue
        label = names[class_id].lower()
        chinese = next((value for key, value in LABEL_ZH.items() if key in label), names[class_id])
        entries.append((fraction, chinese))
    entries.sort(reverse=True)
    return " · ".join(f"{label} {fraction * 100:.0f}%" for fraction, label in entries[:5]) or "未识别出明显场景元素"


def nearest_thirds_distance(cx: float, cy: float, width: int, height: int) -> float:
    points = [(width / 3, height / 3), (2 * width / 3, height / 3),
              (width / 3, 2 * height / 3), (2 * width / 3, 2 * height / 3)]
    distance = min(math.dist((cx, cy), point) for point in points)
    return min(1.0, distance / math.hypot(width, height))


def quality_metrics(crop: np.ndarray) -> tuple[float, float, float]:
    gray = cv2.cvtColor(crop, cv2.COLOR_RGB2GRAY)
    sharpness = min(1.0, cv2.Laplacian(gray, cv2.CV_64F).var() / 500.0)
    brightness = float(gray.mean() / 255.0)
    exposure = max(0.0, 1.0 - abs(brightness - 0.52) / 0.52)
    contrast = min(1.0, float(gray.std() / 70.0))
    return sharpness, exposure, contrast


def score_photo(
    image_array: np.ndarray, safe: np.ndarray, protect: np.ndarray
) -> tuple[float, dict[str, float]]:
    """Score the original photograph, without considering any wallpaper crop."""
    sharpness, exposure, contrast = quality_metrics(image_array)
    height, width = safe.shape
    if protect.any():
        ys, xs = np.where(protect)
        thirds = 1.0 - nearest_thirds_distance(float(xs.mean()), float(ys.mean()), width, height)
    else:
        thirds = 0.50
    # A photograph with usable open background or an identifiable foreground has
    # more wallpaper potential. This is a transparent interim rule, not an
    # aesthetic-model claim.
    wallpaper_material = min(1.0, safe.mean() * 2.2 + protect.mean() * 0.7)
    score = 35 * sharpness + 25 * exposure + 15 * contrast + 15 * thirds + 10 * wallpaper_material
    return score, {
        "清晰": sharpness,
        "曝光": exposure,
        "对比": contrast,
        "主体构图": thirds,
        "壁纸素材": wallpaper_material,
    }


def grade(score: float) -> str:
    if score >= 80:
        return "很好"
    if score >= 65:
        return "不错"
    if score >= 50:
        return "一般"
    return "较弱"


def score_summary(
    photo_score: float, photo_features: dict[str, float], target: str, semantics: str
) -> str:
    details = " · ".join(f"{name} {value * 100:.0f}%" for name, value in photo_features.items())
    recommendation = f"推荐作为{target}" if photo_score >= 65 else f"暂不推荐作为{target}"
    return (
        f"## {recommendation}\n"
        f"识别到：{semantics}\n"
        f"原图基础质量：{photo_score:.0f} / 100（{grade(photo_score)}）\n"
        f"{details}\n\n"
        f"壁纸类型：{target}\n"
        "将保留原图比例，不会生成或强制使用裁切版本。"
    )


def analyse(image: Image.Image | None):
    if image is None:
        raise gr.Error("请先上传一张照片。")
    image = downscale(image)
    try:
        labels, names = segment(image)
    except Exception as exc:
        raise gr.Error(f"分割模型加载失败：{exc}") from exc
    safe, protect = masks_from_labels(labels, names)
    image_array = np.asarray(image)
    photo_score, photo_features = score_photo(image_array, safe, protect)
    semantics = semantic_summary(labels, names)
    if image.height > image.width:
        target = "手机壁纸"
    elif image.width > image.height:
        target = "电脑壁纸"
    else:
        target = "方形壁纸"
    return make_overlay(image, safe, protect), score_summary(
        photo_score, photo_features, target, semantics
    )


with gr.Blocks(title="壁纸照片分析器") as demo:
    gr.Markdown("# 壁纸照片分析器\n上传照片，判断它是否适合做壁纸。竖图推荐给手机，横图推荐给电脑；始终保留原图。")
    with gr.Row():
        source = gr.Image(type="pil", label="选择照片")
        overlay = gr.Image(type="pil", label="模型识别结果")
    analyse_button = gr.Button("分析照片", variant="primary")
    summary = gr.Markdown("上传照片后会显示识别到的场景元素、是否推荐和原图质量。")
    analyse_button.click(analyse, inputs=[source], outputs=[overlay, summary])


if __name__ == "__main__":
    demo.launch(inbrowser=True, server_port=7866)
