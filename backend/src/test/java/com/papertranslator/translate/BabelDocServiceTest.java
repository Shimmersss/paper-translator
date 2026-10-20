package com.papertranslator.translate;

import com.papertranslator.config.ProviderSettings;
import com.papertranslator.config.BabelDocConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BabelDocServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToSinglePageChunks() {
        assertEquals(1, new BabelDocConfig().getMaxPagesPerChunk());
    }

    @Test
    void subtractsInactiveFileCacheFromCgroupWorkingSet() {
        long current = 2_700L * 1024 * 1024;
        long inactiveFile = BabelDocService.inactiveFileBytes(String.join("\n",
                "anon 2147483648", "file 419430400", "inactive_file 314572800"));

        assertEquals(300L * 1024 * 1024, inactiveFile);
        assertEquals(2_400L * 1024 * 1024,
                BabelDocService.workingSetBytes(current, inactiveFile));
    }

    @Test
    void supportsV1TotalInactiveFileAndMalformedStatsConservatively() {
        assertEquals(128L * 1024 * 1024, BabelDocService.inactiveFileBytes(
                "total_inactive_file 134217728\ninactive_file invalid"));
        assertEquals(900L, BabelDocService.workingSetBytes(1_000L, 100L));
        assertEquals(-1L, BabelDocService.workingSetBytes(-1L, 100L));
    }

    @Test
    void recoveryAllowsRetainedSwapBelowOperatingLimitWhenMemoryHasRecovered() {
        BabelDocService service = resourceService(2519, 915, 580);
        assertTrue(service.detectRecoveryRisk().isEmpty());
    }

    @Test
    void recoveryStillRejectsSwapAboveOperatingLimit() {
        assertTrue(resourceService(2519, 1201, 580).detectRecoveryRisk()
                .orElseThrow().contains("Swap"));
        assertTrue(resourceService(2519, 1200, 580).detectRecoveryRisk().isEmpty());
    }

    @Test
    void recoveryStillRequiresMemoryHeadroomAndSafeWorkingSet() {
        assertTrue(resourceService(599, 915, 580).detectRecoveryRisk()
                .orElseThrow().contains("尚未恢复"));
        assertTrue(resourceService(2519, 915, 2851).detectRecoveryRisk()
                .orElseThrow().contains("服务工作集"));
    }

    @Test
    void recoveredRetainedSwapCanAdvanceToNextChunk() throws InterruptedException {
        resourceService(2519, 915, 580).waitForResourceRecovery();
    }

    private BabelDocService resourceService(long availableMiB, long swapUsedMiB,
                                             long workingSetMiB) {
        BabelDocConfig config = new BabelDocConfig();
        config.setResourceRecoveryTimeoutSeconds(5);
        return new BabelDocService(config, mock(ProviderSettings.class)) {
            @Override
            MemoryInfo readMemoryInfo() {
                return new MemoryInfo(availableMiB * 1024 * 1024,
                        2048L * 1024 * 1024, (2048L - swapUsedMiB) * 1024 * 1024);
            }

            @Override
            CgroupMemory readCgroupMemory() {
                return new CgroupMemory(workingSetMiB * 1024 * 1024, 0);
            }
        };
    }

    @Test
    void splitsLongTranslationAndMergesBothOutputsInPageOrder() throws Exception {
        RecordingBabelDocService service = newService();
        List<Double> progress = new ArrayList<>();
        List<String> stages = new ArrayList<>();

        BabelDocService.TranslationResult result = service.translatePdf(
                tempDir.resolve("input.pdf"), tempDir.resolve("result"), "paper.pdf",
                1, 10, "auto", 2, update -> {
                    progress.add(update.progress());
                    stages.add(update.stage());
                });

        assertEquals(List.of("1-4@2", "5-8@2", "9-10@2"), service.calls);
        assertEquals(10, pageCount(result.translatedPdf()));
        assertEquals(10, pageCount(result.bilingualPdf()));
        assertTrue(stages.contains("chunk-wait"));
        assertTrue(stages.contains("merge"));
        assertTrue(isNonDecreasing(progress));
        assertTrue(Files.notExists(tempDir.resolve("result/.babeldoc-chunks/shared")));
    }

    @Test
    void stableModeCanReduceLongDocumentBatchesToOnePage() throws Exception {
        RecordingBabelDocService service = newService();

        BabelDocService.TranslationResult result = service.translatePdf(
                tempDir.resolve("input.pdf"), tempDir.resolve("result"), "paper.pdf",
                1, 8, "auto", 2, 1, ignored -> { });

        assertEquals(List.of("1-1@2", "2-2@2", "3-3@2", "4-4@2",
                "5-5@2", "6-6@2", "7-7@2", "8-8@2"), service.calls);
        assertEquals(8, pageCount(result.translatedPdf()));
    }

    @Test
    void recoveryBatchReturnsToRequestedQpsForFollowingChunks() throws Exception {
        RecordingBabelDocService service = newService();

        service.translatePdf(tempDir.resolve("input.pdf"), tempDir.resolve("result"), "paper.pdf",
                1, 4, "auto", 2, 4, 1, ignored -> { });

        assertEquals(List.of("1-1@2", "2-2@4", "3-3@4", "4-4@4"), service.calls);
    }

    @Test
    void stableModeReslicesCompletedLargerChunksWithoutRetranslatingThem() throws Exception {
        RecordingBabelDocService service = newService();
        Path resultDir = tempDir.resolve("result");
        Path cached = resultDir.resolve(".babeldoc-chunks/shared/1-4");
        Files.createDirectories(cached);
        writeTextPdf(cached.resolve("translated.pdf"), 4, 0, "");
        writeTextPdf(cached.resolve("bilingual.pdf"), 4, 0, "");
        Files.writeString(resultDir.resolve(".babeldoc-chunks/shared/manifest.txt"), String.join("\n",
                "version=2", "completion=complete-v1", "input=" + tempDir.resolve("input.pdf").toAbsolutePath().normalize(),
                "chunkSize=4", "fontFamily=auto", "model=null"));

        service.translatePdf(tempDir.resolve("input.pdf"), resultDir, "paper.pdf",
                1, 8, "auto", 2, 1, ignored -> { });

        assertEquals(List.of("5-5@2", "6-6@2", "7-7@2", "8-8@2"), service.calls);
    }

    @Test
    void reusesCompletedChunksWhenStableModeRetriesAfterResourcePressure() throws Exception {
        RecordingBabelDocService service = newService();
        service.failAcceleratedSecondChunk.set(true);
        Path resultDir = tempDir.resolve("result");

        assertThrows(BabelDocService.ResourcePressureException.class,
                () -> service.translatePdf(tempDir.resolve("input.pdf"), resultDir,
                        "paper.pdf", 1, 10, "auto", 4, ignored -> {
                        }));
        assertTrue(Files.isRegularFile(
                resultDir.resolve(".babeldoc-chunks/shared/1-4/translated.pdf")));

        BabelDocService.TranslationResult result = service.translatePdf(
                tempDir.resolve("input.pdf"), resultDir, "paper.pdf",
                1, 10, "auto", 2, ignored -> {
                });

        assertEquals(List.of("1-4@4", "5-8@4", "5-8@2", "9-10@2"), service.calls);
        assertEquals(10, pageCount(result.translatedPdf()));
        assertEquals(10, pageCount(result.bilingualPdf()));
    }

    @Test
    void importsCompatibleLegacyChunksWhenTheRetryUsesANewPageRange() throws Exception {
        RecordingBabelDocService service = newService();
        Path resultDir = tempDir.resolve("result");
        writeTextPdf(tempDir.resolve("input.pdf"), 10, 0, "");
        Path legacyChunk = resultDir.resolve(".babeldoc-chunks/1-10/1-4");
        Files.createDirectories(legacyChunk);
        writeTextPdf(legacyChunk.resolve("translated.pdf"), 4, 0, "");
        writeTextPdf(legacyChunk.resolve("bilingual.pdf"), 4, 0, "");
        Files.writeString(resultDir.resolve(".babeldoc-chunks/1-10/manifest.txt"), String.join("\n",
                "version=1", "completion=complete-v1", "input=" + Files.size(tempDir.resolve("input.pdf"))
                        + ":" + Files.getLastModifiedTime(tempDir.resolve("input.pdf")).toMillis(),
                "pages=1-10", "chunkSize=4", "fontFamily=auto", "model=null"));

        service.translatePdf(tempDir.resolve("input.pdf"), resultDir, "paper.pdf",
                1, 8, "auto", 2, ignored -> { });

        assertEquals(List.of("5-8@2"), service.calls);
    }

    @Test
    void keepsReferenceSectionActiveWhenFollowingChunksStartAfterHeading() throws Exception {
        RecordingBabelDocService service = newService();
        Path input = tempDir.resolve("references.pdf");
        writeTextPdf(input, 10, 4, "References");

        service.translatePdf(input, tempDir.resolve("result"), "references.pdf",
                1, 10, "auto", 2, ignored -> {
                });

        assertEquals(List.of("5-8", "9-10"), service.forcedReferenceRanges);
    }

    @Test
    void stopsForcingReferenceSectionAfterAppendixAcrossChunks() throws Exception {
        RecordingBabelDocService service = newService();
        Path input = tempDir.resolve("references-appendix.pdf");
        writeTextPdf(input, 10, 4, "References");
        addTextToPdf(input, 7, "Supplementary Material");

        service.translatePdf(input, tempDir.resolve("result"), "references-appendix.pdf",
                1, 10, "auto", 2, ignored -> {
                });

        assertEquals(List.of("5-8"), service.forcedReferenceRanges);
    }

    @Test
    void stopsForcingReferenceSectionAtTitledAppendixHeading() throws Exception {
        RecordingBabelDocService service = newService();
        Path input = tempDir.resolve("references-titled-appendix.pdf");
        writeTextPdf(input, 10, 4, "References");
        addTextToPdf(input, 7, "Appendix A. Derivation of the governing equations");

        service.translatePdf(input, tempDir.resolve("result"), "references-titled-appendix.pdf",
                1, 10, "auto", 2, ignored -> {
                });

        assertEquals(List.of("5-8"), service.forcedReferenceRanges);
    }

    @Test
    void readsReferenceAndAppendixHeadingsInPageOrder() {
        assertFalse(BabelDocService.referenceSectionActiveAfter(
                "References\n[1] A. Author, J. Fluid 2020.\nAPPENDIX B: PROOFS\nBody", false));
        assertTrue(BabelDocService.referenceSectionActiveAfter(
                "Appendix A\nBody\nReferences\n[1] A. Author", false));
        assertTrue(BabelDocService.referenceSectionActiveAfter(
                "Appendix is cited here\nMethods Appl. Mech. Eng. 200 (2011)", true));
        assertFalse(BabelDocService.referenceSectionActiveAfter("Supporting Information", true));
    }

    @Test
    void invalidatesCompletedChunksWhenRenderingParametersChange() throws Exception {
        RecordingBabelDocService service = newService();
        service.failAcceleratedSecondChunk.set(true);
        Path resultDir = tempDir.resolve("result");

        assertThrows(BabelDocService.ResourcePressureException.class,
                () -> service.translatePdf(tempDir.resolve("input.pdf"), resultDir,
                        "paper.pdf", 1, 10, "auto", 4, ignored -> {
                        }));
        service.translatePdf(tempDir.resolve("input.pdf"), resultDir,
                "paper.pdf", 1, 10, "serif", 2, ignored -> {
                });

        assertEquals(List.of(
                "1-4@4", "5-8@4", "1-4@2", "5-8@2", "9-10@2"), service.calls);
    }

    @Test
    void rejectsOldChunksWithoutCompletionChecksEvenWithMatchingPageCount() throws Exception {
        RecordingBabelDocService service = newService();
        Path resultDir = tempDir.resolve("result");
        for (String location : List.of("shared", "1-10")) {
            Path root = resultDir.resolve(".babeldoc-chunks").resolve(location);
            Files.createDirectories(root.resolve("1-4"));
            writeTextPdf(root.resolve("1-4/translated.pdf"), 4, 0, "");
            writeTextPdf(root.resolve("1-4/bilingual.pdf"), 4, 0, "");
            Files.writeString(root.resolve("manifest.txt"), String.join("\n",
                    "version=4", "input=" + tempDir.resolve("input.pdf").toAbsolutePath().normalize(),
                    "fontFamily=auto", "model=null"));
        }
        service.translatePdf(tempDir.resolve("input.pdf"), resultDir, "paper.pdf",
                1, 8, "auto", 2, ignored -> { });
        assertEquals(List.of("1-4@2", "5-8@2"), service.calls);
    }

    private RecordingBabelDocService newService() {
        BabelDocConfig config = new BabelDocConfig();
        config.setMaxPagesPerChunk(4);
        ProviderSettings runtimeConfig = mock(ProviderSettings.class);
        when(runtimeConfig.babelKey()).thenReturn("test-key");
        return new RecordingBabelDocService(config, runtimeConfig);
    }

    private int pageCount(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return document.getNumberOfPages();
        }
    }

    private boolean isNonDecreasing(List<Double> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index) < values.get(index - 1)) return false;
        }
        return true;
    }

    private static class RecordingBabelDocService extends BabelDocService {
        private final List<String> calls = new ArrayList<>();
        private final List<String> forcedReferenceRanges = new ArrayList<>();
        private final AtomicBoolean failAcceleratedSecondChunk = new AtomicBoolean();

        private RecordingBabelDocService(BabelDocConfig config,
                                         ProviderSettings runtimeConfig) {
            super(config, runtimeConfig);
        }

        @Override
        protected TranslationResult translateRange(
                Path inputPdf, Path resultDir, String fileName, int startPage,
                int endPage, String fontFamily, int qps,
                boolean forceReferenceSection,
                Consumer<ProgressUpdate> progressConsumer) {
            calls.add(startPage + "-" + endPage + "@" + qps);
            if (forceReferenceSection) {
                forcedReferenceRanges.add(startPage + "-" + endPage);
            }
            if (startPage == 5 && qps == 4
                    && failAcceleratedSecondChunk.compareAndSet(true, false)) {
                throw new ResourcePressureException("test pressure");
            }
            try {
                Files.createDirectories(resultDir);
                int pages = endPage - startPage + 1;
                writePdf(resultDir.resolve("translated.pdf"), pages);
                writePdf(resultDir.resolve("bilingual.pdf"), pages);
                if (progressConsumer != null) {
                    progressConsumer.accept(
                            new ProgressUpdate(100, "Save PDF", pages, pages));
                }
                return new TranslationResult(
                        resultDir.resolve("translated.pdf"),
                        resultDir.resolve("bilingual.pdf"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected void waitForResourceRecovery() {
            // The production implementation samples cgroup/system memory twice.
        }

        private void writePdf(Path path, int pages) throws IOException {
            try (PDDocument document = new PDDocument()) {
                for (int index = 0; index < pages; index++) {
                    document.addPage(new PDPage());
                }
                document.save(path.toFile());
            }
        }
    }

    private void writeTextPdf(Path path, int pages, int textPage, String text)
            throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int pageNumber = 1; pageNumber <= pages; pageNumber++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (pageNumber == textPage) {
                    try (PDPageContentStream content =
                                 new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(
                                Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(72, 720);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            document.save(path.toFile());
        }
    }

    private void addTextToPdf(Path path, int pageNumber, String text) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".updated");
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            try (PDPageContentStream content = new PDPageContentStream(
                    document, document.getPage(pageNumber - 1),
                    PDPageContentStream.AppendMode.APPEND, true)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(temporary.toFile());
        }
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
