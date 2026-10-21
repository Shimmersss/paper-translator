package com.papertranslator.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.papertranslator.config.BabelDocConfig;
import com.papertranslator.config.LlmConfig;
import com.papertranslator.config.TranslationConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Effective configuration: settings page &gt; .env / environment &gt; built-in defaults.
 * Values are looked up on every use so a saved change applies to the next task.
 */
@Service
public class SettingsService implements Tuning {

    /** Environment variables that pin performance the pre-settings way; when any is set, tiers stay off by default. */
    static final List<String> PERFORMANCE_ENV = List.of(
            "BABELDOC_MAX_PAGES_PER_CHUNK", "TRANSLATION_MAX_QPS", "TRANSLATION_STABLE_QPS",
            "TRANSLATION_QUEUE_CAPACITY", "BABELDOC_TIMEOUT_SECONDS", "BABELDOC_RESOURCE_CGROUP_LIMIT_MIB",
            "BABELDOC_RESOURCE_MIN_AVAILABLE_MIB", "BABELDOC_RESOURCE_MAX_SWAP_USED_MIB");
    private static final List<String> MANUAL_FIELDS = List.of(
            "qps", "pagesPerChunk", "memoryLimitMiB", "timeoutSeconds", "queueCapacity");
    private static final String ENV_TIER = "env";

    private final LlmConfig llm;
    private final BabelDocConfig babel;
    private final TranslationConfig translation;
    private final Environment environment;
    private final SettingsStore store;
    private final HardwareInfo hardware;
    private final boolean readOnly;
    private volatile ObjectNode settings;

    @Autowired
    public SettingsService(LlmConfig llm, BabelDocConfig babel, TranslationConfig translation, Environment environment,
                           @Value("${server.address:127.0.0.1}") String serverAddress,
                           @Value("${SETTINGS_FILE:}") String settingsFile) {
        this(llm, babel, translation, environment, serverAddress,
                new SettingsStore(settingsPath(settingsFile, translation), new ObjectMapper()), HardwareProbe.detect());
    }

    SettingsService(LlmConfig llm, BabelDocConfig babel, TranslationConfig translation, Environment environment,
                    String serverAddress, SettingsStore store, HardwareInfo hardware) {
        this.llm = llm;
        this.babel = babel;
        this.translation = translation;
        this.environment = environment;
        this.store = store;
        this.hardware = hardware;
        this.readOnly = !isLoopback(serverAddress);
        this.settings = store.load();
    }

    private static Path settingsPath(String configured, TranslationConfig translation) {
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path storage = Path.of(translation.getStorageDir()).toAbsolutePath().normalize();
        Path parent = storage.getParent();
        return (parent == null ? storage : parent).resolve("settings.json");
    }

    // ---------------------------------------------------------------- providers

    public String pdfBaseUrl() { return text("pdf", "baseUrl", babel.getOpenaiBaseUrl()); }
    public String pdfApiKey() { return text("pdf", "apiKey", babel.getOpenaiApiKey()); }
    public String pdfModel() { return text("pdf", "model", babel.getOpenaiModel()); }
    public String imageBaseUrl() { return text("image", "baseUrl", llm.getApiUrl()); }
    public String imageApiKey() { return text("image", "apiKey", llm.getApiKey()); }
    public String imageModel() { return text("image", "model", llm.getModel()); }
    public String imageVisionModel() { return text("image", "visionModel", llm.getVisionModel()); }
    public String imageProtocol() { return text("image", "protocol", llm.getProtocol()); }

    // ---------------------------------------------------------------- performance

    public HardwareInfo hardware() {
        return hardware;
    }

    /** "auto", "max", "manual", or "env" when only environment variables configure performance. */
    public String tier() {
        String stored = text("performance", "tier", null);
        if (stored != null && !stored.isBlank()) return stored;
        return PERFORMANCE_ENV.stream().anyMatch(environment::containsProperty) ? ENV_TIER : PerformanceProfile.AUTO;
    }

    public PerformanceProfile profile() {
        String tier = tier();
        PerformanceProfile base = switch (tier) {
            case ENV_TIER -> envProfile();
            case PerformanceProfile.MAX -> PerformanceProfile.recommend(PerformanceProfile.MAX, hardware);
            default -> PerformanceProfile.recommend(PerformanceProfile.AUTO, hardware);
        };
        if (PerformanceProfile.MANUAL.equals(tier)) {
            base = new PerformanceProfile(
                    manual("qps", base.qps()), manual("pagesPerChunk", base.pagesPerChunk()),
                    manual("memoryLimitMiB", base.memoryLimitMiB()), base.minAvailableMiB(), base.maxSwapUsedMiB(),
                    manual("timeoutSeconds", base.timeoutSeconds()), manual("queueCapacity", base.queueCapacity()));
        }
        Integer providerCap = integer(settings.path("performance").path("providerMaxQps"));
        if (providerCap != null && providerCap < base.qps()) {
            base = new PerformanceProfile(providerCap, base.pagesPerChunk(), base.memoryLimitMiB(),
                    base.minAvailableMiB(), base.maxSwapUsedMiB(), base.timeoutSeconds(), base.queueCapacity());
        }
        return base;
    }

    private PerformanceProfile envProfile() {
        return new PerformanceProfile(Math.max(1, translation.getMaxQps()), Math.max(1, babel.getMaxPagesPerChunk()),
                babel.getResourceCgroupLimitMiB(), babel.getResourceMinAvailableMiB(),
                babel.getResourceMaxSwapUsedMiB(), babel.getTimeoutSeconds(), Math.max(1, translation.getQueueCapacity()));
    }

    private int manual(String field, int fallback) {
        Integer value = integer(settings.path("performance").path("manual").path(field));
        return value == null ? fallback : value;
    }

    @Override public int qps() { return profile().qps(); }

    @Override public int stableQps() {
        return ENV_TIER.equals(tier())
                ? Math.max(1, Math.min(translation.getStableQps(), qps()))
                : profile().stableQps();
    }

    @Override public int maxQps() { return Math.max(qps(), Math.max(1, translation.getMaxQps())); }
    @Override public int pagesPerChunk() { return profile().pagesPerChunk(); }
    @Override public int memoryLimitMiB() { return profile().memoryLimitMiB(); }
    @Override public int minAvailableMiB() { return profile().minAvailableMiB(); }
    @Override public int maxSwapUsedMiB() { return profile().maxSwapUsedMiB(); }
    @Override public int timeoutSeconds() { return profile().timeoutSeconds(); }
    @Override public int queueCapacity() { return profile().queueCapacity(); }

    @Override public int maxHistory() {
        Integer stored = integer(settings.path("storage").path("maxHistory"));
        return stored != null ? stored : Math.max(1, translation.getMaxHistory());
    }

    // ---------------------------------------------------------------- view

    public boolean readOnly() {
        return readOnly;
    }

    /** Everything the settings page shows; API keys only as their last four characters. */
    public Map<String, Object> view() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("readOnly", readOnly);
        result.put("settingsFile", store.file().toString());

        Map<String, Object> pdf = new LinkedHashMap<>();
        pdf.put("baseUrl", field(pdfBaseUrl(), source("pdf", "baseUrl", "BABELDOC_OPENAI_BASE_URL")));
        pdf.put("apiKey", secret(pdfApiKey(), source("pdf", "apiKey", "BABELDOC_OPENAI_API_KEY")));
        pdf.put("model", field(pdfModel(), source("pdf", "model", "BABELDOC_OPENAI_MODEL")));
        result.put("pdf", pdf);

        Map<String, Object> image = new LinkedHashMap<>();
        image.put("baseUrl", field(imageBaseUrl(), source("image", "baseUrl", "LLM_API_URL")));
        image.put("apiKey", secret(imageApiKey(), source("image", "apiKey", "LLM_API_KEY")));
        image.put("model", field(imageModel(), source("image", "model", "LLM_MODEL")));
        image.put("visionModel", field(imageVisionModel(), source("image", "visionModel", "LLM_VISION_MODEL")));
        image.put("protocol", field(imageProtocol(), source("image", "protocol", "LLM_PROTOCOL")));
        result.put("image", image);

        Map<String, Object> performance = new LinkedHashMap<>();
        String tier = tier();
        performance.put("tier", field(tier, settings.path("performance").has("tier") ? "settings"
                : ENV_TIER.equals(tier) ? "env" : "default"));
        performance.put("effective", profileMap(profile(), stableQps()));
        Map<String, Object> manual = new LinkedHashMap<>();
        for (String name : MANUAL_FIELDS) {
            manual.put(name, integer(settings.path("performance").path("manual").path(name)));
        }
        performance.put("manual", manual);
        performance.put("providerMaxQps", integer(settings.path("performance").path("providerMaxQps")));
        Map<String, Object> recommended = new LinkedHashMap<>();
        PerformanceProfile auto = PerformanceProfile.recommend(PerformanceProfile.AUTO, hardware);
        PerformanceProfile max = PerformanceProfile.recommend(PerformanceProfile.MAX, hardware);
        recommended.put(PerformanceProfile.AUTO, profileMap(auto, auto.stableQps()));
        recommended.put(PerformanceProfile.MAX, profileMap(max, max.stableQps()));
        performance.put("recommended", recommended);
        Map<String, Object> ranges = new LinkedHashMap<>();
        ranges.put("qps", PerformanceProfile.QPS);
        ranges.put("pagesPerChunk", PerformanceProfile.PAGES_PER_CHUNK);
        ranges.put("memoryLimitMiB", PerformanceProfile.memoryLimitRange(hardware));
        ranges.put("timeoutSeconds", PerformanceProfile.TIMEOUT_SECONDS);
        ranges.put("queueCapacity", PerformanceProfile.QUEUE_CAPACITY);
        ranges.put("providerMaxQps", PerformanceProfile.PROVIDER_MAX_QPS);
        performance.put("ranges", ranges);
        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("cores", hardware.cores());
        hw.put("memoryMiB", hardware.memoryMiB());
        hw.put("detected", hardware.detected());
        performance.put("hardware", hw);
        result.put("performance", performance);

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("maxHistory", field(maxHistory(), source("storage", "maxHistory", "TRANSLATION_MAX_HISTORY")));
        storage.put("maxHistoryRange", PerformanceProfile.MAX_HISTORY);
        result.put("storage", storage);
        return result;
    }

    private static Map<String, Object> profileMap(PerformanceProfile profile, int stableQps) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("qps", profile.qps());
        map.put("stableQps", stableQps);
        map.put("pagesPerChunk", profile.pagesPerChunk());
        map.put("memoryLimitMiB", profile.memoryLimitMiB());
        map.put("timeoutSeconds", profile.timeoutSeconds());
        map.put("queueCapacity", profile.queueCapacity());
        return map;
    }

    private static Map<String, Object> field(Object value, String source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("value", value);
        map.put("source", source);
        return map;
    }

    private static Map<String, Object> secret(String value, String source) {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean set = value != null && !value.isBlank();
        map.put("set", set);
        map.put("hint", set ? mask(value) : "");
        map.put("source", source);
        return map;
    }

    static String mask(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 4 ? "••••" : "••••" + trimmed.substring(trimmed.length() - 4);
    }

    private String source(String group, String name, String envName) {
        if (settings.path(group).has(name)) return "settings";
        return environment.containsProperty(envName) ? "env" : "default";
    }

    // ---------------------------------------------------------------- update

    /**
     * Applies a partial update. A missing field is left alone, {@code null} removes the
     * page's value (falling back to .env / default), and an empty API key means "unchanged".
     */
    public synchronized void update(JsonNode body) throws IOException {
        if (body == null || !body.isObject()) {
            throw new SettingsValidationException(Map.of("body", "请求体必须是 JSON 对象"));
        }
        ObjectNode next = settings.deepCopy();
        Map<String, String> errors = new LinkedHashMap<>();

        applyText(next, body, "pdf", "baseUrl", errors, SettingsService::validUrl);
        applySecret(next, body, "pdf", "apiKey");
        applyText(next, body, "pdf", "model", errors, SettingsService::validModel);
        applyText(next, body, "image", "baseUrl", errors, SettingsService::validUrl);
        applySecret(next, body, "image", "apiKey");
        applyText(next, body, "image", "model", errors, SettingsService::validModel);
        applyText(next, body, "image", "visionModel", errors, value -> null);
        applyText(next, body, "image", "protocol", errors, value ->
                List.of("auto", "openai", "claude").contains(value.toLowerCase(Locale.ROOT)) ? null : "协议只能是 auto、openai 或 claude");

        JsonNode performance = body.path("performance");
        if (performance.isObject()) {
            applyText(next, body, "performance", "tier", errors, value ->
                    List.of(PerformanceProfile.AUTO, PerformanceProfile.MAX, PerformanceProfile.MANUAL).contains(value)
                            ? null : "档位只能是 auto、max 或 manual");
            applyInteger(performance, "providerMaxQps", (ObjectNode) group(next, "performance"),
                    PerformanceProfile.PROVIDER_MAX_QPS, "performance.providerMaxQps", errors);
            JsonNode manual = performance.path("manual");
            if (manual.isObject()) {
                ObjectNode target = (ObjectNode) group(group(next, "performance"), "manual");
                applyInteger(manual, "qps", target, PerformanceProfile.QPS, "performance.manual.qps", errors);
                applyInteger(manual, "pagesPerChunk", target, PerformanceProfile.PAGES_PER_CHUNK,
                        "performance.manual.pagesPerChunk", errors);
                applyInteger(manual, "memoryLimitMiB", target, PerformanceProfile.memoryLimitRange(hardware),
                        "performance.manual.memoryLimitMiB", errors);
                applyInteger(manual, "timeoutSeconds", target, PerformanceProfile.TIMEOUT_SECONDS,
                        "performance.manual.timeoutSeconds", errors);
                applyInteger(manual, "queueCapacity", target, PerformanceProfile.QUEUE_CAPACITY,
                        "performance.manual.queueCapacity", errors);
            } else if (manual.isNull()) {
                ((ObjectNode) group(next, "performance")).remove("manual");
            }
        }
        JsonNode storage = body.path("storage");
        if (storage.isObject()) {
            applyInteger(storage, "maxHistory", (ObjectNode) group(next, "storage"),
                    PerformanceProfile.MAX_HISTORY, "storage.maxHistory", errors);
        }
        if (!errors.isEmpty()) throw new SettingsValidationException(errors);
        prune(next);
        store.save(next);
        settings = next;
    }

    private interface Check {
        String problem(String value);
    }

    private void applyText(ObjectNode next, JsonNode body, String group, String name, Map<String, String> errors,
                           Check check) {
        JsonNode section = body.path(group);
        if (!section.isObject() || !section.has(name)) return;
        JsonNode value = section.get(name);
        ObjectNode target = (ObjectNode) group(next, group);
        if (value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            target.remove(name);
            return;
        }
        if (!value.isTextual()) {
            errors.put(group + "." + name, label(group, name) + "必须是文本");
            return;
        }
        String trimmed = value.asText().trim();
        String problem = check.problem(trimmed);
        if (problem != null) {
            errors.put(group + "." + name, problem);
            return;
        }
        target.put(name, trimmed);
    }

    private void applySecret(ObjectNode next, JsonNode body, String group, String name) {
        JsonNode section = body.path(group);
        if (!section.isObject() || !section.has(name)) return;
        JsonNode value = section.get(name);
        ObjectNode target = (ObjectNode) group(next, group);
        if (value.isNull()) {
            target.remove(name);
        } else if (value.isTextual() && !value.asText().isBlank()) {
            target.put(name, value.asText().trim());
        }
    }

    private static void applyInteger(JsonNode source, String name, ObjectNode target,
                                     PerformanceProfile.Range range, String path, Map<String, String> errors) {
        if (!source.has(name)) return;
        JsonNode value = source.get(name);
        if (value.isNull()) {
            target.remove(name);
            return;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber()) {
            errors.put(path, "必须是整数");
            return;
        }
        if (!range.contains(value.asInt())) {
            errors.put(path, "必须在 " + range.min() + "–" + range.max() + " 之间");
            return;
        }
        target.put(name, value.asInt());
    }

    private static JsonNode group(JsonNode parent, String name) {
        ObjectNode object = (ObjectNode) parent;
        if (!(object.get(name) instanceof ObjectNode)) object.putObject(name);
        return object.get(name);
    }

    private static void prune(ObjectNode node) {
        var names = node.fieldNames();
        List<String> empty = new java.util.ArrayList<>();
        while (names.hasNext()) {
            String name = names.next();
            if (node.get(name) instanceof ObjectNode child) {
                prune(child);
                if (child.isEmpty()) empty.add(name);
            }
        }
        empty.forEach(node::remove);
    }

    private static String validUrl(String value) {
        try {
            URI uri = URI.create(value);
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            return http && uri.getHost() != null ? null : "地址必须是 http:// 或 https:// 开头的完整 URL";
        } catch (IllegalArgumentException e) {
            return "地址必须是 http:// 或 https:// 开头的完整 URL";
        }
    }

    private static String validModel(String value) {
        return value.length() <= 200 ? null : "模型名过长";
    }

    private static String label(String group, String name) {
        return group + "." + name;
    }

    // ---------------------------------------------------------------- helpers

    private String text(String group, String name, String fallback) {
        JsonNode value = settings.path(group).path(name);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static Integer integer(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) return false;
        String host = address.trim();
        if (host.equalsIgnoreCase("localhost")) return true;
        try {
            return InetAddress.getByName(host.replaceAll("^\\[|]$", "")).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
