package com.papertranslator.translate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TranslationFileSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void supportsPdfAndCommonRasterExtensions() {
        assertEquals(TranslationFileSupport.InputKind.PDF,
                TranslationFileSupport.describe("paper.PDF").kind());
        for (String extension : new String[]{"png", "jpg", "jpeg", "gif", "bmp"}) {
            assertEquals(TranslationFileSupport.InputKind.IMAGE,
                    TranslationFileSupport.describe("image." + extension).kind());
        }
        assertThrows(IllegalArgumentException.class,
                () -> TranslationFileSupport.describe("document.docx"));
    }

    @Test
    void inspectsImageDimensionsWithoutLoadingThroughUploadController() throws Exception {
        Path image = tempDir.resolve("image.png");
        ImageIO.write(new BufferedImage(17, 23, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        TranslationFileSupport.ImageInfo info = TranslationFileSupport.inspectImage(image);
        assertEquals(17, info.width());
        assertEquals(23, info.height());
    }
}
