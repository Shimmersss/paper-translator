package com.papertranslator.translate;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Vision models return approximate text boxes: edges often cut through the
 * glyphs, leaving strokes of the original visible next to the translation.
 * This snaps a box to the ink actually present around it and picks the cover
 * and text colors from the image, so the original text is hidden on colored
 * backgrounds as well as on white ones.
 *
 * An edge is moved only when the ink ends at a clean blank row or column within
 * a bounded distance. Busy backgrounds (photos) or neighboring text therefore
 * leave the model's box unchanged rather than growing it unpredictably.
 *
 * The cover follows the background: each row is interpolated between the
 * colors just left and right of the box, so gradients do not show a flat patch.
 */
final class TextBoxRefiner {

    /** Squared RGB distance from the background above which a pixel counts as ink. */
    private static final int INK_DISTANCE_SQUARED = 56 * 56;
    /** A cover color is trusted only when this share of the surrounding ring matches it. */
    private static final double MIN_BACKGROUND_SHARE = 0.35;

    /**
     * Where and how to cover one text block. {@code rowLeft}/{@code rowRight} hold
     * the background color beside each row of {@code box}, for a gradient cover.
     */
    record Placement(Rectangle box, Color background, Color text, Color[] rowLeft, Color[] rowRight) {

        Placement(Rectangle box, Color background, Color text) {
            this(box, background, text, null, null);
        }

        void paintCover(java.awt.Graphics2D graphics) {
            if (rowLeft == null || rowRight == null) {
                graphics.setColor(background);
                graphics.fill(box);
                return;
            }
            int right = box.x + box.width;
            for (int index = 0; index < box.height; index++) {
                int row = box.y + index;
                graphics.setPaint(new java.awt.GradientPaint(box.x, row, rowLeft[index], right, row, rowRight[index]));
                graphics.fillRect(box.x, row, box.width, 1);
            }
        }
    }

    private TextBoxRefiner() {
    }

    static Placement refine(BufferedImage image, int x, int y, int width, int height) {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        Rectangle box = new Rectangle(x, y, Math.max(1, width), Math.max(1, height))
                .intersection(new Rectangle(0, 0, imageWidth, imageHeight));
        if (box.isEmpty()) {
            return new Placement(new Rectangle(x, y, Math.max(1, width), Math.max(1, height)), Color.WHITE, Color.BLACK);
        }

        Color background = ringBackground(image, box);
        if (background == null) {
            return new Placement(pad(box, Math.max(2, Math.min(box.width, box.height) / 14), imageWidth, imageHeight),
                    Color.WHITE, Color.BLACK);
        }

        // A region edge (banner, photo) is many solid columns wide; a glyph stem is only a few.
        int columnRun = Math.max(6, box.height / 3);
        int rowRun = Math.max(4, box.height / 5);
        // A second solid color next to the text (the page around a label, or a
        // label's corner) is never part of the text and must not be covered.
        Color outside = secondaryRingColor(image, box, background);
        Rectangle label = enclosedRegion(image, background, box);
        if (label == null) label = labelRegion(image, background, box, rowRun);
        if (label != null) {
            // The box sits on a label/button: the text is on the label, so cover
            // it in the label's color, and only around the text so the label's
            // corners and border survive.
            outside = background;
            background = dominantColor(image, label);
            Rectangle ink = inkBounds(image, background, label, outside);
            box = ink != null ? ink : label;
        }
        box = trimSolidEdges(image, background, box, columnRun, rowRun);
        // Model boxes can be off by about half a line; growth still stops at the
        // first blank row, so a generous limit does not reach the next line.
        int verticalLimit = Math.max(3, (int) Math.round(box.height * 1.5));
        int top = growVertically(image, background, box.y, -1, verticalLimit, rowRun, box.x, box.x + box.width - 1);
        int bottom = growVertically(image, background, box.y + box.height - 1, 1, verticalLimit, rowRun,
                box.x, box.x + box.width - 1);
        int lineHeight = Math.max(4, longestInkRun(image, background, box.x, box.x + box.width, top, bottom));
        // Horizontal offsets can span several words; growth still stops at the first
        // gap wider than a word space, so this does not reach a neighbouring block.
        int horizontalLimit = Math.max(6, lineHeight * 4);
        int gapTolerance = Math.max(3, (int) Math.round(lineHeight * 0.6));
        int left = growHorizontally(image, background, box.x, -1, horizontalLimit, gapTolerance, columnRun, top, bottom);
        int right = growHorizontally(image, background, box.x + box.width - 1, 1, horizontalLimit, gapTolerance,
                columnRun, top, bottom);

        Rectangle refined = new Rectangle(left, top, right - left + 1, bottom - top + 1);
        Color text = inkColor(image, background, refined);
        if (outside != null && !isInk(text.getRGB(), outside)) outside = null;
        // Cover just the original text (plus padding), not the model's whole box,
        // so label corners, borders and nearby graphics are left alone.
        Rectangle ink = inkBounds(image, background, refined, outside);
        if (ink != null) refined = ink;
        // Faint anti-aliased edges (a period, a hairline serif) fall below the ink
        // threshold, so pad by a quarter line to hide them too.
        Rectangle cover = padTowardsBackground(image, background, refined, Math.max(3, Math.round(lineHeight / 4f)));
        return new Placement(cover, background, text,
                sideColors(image, background, cover, -1), sideColors(image, background, cover, 1));
    }

    /**
     * When most of the box is one color that differs from its surroundings, the
     * box encloses a label or button: returns that region's bounds, else null.
     */
    private static Rectangle enclosedRegion(BufferedImage image, Color background, Rectangle box) {
        Color interior = dominantColor(image, box);
        if (!isInk(interior.getRGB(), background) || colorShare(image, interior, box) < 0.5) return null;
        int left = box.x;
        int right = box.x + box.width - 1;
        int top = box.y;
        int bottom = box.y + box.height - 1;
        while (top < bottom && !mostlyColor(image, interior, top, left, right)) top++;
        while (bottom > top && !mostlyColor(image, interior, bottom, left, right)) bottom--;
        while (left < right && !mostlyColorColumn(image, interior, left, top, bottom)) left++;
        while (right > left && !mostlyColorColumn(image, interior, right, top, bottom)) right--;
        int limit = Math.max(4, box.height / 2);
        for (int step = 0; step < limit && top > 0 && mostlyColor(image, interior, top - 1, left, right); step++) top--;
        for (int step = 0; step < limit && bottom + 1 < image.getHeight()
                && mostlyColor(image, interior, bottom + 1, left, right); step++) bottom++;
        for (int step = 0; step < limit && left > 0 && mostlyColorColumn(image, interior, left - 1, top, bottom); step++) left--;
        for (int step = 0; step < limit && right + 1 < image.getWidth()
                && mostlyColorColumn(image, interior, right + 1, top, bottom); step++) right++;
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /** Bounds of the pixels that differ from the background and, if given, from {@code exclude}. */
    private static Rectangle inkBounds(BufferedImage image, Color background, Rectangle area, Color exclude) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = -1, bottom = -1;
        for (int row = area.y; row < area.y + area.height; row++) {
            for (int column = area.x; column < area.x + area.width; column++) {
                int rgb = image.getRGB(column, row);
                if (!isInk(rgb, background) || (exclude != null && !isInk(rgb, exclude))) continue;
                left = Math.min(left, column);
                right = Math.max(right, column);
                top = Math.min(top, row);
                bottom = Math.max(bottom, row);
            }
        }
        return right < 0 ? null : new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    private static double colorShare(BufferedImage image, Color color, Rectangle area) {
        int matching = 0;
        for (int row = area.y; row < area.y + area.height; row++) {
            for (int column = area.x; column < area.x + area.width; column++) {
                if (!isInk(image.getRGB(column, row), color)) matching++;
            }
        }
        return matching / (double) Math.max(1, area.width * area.height);
    }

    private static boolean mostlyColorColumn(BufferedImage image, Color color, int column, int top, int bottom) {
        int matching = 0;
        for (int row = top; row <= bottom; row++) {
            if (!isInk(image.getRGB(column, row), color)) matching++;
        }
        return matching >= (bottom - top + 1) * 0.5;
    }

    /**
     * When solid rows of another color fill the top or bottom of the box while the
     * rest of the box holds no ink, returns the bounds of that region (a label,
     * tag or button the text actually sits on). Otherwise returns null.
     */
    private static Rectangle labelRegion(BufferedImage image, Color background, Rectangle box, int rowRun) {
        int left = box.x;
        int right = box.x + box.width - 1;
        int top = box.y;
        int bottom = box.y + box.height - 1;
        int solidTop = 0;
        while (top + solidTop <= bottom && solidRow(image, background, top + solidTop, left, right)) solidTop++;
        int solidBottom = 0;
        while (bottom - solidBottom >= top && solidRow(image, background, bottom - solidBottom, left, right)) solidBottom++;
        boolean regionBelow = solidBottom >= rowRun && solidTop == 0;
        boolean regionAbove = solidTop >= rowRun && solidBottom == 0;
        if (!regionBelow && !regionAbove) return null;
        int clearFrom = regionAbove ? top + solidTop : top;
        int clearTo = regionBelow ? bottom - solidBottom : bottom;
        for (int row = clearFrom; row <= clearTo; row++) {
            if (rowHasInk(image, background, row, left, right + 1)) return null;
        }

        Rectangle seed = regionBelow
                ? new Rectangle(left, bottom - solidBottom + 1, box.width, solidBottom)
                : new Rectangle(left, top, box.width, solidTop);
        Color region = dominantColor(image, seed);
        int regionTop = seed.y;
        int regionBottom = seed.y + seed.height - 1;
        int limit = box.height * 3;
        for (int step = 0; step < limit && regionBottom + 1 < image.getHeight()
                && mostlyColor(image, region, regionBottom + 1, left, right); step++) regionBottom++;
        for (int step = 0; step < limit && regionTop - 1 >= 0
                && mostlyColor(image, region, regionTop - 1, left, right); step++) regionTop--;
        return new Rectangle(left, regionTop, box.width, regionBottom - regionTop + 1);
    }

    private static boolean mostlyColor(BufferedImage image, Color color, int row, int left, int right) {
        int matching = 0;
        int total = 0;
        for (int column = Math.max(0, left); column <= Math.min(image.getWidth() - 1, right); column++) {
            total++;
            if (!isInk(image.getRGB(column, row), color)) matching++;
        }
        return total > 0 && matching >= total * 0.5;
    }

    private static Color dominantColor(BufferedImage image, Rectangle area) {
        int[] counts = new int[4096];
        long[][] sums = new long[4096][3];
        for (int row = area.y; row < area.y + area.height; row++) {
            for (int column = area.x; column < area.x + area.width; column++) {
                int rgb = image.getRGB(column, row);
                int bin = ((rgb >> 20) & 0xf) << 8 | ((rgb >> 12) & 0xf) << 4 | ((rgb >> 4) & 0xf);
                counts[bin]++;
                sums[bin][0] += (rgb >> 16) & 0xff;
                sums[bin][1] += (rgb >> 8) & 0xff;
                sums[bin][2] += rgb & 0xff;
            }
        }
        int best = 0;
        for (int bin = 1; bin < counts.length; bin++) {
            if (counts[bin] > counts[best]) best = bin;
        }
        return counts[best] == 0 ? Color.WHITE : new Color((int) (sums[best][0] / counts[best]),
                (int) (sums[best][1] / counts[best]), (int) (sums[best][2] / counts[best]));
    }

    /**
     * Background color beside each row of the cover, sampled a few pixels outside
     * it. Samples that look like ink or another region fall back to the nearest
     * usable row, then to the flat background.
     */
    private static Color[] sideColors(BufferedImage image, Color background, Rectangle cover, int direction) {
        Color[] colors = new Color[cover.height];
        int start = direction < 0 ? cover.x - 1 : cover.x + cover.width;
        for (int index = 0; index < cover.height; index++) {
            int row = cover.y + index;
            long red = 0, green = 0, blue = 0;
            int count = 0;
            for (int offset = 0; offset < 4; offset++) {
                int column = start + direction * offset;
                if (column < 0 || column >= image.getWidth()) break;
                int rgb = image.getRGB(column, row);
                if (isInk(rgb, background)) continue;
                red += (rgb >> 16) & 0xff;
                green += (rgb >> 8) & 0xff;
                blue += rgb & 0xff;
                count++;
            }
            colors[index] = count == 0 ? null : new Color((int) (red / count), (int) (green / count), (int) (blue / count));
        }
        Color previous = null;
        for (int index = 0; index < colors.length; index++) {
            if (colors[index] == null) colors[index] = previous; else previous = colors[index];
        }
        previous = null;
        for (int index = colors.length - 1; index >= 0; index--) {
            if (colors[index] == null) colors[index] = previous; else previous = colors[index];
        }
        for (int index = 0; index < colors.length; index++) {
            if (colors[index] == null) colors[index] = background;
        }
        return colors;
    }

    /**
     * Drops runs of edge rows and columns that are almost entirely another solid
     * colour: the model box overshot into a neighbouring region (a banner edge,
     * a photo) rather than covering text.
     */
    private static Rectangle trimSolidEdges(BufferedImage image, Color background, Rectangle box,
                                            int columnRun, int rowRun) {
        int left = box.x;
        int right = box.x + box.width - 1;
        int top = box.y;
        int bottom = box.y + box.height - 1;
        int maxColumns = box.width / 2;
        int maxRows = box.height / 2;
        int run = 0;
        while (run < maxColumns && solidColumn(image, background, left + run, top, bottom)) run++;
        if (run >= columnRun) left += run;
        run = 0;
        while (run < maxColumns && solidColumn(image, background, right - run, top, bottom)) run++;
        if (run >= columnRun) right -= run;
        run = 0;
        while (run < maxRows && solidRow(image, background, top + run, left, right)) run++;
        if (run >= rowRun) top += run;
        run = 0;
        while (run < maxRows && solidRow(image, background, bottom - run, left, right)) run++;
        if (run >= rowRun) bottom -= run;
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    private static boolean solidColumn(BufferedImage image, Color background, int column, int top, int bottom) {
        if (column < 0 || column >= image.getWidth()) return false;
        int from = Math.max(0, top);
        int to = Math.min(image.getHeight() - 1, bottom);
        if (to < from) return false;
        int foreign = 0;
        for (int row = from; row <= to; row++) {
            if (isInk(image.getRGB(column, row), background)) foreign++;
        }
        return foreign >= (to - from + 1) * 0.9;
    }

    private static boolean solidRow(BufferedImage image, Color background, int row, int left, int right) {
        if (row < 0 || row >= image.getHeight()) return false;
        int from = Math.max(0, left);
        int to = Math.min(image.getWidth() - 1, right);
        if (to < from) return false;
        int foreign = 0;
        for (int column = from; column <= to; column++) {
            if (isInk(image.getRGB(column, row), background)) foreign++;
        }
        return foreign >= (to - from + 1) * 0.9;
    }

    /**
     * Moves a top or bottom edge outward while the rows beyond it still contain ink.
     * Returns the original edge unless a blank row is reached within the limit;
     * running into the image border or a solid region of another colour also
     * counts as "no clean boundary".
     */
    private static int growVertically(BufferedImage image, Color background, int edge, int direction, int limit,
                                      int rowRun, int left, int right) {
        int last = edge;
        int solid = 0;
        for (int step = 1; step <= limit; step++) {
            int row = edge + direction * step;
            if (row < 0 || row >= image.getHeight()) return edge;
            if (!rowHasInk(image, background, row, left, right + 1)) return last;
            solid = solidRow(image, background, row, left, right) ? solid + 1 : 0;
            if (solid >= rowRun) return edge;
            last = row;
        }
        return edge;
    }

    /** Like {@link #growVertically} for columns, tolerating word gaps up to {@code gapTolerance}. */
    private static int growHorizontally(BufferedImage image, Color background, int edge, int direction, int limit,
                                        int gapTolerance, int columnRun, int top, int bottom) {
        int lastInk = edge;
        int gap = 0;
        int solid = 0;
        int lastInkBeforeSolid = edge;
        int gapBeforeSolid = 0;
        for (int step = 1; step <= limit + gapTolerance; step++) {
            int column = edge + direction * step;
            if (column < 0 || column >= image.getWidth()) return gap > 0 ? lastInk : edge;
            if (solidColumn(image, background, column, top, bottom)) {
                if (solid == 0) {
                    lastInkBeforeSolid = lastInk;
                    gapBeforeSolid = gap;
                }
                if (++solid >= columnRun) return gapBeforeSolid > 0 ? lastInkBeforeSolid : edge;
            } else {
                solid = 0;
            }
            if (columnHasInk(image, background, column, top, bottom + 1)) {
                if (step > limit) return edge;
                lastInk = column;
                gap = 0;
            } else if (++gap > gapTolerance) {
                return lastInk;
            }
        }
        return edge;
    }

    /** Pads for anti-aliased glyph edges, except on sides that border another solid region. */
    private static Rectangle padTowardsBackground(BufferedImage image, Color background, Rectangle box, int padding) {
        int top = box.y;
        int bottom = box.y + box.height - 1;
        int left = box.x;
        int right = box.x + box.width - 1;
        int padLeft = solidColumn(image, background, left - 1, top, bottom) ? 0 : padding;
        int padRight = solidColumn(image, background, right + 1, top, bottom) ? 0 : padding;
        int padTop = solidRow(image, background, top - 1, left, right) ? 0 : padding;
        int padBottom = solidRow(image, background, bottom + 1, left, right) ? 0 : padding;
        return new Rectangle(left - padLeft, top - padTop,
                box.width + padLeft + padRight, box.height + padTop + padBottom)
                .intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
    }

    private static int longestInkRun(BufferedImage image, Color background, int fromX, int toX, int top, int bottom) {
        int longest = 0;
        int current = 0;
        for (int row = top; row <= bottom; row++) {
            if (rowHasInk(image, background, row, fromX, toX)) {
                longest = Math.max(longest, ++current);
            } else {
                current = 0;
            }
        }
        return longest;
    }

    private static boolean rowHasInk(BufferedImage image, Color background, int row, int fromX, int toX) {
        int count = 0;
        for (int column = Math.max(0, fromX); column < Math.min(image.getWidth(), toX); column++) {
            if (isInk(image.getRGB(column, row), background) && ++count >= 2) return true;
        }
        return false;
    }

    private static boolean columnHasInk(BufferedImage image, Color background, int column, int fromY, int toY) {
        for (int row = Math.max(0, fromY); row < Math.min(image.getHeight(), toY); row++) {
            if (isInk(image.getRGB(column, row), background)) return true;
        }
        return false;
    }

    private static boolean isInk(int rgb, Color background) {
        int red = ((rgb >> 16) & 0xff) - background.getRed();
        int green = ((rgb >> 8) & 0xff) - background.getGreen();
        int blue = (rgb & 0xff) - background.getBlue();
        return red * red + green * green + blue * blue > INK_DISTANCE_SQUARED;
    }

    /**
     * A second color that fills a sizeable share of the ring around the box (the
     * page around a label, a neighbouring panel), or null.
     */
    private static Color secondaryRingColor(BufferedImage image, Rectangle box, Color background) {
        int margin = Math.max(2, Math.min(box.height, box.width) / 6);
        Rectangle outer = new Rectangle(box.x - margin, box.y - margin, box.width + margin * 2, box.height + margin * 2)
                .intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        int[] counts = new int[4096];
        long[][] sums = new long[4096][3];
        int total = 0;
        for (int row = outer.y; row < outer.y + outer.height; row++) {
            for (int column = outer.x; column < outer.x + outer.width; column++) {
                if (box.contains(column, row)) continue;
                int rgb = image.getRGB(column, row);
                total++;
                if (!isInk(rgb, background)) continue;
                int bin = ((rgb >> 20) & 0xf) << 8 | ((rgb >> 12) & 0xf) << 4 | ((rgb >> 4) & 0xf);
                counts[bin]++;
                sums[bin][0] += (rgb >> 16) & 0xff;
                sums[bin][1] += (rgb >> 8) & 0xff;
                sums[bin][2] += rgb & 0xff;
            }
        }
        int best = 0;
        for (int bin = 1; bin < counts.length; bin++) {
            if (counts[bin] > counts[best]) best = bin;
        }
        if (total == 0 || counts[best] < total * 0.15) return null;
        return new Color((int) (sums[best][0] / counts[best]), (int) (sums[best][1] / counts[best]),
                (int) (sums[best][2] / counts[best]));
    }

    /**
     * The dominant color of a ring just outside the box. Returns null when no
     * color clearly dominates, so callers keep the plain white cover.
     */
    private static Color ringBackground(BufferedImage image, Rectangle box) {
        int margin = Math.max(2, Math.min(box.height, box.width) / 6);
        Rectangle outer = new Rectangle(box.x - margin, box.y - margin, box.width + margin * 2, box.height + margin * 2)
                .intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        int[] counts = new int[4096];
        long[][] sums = new long[4096][3];
        int total = 0;
        int stride = Math.max(1, (outer.width + outer.height) / 400);
        for (int row = outer.y; row < outer.y + outer.height; row += stride) {
            for (int column = outer.x; column < outer.x + outer.width; column += stride) {
                if (box.contains(column, row)) continue;
                int rgb = image.getRGB(column, row);
                int bin = ((rgb >> 20) & 0xf) << 8 | ((rgb >> 12) & 0xf) << 4 | ((rgb >> 4) & 0xf);
                counts[bin]++;
                sums[bin][0] += (rgb >> 16) & 0xff;
                sums[bin][1] += (rgb >> 8) & 0xff;
                sums[bin][2] += rgb & 0xff;
                total++;
            }
        }
        if (total == 0) return null;
        int best = 0;
        for (int bin = 1; bin < counts.length; bin++) {
            if (counts[bin] > counts[best]) best = bin;
        }
        if (counts[best] < total * MIN_BACKGROUND_SHARE) return null;
        return new Color((int) (sums[best][0] / counts[best]), (int) (sums[best][1] / counts[best]),
                (int) (sums[best][2] / counts[best]));
    }

    /** Average of the strongest ink pixels, falling back to black or white for contrast. */
    private static Color inkColor(BufferedImage image, Color background, Rectangle box) {
        long red = 0, green = 0, blue = 0;
        int count = 0;
        int strongest = INK_DISTANCE_SQUARED;
        for (int row = box.y; row < box.y + box.height; row++) {
            for (int column = box.x; column < box.x + box.width; column++) {
                int distance = distanceSquared(image.getRGB(column, row), background);
                if (distance > strongest) strongest = distance;
            }
        }
        int threshold = Math.max(INK_DISTANCE_SQUARED, (int) (strongest * 0.6));
        for (int row = box.y; row < box.y + box.height; row++) {
            for (int column = box.x; column < box.x + box.width; column++) {
                int rgb = image.getRGB(column, row);
                if (distanceSquared(rgb, background) >= threshold) {
                    red += (rgb >> 16) & 0xff;
                    green += (rgb >> 8) & 0xff;
                    blue += rgb & 0xff;
                    count++;
                }
            }
        }
        Color fallback = luminance(background) > 0.5 ? Color.BLACK : Color.WHITE;
        if (count == 0) return fallback;
        Color ink = new Color((int) (red / count), (int) (green / count), (int) (blue / count));
        return contrast(ink, background) >= 3.0 ? ink : fallback;
    }

    private static int distanceSquared(int rgb, Color background) {
        int red = ((rgb >> 16) & 0xff) - background.getRed();
        int green = ((rgb >> 8) & 0xff) - background.getGreen();
        int blue = (rgb & 0xff) - background.getBlue();
        return red * red + green * green + blue * blue;
    }

    private static double luminance(Color color) {
        double red = channel(color.getRed());
        double green = channel(color.getGreen());
        double blue = channel(color.getBlue());
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.03928 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static Rectangle pad(Rectangle box, int padding, int imageWidth, int imageHeight) {
        return new Rectangle(box.x - padding, box.y - padding, box.width + padding * 2, box.height + padding * 2)
                .intersection(new Rectangle(0, 0, imageWidth, imageHeight));
    }
}
