# Awesome Photo

本地壁纸照片分析器。上传一张照片后，它会：

- 使用 SegFormer-B0（ADE20K）提取天空、水面、建筑、山体、人物等语义区域；
- 将天空/水面标为状态栏留白候选，将人物、建筑、山体等标为裁切保护区域；
- 用 OpenCV 计算清晰度、曝光和对比度；
- 先判断原图是否推荐作为壁纸，并输出独立的基础质量评分；
- 竖图推荐作为手机壁纸，横图推荐作为电脑壁纸；全程保留原图，不生成裁切版本。

## 运行

```bash
python3.12 -m venv .venv312
source .venv312/bin/activate
pip install -r requirements.txt
python app.py
```

浏览器会自动打开 `http://127.0.0.1:7866`。

第一次点击“分析照片”时会下载 `nvidia/segformer-b0-finetuned-ade-512-512` 的权重并缓存在本机。该版本用于本地技术验证；正式商业发布前必须复核模型和训练数据的许可。

当前分数由公开、可解释的构图规则计算。后续会将表格中的特征和用户对裁切版本的选择，作为 `WallpaperRanker` 的训练数据。
