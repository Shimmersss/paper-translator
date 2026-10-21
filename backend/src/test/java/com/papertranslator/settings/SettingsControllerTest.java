package com.papertranslator.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertranslator.config.BabelDocConfig;
import com.papertranslator.config.LlmConfig;
import com.papertranslator.config.TranslationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SettingsControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SettingsController controller() {
        SettingsService settings = new SettingsService(new LlmConfig(), new BabelDocConfig(), new TranslationConfig(),
                new MockEnvironment(), "127.0.0.1", new SettingsStore(tempDir.resolve("settings.json"), mapper),
                new HardwareInfo(8, 16384, true));
        return new SettingsController(settings, mock(ConnectionTester.class));
    }

    private static MockHttpServletRequest request(String remote, String host, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/settings");
        request.setRemoteAddr(remote);
        request.addHeader("Host", host);
        if (origin != null) request.addHeader("Origin", origin);
        return request;
    }

    private int status(MockHttpServletRequest request) throws Exception {
        return controller().update(mapper.readTree("{\"storage\":{\"maxHistory\":9}}"), request).getStatusCode().value();
    }

    @Test
    void acceptsWritesFromThisMachine() throws Exception {
        assertEquals(200, status(request("127.0.0.1", "127.0.0.1:8080", "http://127.0.0.1:8080")));
        assertEquals(200, status(request("127.0.0.1", "localhost:5173", "http://localhost:5173")));
        assertEquals(200, status(request("::1", "[::1]:8080", null)));
    }

    @Test
    void refusesOtherMachinesOtherSitesAndRebindingHosts() throws Exception {
        assertEquals(403, status(request("192.168.1.20", "127.0.0.1:8080", null)));
        assertEquals(403, status(request("127.0.0.1", "127.0.0.1:8080", "https://evil.example")));
        assertEquals(403, status(request("127.0.0.1", "rebind.evil.example:8080", null)));
    }

    @Test
    void reportsFieldErrors() throws Exception {
        var response = controller().update(mapper.readTree("{\"storage\":{\"maxHistory\":0}}"),
                request("127.0.0.1", "127.0.0.1:8080", null));
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("storage.maxHistory"));
    }

    @Test
    void connectionErrorsNeverEchoTheKey() {
        String scrubbed = ConnectionTester.scrub(
                "{\"error\":\"invalid key sk-abcdef1234567890 (Bearer sk-abcdef1234567890)\"}", "sk-abcdef1234567890");
        assertFalse(scrubbed.contains("abcdef1234567890"));
    }
}
