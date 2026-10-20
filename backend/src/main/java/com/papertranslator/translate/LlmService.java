package com.papertranslator.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertranslator.config.LlmConfig;
import com.papertranslator.config.ProviderSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final RestClient llmRestClient;
    private final LlmConfig llmConfig;
    private final ProviderSettings providers;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmService(RestClient llmRestClient, LlmConfig llmConfig, ProviderSettings providers) {
        this.llmRestClient = llmRestClient;
        this.llmConfig = llmConfig;
        this.providers = providers;
    }

    /** Checked before an image task is queued so a missing key is reported instead of a generic failure. */
    public java.util.Optional<String> configurationProblem() {
        if (providers.llmKey().isBlank()) return java.util.Optional.of("未配置 LLM_API_KEY，图片翻译需要视觉模型，请在 .env 中填写后重启服务");
        if (providers.llmUrl().isBlank()) return java.util.Optional.of("未配置 LLM_API_URL");
        return java.util.Optional.empty();
    }

    public String completeWithImages(String model, String systemPrompt, String userPrompt,
                                     List<Path> imagePaths, int maxTokens) {
        if (providers.llmKey().isBlank()) {
            throw new IllegalStateException("LLM API Key 未配置，请在 .env 中设置 LLM_API_KEY");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("用户提示词不能为空");
        }

        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userPrompt));
        long totalImageBytes = 0L;
        if (imagePaths != null) {
            for (Path imagePath : imagePaths) {
                if (imagePath == null || !Files.isRegularFile(imagePath)) continue;
                try {
                    EncodedImage encoded = encodeVisionImage(imagePath);
                    if (encoded == null || encoded.data().length == 0) continue;
                    if (totalImageBytes + encoded.data().length > Math.max(1L, llmConfig.getVisionBatchMaxBytes())) {
                        log.warn("视觉批次达到字节预算，跳过剩余图片: {}", imagePath);
                        break;
                    }
                    totalImageBytes += encoded.data().length;
                    String encodedData = Base64.getEncoder().encodeToString(encoded.data());
                    if (providers.resolvedLlmProtocol().equals("CLAUDE")) {
                        content.add(Map.of("type", "image", "source", Map.of(
                                "type", "base64", "media_type", encoded.mediaType(), "data", encodedData)));
                    } else {
                        content.add(Map.of("type", "image_url", "image_url", Map.of(
                                "url", "data:" + encoded.mediaType() + ";base64," + encodedData, "detail", "auto")));
                    }
                } catch (IOException e) {
                    log.warn("读取视觉模型图片失败: {}", imagePath, e);
                }
            }
        }

        String selectedModel = model == null || model.isBlank() ? providers.llmModel() : model;
        String selectedSystem = systemPrompt == null || systemPrompt.isBlank() ? "You are a helpful assistant." : systemPrompt;
        Map<String, Object> requestBody = visionRequest(selectedModel, selectedSystem, userPrompt, content, Math.max(512, maxTokens));

        int maxRetries = 1;
        long delayMs = 1000;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String responseJson = llmRestClient.post()
                        .uri(providers.llmEndpoint())
                        .headers(headers -> applyHeaders(headers))
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
                return extractContent(responseJson);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("401") || msg.contains("403") || msg.contains("400"))) {
                    throw new RuntimeException("LLM 视觉模型调用失败: " + msg, e);
                }
                if (attempt < maxRetries) {
                    log.warn("LLM 视觉模型调用失败 (第{}次)，{}ms 后重试: {}", attempt + 1, delayMs, msg);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM 视觉模型调用被中断", ie);
                    }
                } else {
                    throw new RuntimeException("LLM 视觉模型调用失败: " + msg, e);
                }
            }
        }
        throw new RuntimeException("LLM 视觉模型调用失败: 未知错误");
    }

    private Map<String, Object> visionRequest(String model, String systemPrompt, String userPrompt,
                                               List<Object> content, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (providers.resolvedLlmProtocol().equals("CLAUDE")) {
            body.put("system", systemPrompt);
            body.put("messages", List.of(Map.of("role", "user", "content", content)));
        } else {
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", content)));
        }
        return body;
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers) {
        applyHeaders(headers, providers.llmKey(), providers.resolvedLlmProtocol());
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers, String apiKey, String protocol) {
        headers.remove("Authorization");
        headers.remove("x-api-key");
        headers.remove("anthropic-version");
        if ("CLAUDE".equals(protocol)) {
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
        } else {
            headers.setBearerAuth(apiKey);
        }
    }

    private String mediaType(Path imagePath) {
        String name = imagePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "";
    }

    private record EncodedImage(String mediaType, byte[] data) {}

    private EncodedImage encodeVisionImage(Path imagePath) throws IOException {
        String sourceType = mediaType(imagePath);
        if (sourceType.isBlank()) return null;
        long sourceBytes = Files.size(imagePath);
        long maxBytes = Math.max(64 * 1024L, llmConfig.getVisionImageMaxBytes());
        if (sourceBytes > maxBytes * 4L) {
            log.warn("视觉图片原文件过大，跳过: {} bytes={}", imagePath, sourceBytes);
            return null;
        }
        int maxPixels = Math.max(640 * 480, llmConfig.getVisionImageMaxPixels());
        BufferedImage source = null;
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(imagePath.toFile())) {
            if (input == null) return null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            reader = readers.next();
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            long pixels = (long) width * height;
            int subsampling = pixels > maxPixels
                    ? Math.max(1, (int) Math.ceil(Math.sqrt(pixels / (double) maxPixels))) : 1;
            var params = reader.getDefaultReadParam();
            if (subsampling > 1) params.setSourceSubsampling(subsampling, subsampling, 0, 0);
            source = reader.read(0, params);
        } finally {
            if (reader != null) reader.dispose();
        }
        if (source == null) return null;
        BufferedImage resized = source;
        long decodedPixels = (long) resized.getWidth() * resized.getHeight();
        if (decodedPixels > maxPixels) {
            double scale = Math.sqrt(maxPixels / (double) decodedPixels);
            int targetWidth = Math.max(1, (int) Math.round(resized.getWidth() * scale));
            int targetHeight = Math.max(1, (int) Math.round(resized.getHeight() * scale));
            resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();
            source.flush();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 512 * 1024L));
        ImageIO.write(resized, "jpg", output);
        if (resized != source) resized.flush();
        byte[] data = output.toByteArray();
        if (data.length > maxBytes) {
            return null;
        }
        return new EncodedImage("image/jpeg", data);
    }

    /**
     * 从 Anthropic Messages API 响应中提取翻译内容
     * 响应格式：{"content": [{"type": "text", "text": "..."}], "stop_reason": "end_turn", ...}
     */
    private String extractContent(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                // Anthropic 返回 content 数组，取第一个 text 类型的块
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        String text = block.get("text").asText().trim();
                        if (!text.isEmpty()) {
                            return text;
                        }
                    }
                }
            }
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message").path("content");
                if (message.isTextual() && !message.asText().isBlank()) return message.asText().trim();
                if (message.isArray()) {
                    for (JsonNode block : message) {
                        if (block.path("text").isTextual() && !block.path("text").asText().isBlank()) {
                            return block.path("text").asText().trim();
                        }
                    }
                }
            }
            throw new RuntimeException("LLM 响应格式异常: " + responseJson.substring(0, Math.min(200, responseJson.length())));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM 响应失败: " + e.getMessage(), e);
        }
    }
}
