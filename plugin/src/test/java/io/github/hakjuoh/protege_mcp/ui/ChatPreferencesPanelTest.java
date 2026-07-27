package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatModels;
import io.github.hakjuoh.protege_mcp.testing.TestPreferences;

/**
 * Headless coverage for the ordered model editor: the Add/Update/Delete UI semantics, the Enter key
 * that has to apply the field without stealing the dialog's OK button, and the feedback line that has
 * to say an edit is only staged until OK.
 */
class ChatPreferencesPanelTest {

    @Test
    void applyAddsWhenNoRowIsSelectedAndUpdatesWhenOneIsSelected() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");

        JTextField field = field(editor, "modelField", JTextField.class);
        JButton apply = field(editor, "applyButton", JButton.class);
        assertEquals("Add", apply.getText());
        onEdt(() -> field.setText("third"));
        invoke(editor, "applyModel");
        assertEquals(List.of("first", "second", "third"), models(editor));
        JList<?> list = field(editor, "modelList", JList.class);
        assertEquals(-1, list.getSelectedIndex());
        assertEquals("Add", apply.getText());

        onEdt(() -> list.setSelectedIndex(1));
        assertEquals("Update", apply.getText());
        onEdt(() -> field.setText("updated"));
        invoke(editor, "applyModel");
        assertEquals(List.of("first", "updated", "third"), models(editor));

        onEdt(() -> field.setText(""));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(-1, list.getSelectedIndex());
        assertEquals("Add", apply.getText());
    }

    @Test
    void listRendererShowsCompactActionsAndNativeSelection() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "claude", List.of("first", "second"));
        Object editor = newEditor("claude", preferences);
        invoke(editor, "component");
        JList<String> list = field(editor, "modelList", JList.class);
        assertInstanceOf(DefaultListModel.class, list.getModel());
        assertEquals(Color.WHITE, list.getBackground());

        Component unselected = list.getCellRenderer().getListCellRendererComponent(
                list, "first", 0, false, false);
        assertEquals(List.of("X"), buttonTexts(unselected));

        list.setSelectedIndex(1);
        Component selected = list.getCellRenderer().getListCellRendererComponent(
                list, "second", 1, true, true);
        assertEquals(List.of("↑", "↓", "X"), buttonTexts(selected));
        for (JButton button : buttons(selected)) {
            assertEquals(24, button.getPreferredSize().width);
            assertTrue(button.getMargin().left <= 2 && button.getMargin().right <= 2);
        }
        assertEquals(list.getSelectionBackground(), selected.getBackground());

        list.setSize(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, 52);
        Rectangle selectedBounds = list.getCellBounds(1, 1);
        dispatchPress(list, selectedBounds.x + selectedBounds.width - 64,
                selectedBounds.y + selectedBounds.height / 2);
        assertEquals(List.of("second", "first"), models(editor));

        JScrollPane scroll = field(editor, "modelScroll", JScrollPane.class);
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
        assertEquals(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, scroll.getPreferredSize().width);
        assertEquals(Color.WHITE, scroll.getViewport().getBackground());
    }

    @Test
    void rowDeleteRemovesOnlyTheClickedModel() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second", "third"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        list.setSize(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, 78);
        Rectangle second = list.getCellBounds(1, 1);
        dispatchPress(list, second.x + second.width - 12, second.y + second.height / 2);
        assertEquals(List.of("first", "third"), models(editor));
        assertEquals(-1, list.getSelectedIndex());
    }

    @Test
    void nonPrimaryClickDoesNotTriggerRowActions() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        list.setSize(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, 52);
        Rectangle first = list.getCellBounds(0, 0);

        dispatchPress(list, first.x + first.width - 12,
                first.y + first.height / 2, MouseEvent.BUTTON3);

        assertEquals(List.of("first", "second"), models(editor));
    }

    // ---- Enter, and the feedback that says nothing is stored yet ---------------------------------

    @Test
    void enterAddsTheTypedModelExactlyLikeTheAddButton() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");

        type(editor, "gpt-5-codex");
        pressEnter(editor);

        assertEquals(List.of("first", "gpt-5-codex"), models(editor));
        assertEquals("", field(editor, "modelField", JTextField.class).getText(),
                "the field clears after an add, ready for the next id");
    }

    @Test
    void enterUpdatesTheSelectedRowRatherThanAddingACopy() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);

        onEdt(() -> list.setSelectedIndex(1));
        type(editor, "renamed");
        pressEnter(editor);

        assertEquals(List.of("first", "renamed"), models(editor));
    }

    @Test
    void enterOnAnEmptyFieldIsLeftToTheDialogsOkButton() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JTextField field = field(editor, "modelField", JTextField.class);

        Object binding = field.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        Action action = field.getActionMap().get(binding);
        assertFalse(action.isEnabled(),
                "a blank field must leave Enter to the Preferences dialog's default button, which is "
                        + "the only thing that actually stores an edit");

        // What Swing does with a disabled binding: nothing happens here, and the key travels on.
        pressEnter(editor);
        assertEquals(List.of("first"), models(editor));
    }

    @Test
    void everyStagedEditSaysItIsNotStoredUntilOk() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);

        type(editor, "third");
        pressEnter(editor);
        assertEquals("Added third — click OK to save.", feedback(editor));

        onEdt(() -> list.setSelectedIndex(0));
        type(editor, "renamed");
        pressEnter(editor);
        assertEquals("Updated model id — click OK to save.", feedback(editor));

        invoke(editor, "moveModel", 1, 1);
        assertEquals("Moved second — click OK to save.", feedback(editor));

        invoke(editor, "deleteModel", 0);
        assertEquals("Deleted renamed — click OK to save.", feedback(editor));
    }

    @Test
    void aRefusedEditIsNotReportedAsSomethingOkWouldSave() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");

        type(editor, "first");
        pressEnter(editor);

        assertEquals("That model id is already listed.", feedback(editor));
        assertEquals(List.of("first"), models(editor));
    }

    // ---- what an open Assistant is left with ------------------------------------------------------

    @Test
    void aModelSelectionSurvivesAnEditUnlessItWasDeleted() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("kept", "dropped"));
        preferences.putString(ChatModels.modelPrefKey("codex"), "kept");

        clearMissingModelSelection(preferences, "codex", List.of("kept", "dropped"));
        assertEquals("kept", preferences.getString(ChatModels.modelPrefKey("codex"), ""),
                "an edit that leaves the selected id in place must not reset the picker");

        preferences.putString(ChatModels.modelPrefKey("codex"), "dropped");
        clearMissingModelSelection(preferences, "codex", List.of("kept"));
        assertEquals("", preferences.getString(ChatModels.modelPrefKey("codex"), ""),
                "a selection the edit deleted must fall back to the CLI default, not linger as a "
                        + "value the next turn would still run on");
    }

    private static void clearMissingModelSelection(Preferences preferences, String providerId,
            List<String> catalog) throws Exception {
        Method method = ChatPreferencesPanel.class.getDeclaredMethod("clearMissingModelSelection",
                Preferences.class, String.class, List.class);
        method.setAccessible(true);
        method.invoke(null, preferences, providerId, catalog);
    }

    private static void type(Object editor, String text) throws Exception {
        JTextField field = field(editor, "modelField", JTextField.class);
        onEdt(() -> field.setText(text));
    }

    /** Presses Enter through the field's own key bindings, exactly as a focused field would. */
    private static void pressEnter(Object editor) throws Exception {
        JTextField field = field(editor, "modelField", JTextField.class);
        KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        onEdt(() -> {
            Object binding = field.getInputMap().get(enter);
            Action action = field.getActionMap().get(binding);
            SwingUtilities.notifyAction(action, enter, new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'), field, 0);
        });
    }

    private static String feedback(Object editor) throws Exception {
        return field(editor, "feedback", JLabel.class).getText();
    }

    private static Object newEditor(Preferences preferences) throws Exception {
        return newEditor("codex", preferences);
    }

    private static Object newEditor(String providerId, Preferences preferences) throws Exception {
        Class<?> type = Class.forName(ChatPreferencesPanel.class.getName() + "$ModelEditor");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, Preferences.class);
        constructor.setAccessible(true);
        return constructor.newInstance(providerId, providerId + " models", preferences);
    }

    @SuppressWarnings("unchecked")
    private static List<String> models(Object editor) throws Exception {
        DefaultListModel<String> model = field(editor, "modelData", DefaultListModel.class);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            values.add(model.get(i));
        }
        return List.copyOf(values);
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
        }
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static List<String> buttonTexts(Component root) {
        return buttons(root).stream().map(JButton::getText).toList();
    }

    private static List<JButton> buttons(Component root) {
        List<JButton> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        return buttons;
    }

    private static void collectButtons(Component component, List<JButton> buttons) {
        if (component instanceof JButton button) {
            buttons.add(button);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectButtons(child, buttons);
            }
        }
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void dispatchPress(JList<?> list, int x, int y) throws Exception {
        dispatchPress(list, x, y, MouseEvent.BUTTON1);
    }

    private static void dispatchPress(JList<?> list, int x, int y, int button) throws Exception {
        onEdt(() -> list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, 1, false, button)));
    }

}
