package io.github.hakjuoh.protege_mcp.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.text.View;

/** Read-only Preferences prose whose preferred height follows its currently allocated width. */
final class ResponsiveHelpText extends JTextArea {

    private static final long serialVersionUID = 1L;

    ResponsiveHelpText(String text) {
        super(text);
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont != null) {
            setFont(labelFont.deriveFont(Font.PLAIN, 10f));
        }
        setForeground(Color.GRAY);
        setBorder(BorderFactory.createEmptyBorder(3, 20, 7, 0));
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                // A wrapped text component's preferred height changes with width. Ask GridBag to
                // allocate the new height after the dialog or its tab is resized.
                if (getParent() != null) {
                    getParent().revalidate();
                }
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        int wrappingWidth = getWidth() > 0
                ? getWidth() : PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX;
        Insets insets = getInsets();
        View root = getUI().getRootView(this);
        root.setSize(Math.max(1, wrappingWidth - insets.left - insets.right), Integer.MAX_VALUE);
        int height = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS))
                + insets.top + insets.bottom;
        // Keep the initial dialog compact without making this a fixed width: the surrounding
        // GridBag uses fill=HORIZONTAL and may allocate any available width after this hint.
        return new Dimension(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, height);
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(0, preferred.height);
    }
}
