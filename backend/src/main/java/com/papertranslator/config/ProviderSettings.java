package com.papertranslator.config;

import com.papertranslator.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Model provider endpoints, keys and model names, as merged by {@link SettingsService}
 * (settings page, then .env / environment, then defaults). BabelDOC talks to an
 * OpenAI-compatible API; the vision model used for image translation may speak
 * either the OpenAI or the Anthropic Messages protocol.
 */
@Component
public class ProviderSettings {

    private final SettingsService settings;

    public ProviderSettings(SettingsService settings) {
        this.settings = settings;
    }

    public String llmUrl() { return text(settings.imageBaseUrl()); }
    public String llmKey() { return text(settings.imageApiKey()); }
    public String llmModel() { return text(settings.imageModel()); }
    public String llmVisionModel() { return text(settings.imageVisionModel()); }
    public String llmProtocol() { return normalizeProtocol(settings.imageProtocol()); }

    public String resolvedLlmProtocol() {
        return resolveProtocol(llmUrl(), llmProtocol());
    }

    public String resolveLlmProtocol(String baseUrl, String protocol) {
        return resolveProtocol(baseUrl, protocol);
    }

    public String llmEndpoint() {
        return endpoint(llmUrl(), llmProtocol());
    }

    public String llmEndpoint(String baseUrl, String protocol) {
        return endpoint(baseUrl, protocol);
    }

    public String babelUrl() { return text(settings.pdfBaseUrl()); }
    public String babelKey() { return text(settings.pdfApiKey()); }
    public String babelModel() { return text(settings.pdfModel()); }

    /** "CLAUDE" or "OPENAI"; "auto" picks Anthropic when the URL says so. */
    public static String resolveProtocol(String baseUrl, String protocol) {
        String configured = normalizeProtocol(protocol);
        if (!"AUTO".equals(configured)) return configured;
        String url = (baseUrl == null ? "" : baseUrl).toLowerCase(Locale.ROOT);
        return url.contains("anthropic") || url.endsWith("/messages") ? "CLAUDE" : "OPENAI";
    }

    public static String endpoint(String baseUrl, String protocol) {
        String base = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        if ("CLAUDE".equals(resolveProtocol(baseUrl, protocol))) {
            if (base.endsWith("/messages")) return base;
            return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        }
        if (base.endsWith("/chat/completions")) return base;
        return base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    }

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
