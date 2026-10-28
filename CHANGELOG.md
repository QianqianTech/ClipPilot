# 更新日志

版本号、构建产物大小与 SHA-256 只在本文件维护；README 和任务清单引用这里，不再重复记录。

## 0.8.0（2026-10-08，待本机构建确认）

依据 [改进任务清单](IMPROVEMENT_TASK_CHECKLIST.md) 完成的工程化版本，功能边界不变。

正确性与安全
- 会话启停统一由 `SessionStore` 写入，5 处重复的停止逻辑合并；`SessionKeys` 集中偏好键。
- 30 分钟上限改为确定性定时器，不再依赖无障碍事件到达。
- 启动会话前先请求通知权限；首页、运行管理显示通知未开启提示与入口。
- 适配 Android 15+ edge-to-edge 与预测性返回。
- 网关：幂等缓存按设备主体隔离并限 1000 条；鉴权失败按地址限流；500 错误与日志区分；`TokenVerifier`、`StateStore` 可插拔；共享安全词表 `gateway/config/safety-terms.json`；`x-request-id` 回显；Docker/systemd 部署资产与优雅退出。

架构
- `AutoSwipeAccessibilityService` 拆为 `SessionRunner`（纯状态机）、`ContentScanner`、`CommentWorkflow`、`SessionNotifications`；通知动作与界面通过 `SessionCommands` 直接通知服务，不再把 SharedPreferences 当消息总线。
- `MainActivity` 拆为 `HomeCards`、`StatusCards`、`TaskPage`、`InteractionPage`、`GatewayCard`、`RuntimePage`、`StatsPage`；平台定义收敛到 `Platform` 枚举。
- 网关请求体改用 `org.json` 构建。

体验与隐私
- 界面、通知、对话框与提示文案全部资源化，新增英文（`values-en`）。
- 深色模式、自适应图标（含主题单色层）、单色通知图标、点击通知打开应用、旋转后保留页面与候选。
- 诊断只记录“想看#序号”而非定向词本身；复制诊断前说明包含的字段；令牌输入框禁止自动填充。
- 内容定向：英文词按词边界匹配，忽略 @提及；页面指纹改用 SHA-256；文字采集优先正文并记录扫描量。
- 无障碍服务空闲时只监听窗口切换事件；上滑前确认活动窗口属于目标应用，并按目标窗口边界计算手势。

工程
- Gradle `plugins {}` + 版本目录，移除 javapoet 强制版本；Checkstyle；Robolectric；release 开启 R8 与资源压缩；可选签名配置；CI 工作流；`scripts/verify.sh` 一键本机验证。

| 产物 | 大小 | SHA-256 |
|---|---|---|
| `app-debug.apk`（0.8.0-debug） | 待 `scripts/verify.sh` 输出 | 待 `scripts/verify.sh` 输出 |

## 0.7.0（2026-09-30）

首页按任务配置、互动配置、运行管理、统计与记录分组；本机累计统计与最近 20 条批次记录；新增 TikTok、YouTube Shorts、Instagram Reels、Kwai、X、REDnote。

| 产物 | 大小 | SHA-256 |
|---|---|---|
| `VideoBrowseAssistant.apk`（统计改造后） | 172,606 bytes | `8e3397331895ca5dd3f5dfc81046520ac6f1833615e57a64da8045d28db9aeba` |
| `VideoBrowseAssistant.apk`（海外平台接入） | 156,837 bytes | `ad7ff53ec9d6555f8ad7b8a90f75b6a9070811bc2c77d768d760843a50271031` |

## 0.6.2（2026-09-29）

统一单一构建版本；每平台上限 100；10–30 秒随机上滑；模型网关配置与服务端档位管理。

`VideoBrowseAssistant.apk`：85,937 bytes，`8ffcae249a34698b75c6fd692e649122baf76b0a6dbfb2429594d79a24d40253`。

## 0.5.0（2026-09-28）

本地内容定向 MVP。`0.5.0-debug`：73,833 bytes，`d09b8aaa97e169adeeb1570bb916927350465772797dc87afb8d5c4269e75f67`。

## 0.4.0（2026-09-28）

用户触发的评论辅助（暂停并读取、本地候选、填入草稿）。`0.4.0-debug`：63,337 bytes，`840bee2bb1563a48c5ef00ddd2be480d1eaf65ed55d55e3f640264330792d182`。

## 0.3.0（2026-09-28）

多平台队列与自动切换。`0.3.0-debug`：47,041 bytes，`f506bfba359c95ff3b04b17ecce210a8373078a0484f001c11ac5b20026eac7d`。
