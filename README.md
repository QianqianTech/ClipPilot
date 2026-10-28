# 视频浏览助手（多平台个人无障碍 / 应用测试版）

可构建的 Android 调试工程，当前版本 0.8.0。支持同时勾选微信视频号、抖音、快手、B站、快手极速版，以及 TikTok、YouTube Shorts、Instagram Reels、Kwai、X、REDnote，生成一个多平台任务批次：每个平台按设定配额（2–100 个）浏览视频，完成后自动切换到下一个应用，直到整批完成或达到 30 分钟上限。

Android 同一时刻只能有一个前台应用，所以“同时”是同一批次中的自动排队执行，不是多个应用并行操作。用户必须主动开启无障碍服务并启动任务；只对当前任务平台执行上滑，每条视频随机等待 10–30 秒。随机节奏仅用于用户可见的浏览间隔，不用于绕过平台规则或风控。

工程不包含登录、账号管理、自动点赞、自动收藏、自动发送评论、关注、分享、验证码处理、风控规避或后台隐藏运行。内容定向与评论辅助需单独开启；可选的 HTTPS 模型网关只在用户暂停视频、手动点击生成、确认数据披露并粘贴短期设备令牌后才会请求。使用第三方应用前应先确认平台规则和账号权限。

## 文档

- [更新日志](CHANGELOG.md)：版本、构建产物大小与 SHA-256 的唯一来源。
- [任务清单](TASK_CHECKLIST.md) 与 [改进任务清单](IMPROVEMENT_TASK_CHECKLIST.md)：已完成、待真机验收（`VERIFY`）和待外部条件（`HOLD`）事项。
- [模型网关部署与后台配置](gateway/README.md)：鉴权、限流、安全词表、部署与健康检查。
- 专项资料在 [`docs/`](docs/)：[targetSdk 36 行为审查](docs/TARGET_SDK_36_REVIEW.md)、[首页分组与统计](docs/UI_CONFIGURATION_TASK_CHECKLIST.md)、[海外平台接入](docs/OVERSEAS_PLATFORM_TASK_CHECKLIST.md)、[内容定向](docs/CONTENT_TARGETING_TASK_CHECKLIST.md)、[智能互动](docs/INTERACTION_TASK_CHECKLIST.md)、[iOS 方案](docs/IOS_TASK_CHECKLIST.md)、[任务方案](docs/TASK_PLAN.md)、[0.6 审计报告](docs/AUDIT_REPORT.md)。

## 代码结构

| 模块 | 职责 |
|---|---|
| `MainActivity` | 页面装配、导航、权限、会话启停 |
| `HomeCards` / `StatusCards` / `TaskPage` / `InteractionPage` / `GatewayCard` / `RuntimePage` / `StatsPage` | 各页面卡片与交互 |
| `AutoSwipeAccessibilityService` | 前台判断、定时器、手势、平台切换 |
| `SessionRunner` | 纯 Java 会话状态机：配额、跳过、内容定向计数 |
| `SessionStore` / `SessionKeys` | 会话启停的唯一写入点与偏好键 |
| `SessionCommands` / `SessionCommandReceiver` | 界面与通知动作到服务的显式命令通道 |
| `ContentScanner` / `ContentTargeting` / `SafetyTerms` | 页面文字采集、本地定向匹配、共享安全词表 |
| `CommentWorkflow` / `CommentAssistant` / `ModelGateway*` | 评论读取与填入、本地候选、网关协议与客户端 |
| `Platform` | 支持平台的唯一定义（与 Manifest `<queries>` 由测试保持一致） |

## 构建与验证

需要 JDK 17、Android SDK 36。一键运行全部检查（Android 单元测试、Lint、Checkstyle、debug/release 构建、网关测试与 ESLint），日志写入 `build-logs/verify-latest.log`：

```bash
bash scripts/verify.sh            # 首次或依赖变更后：在线解析依赖
bash scripts/verify.sh --offline  # 依赖已缓存时
```

脚本会在未设置时自动使用 `/opt/homebrew/opt/openjdk@17` 与 `/opt/homebrew/share/android-commandlinetools`。也可以直接执行：

```bash
./gradlew testDebugUnitTest lintDebug lintRelease checkstyle assembleDebug assembleRelease
cd gateway && npm test
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。`debug` / `release` 只是构建与签名类型，功能一致。release 默认开启 R8 与资源压缩；只有提供 `keystore.properties`（参考 `keystore.properties.example`）或 `RELEASE_*` 环境变量时才会签名，否则产物为 unsigned，不能作为发布包。CI 配置见 `.github/workflows/ci.yml`。

这是 debug 测试版，尚未完成正式签名、商店发布、隐私政策或设备兼容性认证。

## 使用

1. 安装 APK，打开“视频浏览助手”。
2. 点击服务卡片的“去开启”。系统会打开“无障碍”总页面；向下滑到“已下载的应用”或“已安装的服务”，选择“视频浏览助手无障碍服务”并开启，返回应用后状态应显示“已开启”。
3. 如果列表没有该服务，或服务开关不可点，返回助手后点击“找不到服务？打开应用详情”，在系统应用详情页的右上角更多菜单中查找并开启“允许受限设置”，再回到无障碍页面重试。不同厂商系统的菜单名称和位置可能不同。
4. 在微信中先进入一次视频号，在 B 站中先进入一次竖屏视频页；这样自动切回应用时更可能恢复到正确页面。助手不会自动寻找入口。
5. 首页进入“任务配置”，点击“选择平台”打开分组弹窗；未安装平台不可应用。设置每个平台的视频任务（2–100 个），顺序会保存在本机。
6. 如需内容定向，在“任务配置”开启并确认披露，填写至少一个“想看什么”方向；“不想看”可选。每个平台的任务数会变成目标命中数，助手最多扫描目标数的 5 倍。
7. 返回任务配置点击“启动 N 个内容定向任务”或“启动 N 个平台任务”。达到平台配额后自动切换；无法打开或 15 秒内未进入前台会跳过并继续。
8. “运行管理”集中无障碍、通知权限、下次自动执行、屏幕常亮和运行诊断；会话运行时首页和运行管理都提供停止入口。首次启动任务前会先请求通知权限，拒绝后通知栏的停止和评论入口不可用。
9. “统计与记录”显示累计完成批次、完成平台任务、完成视频任务、跳过数和最近 20 条批次记录。统计只保存在本机，清除统计不会清除配置和诊断。
10. 如需使用评论辅助，进入“互动配置”开启；视频前台时从通知栏点“暂停并读取”，再设置关键词和内容方向，生成 3 条候选。读取不到文字时可手动补充视频要点。
11. 选择候选后，手动回到目标应用并聚焦评论框，再从通知栏点“填入草稿”；助手不会点击发送，最终发布必须由用户完成。
12. 如需模型候选，请先部署 `gateway/`，在“互动配置”保存 HTTPS 根地址和后台档位，确认披露后粘贴短期设备令牌。令牌只保留在本次应用内存；不要填入模型供应商 API Key。
13. 模型网关只会在暂停后由用户点击生成时请求；恢复浏览、停止会话、关闭评论辅助或关闭网关都会取消进行中的请求，不会自动重试或发布。

如果用户手动离开当前任务平台，上滑会暂停并等待返回。自动切换第三方应用受 Android 版本和厂商后台启动策略影响，必须以真机结果为准。会话配置不会参与云备份或设备迁移。

## 当前验证状态

- 单元测试覆盖队列、配额、随机节奏、会话启停、会话状态机、命令通道、通知动作、平台定义与 Manifest 一致性、安全词表与网关一致性、内容定向、评论候选、网关协议与响应解析、统计存储；另有 Robolectric 用例覆盖页面返回、未开启服务时不能启动和通知栏停止。
- 网关 `npm test` 覆盖幂等隔离、限流与鉴权失败节流、错误映射与日志脱敏、安全词表、优雅退出等。
- 本轮在无 Android SDK 的沙箱中完成了 Java 编译与纯 Java 单测；Gradle 的 Lint、Robolectric 与 APK 构建结果以本机 `scripts/verify.sh` 的日志为准，并记录到 [CHANGELOG](CHANGELOG.md)。
- 真机安装、首屏视觉、edge-to-edge、通知权限流程、预测性返回、多应用自动切换、页面恢复、内容定向与评论辅助在各平台上的兼容性仍为 `VERIFY`，不能由本地构建替代。
