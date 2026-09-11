package com.getjobs.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiService 中纯函数部分的单元测试。
 *
 * <p>这些方法不依赖 Spring 容器与网络，是换厂商时最容易出错、又最难靠手点发现的两块：
 * 端点拼接错了会 404，响应解析错了会把 JSON 原文当成打招呼语发出去。
 */
class AiServiceTest {

    @Nested
    @DisplayName("端点拼接")
    class BuildEndpoint {

        @ParameterizedTest(name = "{0} -> {1}")
        @DisplayName("纯域名补 /v1")
        @CsvSource({
                "https://api.deepseek.com,        https://api.deepseek.com/v1/chat/completions",
                "https://api.openai.com,          https://api.openai.com/v1/chat/completions",
                "https://api.deepseek.com/,       https://api.deepseek.com/v1/chat/completions",
                "'  https://api.deepseek.com  ',  https://api.deepseek.com/v1/chat/completions",
        })
        void bareHostGetsDefaultVersionSegment(String input, String expected) {
            assertEquals(expected, AiService.buildChatCompletionsEndpoint(input));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @DisplayName("厂商自带版本段时不再补 /v1")
        @CsvSource({
                // 智谱 GLM：旧规则会拼成 /api/paas/v4/v1/chat/completions 而 404
                "https://open.bigmodel.cn/api/paas/v4,          https://open.bigmodel.cn/api/paas/v4/chat/completions",
                // 火山方舟
                "https://ark.cn-beijing.volces.com/api/v3,      https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                // 通义千问兼容模式
                "https://dashscope.aliyuncs.com/compatible-mode/v1, https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                // Kimi
                "https://api.moonshot.cn/v1,                    https://api.moonshot.cn/v1/chat/completions",
                // 硅基流动
                "https://api.siliconflow.cn/v1,                 https://api.siliconflow.cn/v1/chat/completions",
                // DeepSeek 官方文档也允许显式带 /v1
                "https://api.deepseek.com/v1,                   https://api.deepseek.com/v1/chat/completions",
        })
        void vendorPathIsPreserved(String input, String expected) {
            assertEquals(expected, AiService.buildChatCompletionsEndpoint(input));
        }

        @Test
        @DisplayName("已是完整端点时原样使用")
        void fullEndpointIsUsedAsIs() {
            String full = "https://api.deepseek.com/v1/chat/completions";
            assertEquals(full, AiService.buildChatCompletionsEndpoint(full));
        }

        @Test
        @DisplayName("带查询参数的完整端点也原样使用，兼容 Azure")
        void azureStyleEndpointKeepsQueryString() {
            String azure = "https://x.openai.azure.com/openai/deployments/gpt4/chat/completions?api-version=2024-02-01";
            assertEquals(azure, AiService.buildChatCompletionsEndpoint(azure));
        }

        @Test
        @DisplayName("缺少协议头时补 https")
        void schemeIsAddedWhenMissing() {
            assertEquals("https://api.deepseek.com/v1/chat/completions",
                    AiService.buildChatCompletionsEndpoint("api.deepseek.com"));
        }

        @Test
        @DisplayName("http 地址保留 http，不被改写")
        void httpSchemeIsPreserved() {
            assertEquals("http://127.0.0.1:11434/v1/chat/completions",
                    AiService.buildChatCompletionsEndpoint("http://127.0.0.1:11434"));
        }

        @ParameterizedTest
        @DisplayName("地址为空时报出明确的配置缺失")
        @ValueSource(strings = {"", "   ", "/"})
        void blankBaseUrlFailsLoudly(String input) {
            assertThrows(IllegalStateException.class,
                    () -> AiService.buildChatCompletionsEndpoint(input));
        }

        @Test
        @DisplayName("地址为 null 时报出明确的配置缺失")
        void nullBaseUrlFailsLoudly() {
            assertThrows(IllegalStateException.class,
                    () -> AiService.buildChatCompletionsEndpoint(null));
        }
    }

    @Nested
    @DisplayName("响应解析")
    class ExtractContent {

        @Test
        @DisplayName("取 choices[0].message.content")
        void readsStandardContent() {
            String body = """
                    {"id":"x","choices":[{"message":{"role":"assistant","content":"你好呀"}}]}
                    """;
            assertEquals("你好呀", AiService.extractContent(body));
        }

        @Test
        @DisplayName("content 为空时回落到 reasoning_content")
        void fallsBackToReasoningContent() {
            String body = """
                    {"choices":[{"message":{"content":"","reasoning_content":"推理正文"}}]}
                    """;
            assertEquals("推理正文", AiService.extractContent(body));
        }

        @Test
        @DisplayName("推理模型同时返回两者时优先取 content")
        void prefersContentOverReasoning() {
            String body = """
                    {"choices":[{"message":{"content":"最终答案","reasoning_content":"思考过程"}}]}
                    """;
            assertEquals("最终答案", AiService.extractContent(body));
        }

        @Test
        @DisplayName("content 为 JSON null 时不抛异常")
        void nullContentDoesNotThrow() {
            String body = """
                    {"choices":[{"message":{"content":null}}]}
                    """;
            assertNull(AiService.extractContent(body));
        }

        @ParameterizedTest
        @DisplayName("结构不符合预期时返回 null，而不是把原文当回复")
        @ValueSource(strings = {
                "{}",
                "{\"choices\":[]}",
                "{\"choices\":[{}]}",
                "{\"error\":{\"message\":\"invalid api key\"}}",
                "not json at all",
                "",
                "   ",
        })
        void malformedBodyReturnsNull(String body) {
            assertNull(AiService.extractContent(body));
        }

        @Test
        @DisplayName("body 为 null 时返回 null")
        void nullBodyReturnsNull() {
            assertNull(AiService.extractContent(null));
        }
    }

    @Nested
    @DisplayName("请求体")
    class BuildRequestBody {

        @Test
        @DisplayName("只包含各厂商都支持的最小字段集")
        void containsMinimalFields() {
            var body = AiService.buildRequestBody("deepseek-chat", "你好", true);

            assertEquals("deepseek-chat", body.getString("model"));
            assertEquals(1, body.getJSONArray("messages").length());
            assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"));
            assertEquals("你好", body.getJSONArray("messages").getJSONObject(0).getString("content"));
            assertTrue(body.has("temperature"));
        }

        @Test
        @DisplayName("降级重试时不带 temperature")
        void omitsTemperatureOnRetry() {
            var body = AiService.buildRequestBody("o3-mini", "你好", false);
            assertFalse(body.has("temperature"));
        }
    }

    @Nested
    @DisplayName("temperature 降级判断")
    class MentionsTemperature {

        @Test
        @DisplayName("认出 OpenAI 推理模型拒收 temperature 的报错")
        void detectsUnsupportedParameterError() {
            String body = """
                    {"error":{"message":"Unsupported value: 'temperature' does not support 0.5 with this model.",
                     "type":"invalid_request_error","param":"temperature"}}
                    """;
            assertTrue(AiService.mentionsTemperature(body));
        }

        @Test
        @DisplayName("与 temperature 无关的报错不触发降级")
        void ignoresUnrelatedError() {
            String body = "{\"error\":{\"message\":\"model not found\"}}";
            assertFalse(AiService.mentionsTemperature(body));
        }

        @Test
        @DisplayName("body 为 null 时不触发降级")
        void nullBodyDoesNotTrigger() {
            assertFalse(AiService.mentionsTemperature(null));
        }
    }
}
