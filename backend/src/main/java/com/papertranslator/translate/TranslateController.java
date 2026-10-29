package com.papertranslator.translate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);
    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    private final TranslationService translationService;

    public TranslateController(TranslationService translationService) {
        this.translationService = translationService;
    }

    /**
     * 上传 PDF 或常见图片，获取页数/图片信息（不立即翻译）
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "请上传 PDF 或图片文件"));
        }

        String fileName = file.getOriginalFilename();
        TranslationFileSupport.FileDescriptor descriptor;
        try {
            descriptor = TranslationFileSupport.describe(fileName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        }

        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "文件大小超过 50MB 限制"));
        }

        try {
            TranslationSession session = translationService.createSessionPreview(
                    fileName, file.getContentType(), file.getInputStream());

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "taskId", session.getTaskId(),
                            "fileName", session.getFileName(),
                            "inputKind", session.getInputKind(),
                            "totalPages", session.getTotalPages(),
                            "textQualitySuspicious", session.isTextQualitySuspicious(),
                            "textQualityWarning", session.getTextQualityWarning() != null ? session.getTextQualityWarning() : ""
                    )
            ));
        } catch (IllegalArgumentException e) {
            log.warn("翻译文件解析失败: file={}, kind={}, message={}", fileName, descriptor.kind(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("code", 503, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("上传处理失败", e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "文件处理失败: " + e.getMessage()));
        }
    }

    /**
     * 开始翻译：指定页面范围并交给 BabelDOC
     */
    @PostMapping("/start/{taskId}")
    public ResponseEntity<?> start(@PathVariable String taskId,
                                   @RequestParam(defaultValue = "1") int startPage,
                                   @RequestParam(required = false) Integer endPage,
                                   @RequestParam(defaultValue = "auto") String fontFamily,
                                   @RequestParam(required = false) Integer qps) {
        try {
            // Without qps the performance tier from the settings page decides.
            TranslationSession session = translationService.startTranslation(
                    taskId, startPage, endPage != null ? endPage : Integer.MAX_VALUE, fontFamily,
                    qps != null ? qps : translationService.defaultQps());

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "taskId", session.getTaskId(),
                            "pageCount", session.getEndPage() - session.getStartPage() + 1,
                            "status", session.getStatus(),
                            "qps", session.getQps(),
                            "requestedQps", session.getRequestedQps(),
                            "resourceDowngraded", session.isResourceDowngraded(),
                            "queuePosition", session.getQueuePosition()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("code", 429, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("开始翻译失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "处理失败: " + e.getMessage()));
        }
    }

    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(@PathVariable String taskId) {
        try {
            translationService.cancel(taskId);
            return ResponseEntity.ok(Map.of("code", 200, "data", Map.of("cancelled", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("code", 409, "message", e.getMessage()));
        }
    }

    /**
     * SSE 流式推送翻译进度
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(6 * 60 * 60 * 1000L); // 长篇论文翻译可能持续数小时
        if (translationService.getSession(taskId) == null) {
            try {
                emitter.send(SseEmitter.event().name("task-error").data(Map.of("message", "任务不存在")));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }

        emitter.onTimeout(() -> {
            log.warn("SSE 超时: taskId={}", taskId);
            emitter.complete();
        });

        emitter.onError(e -> {
            log.warn("SSE 错误: taskId={}", taskId, e);
        });

        translationService.subscribe(taskId, emitter);
        return emitter;
    }

    /**
     * 查询翻译任务状态（用于断线重连）
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId) {
        TranslationSession session = translationService.getSession(taskId);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "任务不存在"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("fileName", session.getFileName());
        data.put("inputKind", session.getInputKind());
        data.put("status", session.getStatus());
        data.put("textQualitySuspicious", session.isTextQualitySuspicious());
        data.put("textQualityWarning", session.getTextQualityWarning() != null ? session.getTextQualityWarning() : "");
        data.put("keptSourceWarning", session.getKeptSourceWarning() != null ? session.getKeptSourceWarning() : "");
        data.put("totalPages", session.getTotalPages());
        data.put("startPage", session.getStartPage());
        data.put("endPage", session.getEndPage());
        data.put("fontFamily", session.getFontFamily());
        data.put("qps", session.getQps());
        data.put("requestedQps", session.getRequestedQps());
        data.put("resourceDowngraded", session.isResourceDowngraded());
        data.put("resourceDowngradeReason", session.getResourceDowngradeReason() != null ? session.getResourceDowngradeReason() : "");
        data.put("resourceDowngradeCount", session.getResourceDowngradeCount());
        data.put("progress", session.getProgress());
        data.put("progressStage", session.getProgressStage());
        data.put("progressStageLabel", translationService.stageLabel(session.getProgressStage()));
        data.put("errorMessage", session.getErrorMessage() != null ? session.getErrorMessage() : "");
        data.put("queuePosition", session.getQueuePosition());
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        data.put("completedAt", session.getCompletedAt());
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    /**
     * 最近翻译任务。只返回轻量元数据，PDF 文件通过下载接口按需读取。
     */
    @GetMapping("/recent")
    public ResponseEntity<?> recent() {
        List<Map<String, Object>> data = translationService.getRecentSessions().stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    /**
     * 下载翻译结果（TXT）
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<?> download(@PathVariable String taskId) {
        try {
            String content = translationService.buildDownloadContent(taskId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(translationService.buildTextDownloadFileName(taskId)))
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        }
    }

    /**
     * 下载翻译后的 PDF（译文填回原位置）
     */
    @GetMapping("/download-pdf/{taskId}")
    public ResponseEntity<?> downloadPdf(@PathVariable String taskId,
                                         @RequestParam(defaultValue = "translated") String mode) {
        try {
            Path pdf = translationService.getTranslatedPdf(taskId, mode);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(translationService.buildPdfDownloadFileName(taskId, mode)))
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(new FileSystemResource(pdf));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("读取翻译 PDF 失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "读取翻译 PDF 失败: " + e.getMessage()));
        }
    }

    /**
     * 下载图片翻译结果。图片任务同时生成 PNG 和 PDF，前端优先使用 PNG 保留图片语义。
     */
    @GetMapping("/download-image/{taskId}")
    public ResponseEntity<?> downloadImage(@PathVariable String taskId,
                                           @RequestParam(defaultValue = "translated") String mode) {
        try {
            Path image = translationService.getTranslatedImage(taskId, mode);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(translationService.buildImageDownloadFileName(taskId, mode)))
                    .contentType(MediaType.IMAGE_PNG)
                    .body(new FileSystemResource(image));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("读取图片翻译结果失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "读取图片翻译结果失败: " + e.getMessage()));
        }
    }

    private static String attachment(String fileName) {
        return "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Map<String, Object> toSummary(TranslationSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", session.getTaskId());
        data.put("fileName", session.getFileName());
        data.put("inputKind", session.getInputKind());
        data.put("status", session.getStatus());
        data.put("textQualitySuspicious", session.isTextQualitySuspicious());
        data.put("textQualityWarning", session.getTextQualityWarning() != null ? session.getTextQualityWarning() : "");
        data.put("keptSourceWarning", session.getKeptSourceWarning() != null ? session.getKeptSourceWarning() : "");
        data.put("totalPages", session.getTotalPages());
        data.put("startPage", session.getStartPage());
        data.put("endPage", session.getEndPage());
        data.put("progress", session.getProgress());
        data.put("progressStageLabel", translationService.stageLabel(session.getProgressStage()));
        data.put("qps", session.getQps());
        data.put("requestedQps", session.getRequestedQps());
        data.put("resourceDowngraded", session.isResourceDowngraded());
        data.put("resourceDowngradeReason", session.getResourceDowngradeReason() != null ? session.getResourceDowngradeReason() : "");
        data.put("queuePosition", session.getQueuePosition());
        data.put("createdAt", session.getCreatedAt());
        data.put("completedAt", session.getCompletedAt());
        data.put("errorMessage", session.getErrorMessage() != null ? session.getErrorMessage() : "");
        return data;
    }
}
