package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.text.PlainDocument;

import io.github.hakjuoh.protege_mcp.external.OntoPortalProvider;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.AuthScheme;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.CredentialBinding;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.OriginBinding;

/** Modal editor for one credential binding; stored secrets are never pre-filled. */
final class TerminologyCredentialDialog extends JDialog {

    private static final long serialVersionUID = 1L;
    private final JTextField id = new JTextField(20);
    private final JTextField providerId = new JTextField(20);
    private final JComboBox<String> originAlias;
    private final JComboBox<AuthScheme> scheme = new JComboBox<>(AuthScheme.values());
    private final JTextField header = new JTextField(20);
    private final JTextField projectFingerprint = new JTextField(28);
    private final JPasswordField secret = new JPasswordField(24);
    private final boolean secretRequired;
    private final Map<String, String> originProfiles = new LinkedHashMap<>();
    private String autoFilledId;
    private String autoFilledProviderId;
    private CredentialBinding result;
    private char[] resultSecret;

    TerminologyCredentialDialog(Frame owner, String title, List<OriginBinding> origins,
            CredentialBinding initial) {
        super(owner, title, true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { cancel(); }
        });
        secretRequired = initial == null;
        origins.forEach(origin -> originProfiles.put(origin.alias(), origin.profile()));
        originAlias = new JComboBox<>(originProfiles.keySet().toArray(String[]::new));
        String initialAlias = initial == null ? String.valueOf(originAlias.getSelectedItem())
                : initial.originAlias();
        id.setText(initial == null ? initialAlias : initial.id());
        providerId.setText(initial == null ? initialAlias : initial.providerId());
        if (initial == null) {
            autoFilledId = initialAlias;
            autoFilledProviderId = initialAlias;
        }
        if (initial != null) originAlias.setSelectedItem(initial.originAlias());
        scheme.setSelectedItem(initial == null
                ? defaultSchemeForProfile(selectedProfile()) : initial.scheme());
        scheme.setRenderer((list, value, index, selected, focused) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer().getListCellRendererComponent(
                    list, value, index, selected, focused);
            label.setText(value == null ? "" : switch (value) {
                case BEARER -> "Bearer token";
                case API_KEY -> "API key header";
                case ONTOPORTAL_API_KEY ->
                        "OntoPortal API key (Authorization: apikey token=<key>)";
                case QUERY_API_KEY -> "Query parameter: apikey";
            });
            return label;
        });
        header.setText(initial == null ? placementName((AuthScheme) scheme.getSelectedItem())
                : initial.header() == null ? initial.parameter() : initial.header());
        if (initial == null) setSecretValue(defaultValueForProfile(selectedProfile()));
        projectFingerprint.setText(initial == null || initial.projectFingerprint() == null
                ? "" : initial.projectFingerprint());
        scheme.addActionListener(ignored -> updatePlacementForScheme());
        if (initial == null) originAlias.addActionListener(ignored -> applyOriginDefaults());
        updatePlacementEditability();

        setLayout(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, constraints, 0, "Credential ID (policy credential_id):", id);
        addRow(form, constraints, 1, "Policy provider ID (policy id):", providerId);
        addRow(form, constraints, 2, "Origin alias:", originAlias);
        addRow(form, constraints, 3, "Authentication:", scheme);
        addRow(form, constraints, 4, "Header / query parameter:", header);
        addRow(form, constraints, 5, "Project fingerprint (optional):", projectFingerprint);
        addRow(form, constraints, 6,
                secretRequired ? "Secret:"
                        : "New secret (required if ID or authentication changes):", secret);
        add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(ignored -> cancel());
        JButton save = new JButton("OK");
        save.addActionListener(ignored -> confirm());
        buttons.add(cancel);
        buttons.add(save);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private void confirm() {
        char[] password = secret.getPassword();
        try {
            if (secretRequired && password.length == 0) {
                throw new IllegalArgumentException("secret is required");
            }
            String fingerprint = projectFingerprint.getText().trim();
            AuthScheme selectedScheme = (AuthScheme) scheme.getSelectedItem();
            String placement = header.getText().trim();
            result = new CredentialBinding(id.getText().trim(), providerId.getText().trim(),
                    String.valueOf(originAlias.getSelectedItem()),
                    selectedScheme,
                    selectedScheme == AuthScheme.QUERY_API_KEY ? null : placement,
                    selectedScheme == AuthScheme.QUERY_API_KEY ? placement : null,
                    fingerprint.isEmpty() ? null : fingerprint);
            resultSecret = password.clone();
            clearSecretField();
            dispose();
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this,
                    "Enter valid IDs, a compatible authentication header, and a secret for new credentials.",
                    "Invalid credential binding", JOptionPane.ERROR_MESSAGE);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void cancel() {
        clearSecretField();
        dispose();
    }

    private void clearSecretField() {
        char[] visible = secret.getPassword();
        java.util.Arrays.fill(visible, '\0');
        secret.setText("");
        secret.setDocument(new PlainDocument());
    }

    private void applyOriginDefaults() {
        String selectedAlias = String.valueOf(originAlias.getSelectedItem());
        if (shouldReplaceAutoFilledValue(id.getText(), autoFilledId)) {
            id.setText(selectedAlias);
            autoFilledId = selectedAlias;
        }
        if (shouldReplaceAutoFilledValue(providerId.getText(), autoFilledProviderId)) {
            providerId.setText(selectedAlias);
            autoFilledProviderId = selectedAlias;
        }
        scheme.setSelectedItem(defaultSchemeForProfile(selectedProfile()));
        setSecretValue(defaultValueForProfile(selectedProfile()));
    }

    private void updatePlacementForScheme() {
        AuthScheme selected = (AuthScheme) scheme.getSelectedItem();
        header.setText(placementName(selected));
        updatePlacementEditability();
    }

    private void updatePlacementEditability() {
        header.setEditable(scheme.getSelectedItem() == AuthScheme.API_KEY);
    }

    private String selectedProfile() {
        return originProfiles.get(String.valueOf(originAlias.getSelectedItem()));
    }

    static AuthScheme defaultSchemeForProfile(String profile) {
        return OntoPortalProvider.PROFILE.equals(profile)
                ? AuthScheme.ONTOPORTAL_API_KEY : AuthScheme.BEARER;
    }

    static String defaultValueForProfile(String profile) {
        return "";
    }

    static boolean shouldReplaceAutoFilledValue(String current, String previousAutoFill) {
        return current == null || current.isBlank() || current.trim().equals(previousAutoFill);
    }

    private static String placementName(AuthScheme selected) {
        return selected.defaultHeader() == null
                ? selected.defaultParameter() : selected.defaultHeader();
    }

    private void setSecretValue(String value) {
        char[] previous = secret.getPassword();
        java.util.Arrays.fill(previous, '\0');
        secret.setText(value);
        secret.setCaretPosition(value.length());
    }

    CredentialBinding result() {
        return result;
    }

    char[] secret() {
        if (resultSecret == null) return new char[0];
        char[] copy = resultSecret.clone();
        java.util.Arrays.fill(resultSecret, '\0');
        resultSecret = null;
        return copy;
    }

    private static void addRow(JPanel panel, GridBagConstraints base, int row, String label,
            java.awt.Component editor) {
        GridBagConstraints left = (GridBagConstraints) base.clone();
        left.gridx = 0;
        left.gridy = row;
        panel.add(new JLabel(label), left);
        GridBagConstraints right = (GridBagConstraints) base.clone();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        panel.add(editor, right);
    }
}
