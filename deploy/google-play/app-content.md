# Google Play 应用内容填报

- 应用访问权限选择所有功能均可直接访问。首次运行需要联网下载默认模型，批量分析需要至少一张近期的合格照片。
- 广告选择无。Play 版移除了广告 ID 权限，并停用广告 ID 采集与广告个性化信号。应用无广告、内购、订阅和账号注册。
- 应用定位为通用照片和壁纸工具，儿童并非预期受众。按开发者确认的年龄范围填写。按实际功能回答分级问卷，由 IARC 决定分级。
- 政府应用、金融功能和健康功能选否。

## 照片权限声明草稿

应用的核心功能是从已授权的系统相册自动按日期和尺寸批量检索照片，再在本机评分并生成壁纸候选。用户可以扫描不同日期范围，查看每天的照片数量，调整分类和方向筛选，后续可以扫描新增内容。一次性的系统照片选择器要求逐次手动选择具体文件，在应用显示每日照片数量、自动筛选和排名之前就要求用户做选择，不能提供相册索引和后续日期范围的批量查询。Android 14 及以上用户可选仅授权部分照片，应用继续处理授权范围内的照片。应用不会把照片传给 Cyeam 或 Firebase。

## 数据安全核对

| 行为 | 填报依据 |
| --- | --- |
| 本地扫描与评分 | 相册照片和评分在设备本地处理。Android 备份和用户主动导出适用各自的系统流程。 |
| 预览地址 | 可选读取照片的 EXIF GPS 坐标，Android Geocoder 可能联网处理。核对 Android 系统服务及数据安全问卷对处理目的和临时处理的定义。 |
| Firebase Analytics | 自动收集应用互动、会话、应用实例标识符等。按 Google Analytics 对 IP 派生大致位置的规则核对“大致位置”。 |
| Firebase Crashlytics | 收集崩溃日志、诊断、设备或应用状态、Crashlytics 安装 UUID 和 Firebase 安装标识符。 |
| 模型下载 | ModelScope 通过 HTTPS 提供模型，按服务实际行为核对 IP、请求信息、留存、用途及服务提供商身份。 |
| 保留和删除 | 公布本地删除方法及可定位的服务端数据删除请求邮箱。核对 Firebase 属性留存、分享设置和实际删除处理能力后再提交最终答案。 |

- [Firebase SDK 披露](https://firebase.google.com/docs/android/play-data-disclosure)
- [Google Play 数据安全](https://support.google.com/googleplay/android-developer/answer/10787469)
- [照片访问权限](https://support.google.com/googleplay/android-developer/answer/16935362)
