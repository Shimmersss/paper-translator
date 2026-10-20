package com.papertranslator.config;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Model provider endpoints, keys and model names, read from configuration only.
 * BabelDOC talks to an OpenAI-compatible API; the vision model used for image
 * translation may speak either the OpenAI or the Anthropic Messages protocol.
 */
@Component
public class ProviderSettings {

    private final LlmConfig llm;
    private final BabelDocConfig babeldoc;

    public ProviderSettings(LlmConfig llm, BabelDocConfig babeldoc) {
        this.llm = llm;
        this.babeldoc = babeldoc;
    }

    public String llmUrl() { return text(llm.getApiUrl()); }
    public String llmKey() { return text(llm.getApiKey()); }
    public String llmModel() { return text(llm.getModel()); }
    public String llmProtocol() { return normalizeProtocol(llm.getProtocol()); }

    public String resolvedLlmProtocol() {
        return resolveLlmProtocol(llmUrl(), llmProtocol());
    }

    public String resolveLlmProtocol(String baseUrl, String protocol) {
        String configured = normalizeProtocol(protocol);
        if (!"AUTO".equals(configured)) return configured;
        String url = (baseUrl == null ? "" : baseUrl).toLowerCase(Locale.ROOT);
        return url.contains("anthropic") || url.endsWith("/messages") ? "CLAUDE" : "OPENAI";
    }

    public String llmEndpoint() {
        return llmEndpoint(llmUrl(), llmProtocol());
    }

    public String llmEndpoint(String baseUrl, String protocol) {
        String base = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        if ("CLAUDE".equals(resolveLlmProtocol(baseUrl, protocol))) {
            if (base.endsWith("/messages")) return base;
            return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        }
        if (base.endsWith("/chat/completions")) return base;
        return base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    }

    public String babelUrl() { return text(babeldoc.getOpenaiBaseUrl()); }
    public String babelKey() { return text(babeldoc.getOpenaiApiKey()); }
    public String babelModel() { return text(babeldoc.getOpenaiModel()); }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeProtocol(String value) {
        String protocol = value == null || value.isBlank() ? "AUTO" : value.trim().toUpperCase(Locale.ROOT);
        if ("ANTHROPIC".equals(protocol)) protocol = "CLAUDE";
        if (!List.of("AUTO", "OPENAI", "CLAUDE").contains(protocol)) {
            throw new IllegalArgumentException("LLM_PROTOCOL 只能是 auto、openai 或 claude");
        }
        return protocol;
    }
}
