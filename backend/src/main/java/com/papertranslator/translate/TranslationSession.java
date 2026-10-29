package com.papertranslator.translate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;

/**
 * 一个翻译任务的轻量状态。原文件和结果只保存磁盘路径，不长期驻留 JVM 堆。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TranslationSession {

    private String taskId;
    private String fileName;
    /** pdf / image；历史任务没有该字段时按 pdf 兼容。 */
    private String inputKind = "pdf";
    private String inputExtension = "pdf";
    private int totalPages;
    private int startPage = 1;
    private int endPage = 1;
    private volatile String status = "preview";
    private volatile String errorMessage;
    private volatile boolean textQualitySuspicious;
    private volatile String textQualityWarning;
    /** Set when a delivered document kept a few paragraphs in the source language. */
    private volatile String keptSourceWarning;
    private String fontFamily = "auto";
    private int qps = 4;
    private int requestedQps = 4;
    private volatile boolean resourceDowngraded;
    private volatile String resourceDowngradeReason;
    private volatile int resourceDowngradeCount;
    /** Last-resort PDF mode: translate the remaining pages one at a time after memory pressure. */
    private volatile boolean singlePageFallback;
    private volatile double progress;
    private volatile String progressStage = "";
    private long createdAt;
    private volatile long updatedAt;
    private volatile long completedAt;
    private volatile int queuePosition;

    @JsonIgnore
    private Path taskDir;

    public TranslationSession() {}

    public TranslationSession(String taskId, String fileName, Path taskDir) {
        this.taskId = taskId;
        this.fileName = fileName;
        this.taskDir = taskDir;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getTaskId() { return taskId; }
    public String getFileName() { return fileName; }
    public String getInputKind() { return inputKind == null || inputKind.isBlank() ? "pdf" : inputKind; }
    public String getInputExtension() { return inputExtension == null || inputExtension.isBlank() ? "pdf" : inputExtension; }
    @JsonIgnore
    public boolean isImageInput() { return "image".equalsIgnoreCase(getInputKind()); }
    public int getTotalPages() { return totalPages; }
    public int getStartPage() { return startPage; }
    public int getEndPage() { return endPage; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isTextQualitySuspicious() { return textQualitySuspicious; }
    public String getTextQualityWarning() { return textQualityWarning; }
    public String getKeptSourceWarning() { return keptSourceWarning; }
    public String getFontFamily() { return fontFamily; }
    public int getQps() { return qps; }
    public int getRequestedQps() { return requestedQps; }
    public boolean isResourceDowngraded() { return resourceDowngraded; }
    public String getResourceDowngradeReason() { return resourceDowngradeReason; }
    public int getResourceDowngradeCount() { return resourceDowngradeCount; }
    public boolean isSinglePageFallback() { return singlePageFallback; }
    public double getProgress() { return progress; }
    public String getProgressStage() { return progressStage; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getCompletedAt() { return completedAt; }
    public int getQueuePosition() { return queuePosition; }
    @JsonIgnore
    public Path getTaskDir() { return taskDir; }
    @JsonIgnore
    public Path getInputPdfPath() { return taskDir.resolve("input.pdf"); }
    @JsonIgnore
    public Path getInputImagePath() { return taskDir.resolve("input-image." + getInputExtension()); }
    @JsonIgnore
    public Path getInputPath() { return isImageInput() ? getInputImagePath() : getInputPdfPath(); }
    @JsonIgnore
    public Path getTranslatedPdfPath() { return taskDir.resolve("translated.pdf"); }
    @JsonIgnore
    public Path getBilingualPdfPath() { return taskDir.resolve("bilingual.pdf"); }
    @JsonIgnore
    public Path getTranslatedImagePath() { return taskDir.resolve("translated.png"); }
    @JsonIgnore
    public Path getBilingualImagePath() { return taskDir.resolve("bilingual.png"); }
    @JsonIgnore
    public Path getTranslatedTextPath() { return taskDir.resolve("translated.txt"); }
    @JsonIgnore
    public Path getMetadataPath() { return taskDir.resolve("task.json"); }

    public void setTaskId(String taskId) { this.taskId = taskId; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setInputKind(String inputKind) { this.inputKind = inputKind; touch(); }
    public void setInputExtension(String inputExtension) { this.inputExtension = inputExtension; touch(); }
    public void setTaskDir(Path taskDir) { this.taskDir = taskDir; }
    public void setStatus(String status) { this.status = status; touch(); }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; touch(); }
    public void setPageRange(int startPage, int endPage) {
        this.startPage = startPage;
        this.endPage = endPage;
        touch();
    }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; touch(); }
    public void setTextQualitySuspicious(boolean textQualitySuspicious) { this.textQualitySuspicious = textQualitySuspicious; touch(); }
    public void setTextQualityWarning(String textQualityWarning) { this.textQualityWarning = textQualityWarning; touch(); }
    public void setKeptSourceWarning(String keptSourceWarning) { this.keptSourceWarning = keptSourceWarning; touch(); }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; touch(); }
    public void setQps(int qps) { this.qps = qps; touch(); }
    public void setRequestedQps(int requestedQps) { this.requestedQps = requestedQps; touch(); }
    public void setResourceDowngraded(boolean resourceDowngraded) { this.resourceDowngraded = resourceDowngraded; touch(); }
    public void setResourceDowngradeReason(String resourceDowngradeReason) { this.resourceDowngradeReason = resourceDowngradeReason; touch(); }
    public void setResourceDowngradeCount(int resourceDowngradeCount) { this.resourceDowngradeCount = resourceDowngradeCount; touch(); }
    public void setSinglePageFallback(boolean singlePageFallback) { this.singlePageFallback = singlePageFallback; touch(); }
    public void setProgress(double progress) { this.progress = progress; touch(); }
    public void setProgressStage(String progressStage) { this.progressStage = progressStage; touch(); }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; touch(); }
    public void setQueuePosition(int queuePosition) { this.queuePosition = queuePosition; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
