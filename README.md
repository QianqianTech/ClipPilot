# ClipPilot · 视频浏览助手

ClipPilot 是一款 Android 短视频浏览助手。选好平台和每个平台的浏览条数，启动后，应用会按顺序切换平台并执行上滑。你可以用它减少重复的手势操作，也可以按关键词安排一批想看的内容。

[**下载 Android APK**](https://github.com/QianqianTech/ClipPilot/releases/download/v0.8.0-debug/VideoBrowseAssistant.apk) · [发行说明](https://github.com/QianqianTech/ClipPilot/releases/tag/v0.8.0-debug) · [SHA-256 校验](APK_SHA256.txt)

当前下载为 `0.8.0-debug` 调试预览版，支持 Android 8.0（API 26）及以上。安装后，桌面应用显示为「视频浏览助手」，提供简体中文和英文界面。

## 主要功能

| 功能 | 怎么用 |
| --- | --- |
| 多平台批次 | 一次选择多个已安装的平台，每个平台设置 2–100 条。达到配额后依次切换。 |
| 浏览节奏 | 每条视频停留约 10–30 秒，单个批次最长 30 分钟。运行时可手动停止。 |
| 内容定向 | 用「想看什么」和「不想看什么」设置方向，依据前台页面可读取的文字在本机匹配。 |
| 评论草稿 | 暂停浏览后，按视频要点和关键词生成候选。你可以选择一条填入评论框，再自行发送。 |
| 统计与记录 | 查看完成的批次、平台数、视频数和跳过数，以及最近 20 条批次记录。 |

## 支持平台

| 分组 | 平台 |
| --- | --- |
| 国内 | 微信视频号、抖音、快手、B 站、快手极速版 |
| 国际 | TikTok、YouTube Shorts、Instagram Reels、Kwai、X、REDnote |

微信视频号和 B 站建议先手动进入对应的视频页面，便于切换回来时恢复。自动切换及页面恢复会受 Android 版本、手机厂商和目标应用版本影响。

## 安装与使用

1. 下载并打开 `VideoBrowseAssistant.apk`。若系统询问安装来源，按提示给当前浏览器或文件管理器授权。
2. 打开「视频浏览助手」，在服务卡片点击「去开启」。在系统无障碍设置的已安装服务列表中启用「视频浏览助手无障碍服务」。
3. 回到「任务配置」，选择已安装的平台、设置各平台条数，再点击「启动」。批次按配置顺序运行，切换失败的平台会被跳过。
4. 在首页或「运行管理」随时停止任务。开启通知权限后，也可从通知栏停止任务。完成情况见「统计与记录」。

如果无障碍开关不可用，可在应用内点「找不到服务？打开应用详情」。若系统应用详情页提供「允许受限设置」，按提示开启后回到无障碍设置重试。

## 内容定向与评论草稿

- **内容定向**在「任务配置」中开启并确认说明。填写至少一个想看的方向，可以再填写排除方向。开启后，配额按匹配的视频计数，每个平台设有扫描上限。
- **评论草稿**在「互动配置」中开启。视频位于前台时，从通知栏点「暂停并读取」，补充视频要点或关键词后生成 3 条候选。选定后，回到目标应用聚焦评论框，再从通知栏点「填入草稿」。
- **可选模型网关**可为评论草稿生成模型候选。自行部署 HTTPS 网关后，在「互动配置」填写网关地址、选择档位并粘贴短期设备令牌。部署说明见 [模型网关文档](gateway/README.md)。

## 权限与数据

无障碍服务用于前台上滑，并在启用内容定向或评论辅助后读取对应页面的可访问信息。切离当前任务应用时，上滑暂停。任务配置与统计保存在本机。

浏览、内容定向和本地评论草稿可在未配置模型网关时使用。配置网关后，只有在暂停浏览、确认数据披露并手动点击生成时，应用才会向指定的 HTTPS 网关发送生成所需内容。设备令牌只保留在本次应用内存中。

## 从源码构建

使用 JDK 17 和 Android SDK 36。运行网关测试可使用 Node.js 22，与 CI 配置一致。

```bash
git clone https://github.com/QianqianTech/ClipPilot.git
cd ClipPilot
./gradlew assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。运行 `bash scripts/verify.sh` 可执行 Android 单元测试、Lint、Checkstyle、构建及网关检查，日志写入 `build-logs/verify-latest.log`。Gradle 依赖已缓存时可传入 `--offline`。

正式签名构建通过 `keystore.properties` 或 CI 的 `RELEASE_*` 环境变量配置，示例见 [keystore.properties.example](keystore.properties.example)。

## 项目资料

- [更新日志](CHANGELOG.md) 记录版本及 APK 校验值。
- [任务清单](TASK_CHECKLIST.md) 与 [改进任务清单](IMPROVEMENT_TASK_CHECKLIST.md) 记录已完成工作和待真机验收项。
- [Android 源码](app/src/main/) 包含界面、无障碍服务、任务队列和会话管理。
- [技术文档](docs/) 涵盖平台接入、内容定向、评论辅助和 Android 版本适配。
- [GitHub Issues](https://github.com/QianqianTech/ClipPilot/issues) 接收问题反馈。反馈时可附上手机型号、Android 版本、目标平台和复现步骤。
