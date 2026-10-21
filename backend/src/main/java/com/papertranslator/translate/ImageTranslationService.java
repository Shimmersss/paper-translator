package com.papertranslator.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertranslator.config.LlmConfig;
import com.papertranslator.config.ProviderSettings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 图片翻译链路：先把图片送入视觉模型识别文字和位置，再将中文覆盖回原图。
 * 输出 PNG 便于直接使用，同时生成 PDF 版本以保持旧下载接口的兼容性。
 */
@Service
public class ImageTranslationService {

    private static final Logger log = LoggerFactory.getLogger(ImageTranslationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_VISION_RESPONSE_CHARS = 64 * 1024;
    private static final int MAX_TEXT_ITEMS = 500;
    private static final int MAX_JSON_CANDIDATES = 8;
    private static final String VISION_SYSTEM_PROMPT = """
            You are an image text translation engine. Translate visible non-Chinese text in the image into Simplified Chinese.
            Return JSON only, without Markdown fences or commentary, using this exact shape:
            {"items":[{"source":"original text","translation":"简体中文译文","x":0.0,"y":0.0,"width":0.0,"height":0.0}]}
            Coordinates are normalized to 0..1, with x/y at the top-left of each text block. Group lines that belong together.
            Include every readable text block that should be translated, including headings, labels, captions and table text.
            Do not include logos, decorative marks, equations, isolated numbers or text that is already Chinese.
            Keep the boxes tight enough to cover the original text and never invent text that is not visible.
            """;

    private static final String VISION_REPAIR_SYSTEM_PROMPT = """
            You are a strict JSON image text translation engine.
            Your entire response must be exactly one complete valid JSON object and nothing else.
            Do not output analysis, explanations, Markdown fences, or a preamble.
            Use this exact shape:
            {"items":[{"source":"original text","translation":"简体中文译文","x":0.0,"y":0.0,"width":0.0,"height":0.0}]}
            Coordinates must be normalized to 0..1. If no readable text is present, return {"items":[]}.
            Escape every quote and backslash inside JSON strings and make sure the final closing braces are present.
            """;

    private final LlmService llmService;
    private final LlmConfig llmConfig;
    private final ProviderSettings providers;

    public ImageTranslationService(LlmService llmService, LlmConfig llmConfig) {
        this(llmService, llmConfig, null);
    }

    @Autowired
    public ImageTranslationService(LlmService llmService, LlmConfig llmConfig, ProviderSettings providers) {
        this.llmService = llmService;
        this.llmConfig = llmConfig;
        this.providers = providers;
    }

    public java.util.Optional<String> configurationProblem() {
        return llmService.configurationProblem();
    }

    public TranslationFileSupport.ImageInfo inspect(Path imagePath) throws IOException {
        return TranslationFileSupport.inspectImage(imagePath);
    }

    public void translateImage(Path inputImage, Path resultDir, String fileName, String fontFamily,
                               Consumer<BabelDocService.ProgressUpdate> progressConsumer) throws Exception {
        progress(progressConsumer, 5, "image-loading");
        BufferedImage source = readImageSafely(inputImage);
        if (source == null) throw new IllegalArgumentException("图片格式无法读取或图片已损坏");

        Path visionInput = resultDir.resolve("vision-input.png");
        ImageIO.write(source, "png", visionInput.toFile());
        progress(progressConsumer, 25, "image-vision");

        String requestPrompt = "Translate all readable text in this image into Simplified Chinese. File: " + fileName;
        List<TextItem> items = requestAndParse(visionInput, requestPrompt, false);
        progress(progressConsumer, 65, "image-render");

        BufferedImage translated = render(source, items, false, fontFamily);
        BufferedImage bilingual = render(source, items, true, fontFamily);
        Path translatedImage = resultDir.resolve("translated.png");
        Path bilingualImage = resultDir.resolve("bilingual.png");
        ImageIO.write(translated, "png", translatedImage.toFile());
        ImageIO.write(bilingual, "png", bilingualImage.toFile());
        writeText(resultDir.resolve("translated.txt"), items);
        writePdf(translatedImage, resultDir.resolve("translated.pdf"));
        writePdf(bilingualImage, resultDir.resolve("bilingual.pdf"));
        translated.flush();
        bilingual.flush();
        source.flush();
        progress(progressConsumer, 95, "image-export");
        log.info("图片翻译完成: file={}, textBlocks={}", fileName, items.size());
    }

    private String visionModel() {
        String model = providers != null ? providers.llmVisionModel() : llmConfig.getVisionModel();
        return model == null || model.isBlank() ? null : model.trim();
    }

    private List<TextItem> requestAndParse(Path visionInput, String prompt, boolean repair) throws Exception {
        String response;
        try {
            response = llmService.completeWithImages(
                    visionModel(),
                    repair ? VISION_REPAIR_SYSTEM_PROMPT : VISION_SYSTEM_PROMPT,
                    prompt,
                    List.of(visionInput),
                    visionMaxTokens());
            List<TextItem> items = parseItems(response);
            if (!items.isEmpty() || repair) return items;
            // Vision models occasionally answer {"items":[]} for an image that
            // clearly has text; one more identical request usually succeeds.
            log.warn("视觉模型未返回任何文字块，重试一次: {}",
                    response == null ? "" : response.substring(0, Math.min(200, response.length())));
            return parseItems(llmService.completeWithImages(
                    visionModel(), VISION_SYSTEM_PROMPT, prompt, List.of(visionInput), visionMaxTokens()));
        } catch (IOException | RuntimeException firstFailure) {
            if (repair || !isResponseFormatFailure(firstFailure)) throw firstFailure;
            log.warn("视觉模型首次响应不是完整 JSON，启动一次严格 JSON 修复请求: {}", firstFailure.getMessage());
            response = llmService.completeWithImages(
                    visionModel(),
                    VISION_REPAIR_SYSTEM_PROMPT,
                    "上一次响应无法解析为 JSON。请重新读取图片并只返回一个完整 JSON 对象。文件名: " + prompt,
                    List.of(visionInput),
                    visionMaxTokens());
            try {
                return parseItems(response);
            } catch (IOException | RuntimeException secondFailure) {
                throw new IOException("视觉模型连续两次未返回有效 JSON，请重试或更换视觉模型", secondFailure);
            }
        }
    }

    private int visionMaxTokens() {
        return Math.max(4096, Math.min(16_000, llmConfig.getVisionMaxTokens()));
    }

    private boolean isResponseFormatFailure(Throwable failure) {
        String message = failure.getMessage();
        return message == null
                || message.contains("视觉模型返回")
                || message.contains("LLM 响应格式异常")
                || message.contains("解析 LLM 响应");
    }

    private BufferedImage readImageSafely(Path inputImage) throws IOException {
        int maxPixels = Math.max(640 * 480, llmConfig.getVisionImageMaxPixels());
        try (ImageInputStream input = ImageIO.createImageInputStream(inputImage.toFile())) {
            if (input == null) return null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels > (long) maxPixels * 16L) {
                    throw new IllegalArgumentException("图片分辨率过高，请压缩到 400 万像素以内后重试");
                }
                int subsampling = pixels > maxPixels
                        ? Math.max(1, (int) Math.ceil(Math.sqrt(pixels / (double) maxPixels))) : 1;
                var params = reader.getDefaultReadParam();
                if (subsampling > 1) params.setSourceSubsampling(subsampling, subsampling, 0, 0);
                return reader.read(0, params);
            } finally {
                reader.dispose();
            }
        }
    }

    private List<TextItem> parseItems(String response) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("视觉模型原始响应: {}", response == null ? "" : response.substring(0, Math.min(4000, response.length())));
        }
        String json = response == null ? "" : response.trim();
        if (json.length() > MAX_VISION_RESPONSE_CHARS) {
            throw new IOException("视觉模型响应过长，已拒绝解析");
        }
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        JsonNode nodes = null;
        for (String candidate : jsonCandidates(json)) {
            try {
                JsonNode root = MAPPER.readTree(candidate);
                JsonNode candidateNodes = root.isArray() ? root : root.path("items");
                if (candidateNodes.isArray()) {
                    if (root.isArray() && !containsTranslationItems(candidateNodes)) {
                        continue;
                    }
                    nodes = candidateNodes;
                    break;
                }
            } catch (Exception ignored) {
                // Try the next balanced JSON object/array in a verbose model response.
            }
        }
        if (nodes == null) throw new IOException("视觉模型返回的图片翻译不是有效 JSON");

        List<TextItem> items = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (items.size() >= MAX_TEXT_ITEMS) {
                throw new IOException("视觉模型返回的文字块数量超过限制");
            }
            String source = firstText(node, "source", "text", "original");
            String translation = firstText(node, "translation", "translated", "target");
            if (translation.isBlank()) continue;
            double x = number(node, "x", 0);
            double y = number(node, "y", 0);
            double width = number(node, "width", 0);
            double height = number(node, "height", 0);
            JsonNode bounds = node.path("box");
            if (bounds.isObject()) {
                x = number(bounds, "x", x);
                y = number(bounds, "y", y);
                width = number(bounds, "width", width);
                height = number(bounds, "height", height);
            }
            if (width <= 0 && node.has("x2")) width = number(node, "x2", x) - x;
            if (height <= 0 && node.has("y2")) height = number(node, "y2", y) - y;
            double scale = Math.max(Math.max(x, y), Math.max(x + width, y + height));
            if (scale > 1.5) {
                x /= 1000d;
                y /= 1000d;
                width /= 1000d;
                height /= 1000d;
            }
            x = clamp(x, 0, 1);
            y = clamp(y, 0, 1);
            width = clamp(width, 0, 1 - x);
            height = clamp(height, 0, 1 - y);
            if (width < 0.002 || height < 0.002) continue;
            items.add(new TextItem(source, translation, x, y, width, height));
        }
        return items;
    }

    private boolean containsTranslationItems(JsonNode nodes) {
        for (JsonNode node : nodes) {
            if (!node.isObject()) continue;
            if (!firstText(node, "translation", "translated", "target").isBlank()) return true;
        }
        return false;
    }

    private List<String> jsonCandidates(String text) {
        int start = -1;
        ArrayList<Character> delimiters = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (start < 0) {
                if (current == '{' || current == '[') {
                    start = index;
                    delimiters.add(current == '{' ? '}' : ']');
                }
                continue;
            }
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{' || current == '[') {
                delimiters.add(current == '{' ? '}' : ']');
            } else if (current == '}' || current == ']') {
                int top = delimiters.size() - 1;
                if (top < 0 || current != delimiters.get(top)) {
                    start = -1;
                    delimiters.clear();
                    inString = false;
                    escaped = false;
                    continue;
                }
                delimiters.remove(top);
                if (delimiters.isEmpty()) {
                    candidates.add(text.substring(start, index + 1));
                    if (candidates.size() >= MAX_JSON_CANDIDATES) return candidates;
                    start = -1;
                }
            }
        }
        return candidates;
    }

    private BufferedImage render(BufferedImage source, List<TextItem> items, boolean bilingual,
                                 String fontFamily) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, null);

        // Cover every box before drawing any text, so an overlapping neighbour
        // never paints over a translation that was already drawn.
        List<TextBoxRefiner.Placement> placements = new ArrayList<>();
        for (TextItem item : items) {
            int x = (int) Math.round(item.x() * source.getWidth());
            int y = (int) Math.round(item.y() * source.getHeight());
            int width = Math.max(4, (int) Math.round(item.width() * source.getWidth()));
            int height = Math.max(4, (int) Math.round(item.height() * source.getHeight()));
            TextBoxRefiner.Placement placement = TextBoxRefiner.refine(source, x, y, width, height);
            if (log.isDebugEnabled()) {
                log.debug("文字块: model=[{},{} {}x{}] cover={} background={} text={} source={}", x, y, width, height,
                        placement.box(), placement.background(), placement.text(), item.source());
            }
            placements.add(placement);
            graphics.setComposite(AlphaComposite.SrcOver);
            placement.paintCover(graphics);
        }

        for (int index = 0; index < items.size(); index++) {
            TextItem item = items.get(index);
            TextBoxRefiner.Placement placement = placements.get(index);
            int boxX = placement.box().x;
            int boxY = placement.box().y;
            int boxWidth = placement.box().width;
            int boxHeight = placement.box().height;
            int padding = Math.max(1, Math.min(boxWidth, boxHeight) / 20);

            int fontSize = fitFontSize(graphics, item.translation(), boxWidth - padding * 2,
                    boxHeight - padding * 2, bilingual ? 2 : 1);
            Font font = new Font(fontFamilyName(fontFamily), Font.PLAIN, fontSize);
            graphics.setFont(font);
            int cursorY = boxY + padding + graphics.getFontMetrics().getAscent();
            if (bilingual && !item.source().isBlank()) {
                Font sourceFont = font.deriveFont(Math.max(8f, fontSize * 0.62f));
                graphics.setFont(sourceFont);
                graphics.setColor(mix(placement.text(), placement.background(), 0.65));
                cursorY = drawWrapped(graphics, item.source(), boxX + padding, cursorY,
                        boxWidth - padding * 2, sourceFont);
                cursorY += Math.max(2, fontSize / 8);
                graphics.setFont(font);
            }
            graphics.setColor(placement.text());
            drawWrapped(graphics, item.translation(), boxX + padding, cursorY,
                    boxWidth - padding * 2, font);
        }
        graphics.dispose();
        return output;
    }

    private static Color mix(Color foreground, Color background, double weight) {
        return new Color(
                (int) Math.round(foreground.getRed() * weight + background.getRed() * (1 - weight)),
                (int) Math.round(foreground.getGreen() * weight + background.getGreen() * (1 - weight)),
                (int) Math.round(foreground.getBlue() * weight + background.getBlue() * (1 - weight)));
    }

    /** Largest size whose wrapped lines fit the box; 8pt is the floor even if it overflows. */
    private int fitFontSize(Graphics2D graphics, String text, int width, int height, int rows) {
        int max = Math.max(10, Math.min(72, Math.round((float) height / rows * 0.82f)));
        for (int size = max; size >= 8; size--) {
            Font font = new Font("SansSerif", Font.PLAIN, size);
            FontMetrics metrics = graphics.getFontMetrics(font);
            int lines = wrap(text, metrics, Math.max(1, width)).size();
            int needed = lines * metrics.getHeight() - metrics.getLeading();
            if (needed <= Math.max(1, height / rows)) {
                return size;
            }
        }
        return 8;
    }

    private int drawWrapped(Graphics2D graphics, String text, int x, int y, int width, Font font) {
        FontMetrics metrics = graphics.getFontMetrics(font);
        int cursorY = y;
        for (String line : wrap(text, metrics, Math.max(1, width))) {
            // Never drop words: at the minimum size a line may run past the cover
            // slightly, which is better than a silently truncated translation.
            graphics.drawString(line, x, cursorY);
            cursorY += metrics.getHeight();
        }
        return cursorY;
    }

    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String part : (text == null ? "" : text).split("\\n", -1)) {
            for (int index = 0; index < part.length(); index++) {
                char c = part.charAt(index);
                String candidate = line + String.valueOf(c);
                if (metrics.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(c);
            }
            lines.add(line.toString());
            line.setLength(0);
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private void writePdf(Path imagePath, Path pdfPath) throws IOException {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) throw new IOException("无法读取图片翻译结果");
        float scale = Math.min(0.75f, 14400f / Math.max(image.getWidth(), image.getHeight()));
        float width = Math.max(1f, image.getWidth() * scale);
        float height = Math.max(1f, image.getHeight() * scale);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            PDImageXObject pdfImage = PDImageXObject.createFromFile(imagePath.toString(), document);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 0, 0, width, height);
            }
            document.save(pdfPath.toFile());
        } finally {
            image.flush();
        }
    }

    private void writeText(Path target, List<TextItem> items) throws IOException {
        String text = items.isEmpty()
                ? "未检测到可翻译文字。"
                : items.stream().map(TextItem::translation).filter(value -> !value.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("");
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private String fontFamilyName(String requested) {
        return switch (requested == null ? "auto" : requested.toLowerCase(Locale.ROOT)) {
            case "serif" -> "Serif";
            case "script" -> "Serif";
            default -> "SansSerif";
        };
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            if (node.path(name).isTextual()) return node.path(name).asText().trim();
        }
        return "";
    }

    private double number(JsonNode node, String name, double fallback) {
        return node.path(name).isNumber() ? node.path(name).asDouble() : fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void progress(Consumer<BabelDocService.ProgressUpdate> consumer, double percentage, String stage) {
        if (consumer != null) consumer.accept(new BabelDocService.ProgressUpdate(percentage, stage, 1, 1));
    }

    private record TextItem(String source, String translation, double x, double y, double width, double height) {}
}
