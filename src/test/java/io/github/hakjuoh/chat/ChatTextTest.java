package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link ChatText}, the pure formatting/heuristic helpers split out of the
 * Swing {@code ChatView}. Covers {@code formatUsage}, {@code shouldAttachPastedText},
 * {@code isImageFileName} and {@code imageMediaType}. {@link ChatUsage} is a plain record so it is
 * constructed directly: {@code new ChatUsage(inputTokens, outputTokens, cachedInputTokens, costUsd)}.
 */
class ChatTextTest {

    // ---- formatUsage --------------------------------------------------------

    @Test
    void formatUsage_startsWithTokensPrefixAndTrailingPadding() {
        String s = ChatText.formatUsage(new ChatUsage(10, 20, 0, null));
        assertTrue(s.startsWith("tokens: "), s);
        assertTrue(s.endsWith("   "), "should always end with 3 trailing spaces: [" + s + "]");
    }

    @Test
    void formatUsage_rendersPlainInputAndOutputCounts() {
        String s = ChatText.formatUsage(new ChatUsage(10, 20, 0, null));
        assertEquals("tokens: 10 in / 20 out   ", s);
    }

    @Test
    void formatUsage_negativeInputRendersAsQuestionMark() {
        String s = ChatText.formatUsage(new ChatUsage(-1, 20, 0, null));
        assertEquals("tokens: ? in / 20 out   ", s);
    }

    @Test
    void formatUsage_negativeOutputRendersAsQuestionMark() {
        String s = ChatText.formatUsage(new ChatUsage(10, -1, 0, null));
        assertEquals("tokens: 10 in / ? out   ", s);
    }

    @Test
    void formatUsage_bothNegativeRenderAsQuestionMarks() {
        String s = ChatText.formatUsage(ChatUsage.unknown());
        assertEquals("tokens: ? in / ? out   ", s);
    }

    @Test
    void formatUsage_cachedInputTokensAddsCachedSuffix() {
        String s = ChatText.formatUsage(new ChatUsage(100, 20, 30, null));
        assertEquals("tokens: 100 in (30 cached) / 20 out   ", s);
    }

    @Test
    void formatUsage_zeroCachedInputTokensOmitsCachedSuffix() {
        String s = ChatText.formatUsage(new ChatUsage(100, 20, 0, null));
        assertFalse(s.contains("cached"), s);
    }

    @Test
    void formatUsage_negativeCachedInputTokensOmitsCachedSuffix() {
        // cachedInputTokens is only shown when strictly > 0.
        String s = ChatText.formatUsage(new ChatUsage(100, 20, -1, null));
        assertFalse(s.contains("cached"), s);
    }

    @Test
    void formatUsage_costAppendsFourDecimalDollarAmount() {
        Locale prev = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            String s = ChatText.formatUsage(new ChatUsage(100, 20, 0, 0.1234));
            assertEquals("tokens: 100 in / 20 out  ·  $0.1234   ", s);
        } finally {
            Locale.setDefault(prev);
        }
    }

    @Test
    void formatUsage_costIsFormattedToExactlyFourDecimals() {
        Locale prev = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            // 0.5 must render with four decimals and the mid-dot separator.
            String s = ChatText.formatUsage(new ChatUsage(1, 2, 0, 0.5));
            assertTrue(s.contains("$0.5000"), s);
            assertTrue(s.contains("·"), s);
        } finally {
            Locale.setDefault(prev);
        }
    }

    @Test
    void formatUsage_nullCostOmitsDollarAmount() {
        String s = ChatText.formatUsage(new ChatUsage(100, 20, 0, null));
        assertFalse(s.contains("$"), s);
        assertFalse(s.contains("·"), s);
    }

    @Test
    void formatUsage_cachedAndCostCombine() {
        Locale prev = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            String s = ChatText.formatUsage(new ChatUsage(100, 20, 30, 0.25));
            assertEquals("tokens: 100 in (30 cached) / 20 out  ·  $0.2500   ", s);
        } finally {
            Locale.setDefault(prev);
        }
    }

    // ---- shouldAttachPastedText ---------------------------------------------

    @Test
    void shouldAttach_nullTextIsFalse() {
        assertFalse(ChatText.shouldAttachPastedText(null, 10, 3, 5));
    }

    @Test
    void shouldAttach_lengthAtOrAboveAttachThresholdIsTrue() {
        // length == attachThreshold is true because the check is >=.
        String tenChars = "0123456789";
        assertEquals(10, tenChars.length());
        assertTrue(ChatText.shouldAttachPastedText(tenChars, 10, 999, 999));
    }

    @Test
    void shouldAttach_lengthAboveAttachThresholdIsTrue() {
        assertTrue(ChatText.shouldAttachPastedText("0123456789X", 10, 999, 999));
    }

    @Test
    void shouldAttach_shortSingleLineBelowLineMinCharsIsFalse() {
        // Below attachThreshold and below lineMinChars -> false.
        assertFalse(ChatText.shouldAttachPastedText("abc", 100, 3, 5));
    }

    @Test
    void shouldAttach_manyLinesButBelowLineMinCharsIsFalse() {
        // Four newlines (>= lineThreshold of 3) but length 5 < lineMinChars 6 -> false.
        String s = "\n\n\n\n\n"; // length 5, five newlines
        assertEquals(5, s.length());
        assertFalse(ChatText.shouldAttachPastedText(s, 100, 3, 6));
    }

    @Test
    void shouldAttach_enoughLinesAndAtLeastLineMinCharsIsTrue() {
        // lines counted from 1, incremented per '\n'; two newlines -> lines reaches 3 >= lineThreshold.
        String s = "aa\nbb\ncc"; // length 8, two newlines
        assertEquals(8, s.length());
        assertTrue(ChatText.shouldAttachPastedText(s, 100, 3, 5));
    }

    @Test
    void shouldAttach_lineThresholdBoundaryOneNewlineNotEnough() {
        // lineThreshold 3 needs 2 newlines (1 + 2 = 3); a single newline yields lines=2 < 3 -> false.
        String s = "aaaa\nbbbb"; // length 9, one newline, above lineMinChars
        assertFalse(ChatText.shouldAttachPastedText(s, 100, 3, 5));
    }

    @Test
    void shouldAttach_lineThresholdOfTwoWithOneNewlineIsTrue() {
        // With lineThreshold 2, a single newline pushes lines to 2 >= 2 -> true (given length >= lineMinChars).
        String s = "aaaa\nbbbb"; // length 9, one newline
        assertTrue(ChatText.shouldAttachPastedText(s, 100, 2, 5));
    }

    @Test
    void shouldAttach_lengthExactlyLineMinCharsWithEnoughLinesIsTrue() {
        // length == lineMinChars passes the "< lineMinChars" guard (which is strict <).
        String s = "a\nb\nc"; // length 5, two newlines -> lines 3
        assertEquals(5, s.length());
        assertTrue(ChatText.shouldAttachPastedText(s, 100, 3, 5));
    }

    // ---- isImageFileName ----------------------------------------------------

    @Test
    void isImageFileName_recognisesAllKnownExtensions() {
        assertTrue(ChatText.isImageFileName("a.png"));
        assertTrue(ChatText.isImageFileName("a.jpg"));
        assertTrue(ChatText.isImageFileName("a.jpeg"));
        assertTrue(ChatText.isImageFileName("a.gif"));
        assertTrue(ChatText.isImageFileName("a.webp"));
        assertTrue(ChatText.isImageFileName("a.bmp"));
        assertTrue(ChatText.isImageFileName("a.tif"));
        assertTrue(ChatText.isImageFileName("a.tiff"));
    }

    @Test
    void isImageFileName_isCaseInsensitive() {
        assertTrue(ChatText.isImageFileName("PHOTO.PNG"));
        assertTrue(ChatText.isImageFileName("Image.JpEg"));
    }

    @Test
    void isImageFileName_nullIsFalse() {
        assertFalse(ChatText.isImageFileName(null));
    }

    @Test
    void isImageFileName_nonImageExtensionIsFalse() {
        assertFalse(ChatText.isImageFileName("notes.txt"));
    }

    @Test
    void isImageFileName_compoundNonImageExtensionIsFalse() {
        assertFalse(ChatText.isImageFileName("archive.tar.gz"));
    }

    @Test
    void isImageFileName_emptyStringIsFalse() {
        assertFalse(ChatText.isImageFileName(""));
    }

    @Test
    void isImageFileName_noExtensionIsFalse() {
        assertFalse(ChatText.isImageFileName("README"));
    }

    // ---- imageMediaType -----------------------------------------------------

    @Test
    void imageMediaType_png() {
        assertEquals("image/png", ChatText.imageMediaType("a.png"));
    }

    @Test
    void imageMediaType_jpgAndJpegBothMapToJpeg() {
        assertEquals("image/jpeg", ChatText.imageMediaType("a.jpg"));
        assertEquals("image/jpeg", ChatText.imageMediaType("a.jpeg"));
    }

    @Test
    void imageMediaType_gif() {
        assertEquals("image/gif", ChatText.imageMediaType("a.gif"));
    }

    @Test
    void imageMediaType_webp() {
        assertEquals("image/webp", ChatText.imageMediaType("a.webp"));
    }

    @Test
    void imageMediaType_bmp() {
        assertEquals("image/bmp", ChatText.imageMediaType("a.bmp"));
    }

    @Test
    void imageMediaType_tifAndTiffBothMapToTiff() {
        assertEquals("image/tiff", ChatText.imageMediaType("a.tif"));
        assertEquals("image/tiff", ChatText.imageMediaType("a.tiff"));
    }

    @Test
    void imageMediaType_isCaseInsensitive() {
        assertEquals("image/png", ChatText.imageMediaType("PHOTO.PNG"));
        assertEquals("image/jpeg", ChatText.imageMediaType("Snap.JPEG"));
    }

    @Test
    void imageMediaType_unknownExtensionMapsToWildcard() {
        assertEquals("image/*", ChatText.imageMediaType("notes.txt"));
    }

    @Test
    void imageMediaType_nullMapsToWildcard() {
        assertEquals("image/*", ChatText.imageMediaType(null));
    }
}
