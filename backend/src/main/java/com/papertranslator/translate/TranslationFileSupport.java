package com.papertranslator.translate;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 翻译上传文件的格式识别和安全边界。图片统一按 ImageIO 能解码的常见格式处理，
 * 不根据用户提交的 Content-Type 直接信任文件内容。
 */
final class TranslationFileSupport {

    private TranslationFileSupport() {}

    static FileDescriptor describe(String fileName) {
        String extension = extensionOf(fileName);
        if ("pdf".equals(extension)) {
            return new FileDescriptor(InputKind.PDF, extension);
        }
        if (SetOfImages.contains(extension)) {
            return new FileDescriptor(InputKind.IMAGE, extension);
        }
        throw new IllegalArgumentException("仅支持 PDF、PNG、JPG、JPEG、GIF、BMP 图片格式");
    }

    static ImageInfo inspectImage(Path path) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IllegalArgumentException("图片格式无法读取");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("不支持的图片格式或图片已损坏");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw new IllegalArgumentException("图片尺寸无效");
                return new ImageInfo(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    static String extensionOf(String fileName) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) return "";
        return name.substring(dot + 1);
    }

    enum InputKind { PDF, IMAGE }

    record FileDescriptor(InputKind kind, String extension) {}

    record ImageInfo(int width, int height) {}

    private static final java.util.Set<String> SetOfImages = java.util.Set.of(
            "png", "jpg", "jpeg", "gif", "bmp");
}
