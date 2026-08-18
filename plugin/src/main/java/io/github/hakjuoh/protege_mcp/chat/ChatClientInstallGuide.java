package io.github.hakjuoh.protege_mcp.chat;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Official installation and first-run guidance for one CLI adapter. */
public record ChatClientInstallGuide(String macOsLinuxCommand, String windowsCommand,
        String firstRunInstruction, URI documentationUri) {

    public ChatClientInstallGuide {
        macOsLinuxCommand = required(macOsLinuxCommand, "macOsLinuxCommand");
        windowsCommand = required(windowsCommand, "windowsCommand");
        firstRunInstruction = required(firstRunInstruction, "firstRunInstruction");
        documentationUri = Objects.requireNonNull(documentationUri, "documentationUri");
        if (!"https".equalsIgnoreCase(documentationUri.getScheme())
                || documentationUri.getHost() == null) {
            throw new IllegalArgumentException("documentationUri must be an absolute HTTPS URI");
        }
    }

    /** Selects the copy-paste command and its label for an operating-system name. */
    public InstallCommand commandFor(String osName) {
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("win")) {
            return new InstallCommand("PowerShell", windowsCommand);
        }
        return new InstallCommand("Terminal", macOsLinuxCommand);
    }

    /** A labelled command selected for one platform. */
    public record InstallCommand(String label, String command) {
        public InstallCommand {
            label = required(label, "label");
            command = required(command, "command");
        }
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
