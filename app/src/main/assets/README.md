# 按需下载的本地模型

APK 只携带 models.json 与许可证。模型经下载校验后保存在应用 noBackupFilesDir/analysis-models，切换或重启不删除。

- NIMA MobileNet：轻量审美，约 12.25 MiB。224×224 NCHW RGB [-1,1]，输出 [N,10] 分布。
- TOPIQ-IAA ResNet50：可选审美，约 264 MiB。384×384 NCHW RGB [0,1]，输出 [1,10] 分布，单张运行。更大不代表必然更符合个人喜好。
- SegFormer B0：物体／语义区域识别，约 4.12 MiB。512×512 NCHW RGB ImageNet 标准化，输出 [N,150,128,128]。

NIMA 权重来自 idealo/image-quality-assessment，提交 dceaf7c2d218bc6e80b21d6e147e3b56a21b7f31；SHA-256 e563ad91b3d47410e45f7238f07ab8f6abd1bd0c4b18a4b0af9c681a21a91cb2。保持原 Keras 2.1.6 对称 padding。Copyright 2018 idealo internet GmbH，Apache-2.0，见 NIMA-LICENSE.txt。

TOPIQ 使用 pyiqa 0.1.13 的 ResNet50/AVA 架构及 cfanet_iaa_ava_res50-3cd62bb3.pth，SHA-256 3cd62bb33f9933ed7c6e3d5e79129e81c898eba78b7a2af516a0b0b974616975。导出固定 384 方形输入和单张批次，校验 ONNX 与 PyTorch 的概率分布。IQA-PyTorch Copyright (c) 2022 Chaofeng Chen，PolyForm Noncommercial 许可，见 TOPIQ-LICENSE.txt。

导出／发布流程见仓库 README 和 tools/export_*model.py、tools/package_models.py。每次导出后生成独立附件；模型 ID、SHA-256 与预处理共同区分评分缓存。仅替换下载镜像而内容相同时会继续复用。
