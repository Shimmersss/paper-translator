package com.papertranslator.translate;

import com.papertranslator.config.BabelDocConfig;
import com.papertranslator.config.TranslationConfig;
import com.papertranslator.settings.Tuning;
import com.papertranslator.config.ProviderSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class BabelDocService {

    private static final Logger log = LoggerFactory.getLogger(BabelDocService.class);
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final long MONITOR_INTERVAL_MILLIS = 2000;
    // Host-wide guard; the configured swap ceiling applies to this service's cgroup.
    private static final long MIN_SYSTEM_SWAP_FREE_MIB = 256;
    private static final String CHUNK_CACHE_VERSION = "version=5";
    private static final String COMPLETION_POLICY = "completion=complete-v1";
    // isCompatibleChunkManifest ignores the version line so compatible legacy
    // chunks survive upgrades; output-changing fixes add a policy line instead.
    // Chunks cached before titled appendix headings ("GraspGen-X: Supplementary
    // Material") were recognised may hold untranslated appendices.
    private static final String REFERENCE_POLICY = "references=titled-appendix-v1";
    private static final Pattern CHUNK_RANGE = Pattern.compile("^(\\d+)-(\\d+)$");
    private static final Pattern REFERENCE_HEADING = Pattern.compile(
            "\\s*(?:references|bibliography|works\\s+cited|literature\\s+cited)\\s*",
            Pattern.CASE_INSENSITIVE);
    // Mirrors REFERENCE_SECTION_END_RE in scripts/reference_layout.py. Appendix
    // headings usually carry an identifier and title ("Appendix A. Derivation");
    // only keywords are case-insensitive so prose like "Appendix is" is ignored.
    // A short title prefix ("GraspGen-X: Supplementary Material") is allowed when
    // it has no period and does not start like a numbered reference entry.
    private static final Pattern REFERENCE_SECTION_END = Pattern.compile(
            "\\s*(?:(?![\\[(\\d])[^.:\\uff1a\\n]{1,80}[:\\uff1a]\\s*)?(?:(?:[A-Z]|\\d{1,2}(?:\\.\\d{1,2})*)[.)]?\\s+)?(?:"
                    + "(?i:appendix|appendices)(?:\\s+(?:[A-Z]|[IVX]{1,4}|\\d{1,2})(?:\\.\\d{1,2})*)?"
                    + "(?:\\s*[.:\\uff1a\\u2014\\u2013-]\\s*\\S.*|\\s+[A-Z(].*)?"
                    + "|(?:(?i:supplementary|supplemental|supporting)\\s+(?i:materials?|information|data)"
                    + "|(?i:online\\s+)?(?i:methods)|(?i:extended\\s+data)"
                    + "|(?i:acknowledge?ments?)|(?i:author\\s+contributions))\\s*[.:\\uff1a]?"
                    + ")\\s*");

    private final BabelDocConfig config;
    private final ProviderSettings providers;
    private final Tuning tuning;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile double lastProgress;

    /** Uses application.yml / environment values only (tests and embedding). */
    public BabelDocService(BabelDocConfig config, ProviderSettings providers) {
        this(config, providers, Tuning.fromConfig(config, new TranslationConfig()));
    }

    @Autowired
    public BabelDocService(BabelDocConfig config, ProviderSettings providers, Tuning tuning) {
        this.config = config;
        this.providers = providers;
        this.tuning = tuning;
    }

    /** Checked before a task is queued so a missing key is reported instead of a generic failure. */
    public Optional<String> configurationProblem() {
        if (!config.isEnabled()) return Optional.of("BabelDOC 未启用，请设置 BABELDOC_ENABLED=true");
        if (providers.babelKey().isBlank()) return Optional.of("未配置 BABELDOC_OPENAI_API_KEY，请在 .env 中填写后重启服务");
        if (providers.babelUrl().isBlank() || providers.babelModel().isBlank()) {
            return Optional.of("未配置 BABELDOC_OPENAI_BASE_URL 或 BABELDOC_OPENAI_MODEL");
        }
        return Optional.empty();
    }

    public TranslationResult translatePdf(Path inputPdf, Path resultDir, String fileName, int startPage, int endPage,
                                          String fontFamily, int qps, Consumer<ProgressUpdate> progressConsumer) {
        return translatePdf(inputPdf, resultDir, fileName, startPage, endPage, fontFamily, qps,
                tuning.pagesPerChunk(), progressConsumer);
    }

    /** Stable mode may lower the page batch size without mutating global runtime configuration. */
    public TranslationResult translatePdf(Path inputPdf, Path resultDir, String fileName, int startPage, int endPage,
                                          String fontFamily, int qps, int maxPagesPerChunk,
                                          Consumer<ProgressUpdate> progressConsumer) {
        return translatePdf(inputPdf, resultDir, fileName, startPage, endPage, fontFamily, qps, qps,
                maxPagesPerChunk, progressConsumer);
    }

    /** The first missing chunk may use recovery QPS; later chunks resume the requested speed. */
    public TranslationResult translatePdf(Path inputPdf, Path resultDir, String fileName, int startPage, int endPage,
                                          String fontFamily, int firstPendingQps, int followingQps,
                                          int maxPagesPerChunk, Consumer<ProgressUpdate> progressConsumer) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("BabelDOC 未启用，请设置 BABELDOC_ENABLED=true");
        }
        if (providers.babelKey().isBlank()) {
            throw new IllegalStateException("BabelDOC API Key 未配置，请在 .env 中设置 BABELDOC_OPENAI_API_KEY");
        }
        int totalPages = endPage - startPage + 1;
        if (totalPages <= 0) {
            throw new IllegalArgumentException("BabelDOC 页面范围无效");
        }
        int chunkSize = Math.max(1, Math.min(tuning.pagesPerChunk(), maxPagesPerChunk));
        if (totalPages <= chunkSize) {
            return translateRange(inputPdf, resultDir, fileName, startPage, endPage,
                    fontFamily, firstPendingQps,
                    isReferenceSectionActiveBefore(inputPdf, startPage),
                    progressConsumer);
        }

        Path chunkCacheBase = resultDir.resolve(".babeldoc-chunks");
        // Chunks are page-addressed and are safe to reuse when the source and rendering
        // parameters match.  Do not key them by the requested range: a user narrowing a
        // failed 224-page job to 1-100 must not throw away completed pages 1-90.
        Path chunkRoot = chunkCacheBase.resolve("shared");
        List<Path> translatedParts = new ArrayList<>();
        List<Path> bilingualParts = new ArrayList<>();
        int totalChunks = (totalPages + chunkSize - 1) / chunkSize;
        int completedPages = 0;
        boolean processedPendingChunk = false;
        try {
            prepareChunkRoot(chunkRoot, inputPdf, fontFamily);
            importCompatibleLegacyChunks(chunkCacheBase, chunkRoot, inputPdf, fontFamily);
            log.info("BabelDOC 长文档启用分片: file={}, pages={}-{}, chunkSize={}, chunks={}",
                    fileName, startPage, endPage, chunkSize, totalChunks);
            for (int chunkIndex = 0, chunkStart = startPage;
                 chunkStart <= endPage;
                 chunkIndex++, chunkStart += chunkSize) {
                int chunkEnd = Math.min(endPage, chunkStart + chunkSize - 1);
                int chunkPages = chunkEnd - chunkStart + 1;
                Path chunkDir = Files.createDirectories(
                        chunkRoot.resolve(chunkStart + "-" + chunkEnd));
                Path translatedPart = chunkDir.resolve("translated.pdf");
                Path bilingualPart = chunkDir.resolve("bilingual.pdf");

                if (!isCompleteChunk(translatedPart, bilingualPart, chunkPages)) {
                    materializeCoveringCachedChunk(chunkRoot, chunkStart, chunkEnd,
                            translatedPart, bilingualPart);
                }
                if (!isCompleteChunk(translatedPart, bilingualPart, chunkPages)) {
                    if (chunkIndex > 0) {
                        emitProgress(progressConsumer, completedPages * 100.0 / totalPages,
                                "chunk-wait", chunkIndex, totalChunks);
                        waitForResourceRecovery();
                    }
                    int completedBeforeChunk = completedPages;
                    Consumer<ProgressUpdate> chunkProgress = update -> emitProgress(
                            progressConsumer,
                            aggregateProgress(completedBeforeChunk, chunkPages, totalPages,
                                    update.progress()),
                            update.stage(), update.current(), update.total());
                    int chunkQps = processedPendingChunk ? followingQps : firstPendingQps;
                    log.info("启动 BabelDOC 分片: file={}, chunk={}/{}, pages={}-{}, qps={}",
                            fileName, chunkIndex + 1, totalChunks, chunkStart, chunkEnd, chunkQps);
                    try {
                        translateRange(inputPdf, chunkDir, fileName, chunkStart, chunkEnd,
                                fontFamily, chunkQps,
                                isReferenceSectionActiveBefore(inputPdf, chunkStart), chunkProgress);
                    } catch (ResourcePressureException pressure) {
                        throw new ResourcePressureException(pressure.getMessage(), chunkQps);
                    }
                    if (!isCompleteChunk(translatedPart, bilingualPart, chunkPages)) {
                        throw new IllegalStateException("BabelDOC 分片结果不完整: "
                                + chunkStart + "-" + chunkEnd);
                    }
                    processedPendingChunk = true;
                } else {
                    log.info("复用已完成 BabelDOC 分片: file={}, pages={}-{}",
                            fileName, chunkStart, chunkEnd);
                }

                translatedParts.add(translatedPart);
                bilingualParts.add(bilingualPart);
                completedPages += chunkPages;
                emitProgress(progressConsumer,
                        Math.min(98.5, completedPages * 100.0 / totalPages),
                        "chunk-completed", chunkIndex + 1, totalChunks);
            }

            emitProgress(progressConsumer, 99.0, "merge", totalChunks, totalChunks);
            Files.createDirectories(resultDir);
            Path translatedResult = resultDir.resolve("translated.pdf");
            Path bilingualResult = resultDir.resolve("bilingual.pdf");
            mergePdfParts(translatedParts, translatedResult);
            mergePdfParts(bilingualParts, bilingualResult);
            deleteRecursively(chunkRoot);
            log.info("BabelDOC 分片合并完成: file={}, pages={}-{}, chunks={}",
                    fileName, startPage, endPage, totalChunks);
            return new TranslationResult(translatedResult, bilingualResult);
        } catch (IllegalStateException e) {
            // Keep completed chunks so a resource-pressure downgrade or service restart
            // resumes at the failed range instead of translating earlier pages again.
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("BabelDOC 分片翻译失败: " + e.getMessage(), e);
        }
    }

    protected TranslationResult translateRange(Path inputPdf, Path resultDir, String fileName,
                                               int startPage, int endPage, String fontFamily,
                                               int qps, boolean forceReferenceSection,
                                               Consumer<ProgressUpdate> progressConsumer) {

        Path workDir = null;
        Process process = null;
        try {
            workDir = Files.createTempDirectory("paper-translator-babeldoc-");
            Path inputFile = workDir.resolve(sanitizeFileName(fileName));
            Path outputDir = Files.createDirectories(workDir.resolve("output"));
            Files.copy(inputPdf, inputFile);

            List<String> command = buildCommand(inputFile, outputDir, startPage, endPage,
                    fontFamily, qps, forceReferenceSection);
            log.info("启动 BabelDOC: file={}, pages={}-{}, fontFamily={}, qps={}",
                    fileName, startPage, endPage, fontFamily, qps);

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().put("BABELDOC_OPENAI_API_KEY", providers.babelKey());
            process = processBuilder.start();
            Process runningProcess = process;
            StringBuilder output = new StringBuilder();
            Thread outputReader = new Thread(() -> readOutput(runningProcess, output, progressConsumer), "babeldoc-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean completed = waitForCompletionOrResourceRisk(process, Duration.ofSeconds(tuning.timeoutSeconds()));
            if (!completed) {
                terminateProcessTree(process);
                throw new IllegalStateException("BabelDOC 处理超时，请缩小页面范围或调大 BABELDOC_TIMEOUT_SECONDS");
            }

            outputReader.join(TimeUnit.SECONDS.toMillis(10));
            String commandOutput = output.toString();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("BabelDOC 执行失败: " + tail(commandOutput, 1200));
            }

            Path translatedPdf = findPdf(outputDir, ".zh.mono.pdf", "纯中文");
            Path bilingualPdf = findPdf(outputDir, ".zh.dual.pdf", "双语");
            Files.createDirectories(resultDir);
            Path translatedResult = resultDir.resolve("translated.pdf");
            Path bilingualResult = resultDir.resolve("bilingual.pdf");
            Files.copy(translatedPdf, translatedResult, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(bilingualPdf, bilingualResult, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("BabelDOC 完成: file={}, mono={}, dual={}", fileName,
                    translatedPdf.getFileName(), bilingualPdf.getFileName());
            return new TranslationResult(translatedResult, bilingualResult);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BabelDOC 处理被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("BabelDOC 生成翻译 PDF 失败: " + e.getMessage(), e);
        } finally {
            if (process != null && process.isAlive()) {
                terminateProcessTree(process);
            }
            deleteRecursively(workDir);
        }
    }

    private double aggregateProgress(int completedPages, int chunkPages, int totalPages,
                                     double chunkProgress) {
        double normalized = Math.max(0, Math.min(100, chunkProgress));
        return Math.min(98.5,
                (completedPages + chunkPages * normalized / 100.0) * 100.0 / totalPages);
    }

    private void emitProgress(Consumer<ProgressUpdate> progressConsumer, double progress,
                              String stage, int current, int total) {
        if (progressConsumer != null) {
            progressConsumer.accept(new ProgressUpdate(progress, stage, current, total));
        }
    }

    private boolean isCompleteChunk(Path translated, Path bilingual, int expectedPages) {
        return hasExpectedPages(translated, expectedPages)
                && hasExpectedPages(bilingual, expectedPages);
    }

    private boolean hasExpectedPages(Path pdf, int expectedPages) {
        if (!Files.isRegularFile(pdf)) return false;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return document.getNumberOfPages() == expectedPages;
        } catch (Exception e) {
            log.warn("忽略损坏的 BabelDOC 分片结果: {}", pdf);
            return false;
        }
    }

    private void mergePdfParts(List<Path> parts, Path destination) throws IOException {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("没有可合并的 BabelDOC 分片");
        }
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(temporary);
        PDFMergerUtility merger = new PDFMergerUtility();
        for (Path part : parts) {
            merger.addSource(part.toFile());
        }
        merger.setDestinationFileName(temporary.toString());
        try {
            merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void prepareChunkRoot(Path chunkRoot, Path inputPdf, String fontFamily)
            throws IOException {
        String fingerprint = String.join("\n",
                CHUNK_CACHE_VERSION,
                COMPLETION_POLICY,
                REFERENCE_POLICY,
                "input=" + inputFingerprint(inputPdf),
                "fontFamily=" + String.valueOf(fontFamily),
                "model=" + String.valueOf(providers.babelModel()));
        Path manifest = chunkRoot.resolve("manifest.txt");
        if (Files.isDirectory(chunkRoot)) {
            String existing = Files.isRegularFile(manifest)
                    ? Files.readString(manifest, StandardCharsets.UTF_8)
                    : "";
            if (!isCompatibleChunkManifest(existing, inputPdf, fontFamily)) {
                log.info("BabelDOC 分片参数已变化，清理旧缓存: {}", chunkRoot);
                deleteRecursively(chunkRoot);
            }
        }
        Files.createDirectories(chunkRoot);
        Files.writeString(manifest, fingerprint, StandardCharsets.UTF_8);
    }

    /**
     * One-time compatibility bridge for jobs created before the shared cache existed.
     * Older builds placed valid chunks under e.g. {@code 1-224/}; importing only verified
     * page pairs lets an interrupted production job resume after the upgrade.
     */
    private void importCompatibleLegacyChunks(Path cacheBase, Path sharedRoot, Path inputPdf,
                                               String fontFamily) throws IOException {
        if (!Files.isDirectory(cacheBase)) return;
        try (Stream<Path> candidates = Files.list(cacheBase)) {
            for (Path candidate : candidates.toList()) {
                if (candidate.equals(sharedRoot) || !Files.isDirectory(candidate)
                        || !CHUNK_RANGE.matcher(candidate.getFileName().toString()).matches()) {
                    continue;
                }
                Path manifest = candidate.resolve("manifest.txt");
                if (!Files.isRegularFile(manifest) || !isCompatibleLegacyManifest(
                        Files.readString(manifest, StandardCharsets.UTF_8), inputPdf, fontFamily)) {
                    continue;
                }
                importCompleteChunks(candidate, sharedRoot);
            }
        }
    }

    private boolean isCompatibleLegacyManifest(String manifest, Path inputPdf, String fontFamily) {
        return isCompatibleChunkManifest(manifest, inputPdf, fontFamily);
    }

    private boolean isCompatibleChunkManifest(String manifest, Path inputPdf, String fontFamily) {
        String normalized = manifest.replaceFirst("(?m)^version=.*\\R?", "")
                .replaceFirst("(?m)^pages=.*\\R?", "")
                .replaceFirst("(?m)^chunkSize=.*\\R?", "");
        return String.join("\n",
                COMPLETION_POLICY,
                REFERENCE_POLICY,
                "input=" + inputFingerprint(inputPdf),
                "fontFamily=" + String.valueOf(fontFamily),
                "model=" + String.valueOf(providers.babelModel())).equals(normalized);
    }

    private void importCompleteChunks(Path legacyRoot, Path sharedRoot) throws IOException {
        try (Stream<Path> children = Files.list(legacyRoot)) {
            for (Path sourceDir : children.toList()) {
                var matcher = CHUNK_RANGE.matcher(sourceDir.getFileName().toString());
                if (!Files.isDirectory(sourceDir) || !matcher.matches()) continue;
                int expectedPages = Integer.parseInt(matcher.group(2))
                        - Integer.parseInt(matcher.group(1)) + 1;
                Path translated = sourceDir.resolve("translated.pdf");
                Path bilingual = sourceDir.resolve("bilingual.pdf");
                if (!isCompleteChunk(translated, bilingual, expectedPages)) continue;
                Path destination = sharedRoot.resolve(sourceDir.getFileName().toString());
                Files.createDirectories(destination);
                Files.copy(translated, destination.resolve("translated.pdf"),
                        StandardCopyOption.REPLACE_EXISTING);
                Files.copy(bilingual, destination.resolve("bilingual.pdf"),
                        StandardCopyOption.REPLACE_EXISTING);
                log.info("导入可复用 BabelDOC 旧分片: pages={}", sourceDir.getFileName());
            }
        }
    }

    /** Re-slice a verified older 5-page cache into stable one-page cache entries without retranslation. */
    private void materializeCoveringCachedChunk(Path chunkRoot, int startPage, int endPage,
                                                Path translatedTarget, Path bilingualTarget) throws IOException {
        try (Stream<Path> children = Files.list(chunkRoot)) {
            for (Path sourceDir : children.toList()) {
                var matcher = CHUNK_RANGE.matcher(sourceDir.getFileName().toString());
                if (!Files.isDirectory(sourceDir) || !matcher.matches()
                        || sourceDir.equals(translatedTarget.getParent())) continue;
                int sourceStart = Integer.parseInt(matcher.group(1));
                int sourceEnd = Integer.parseInt(matcher.group(2));
                if (sourceStart > startPage || sourceEnd < endPage) continue;
                int sourcePages = sourceEnd - sourceStart + 1;
                Path translated = sourceDir.resolve("translated.pdf");
                Path bilingual = sourceDir.resolve("bilingual.pdf");
                if (!isCompleteChunk(translated, bilingual, sourcePages)) continue;
                copyPdfRange(translated, translatedTarget, startPage - sourceStart, endPage - startPage + 1);
                copyPdfRange(bilingual, bilingualTarget, startPage - sourceStart, endPage - startPage + 1);
                log.info("复用并切分 BabelDOC 旧分片: pages={}-{} from={}",
                        startPage, endPage, sourceDir.getFileName());
                return;
            }
        }
    }

    private void copyPdfRange(Path source, Path target, int offset, int pageCount) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temporary);
        try (PDDocument input = Loader.loadPDF(source.toFile()); PDDocument output = new PDDocument()) {
            for (int index = offset; index < offset + pageCount; index++) output.importPage(input.getPage(index));
            output.save(temporary.toFile());
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String inputFingerprint(Path inputPdf) {
        try {
            return Files.size(inputPdf) + ":" + Files.getLastModifiedTime(inputPdf).toMillis();
        } catch (IOException e) {
            return inputPdf.toAbsolutePath().normalize().toString();
        }
    }

    private List<String> buildCommand(Path inputFile, Path outputDir, int startPage, int endPage,
                                      String fontFamily, int qps,
                                      boolean forceReferenceSection) {
        List<String> command = new ArrayList<>(splitCommand(config.getCommand()));
        command.add(Path.of(config.getRunnerScript()).toAbsolutePath().normalize().toString());
        command.add("--input");
        command.add(inputFile.toString());
        command.add("--output");
        command.add(outputDir.toString());
        command.add("--pages");
        command.add(startPage + "-" + endPage);
        command.add("--base-url");
        command.add(providers.babelUrl());
        command.add("--model");
        command.add(providers.babelModel());
        command.add("--qps");
        command.add(String.valueOf(qps));
        command.add("--font-family");
        command.add(fontFamily);
        if (forceReferenceSection) {
            command.add("--force-reference-section");
        }
        return command;
    }

    boolean isReferenceSectionActiveBefore(Path inputPdf, int startPage) {
        if (!Files.isRegularFile(inputPdf) || startPage <= 1) return false;
        try (PDDocument document = Loader.loadPDF(inputPdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int lastPage = Math.min(startPage - 1, document.getNumberOfPages());
            boolean active = false;
            for (int page = 1; page <= lastPage; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                active = referenceSectionActiveAfter(stripper.getText(document), active);
            }
            return active;
        } catch (Exception e) {
            log.warn("检测分片起点参考文献状态失败，将回退逐段识别: file={}", inputPdf, e);
        }
        return false;
    }

    /** Lines are read in order: a page may close the bibliography and open an appendix. */
    static boolean referenceSectionActiveAfter(String pageText, boolean active) {
        for (String line : pageText.lines().toList()) {
            if (REFERENCE_HEADING.matcher(line).matches()) {
                active = true;
            } else if (active && REFERENCE_SECTION_END.matcher(line).matches()) {
                active = false;
            }
        }
        return active;
    }

    private List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("BABELDOC_COMMAND 未配置");
        }
        return Stream.of(command.trim().split("\\s+")).toList();
    }

    private String sanitizeFileName(String fileName) {
        String safeName = fileName == null ? "input.pdf" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safeName.toLowerCase().endsWith(".pdf") ? safeName : safeName + ".pdf";
    }

    private void readOutput(Process process, StringBuilder output, Consumer<ProgressUpdate> progressConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutputTail(output, line);
                parseProgress(line, progressConsumer);
            }
        } catch (IOException e) {
            appendOutputTail(output, "无法读取 BabelDOC 输出: " + e.getMessage());
        }
    }

    private void appendOutputTail(StringBuilder output, String line) {
        output.append(line).append('\n');
        if (output.length() > MAX_OUTPUT_CHARS) {
            output.delete(0, output.length() - MAX_OUTPUT_CHARS);
        }
    }

    void parseProgress(String line, Consumer<ProgressUpdate> progressConsumer) {
        if (progressConsumer == null || !line.startsWith("{")) return;
        try {
            JsonNode root = objectMapper.readTree(line);
            String type = root.path("type").asText();
            if ("rate-limit".equals(type)) {
                // The runner is backing off after an HTTP 429; keep the bar where it is.
                progressConsumer.accept(new ProgressUpdate(lastProgress, "rate-limited", 0, 0));
                return;
            }
            if (!"progress".equals(type)) return;
            lastProgress = root.path("overallProgress").asDouble();
            progressConsumer.accept(new ProgressUpdate(
                    lastProgress,
                    root.path("stage").asText(),
                    root.path("stageCurrent").asInt(),
                    root.path("stageTotal").asInt()));
        } catch (Exception ignored) {
            // BabelDOC dependencies may log non-JSON lines; keep them for error diagnostics.
        }
    }

    private Path findPdf(Path outputDir, String suffix, String label) throws IOException {
        try (Stream<Path> files = Files.walk(outputDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("BabelDOC 未生成" + label + " PDF"));
        }
    }

    public record TranslationResult(Path translatedPdf, Path bilingualPdf) {}
    public record ProgressUpdate(double progress, String stage, int current, int total) {}

    public static class ResourcePressureException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int qps;

        public ResourcePressureException(String message) {
            this(message, 0);
        }

        public ResourcePressureException(String message, int qps) {
            super(message);
            this.qps = qps;
        }

        public int getQps() { return qps; }
    }

    private String tail(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "(没有命令输出)";
        }
        return text.length() <= maxLength ? text : text.substring(text.length() - maxLength);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    log.warn("清理 BabelDOC 临时文件失败: {}", item, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理 BabelDOC 临时目录失败: {}", path, e);
        }
    }

    private boolean waitForCompletionOrResourceRisk(Process process, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            long waitMillis = Math.max(1, Math.min(MONITOR_INTERVAL_MILLIS, remainingMillis));
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }

            Optional<String> risk = detectResourceRisk();
            if (risk.isPresent()) {
                terminateProcessTree(process);
                throw new ResourcePressureException("检测到服务器内存压力，已停止当前翻译进程: " + risk.get());
            }
        }
        return process.waitFor(1, TimeUnit.MILLISECONDS);
    }

    private Optional<String> detectResourceRisk() {
        CgroupMemory cgroupMemory = readCgroupMemory();
        long cgroupRiskBytes = mibToBytes(tuning.memoryLimitMiB());
        if (cgroupMemory.workingSetBytes() > cgroupRiskBytes) {
            return Optional.of("服务工作集 " + formatMiB(cgroupMemory.workingSetBytes())
                    + " MiB 超过阈值 " + tuning.memoryLimitMiB()
                    + " MiB（总内存 " + formatMiB(cgroupMemory.currentBytes())
                    + " MiB，可回收文件缓存 " + formatMiB(cgroupMemory.inactiveFileBytes()) + " MiB）");
        }

        MemoryInfo memoryInfo = readMemoryInfo();
        long minAvailableBytes = mibToBytes(tuning.minAvailableMiB());
        if (memoryInfo.memAvailableBytes > 0 && memoryInfo.memAvailableBytes < minAvailableBytes) {
            return Optional.of("系统可用内存 " + formatMiB(memoryInfo.memAvailableBytes)
                    + " MiB 低于阈值 " + tuning.minAvailableMiB() + " MiB");
        }
        if (memoryInfo.swapTotalBytes <= 0) {
            return Optional.empty();
        }
        // Other daemons (MySQL, frps) keep cold pages in swap for weeks, so the
        // host total drifts up to the ceiling while idle. Measure what this
        // service and its BabelDOC children use, as systemd MemorySwapMax does.
        long serviceSwap = cgroupMemory.swapBytes();
        long swapUsed = serviceSwap >= 0
                ? serviceSwap : memoryInfo.swapTotalBytes - memoryInfo.swapFreeBytes;
        long maxSwapUsedBytes = mibToBytes(tuning.maxSwapUsedMiB());
        if (swapUsed > maxSwapUsedBytes) {
            return Optional.of((serviceSwap >= 0 ? "服务 Swap 已用 " : "Swap 已用 ")
                    + formatMiB(swapUsed)
                    + " MiB 超过阈值 " + tuning.maxSwapUsedMiB() + " MiB");
        }
        if (memoryInfo.swapFreeBytes < mibToBytes(MIN_SYSTEM_SWAP_FREE_MIB)) {
            return Optional.of("系统 Swap 剩余 " + formatMiB(memoryInfo.swapFreeBytes)
                    + " MiB 低于阈值 " + MIN_SYSTEM_SWAP_FREE_MIB + " MiB");
        }
        return Optional.empty();
    }

    protected void waitForResourceRecovery() throws InterruptedException {
        int timeoutSeconds = Math.max(5, config.getResourceRecoveryTimeoutSeconds());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        int consecutiveSafeSamples = 0;
        String lastRisk = "内存尚未恢复";
        while (System.nanoTime() < deadline) {
            Optional<String> risk = detectRecoveryRisk();
            if (risk.isEmpty()) {
                consecutiveSafeSamples++;
                if (consecutiveSafeSamples >= 2) {
                    return;
                }
            } else {
                consecutiveSafeSamples = 0;
                lastRisk = risk.get();
            }
            Thread.sleep(MONITOR_INTERVAL_MILLIS);
        }
        throw new ResourcePressureException("上一分片结束后等待内存恢复超时: " + lastRisk);
    }

    /** Wait for two healthy samples before restarting after a resource-protection stop. */
    public void awaitResourceRecovery() {
        try {
            waitForResourceRecovery();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待服务器资源恢复时被中断", e);
        }
    }

    Optional<String> detectRecoveryRisk() {
        Optional<String> hardRisk = detectResourceRisk();
        if (hardRisk.isPresent()) {
            return hardRisk;
        }
        MemoryInfo memoryInfo = readMemoryInfo();
        long recoveryAvailableMiB = Math.max(
                600L, tuning.minAvailableMiB() + 200L);
        if (memoryInfo.memAvailableBytes > 0
                && memoryInfo.memAvailableBytes < mibToBytes(recoveryAvailableMiB)) {
            return Optional.of("系统可用内存 " + formatMiB(memoryInfo.memAvailableBytes)
                    + " MiB 尚未恢复到 " + recoveryAvailableMiB + " MiB");
        }
        // Linux can retain cold pages in swap after the child exits even when
        // RAM has recovered. Requiring an extra 300 MiB swap reduction stalls
        // healthy chunked jobs. detectResourceRisk still enforces the configured
        // swap ceiling; recovery additionally requires RAM headroom and two
        // consecutive safe samples before starting the next child.
        return Optional.empty();
    }

    CgroupMemory readCgroupMemory() {
        for (Path path : cgroupMemoryPaths()) {
            try {
                if (Files.isRegularFile(path)) {
                    long currentBytes = Long.parseLong(Files.readString(path).trim());
                    Path memoryStat = path.resolveSibling("memory.stat");
                    long inactiveFileBytes = Files.isRegularFile(memoryStat)
                            ? inactiveFileBytes(Files.readString(memoryStat)) : 0;
                    return new CgroupMemory(currentBytes, inactiveFileBytes,
                            readCgroupSwapBytes(path.resolveSibling("memory.swap.current")));
                }
            } catch (Exception ignored) {
            }
        }
        return new CgroupMemory(-1, 0);
    }

    /** cgroup v2 swap usage of this service, or -1 when the controller is unavailable. */
    private static long readCgroupSwapBytes(Path swapCurrent) {
        try {
            if (Files.isRegularFile(swapCurrent)) {
                return Math.max(0, Long.parseLong(Files.readString(swapCurrent).trim()));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static long inactiveFileBytes(String memoryStat) {
        long v1Fallback = 0;
        if (memoryStat == null || memoryStat.isBlank()) return 0;
        for (String line : memoryStat.lines().toList()) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) continue;
            try {
                if ("inactive_file".equals(parts[0])) return Math.max(0, Long.parseLong(parts[1]));
                if ("total_inactive_file".equals(parts[0])) {
                    v1Fallback = Math.max(0, Long.parseLong(parts[1]));
                }
            } catch (NumberFormatException ignored) {
                // Ignore a malformed metric and continue with the conservative total.
            }
        }
        return v1Fallback;
    }

    static long workingSetBytes(long currentBytes, long inactiveFileBytes) {
        return currentBytes < 0 ? -1 : Math.max(0, currentBytes - Math.max(0, inactiveFileBytes));
    }

    private List<Path> cgroupMemoryPaths() {
        List<Path> paths = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                String[] parts = line.split(":", 3);
                if (parts.length != 3) continue;
                if ("0".equals(parts[0])) {
                    paths.add(Path.of("/sys/fs/cgroup").resolve(parts[2].replaceFirst("^/", "")).resolve("memory.current"));
                } else if (parts[1].contains("memory")) {
                    paths.add(Path.of("/sys/fs/cgroup/memory").resolve(parts[2].replaceFirst("^/", "")).resolve("memory.usage_in_bytes"));
                }
            }
        } catch (IOException ignored) {
        }
        paths.add(Path.of("/sys/fs/cgroup/memory.current"));
        paths.add(Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"));
        return paths;
    }

    MemoryInfo readMemoryInfo() {
        long memAvailable = -1;
        long swapTotal = 0;
        long swapFree = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    memAvailable = parseMeminfoBytes(line);
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = parseMeminfoBytes(line);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = parseMeminfoBytes(line);
                }
            }
        } catch (IOException ignored) {
        }
        return new MemoryInfo(memAvailable, swapTotal, swapFree);
    }

    private long parseMeminfoBytes(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return -1;
        try {
            return Long.parseLong(parts[1]) * 1024;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatMiB(long bytes) {
        return String.valueOf(bytes / 1024 / 1024);
    }

    private long mibToBytes(long mib) {
        return Math.max(1L, mib) * 1024 * 1024;
    }

    private void terminateProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    record MemoryInfo(long memAvailableBytes, long swapTotalBytes, long swapFreeBytes) {}

    record CgroupMemory(long currentBytes, long inactiveFileBytes, long swapBytes) {
        CgroupMemory(long currentBytes, long inactiveFileBytes) {
            this(currentBytes, inactiveFileBytes, -1);
        }

        long workingSetBytes() {
            return BabelDocService.workingSetBytes(currentBytes, inactiveFileBytes);
        }
    }
}
