package io.github.hakjuoh.protege_mcp.chat;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Pure Markdown-to-styled-runs renderer for the chat transcript, split out of ChatView (a Swing
 * component) so it can be unit-tested headless. Assistant replies arrive as Markdown; this class
 * parses them with commonmark-java and walks the AST into a flat list of {@link Run}s (text +
 * {@code javax.swing.text} attributes) that the view inserts into its {@code StyledDocument}.
 *
 * <p>Designed for streaming: the view re-renders the in-flight message from its accumulated source
 * on every flush tick, so this must tolerate partial Markdown (an unclosed {@code **} renders as
 * literal text until the closer arrives; an unclosed fence renders as a code block to end-of-input
 * — both fall out of re-parsing the whole buffer). A renderer failure must never lose the reply:
 * {@link #render} falls back to one plain-text run instead of throwing.
 *
 * <p>Mapping notes: soft line breaks render as real line breaks (chat transcripts read like
 * terminals, not justified prose); the last block has no trailing newline (mirrors how plain
 * chunks stream today, so the view's end-of-line bookkeeping is unchanged); tables render as
 * monospace columns padded with spaces (Swing text panes have no usable table layout); links are
 * styled and carry {@link #LINK_URL} only for http(s) destinations, so nothing else ever becomes
 * clickable.
 */
public final class ChatMarkdown {

    /** Character-attribute key whose value is the clickable http(s) URL of a rendered link run. */
    public static final String LINK_URL = "io.github.hakjuoh.protege_mcp.chat.link-url";

    /** One styled slice of rendered output, in document order. */
    public record Run(String text, AttributeSet attributes) {
    }

    private static final Color TEXT_COLOR = Color.BLACK;
    private static final Color QUOTE_COLOR = new Color(0x555555);
    private static final Color LINK_COLOR = new Color(0x0B57D0);
    private static final Color RULE_COLOR = new Color(0xAAAAAA);
    private static final Color CODE_BG = new Color(0xF2F2F2);

    private static final String QUOTE_PREFIX = "│ ";      // │
    private static final String BULLET = "• ";            // •
    private static final int RULE_WIDTH = 24;                  // ─ repeated

    /** Nesting cap for the recursive walk. Model output is untrusted, and a few KB of repeated
     *  {@code >} or {@code -} markers nest thousands of levels deep — enough to overflow the EDT
     *  stack. Past the cap the subtree renders as flattened plain text (collected iteratively). */
    private static final int MAX_DEPTH = 80;

    /** Parser instances are immutable and safe to share; tables come from the GFM extension. */
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private ChatMarkdown() {
    }

    /**
     * Renders {@code markdown} into styled runs whose font sizes are derived from
     * {@code baseFontSize} (headings scale up from it). Never throws; a parser/renderer failure
     * falls back to the raw text as a single plain run.
     */
    public static List<Run> render(String markdown, int baseFontSize) {
        if (markdown == null || markdown.isEmpty()) {
            return List.of();
        }
        try {
            Emitter out = new Emitter();
            blocks(out, PARSER.parse(markdown), "", Style.base(baseFontSize), true, 0);
            return out.finish();
        } catch (RuntimeException | StackOverflowError ex) {
            // StackOverflowError too: MAX_DEPTH bounds our own walk, but the parser itself is not
            // ours to bound, and "never lose the reply" must hold for whatever the model sends.
            return List.of(new Run(markdown, attrs(Style.base(baseFontSize))));
        }
    }

    // ------------------------------------------------------------------ block-level walk

    /**
     * Renders {@code parent}'s child blocks, separated by a blank line ({@code blank}) or a plain
     * line break. Every generated newline is followed by {@code indent} so nested content (list
     * bodies, quoted blocks) stays visually aligned on subsequent lines.
     */
    private static void blocks(Emitter out, Node parent, String indent, Style st, boolean blank,
            int depth) {
        if (depth > MAX_DEPTH) {
            out.emit(plainText(parent), attrs(st));
            return;
        }
        boolean first = true;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof LinkReferenceDefinition) {
                continue;   // definitions are invisible; the links that use them render inline
            }
            if (!first) {
                out.emit(blank ? "\n" + indent + "\n" + indent : "\n" + indent, attrs(st));
            }
            block(out, n, indent, st, depth);
            first = false;
        }
    }

    private static void block(Emitter out, Node n, String indent, Style st, int depth) {
        if (n instanceof Paragraph) {
            inlines(out, n, indent, st, depth + 1);
        } else if (n instanceof Heading h) {
            inlines(out, h, indent, st.heading(headingSizeDelta(h.getLevel())), depth + 1);
        } else if (n instanceof FencedCodeBlock f) {
            codeBlock(out, f.getLiteral(), indent, st);
        } else if (n instanceof IndentedCodeBlock i) {
            codeBlock(out, i.getLiteral(), indent, st);
        } else if (n instanceof BulletList || n instanceof OrderedList) {
            list(out, n, indent, st, depth);
        } else if (n instanceof BlockQuote) {
            out.emit(QUOTE_PREFIX, attrs(st.quoted()));
            blocks(out, n, indent + QUOTE_PREFIX, st.quoted(), true, depth + 1);
        } else if (n instanceof ThematicBreak) {
            SimpleAttributeSet a = attrs(st);
            StyleConstants.setForeground(a, RULE_COLOR);
            out.emit("─".repeat(RULE_WIDTH), a);
        } else if (n instanceof TableBlock t) {
            table(out, t, indent, st);
        } else if (n instanceof HtmlBlock hb) {
            String literal = stripTrailingNewline(hb.getLiteral() == null ? "" : hb.getLiteral());
            out.emit(literal.replace("\n", "\n" + indent), attrs(st));
        } else {
            blocks(out, n, indent, st, true, depth + 1);   // unknown block: render children as best effort
        }
    }

    private static int headingSizeDelta(int level) {
        switch (level) {
            case 1: return 6;
            case 2: return 4;
            case 3: return 2;
            default: return 0;                  // h4-h6: bold only
        }
    }

    private static void codeBlock(Emitter out, String literal, String indent, Style st) {
        String body = stripTrailingNewline(literal == null ? "" : literal);
        if (body.isEmpty()) {
            return;                             // e.g. a just-opened streaming fence: nothing to show yet
        }
        out.emit(body.replace("\n", "\n" + indent), attrs(st.coded()));
    }

    private static void list(Emitter out, Node listNode, String indent, Style st, int depth) {
        int number = -1;
        if (listNode instanceof OrderedList ol) {
            number = ol.getMarkerStartNumber() != null ? ol.getMarkerStartNumber() : 1;
        }
        boolean first = true;
        for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
            if (!first) {
                out.emit("\n" + indent, attrs(st));
            }
            String marker = number >= 0 ? (number++) + ". " : BULLET;
            out.emit(marker, attrs(st));
            // The item body hangs under its marker; loose and tight lists both render compactly
            // (single line breaks) — blank lines between items read as noise in a transcript.
            blocks(out, item, indent + " ".repeat(marker.length()), st, false, depth + 1);
            first = false;
        }
    }

    // ------------------------------------------------------------------ tables (monospace columns)

    private static void table(Emitter out, TableBlock tableNode, String indent, Style st) {
        List<List<String>> head = new ArrayList<>();
        List<List<String>> body = new ArrayList<>();
        List<TableCell.Alignment> aligns = new ArrayList<>();
        for (Node section = tableNode.getFirstChild(); section != null; section = section.getNext()) {
            boolean isHead = section instanceof TableHead;
            if (!isHead && !(section instanceof TableBody)) {
                continue;
            }
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell tc) {
                        cells.add(plainText(tc));
                        if (isHead && aligns.size() < cells.size()) {
                            aligns.add(tc.getAlignment());
                        }
                    }
                }
                (isHead ? head : body).add(cells);
            }
        }
        int cols = 0;
        for (List<String> row : head) {
            cols = Math.max(cols, row.size());
        }
        for (List<String> row : body) {
            cols = Math.max(cols, row.size());
        }
        if (cols == 0) {
            return;
        }
        int[] width = new int[cols];
        for (int c = 0; c < cols; c++) {
            width[c] = 1;
        }
        for (List<String> row : head) {
            for (int c = 0; c < row.size(); c++) {
                width[c] = Math.max(width[c], row.get(c).length());
            }
        }
        for (List<String> row : body) {
            for (int c = 0; c < row.size(); c++) {
                width[c] = Math.max(width[c], row.get(c).length());
            }
        }

        List<String> lines = new ArrayList<>();
        for (List<String> row : head) {
            lines.add(tableLine(row, width, aligns));
        }
        if (!head.isEmpty()) {
            StringBuilder dash = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                if (c > 0) {
                    dash.append("  ");
                }
                dash.append("─".repeat(width[c]));
            }
            lines.add(dash.toString());
        }
        for (List<String> row : body) {
            lines.add(tableLine(row, width, aligns));
        }
        out.emit(String.join("\n" + indent, lines), attrs(st.monoed()));
    }

    private static String tableLine(List<String> row, int[] width, List<TableCell.Alignment> aligns) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < width.length; c++) {
            if (c > 0) {
                line.append("  ");
            }
            String cell = c < row.size() ? row.get(c) : "";
            TableCell.Alignment align = c < aligns.size() ? aligns.get(c) : null;
            line.append(pad(cell, width[c], align));
        }
        // Trailing pad spaces on the last column are invisible noise (and would be copied).
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(0, end);
    }

    private static String pad(String cell, int width, TableCell.Alignment align) {
        int gap = width - cell.length();
        if (gap <= 0) {
            return cell;
        }
        if (align == TableCell.Alignment.RIGHT) {
            return " ".repeat(gap) + cell;
        }
        if (align == TableCell.Alignment.CENTER) {
            return " ".repeat(gap / 2) + cell + " ".repeat(gap - gap / 2);
        }
        return cell + " ".repeat(gap);
    }

    // ------------------------------------------------------------------ inline-level walk

    private static void inlines(Emitter out, Node parent, String indent, Style st, int depth) {
        if (depth > MAX_DEPTH) {
            out.emit(plainText(parent), attrs(st));
            return;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            inline(out, n, indent, st, depth);
        }
    }

    private static void inline(Emitter out, Node n, String indent, Style st, int depth) {
        if (n instanceof Text t) {
            out.emit(t.getLiteral(), attrs(st));
        } else if (n instanceof StrongEmphasis) {
            inlines(out, n, indent, st.bolded(), depth + 1);
        } else if (n instanceof Emphasis) {
            inlines(out, n, indent, st.italicized(), depth + 1);
        } else if (n instanceof Code c) {
            out.emit(c.getLiteral(), attrs(st.coded()));
        } else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) {
            out.emit("\n" + indent, attrs(st));
        } else if (n instanceof Link l) {
            String url = clickableUrl(l.getDestination());
            if (url == null) {
                inlines(out, l, indent, st, depth + 1);   // non-http(s): text only, never clickable
            } else if (l.getFirstChild() == null) {
                out.emit(url, attrs(st.linked(url)));
            } else {
                inlines(out, l, indent, st.linked(url), depth + 1);
            }
        } else if (n instanceof Image img) {
            String alt = plainText(img);
            out.emit(alt.isEmpty() ? "[image]" : alt, attrs(st.italicized()));
        } else if (n instanceof HtmlInline h) {
            out.emit(h.getLiteral(), attrs(st));
        } else {
            inlines(out, n, indent, st, depth + 1);   // unknown inline: render children as best effort
        }
    }

    /** The destination if it is a http(s) URL (the only schemes the view will open), else null. */
    private static String clickableUrl(String destination) {
        if (destination == null) {
            return null;
        }
        String lower = destination.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? destination : null;
    }

    /**
     * The concatenated literal text under {@code parent}, breaks collapsed to spaces. Iterative
     * (explicit stack) on purpose: this is also the beyond-MAX_DEPTH degradation path, where the
     * subtree is exactly the pathologically deep one.
     */
    private static String plainText(Node parent) {
        StringBuilder sb = new StringBuilder();
        Deque<Node> stack = new ArrayDeque<>();
        pushChildrenReversed(parent, stack);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            if (n instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (n instanceof Code c) {
                sb.append(c.getLiteral());
            } else if (n instanceof HtmlInline h) {
                sb.append(h.getLiteral());
            } else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) {
                sb.append(' ');
            } else {
                pushChildrenReversed(n, stack);
            }
        }
        return sb.toString();
    }

    /** Pushes children so the stack pops them in document order. */
    private static void pushChildrenReversed(Node parent, Deque<Node> stack) {
        for (Node child = parent.getLastChild(); child != null; child = child.getPrevious()) {
            stack.push(child);
        }
    }

    private static String stripTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    // ------------------------------------------------------------------ styles

    /** Immutable inline-style context threaded through the walk; each flag maps to attributes. */
    private record Style(int size, boolean bold, boolean italic, boolean code, boolean mono,
            boolean quote, String link) {

        static Style base(int size) {
            return new Style(size, false, false, false, false, false, null);
        }

        Style bolded() {
            return new Style(size, true, italic, code, mono, quote, link);
        }

        Style italicized() {
            return new Style(size, bold, true, code, mono, quote, link);
        }

        Style coded() {
            return new Style(size, bold, italic, true, mono, quote, link);
        }

        Style monoed() {
            return new Style(size, bold, italic, code, true, quote, link);
        }

        Style quoted() {
            return new Style(size, bold, italic, code, mono, true, link);
        }

        Style linked(String url) {
            return new Style(size, bold, italic, code, mono, quote, url);
        }

        Style heading(int sizeDelta) {
            return new Style(size + sizeDelta, true, italic, code, mono, quote, link);
        }
    }

    private static SimpleAttributeSet attrs(Style st) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontSize(a, st.size());
        StyleConstants.setForeground(a,
                st.link() != null ? LINK_COLOR : st.quote() ? QUOTE_COLOR : TEXT_COLOR);
        if (st.bold()) {
            StyleConstants.setBold(a, true);
        }
        if (st.italic()) {
            StyleConstants.setItalic(a, true);
        }
        if (st.code() || st.mono()) {
            StyleConstants.setFontFamily(a, Font.MONOSPACED);
        }
        if (st.code()) {
            StyleConstants.setBackground(a, CODE_BG);
        }
        if (st.link() != null) {
            StyleConstants.setUnderline(a, true);
            a.addAttribute(LINK_URL, st.link());
        }
        return a;
    }

    // ------------------------------------------------------------------ run assembly

    /** Collects emitted text, coalescing adjacent slices whose attributes are equal. */
    private static final class Emitter {

        private final List<Run> runs = new ArrayList<>();
        private StringBuilder pending;
        private AttributeSet pendingAttrs;

        void emit(String text, AttributeSet attributes) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (pending != null && pendingAttrs.equals(attributes)) {
                pending.append(text);
                return;
            }
            flush();
            pending = new StringBuilder(text);
            pendingAttrs = attributes;
        }

        private void flush() {
            if (pending != null) {
                runs.add(new Run(pending.toString(), pendingAttrs));
                pending = null;
                pendingAttrs = null;
            }
        }

        List<Run> finish() {
            flush();
            return runs;
        }
    }
}
