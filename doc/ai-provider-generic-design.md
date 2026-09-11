# 通用 AI 厂商适配设计

日期：2026-09-11
分支：feature/generic-ai-provider

## 背景

原实现把 OpenAI 的细节写死在 `AiService` 里，换厂商会失败。两处硬伤：

1. **靠模型名猜厂商。** `isResponsesModel()` 看到模型名里含 `o1`、`o3`、`o4`、`4.1`、`reasoner`、`4o-mini`
   就改用 OpenAI 独有的 `/v1/responses` 端点。DeepSeek 的 `deepseek-reasoner` 命中 `reasoner`
   这条规则，被打到一个 DeepSeek 根本没有的端点上，必然失败。
   该判断本身也是错的：`o1`、`o3`、`gpt-4.1`、`gpt-4o-mini` 都支持 chat/completions 协议。

2. **端点拼接规则过窄。** 原规则是「地址不以 `/v1` 结尾就补 `/v1`」。
   智谱 GLM 的路径是 `/api/paas/v4`，补完变成 `/api/paas/v4/v1/chat/completions`，404。
   火山方舟 `/api/v3`、通义千问 `/compatible-mode/v1` 同样受影响。

## 目标

面向 **OpenAI Chat Completions 协议** 编写，而不是面向某一家厂商。
凡是兼容该协议的厂商，在页面上填好地址、密钥、模型名即可使用，不改代码。

明确不做：Anthropic、Gemini 这类协议不同的厂商。它们需要独立的 Provider 实现，
当前用不上，不提前抽象。

## 后端

改动范围：`src/main/java/com/getjobs/application/service/AiService.java`。

### 删除

`isResponsesModel`、`buildResponsesEndpoint`、`sendRequestViaResponses`、
`containsReasoningParamError`，以及整条 Responses API 旁路。

删除不损失能力，因为这些模型本来就支持 chat/completions 协议。

### 端点拼接

按三条规则，自上而下匹配：

| 输入的 BASE_URL | 判定 | 实际请求 |
| --- | --- | --- |
| 路径已以 `/chat/completions` 结尾 | 完整端点 | 原样使用 |
| 带路径，如 `/api/paas/v4` | 厂商自带版本段 | 追加 `/chat/completions` |
| 纯域名，如 `api.deepseek.com` | 需要补版本段 | 追加 `/v1/chat/completions` |

附带处理：去掉首尾空白与末尾斜杠；缺少协议头时补 `https://`。

第一条规则顺带让 Azure OpenAI 这类「完整端点带查询参数」的地址也能用，
因此保留 `api-key` 请求头。

### 请求体

OpenAI 协议最小集：`model`、`messages`、`temperature`。

`temperature` 的降级：OpenAI 新推理模型会拒收该参数并返回 400。
处理方式是先带上发送，若返回 400 且响应体提到 `temperature`，则去掉该参数重试一次。
判断依据从「猜模型名」改成「服务端的实际报错」，对任何厂商都成立。

### 响应解析

取 `choices[0].message.content`。

两处加固：
- 改用宽松取值，避免兼容层返回 `null` 时直接抛异常。
- `content` 为空时回落到 `reasoning_content`，覆盖只回推理正文的兼容层。

无法解析时抛异常，而不是像原来那样把整个 JSON 原文当作打招呼语返回。
调用方 `Boss.generateAiMessage` 已捕获异常并回落到用户自定义的打招呼语。

## 前端

改动范围：`front/app/env-config/page.tsx`，新增 `front/lib/ai-providers.ts`。

在「API 配置」卡片里加厂商下拉。选中后自动填入地址与模型名，两个输入框仍可手改。

预设：DeepSeek（默认）、通义千问、Kimi、智谱 GLM、火山方舟、硅基流动、OpenAI、自定义。

**当前选中项不入库。** 加载时用 BASE_URL 反查预设列表得出，匹配不上显示「自定义」。

这样做是因为 `ConfigService.batchUpdateConfigs` 只更新已存在的配置键，
遇到新键只打警告就跳过（见 `ConfigService.java:135`）。
新增 `PROVIDER` 键会保存失败且无提示，反查规避了这个问题。

预设列表放在前端，因为前端是唯一消费者。

## 数据库

不改 `db/getjobs.db`。

该文件是必需的：`spring.sql.init.mode` 为 `never`，仓库内无任何 DDL 脚本，
`config`、`ai`、`cookie`、`boss_option` 等表在代码里没有建表语句，删掉则应用起不来。
它还装着代码生成不出来的种子数据，仅 `boss_option` 就有 578 行城市与行业代码。

它同时是随仓库提交的二进制文件，改动无法合并。
保持不动，从上游拉取更新时才不会冲突。

## 验证

- 单元测试覆盖端点拼接与响应解析，两者都是纯函数，无需 Spring 容器。
- 端到端用现成的 `GET /api/ai/chat?content=...` 接口，需真实密钥。
