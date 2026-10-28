# CommentModelGateway

这是 Android 单一版本可调用的服务端模型网关。它只为“用户暂停视频后，手动点击生成候选”提供 1–3 条评论草稿；不提供自动发送、点赞、收藏或平台操作。

## 安全边界

- Android APK 不保存供应商 API Key；供应商地址和密钥只从服务端环境变量读取。
- 客户端仅可传递最多 360 个字符的可访问文字、关键词和内容方向；服务不记录原始视频文字、候选正文、令牌或密钥。
- 页面文字被明确标记为不可信数据，不能改变模型规则；网关按共享安全词表拒绝危险上下文，并对关键词和返回候选再次做安全和重复过滤。
- Android 只接受 HTTPS 网关根地址，统一版本关闭明文流量；模型请求仍必须由用户暂停视频并手动点击生成触发。
- 管理页只编辑模型 ID、温度和输出上限，没有 API Key 输入框。配置文件权限为仅服务账户可读写。

## 本地准备

运行时只需要 Node.js 18+，没有任何第三方运行时依赖。

```bash
cd gateway
npm test          # node --test，无需 node_modules
npm install       # 仅用于开发期 lint（eslint 9 flat config）
npm run lint
```

## 环境变量

部署时由密钥管理系统、进程管理器或受控环境变量注入（不要把实际值写入仓库、镜像或 APK）：

| 变量 | 说明 |
| --- | --- |
| `MODEL_BASE_URL` / `MODEL_API_KEY` | 供应商 HTTPS 地址与密钥，仅服务端持有 |
| `MODEL_GATEWAY_CLIENT_TOKEN` | 默认设备令牌（≥16 字符），见“设备鉴权” |
| `GATEWAY_ADMIN_TOKEN` | 后台令牌（≥16 字符）；未配置时后台接口返回 503 |
| `GATEWAY_BIND_HOST` / `GATEWAY_PORT` | 默认 `127.0.0.1:8787` |
| `GATEWAY_PROFILE_FILE` | 模型档位文件，默认 `config/model-profiles.json` |
| `GATEWAY_SAFETY_TERMS_FILE` | 安全词表，默认 `config/safety-terms.json` |
| `GATEWAY_REQUESTS_PER_MINUTE` / `GATEWAY_REQUESTS_PER_DAY` | 每个设备主体的限额，默认 20 / 500 |
| `GATEWAY_TRUST_PROXY` | 设为 `1` 时按 `X-Forwarded-For` 第一跳识别客户端地址 |

```bash
export MODEL_BASE_URL='https://provider.example/v1'
export MODEL_API_KEY='server-side-provider-key'
export MODEL_GATEWAY_CLIENT_TOKEN='device-token-for-controlled-test'
export GATEWAY_ADMIN_TOKEN='admin-token-for-controlled-test'
npm start
```

默认只监听回环地址。生产环境应在 TLS 反向代理之后部署，不要直接把端口暴露为明文 HTTP。只有在网关**只能**经由反向代理访问时才设置 `GATEWAY_TRUST_PROXY=1`，并让代理**覆盖**（而不是追加）该头，例如 nginx：`proxy_set_header X-Forwarded-For $remote_addr;`，否则第一跳可被客户端伪造。

## 设备鉴权（TokenVerifier）

`src/token-verifier.mjs` 定义可插拔接口：`verify(authorizationHeader) -> Promise<{ subject, expiresAt } | null>`，可选 `isConfigured()`（返回 false 时网关回 503）。

- `subject` 是稳定、非机密的设备/用户标识，用于限流与幂等缓存的键，不能是原始令牌。
- `expiresAt` 为毫秒时间戳或 `null`；已过期视为 401。`verify` 抛异常视为验证服务不可用（500），不计入鉴权失败。
- 默认 `EnvTokenVerifier` 比较 `MODEL_GATEWAY_CLIENT_TOKEN`（摘要后常量时间比较，最短 16 字符），`subject = "env:" + sha256(token) 前 12 位十六进制`。

正式接入时，应由既有身份服务签发、轮换和撤销短期设备令牌，实现上述接口后传入 `new CommentGatewayService({ tokenVerifier })`；不要把长期令牌硬编码进 APK。

## 限流、鉴权失败节流与幂等

- **请求限额**：按 `subject` 计数，自然分钟和 UTC 自然日两个窗口；超出返回 429 `RATE_LIMITED` 并带 `Retry-After`。被分钟限额拒绝的请求不消耗日额度。
- **鉴权失败节流**：按客户端地址计数。设备接口 5 分钟内最多 10 次失败，后台接口 15 分钟内最多 5 次；超出后即使令牌正确也返回 429 `AUTH_THROTTLED`（带 `Retry-After`），直到窗口结束。窗口从第一次失败开始计时。**成功鉴权不会重置计数**，避免持有一个有效凭据的人交替成功来继续猜测；计数只随窗口过期。
- **幂等**：缓存键为 `<subject>:<request_id>`（subject 经 URL 编码），不同设备使用相同 `request_id` 互不可见；有效期 10 分钟，内存实现最多 1000 条，满时淘汰最旧条目。只缓存成功响应。

## 状态存储（StateStore）

限流窗口、日计数、幂等缓存和鉴权失败计数都经由 `src/state-store.mjs` 的接口访问（全部为 async）：

- `incrementWindow(key, windowMs, now) -> { count, resetAt }`：固定窗口计数，首次递增时开窗。
- `getWindow(key, now) -> { count, resetAt } | null`：只读。
- `getCached(key, now)` / `setCached(key, value, ttlMs, now)`：短期缓存，值必须可 JSON 序列化。

默认 `MemoryStateStore` **仅适用于单实例**：重启即丢失，多实例之间不共享（多实例时每个实例各自限额，幂等也只在同一实例内生效）。内存条目有上限（窗口 10000 条、缓存 1000 条），满时先清理过期再淘汰最旧。

多实例部署时实现同一接口接入共享存储并传入 `new CommentGatewayService({ stateStore })`。以 Redis 为例：`incrementWindow` 用 `MULTI`/Lua 执行 `INCR key`、`PEXPIRE key windowMs NX`、`PTTL key`；`getWindow` 用 `GET` + `PTTL`；`setCached` 用 `SET key <json> PX ttlMs`；`getCached` 用 `GET` 后 `JSON.parse`。本仓库不内置 Redis 实现。

## 安全词表

`config/safety-terms.json` 是网关与 Android 共用的唯一词表（`version`、`block`、`review`、`allow_phrases`），进程启动时读取一次，路径可由 `GATEWAY_SAFETY_TERMS_FILE` 覆盖；文件缺失或格式错误时启动失败。判定规则（Android 必须逐步一致）：

1. 文本转小写（与 Locale.ROOT 一致）；
2. 按文件顺序删除每个 `allow_phrases`（同样小写）的所有出现；
3. 包含任一 `block` 词 → `block`；否则包含任一 `review` 词 → `review`；否则 `ok`。

应用方式：视频上下文 `block` → 422 `UNSAFE_CONTEXT`；`review` → 正常生成，响应带 `context_review: true`，每条候选 `safety_labels` 为 `["context_review"]`。关键词或候选被判为 `block`/`review` 时直接丢弃。例如“反诈骗”不会被拦截。当前词表版本在 `GET /health` 和后台状态的 `safety_terms_version` 中公开。

## 错误与日志

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_JSON` | 请求体不是有效 JSON |
| 413 | `PAYLOAD_TOO_LARGE` | 请求体超过 32 KiB |
| 401 / 429 | `UNAUTHORIZED` / `AUTH_THROTTLED` | 令牌无效 / 鉴权失败过多 |
| 429 | `RATE_LIMITED` / `PROVIDER_REJECTED` | 网关限额 / 供应商限流 |
| 502 / 504 | `PROVIDER_REJECTED` 等 / `PROVIDER_UNAVAILABLE` | 供应商拒绝或返回异常 / 抛错或 12 秒超时 |
| 500 | `INTERNAL_ERROR` | 其他异常（如 `model-profiles.json` 损坏） |

500 时向 stderr 写一行 JSON，仅包含 `timestamp`、`method`、`path`（不含查询串）、`request_id`、`error_class`、`error_code`；不记录错误消息、视频文字、候选、令牌、授权头或供应商密钥。测试可通过 `createGatewayHandler(service, { logger })` 注入 `{ error(entry) }` 捕获。

## 后台配置

打开受保护的 `https://你的网关/admin`，粘贴 `GATEWAY_ADMIN_TOKEN` 后即可查看状态、选择默认档位并编辑三个档位：`standard`、`high_relevance`、`economy`。页面不再内置默认模型；默认值由 `GET /admin/v1/status` 的 `default_profiles` 提供（唯一来源是 `src/profile-store.mjs`），“填入默认值”按钮使用该数据。

管理端接口：

- `GET /admin/v1/status`
- `GET /admin/v1/model-profiles`
- `PUT /admin/v1/model-profiles`

模型档位持久化到 `GATEWAY_PROFILE_FILE`；该文件被 `.gitignore` 排除。模型供应商地址、模型 API Key、后台令牌和设备令牌都不能经由管理接口读出或写入。

## Android 接口

`POST /v1/comment-candidates` 需要 `Authorization: Bearer <device token>` 和可选的 `X-Model-Profile`。请求协议固定为 `comment-gateway.v1`，仅允许 `manual_comment_assist`。

请求体中的 `request_id` 是唯一请求标识：网关忽略任何 `X-Request-Id` 请求头；只要请求体可解析且 `request_id` 有效（6–64 字符，且为可打印 ASCII），成功和错误响应都会带 `x-request-id` 响应头回显规范化后的值。成功响应：

```json
{
  "request_id": "...",
  "context_review": false,
  "provider_profile": "high_relevance",
  "candidates": [
    {
      "text": "...",
      "evidence_label": "学习计划",
      "direction_match": "ADD_VALUE",
      "relevance_confidence": 0,
      "safety_labels": []
    }
  ]
}
```

Android 仍由用户选择草稿、主动填入已聚焦的输入框，并亲自点击发送。网关故障、令牌错误、限流或超时不会自动重试、自动切换本地结果或自动发布。

## 健康检查与部署

`GET /health` 无需鉴权，返回 `status`、`protocol_version`、`provider_configured`、`active_profile`、`safety_terms_version`；档位文件损坏时返回 500，可直接作为存活/就绪探针。

收到 `SIGTERM`/`SIGINT` 后，网关停止接受新连接，等待进行中的请求最多 10 秒（期间响应带 `connection: close`），全部结束后以 0 退出，超时以 1 退出。

**Docker**（`node:22-alpine`，非 root 的 `node` 用户，镜像内不含任何密钥；`config/model-profiles.json` 不会被打入镜像）：

```bash
docker build -t comment-gateway gateway/
docker run -d --name comment-gateway --env-file /etc/comment-gateway/env \
  -v comment-gateway-data:/app/data -p 127.0.0.1:8787:8787 comment-gateway
```

容器内 `GATEWAY_BIND_HOST=0.0.0.0`，`GATEWAY_PROFILE_FILE=/app/data/model-profiles.json`（挂卷持久化）；`HEALTHCHECK` 用 node 请求 `/health`。宿主机端口仍只绑定回环，由 TLS 反向代理对外。

**systemd**：参考 `deploy/comment-gateway.service`。代码放在 `/opt/comment-gateway`，环境变量写入 `/etc/comment-gateway/env`（root 所有、权限 600），以专用 `comment-gateway` 用户运行；启用了 `NoNewPrivileges`、`ProtectSystem=strict` 等加固，仅 `config` 目录可写（用于档位文件的原子写入）。
