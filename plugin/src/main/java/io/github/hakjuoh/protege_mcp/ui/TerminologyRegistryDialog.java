package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import io.github.hakjuoh.protege_mcp.external.Ols4Provider;
import io.github.hakjuoh.protege_mcp.external.OntoPortalProvider;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.OriginBinding;

/** Modal editor for one owner-bound terminology origin. */
final class TerminologyRegistryDialog extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final String CUSTOM_PRESET = "Custom";
    private static final String BIOPORTAL_PRESET = "BioPortal";
    private static final String AGROPORTAL_PRESET = "AgroPortal";
    private static final List<String> SUPPORTED_PROFILES = List.of(
            Ols4Provider.PROFILE, OntoPortalProvider.PROFILE);
    private static final Map<String, String> ONTOPORTAL_PRESETS = Map.of(
            BIOPORTAL_PRESET, "https://data.bioontology.org",
            AGROPORTAL_PRESET, "https://data.agroportal.eu");
    private final JTextField alias = new JTextField(20);
    private final JComboBox<String> profile = new JComboBox<>(
            SUPPORTED_PROFILES.toArray(String[]::new));
    private final JComboBox<String> preset = new JComboBox<>(new String[] {
            CUSTOM_PRESET, BIOPORTAL_PRESET, AGROPORTAL_PRESET});
    private final JTextField origin = new JTextField(32);
    private final JLabel loopbackWarning = new JLabel(
            "Loopback origins enable the test-only private-address exemption.");
    private final PresetBehavior presetBehavior;
    private OriginBinding result;

    TerminologyRegistryDialog(Frame owner, String title, OriginBinding initial) {
        super(owner, title, true);
        setLayout(new BorderLayout());
        alias.setText(initial == null ? "" : initial.alias());
        profile.setSelectedItem(initial == null ? Ols4Provider.PROFILE : initial.profile());
        preset.setSelectedItem(initial == null ? CUSTOM_PRESET
                : presetForOrigin(initial.profile(), initial.origin().toASCIIString()));
        String autoFilledOrigin = initial == null ? defaultCustomOrigin()
                : defaultOriginForPreset(String.valueOf(preset.getSelectedItem()));
        origin.setText(initial == null ? autoFilledOrigin : initial.origin().toASCIIString());
        preset.setEnabled(OntoPortalProvider.PROFILE.equals(profile.getSelectedItem()));
        presetBehavior = new PresetBehavior(profile, preset, origin, autoFilledOrigin);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, constraints, 0, "Origin alias:", alias);
        addRow(form, constraints, 1, "Profile:", profile);
        addRow(form, constraints, 2, "Endpoint preset:", preset);
        addRow(form, constraints, 3, "Origin HTTPS URL:", origin);
        addRow(form, constraints, 4, "", loopbackWarning);
        origin.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateLoopbackWarning(); }
            @Override public void removeUpdate(DocumentEvent event) { updateLoopbackWarning(); }
            @Override public void changedUpdate(DocumentEvent event) { updateLoopbackWarning(); }
        });
        presetBehavior.install();
        profile.addActionListener(ignored -> updateLoopbackWarning());
        preset.addActionListener(ignored -> updateLoopbackWarning());
        updateLoopbackWarning();
        add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(ignored -> dispose());
        JButton save = new JButton("OK");
        save.addActionListener(ignored -> confirm());
        buttons.add(cancel);
        buttons.add(save);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private void confirm() {
        try {
            OriginBinding candidate = bindingFor(alias.getText(), profile.getSelectedItem(),
                    origin.getText());
            if (candidate.testOnlyLoopback()) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "This loopback origin enables a test-only private-address exemption. "
                                + "Use it only for a local test service. Continue?",
                        "Confirm loopback test origin", JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) return;
            }
            result = candidate;
            dispose();
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this,
                    "Enter a valid identifier, profile, and HTTPS origin without a trailing slash.",
                    "Invalid terminology origin", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateLoopbackWarning() {
        try {
            loopbackWarning.setVisible(ProviderOwnerConfig.bindOrigin("preview",
                    String.valueOf(profile.getSelectedItem()),
                    URI.create(origin.getText().trim())).testOnlyLoopback());
        } catch (IllegalArgumentException invalid) {
            loopbackWarning.setVisible(false);
        }
    }

    static String defaultCustomOrigin() {
        return "https://";
    }

    static List<String> supportedProfiles() {
        return SUPPORTED_PROFILES;
    }

    static Map<String, String> ontoPortalPresets() {
        return ONTOPORTAL_PRESETS;
    }

    static String defaultOriginForPreset(String preset) {
        return ONTOPORTAL_PRESETS.get(preset);
    }

    static String presetForOrigin(String profile, String origin) {
        if (!OntoPortalProvider.PROFILE.equals(profile) || origin == null) return CUSTOM_PRESET;
        return ONTOPORTAL_PRESETS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(origin))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(CUSTOM_PRESET);
    }

    static boolean shouldReplaceAutoFilledOrigin(String current, String previousAutoFill) {
        return current == null || current.isBlank() || current.trim().equals(previousAutoFill);
    }

    static OriginBinding bindingFor(String alias, Object profile, String origin) {
        return ProviderOwnerConfig.bindOrigin(alias.trim(), String.valueOf(profile),
                URI.create(origin.trim()));
    }

    /** Installs and owns the state transitions between real Swing profile/preset/origin controls. */
    static final class PresetBehavior {
        private final JComboBox<String> profile;
        private final JComboBox<String> preset;
        private final JTextField origin;
        private String autoFilledOrigin;

        PresetBehavior(JComboBox<String> profile, JComboBox<String> preset, JTextField origin,
                String autoFilledOrigin) {
            this.profile = profile;
            this.preset = preset;
            this.origin = origin;
            this.autoFilledOrigin = autoFilledOrigin;
        }

        void install() {
            profile.addActionListener(ignored -> applyProfileSelection());
            preset.addActionListener(ignored -> applyPresetSelection());
        }

        private void applyProfileSelection() {
            boolean ontoPortal = OntoPortalProvider.PROFILE.equals(profile.getSelectedItem());
            preset.setEnabled(ontoPortal);
            if (ontoPortal) {
                if (CUSTOM_PRESET.equals(preset.getSelectedItem())
                        && shouldReplaceAutoFilledOrigin(origin.getText(), autoFilledOrigin)) {
                    preset.setSelectedItem(BIOPORTAL_PRESET);
                }
                return;
            }
            preset.setSelectedItem(CUSTOM_PRESET);
            if (shouldReplaceAutoFilledOrigin(origin.getText(), autoFilledOrigin)) {
                origin.setText(defaultCustomOrigin());
                autoFilledOrigin = defaultCustomOrigin();
            }
        }

        private void applyPresetSelection() {
            String selectedDefault = defaultOriginForPreset(
                    String.valueOf(preset.getSelectedItem()));
            if (selectedDefault != null) {
                origin.setText(selectedDefault);
                autoFilledOrigin = selectedDefault;
            }
        }
    }

    OriginBinding result() {
        return result;
    }

    private static void addRow(JPanel panel, GridBagConstraints base, int row, String label,
            java.awt.Component editor) {
        GridBagConstraints left = (GridBagConstraints) base.clone();
        left.gridx = 0;
        left.gridy = row;
        left.weightx = 0;
        panel.add(new JLabel(label), left);
        GridBagConstraints right = (GridBagConstraints) base.clone();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        panel.add(editor, right);
    }
}
