package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.mapper.AiMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * AI 服务（Spring 管理）
 *
 * <p>本类面向 <b>OpenAI Chat Completions 协议</b> 编写，而不是面向某一家厂商。
 * 凡是兼容该协议的厂商，只要在页面上配置好 BASE_URL / API_KEY / MODEL 即可使用，无须改代码。
 * 常见的兼容厂商包括 DeepSeek、通义千问、Kimi、智谱 GLM、火山方舟、硅基流动、OpenAI。
 *
 * <p>刻意不做厂商嗅探：历史版本靠模型名里是否含 reasoner、o1 等字样来切换端点，
 * 导致 deepseek-reasoner 被打到 OpenAI 独有的 /v1/responses 上而必然失败。
 * 现在一律走 chat/completions，参数兼容问题改为依据服务端的实际报错来降级。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiService {

    /** chat/completions 在各厂商中的统一路径后缀 */
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";

    /** 未带版本段的纯域名需要补上的默认版本段 */
    private static final String DEFAULT_VERSION_SEGMENT = "/v1";

    private static final int TIMEOUT_SECONDS = 60;

    private static final double DEFAULT_TEMPERATURE = 0.5;

    private final ConfigService configService;
    private final AiMapper aiMapper;

    /**
     * 发送 AI 请求（非流式）并返回回复内容。
     *
     * @param content 用户消息内容
     * @return AI 回复文本
     * @throws RuntimeException 请求失败或响应无法解析时抛出，由调用方决定降级策略
     */
    public String sendRequest(String content) {
        var cfg = configService.getAiConfigs();
        String endpoint = buildChatCompletionsEndpoint(cfg.get("BASE_URL"));
        String apiKey = cfg.get("API_KEY");
        String model = cfg.get("MODEL");

        HttpResponse<String> response = post(endpoint, apiKey, buildRequestBody(model, content, true));

        // 部分推理模型（如 OpenAI o 系列）拒收 temperature，依据服务端报错去掉该参数重试一次。
        if (response.statusCode() == 400 && mentionsTemperature(response.body())) {
            log.warn("服务端拒收 temperature 参数，去掉后重试一次: endpoint={}, model={}", endpoint, model);
            response = post(endpoint, apiKey, buildRequestBody(model, content, false));
        }

        if (response.statusCode() != 200) {
            log.error("AI请求失败: status={}, endpoint={}, model={}, body={}",
                    response.statusCode(), endpoint, model, response.body());
            throw new RuntimeException("AI请求失败，状态码: " + response.statusCode() + ", 详情: " + response.body());
        }

        logUsage(response.body());

        String replyContent = extractContent(response.body());
        if (replyContent == null) {
            log.error("AI响应无法解析: endpoint={}, body={}", endpoint, response.body());
            throw new RuntimeException("AI响应无法解析，原始内容: " + response.body());
        }
        return replyContent;
    }

    /**
     * 发起一次 POST 请求。
     *
     * <p>同时带上 Authorization 与 api-key 两个头：前者是 OpenAI 协议的标准做法，
     * 后者供 Azure OpenAI 这类直接填完整端点的场景使用。
     */
    private HttpResponse<String> post(String endpoint, String apiKey, JSONObject body) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("调用AI服务异常: endpoint={}", endpoint, e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    /**
     * 构建 OpenAI 协议的请求体，只用各厂商都支持的最小字段集。
     *
     * @param withTemperature 是否带上 temperature，降级重试时传 false
     */
    static JSONObject buildRequestBody(String model, String content, boolean withTemperature) {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);

        JSONObject requestData = new JSONObject();
        requestData.put("model", model);
        requestData.put("messages", new JSONArray().put(message));
        if (withTemperature) {
            requestData.put("temperature", DEFAULT_TEMPERATURE);
        }
        return requestData;
    }

    /**
     * 规范化 BASE_URL：去首尾空白、去掉末尾斜杠、缺协议头时补 https。
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.regionMatches(true, 0, "http://", 0, 7)
                && !trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    /**
     * 根据 BASE_URL 推导 chat/completions 端点。
     *
     * <p>三条规则自上而下匹配：
     * <ol>
     *   <li>路径已以 /chat/completions 结尾，视为完整端点，原样使用（兼容带查询参数的 Azure 地址）</li>
     *   <li>带路径（如智谱的 /api/paas/v4、通义千问的 /compatible-mode/v1），
     *       说明厂商自带版本段，直接追加 /chat/completions</li>
     *   <li>纯域名（如 api.deepseek.com），补上 /v1 再追加 /chat/completions</li>
     * </ol>
     */
    static String buildChatCompletionsEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("缺少必要配置: BASE_URL");
        }

        String path = pathOf(normalized);
        if (path.endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            return normalized;
        }
        if (path.isEmpty() || path.equals("/")) {
            return normalized + DEFAULT_VERSION_SEGMENT + CHAT_COMPLETIONS_SUFFIX;
        }
        return normalized + CHAT_COMPLETIONS_SUFFIX;
    }

    /**
     * 取出 URL 的路径部分，解析失败时返回空串（当作纯域名处理）。
     */
    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从 OpenAI 协议响应中取出回复正文。
     *
     * <p>优先取 choices[0].message.content；为空时回落到 reasoning_content，
     * 覆盖只回推理正文的兼容层。全都取不到则返回 null，由调用方报错，
     * 避免把整段 JSON 原文当作打招呼语发出去。
     */
    static String extractContent(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject firstChoice = choices.optJSONObject(0);
            JSONObject message = firstChoice == null ? null : firstChoice.optJSONObject("message");
            if (message == null) {
                return null;
            }
            String text = message.optString("content", "");
            if (!text.isBlank()) {
                return text;
            }
            String reasoning = message.optString("reasoning_content", "");
            return reasoning.isBlank() ? null : reasoning;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 400 响应是否在抱怨 temperature 参数。
     */
    static boolean mentionsTemperature(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("temperature");
    }

    /**
     * 记录本次调用的模型与 token 消耗，字段缺失时不影响主流程。
     */
    private void logUsage(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject usage = root.optJSONObject("usage");
            long created = root.optLong("created", 0);
            LocalDateTime createdTime = created > 0
                    ? Instant.ofEpochSecond(created).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    : LocalDateTime.now();

            log.info("AI响应: id={}, time={}, model={}, promptTokens={}, completionTokens={}, totalTokens={}",
                    root.optString("id"),
                    createdTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    root.optString("model"),
                    usage != null ? usage.optInt("prompt_tokens", -1) : -1,
                    usage != null ? usage.optInt("completion_tokens", -1) : -1,
                    usage != null ? usage.optInt("total_tokens", -1) : -1);
        } catch (Exception e) {
            log.debug("解析 AI 响应用量信息失败: {}", e.getMessage());
        }
    }

    // ================= 合并的 AI 配置管理方法 =================

    /**
     * 获取AI配置（获取最新一条，如果不存在则创建默认配置）
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfig() {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
        if (aiEntity == null) {
            aiEntity = createDefaultConfig();
        }
        return aiEntity;
    }

    /**
     * 获取所有AI配置
     */
    @Transactional(readOnly = true)
    public java.util.List<AiEntity> getAllAiConfigs() {
        return aiMapper.selectList(null);
    }

    /**
     * 根据ID获取AI配置
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfigById(Long id) {
        return aiMapper.selectById(id);
    }

    /**
     * 保存或更新AI配置（introduce/prompt）
     */
    @Transactional
    public AiEntity saveOrUpdateAiConfig(String introduce, String prompt) {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);

        if (aiEntity == null) {
            aiEntity = new AiEntity();
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            aiEntity.setCreatedAt(java.time.LocalDateTime.now());
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.insert(aiEntity);
            log.info("创建新的AI配置，ID: {}", aiEntity.getId());
        } else {
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.updateById(aiEntity);
            log.info("更新AI配置，ID: {}", aiEntity.getId());
        }

        return aiEntity;
    }

    /**
     * 删除AI配置
     */
    @Transactional
    public boolean deleteAiConfig(Long id) {
        int result = aiMapper.deleteById(id);
        if (result > 0) {
            log.info("删除AI配置成功，ID: {}", id);
            return true;
        }
        return false;
    }

    /**
     * 创建默认配置
     */
    @Transactional
    protected AiEntity createDefaultConfig() {
        AiEntity aiEntity = new AiEntity();
        aiEntity.setIntroduce("请在此填写您的技能介绍");
        aiEntity.setPrompt("请在此填写AI提示词模板");
        aiEntity.setCreatedAt(java.time.LocalDateTime.now());
        aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
        aiMapper.insert(aiEntity);
        log.info("创建默认AI配置，ID: {}", aiEntity.getId());
        return aiEntity;
    }
}
