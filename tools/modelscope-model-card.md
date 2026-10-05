---
license: other
tags:
- onnx
- image-quality-assessment
- image-segmentation
---

# 壁纸照片分析器：Android 离线模型

供 [awesome-photo](https://github.com/mnhkahn/awesome-photo) 下载到 Android 本地运行的 ONNX 模型。此仓库只托管模型和元数据，不包含用户照片。不提供云端推理。

| 模型 | 输入 | 输出 | 上游来源及许可 |
|---|---|---|---|
| NIMA MobileNet | NCHW RGB 224×224，[-1,1] | 10 档审美概率 | [idealo/image-quality-assessment](https://github.com/idealo/image-quality-assessment)，Apache-2.0，见 NIMA-LICENSE.txt |
| TOPIQ-IAA ResNet50 | 1×3×384×384，RGB [0,1] | 10 档审美概率 | [IQA-PyTorch](https://github.com/chaofengc/IQA-PyTorch)，PolyForm Noncommercial，见 TOPIQ-LICENSE.txt |
| SegFormer B0 ADE20K INT8 | NCHW RGB 512×512，ImageNet 标准化 | N×150×128×128 logits | [nvidia/segformer-b0-finetuned-ade-512-512](https://huggingface.co/nvidia/segformer-b0-finetuned-ade-512-512)，NVIDIA SegFormer 许可，见 SEGFORMER-LICENSE.txt |

这些是第三方模型的 ONNX 派生文件，各自遵循上游许可；不对整个仓库统一授予 Apache 或 MIT 许可。TOPIQ 和 SegFormer 存在非商业使用限制，请阅读各自许可全文。

NIMA 转换保留原 Keras 2.1.6 对称 padding；TOPIQ 使用 pyiqa 0.1.13 的 AVA ResNet50 检查点，并固定单张推理。SegFormer 为动态 INT8 量化版本。导出脚本和验证方式见应用源码。

文件名包含 SHA-256。`models.json` 给出下载地址、校验值、长度、预处理及批大小；下载后校验，再由 ONNX Runtime 本地推理。已发布的带哈希文件名应保留，保证旧版 APP 可以继续下载。

审美模型反映训练数据的评分倾向，并不能自动判断个人旅行回忆或保证符合每个人的壁纸偏好。
