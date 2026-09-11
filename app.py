"""Local wallpaper-photo analyser.

Run with: python app.py
The first semantic analysis downloads SegFormer-B0 weights from Hugging Face and
caches them locally. Photos are processed only on this computer.
"""

from __future__ import annotations

import math
import json
import hashlib
import shutil
import sqlite3
import subprocess
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path

import cv2
import gradio as gr
import numpy as np
from fastapi import FastAPI
from PIL import Image, ImageDraw, ImageFont, ImageOps
from pillow_heif import register_heif_opener

MODEL_ID = "nvidia/segformer-b0-finetuned-ade-512-512"
MAX_EDGE = 1280
BATCH_SIZE = 8
ANALYSIS_VERSION = "segformer-b0-ade20k-score-v1"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff", ".heic", ".heif"}
LOGO_PATH = Path(__file__).parent / "assets" / "wallpaper-finder-logo.png"
CACHE_DB_PATH = Path(__file__).parent / ".cache" / "photo-analysis.sqlite3"

register_heif_opener()


def open_analysis_cache() -> sqlite3.Connection:
    CACHE_DB_PATH.parent.mkdir(exist_ok=True)
    db = sqlite3.connect(CACHE_DB_PATH)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("""CREATE TABLE IF NOT EXISTS analysis_cache (
        algorithm_version TEXT, path TEXT, size INTEGER, modified_ns INTEGER,
        score REAL, categories_json TEXT,
        PRIMARY KEY (algorithm_version, path, size, modified_ns))""")
    return db


def cached_analysis(db: sqlite3.Connection, path: Path) -> tuple[float, list[str]] | None:
    """Return a reusable result only for an unchanged file and this exact algorithm version."""
    stat = path.stat()
    row = db.execute(
        "SELECT score, categories_json FROM analysis_cache WHERE algorithm_version=? AND path=? AND size=? AND modified_ns=?",
        (ANALYSIS_VERSION, str(path.resolve()), stat.st_size, stat.st_mtime_ns),
    ).fetchone()
    return (float(row[0]), json.loads(row[1])) if row else None


def save_cached_analysis(db: sqlite3.Connection, path: Path, score: float, categories: list[str]) -> None:
    stat = path.stat()
    db.execute(
        "INSERT OR REPLACE INTO analysis_cache VALUES (?, ?, ?, ?, ?, ?)",
        (ANALYSIS_VERSION, str(path.resolve()), stat.st_size, stat.st_mtime_ns, score, json.dumps(categories)),
    )


def preview_path(path: Path) -> Path:
    """Keep only a small private UI thumbnail; original photos are never exported here."""
    stat = path.stat()
    digest = hashlib.sha256(f"{path.resolve()}:{stat.st_size}:{stat.st_mtime_ns}".encode()).hexdigest()
    preview = CACHE_DB_PATH.parent / "previews" / f"{digest}.jpg"
    if not preview.exists():
        preview.parent.mkdir(parents=True, exist_ok=True)
        with Image.open(path) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            image.thumbnail((480, 480), Image.Resampling.LANCZOS)
            image.save(preview, "JPEG", quality=82, optimize=True)
    return preview


def batch_item(path: Path, target: str, target_label: str, score: float, categories: list[str]) -> dict[str, object]:
    return {
        "source": str(preview_path(path).resolve()),
        "caption": f"{target_label} · {score:.0f} 分 · {'/'.join(categories)} · {path.name}",
        "categories": categories,
        "score_band": score_band(score),
        "orientation": "竖屏" if target == "app" else "横屏",
        "path": str(path.resolve()),
    }

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

    processor = AutoImageProcessor.from_pretrained(MODEL_ID, local_files_only=True)
    model = SegformerForSemanticSegmentation.from_pretrained(MODEL_ID, local_files_only=True)
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
    return segment_batch([image])[0]


def segment_batch(images: list[Image.Image]) -> list[tuple[np.ndarray, dict[int, str]]]:
    """Run several same-sized model inputs in one PyTorch forward pass."""
    import torch

    processor, model, device = load_segmenter()
    inputs = processor(images=images, return_tensors="pt")
    inputs = {name: value.to(device) for name, value in inputs.items()}
    with torch.no_grad():
        logits = model(**inputs).logits
    names = {int(key): value for key, value in model.config.id2label.items()}
    results = []
    for index, image in enumerate(images):
        resized = torch.nn.functional.interpolate(
            logits[index:index + 1], size=(image.height, image.width), mode="bilinear", align_corners=False
        )
        labels = resized.argmax(dim=1)[0].cpu().numpy().astype(np.int32)
        results.append((labels, names))
    return results


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


def semantic_categories(labels: np.ndarray, names: dict[int, str]) -> list[str]:
    """Convert the detailed segmentation labels into user-facing filter groups."""
    active_labels = []
    counts = np.bincount(labels.ravel())
    for class_id, pixels in enumerate(counts):
        if class_id in names and pixels / labels.size >= 0.02:
            active_labels.append(names[class_id].lower())
    if any("person" in label for label in active_labels):
        return ["人物"]
    landscape_terms = ("sky", "cloud", "water", "sea", "river", "lake", "mountain", "tree", "grass", "plant", "field", "rock", "sand")
    if any(any(term in label for term in landscape_terms) for label in active_labels):
        return ["风景"]
    return ["其他"]


def score_band(score: float) -> str:
    if score >= 95:
        return "95 分以上"
    if score >= 90:
        return "90–94 分"
    if score >= 85:
        return "85–89 分"
    return "85 分以下"


SCORE_FILTERS = ("全部", "95 分以上", "90–94 分", "85–89 分", "85 分以下")
CATEGORY_FILTERS = ("全部", "人物", "风景", "其他")
ORIENTATION_FILTERS = ("全部", "横屏", "竖屏")


def item_matches(item: dict[str, object], score_filter: str, category: str, orientation: str) -> bool:
    return (
        (score_filter == "全部" or score_filter == item["score_band"])
        and (category == "全部" or category in item["categories"])
        and (orientation == "全部" or orientation == item["orientation"])
    )


def gallery_items(
    items: list[dict[str, object]], score_filter: str = "95 分以上",
    category: str = "全部", orientation: str = "全部",
) -> list[tuple[str, str]]:
    return [
        (str(item["source"]), str(item["caption"]))
        for item in items
        if item_matches(item, score_filter, category, orientation)
    ]


def progress_markup(current: int, total: int) -> str:
    percent = 0 if total == 0 else round(current / total * 100)
    return (
        f'<div style="display:flex;align-items:center;gap:12px">'
        f'<progress value="{current}" max="{max(1, total)}" style="width:100%"></progress>'
        f'<span style="white-space:nowrap">{current}/{total} · {percent}%</span></div>'
    )


def apply_gallery_filter(
    items: list[dict[str, object]] | None, score_filter: str, category: str, orientation: str,
):
    return gallery_items(items or [], score_filter, category, orientation)


def filter_button_updates(
    items: list[dict[str, object]], score_filter: str, category: str, orientation: str,
) -> tuple[dict, ...]:
    """Each group shows counts after applying the other two groups' choices."""
    def button(value: str, count: int, selected: bool, display: str | None = None) -> dict:
        return gr.update(value=f"{display or value} ({count})", variant="primary" if selected else "secondary")

    score_updates = tuple(
        button(value, sum(item_matches(item, value, category, orientation) for item in items), value == score_filter,
               {"95 分以上": "95+", "90–94 分": "90–94", "85–89 分": "85–89"}.get(value))
        for value in SCORE_FILTERS
    )
    category_updates = tuple(
        button(value, sum(item_matches(item, score_filter, value, orientation) for item in items), value == category)
        for value in CATEGORY_FILTERS
    )
    orientation_updates = tuple(
        button(value, sum(item_matches(item, score_filter, category, value) for item in items), value == orientation)
        for value in ORIENTATION_FILTERS
    )
    return score_updates + category_updates + orientation_updates


def filtered_view(items: list[dict[str, object]] | None, score_filter: str, category: str, orientation: str):
    items = items or []
    return (
        gallery_items(items, score_filter, category, orientation),
        *filter_button_updates(items, score_filter, category, orientation),
        score_filter, category, orientation,
    )


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


def unique_destination(folder: Path, filename: str) -> Path:
    destination = folder / filename
    index = 2
    while destination.exists():
        destination = folder / f"{Path(filename).stem}-{index}{Path(filename).suffix}"
        index += 1
    return destination


def choose_local_folder() -> str:
    """Open macOS's native directory picker; no browser file upload is involved."""
    try:
        result = subprocess.run(
            ["osascript", "-e", 'POSIX path of (choose folder with prompt "选择照片目录")'],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        return ""
    return result.stdout.strip()


def export_filtered(
    items: list[dict[str, object]] | None, score_filter: str, category: str, orientation: str,
) -> str:
    """Ask for an output directory only after the user has chosen a filtered set."""
    matching = [
        item for item in (items or [])
        if (score_filter == "全部" or score_filter == item["score_band"])
        and (category == "全部" or category in item["categories"])
        and (orientation == "全部" or orientation == item["orientation"])
    ]
    if not matching:
        raise gr.Error("当前筛选条件下没有图片可导出。")
    try:
        result = subprocess.run(
            ["osascript", "-e", 'POSIX path of (choose folder with prompt "选择导出目录")'],
            check=True, capture_output=True, text=True,
        )
    except subprocess.CalledProcessError:
        return "已取消导出。"
    destination_folder = Path(result.stdout.strip())
    copied = 0
    for item in matching:
        source = Path(str(item["path"]))
        if source.is_file():
            shutil.copy2(source, unique_destination(destination_folder, source.name))
            copied += 1
    return f"已导出 {copied} 张原图到 `{destination_folder}`。"


def batch_analyse(
    folder: str | None,
    max_images: int,
    desktop_min_width: int,
    desktop_min_height: int,
    app_min_width: int,
    app_min_height: int,
):
    if not folder:
        raise gr.Error("请选择一个图片目录。")
    folder_path = Path(folder).expanduser()
    if not folder_path.is_dir():
        raise gr.Error("所选目录不存在或无法读取。")
    paths = sorted(
        path for path in folder_path.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_SUFFIXES
    )
    scan_limit = max(0, int(max_images))
    if scan_limit:
        paths = paths[:scan_limit]
    if not paths:
        raise gr.Error("目录中没有可分析的 JPG、PNG、WebP、BMP、TIFF、HEIC 或 HEIF 图片。")
    selected_items: list[dict[str, object]] = []
    skipped_reasons: Counter[str] = Counter()
    skipped = 0
    cache_hits = 0
    cache_db = open_analysis_cache()

    def inspect_size(path: Path):
        try:
            with Image.open(path) as opened:
                width, height = ImageOps.exif_transpose(opened).size
        except Exception:
            return path, None, None
        return path, width, height

    with ThreadPoolExecutor(max_workers=min(8, len(paths))) as executor:
        inspected = list(executor.map(inspect_size, paths))

    eligible: list[tuple[Path, str, Path, str]] = []
    for path, width, height in inspected:
        if width is None or height is None:
            skipped += 1
            skipped_reasons["无法读取图片"] += 1
            continue
        if height > width:
            target = "app"
            min_width, min_height = app_min_width, app_min_height
            target_label = "App（移动端）"
        elif width > height:
            target = "desktop"
            min_width, min_height = desktop_min_width, desktop_min_height
            target_label = "桌面"
        else:
            skipped += 1
            skipped_reasons["方图不归入桌面或 App"] += 1
            continue
        if width < min_width or height < min_height:
            skipped += 1
            skipped_reasons["尺寸不足"] += 1
            continue
        eligible.append((path, target, target_label))

    total = len(eligible)

    def live_summary(current: int, current_name: str) -> str:
        return (
            f"## 正在分析 {current} / {total}\n"
            f"当前：`{current_name}`\n\n"
            f"发现图片：{len(paths)} 张  · 尺寸合格候选：{total} 张  · 已评分：{len(selected_items)} 张  · 缓存命中：{cache_hits} 张  · 已过滤：{skipped} 张"
        )

    yield live_summary(0, "等待开始"), [], selected_items, progress_markup(0, total), *filter_button_updates(selected_items, "95 分以上", "全部", "全部"), "95 分以上", "全部", "全部"

    for batch_start in range(0, total, BATCH_SIZE):
        batch = eligible[batch_start:batch_start + BATCH_SIZE]
        originals = []
        readable_batch = []
        for path, target, target_label in batch:
            try:
                cached = cached_analysis(cache_db, path)
            except OSError:
                cached = None
            if cached:
                score, categories = cached
                selected_items.append(batch_item(path, target, target_label, score, categories))
                cache_hits += 1
                continue
            try:
                with Image.open(path) as opened:
                    original = ImageOps.exif_transpose(opened).convert("RGB")
                originals.append(original)
                readable_batch.append((path, target, target_label))
            except Exception:
                skipped += 1
                skipped_reasons["无法读取图片"] += 1
        if not readable_batch:
            current = min(batch_start + len(batch), total)
            yield live_summary(current, batch[-1][0].name), gallery_items(selected_items), selected_items, progress_markup(current, total), *filter_button_updates(selected_items, "95 分以上", "全部", "全部"), "95 分以上", "全部", "全部"
            continue
        try:
            analysed_images = [downscale(original) for original in originals]
            segmented = segment_batch(analysed_images)
        except Exception as exc:
            for path, _target, _target_label in readable_batch:
                skipped += 1
                skipped_reasons["分析失败"] += 1
            current = min(batch_start + len(batch), total)
            yield live_summary(current, batch[-1][0].name), gallery_items(selected_items), selected_items, progress_markup(current, total), *filter_button_updates(selected_items, "95 分以上", "全部", "全部"), "95 分以上", "全部", "全部"
            continue

        for (path, target, target_label), original, analysed, (labels, names) in zip(
            readable_batch, originals, analysed_images, segmented
        ):
            safe, protect = masks_from_labels(labels, names)
            score, _features = score_photo(np.asarray(analysed), safe, protect)
            semantics = semantic_summary(labels, names)
            categories = semantic_categories(labels, names)
            save_cached_analysis(cache_db, path, score, categories)
            selected_items.append(batch_item(path, target, target_label, score, categories))
        current = min(batch_start + len(batch), total)
        yield live_summary(current, batch[-1][0].name), gallery_items(selected_items), selected_items, progress_markup(current, total), *filter_button_updates(selected_items, "95 分以上", "全部", "全部"), "95 分以上", "全部", "全部"

    cache_db.commit()
    cache_db.close()
    reasons = " · ".join(f"{reason} {count}" for reason, count in skipped_reasons.items()) or "无"
    summary = (
        f"## 批量完成\n"
        f"尺寸合格并已评分：{len(selected_items)} 张  · 缓存复用：{cache_hits} 张  · 已过滤：{skipped} 张\n\n"
        f"过滤原因：{reasons}\n\n"
        "当前结果只是本地阅览，尚未保存任何图片。选择三个筛选条件后，点击“导出当前筛选结果”再指定目标目录。"
    )
    yield summary, gallery_items(selected_items), selected_items, progress_markup(total, total), *filter_button_updates(selected_items, "95 分以上", "全部", "全部"), "95 分以上", "全部", "全部"


with gr.Blocks(title="壁纸照片分析器") as demo:
    with gr.Row(equal_height=True):
        gr.Image(value=str(LOGO_PATH), label=None, height=76, interactive=False)
        gr.Markdown("# 壁纸照片分析器\n上传照片，判断它是否适合做壁纸。竖图推荐给手机，横图推荐给电脑；始终保留原图。")
    with gr.Tab("单张分析"):
        with gr.Row():
            source = gr.Image(type="pil", label="选择照片")
            overlay = gr.Image(type="pil", label="模型识别结果")
        analyse_button = gr.Button("分析照片", variant="primary")
        summary = gr.Markdown("上传照片后会显示识别到的场景元素、是否推荐和原图质量。")
        analyse_button.click(analyse, inputs=[source], outputs=[overlay, summary])

    with gr.Tab("批量筛选"):
        gr.Markdown(
            "选择本机目录后，程序只读取图片文件。所有尺寸合格的横图、竖图都会评分并直接预览；默认显示 95 分以上。分析不会保存原图。"
        )
        with gr.Row():
            batch_folder = gr.Textbox(label="本机图片目录", placeholder="点击右侧按钮选择目录")
            choose_folder_button = gr.Button("选择本机目录")
        choose_folder_button.click(choose_local_folder, outputs=[batch_folder])
        with gr.Row():
            max_images = gr.Number(value=100, precision=0, label="最多扫描图片数（0 = 全部）")
            desktop_min_width = gr.Number(value=1920, precision=0, label="桌面最小宽度")
            desktop_min_height = gr.Number(value=1080, precision=0, label="桌面最小高度")
        with gr.Row():
            app_min_width = gr.Number(value=1080, precision=0, label="App 最小宽度")
            app_min_height = gr.Number(value=1920, precision=0, label="App 最小高度")
        batch_button = gr.Button("批量分析", variant="primary")
        batch_summary = gr.Markdown()
        batch_progress = gr.HTML(value=progress_markup(0, 0), label="分析进度")
        gr.Markdown("### 筛选结果")
        gr.Markdown("**评分段（只选一个）**")
        with gr.Row(variant="compact"):
            score_all = gr.Button("全部 (0)", size="sm", min_width=72)
            score_95 = gr.Button("95+ (0)", variant="primary", size="sm", min_width=72)
            score_90 = gr.Button("90–94 (0)", size="sm", min_width=72)
            score_85 = gr.Button("85–89 (0)", size="sm", min_width=72)
            score_low = gr.Button("85 以下 (0)", size="sm", min_width=72)
        with gr.Row(variant="compact"):
            gr.Markdown("**类别**", scale=0, min_width=36)
            category_all = gr.Button("全部 (0)", size="sm", min_width=72)
            person_filter = gr.Button("人物 (0)", size="sm", min_width=72)
            landscape_filter = gr.Button("风景 (0)", size="sm", min_width=72)
            other_filter = gr.Button("其他 (0)", size="sm", min_width=72)
            gr.Markdown("**方向**", scale=0, min_width=36)
            orientation_all = gr.Button("全部 (0)", size="sm", min_width=72)
            landscape_orientation = gr.Button("横屏 (0)", size="sm", min_width=72)
            portrait_orientation = gr.Button("竖屏 (0)", size="sm", min_width=72)
        batch_gallery = gr.Gallery(
            label="已选图片预览", columns=6, rows=2, fit_columns=False,
            object_fit="contain", height=260, elem_id="batch-gallery",
        )
        batch_state = gr.State([])
        score_state = gr.State("95 分以上")
        category_state = gr.State("全部")
        orientation_state = gr.State("全部")
        export_button = gr.Button("导出当前筛选结果")
        export_summary = gr.Markdown()
        batch_button.click(
            batch_analyse,
            inputs=[
                batch_folder, max_images, desktop_min_width, desktop_min_height,
                app_min_width, app_min_height,
            ],
            outputs=[
                batch_summary, batch_gallery, batch_state, batch_progress,
                score_all, score_95, score_90, score_85, score_low,
                category_all, person_filter, landscape_filter, other_filter,
                orientation_all, landscape_orientation, portrait_orientation,
                score_state, category_state, orientation_state,
            ],
        )

        filter_outputs = [
            batch_gallery,
            score_all, score_95, score_90, score_85, score_low,
            category_all, person_filter, landscape_filter, other_filter,
            orientation_all, landscape_orientation, portrait_orientation,
            score_state, category_state, orientation_state,
        ]

        def set_score(value: str):
            return lambda items, category, orientation: filtered_view(items, value, category, orientation)

        def set_category(value: str):
            return lambda items, score, orientation: filtered_view(items, score, value, orientation)

        def set_orientation(value: str):
            return lambda items, score, category: filtered_view(items, score, category, value)

        for button, value in ((score_all, "全部"), (score_95, "95 分以上"), (score_90, "90–94 分"), (score_85, "85–89 分"), (score_low, "85 分以下")):
            button.click(set_score(value), inputs=[batch_state, category_state, orientation_state], outputs=filter_outputs)
        for button, value in ((category_all, "全部"), (person_filter, "人物"), (landscape_filter, "风景"), (other_filter, "其他")):
            button.click(set_category(value), inputs=[batch_state, score_state, orientation_state], outputs=filter_outputs)
        for button, value in ((orientation_all, "全部"), (landscape_orientation, "横屏"), (portrait_orientation, "竖屏")):
            button.click(set_orientation(value), inputs=[batch_state, score_state, category_state], outputs=filter_outputs)
        export_button.click(
            export_filtered,
            inputs=[batch_state, score_state, category_state, orientation_state],
            outputs=[export_summary],
        )

# ASGI entry point required by Vercel's Python runtime.  This only makes the
# Gradio UI mountable; the local-folder workflow still needs a local runtime.
app = gr.mount_gradio_app(
    FastAPI(), demo, path="/",
    allowed_paths=[str(CACHE_DB_PATH.parent / "previews")],
)

if __name__ == "__main__":
    demo.queue().launch(
        inbrowser=True,
        server_port=7866,
        # Only generated 480px thumbnails are exposed to the local Web UI.
        # Original photo folders remain outside Gradio's static-file allow list.
        allowed_paths=[str(CACHE_DB_PATH.parent / "previews")],
    )
