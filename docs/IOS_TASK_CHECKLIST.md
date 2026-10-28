# 视频浏览助手 iOS 任务清单

日期：2026-10-02  
基线工程：`VideoBrowseAssistant` Android 0.7.0  
状态：`DONE` 已有证据；`TODO` 待实现；`VERIFY` 需要设备、账号或人工验收；`HOLD` 受平台能力、政策或外部条件阻塞。

## 结论和交付边界

可以生成 iOS 原生版本，但不能把当前 Android 版的“跨第三方 App 自动读取页面、自动上滑、自动切换并填入评论框”直接等价搬到标准 iOS。

当前 Android 工程是纯 Java/Gradle 项目，核心依赖 `AccessibilityService`、`dispatchGesture`、前台包名判断、`startActivity`、通知接收器和 `WakeLock`；仓库没有 Swift、Xcode 工程或 iOS 目标。Android 当前的本地队列、配额、统计、内容定向规则、评论候选规则和 `comment-gateway.v1` 协议可以迁移，跨第三方 App 的 UI 控制必须改成用户触发或目标 App 合作的流程。

推荐交付“iOS 配套版”：任务配置、队列和统计、用户手动打开目标 App、分享/粘贴上下文、候选生成、复制或系统分享，以及 App Intents/快捷指令入口。若目标 App 是自有产品，可通过 URL Scheme/Universal Link、App Intents 或正式 API 增加受支持的第一方联动；否则保持人工确认。

## 能力映射

| Android 能力 | iOS 计划 | 结论 |
|---|---|---|
| 任务配置、平台顺序、每个平台 2–100 个配额 | SwiftUI 配置页和 `Codable` 域模型 | `TODO`，可复用业务规则 |
| 30 分钟会话、完成/跳过统计、最近记录 | `SessionStore` + 本地持久化 + 前台计时 | `TODO`，只能承诺本应用自己的会话 |
| 查询已安装平台、自动拉起第三方 App | 已知 URL Scheme/Universal Link + 用户确认 | `TODO/VERIFY`；不能按 Android 包名枚举安装应用 |
| `AccessibilityService` 读取第三方页面文字 | 分享扩展、粘贴、用户手动输入、目标 App API | `HOLD`，标准 iOS 无等价公共生产 API |
| `dispatchGesture` 自动上滑 | 用户在目标 App 内手动操作；自有 App 提供 API/Intent | `HOLD`，不能作为普通 iOS App 的跨 App 自动化 |
| 通知栏“停止/暂停/填入草稿” | 本地通知动作、App Intent、重新打开本应用 | `TODO`，不能提供 Android 式常驻跨 App 服务控件 |
| `WakeLock` 保持屏幕常亮 | 本应用前台使用 `isIdleTimerDisabled`，并提示用户 | `TODO/VERIFY`，不能保证后台或第三方 App 常亮 |
| 本地内容定向 | 对用户分享的文本、图片或视频做规则/模型判断 | `TODO`；不能抓取任意第三方页面 |
| 评论候选和模型网关 | Swift `URLSession` + Keychain，复用 `comment-gateway.v1` | `TODO`，服务端可复用 |
| 自动把草稿写入第三方评论框 | 复制到剪贴板、系统分享、用户粘贴和手动发送 | `HOLD`，不能宣称自动填入/自动发布 |
| UI 自动化测试 | XCUITest 仅验证本应用自身 UI | `TODO`，不能作为上架后的控制服务 |

## P0：先定产品路线和合规边界

- [ ] `IOS-P0-01` **确定交付路线**：默认采用“iOS 配套版”；如果必须 1:1 自动刷第三方平台，先把需求标记为 `HOLD`，不得先做假 UI 或 WebView 规避平台限制。
  - 依赖：无。
  - 验收：产品说明明确列出“可做/不可做”，并写入 App 内首次使用说明。
- [ ] `IOS-P0-02` **确认目标 App 合作条件**：逐个记录是否为自有 App、URL Scheme/Universal Link、App Intents、分享扩展或正式 API；没有合作接口的平台只保留手动打开和手动确认。
  - 依赖：`IOS-P0-01`。
  - 验收：形成平台矩阵，不能用“已安装/可打开”替代“可读取/可操作”。
- [ ] `IOS-P0-03` **完成 App Store 风险预审**：审查跨 App 自动化、第三方品牌名称、数据收集、模型网关、剪贴板和通知说明；未获得书面结论前不承诺上架。
  - 依赖：`IOS-P0-01`、`IOS-P0-02`。
  - 验收：保留审查记录、隐私字段清单和需要删除的高风险能力。
- [ ] `IOS-P0-04` **冻结最低系统和设备矩阵**：建议先选定一组受支持的 iOS/iPhone 版本，再确定 Swift/Xcode、最低部署版本、深色模式、动态字体、横竖屏和可访问性要求。
  - 依赖：`IOS-P0-03`。
  - 验收：`SUPPORTED_DEVICES.md` 或本清单记录型号、系统、测试责任人和结果。

## P1：建立原生 iOS 工程和共享契约

- [ ] `IOS-P1-01` 创建 `ios/VideoBrowseAssistant.xcodeproj`，使用 SwiftUI；iOS 作为 Android 的原生 peer，不使用网页壳替代原生能力。
  - 依赖：`IOS-P0-04`。
  - 验收：Debug 工程可在 Xcode 构建并启动，Bundle ID、版本号和显示名称已登记。
- [ ] `IOS-P1-02` 建立域层：`TargetApp`、`SessionTask`、`SessionQueue`、`SessionState`、`ContentTargetingConfig`、`CommentStrategy`、`TaskStats`；把 Android 的 2–100 配额、去重、顺序和 30 分钟上限写成平台无关规则。
  - 依赖：`IOS-P0-01`。
  - 验收：规则不依赖 SwiftUI、UIKit 或具体第三方 App；边界测试覆盖空队列、重复目标、越界配额和超时。
- [ ] `IOS-P1-03` 固化 `comment-gateway.v1` 的 Swift `Codable` 请求/响应模型，兼容网关的候选过滤、错误码、超时和取消语义。
  - 依赖：`gateway/src/protocol.mjs`、`gateway/README.md`。
  - 验收：Swift fixture 与现有 `gateway/test` 的请求/响应样例互通；不把供应商 API Key 放进客户端。
- [ ] `IOS-P1-04` 设计本地存储：配置和统计使用受保护的本地文件/SwiftData；设备令牌只放 Keychain，短时视频上下文、草稿和候选按 TTL 清除。
  - 依赖：`IOS-P1-02`、`IOS-P1-03`。
  - 验收：卸载/清除路径、日志、备份和 iCloud 同步检查均不会泄露令牌或原始页面文字。
- [ ] `IOS-P1-05` 建立安全网络层：仅 HTTPS、证书/ATS 配置、请求超时、取消、限流错误展示和脱敏日志；禁止客户端回退到明文或直接请求供应商。
  - 依赖：`IOS-P1-03`、`IOS-P1-04`。
  - 验收：网关不可用、令牌失效、超时、取消、429 和非法 JSON 都有可解释状态，不自动发布内容。

## P2：实现 iOS 配套版功能

- [ ] `IOS-P2-01` 实现 SwiftUI 首页，分为“任务配置、互动配置、运行管理、统计与记录”，保留 Android 已有的清晰单主操作和高级设置折叠。
  - 依赖：`IOS-P1-02`。
  - 验收：首次使用不自动打开第三方 App、不自动启动会话；VoiceOver、动态字体和深色模式可用。
- [ ] `IOS-P2-02` 实现任务配置和队列预览：用户选择已登记的平台、设置配额、调整顺序、查看不可用原因；不可用目标必须显示“需手动打开/未提供 iOS 接口”。
  - 依赖：`IOS-P1-02`、`IOS-P0-02`。
  - 验收：保存/恢复顺序、重复项、空选择、越界输入和目标状态均有单元测试。
- [ ] `IOS-P2-03` 实现“打开目标 App”流程：只调用已知 URL Scheme/Universal Link；打开前展示目标名称和用户确认，失败时回到本应用并记录诊断。
  - 依赖：`IOS-P0-02`、`IOS-P1-02`。
  - 验收：每个已登记链接在真实 iPhone 上逐项验证；不把 URL 能打开误报为页面可读取或可自动操作。
- [ ] `IOS-P2-04` 实现会话状态机和统计：`READY`、`RUNNING_OWN_APP`、`WAITING_USER`、`PAUSED`、`STOPPED`、`EXPIRED`；会话仅由用户按钮或已批准的 App Intent 启动，30 分钟后自动结束。
  - 依赖：`IOS-P1-02`、`IOS-P1-04`。
  - 验收：停止、后台、强杀、重启、系统时间变化和超时不会重复记账或恢复旧会话。
- [ ] `IOS-P2-05` 实现内容定向输入：支持用户手动输入和系统分享扩展接收的文本/媒体摘要；保留命中、排除、未知和扫描上限的解释。
  - 依赖：`IOS-P1-02`、`IOS-P1-04`。
  - 验收：上下文来源和有效期可见；没有用户分享或输入时不读取目标 App 内容。
- [ ] `IOS-P2-06` 实现评论辅助：用户暂停后输入/分享上下文，生成本地候选或通过网关生成候选；提供复制、系统分享和编辑，最终发送由用户完成。
  - 依赖：`IOS-P1-03`、`IOS-P1-05`、`IOS-P2-05`。
  - 验收：候选去重/安全过滤、取消、过期、脱敏和手动发送路径均可复现；UI 不出现“自动填写/自动发布”。
- [ ] `IOS-P2-07` 接入 App Intents/Shortcuts：只暴露本应用能独立完成且用户预期明确的动作，如打开任务配置、读取统计、生成草稿或打开指定目标链接。
  - 依赖：`IOS-P2-01` 至 `IOS-P2-06`。
  - 验收：快捷指令在未打开本应用和已打开本应用两种状态下都有明确结果；不能把 Intent 当作第三方 UI 自动化代理。
- [ ] `IOS-P2-08` 实现本地通知和诊断页：通知仅提醒等待用户、候选就绪、失败和会话结束；提供停止/打开本应用动作，不创建隐藏常驻服务。
  - 依赖：`IOS-P2-04`、`IOS-P2-06`。
  - 验收：拒绝通知权限时核心流程仍可运行；通知不包含原始视频文字、令牌或账号字段。

## P3：测试和真实设备验证

- [ ] `IOS-P3-01` 为域层编写 Swift Testing/XCTest 单元测试：队列顺序、配额 2–100、超时、统计幂等、内容命中/排除/未知、候选安全过滤、TTL 清理和错误映射。
  - 依赖：`IOS-P1-02`、`IOS-P1-04`、`IOS-P1-05`。
  - 验收：测试结果、版本、设备和提交哈希进入验证记录；不以“能编译”代替业务测试。
- [ ] `IOS-P3-02` 编写 XCUITest：首次启动、权限拒绝、任务配置、停止、超时、统计清除、网关错误和 VoiceOver 标识；测试只控制本应用。
  - 依赖：`IOS-P2-01` 至 `IOS-P2-08`。
  - 验收：模拟器和至少一台真实 iPhone 都通过；第三方 App 自动上滑不列为 XCUITest 通过项。
- [ ] `IOS-P3-03` 做真实 iPhone 矩阵：iOS 版本、深色模式、动态字体、弱网/断网、通知权限、剪贴板提示、分享扩展、Universal Link 和目标 App 版本。
  - 依赖：`IOS-P0-04`、`IOS-P2-03`、`IOS-P2-05`、`IOS-P2-08`。
  - 验收：每个目标平台给出 `PASS/VERIFY/HOLD` 和复现步骤；“App 能打开”与“业务链路完成”分开记录。
- [ ] `IOS-P3-04` 做网关端到端联调：HTTPS、设备令牌轮换/撤销、模型档位、超时、限流、取消、数据保留和成本上限。
  - 依赖：`IOS-P1-03`、`IOS-P1-05`、真实网关环境。
  - 验收：供应商 Key 只存在服务端；客户端抓包、日志和崩溃报告不出现令牌或原始上下文。

## P4：签名、分发和发布门禁

- [ ] `IOS-P4-01` 建立 Apple Developer 团队、Bundle ID、Capabilities、签名证书、Provisioning Profile 和 App Store Connect 记录；密钥只登记保管人、指纹和有效期。
- [ ] `IOS-P4-02` 生成 Archive，完成 TestFlight 内测；记录版本、Build、签名、导出选项、崩溃符号和 SHA-256。
- [ ] `IOS-P4-03` 完成隐私清单、数据收集声明、第三方平台名称/商标审查、模型供应商说明、剪贴板/通知说明和 App Review 演示账号或视频。
- [ ] `IOS-P4-04` 只有在 `P0–P3` 通过、真实设备矩阵闭环、政策审查完成后，才评估 App Store 提交；任何“跨第三方自动滑动/读取/填入”仍保持 `HOLD`，不写入发布承诺。

## 当前证据和待验证项

- [x] `DONE` Android 基线已存在：0.7.0、Java/Gradle、`AccessibilityService`、多平台队列、内容定向、评论候选和可选 HTTPS 网关。
- [x] `DONE` 当前仓库没有 `.swift`、`.xcodeproj`、`.xcworkspace` 或 `Package.swift`，所以尚未生成 iOS 工程。
- [x] `DONE` `gateway/npm test` 当前通过 6/6；网关可以作为 iOS 客户端的共享服务契约。
- [ ] `VERIFY` Android 单元测试未能在本环境重跑：当前 shell 找不到 Java Runtime；README 中记录的 30/30 需要在配置 JDK 17 的环境重验。
- [x] `DONE` 当前主机可见 Xcode 26.6、Swift 6.3.3。
- [ ] `VERIFY` `xcrun simctl` 当前无法连接 CoreSimulatorService；模拟器和真实 iPhone 验收尚未完成。
- [ ] `HOLD` “iOS 完全复刻 Android 跨第三方 App 自动化”没有标准公共 API 交付路径；除非目标 App 提供正式合作接口，否则不能作为普通 iOS 上架功能承诺。

## 推荐执行顺序

`IOS-P0-01 → IOS-P0-02 → IOS-P0-03 → IOS-P0-04 → IOS-P1-01~05 → IOS-P2-01~08 → IOS-P3-01~04 → IOS-P4-01~04`

第一阶段应先交付“可编译、可配置、可手动联动、可生成候选”的 iOS 配套版；跨 App 自动化只有在目标 App 合作接口和政策审查同时通过后，才单独增加能力，不与配套版混在同一个完成标准中。

## 平台依据

- [UIApplication.open(_:options:completionHandler:)](https://developer.apple.com/documentation/uikit/uiapplication/open(_:options:completionhandler:))：iOS 可通过 URL Scheme/Universal Link 打开其他 App，但这不等于读取或控制其界面。
- [App Intents](https://developer.apple.com/documentation/appintents)：将本应用自己提供的动作接入 Siri、Spotlight、Shortcuts 和系统入口。
- [Performing long-running tasks on iOS and iPadOS](https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados)：后台长任务仍需由用户操作触发，并受系统调度约束。
- [XCUIApplication](https://developer.apple.com/documentation/xcuiautomation/xcuiapplication) / [XCUIAutomation](https://developer.apple.com/documentation/xcuiautomation)：用于 Xcode UI 测试，不能作为发布给用户的跨 App 自动化服务。
- [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)：发布前必须按后台服务、隐私、第三方服务和功能用途完成审核。
