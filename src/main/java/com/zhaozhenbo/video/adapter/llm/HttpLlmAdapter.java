package com.zhaozhenbo.video.adapter.llm;

import com.zhaozhenbo.video.domain.LlmCall;
import com.zhaozhenbo.video.port.LlmException;
import com.zhaozhenbo.video.port.LlmPort;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 Chat Completions 适配器。
 *
 * 不用厂商 SDK：请求体就是标准的 model/messages/temperature，
 * 换 DeepSeek / Qwen / GLM 只改 baseUrl + model。
 */
public class HttpLlmAdapter implements LlmPort {

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public HttpLlmAdapter(LlmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, properties.getTimeoutSeconds())))
                .build();
    }

    @Override
    public String complete(LlmCall call) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new LlmException("LLM_API_KEY 未配置，无法调用模型");
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return doComplete(call);
            } catch (Exception e) {
                last = new LlmException("模型调用失败 attempt=" + attempt + " step=" + call.step(), e);
                sleep(attempt);
            }
        }
        throw last;
    }

    private String doComplete(LlmCall call) throws Exception {
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", call.systemPrompt()),
                        Map.of("role", "user", "content", call.userPrompt())),
                "temperature", call.temperature(),
                "max_tokens", call.maxTokens());

        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmException("模型返回 HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }

        Map<?, ?> root = mapper.readValue(response.body(), Map.class);
        List<?> choices = (List<?>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("模型响应缺少 choices");
        }
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.getFirst()).get("message");
        String content = message == null ? null : String.valueOf(message.get("content"));
        if (content == null || content.isBlank()) {
            throw new LlmException("模型返回内容为空");
        }
        return extractJson(content);
    }

    private URI endpoint() {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        String url = base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
        return URI.create(url);
    }

    /** 有些模型会包 ```json 或加一句“以下是 JSON”，这里只截取最外层对象。 */
    static String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content.strip();
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
