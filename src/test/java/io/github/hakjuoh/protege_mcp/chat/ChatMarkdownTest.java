package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.util.List;

import javax.swing.text.AttributeSet;
import javax.swing.text.StyleConstants;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ChatMarkdown}, the pure Markdown-to-styled-runs renderer behind the
 * chat transcript. Only {@code javax.swing.text} attribute machinery is touched (no AWT component
 * is constructed), matching the suite's headless contract.
 */
class ChatMarkdownTest {

    private static final int BASE = 13;

    private static List<ChatMarkdown.Run> render(String md) {
        return ChatMarkdown.render(md, BASE);
    }

    private static String textOf(List<ChatMarkdown.Run> runs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMarkdown.Run r : runs) {
            sb.append(r.text());
        }
        return sb.toString();
    }

    /** The first run whose text equals {@code text} (fails the test if absent). */
    private static ChatMarkdown.Run run(List<ChatMarkdown.Run> runs, String text) {
        for (ChatMarkdown.Run r : runs) {
            if (r.text().equals(text)) {
                return r;
            }
        }
        throw new AssertionError("no run with text <" + text + "> in " + runs);
    }

    // ------------------------------------------------------------------ empty / plain

    @Test
    void nullInputRendersNothing() {
        assertTrue(render(null).isEmpty());
    }

    @Test
    void emptyInputRendersNothing() {
        assertTrue(render("").isEmpty());
    }

    @Test
    void whitespaceOnlyInputRendersNothing() {
        assertTrue(render("  \n \n").isEmpty());
    }

    @Test
    void plainParagraphIsOneUnstyledBlackRun() {
        List<ChatMarkdown.Run> runs = render("hello world");
        assertEquals(1, runs.size());
        AttributeSet a = runs.get(0).attributes();
        assertEquals("hello world", runs.get(0).text());
        assertEquals(Color.BLACK, StyleConstants.getForeground(a));
        assertEquals(BASE, StyleConstants.getFontSize(a));
        assertFalse(StyleConstants.isBold(a));
        assertFalse(StyleConstants.isItalic(a));
        assertFalse(StyleConstants.isUnderline(a));
        assertFalse(a.isDefined(StyleConstants.FontFamily), "plain text must not pin a font family");
        assertFalse(a.isDefined(StyleConstants.Background), "plain text must not paint a background");
    }

    @Test
    void baseFontSizeIsApplied() {
        assertEquals(16, StyleConstants.getFontSize(ChatMarkdown.render("x", 16).get(0).attributes()));
    }

    @Test
    void lastBlockHasNoTrailingNewline() {
        assertFalse(textOf(render("para")).endsWith("\n"));
        assertFalse(textOf(render("```\ncode\n```")).endsWith("\n"));
    }

    // ------------------------------------------------------------------ paragraphs and breaks

    @Test
    void paragraphsAreSeparatedByABlankLine() {
        assertEquals("first\n\nsecond", textOf(render("first\n\nsecond")));
    }

    @Test
    void softLineBreakRendersAsLineBreak() {
        assertEquals("a\nb", textOf(render("a\nb")));
    }

    @Test
    void hardLineBreakRendersAsLineBreak() {
        assertEquals("a\nb", textOf(render("a  \nb")));
    }

    // ------------------------------------------------------------------ emphasis and code spans

    @Test
    void strongEmphasisIsBoldWithoutMarkers() {
        List<ChatMarkdown.Run> runs = render("**b**");
        assertEquals("b", textOf(runs));
        assertTrue(StyleConstants.isBold(run(runs, "b").attributes()));
    }

    @Test
    void emphasisIsItalicWithoutMarkers() {
        List<ChatMarkdown.Run> runs = render("*i*");
        assertEquals("i", textOf(runs));
        assertTrue(StyleConstants.isItalic(run(runs, "i").attributes()));
    }

    @Test
    void tripleEmphasisIsBoldAndItalic() {
        AttributeSet a = run(render("***x***"), "x").attributes();
        assertTrue(StyleConstants.isBold(a));
        assertTrue(StyleConstants.isItalic(a));
    }

    @Test
    void inlineCodeIsMonospaceWithBackground() {
        AttributeSet a = run(render("`code`"), "code").attributes();
        assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(a));
        assertTrue(a.isDefined(StyleConstants.Background));
    }

    @Test
    void inlineCodeKeepsLiteralMarkers() {
        assertEquals("**x**", textOf(render("`**x**`")));
    }

    @Test
    void adjacentDifferentlyStyledSlicesStaySeparateRuns() {
        List<ChatMarkdown.Run> runs = render("a *i* b");
        assertEquals(3, runs.size());
        assertEquals("a ", runs.get(0).text());
        assertEquals("i", runs.get(1).text());
        assertEquals(" b", runs.get(2).text());
    }

    // ------------------------------------------------------------------ code blocks

    @Test
    void fencedCodeBlockIsMonospaceWithBackgroundAndNoFenceMarkers() {
        List<ChatMarkdown.Run> runs = render("```java\nint x = 1;\n```");
        assertEquals("int x = 1;", textOf(runs));
        AttributeSet a = runs.get(0).attributes();
        assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(a));
        assertTrue(a.isDefined(StyleConstants.Background));
    }

    @Test
    void fencedCodeBlockKeepsInternalNewlines() {
        assertEquals("a\nb", textOf(render("```\na\nb\n```")));
    }

    @Test
    void unclosedFenceStreamsAsCodeBlock() {
        // Mid-stream state: the closing fence has not arrived yet.
        List<ChatMarkdown.Run> runs = render("```\npartial");
        assertEquals("partial", textOf(runs));
        assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(runs.get(0).attributes()));
    }

    @Test
    void justOpenedFenceRendersNothingYet() {
        assertEquals("", textOf(render("```")));
    }

    // ------------------------------------------------------------------ headings

    @Test
    void headingLevelsScaleDownFromH1() {
        assertEquals(BASE + 6, StyleConstants.getFontSize(run(render("# t"), "t").attributes()));
        assertEquals(BASE + 4, StyleConstants.getFontSize(run(render("## t"), "t").attributes()));
        assertEquals(BASE + 2, StyleConstants.getFontSize(run(render("### t"), "t").attributes()));
        assertEquals(BASE, StyleConstants.getFontSize(run(render("#### t"), "t").attributes()));
    }

    @Test
    void headingsAreBoldAndKeepNoHashMarkers() {
        List<ChatMarkdown.Run> runs = render("# Title");
        assertEquals("Title", textOf(runs));
        assertTrue(StyleConstants.isBold(runs.get(0).attributes()));
    }

    @Test
    void headingThenBodyKeepsBodyPlain() {
        List<ChatMarkdown.Run> runs = render("# T\n\nbody");
        assertEquals("T\n\nbody", textOf(runs));
        // The blank-line separator carries base style, so it coalesces into the body's run.
        AttributeSet body = run(runs, "\n\nbody").attributes();
        assertEquals(BASE, StyleConstants.getFontSize(body));
        assertFalse(StyleConstants.isBold(body));
    }

    // ------------------------------------------------------------------ lists

    @Test
    void bulletListRendersDotMarkers() {
        assertEquals("• a\n• b", textOf(render("- a\n- b")));
    }

    @Test
    void nestedBulletListIndentsUnderParent() {
        assertEquals("• a\n  • b", textOf(render("- a\n  - b")));
    }

    @Test
    void orderedListRendersNumbers() {
        assertEquals("1. a\n2. b", textOf(render("1. a\n2. b")));
    }

    @Test
    void orderedListHonoursStartNumber() {
        assertEquals("3. a\n4. b", textOf(render("3. a\n4. b")));
    }

    @Test
    void multiParagraphListItemHangsUnderItsMarker() {
        assertEquals("• a\n  second\n• b", textOf(render("- a\n\n  second\n- b")));
    }

    @Test
    void styledTextInsideListKeepsStyle() {
        List<ChatMarkdown.Run> runs = render("- **a**\n- `b`");
        assertEquals("• a\n• b", textOf(runs));
        assertTrue(StyleConstants.isBold(run(runs, "a").attributes()));
        assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(run(runs, "b").attributes()));
    }

    // ------------------------------------------------------------------ quotes and rules

    @Test
    void blockQuoteIsPrefixedAndMuted() {
        List<ChatMarkdown.Run> runs = render("> quoted");
        assertEquals("│ quoted", textOf(runs));
        assertEquals(new Color(0x555555), StyleConstants.getForeground(runs.get(0).attributes()));
    }

    @Test
    void multiParagraphQuoteKeepsBarOnBlankLine() {
        assertEquals("│ a\n│ \n│ b", textOf(render("> a\n>\n> b")));
    }

    @Test
    void thematicBreakRendersAsRule() {
        List<ChatMarkdown.Run> runs = render("---");
        assertEquals("─".repeat(24), textOf(runs));
        assertEquals(new Color(0xAAAAAA), StyleConstants.getForeground(runs.get(0).attributes()));
    }

    // ------------------------------------------------------------------ links and images

    @Test
    void httpLinkIsUnderlinedAndCarriesUrlAttribute() {
        List<ChatMarkdown.Run> runs = render("see [docs](https://example.com/x)");
        AttributeSet a = run(runs, "docs").attributes();
        assertEquals("see docs", textOf(runs));
        assertTrue(StyleConstants.isUnderline(a));
        assertEquals("https://example.com/x", a.getAttribute(ChatMarkdown.LINK_URL));
    }

    @Test
    void schemeCheckIsCaseInsensitive() {
        AttributeSet a = run(render("[x](HTTPS://example.com)"), "x").attributes();
        assertEquals("HTTPS://example.com", a.getAttribute(ChatMarkdown.LINK_URL));
    }

    @Test
    void nonHttpLinkRendersAsPlainTextAndIsNeverClickable() {
        List<ChatMarkdown.Run> runs = render("[x](javascript:alert(1))");
        assertEquals("x", textOf(runs));
        AttributeSet a = runs.get(0).attributes();
        assertFalse(StyleConstants.isUnderline(a));
        assertNull(a.getAttribute(ChatMarkdown.LINK_URL));
    }

    @Test
    void autolinkIsClickable() {
        List<ChatMarkdown.Run> runs = render("<https://example.com>");
        assertEquals("https://example.com", textOf(runs));
        assertEquals("https://example.com",
                runs.get(0).attributes().getAttribute(ChatMarkdown.LINK_URL));
    }

    @Test
    void linkInsideBoldKeepsBoth() {
        AttributeSet a = run(render("**[x](https://e.com)**"), "x").attributes();
        assertTrue(StyleConstants.isBold(a));
        assertTrue(StyleConstants.isUnderline(a));
        assertEquals("https://e.com", a.getAttribute(ChatMarkdown.LINK_URL));
    }

    @Test
    void imageRendersAltTextItalicWithoutLink() {
        List<ChatMarkdown.Run> runs = render("![alt text](https://e.com/i.png)");
        assertEquals("alt text", textOf(runs));
        AttributeSet a = runs.get(0).attributes();
        assertTrue(StyleConstants.isItalic(a));
        assertNull(a.getAttribute(ChatMarkdown.LINK_URL));
    }

    // ------------------------------------------------------------------ tables

    @Test
    void tableRendersAlignedMonospaceColumns() {
        List<ChatMarkdown.Run> runs = render("| a | bb |\n| - | -- |\n| ccc | d |");
        assertEquals("a    bb\n───  ──\nccc  d", textOf(runs));
        AttributeSet a = runs.get(0).attributes();
        assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(a));
        assertFalse(a.isDefined(StyleConstants.Background), "tables are mono but not code-shaded");
    }

    @Test
    void tableRightAlignmentPadsLeft() {
        String text = textOf(render("| num |\n| --: |\n| 1 |"));
        assertTrue(text.endsWith("\n  1"), text);
    }

    @Test
    void tableWithMissingCellsStillAligns() {
        String text = textOf(render("| a | b |\n| - | - |\n| only |"));
        assertEquals("a     b\n────  ─\nonly", text);
    }

    @Test
    void inlineStylingInsideCellsIsFlattenedForAlignment() {
        assertEquals("h\n─\nb", textOf(render("| h |\n| - |\n| **b** |")));
    }

    // ------------------------------------------------------------------ raw html

    @Test
    void inlineHtmlPassesThroughLiterally() {
        assertEquals("a <br> b", textOf(render("a <br> b")));
    }

    @Test
    void htmlBlockPassesThroughLiterally() {
        assertEquals("<div>\nx\n</div>", textOf(render("<div>\nx\n</div>")));
    }

    // ------------------------------------------------------------------ robustness (streaming partials)

    @Test
    void rendererNeverThrowsOnPartialOrPathologicalInput() {
        String[] nasty = {
                "**unclosed", "*", "```", "```java", "> ", "- ", "1. ", "[", "[x](", "![",
                "| a |", "| a |\n| - |", "#", "######", "---\n***\n___",
                "a\0b", "> > > - [ ] `\n**", "<", "<a", "&amp;", "\\*not em\\*",
        };
        for (String s : nasty) {
            assertDoesNotThrow(() -> ChatMarkdown.render(s, BASE), "input: " + s);
            assertNotNull(ChatMarkdown.render(s, BASE));
        }
    }

    /**
     * The no-text-loss contract for mid-stream partials: an unclosed marker renders as literal
     * text — never dropped, never "cleaned up" — until its closer streams in.
     */
    @Test
    void streamingPartialsPassThroughLiterally() {
        assertEquals("**unclosed", textOf(render("**unclosed")));
        assertEquals("*a", textOf(render("*a")));
        assertEquals("`tick", textOf(render("`tick")));
        assertEquals("[docs](https://exa", textOf(render("[docs](https://exa")));
        // A table without its delimiter row is still just a paragraph.
        assertEquals("| a | b |", textOf(render("| a | b |")));
    }

    @Test
    void partialThenClosedMarkerConverges() {
        // The streaming pair: first tick literal and unstyled, later tick styled without markers.
        List<ChatMarkdown.Run> partial = render("**bo");
        assertEquals("**bo", textOf(partial));
        assertFalse(StyleConstants.isBold(partial.get(0).attributes()));
        List<ChatMarkdown.Run> closed = render("**bo**");
        assertEquals("bo", textOf(closed));
        assertTrue(StyleConstants.isBold(closed.get(0).attributes()));
    }

    @Test
    void deeplyNestedInputNeitherThrowsNorLosesText() {
        // Model output is untrusted: thousands of nested quote/list levels must degrade (flatten
        // or plain-text fallback), not overflow the EDT stack or drop the reply.
        for (String s : new String[] {
                ">".repeat(4000) + " x",
                "> ".repeat(4000) + "x",
                "- ".repeat(2000) + "x",
        }) {
            List<ChatMarkdown.Run> runs = assertDoesNotThrow(() -> ChatMarkdown.render(s, BASE));
            assertTrue(textOf(runs).contains("x"), "reply text must survive pathological nesting");
        }
    }

    // ------------------------------------------------------------------ CRLF input (CLIs may emit it)

    @Test
    void crlfSoftBreakNormalizesToNewline() {
        assertEquals("a\nb", textOf(render("a\r\nb")));
    }

    @Test
    void crlfFencedCodeBlockRoundTripsWithoutCarriageReturns() {
        String text = textOf(render("```\r\na\r\nb\r\n```\r\n"));
        assertEquals("a\nb", text);
        assertFalse(text.contains("\r"));
    }

    @Test
    void crlfTableStaysAlignedWithoutCarriageReturns() {
        String text = textOf(render("| a | bb |\r\n| - | -- |\r\n| ccc | d |"));
        assertEquals("a    bb\n───  ──\nccc  d", text);
        assertFalse(text.contains("\r"));
    }

    @Test
    void escapedMarkersRenderLiterally() {
        assertEquals("*not em*", textOf(render("\\*not em\\*")));
    }

    @Test
    void entitiesAreDecoded() {
        assertEquals("a & b", textOf(render("a &amp; b")));
    }

    @Test
    void mixedDocumentSnapshot() {
        String md = "# T\n\n- **a**\n- `b`\n\ndone";
        assertEquals("T\n\n• a\n• b\n\ndone", textOf(render(md)));
    }

    @Test
    void linkReferenceDefinitionsAreInvisible() {
        List<ChatMarkdown.Run> runs = render("[x][1]\n\n[1]: https://example.com");
        assertEquals("x", textOf(runs));
        assertEquals("https://example.com",
                run(runs, "x").attributes().getAttribute(ChatMarkdown.LINK_URL));
    }
}
