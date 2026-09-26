# SegFormer 模型资源

将转换后的 `segformer_b0_ade512_int8.onnx` 放到本目录。它来自
`nvidia/segformer-b0-finetuned-ade-512-512`，输入为 `float32 [1,3,512,512]`，
输出为 `logits [1,150,128,128]`。应用启动后只从设备本地 assets 读取它，不会上传照片。

仓库提供 `tools/export_android_model.py` 来导出及量化该模型。模型二进制不纳入 Git；在打包前执行：

```bash
.venv312/bin/pip install -r tools/requirements-model.txt
.venv312/bin/python tools/export_android_model.py --download
```

已有本地模型缓存时可省略 `--download`。发布流水线会自动下载、导出并执行推理校验，然后确认 APK 内的模型与导出结果一致。缺失或空模型会使 Gradle 构建失败。
