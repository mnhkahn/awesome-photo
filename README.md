# Awesome Photo

本地壁纸照片分析器。上传一张照片后，它会：

- 使用 SegFormer-B0（ADE20K）提取天空、水面、建筑、山体、人物等语义区域；
- 将天空/水面识别为可留白区域，将人物、建筑、山体等识别为视觉主体；
- 用 OpenCV 计算清晰度、曝光和对比度；
- 先判断原图是否推荐作为壁纸，并输出独立的基础质量评分；
- 竖图推荐作为手机壁纸，横图推荐作为电脑壁纸；全程保留原图，不生成裁切版本。
- 批量分析通过 macOS 原生目录选择器直接读取本机文件夹，不经过浏览器上传；所有尺寸合格图片都会打分，默认阅览 95 分以上的图片。分析只预览、不保存；在评分段、类别、横竖屏三组筛选后，点击导出才选择目标目录复制原图。
- Web 与 Android 都会保存带算法版本的本地识别缓存。缓存只在“算法版本、文件位置、文件大小、文件修改时间”全部一致时复用；任一项变化即自动重新识别。
- 批量导出只枚举 JPG、JPEG、PNG、WebP、BMP、TIFF、HEIC 和 HEIF；目录内的其他文件从一开始就不会被读取。

## 运行

```bash
python3.12 -m venv .venv312
source .venv312/bin/activate
pip install -r requirements.txt
python app.py
```

浏览器会自动打开 `http://127.0.0.1:7866`。

第一次点击“分析照片”时会下载 `nvidia/segformer-b0-finetuned-ade-512-512` 的权重并缓存在本机。该版本用于本地技术验证；正式商业发布前必须复核模型和训练数据的许可。

当前分数由公开、可解释的图像质量和构图规则计算。后续会用用户对入选图片的选择作为训练数据，校准评分模型。

## Android App

原生 Android 工程位于 `app/`。它使用系统目录授权读取手机上的文件：先按图片扩展名和最小分辨率过滤，再以 4 张一批在设备本机调用 SegFormer ONNX 模型，显示唯一进度条和三组筛选。模型不会接收或上传图片。

构建前导出模型资产：

```bash
.venv312/bin/pip install -r tools/requirements-model.txt
.venv312/bin/python tools/export_android_model.py --download
```

然后用 Android Studio 打开本仓库，或运行 `./gradlew :app:assembleDebug`。模型二进制故意不提交 Git，避免仓库被 10MB+ 的派生权重占用。

已有本地权重缓存时可以省略 `--download`。发布流水线会自动导出并验证模型，打包后还会检查 APK 内的模型内容。所有构建都会检查模型文件是否存在且非空，避免发布缺少模型的安装包。

## 发布到蒲公英

推送版本 tag（例如 `git tag 0.1.7 && git push origin 0.1.7`）或手动运行 GitHub Actions 的 **Android Release Build** 并填写版本号。工作流会构建签名 APK、用 `git-chglog` 生成更新说明、上传到蒲公英并轮询到发布完成，最后用同一份说明创建 GitHub Release。

在仓库 **Secrets** 配置：

- `RELEASE_KEYSTORE_BASE64`：release keystore 的 Base64 内容
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `PGYER_API_KEY`
- `LARK_RELEASE_WEBHOOK`：飞书群机器人 webhook；仅在蒲公英和 GitHub Release 均成功后发送通知

可选：在仓库 **Variables** 配置 `PGYER_SHORTCUT`（例如 `awesomephoto`，对应 `https://www.pgyer.com/awesomephoto`）。不配置时蒲公英仍会正常发布；配置后，流水线会保持使用该固定公开下载页，并将其编译进 App。

App 启动时会访问该公开页面，读取最新版本与更新说明。发现更新会显示提示，点击“前往蒲公英更新”后由蒲公英生成短时安装链接并引导安装；App 不从 GitHub 下载，也不包含蒲公英 API Key。
