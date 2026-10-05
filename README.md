# Awesome Photo

本地壁纸照片分析器。下述运行方式和旧规则属于 Python Web 原型；Android 的离线模型与新评分见后文。上传一张照片后，它会：

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

原生 Android 工程位于 `app/`，从已授权的系统相册按日期和最小尺寸筛选，以 4 张一批调用本地 NIMA 审美模型和 SegFormer 分割模型。模型按需下载到应用持久目录；下载完成后分析不联网、不上传照片、不调用 LLM。审美模型提供 NIMA MobileNet 和 TOPIQ-IAA ResNet50 两个选项，保存上次选择，切换不删除已下载模型。首次打开及切换模型时自动下载，没有单独的下载按钮；下载失败后，点击“开始分析”会重试并在准备完成后继续分析。图片解码限制在最长边 2048 像素以内，批次结束释放位图；缓存命中时不加载模型。

照片预览会从原图读取拍摄时间和 GPS。Android 10 及以上需在预览中点击“允许读取照片位置”授权；授权后立即刷新，不必重新分析。地址由系统地理编码服务解析，服务不可用时显示经纬度；没有权限、原图读取失败和文件未记录 GPS 会分别提示。相册自行保存或云端补充的位置不一定写在照片文件中。

题材筛选固定为五类：人物、风景、建筑、人文艺术、其他（另有“全部”入口）。人文艺术对应模型识别的雕塑、绘画；建筑包括房屋、高楼、桥梁和塔；风景包含山水、天空、草木等。每张图只归一类：艺术区域 >8% 优先，其次人物 >1.5%、建筑 >12%、风景 >12%，否则归入其他。这样的优先级可避免雕塑因背景游客、建筑因背景天空被分走，但分类仍取决于模型识别结果。类别规则更新后，下次扫描会重新分析旧缓存。

Android 打包时计算评分代码指纹；缓存键另含所选审美模型和识别模型的 ID、SHA-256 及预处理配置。切换模型自动隔离结果，切回同一模型且规则和图片未改变时复用原缓存；单纯换下载地址不导致重算。

模型目录为 `noBackupFilesDir/analysis-models`，不使用临时缓存，不随切换删除，也不进入系统备份。下载使用 HTTPS、长度和 SHA-256 校验，通过后原子保存；失败只清理未完成文件，可重试。重启后保留所选模型和已完成的下载。APK 仅包含 `models.json` 和许可证，不含 `.onnx`；清理应用缓存不会移除已下载模型。首次使用需要下载所选审美模型和 SegFormer，后续离线使用。

### Android 本地壁纸评分

初版总分 = 美感 70% + 主体明确度（视觉结构近似）20% + 清晰度 10%，最终向下取整。权重是产品初始选择，未经过用户偏好数据校准。默认显示全部结果并按分数排序，提供 80+、70–79、60–69、60 以下筛选。分数是相对筛选依据，不是考试及格率，不能与此前人工评图的 89 分或旧版分数直接比较。

- **画面美感**：按选择使用 [NIMA MobileNet](https://github.com/idealo/image-quality-assessment)（224×224，RGB [-1,1]）或 [TOPIQ-IAA ResNet50](https://github.com/chaofengc/IQA-PyTorch)（384×384，RGB [0,1]，模型内标准化）。二者预测 1–10 分概率分布；已验证权重使用各自的视觉参考校准系数映射到 0–100，再按 70% 计入总分：NIMA 为 `21.101049 × 均值 − 35.624449`，TOPIQ 为 `18.416472 × 均值 − 25.420048`，结果限制在 0–100。发布前必须验证目录里的模型 SHA-256 与校准匹配；运行时若不匹配，停止分析并提示更新，禁止静默回退旧公式。APP 不调用大模型，不虚构逐张光影、色彩解释。
- **主体明确度**：用分割图的区域集中连贯性作弱近似。按同标签四邻域找连通区域，忽略小于全图 0.1%（至少 2 像素）的碎片，最大三个区域面积除以全部有效区域面积。任何题材使用同一公式，不依赖类别是否为“其他”。区域不足时按中性 50% 并明确提示。大背景也可能得到高分，此项不能理解主体关系或故事，不能代替人工主题判断。
- **清晰度**：最长边 1024 像素，轻度降噪后测有效边缘的局部陡峭程度。边缘指标达到 70% 得满分；人物边缘充足时人物占 70%、全图占 30%。平坦区域不计入边缘平均；不足 12 个边缘时用中性 50% 并提示证据不足。70% 是经验线，未针对具体屏幕或观看距离校准。

旅行回忆不自动打分；不评价画面比例、裁切和图标遮挡。最小尺寸仍作为入选过滤，题材用于分类。每张照片均展示上述实际指标及限制。分析失败不会伪造一个完整分数写入缓存。

旧评分缓存会在**下一次点击分析时**自动失效重算，不是在安装后后台扫描整个相册。新结果继续缓存。Python Web 原型仍使用旧规则。

本地复现样例和性能（需安装 `opencv-python`、`Pillow`）：

```bash
.venv312/bin/python tools/benchmark_local_scoring.py --output /tmp/wallpaper-samples /path/photo1.jpg /path/photo2.jpg
WALLPAPER_TEST_SAMPLES=/tmp/wallpaper-samples ./gradlew :app:testDebugUnitTest
```

该脚本运行真实 ONNX 模型，随后将分割、亮度和审美输出送入 Kotlin 生产评分公式。桌面图像解码与 Android 可能有细微差异，桌面耗时不能视为手机耗时。没有设备实测前不承诺手机吞吐量。

### 大模型视觉参考校准（初版）

2026-10-05 从用户授权的下载目录筛出 57 张不重复的高分辨率照片，要求短边 ≥2000 像素、总像素 ≥600 万，实际最低约 675 万像素。由本次对话助手看图提供 0–100 的**美感单项**参考评分与理由；不让用户提前标好坏，不评价手机/桌面适配，也不推断个人回忆。57 张的参考评分包含旅行、人像、建筑、艺术和日常记录。

先固定评分、场景分组和划分，再运行两个本地 ONNX 模型。46 张训练图拟合每个模型独立的线性系数，11 张相互独立场景的验证图不参与拟合；同一场景、近似画面不跨训练和验证。验证美感百分制 MAE：NIMA 30.27 → 7.05，TOPIQ 26.49 → 6.85；训练均值常数基线 MAE 为 7.96。参考差距 ≥5 分的 44 对验证照片，模型排序一致率分别为 77.3% 和 70.5%，正向线性校准不会改变这些排序。

这是单次助手视觉参考，并非独立人工标准答案；主要使用联系表检查整体观感，数据偏向此目录的旅行照片。只有 11 张验证图，低分照片在验证集覆盖不足，未知类型与极端质量的泛化未得到充分验证。不能把数字分布扩大解释成审美识别能力提升。70/20/10 的总分权重未在此轮调整，个人偏好仍未验证。

汇总证据与系数在 `tools/aesthetic-calibration-report.json`；私有照片路径、逐张参考评分和原始预测保存在忽略 Git 的 `.cache/teacher-calibration/`，未上传到模型仓库。Android 系数由 `AestheticCalibration.kt` 按模型 ID 和权重 SHA-256 绑定；该文件参与分析指纹，规则升级后下一次分析会自动重算旧评分，模型文件继续保留。

复现实验（使用已有参考标签，不调用云端 API）：

```bash
.venv312/bin/python tools/predict_calibration_samples.py --labels .cache/teacher-calibration/teacher-labels.json --output .cache/teacher-calibration/recheck
.venv312/bin/python tools/fit_aesthetic_calibration.py .cache/teacher-calibration/recheck/nima-mobile-predictions.json .cache/teacher-calibration/recheck/topiq-res50-predictions.json --output .cache/teacher-calibration/recheck/report.json
```

拟合工具只输出候选报告，不自动覆盖 Android 系数。新增样本时应保持独立验证集，不反复按照验证误差挑选参数。

### 个人偏好验证（尚未校准）

界面分数明确标为“模型参考分”。喜欢的照片只有正例，不能用来验证喜欢与不喜欢的区分能力，也不会直接把这些照片固定到 89 分。

准备已有的“喜欢／一般／不想用”目录后，可完全本地评估：

```bash
.venv312/bin/python tools/evaluate_wallpaper_preferences.py --liked /photos/liked --neutral /photos/neutral --disliked /photos/disliked --output .cache/preference-audit/run-1
```

也支持 `--manifest` 指向 JSON 数组，每项为 `{"path":"/absolute/photo.jpg","group":"liked"}`，group 可为 liked、neutral、disliked。不移动或修改原图；相同文件去重，标签冲突直接报错。输出目录必须是新目录，避免混入上一次结果。报告包含各组分布、两组照片的排序一致率（平分计 0.5）和错排样例；只有正例时不报告区分准确率。它使用实际 Kotlin 总分，不另写一套近似总分公式。

可复跑退化对照：

```bash
.venv312/bin/python tools/benchmark_local_scoring.py --output /tmp/wallpaper-controls --runs 0 --controls /path/photo1.jpg
WALLPAPER_TEST_SAMPLES=/tmp/wallpaper-controls ./gradlew :app:testDebugUnitTest --rerun-tasks
.venv312/bin/python tools/check_control_scores.py /tmp/wallpaper-controls/report.json
```

模糊、变暗、过曝和噪声对照只在内存生成，不写修改后的图片。控制变量排序通过只能说明这些样例的退化响应，不代表已经符合用户审美；分数映射暂不调整，也未以少数正例拟合权重。

模型维护／发布前导出独立下载文件（普通 APK 构建只需已提交的模型目录元数据，不需要下载权重）：

```bash
.venv312/bin/pip install -r tools/requirements-model.txt
.venv312/bin/pip install --no-deps -r tools/requirements-topiq.txt
.venv312/bin/python tools/export_android_model.py --download
.venv312/bin/python tools/export_aesthetic_model.py --download
.venv312/bin/python tools/export_topiq_model.py --download
.venv312/bin/pip install -r tools/requirements-modelscope.txt
# 先设置 MODELSCOPE_API_KEY 环境变量
.venv312/bin/python tools/publish_models_modelscope.py
```

然后用 Android Studio 打开本仓库，或运行 `./gradlew :app:assembleDebug`。模型二进制故意不提交 Git，避免仓库被 10MB+ 的派生权重占用。

已有本地权重时可省略 `--download`。发布流水线使用 `tools/prepare_release_models.py` 获取已提交目录中指定的同一份 ONNX 文件并验证 SHA-256，不在 CI 重新导出，随后生成含下载地址、长度及 SHA-256 的目录，上传到 ModelScope，逐个匿名下载并校验完整文件后才更新 APK 的模型目录，再检查 APK 没有混入权重。ModelScope 上传或验证失败会停止发布，保留原目录。GitHub Release 仍保留模型附件作为发布归档，APP 使用 ModelScope 地址。

NIMA 原权重按上游 Apache-2.0 许可附带声明；TOPIQ/IQA-PyTorch 上游使用 PolyForm Noncommercial 许可，随包及下载附件附带许可全文。TOPIQ 是较大可选模型，约 264 MiB，固定单张推理以控制内存；没有手机实测前不承诺与轻量模型相同的速度。

## 发布到蒲公英

推送版本 tag（例如 `git tag 0.1.7 && git push origin 0.1.7`）或手动运行 GitHub Actions 的 **Android Release Build** 并填写版本号。工作流先上传并验证 ModelScope 模型，再构建签名 APK、用 `git-chglog` 生成更新说明、先发布包含 APK、模型及许可证的 GitHub Release，再上传到蒲公英并轮询到发布完成。

在仓库 **Secrets** 配置：

- `RELEASE_KEYSTORE_BASE64`：release keystore 的 Base64 内容
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `PGYER_API_KEY`
- `MODELSCOPE_API_KEY`：有模型仓库上传权限的魔搭令牌，仅用于发布，不进入 APK
- `LARK_RELEASE_WEBHOOK`：飞书群机器人 webhook；仅在蒲公英和 GitHub Release 均成功后发送通知

模型托管在公开仓库 [mnhkahn/awesome-photo-models](https://modelscope.cn/models/mnhkahn/awesome-photo-models)。可在仓库 **Variables** 配置 `MODELSCOPE_MODEL_REPO` 覆盖默认仓库；本地发布使用同名环境变量。APP 通过 ModelScope 的公开 HTTPS 文件下载接口获取模型，不需要用户登录或令牌。

发布脚本上传模型、许可证及模型说明，真实匿名下载全部模型并校验长度与 SHA-256，全部通过后才替换 APK 内的模型目录。上传失败不会覆盖应用原来的目录。带哈希的旧文件会保留供旧版 APP 使用；更换下载域名不会导致已下载的相同模型重新下载。

可选：在仓库 **Variables** 配置 `APP_UPDATE_URL`，覆盖默认后端更新接口地址。蒲公英短链接在后端 `conf/app_updates.json` 管理；旧 `PGYER_SHORTCUT` 变量不再被新版打包流程使用。

App 启动时访问 `https://www.cyeam.com/api/apps/awesome-photo/update?versionCode=<当前构建号>`，发现更新后交给系统 DownloadManager 在非计费网络后台下载。回到 App 后可查看进度、失败重试，下载完成校验文件大小、包名、versionCode 和签名，然后发起系统安装确认。首次需允许本应用安装更新；不承诺静默安装。后台下载任务和目标版本会持久化，重开 App 可恢复进度，链接过期可重试获取新链接。安装包来自蒲公英，由 cyeam_web 转发，APK 内没有 API Key。

后端须先部署 `cyeam_web` 的 `/api/apps/` 接口，并配置服务端 `PGYER_API_KEY`。多 App 映射位于后端 `conf/app_updates.json`；本 App 的 id 是 `awesome-photo`、包名是 `com.awesomephoto`。可用 Gradle 参数 `-PappUpdateUrl=https://<服务域名>/api/apps/awesome-photo/update` 覆盖更新接口。蒲公英短链接已不再用于客户端解析版本。后端暂不提供 SHA-256（蒲公英元数据无此字段），客户端依赖 HTTPS、文件大小和 Android 签名/包名/版本校验；系统安装器会最终验证安装包。

1.1.1 发布回归：CI 重新导出 ONNX 导致文件 SHA-256 与本地校准绑定不一致，旧客户端静默使用未校准公式。修复后固定下载已校准的模型文件，上传前检查校准报告，打包前运行目录与运行时校准匹配测试；不修改已发布的旧模型文件。更换权重需先完成校准和验证再更新目录。
