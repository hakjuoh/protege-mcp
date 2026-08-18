package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.Icon;
import javax.swing.JButton;

/** Shared flat icons used by the chat composer and transcript affordances. */
final class ChatIcons {

    enum Glyph { PLUS, SEND, STOP, COPY, CHECK }

    private ChatIcons() {
    }

    static JButton iconButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(2, 2, 2, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /** Draws a small plus, send arrow, stop square, copy sheets, or confirmation check. */
    static Icon icon(Glyph glyph, int size, Color foreground, Color background) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.translate(x, y);
                    float s = size;
                    if (background != null) {
                        g.setColor(background);
                        g.fill(new Ellipse2D.Float(0, 0, s, s));
                    }
                    g.setColor(foreground);
                    switch (glyph) {
                        case PLUS -> drawPlus(g, s);
                        case SEND -> drawSend(g, s);
                        case STOP -> drawStop(g, s);
                        case COPY -> drawCopy(g, s);
                        case CHECK -> drawCheck(g, s);
                        default -> {
                        }
                    }
                } finally {
                    g.dispose();
                }
            }
        };
    }

    private static void drawPlus(Graphics2D g, float size) {
        g.setStroke(roundStroke(Math.max(1.6f, size * 0.11f)));
        float margin = size * 0.24f;
        g.draw(new Line2D.Float(size / 2, margin, size / 2, size - margin));
        g.draw(new Line2D.Float(margin, size / 2, size - margin, size / 2));
    }

    private static void drawSend(Graphics2D g, float size) {
        g.setStroke(roundStroke(Math.max(1.7f, size * 0.10f)));
        float center = size / 2;
        float top = size * 0.30f;
        float bottom = size * 0.72f;
        float head = size * 0.17f;
        g.draw(new Line2D.Float(center, top, center, bottom));
        g.draw(new Line2D.Float(center, top, center - head, top + head));
        g.draw(new Line2D.Float(center, top, center + head, top + head));
    }

    private static void drawStop(Graphics2D g, float size) {
        float margin = size * 0.34f;
        g.fill(new RoundRectangle2D.Float(margin, margin, size - 2 * margin,
                size - 2 * margin, size * 0.08f, size * 0.08f));
    }

    private static void drawCopy(Graphics2D g, float size) {
        g.setStroke(roundStroke(Math.max(1.2f, size * 0.09f)));
        float side = size * 0.50f;
        float arc = size * 0.14f;
        float frontX = size * 0.16f;
        float frontY = size - size * 0.16f - side;
        float backX = frontX + size * 0.19f;
        float backY = frontY - size * 0.19f;
        Path2D.Float back = new Path2D.Float();
        back.moveTo(backX, frontY);
        back.lineTo(backX, backY + arc);
        back.quadTo(backX, backY, backX + arc, backY);
        back.lineTo(backX + side - arc, backY);
        back.quadTo(backX + side, backY, backX + side, backY + arc);
        back.lineTo(backX + side, backY + side);
        back.lineTo(frontX + side, backY + side);
        g.draw(back);
        g.draw(new RoundRectangle2D.Float(frontX, frontY, side, side, arc, arc));
    }

    private static void drawCheck(Graphics2D g, float size) {
        g.setStroke(roundStroke(Math.max(1.6f, size * 0.12f)));
        Path2D.Float check = new Path2D.Float();
        check.moveTo(size * 0.24f, size * 0.54f);
        check.lineTo(size * 0.43f, size * 0.72f);
        check.lineTo(size * 0.78f, size * 0.30f);
        g.draw(check);
    }

    private static BasicStroke roundStroke(float width) {
        return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }
}
