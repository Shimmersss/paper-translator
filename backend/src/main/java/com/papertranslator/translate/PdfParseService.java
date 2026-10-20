package com.papertranslator.translate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PdfParseService {

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("^\\s*\\d+\\s*$");
    private static final Pattern EQUATION_NUMBER_PATTERN = Pattern.compile("^\\s*\\(?\\d+(?:\\.\\d+)*\\)?\\s*$");
    private static final Pattern WORD_SPLIT_PATTERN = Pattern.compile("[^A-Za-z]+");
    private static final Set<String> COMMON_ENGLISH_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "for", "with", "that", "this", "from", "between", "system", "systems",
            "model", "models", "data", "method", "methods", "results", "study", "studies",
            "reaction", "reactions", "chemical", "using", "used", "were", "was", "are", "is",
            "as", "in", "of", "to", "by", "on", "an", "be", "we", "it", "can"));

    /**
     * 获取 PDF 总页数
     */
    public int getTotalPages(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return document.getNumberOfPages();
        }
    }

    public int getTotalPages(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return document.getNumberOfPages();
        }
    }

    public PdfTextQuality analyzeTextQuality(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("PDF 已加密，请先解密后再上传");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(3, document.getNumberOfPages()));
            return analyzeExtractedText(stripper.getText(document));
        }
    }

    private PdfTextQuality analyzeExtractedText(String text) {
        if (text == null || text.isBlank()) {
            return new PdfTextQuality(false, "");
        }

        int latinLetters = 0;
        int vowels = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                latinLetters++;
                char lower = Character.toLowerCase(c);
                if (lower == 'a' || lower == 'e' || lower == 'i' || lower == 'o' || lower == 'u') {
                    vowels++;
                }
            }
        }

        if (latinLetters < 500) {
            return new PdfTextQuality(false, "");
        }

        int words = 0;
        int commonWords = 0;
        for (String token : WORD_SPLIT_PATTERN.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() < 2) continue;
            words++;
            if (COMMON_ENGLISH_WORDS.contains(token)) {
                commonWords++;
            }
        }

        double vowelRatio = (double) vowels / latinLetters;
        double commonWordRatio = words == 0 ? 0 : (double) commonWords / words;
        if (vowelRatio >= 0.18 || commonWordRatio >= 0.03) {
            return new PdfTextQuality(false, "");
        }

        return new PdfTextQuality(true,
                "PDF 文本层疑似乱码或字体映射异常，当前翻译链路可能读取到错误内容。"
                        + "系统会尝试在翻译前自动修复，请优先检查完成后的预览；如仍不理想，再换用可正常复制英文的 PDF 或 OCR/重新导出。");
    }

    /**
     * 从 PDF 字节数组中提取带位置信息的段落列表（全部页面）
     */
    public List<Paragraph> extractParagraphs(byte[] pdfBytes) throws IOException {
        return extractParagraphs(pdfBytes, 1, Integer.MAX_VALUE);
    }

    /**
     * 从 PDF 字节数组中提取指定页面范围的段落列表
     * @param startPage 起始页码（从 1 开始）
     * @param endPage 结束页码（包含）
     */
    public List<Paragraph> extractParagraphs(byte[] pdfBytes, int startPage, int endPage) throws IOException {
        if (pdfBytes.length < 4 || pdfBytes[0] != '%' || pdfBytes[1] != 'P'
                || pdfBytes[2] != 'D' || pdfBytes[3] != 'F') {
            throw new IllegalArgumentException("上传的文件不是有效的 PDF 格式");
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("PDF 已加密，请先解密后再上传");
            }

            // 用位置捕获 stripper 提取每一行的文本和坐标，再按版面关系重建文本块。
            PositionStripper posStripper = new PositionStripper();
            posStripper.setSortByPosition(true);

            int totalPages = document.getNumberOfPages();
            int effectiveEnd = Math.min(endPage, totalPages);
            List<Paragraph> paragraphs = new ArrayList<>();
            int[] globalIndex = {0};

            for (int page = startPage; page <= effectiveEnd; page++) {
                posStripper.setStartPage(page);
                posStripper.setEndPage(page);
                posStripper.lineBoxes.clear();
                posStripper.getText(document);

                List<LineBox> pageLines = new ArrayList<>(posStripper.lineBoxes);
                appendLayoutBlocks(page, pageLines, paragraphs, globalIndex);
            }

            if (paragraphs.isEmpty()) {
                throw new IllegalArgumentException("该 PDF 无可提取的文本内容，可能为扫描件（暂不支持 OCR）");
            }

            return paragraphs;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("encrypted")) {
                throw new IllegalArgumentException("PDF 已加密，请先解密后再上传", e);
            }
            throw new IOException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 自定义 PDFTextStripper，捕获每行文字的位置信息
     */
    private static class PositionStripper extends PDFTextStripper {
        final List<LineBox> lineBoxes = new ArrayList<>();
        private final List<TextPosition> currentLinePositions = new ArrayList<>();
        private final StringBuilder currentLineText = new StringBuilder();

        public PositionStripper() throws IOException {}

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions != null && !positions.isEmpty()) {
                if (!currentLineText.isEmpty() && text != null && !text.startsWith(" ")) {
                    currentLineText.append(' ');
                }
                if (text != null) {
                    currentLineText.append(text);
                }
                currentLinePositions.addAll(positions);
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
            super.writeLineSeparator();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            flushLine();
            super.endPage(page);
        }

        private void flushLine() {
            if (currentLinePositions.isEmpty()) return;

            appendLineSegments();
            currentLineText.setLength(0);
            currentLinePositions.clear();
        }

        private void appendLineSegments() {
            List<TextPosition> segment = new ArrayList<>();
            TextPosition previous = null;
            for (TextPosition position : currentLinePositions) {
                if (previous != null && isLargeHorizontalGap(previous, position)) {
                    addSegment(segment);
                    segment.clear();
                }
                segment.add(position);
                previous = position;
            }
            addSegment(segment);
        }

        private boolean isLargeHorizontalGap(TextPosition previous, TextPosition current) {
            float previousEnd = previous.getXDirAdj() + previous.getWidthDirAdj();
            float gap = current.getXDirAdj() - previousEnd;
            float threshold = Math.max(previous.getWidthOfSpace() * 2.8f, previous.getFontSizeInPt() * 1.8f);
            return gap > threshold;
        }

        private void addSegment(List<TextPosition> positions) {
            if (positions.isEmpty()) return;

            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = 0, maxY = 0;
            float avgFontSize = 0;
            StringBuilder text = new StringBuilder();
            TextPosition previous = null;

            for (TextPosition tp : positions) {
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float w = tp.getWidthDirAdj();
                float h = tp.getHeightDir();
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x + w > maxX) maxX = x + w;
                if (y + h > maxY) maxY = y + h;
                avgFontSize += tp.getFontSizeInPt();

                if (previous != null && shouldInsertSpace(previous, tp)) {
                    text.append(' ');
                }
                text.append(tp.getUnicode());
                previous = tp;
            }
            avgFontSize /= positions.size();

            String normalized = text.toString().replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                lineBoxes.add(new LineBox(normalized, minX, minY, maxX - minX, maxY - minY, avgFontSize));
            }
        }

        private boolean shouldInsertSpace(TextPosition previous, TextPosition current) {
            float previousEnd = previous.getXDirAdj() + previous.getWidthDirAdj();
            float gap = current.getXDirAdj() - previousEnd;
            return gap > Math.max(previous.getWidthOfSpace() * 0.5f, previous.getFontSizeInPt() * 0.25f);
        }
    }

    /**
     * 一行文字的 bounding box
     */
    record LineBox(String text, float x, float y, float width, float height, float fontSize) {}

    /**
     * PDFMathTranslate/PDF2ZH 类工具的核心方向是“先恢复版面块，再替换文本”。
     * 这里先做轻量 block 重建：同一栏、行距接近的行合并，标题/图注/表注保留为独立块。
     */
    private void appendLayoutBlocks(int page,
                                    List<LineBox> lines,
                                    List<Paragraph> paragraphs,
                                    int[] globalIndex) {
        List<LineBox> block = new ArrayList<>();
        LineBox previous = null;

        for (LineBox line : lines) {
            String text = normalizeLineText(line.text());
            if (!shouldTranslateText(text)) {
                flushBlock(page, block, paragraphs, globalIndex);
                appendProtectedBlock(page, line, paragraphs);
                previous = null;
                continue;
            }

            LineBox normalized = new LineBox(text, line.x(), line.y(), line.width(), line.height(), line.fontSize());
            if (!block.isEmpty() && shouldStartNewBlock(previous, normalized)) {
                flushBlock(page, block, paragraphs, globalIndex);
            }

            block.add(normalized);
            previous = normalized;
        }

        flushBlock(page, block, paragraphs, globalIndex);
    }

    private void flushBlock(int page,
                            List<LineBox> block,
                            List<Paragraph> paragraphs,
                            int[] globalIndex) {
        if (block.isEmpty()) return;

        String text = joinBlockText(block);
        if (!shouldTranslateText(text)) {
            block.clear();
            return;
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = 0, maxY = 0;
        float avgFontSize = 0;
        for (LineBox line : block) {
            if (line.x() < minX) minX = line.x();
            if (line.y() < minY) minY = line.y();
            if (line.x() + line.width() > maxX) maxX = line.x() + line.width();
            if (line.y() + line.height() > maxY) maxY = line.y() + line.height();
            avgFontSize += line.fontSize();
        }
        avgFontSize /= block.size();

        paragraphs.add(new Paragraph(page, globalIndex[0]++, text,
                minX, minY, maxX - minX, maxY - minY, avgFontSize, true));
        block.clear();
    }

    private void appendProtectedBlock(int page, LineBox line, List<Paragraph> paragraphs) {
        String text = normalizeLineText(line.text());
        if (!shouldPreserveOriginalText(text)) return;
        paragraphs.add(new Paragraph(page, -1, text,
                line.x(), line.y(), line.width(), line.height(), line.fontSize(), false));
    }

    private boolean shouldStartNewBlock(LineBox previous, LineBox current) {
        if (previous == null) return false;

        float verticalGap = current.y() - (previous.y() + previous.height());
        boolean sameBaseline = Math.abs(current.y() - previous.y()) < Math.max(2f, previous.fontSize() * 0.35f);
        float xDiff = Math.abs(current.x() - previous.x());
        boolean horizontallyOverlaps = current.x() < previous.x() + previous.width()
                && previous.x() < current.x() + current.width();
        boolean sameRowCell = sameBaseline && xDiff > Math.max(18f, previous.fontSize() * 1.5f);
        boolean differentColumn = !horizontallyOverlaps && xDiff > 24f;
        boolean largeIndentChange = xDiff > Math.max(28f, previous.fontSize() * 3f)
                && !previous.text().endsWith("-");
        boolean largeGap = verticalGap > Math.max(10f, previous.fontSize() * 1.25f);
        boolean captionBoundary = isCaptionLine(previous.text()) || isCaptionLine(current.text());
        boolean headingBoundary = isLikelyHeading(previous) || isLikelyHeading(current);

        return sameRowCell || differentColumn || largeIndentChange || largeGap || captionBoundary || headingBoundary;
    }

    private String joinBlockText(List<LineBox> block) {
        StringBuilder sb = new StringBuilder();
        for (LineBox line : block) {
            String text = line.text();
            if (sb.isEmpty()) {
                sb.append(text);
            } else if (sb.charAt(sb.length() - 1) == '-') {
                sb.deleteCharAt(sb.length() - 1).append(text);
            } else {
                sb.append(' ').append(text);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private String normalizeLineText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private boolean shouldTranslateText(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        if (PAGE_NUMBER_PATTERN.matcher(trimmed).matches()) return false;
        if (looksLikeFormula(trimmed)) return false;
        if (looksLikeShortProtectedText(trimmed)) return false;
        long letters = trimmed.chars().filter(Character::isLetter).count();
        return letters >= 3;
    }

    private boolean shouldPreserveOriginalText(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        if (PAGE_NUMBER_PATTERN.matcher(trimmed).matches()) return true;
        if (looksLikeFormula(trimmed)) return true;
        if (trimmed.matches("^\\[[0-9,\\-\\s]+]$")) return true;
        return looksLikeShortProtectedText(trimmed);
    }

    private boolean looksLikeShortProtectedText(String trimmed) {
        long letters = trimmed.chars().filter(Character::isLetter).count();
        long digits = trimmed.chars().filter(Character::isDigit).count();
        boolean hasSymbol = trimmed.chars().anyMatch(c -> "()[]{}+-=*/%.,:;<>≤≥≈≠_".indexOf(c) >= 0);

        // 清理原 PDF 文本流后，短表格值、坐标轴标签、单位、变量名等也需要原样重绘，避免“删内容”。
        if (trimmed.length() <= 24 && (digits > 0 || hasSymbol)) return true;
        return trimmed.length() <= 12 && letters <= 2;
    }

    private boolean isCaptionLine(String text) {
        return text.matches("(?i)^\\s*(figure|fig\\.|table)\\s+\\d+.*");
    }

    private boolean isLikelyHeading(LineBox line) {
        String text = line.text();
        if (text.length() > 80) return false;
        if (text.matches("(?i)^\\s*(abstract|introduction|conclusion|references)\\s*$")) return true;
        return text.matches("^\\s*(?:[0-9]+|[IVX]+)\\.?\\s+[A-Z][A-Za-z0-9 ,:()/-]{2,}$")
                && !text.endsWith(".")
                && line.width() < 360f;
    }

    /**
     * 粗略过滤公式/变量密集行，避免把数学表达式送去翻译并遮罩。
     */
    private boolean looksLikeFormula(String text) {
        if (EQUATION_NUMBER_PATTERN.matcher(text).matches()) return true;
        long mathChars = text.chars()
                .filter(c -> "=+-*/∑∫√≤≥≈≠∞∂∆∇|".indexOf(c) >= 0)
                .count();
        long letters = text.chars().filter(Character::isLetter).count();
        long spaces = text.chars().filter(Character::isWhitespace).count();
        if (mathChars >= 2 && letters < 20) return true;
        return mathChars >= 3 && spaces <= 3;
    }

    /**
     * 段落记录（带位置信息）
     */
    public record Paragraph(int pageNumber, int index, String text,
                            float x, float y, float width, float height, float fontSize,
                            boolean translatable) {}

    public record PdfTextQuality(boolean suspicious, String warning) {}
}
