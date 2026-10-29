package com.papertranslator.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertranslator.config.TranslationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranslationServiceTest {

    @Test
    void hidesBabelDocDiagnosticsFromUsers() {
        String raw = "BabelDOC 执行失败: MuPDF error: cannot parse object (5 0 R) "
                + "Traceback (most recent call last): /home/admin/web-homepage";

        assertEquals("PDF 文件无法解析，请更换文件后重试",
                TranslationService.userFacingErrorMessage(new IllegalStateException(raw)));
        assertEquals("翻译失败，请稍后重试",
                TranslationService.userFacingErrorMessage(new IllegalStateException("connection reset")));
    }

    @TempDir
    Path tempDir;

    @Test
    void keepsOnlyTheConfiguredNumberOfFinishedTasks() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(2);
        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(1);

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            List<String> taskIds = new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                TranslationSession session = service.createSessionPreview(
                        "paper-" + index + ".pdf", new ByteArrayInputStream("pdf".getBytes()));
                session.setStatus("completed");
                taskIds.add(session.getTaskId());
                Thread.sleep(2);
            }
            service.cleanupHistory();
            List<TranslationSession> recent = service.getRecentSessions();
            assertEquals(2, recent.size());
            assertEquals(taskIds.get(3), recent.get(0).getTaskId());
            assertFalse(Files.exists(tempDir.resolve(taskIds.get(0))));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void rejectsSubmissionWhenTheModelKeyIsMissing() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(2);
        when(babelDocService.configurationProblem()).thenReturn(java.util.Optional.of("未配置 BABELDOC_OPENAI_API_KEY"));

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            TranslationSession session = service.createSessionPreview(
                    "paper.pdf", new ByteArrayInputStream("pdf".getBytes()));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.startTranslation(session.getTaskId(), 1, 2, "auto", 2));
            assertTrue(error.getMessage().contains("BABELDOC_OPENAI_API_KEY"));
            assertEquals("preview", service.getSession(session.getTaskId()).getStatus());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void appliesAQueueCapacityChangeWithoutARestart() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(babelDocService.translatePdf(any(), any(), anyString(), anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    Path resultDir = invocation.getArgument(1);
                    Files.writeString(resultDir.resolve("translated.pdf"), "t");
                    Files.writeString(resultDir.resolve("bilingual.pdf"), "b");
                    return new BabelDocService.TranslationResult(resultDir.resolve("translated.pdf"), resultDir.resolve("bilingual.pdf"));
                });
        java.util.concurrent.atomic.AtomicInteger capacity = new java.util.concurrent.atomic.AtomicInteger(1);
        com.papertranslator.settings.Tuning base = com.papertranslator.settings.Tuning.fromConfig(new com.papertranslator.config.BabelDocConfig(), config);
        com.papertranslator.settings.Tuning tuning = new com.papertranslator.settings.Tuning() {
            @Override public int qps() { return 2; }
            @Override public int stableQps() { return 1; }
            @Override public int maxQps() { return 4; }
            @Override public int pagesPerChunk() { return 1; }
            @Override public int memoryLimitMiB() { return base.memoryLimitMiB(); }
            @Override public int minAvailableMiB() { return base.minAvailableMiB(); }
            @Override public int maxSwapUsedMiB() { return base.maxSwapUsedMiB(); }
            @Override public int timeoutSeconds() { return base.timeoutSeconds(); }
            @Override public int queueCapacity() { return capacity.get(); }
            @Override public int maxHistory() { return 20; }
        };
        TranslationService service = new TranslationService(pdfParseService, babelDocService, config, new ObjectMapper(), null, tuning);
        service.initialize();
        try {
            List<String> ids = new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                ids.add(service.createSessionPreview("p" + index + ".pdf", new ByteArrayInputStream("pdf".getBytes())).getTaskId());
            }
            service.startTranslation(ids.get(0), 1, 1, "auto", service.defaultQps());
            awaitStatus(service, ids.get(0), "translating");
            service.startTranslation(ids.get(1), 1, 1, "auto", service.defaultQps());
            assertThrows(IllegalStateException.class, () -> service.startTranslation(ids.get(2), 1, 1, "auto", 2));
            assertEquals("preview", service.getSession(ids.get(2)).getStatus());

            capacity.set(2);
            service.startTranslation(ids.get(2), 1, 1, "auto", 2);
            assertEquals("queued", service.getSession(ids.get(2)).getStatus());
            assertEquals(2, service.getSession(ids.get(1)).getQps());

            release.countDown();
            for (String id : ids.subList(0, 3)) awaitStatus(service, id, "completed");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void queuesTranslationAndKeepsRecentResultFilesOnDisk() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(5);
        config.setQueueCapacity(2);
        config.setMaxQps(4);

        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(3);
        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    // One paragraph kept its source text: delivered with a warning.
                    return new BabelDocService.TranslationResult(translated, bilingual,
                            new BabelDocService.ParagraphStats(60, 1, Map.of(2, 1)));
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        String taskId = null;
        try {
            TranslationSession session = service.createSessionPreview(
                    "paper.pdf", new ByteArrayInputStream("pdf".getBytes()));
            taskId = session.getTaskId();
            assertTrue(Files.isRegularFile(session.getInputPdfPath()));

            service.startTranslation(session.getTaskId(), 1, 3, "auto", 4);
            awaitStatus(service, session.getTaskId(), "completed");

            TranslationSession completed = service.getSession(session.getTaskId());
            assertTrue(Files.isRegularFile(completed.getTranslatedPdfPath()));
            assertTrue(Files.isRegularFile(completed.getBilingualPdfPath()));
            assertEquals("第 2 页有 1 段翻译失败，已保留原文", completed.getKeptSourceWarning());
            assertEquals("paper-翻译版.pdf", service.buildPdfDownloadFileName(session.getTaskId(), "translated"));
            assertEquals("paper-双语对照版.pdf", service.buildPdfDownloadFileName(session.getTaskId(), "bilingual"));
            assertEquals("paper-翻译结果.txt", service.buildTextDownloadFileName(session.getTaskId()));
            assertEquals(session.getTaskId(), service.getRecentSessions().get(0).getTaskId());

            service.createSessionPreview("not-submitted.pdf", new ByteArrayInputStream("pdf".getBytes()));
            assertEquals(1, service.getRecentSessions().size());
            assertEquals(session.getTaskId(), service.getRecentSessions().get(0).getTaskId());
        } finally {
            service.shutdown();
        }

        TranslationService restoredService = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        restoredService.initialize();
        try {
            assertNotNull(taskId);
            assertEquals(taskId, restoredService.getRecentSessions().get(0).getTaskId());
            assertEquals("completed", restoredService.getSession(taskId).getStatus());
            assertEquals("第 2 页有 1 段翻译失败，已保留原文", restoredService.getSession(taskId).getKeptSourceWarning());
        } finally {
            restoredService.shutdown();
        }
    }

    @Test
    void resumesQueuedTaskFromDiskAfterRestart() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(5);
        config.setQueueCapacity(2);
        config.setMaxQps(4);

        Path taskDir = Files.createDirectories(tempDir.resolve("resume01"));
        Files.writeString(taskDir.resolve("input.pdf"), "pdf");
        TranslationSession queued = new TranslationSession("resume01", "paper.pdf", taskDir);
        queued.setTotalPages(3);
        queued.setPageRange(1, 2);
        queued.setStatus("queued");
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(taskDir.resolve("task.json").toFile(), queued);

        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    return new BabelDocService.TranslationResult(translated, bilingual);
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            awaitStatus(service, "resume01", "completed");
            verify(babelDocService).translatePdf(
                    eq(queued.getInputPdfPath()), eq(taskDir), eq("paper.pdf"), eq(1), eq(2), eq("auto"), eq(4), any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void resumesTaskLeftInCreatingStateAfterRestart() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(2);
        Path taskDir = Files.createDirectories(tempDir.resolve("creating01"));
        Files.writeString(taskDir.resolve("input.pdf"), "pdf");
        TranslationSession creating = new TranslationSession("creating01", "paper.pdf", taskDir);
        creating.setTotalPages(2);
        creating.setPageRange(1, 2);
        creating.setStatus("creating");
        creating.setProgressStage("creating");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(creating.getMetadataPath().toFile(), creating);
        when(babelDocService.translatePdf(any(), any(), anyString(), anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    return new BabelDocService.TranslationResult(translated, bilingual);
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            awaitStatus(service, "creating01", "completed");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void failsRecoveredTaskWhenItsOriginalFileIsMissing() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(2);

        Path taskDir = Files.createDirectories(tempDir.resolve("missing-input"));
        TranslationSession queued = new TranslationSession("missing-input", "paper.pdf", taskDir);
        queued.setTotalPages(2);
        queued.setPageRange(1, 2);
        queued.setStatus("queued");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(queued.getMetadataPath().toFile(), queued);

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            TranslationSession recovered = service.getSession("missing-input");
            assertEquals("error", recovered.getStatus());
            assertTrue(recovered.getErrorMessage().contains("未找到原始文件"));
            verify(babelDocService, never()).translatePdf(any(), any(), anyString(), anyInt(), anyInt(), anyString(), anyInt(), any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void downgradesAcceleratedTranslationOnceWhenResourcePressureIsDetected() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(5);
        config.setQueueCapacity(2);
        config.setMaxQps(4);
        config.setStableQps(2);

        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(3);
        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    int qps = invocation.getArgument(6);
                    if (qps == 4) {
                        throw new BabelDocService.ResourcePressureException("检测到服务器内存压力");
                    }
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    return new BabelDocService.TranslationResult(translated, bilingual);
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            TranslationSession session = service.createSessionPreview(
                    "paper.pdf", new ByteArrayInputStream("pdf".getBytes()));
            service.startTranslation(session.getTaskId(), 1, 3, "auto", 4);
            awaitStatus(service, session.getTaskId(), "completed");

            TranslationSession completed = service.getSession(session.getTaskId());
            assertEquals(4, completed.getRequestedQps());
            assertEquals(2, completed.getQps());
            assertTrue(completed.isResourceDowngraded());
            assertEquals(1, completed.getResourceDowngradeCount());
            verify(babelDocService).translatePdf(
                    any(Path.class), any(Path.class), eq("paper.pdf"), eq(1), eq(3), eq("auto"), eq(4), any());
            verify(babelDocService).translatePdf(
                    any(Path.class), any(Path.class), eq("paper.pdf"), eq(1), eq(3), eq("auto"), eq(2), any());
            verify(babelDocService).awaitResourceRecovery();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void fallsBackToSinglePageBatchesWhenStableQpsStillHitsResourcePressure() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(5);
        config.setQueueCapacity(2);
        config.setMaxQps(4);
        config.setStableQps(2);

        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(3);
        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenThrow(new BabelDocService.ResourcePressureException("内存压力", 4))
                .thenThrow(new BabelDocService.ResourcePressureException("内存压力", 2));
        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    return new BabelDocService.TranslationResult(translated, bilingual);
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            TranslationSession session = service.createSessionPreview(
                    "paper.pdf", new ByteArrayInputStream("pdf".getBytes()));
            service.startTranslation(session.getTaskId(), 1, 3, "auto", 4);
            awaitStatus(service, session.getTaskId(), "completed");

            TranslationSession completed = service.getSession(session.getTaskId());
            assertTrue(completed.isSinglePageFallback());
            assertEquals(2, completed.getQps());
            assertEquals(2, completed.getResourceDowngradeCount());
            verify(babelDocService).translatePdf(
                    any(Path.class), any(Path.class), eq("paper.pdf"), eq(1), eq(3), eq("auto"),
                    eq(2), eq(2), eq(1), any());
            verify(babelDocService, times(2)).awaitResourceRecovery();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void keepsWarningButAllowsTranslationWhenPdfTextLayerLooksCorrupt() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(5);
        config.setQueueCapacity(2);
        config.setMaxQps(4);

        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(3);
        when(pdfParseService.analyzeTextQuality(any(Path.class)))
                .thenReturn(new PdfParseService.PdfTextQuality(true, "PDF 文本层疑似乱码"));
        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    return new BabelDocService.TranslationResult(translated, bilingual);
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            TranslationSession session = service.createSessionPreview(
                    "bad.pdf", new ByteArrayInputStream("pdf".getBytes()));
            assertTrue(session.isTextQualitySuspicious());
            assertEquals("PDF 文本层疑似乱码", session.getTextQualityWarning());

            service.startTranslation(session.getTaskId(), 1, 3, "auto", 4);
            awaitStatus(service, session.getTaskId(), "completed");
            verify(babelDocService).translatePdf(
                    any(Path.class), any(Path.class), eq("bad.pdf"), eq(1), eq(3), eq("auto"), eq(4), any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void queuesImageTranslationAsOnePageAndKeepsImageResults() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        ImageTranslationService imageTranslationService = mock(ImageTranslationService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(2);

        when(imageTranslationService.inspect(any(Path.class)))
                .thenReturn(new TranslationFileSupport.ImageInfo(100, 80));
        doAnswer(invocation -> {
            Path resultDir = invocation.getArgument(1);
            Files.writeString(resultDir.resolve("translated.txt"), "你好");
            Files.write(resultDir.resolve("translated.png"), new byte[]{1});
            Files.write(resultDir.resolve("bilingual.png"), new byte[]{2});
            Files.write(resultDir.resolve("translated.pdf"), new byte[]{3});
            Files.write(resultDir.resolve("bilingual.pdf"), new byte[]{4});
            return null;
        }).when(imageTranslationService).translateImage(any(Path.class), any(Path.class), anyString(), anyString(), any());

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper(), imageTranslationService);
        service.initialize();
        try {
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            TranslationSession session = service.createSessionPreview(
                    "poster.png", "image/png", new ByteArrayInputStream(bytes.toByteArray()));

            assertEquals("image", session.getInputKind());
            assertEquals(1, session.getTotalPages());
            assertTrue(Files.isRegularFile(session.getInputImagePath()));

            service.startTranslation(session.getTaskId(), 1, 1, "auto", 4);
            awaitStatus(service, session.getTaskId(), "completed");

            assertEquals("poster-翻译版.png", service.buildImageDownloadFileName(session.getTaskId(), "translated"));
            assertEquals("你好", service.buildDownloadContent(session.getTaskId()));
            verify(imageTranslationService).translateImage(
                    eq(session.getInputImagePath()), eq(session.getTaskDir()), eq("poster.png"), eq("auto"), any());
            verifyNoInteractions(babelDocService);
        } finally {
            service.shutdown();
        }
    }

    private void awaitStatus(TranslationService service, String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(service.getSession(taskId).getStatus())) return;
            Thread.sleep(20);
        }
        fail("任务未进入状态: " + expected);
    }
}
