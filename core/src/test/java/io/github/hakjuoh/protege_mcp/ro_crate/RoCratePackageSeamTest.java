package io.github.hakjuoh.protege_mcp.ro_crate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The ro_crate package is a deliberate extraction seam: it must stay dependency-clean (JDK +
 * Jackson only — no Protégé, OWLAPI, MCP, or other protege_mcp packages) so it can move to its
 * own Git project later without changing the profile contract. This replaces the enforcer rules
 * the former standalone Maven module carried.
 */
class RoCratePackageSeamTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "^import\\s+(?!java\\.|com\\.fasterxml\\.jackson\\.|io\\.github\\.hakjuoh\\.protege_mcp\\.ro_crate\\.).*",
            Pattern.MULTILINE);

    @Test
    void roCratePackageImportsOnlyJdkAndJackson() throws Exception {
        Path relative = Path.of("src", "main", "java",
                "io", "github", "hakjuoh", "protege_mcp", "ro_crate");
        Path sources = Files.isDirectory(relative) ? relative : Path.of("core").resolve(relative);
        assertTrue(Files.isDirectory(sources), "ro_crate sources not found from " + Path.of("").toAbsolutePath());
        List<Path> files;
        try (var stream = Files.list(sources)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        for (Path file : files) {
            String content = Files.readString(file);
            var matcher = FORBIDDEN.matcher(content);
            assertFalse(matcher.find(),
                    () -> file + " breaks the extraction seam: " + matcher.group());
        }
    }
}
