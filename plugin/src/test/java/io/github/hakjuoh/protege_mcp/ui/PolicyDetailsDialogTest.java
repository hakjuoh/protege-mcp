package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

class PolicyDetailsDialogTest {

    @Test
    void fallbackDialogSizeIsBoundedAndIssueTextRemainsInScrollableContent(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        Dimension size = PolicyDetailsDialog.boundedSize(null);
        assertTrue(size.width <= 760);
        assertTrue(size.height <= 480);
        java.nio.file.Path document = java.nio.file.Files.writeString(
                temp.resolve("ontology.ttl"), "");
        java.nio.file.Path policyPath = temp.resolve(".protege-mcp/project.yaml");
        io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.writePolicy(policyPath,
                io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.minimalPolicy(
                        "dialog", "https://example.org/ontology"));
        ProjectPolicy invalid = ProjectPolicyLoader.load(policyPath, document);
        String longMessage = "x".repeat(5_000);
        String text = PolicyDetailsDialog.text(invalid, null,
                List.of(new PolicyIssue("error", "long", "field", longMessage)));
        assertTrue(text.contains(longMessage.substring(0, 1_000)));
        JScrollPane pane = PolicyDetailsDialog.detailsPane(text);
        JTextArea area = (JTextArea) pane.getViewport().getView();
        assertTrue(area.getLineWrap());
        assertTrue(area.getWrapStyleWord());
        assertTrue(!area.isEditable());
        assertTrue(area.getText().contains(longMessage.substring(0, 1_000)));
    }
}
