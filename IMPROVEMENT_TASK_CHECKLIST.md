# 视频浏览助手改进任务清单

审查日期：2026-10-08　执行日期：2026-10-08
基线：Android 0.7.0-debug → 0.8.0，`gateway/` 0.1.0
状态：`DONE` 已完成，`VERIFY` 需真机或本机构建确认，`HOLD` 需外部条件或审批，`SKIP` 本轮按决定不做。
优先级：`P0` 正确性/安全；`P1` 可维护性与质量门禁；`P2` 体验、兼容与工程卫生。

执行说明：本轮修改在无 Android SDK 的沙箱中完成，已通过 Java 编译（Android API 桩）、65 个纯 Java 单测与网关 37 个测试。Gradle 的 Lint、Checkstyle、Robolectric 与 APK 构建需在本机执行 `bash scripts/verify.sh` 确认，结果写入 [CHANGELOG](CHANGELOG.md)。改动前的完整源码备份在 `_backup/pre-improvement-2026-10-08.tar.gz`。

## P0：正确性与安全

### Android

- [x] `DONE` **适配 Android 15+ 强制 edge-to-edge。** `MainActivity.applyEdgeToEdge()` 在 API 30+ 关闭 `decorFitsSystemWindows`，按系统栏、刘海与输入法插入内边距；主题删除失效的系统栏颜色，改用 `windowBackground`。
- [ ] `VERIFY` 在 Android 15/16 真机确认首屏、弹窗、键盘弹出与底部按钮不被系统栏覆盖（见 [targetSdk 36 审查](docs/TARGET_SDK_36_REVIEW.md)）。
- [x] `DONE` **通知权限请求时机。** 启动前先请求 `POST_NOTIFICATIONS`，结果返回后再拉起目标应用（拒绝也继续启动且不重复弹窗）；首页会话卡片与运行管理显示未开启提示和“开启通知”入口，永久拒绝时跳转系统通知设置。
- [x] `DONE` **统一会话生命周期。** 新增 `SessionStore.start/stop` 作为唯一写入点，替换原 5 处拷贝；`SessionKeys` 集中跨组件键；`TaskStatsStore` 改用常量；停止时同时清理计数键。由 `SessionStoreTest` 覆盖。
- [x] `DONE` **30 分钟确定性定时器。** 服务在会话开始/恢复时按剩余时长投递 `expiryRunnable`，所有清理路径取消；屏幕常亮锁超时同步为剩余时长。

### Gateway

- [x] `DONE` **幂等缓存按设备主体隔离**，键为 `idem:<subject>:<request_id>`，上限 1000 条、10 分钟过期，只缓存成功结果。
- [x] `DONE` **鉴权失败限流**：设备接口每地址 5 分钟 10 次、管理接口 15 分钟 5 次，超限返回 429 `AUTH_THROTTLED` 与 `Retry-After`；成功鉴权不重置计数。`X-Forwarded-For` 仅在 `GATEWAY_TRUST_PROXY=1` 时采用。
- [x] `DONE` **错误映射与日志**：400 `INVALID_JSON` / 413 / 500 `INTERNAL_ERROR` 分开；500 输出一行仅含时间、方法、路径、request_id、错误类名和错误码的 JSON 日志，logger 可注入。
- [x] `DONE` 回归测试：跨主体幂等、缓存上限、分钟/日限流与窗口滚动、鉴权节流、配置损坏 500 与日志、供应商超时/429、管理端 401/503 等（网关测试 6 → 37）。

## P1：架构与可维护性

- [x] `DONE` **拆分 `MainActivity`**（2357 → 约 600 行）：`HomeCards`、`StatusCards`、`TaskPage`、`InteractionPage`、`GatewayCard`、`RuntimePage`、`StatsPage`，通过 `PageHost` 接口与 `UiKit` 视图工厂协作；主按钮决策抽为纯 Java 的 `PrimaryAction`。
- [x] `DONE` **拆分 `AutoSwipeAccessibilityService`**：`SessionRunner`（纯 Java 状态机，`SessionRunnerTest` 覆盖配额、跳过、扫描/连续未命中上限、重复页、持久化往返）、`ContentScanner`、`CommentWorkflow`、`SessionNotifications`。
- [x] `DONE` **平台定义单一来源**：`Platform` 枚举；`PlatformTest` 校验与 Manifest `<queries>` 一致。
- [x] `DONE` **显式命令通道**：`SessionCommands` 替代偏好监听；通知动作由 `SessionCommandReceiver.handle()` 先持久化再派发命令，服务未连接时在下一次事件按持久化状态补齐。
- [x] `DONE` **请求 JSON 改用 `org.json`**，删除手写转义；单测加入 `org.json` 测试依赖。
- [x] `DONE` **文案资源化与国际化**：界面、对话框、Toast、通知与会话摘要共 291 条进入 `strings.xml`，新增 `values-en`。诊断文案与 `CommentAssistant` 本地中文模板、`ModelGatewayClient` 状态语句仍为中文（前者面向本机排障，后者生成中文评论）。
- [x] `DONE` **共享安全词表**：`gateway/config/safety-terms.json`（block / review / allow_phrases + 版本号）为真源，Android `SafetyTerms` 镜像并由 `SafetyTermsTest` 校验一致；`/health` 暴露 `safety_terms_version`；“反诈骗”“暴力美学”等不再误拦。
- [x] `DONE` **集中偏好键常量**（随 `SessionKeys`）。

## P1：测试与质量门禁

- [x] `DONE` **补齐零覆盖区域**：新增 `SessionStoreTest`、`SessionRunnerTest`、`SessionCommandsTest`、`SessionCommandReceiverTest`、`PlatformTest`、`SafetyTermsTest`、`PrimaryActionTest`、`ModelGatewayClientTest`，并扩展内容定向、协议与统计测试（30 → 65 个纯 Java 用例）。
- [x] `DONE` **引入 Robolectric**（4.14.1，`@Config(sdk = 34)`）：`MainActivityRobolectricTest` 覆盖子页面返回、未开启服务不能启动、通知栏停止清理会话。
- [ ] `VERIFY` 在本机 Gradle 中实际运行 Robolectric 用例（需联网下载 Robolectric 运行时）。
- [x] `DONE` **补齐网关测试**（见 P0）。
- [x] `DONE` **建立 CI**：`.github/workflows/ci.yml`（Android：测试、Lint、Checkstyle、debug/release 构建并上传 APK 与校验值；网关：测试与 ESLint）。另提供本机 `scripts/verify.sh`。
- [x] `DONE` **静态检查**：Checkstyle（`config/checkstyle/checkstyle.xml`，`./gradlew checkstyle`）；网关 ESLint 平面配置与 `npm run lint`。

## P1：安全与隐私

- [x] `DONE` **可插拔令牌校验**：`TokenVerifier` 接口，默认 `EnvTokenVerifier`；限流与幂等按主体隔离。
- [ ] `HOLD` 接入正式身份服务签发短期设备令牌；与 [智能互动清单](docs/INTERACTION_TASK_CHECKLIST.md) 的身份签发项合并验收。
- [x] `DONE` **内存态状态的约束**：`StateStore` 接口与 `MemoryStateStore`，README 说明默认仅单实例及接入 Redis 等共享后端的方法。
- [x] `DONE` **令牌输入框**：禁止自动填充、不保存视图状态；界面重建后提示“令牌已按设计清除”。
- [x] `DONE` **诊断脱敏**：内容定向只记录 `MATCH（想看#2）` 这类序号；复制诊断前弹窗说明包含的字段。
- [x] `DONE` **收窄无障碍监听**（调整后的做法）：会话空闲时只监听 `TYPE_WINDOW_STATE_CHANGED`，运行时才加入窗口集合与内容变化事件；上滑前确认活动窗口属于目标应用。未采用 `packageNames` 过滤，因为离开目标应用要靠其他包（桌面等）的窗口事件识别，按包过滤会让服务在用户离开后继续上滑。
- [ ] `VERIFY` 真机确认自动切换、离开前台判断与评论暂停流程不受事件收窄影响。

## P2：界面、体验与兼容性

- [x] `DONE` **深色模式**：颜色全部来自 `colors.xml`，新增 `values-night` 颜色与主题。
- [x] `DONE` **自适应图标与单色通知图标**：`mipmap-anydpi/ic_launcher`（前景、背景、主题单色层），通知改用 `ic_stat_session`。
- [x] `DONE` **通知 `contentIntent`**：点击通知打开助手。
- [x] `DONE` **配置变化保存状态**：保存当前页面、候选与手动要点。
- [x] `DONE` **预测性返回**：`enableOnBackInvokedCallback` + `OnBackInvokedCallback`，旧版本保留 `onBackPressed()`。
- [x] `DONE` **无障碍服务状态检测**改用 `AccessibilityManager.getEnabledAccessibilityServiceList()`。
- [x] `DONE` **上滑坐标**按目标窗口根节点边界计算，异常尺寸时回退到显示尺寸。
- [x] `DONE` **文字采集策略**：`ContentScanner` 优先节点正文、上限可配置（默认 220 节点 / 16 段），诊断记录扫描节点数与文本段数。
- [x] `DONE` **内容定向匹配**：英文词按词边界匹配，忽略 @提及；指纹改为 SHA-256 前 16 位十六进制。
- [x] `DONE` **本应用可达性**：状态点不再被朗读，状态文字与徽章设为礼貌型实时区域，标题标为无障碍标题，下拉框与候选按钮补充描述。
- [ ] `VERIFY` 使用 TalkBack 与 Accessibility Scanner 检查首页与各子页面。
- [ ] `VERIFY` `SCREEN_BRIGHT_WAKE_LOCK` 在各厂商设备上的实际效果（运行管理页已注明可能无效）。

## P2：工程与仓库卫生

- [ ] `SKIP` **初始化版本控制**：本轮按用户决定不使用 git，改为改动前完整备份到 `_backup/`。建议后续在本机执行 `git init` 并以当前状态为首个提交（`.gitignore` 已就绪）。
- [x] `DONE` **补全 `.gitignore`**：构建产物、`.gradle`、APK/AAB、`APK_SHA256.txt`、`local.properties`、签名文件、`.DS_Store`、`_backup/`、`build-logs/`、网关 `node_modules`。
- [x] `DONE` **构建脚本现代化**：`plugins {}` + `gradle/libs.versions.toml`，删除 javapoet 强制版本。
- [ ] `VERIFY` 首次在线构建成功后，`--offline` 构建可再次使用（依赖缓存会变化）。
- [x] `DONE` **文档结构整理**：新增 [CHANGELOG](CHANGELOG.md) 作为版本与校验值唯一来源；专项清单、任务方案与审计报告移入 `docs/`；README 精简为当前版本、结构、构建与使用。
- [x] `DONE` **release 构建加固**：开启 R8 与 `shrinkResources`，补充 `proguard-rules.pro`；`versionCode` 支持 `-PappVersionCode`（CI 使用运行编号）。
- [ ] `VERIFY` `assembleRelease` 在 R8 下通过并在真机安装运行。
- [x] `DONE` **网关部署资产**：`Dockerfile`（非 root、健康检查）、`.dockerignore`、`deploy/comment-gateway.service`；`SIGTERM/SIGINT` 优雅退出（最多等待 10 秒）；默认档位由 `/admin/v1/status` 下发，管理页不再硬编码模型名。
- [x] `DONE` **request id 统一**：仅使用 body `request_id`，网关在响应头回显 `x-request-id`；Android 不再发送 `X-Request-Id`。

## 与发布门禁的衔接

- [x] `DONE` 预接 release 签名：`keystore.properties`（参考 `keystore.properties.example`）或 CI 的 `RELEASE_*` 环境变量，均不入库。
- [x] `DONE` targetSdk 36 行为变更审查：[docs/TARGET_SDK_36_REVIEW.md](docs/TARGET_SDK_36_REVIEW.md)。
- [ ] `HOLD` Google Play 无障碍 API 使用声明与国内商店政策审查，结论决定 `isAccessibilityTool` 取值与上架范围。

## 后续建议

1. 在本机执行 `bash scripts/verify.sh`，把 APK 大小与 SHA-256 写入 CHANGELOG 的 0.8.0 条目。
2. 用一台 Android 13+ 与一台 Android 15/16 设备完成本清单和 [targetSdk 36 审查](docs/TARGET_SDK_36_REVIEW.md) 中的 `VERIFY` 项。
3. 推送到远程仓库后启用 CI，并在主分支开启必需检查。
