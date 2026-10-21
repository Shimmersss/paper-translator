package com.papertranslator.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertranslator.config.BabelDocConfig;
import com.papertranslator.config.LlmConfig;
import com.papertranslator.config.TranslationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsServiceTest {

    private static final HardwareInfo LAPTOP = new HardwareInfo(8, 16 * 1024, true);
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SettingsService service(MockEnvironment environment, BabelDocConfig babel) {
        LlmConfig llm = new LlmConfig();
        TranslationConfig translation = new TranslationConfig();
        return new SettingsService(llm, babel, translation, environment, "127.0.0.1",
                new SettingsStore(tempDir.resolve("settings.json"), mapper), LAPTOP);
    }

    private SettingsService service() {
        return service(new MockEnvironment(), new BabelDocConfig());
    }

    @Test
    void settingsPageOverridesEnvironmentWhichOverridesDefaults() throws Exception {
        BabelDocConfig babel = new BabelDocConfig();
        babel.setOpenaiModel("env-model");
        MockEnvironment environment = new MockEnvironment().withProperty("BABELDOC_OPENAI_MODEL", "env-model");
        SettingsService settings = service(environment, babel);

        assertEquals("env-model", settings.pdfModel());
        assertEquals("env", source(settings, "pdf", "model"));
        assertEquals("default", source(settings, "pdf", "baseUrl"));

        settings.update(mapper.readTree("{\"pdf\":{\"model\":\"page-model\"}}"));
        assertEquals("page-model", settings.pdfModel());
        assertEquals("settings", source(settings, "pdf", "model"));

        settings.update(mapper.readTree("{\"pdf\":{\"model\":null}}"));
        assertEquals("env-model", settings.pdfModel());
    }

    @Test
    void emptyKeyMeansUnchangedAndNullRemovesIt() throws Exception {
        SettingsService settings = service();
        settings.update(mapper.readTree("{\"pdf\":{\"apiKey\":\"sk-test-123456\"}}"));
        settings.update(mapper.readTree("{\"pdf\":{\"apiKey\":\"\",\"model\":\"m\"}}"));
        assertEquals("sk-test-123456", settings.pdfApiKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> key = (Map<String, Object>) ((Map<String, Object>) settings.view().get("pdf")).get("apiKey");
        assertEquals("••••3456", key.get("hint"));
        assertFalse(mapper.writeValueAsString(settings.view()).contains("sk-test-123456"));

        settings.update(mapper.readTree("{\"pdf\":{\"apiKey\":null}}"));
        assertEquals("", settings.pdfApiKey());
    }

    @Test
    void persistsOwnerOnlyAndSurvivesReload() throws Exception {
        SettingsService settings = service();
        settings.update(mapper.readTree("{\"storage\":{\"maxHistory\":42}}"));
        Path file = tempDir.resolve("settings.json");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        assertEquals(42, service().maxHistory());
    }

    @Test
    void rejectsInvalidValuesFieldByFieldWithoutSavingAnything() throws Exception {
        SettingsService settings = service();
        SettingsValidationException error = assertThrows(SettingsValidationException.class, () -> settings.update(
                mapper.readTree("{\"pdf\":{\"baseUrl\":\"ftp://x\",\"model\":\"ok\"},"
                        + "\"performance\":{\"tier\":\"turbo\",\"manual\":{\"qps\":0}}}")));
        assertEquals(java.util.Set.of("pdf.baseUrl", "performance.tier", "performance.manual.qps"),
                error.fieldErrors().keySet());
        assertFalse(Files.exists(tempDir.resolve("settings.json")));
        assertNotEquals("ok", settings.pdfModel());
    }

    @Test
    void defaultsToTheAutoTierButKeepsEnvironmentPerformanceForExistingSetups() {
        assertEquals("auto", service().tier());
        assertEquals(PerformanceProfile.recommend("auto", LAPTOP), service().profile());

        BabelDocConfig babel = new BabelDocConfig();
        babel.setMaxPagesPerChunk(2);
        SettingsService envConfigured = service(
                new MockEnvironment().withProperty("BABELDOC_MAX_PAGES_PER_CHUNK", "2"), babel);
        assertEquals("env", envConfigured.tier());
        assertEquals(2, envConfigured.pagesPerChunk());
    }

    @Test
    void manualTierOverridesOnlyTheFieldsGivenAndProviderCapLimitsConcurrency() throws Exception {
        SettingsService settings = service();
        settings.update(mapper.readTree("{\"performance\":{\"tier\":\"manual\",\"manual\":{\"pagesPerChunk\":7}}}"));
        PerformanceProfile auto = PerformanceProfile.recommend("auto", LAPTOP);
        assertEquals(7, settings.pagesPerChunk());
        assertEquals(auto.qps(), settings.qps());

        settings.update(mapper.readTree("{\"performance\":{\"providerMaxQps\":2}}"));
        assertEquals(2, settings.qps());
        assertEquals(1, settings.stableQps());
        assertTrue(settings.maxQps() >= 2);
    }

    @Test
    void readOnlyWhenListeningBeyondThisMachine() {
        assertFalse(SettingsService.isLoopback("0.0.0.0"));
        assertTrue(SettingsService.isLoopback("127.0.0.1"));
        assertTrue(SettingsService.isLoopback("::1"));
        assertTrue(SettingsService.isLoopback("localhost"));
        SettingsService exposed = new SettingsService(new LlmConfig(), new BabelDocConfig(), new TranslationConfig(),
                new MockEnvironment(), "0.0.0.0", new SettingsStore(tempDir.resolve("s.json"), mapper), LAPTOP);
        assertTrue(exposed.readOnly());
    }

    @Test
    void movesAnUnreadableFileAsideInsteadOfOverwritingIt() throws Exception {
        Files.writeString(tempDir.resolve("settings.json"), "{not json");
        SettingsService settings = service();
        assertEquals("auto", settings.tier());
        try (var files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("settings.json.invalid-")));
        }
    }

    @SuppressWarnings("unchecked")
    private static String source(SettingsService settings, String group, String name) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) settings.view().get(group)).get(name)).get("source");
    }
}
