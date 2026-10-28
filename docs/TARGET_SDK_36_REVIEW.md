# targetSdk 36 行为变更审查

日期：2026-10-08　适用版本：0.8.0（`compileSdk 36` / `targetSdk 36` / `minSdk 26`）

本文件记录把 `targetSdk` 提升到 35/36 后对本应用有影响的平台行为，以及当前的处理方式。状态：`DONE` 已在代码中处理，`VERIFY` 需真机确认，`HOLD` 等待外部决策。

| 变更 | 影响 | 处理 | 状态 |
|---|---|---|---|
| Android 15（API 35）起 target 35+ 强制 edge-to-edge；Android 16（API 36）移除退出选项 | 内容绘制到状态栏/导航栏下方，`statusBarColor`、`navigationBarColor` 失效 | `MainActivity.applyEdgeToEdge()` 在 API 30+ 调用 `setDecorFitsSystemWindows(false)`，按 `systemBars + displayCutout + ime` 插入内边距；主题移除已失效的系统栏颜色，改用 `windowBackground` | `DONE` / `VERIFY` 真机 |
| 预测性返回（API 33+，target 36 默认启用动画） | `onBackPressed()` 在启用 `enableOnBackInvokedCallback` 后不再被调用 | Manifest 声明 `enableOnBackInvokedCallback="true"`；API 33+ 仅在子页面注册 `OnBackInvokedCallback`，首页交给系统；API 32 及以下保留 `onBackPressed()` | `DONE` / `VERIFY` |
| `POST_NOTIFICATIONS` 运行时权限（API 33+） | 未授权时通知栏“停止/暂停并读取/填入草稿”入口全部不可用 | 启动会话前先请求权限，结果返回后再拉起目标应用；首页和运行管理显示未开启提示与入口；永久拒绝时跳转系统通知设置 | `DONE` / `VERIFY` |
| `PendingIntent` 必须声明可变性（API 31+） | 未声明会崩溃 | 所有 `PendingIntent` 使用 `FLAG_IMMUTABLE`，新增通知 `contentIntent` 同样不可变 | `DONE` |
| 后台启动 Activity 限制（API 29+，API 34/35 进一步收紧 `PendingIntent` 发送方豁免） | 自动切换下一个平台依赖 `startActivity(FLAG_ACTIVITY_NEW_TASK)` | 调用方是已启用的无障碍服务，属于系统豁免；15 秒内未进入前台会记录诊断并跳过该平台。不同厂商是否额外拦截需真机记录 | `VERIFY` |
| 侧载应用的受限设置（API 33+） | 侧载 APK 的无障碍开关可能被置灰 | 服务卡片提供“打开应用详情”和“允许受限设置”指引，并自检服务声明 | `DONE` / `VERIFY` |
| `AccessibilityNodeInfo.recycle()` 弃用（API 33） | 无功能影响 | 统一经 `ContentScanner.recycle()` 调用，旧版本仍回收 | `DONE` |
| `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` 弃用 | 部分厂商忽略 | 运行管理页注明可能无效；锁带超时并在所有停止路径释放 | `VERIFY` |
| 16 KB 内存页大小（Android 15+ 设备） | 仅影响含原生库的 APK | 本应用无 `.so`，无需处理 | `DONE` |
| 无障碍 API 使用声明 / `isAccessibilityTool` | Google Play 要求说明无障碍 API 用途；`isAccessibilityTool=true` 只适用于真正的辅助工具 | 未声明 `isAccessibilityTool`，等待平台政策审查结论 | `HOLD` |
| 安全默认值：`allowBackup`、明文流量 | 数据迁移与明文请求 | 维持 `allowBackup=false`、`dataExtractionRules` 排除全部域、`usesCleartextTraffic=false` | `DONE` |

## 真机验收要点

1. Android 15/16 设备：首屏标题、“测试版”徽章、底部按钮、平台选择弹窗与键盘弹出时的输入框均不被系统栏遮挡。
2. Android 13+：首次点击启动时先出现通知授权弹窗，授权或拒绝后才打开第一个平台；拒绝后首页出现“通知未开启”提示。
3. Android 14+：子页面使用手势返回显示预测性返回动画并回到概览，概览页返回退出应用。
4. 多平台自动切换：记录厂商、系统版本，确认第二个平台能被拉起或在 15 秒后被跳过。
