package com.papertranslator.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertranslator.config.ProviderSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sends one tiny chat request to check an endpoint, model and key; nothing is saved. */
@Component
public class ConnectionTester {

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConnectionTester(RestClient llmRestClient) {
        this.client = llmRestClient;
    }

    public Map<String, Object> test(String baseUrl, String apiKey, String model, String protocol) {
        if (baseUrl == null || baseUrl.isBlank()) return result(false, "未填写接口地址");
        if (apiKey == null || apiKey.isBlank()) return result(false, "未填写 API Key");
        if (model == null || model.isBlank()) return result(false, "未填写模型名");
        String resolved = ProviderSettings.resolveProtocol(baseUrl, protocol);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 16);
        if ("CLAUDE".equals(resolved)) {
            body.put("system", "Reply with exactly OK.");
            body.put("messages", List.of(Map.of("role", "user", "content", "OK?")));
        } else {
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "Reply with exactly OK."),
                    Map.of("role", "user", "content", "OK?")));
        }
        try {
            String response = client.post()
                    .uri(ProviderSettings.endpoint(baseUrl, protocol))
                    .headers(headers -> {
                        if ("CLAUDE".equals(resolved)) {
                            headers.set("x-api-key", apiKey);
                            headers.set("anthropic-version", "2023-06-01");
                        } else {
                            headers.setBearerAuth(apiKey);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(response == null ? "{}" : response);
            boolean answered = root.path("choices").isArray() || root.path("content").isArray();
            return answered ? result(true, "连接成功（" + ("CLAUDE".equals(resolved) ? "Anthropic" : "OpenAI 兼容") + "接口）")
                    : result(false, "接口有响应，但不是对话补全格式：" + scrub(response, apiKey));
        } catch (RestClientResponseException e) {
            return result(false, "HTTP " + e.getStatusCode().value() + "：" + scrub(e.getResponseBodyAsString(), apiKey));
        } catch (Exception e) {
            return result(false, "无法连接：" + scrub(e.getMessage(), apiKey));
        }
    }

    static String scrub(String text, String apiKey) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (apiKey != null && apiKey.length() >= 4) value = value.replace(apiKey.trim(), "****");
        value = value.replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9._-]{8,}", "$1****");
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    private static Map<String, Object> result(boolean ok, String message) {
        return Map.of("ok", ok, "message", message);
    }
}
