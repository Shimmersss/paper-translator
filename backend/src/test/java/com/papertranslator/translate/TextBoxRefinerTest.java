package com.papertranslator.translate;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TextBoxRefinerTest {

    private static final Color PAPER = new Color(250, 248, 240);
    private static final Color INK = new Color(30, 30, 30);

    @Test
    void extendsABoxWhoseTopEdgeCutsThroughTheGlyphs() {
        BufferedImage image = canvas(PAPER);
        drawLine(image, 70, 190, 600, 26, INK);

        // The model's box starts 8px below the top of the strokes.
        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 66, 198, 610, 34);

        Rectangle box = placement.box();
        assertTrue(box.y <= 190, "top should reach the strokes, was " + box.y);
        assertTrue(box.y >= 182, "top should stop at the blank row above the line, was " + box.y);
        assertTrue(box.x <= 70 && box.x + box.width >= 670);
        assertNear(PAPER, placement.background());
        assertNear(INK, placement.text());
    }

    @Test
    void extendsAcrossWordGapsWhenTheBoxStopsShortHorizontally() {
        BufferedImage image = canvas(PAPER);
        drawLine(image, 70, 300, 500, 24, INK);

        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 70, 300, 470, 24);

        assertTrue(placement.box().x + placement.box().width >= 570,
                "right edge should cover the last word, was " + (placement.box().x + placement.box().width));
    }

    @Test
    void coversColouredBannersWithTheirOwnColourWithoutSpillingOutside() {
        Color banner = new Color(168, 59, 50);
        BufferedImage image = canvas(PAPER);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(banner);
        graphics.fillRect(40, 40, 820, 100);
        graphics.dispose();
        drawLine(image, 70, 72, 420, 34, Color.WHITE);

        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 60, 64, 475, 76);

        assertNear(banner, placement.background());
        assertNear(Color.WHITE, placement.text());
        assertTrue(placement.box().y + placement.box().height <= 146,
                "box must not grow into the page below the banner");
    }

    @Test
    void trimsTheBoxBackWhenItOvershootsTheBanner() {
        Color banner = new Color(168, 59, 50);
        BufferedImage image = canvas(PAPER);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(banner);
        graphics.fillRect(40, 40, 820, 100);
        graphics.dispose();
        drawLine(image, 70, 72, 420, 34, Color.WHITE);

        // The model's box starts at the image edge, 40px left of the banner.
        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 0, 48, 320, 70);

        assertNear(banner, placement.background());
        assertTrue(placement.box().x >= 38, "cover must not spill left of the banner, was " + placement.box().x);
    }

    @Test
    void reachesTextWhenTheBoxSitsHalfALineTooHigh() {
        BufferedImage image = canvas(PAPER);
        drawLine(image, 70, 300, 300, 26, INK);
        drawLine(image, 70, 350, 300, 26, INK);

        // The model's box covers only the top third of the first row.
        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 66, 284, 310, 24);

        int bottom = placement.box().y + placement.box().height;
        assertTrue(bottom >= 326, "bottom should reach the end of the row, was " + bottom);
        assertTrue(bottom < 350, "bottom must stop before the next row, was " + bottom);
    }

    @Test
    void anchorsToALabelWhenTheBoxStraddlesItsTopEdge() {
        Color slide = new Color(28, 34, 52);
        Color tag = new Color(46, 125, 220);
        BufferedImage image = canvas(slide);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(tag);
        graphics.fillRect(60, 130, 240, 40);
        graphics.dispose();
        drawLine(image, 76, 138, 180, 22, Color.WHITE);

        // The box starts above the tag, over plain slide background.
        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 60, 112, 240, 34);

        assertNear(tag, placement.background());
        assertNear(Color.WHITE, placement.text());
        Rectangle box = placement.box();
        assertTrue(box.y > 130 && box.y <= 138 && box.y + box.height >= 160 && box.y + box.height < 170,
                "cover should hold the tag's text without touching its edges, was " + box);
    }

    @Test
    void keepsALabelThatTheBoxFullyEncloses() {
        Color slide = new Color(28, 34, 52);
        Color tag = new Color(46, 125, 220);
        BufferedImage image = canvas(slide);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(tag);
        graphics.fillRect(60, 130, 240, 40);
        graphics.dispose();
        drawLine(image, 76, 138, 180, 22, Color.WHITE);

        // The box surrounds the whole tag with a margin of slide background.
        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 52, 124, 256, 52);

        assertNear(tag, placement.background());
        assertNear(Color.WHITE, placement.text());
        Rectangle box = placement.box();
        assertTrue(box.x > 60 && box.y > 130 && box.x + box.width < 300 && box.y + box.height < 170,
                "cover must stay inside the tag so its rounded corners survive, was " + box);
    }

    @Test
    void leavesRoundedLabelCornersWhenTheBoxMatchesTheLabelExactly() {
        Color slide = new Color(28, 34, 52);
        Color tag = new Color(46, 125, 220);
        for (Rectangle model : new Rectangle[] {new Rectangle(60, 129, 240, 42), new Rectangle(60, 130, 240, 40)}) {
            BufferedImage image = canvas(slide);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(tag);
            graphics.fillRoundRect(60, 130, 240, 40, 16, 16);
            graphics.dispose();
            drawLine(image, 76, 138, 180, 22, Color.WHITE);

            TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, model.x, model.y, model.width, model.height);

            assertNear(tag, placement.background());
            Rectangle box = placement.box();
            assertTrue(box.x > 62 && box.y > 131 && box.x + box.width < 298 && box.y + box.height < 169,
                    "cover must stay clear of the rounded corners for " + model + ", was " + box);
        }
    }

    @Test
    void followsAVerticalGradientInsteadOfAFlatPatch() {
        BufferedImage image = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            int shade = 20 + y / 5;
            Color color = new Color(shade, shade + 8, shade + 28);
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, color.getRGB());
        }
        drawLine(image, 60, 100, 300, 60, new Color(240, 240, 245));

        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 56, 96, 310, 68);

        Color first = placement.rowLeft()[0];
        Color last = placement.rowLeft()[placement.rowLeft().length - 1];
        assertTrue(last.getRed() - first.getRed() >= 10, "cover should darken-to-lighten with the slide: " + first + " -> " + last);
        int firstRow = placement.box().y;
        assertNear(new Color(image.getRGB(10, firstRow)), first);
    }

    @Test
    void doesNotMergeTheNextLineSeparatedByABlankGap() {
        BufferedImage image = canvas(PAPER);
        drawLine(image, 70, 250, 400, 24, INK);
        drawLine(image, 70, 300, 400, 24, INK);

        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 70, 250, 400, 24);

        assertTrue(placement.box().y + placement.box().height < 300,
                "the box for line one must not reach line two");
    }

    @Test
    void keepsTheModelBoxOnBusyBackgrounds() {
        BufferedImage image = canvas(PAPER);
        Random random = new Random(7);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt(0xffffff));
            }
        }

        TextBoxRefiner.Placement placement = TextBoxRefiner.refine(image, 100, 100, 200, 40);

        assertEquals(Color.WHITE, placement.background());
        assertEquals(Color.BLACK, placement.text());
        Rectangle box = placement.box();
        assertTrue(box.x >= 90 && box.y >= 90 && box.x + box.width <= 310 && box.y + box.height <= 150,
                "noise must not make the box grow: " + box);
    }

    private static BufferedImage canvas(Color color) {
        BufferedImage image = new BufferedImage(900, 520, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        return image;
    }

    /** Words as solid stroke blocks separated by narrow gaps, like a line of text. */
    private static void drawLine(BufferedImage image, int x, int y, int width, int height, Color color) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        int cursor = x;
        int word = 0;
        while (cursor < x + width) {
            int wordWidth = Math.min(40 + (word * 17) % 50, x + width - cursor);
            for (int stroke = cursor; stroke < cursor + wordWidth; stroke += 6) {
                int strokeTop = y + ((stroke / 6) % 3 == 0 ? 0 : height / 4);
                graphics.fillRect(stroke, strokeTop, 3, y + height - strokeTop);
            }
            cursor += wordWidth + Math.max(6, height / 3);
            word++;
        }
        graphics.dispose();
    }

    private static void assertNear(Color expected, Color actual) {
        int distance = Math.abs(expected.getRed() - actual.getRed())
                + Math.abs(expected.getGreen() - actual.getGreen())
                + Math.abs(expected.getBlue() - actual.getBlue());
        assertTrue(distance < 45, "expected about " + expected + " but was " + actual);
    }
}
