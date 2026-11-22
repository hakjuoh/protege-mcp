package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.util.List;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;

class ChatIconsTest {

    @Test
    void iconOnlyButtonsExposeTheirTooltipAsAccessibleName() {
        for (String tooltip : List.of(
                "Attach files or images", "Send (Enter)", "Stop",
                "Copy message (original Markdown)", "Create or synchronize project policy")) {
            JButton button = ChatIcons.iconButton(
                    ChatIcons.icon(ChatIcons.Glyph.SEND, 18, Color.BLACK, null), tooltip);
            assertEquals(tooltip, button.getAccessibleContext().getAccessibleName());
            assertEquals(tooltip, button.getToolTipText());
        }
    }
}
