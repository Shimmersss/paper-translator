package com.papertranslator.translate;

import com.papertranslator.config.LlmConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageTranslationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recognizesVisionBlocksAndWritesImageAndPdfResults() throws Exception {
        Path input = tempDir.resolve("poster.png");
        BufferedImage source = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();
        ImageIO.write(source, "png", input.toFile());
        Path resultDir = Files.createDirectories(tempDir.resolve("result"));

        LlmService llmService = mock(LlmService.class);
        when(llmService.completeWithImages(any(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn("{\"items\":[{\"source\":\"Hello\",\"translation\":\"你好\",\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.2}]}");

        LlmConfig llmConfig = new LlmConfig();
        llmConfig.setVisionModel("vision-model");
        ImageTranslationService service = new ImageTranslationService(llmService, llmConfig);
        service.translateImage(input, resultDir, "poster.png", "auto", ignored -> {});

        assertTrue(Files.isRegularFile(resultDir.resolve("translated.png")));
        assertTrue(Files.isRegularFile(resultDir.resolve("bilingual.png")));
        assertTrue(Files.isRegularFile(resultDir.resolve("translated.pdf")));
        assertEquals("你好", Files.readString(resultDir.resolve("translated.txt")));
        assertEquals(320, ImageIO.read(resultDir.resolve("translated.png").toFile()).getWidth());
        try (PDDocument document = Loader.loadPDF(resultDir.resolve("translated.pdf").toFile())) {
            assertEquals(1, document.getNumberOfPages());
        }
        verify(llmService).completeWithImages(eq("vision-model"), contains("image text translation engine"), contains("poster.png"), anyList(), eq(12_000));
    }

    @Test
    void retriesWhenVisionModelReturnsNonJsonAndAcceptsVerboseJsonResponse() throws Exception {
        Path input = tempDir.resolve("repair.png");
        BufferedImage source = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(source, "png", input.toFile());
        Path resultDir = Files.createDirectories(tempDir.resolve("repair-result"));

        LlmService llmService = mock(LlmService.class);
        when(llmService.completeWithImages(any(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn("I found some text, but here is the result:",
                        "The complete answer is: {\"items\":[{\"source\":\"Hello\",\"translation\":\"你好\",\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.2}]}.");

        LlmConfig llmConfig = new LlmConfig();
        llmConfig.setVisionModel("vision-model");
        ImageTranslationService service = new ImageTranslationService(llmService, llmConfig);
        service.translateImage(input, resultDir, "repair.png", "auto", ignored -> {});

        assertEquals("你好", Files.readString(resultDir.resolve("translated.txt")));
        verify(llmService, times(2)).completeWithImages(eq("vision-model"), anyString(), anyString(), anyList(), eq(12_000));
    }

    @Test
    void acceptsValidJsonAfterACompleteNonJsonPrefixCandidate() throws Exception {
        Path input = tempDir.resolve("prefixed-response.png");
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", input.toFile());
        Path resultDir = Files.createDirectories(tempDir.resolve("prefixed-response-result"));
        LlmService llmService = mock(LlmService.class);
        when(llmService.completeWithImages(any(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn("说明：[{\"type\":\"metadata\"}] {\"items\":[{\"translation\":\"你好\",\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.2}]}");

        LlmConfig llmConfig = new LlmConfig();
        llmConfig.setVisionModel("vision-model");
        ImageTranslationService service = new ImageTranslationService(llmService, llmConfig);
        service.translateImage(input, resultDir, "prefixed-response.png", "auto", ignored -> {});

        assertEquals("你好", Files.readString(resultDir.resolve("translated.txt")));
        verify(llmService).completeWithImages(eq("vision-model"), anyString(), anyString(), anyList(), eq(12_000));
    }

    @Test
    void rejectsOversizedVisionResponseBeforeQuadraticCandidateScanning() throws Exception {
        Path input = tempDir.resolve("bounded-response.png");
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", input.toFile());
        Path resultDir = Files.createDirectories(tempDir.resolve("bounded-response-result"));
        LlmService llmService = mock(LlmService.class);
        String oversized = "{".repeat(64 * 1024 + 1);
        when(llmService.completeWithImages(any(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(oversized, oversized);

        LlmConfig llmConfig = new LlmConfig();
        llmConfig.setVisionModel("vision-model");
        ImageTranslationService service = new ImageTranslationService(llmService, llmConfig);

        assertThrows(Exception.class,
                () -> service.translateImage(input, resultDir, "bounded-response.png", "auto", ignored -> {}));
        verify(llmService).completeWithImages(eq("vision-model"), anyString(), anyString(), anyList(), eq(12_000));
    }
}
