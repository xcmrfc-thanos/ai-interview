# Java 侧模型管理（LLM 设置）设计

> 日期：2026-09-07
> 分支：interview-server（worktree `ai-interview-java`）
> 状态：已获用户批准（方案 A：共享配置提供器 + 动态生效）

## 背景与目标

Python 主仓已实现「模型配置」功能：前端「设置 → 模型配置」页通过 `PUT /api/settings/llm`
将提供商/模型/API Key 加密存入 SQLite `llm_config` 表（Fernet 加密，明文不落库），运行时
`llm_config.get_llm_config()` 读库优先、未配置时回退 `.env` 的 `LLM_*` 变量。

Java 主程（interview-server 分支）目前只读 `.env` 的 `LLM_API_KEY / LLM_BASE_URL / LLM_MODEL`
（`AppConfig.load()` 启动快照），**不读 `llm_config` 表**，因此网页上保存的模型配置对 Java
侧的 Copilot / 模拟面试 / 复盘 / 计划等 LLM 调用完全不生效，两边配置不一致。

目标：让 Java 侧完整实现模型管理——与 Python 共享同一张 `llm_config` 表、同一 Fernet 加密格式，
提供同契约的 `GET/PUT /api/settings/llm`，并使 LLM 调用方**保存后立即生效**（无需重启）。

## 方案选择

| 方案 | 做法 | 结论 |
|---|---|---|
| A. 共享配置提供器 + 动态生效（**采用**） | `LlmConfigProvider` 静态单例提供生效配置，`Llm` 每次调用前解析，保存后立即生效 | 与 Python 语义完全对齐 |
| B. 启动快照 + 重启生效 | 启动时读表覆盖 AppConfig，PUT 只写表 | 拒绝：改配置需重启，延续"Java 不感知网页改动"问题 |
| C. Java 独立配置 | 不复用 `llm_config` 表 | 拒绝：与 Python 配置分叉 |

## 现有代码事实

- Python 侧 `llm_config` 表结构（`models/LlmConfig.py`）：`id`（固定 1）、`provider`、`base_url`、
  `model`、`copilot_model`、`think_model`、`api_key_enc`（Text，可空）、`updated_at`。
- Python Fernet 密钥派生（`utils/secret_box.py`）：
  `secret = CONFIG_ENCRYPTION_KEY.strip() || APP_SECRET_KEY.strip()`；
  `key = base64.urlsafe_b64encode(sha256(secret.encode()).digest())`；密文为 ASCII urlsafe base64。
- Python 契约（`routes/route_settings.py`，前端 `web/src/pages/llm-settings.tsx` 已按此实现）：
  - `GET /api/settings/llm` → `{success, config:{provider, base_url, model, copilot_model,
    think_model, has_api_key, api_key_masked, api_key_source, updated_at}}`；
    `api_key_source` ∈ `database`（有可解密 key）/ `none`。
  - `PUT /api/settings/llm` body：`provider / base_url / model / copilot_model / think_model /
    api_key / clear_api_key`。规则：`base_url` 必须 `http(s)://` 开头否则 400；`provider` 转小写；
    `api_key` 非空则加密落库；留空则保留原密钥；`clear_api_key=true` 清空；加密密钥缺失时
    400 拒绝明文保存；成功后失效缓存。
- Java 侧：`AppConfig.load()` 启动快照；`Llm(config)` 用 `llmBaseUrl/llmModel/llmApiKey` 构造
  FeatAI `ChatModel`；DB 访问为 MyBatis 注解 mapper + DAO（`MyBatis.addMapper` 注册）；路由为
  `RouteSupport.route(router, path, method, handler)` + fastjson2；BouncyCastle
  `bcprov-jdk18on 1.78.1` 已在 pom；会话鉴权为 `AuthRoutes.current(ctx.Request)`（返回
  `SessionStore.Session` 或 null）。`new Llm(config)` 出现于：`PlanRoutes.init`（PreparationService）、
  `MockRoutes.init`（MockInterviewService）、`ReviewRoutes.init`、`CopilotWs`（每 WS 连接）、
  `InterviewServerApplication`（/ws/copilot 升级处）。

## 设计（方案 A）

### 组件

1. **`FernetBox`**（`com.aiinterview.server` 新类）
   - 静态方法：`encrypt(String plain)`、`decrypt(String token)`、`mask(String plain)`。
   - 用 BouncyCastle 实现 Fernet：AES-128-CBC + PKCS7 填充 + HMAC-SHA256 + urlsafe base64；
     密钥 = `base64url(sha256(secret))`，`secret` 取自 env `CONFIG_ENCRYPTION_KEY`，
     缺失时回退 `APP_SECRET_KEY`；两者皆无返回空（不可用）。
   - `decrypt` 对空 token、密钥缺失、校验失败（密钥轮换/损坏）返回 `null`，不抛异常。
   - `mask`：末 4 位，`****7890` 形式；长度 < 8 时为 `****`；空返回 `""`。
   - 互操作性：与 Python `cryptography.Fernet` 产出的 token 双向可解（有专项测试）。

2. **`LlmConfigMapper` + `LlmConfigDao`**（`com.aiinterview.server.db`）
   - Mapper（MyBatis 注解）：
     - `@Select("SELECT * FROM llm_config WHERE id = 1") Map<String,Object> findRow()`
     - `@Insert` 或 `@Update` upsert（存在则更新、不存在则插入 id=1；`updated_at = datetime('now')`）。
   - DAO：`find()` 返回行（含 `api_key_enc`）；`save(...)` 执行 upsert。
   - `MyBatis.init` 增加 `configuration.addMapper(LlmConfigMapper.class)`。

3. **`LlmConfigProvider`**（`com.aiinterview.server.svc`，静态单例）
   - `EffectiveConfig effective()`：返回 `{provider, baseUrl, model, copilotModel, thinkModel,
     apiKey, apiKeySource}`。
     - env 默认（与 Python 语义一致）：`LLM_MODEL` 默认 `THUDM/GLM-Z1-9B-0414`、
       `LLM_BASE_URL` 默认 `https://api.siliconflow.cn/v1`、`LLM_PROVIDER=ark` 时 base_url 默认
       `https://ark.cn-beijing.volces.com/api/coding/v3`、copilot_model 默认回落 model /
       ark 回落 `deepseek-v4-flash`。
     - DB 覆盖：读 `llm_config` 行，`provider/base_url/model/copilot_model/think_model` 非空
       逐字段覆盖；`api_key_enc` 解密成功且非空则覆盖 `apiKey`。
     - `apiKeySource`：DB 覆盖存在且 key 可解密 → `database`；否则 env 有 `LLM_API_KEY` →
       `environment`；否则 `none`。
   - 缓存：`effective()` 结果缓存 TTL 5s（`System.nanoTime` 比较）；`invalidate()` 立即清空
     （PUT 成功后调用，与 Python `invalidate_llm_config_cache` 对齐）。
   - 懒加载：首次 `effective()` 才查库；查库失败（表不存在等）按无 DB 覆盖处理并记录 warn。

4. **`SettingsRoutes`**（`com.aiinterview.server.web`）
   - `GET /api/settings/llm`：鉴权（未登录 401）→ 读行 → 解密 → 掩码 → 响应
     `{success:true, config:{...}}`（字段与 Python 一致；无行时全空 + `has_api_key=false` +
     `api_key_source="none"` + `updated_at=null`）。
   - `PUT /api/settings/llm`：鉴权 → 解析 JSON → 逐字段校验/写入：
     - `base_url` 非空且不以 `http://`/`https://` 开头 → 400 `"Base URL 必须以 http(s):// 开头"`；
     - `provider` 转小写；
     - `api_key` 非空 → `FernetBox.encrypt`，不可用时 400
       `"未配置加密密钥（.env 需设置 CONFIG_ENCRYPTION_KEY 或 APP_SECRET_KEY），拒绝明文保存 API Key"`；
     - 留空 → 保留原 `api_key_enc`；`clear_api_key=true` → 置空；
     - 无行则插入 id=1；`updated_at = datetime('now')`；
     - 成功后 `LlmConfigProvider.invalidate()` → 响应 `{success:true}`。
   - 注册到 `InterviewServerApplication`（`SettingsRoutes.register(router)`）。

5. **`Llm` 改造**
   - 新增无参/`LlmConfigProvider` 构造；`chat()` 开头解析 `provider.effective()`，与当前
     `ChatModel` 的 baseUrl/model/apiKey 比较，变化时重建（FeatAI ChatModel 为轻量对象，重建成本可忽略）。
   - 保留现有 `Llm(ChatModel, timeout)` 测试构造不变；删除/替换 `Llm(AppConfig)` 构造。
   - 消费方（`PlanRoutes.init`、`MockRoutes.init`、`ReviewRoutes.init`、`CopilotWs`、
     `InterviewServerApplication`）由 `new Llm(config)` 改为 `new Llm()`。

### 数据流

```text
GET /api/settings/llm
  鉴权 → LlmConfigDao.find() → FernetBox.decrypt(api_key_enc) → mask
  → {success, config:{provider, base_url, model, copilot_model, think_model,
     has_api_key, api_key_masked, api_key_source, updated_at}}

PUT /api/settings/llm
  鉴权 → 校验/清洗字段 → api_key 加密（或留空保留/clear 清空）
  → LlmConfigDao.save() → LlmConfigProvider.invalidate() → {success:true}

LLM 调用（任意消费方）
  Llm.chat() → LlmConfigProvider.effective() → ChatModel(base_url, model, api_key)
  → chatStream(...)
```

### 错误处理

| 场景 | 行为 |
|---|---|
| 未登录访问 /api/settings/llm | 401 |
| `base_url` 非 http(s):// | 400 |
| 加密密钥缺失时 PUT 带 api_key | 400 拒绝明文保存 |
| 解密失败（密钥轮换/损坏） | 按未配置处理，`api_key_source=none`，不抛异常 |
| `llm_config` 表不存在/查库失败 | 记录 warn，按 env 配置继续 |

### 测试（JUnit，沿用现有测试风格）

- `FernetBoxTest`：加解密往返；与 Python 预生成 token 互操作（测试内嵌固定 token
  `unittest-dummy-*` 前缀，非真实凭据）；密钥轮换解密失败返回 null；掩码格式。
- `LlmConfigProviderTest`：env 默认；DB 覆盖优先级；TTL 缓存与 `invalidate()`；无行场景。
- `SettingsRoutesTest`：GET 掩码回显；PUT 保存后 GET 回读；留空保留；`clear_api_key`；
  401；400 分支（base_url 非法、无加密密钥）。
- `LlmTest`：配置变更后下一次 chat 使用新 base_url/model/api_key（mock ChatModel）。

## 范围外

- 不改 Python 侧任何代码与表结构。
- 不改 `docs/api-contract.md`（Java 按已冻结契约实现）。
- 不改 `.env` 机制与 `start.ps1`（密钥经 `.env` 透传的既有路径不变）。
- 不在 Java 侧新增前端页面（web/dist 由主仓构建，Java 托管，页面已实现）。

## 验收标准

1. Java 侧 `GET /api/settings/llm` 与 Python 返回同构响应；掩码只露末 4 位，明文/密文不出现在响应。
2. Java 侧 `PUT /api/settings/llm` 保存后：同库数据 Python 侧可读（互操作）；`Llm` 下一次调用
   即用新配置，无需重启；留空保留、`clear_api_key` 清除、无密钥 400 各分支正确。
3. 未配置 DB 时 Java 侧 LLM 行为与现状一致（回退 `.env`）。
4. 新增测试全绿；`mvn test` 全量通过。
