package com.papertranslator;

import com.papertranslator.translate.TranslateController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class PaperTranslatorApplicationTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("translation.storage-dir", () -> storage.toString());
    }

    @Autowired
    TranslateController controller;

    @Test
    void contextStartsWithoutModelKeys() {
        assertNotNull(controller);
    }
}
