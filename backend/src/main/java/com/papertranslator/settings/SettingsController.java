package com.papertranslator.settings;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings page API. There are no accounts, so writes are accepted only from this
 * machine, only as JSON (which a cross-site form cannot send without a CORS
 * preflight), and only when Origin/Host name this machine (DNS rebinding).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final ConnectionTester tester;

    public SettingsController(SettingsService settings, ConnectionTester tester) {
        this.settings = settings;
        this.tester = tester;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        return ResponseEntity.ok(Map.of("code", 200, "data", settings.view()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@RequestBody JsonNode body, HttpServletRequest request) {
        ResponseEntity<?> denied = denyWrite(request);
        if (denied != null) return denied;
        try {
            settings.update(body);
            return ResponseEntity.ok(Map.of("code", 200, "data", settings.view()));
        } catch (SettingsValidationException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 400);
            error.put("message", e.getMessage());
            error.put("fieldErrors", e.fieldErrors());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "保存设置失败：" + e.getMessage()));
        }
    }

    /** Tests the given values, falling back to the saved ones for fields left empty. */
    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> test(@RequestBody JsonNode body, HttpServletRequest request) {
        ResponseEntity<?> denied = denyWrite(request);
        if (denied != null) return denied;
        boolean image = "image".equals(body.path("kind").asText());
        String baseUrl = value(body, "baseUrl", image ? settings.imageBaseUrl() : settings.pdfBaseUrl());
        String apiKey = value(body, "apiKey", image ? settings.imageApiKey() : settings.pdfApiKey());
        String model = image
                ? value(body, "visionModel", value(body, "model", firstNonBlank(settings.imageVisionModel(), settings.imageModel())))
                : value(body, "model", settings.pdfModel());
        String protocol = image ? value(body, "protocol", settings.imageProtocol()) : "openai";
        return ResponseEntity.ok(Map.of("code", 200, "data", tester.test(baseUrl, apiKey, model, protocol)));
    }

    private ResponseEntity<?> denyWrite(HttpServletRequest request) {
        if (settings.readOnly()) {
            return forbidden("服务对外开放时设置页只读，请修改 .env 并在前面加认证代理");
        }
        if (!SettingsService.isLoopback(request.getRemoteAddr())) {
            return forbidden("只能在运行服务的这台电脑上修改设置");
        }
        if (!isLocalHost(request.getHeader("Host"))) {
            return forbidden("请通过 127.0.0.1 或 localhost 访问后再修改设置");
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !"null".equals(origin) && !isLocalHost(hostOf(origin))) {
            return forbidden("拒绝来自其他网站的修改请求");
        }
        return null;
    }

    static boolean isLocalHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return false;
        String host = hostHeader.trim();
        if (host.startsWith("[")) {
            host = host.substring(1, host.indexOf(']') > 0 ? host.indexOf(']') : host.length());
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.contains(":")) {
            host = host.substring(0, host.indexOf(':'));
        }
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private static String hostOf(String origin) {
        try {
            URI uri = URI.create(origin);
            return uri.getHost() == null ? "" : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static ResponseEntity<?> forbidden(String message) {
        return ResponseEntity.status(403).body(Map.of("code", 403, "message", message));
    }

    private static String value(JsonNode body, String name, String fallback) {
        JsonNode node = body.path(name);
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : fallback;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
