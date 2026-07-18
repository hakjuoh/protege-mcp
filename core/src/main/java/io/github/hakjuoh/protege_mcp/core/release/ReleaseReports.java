package io.github.hakjuoh.protege_mcp.core.release;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.StageResult;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

/**
 * Deterministic report renderers for a release gate result: the same {@link GateResult} always
 * produces byte-identical JSON, Markdown, JUnit XML, and SARIF 2.1.0 (there is no wall-clock; any
 * timestamp is a caller input). The four formats project one result — never a re-computed one — so a
 * finding cannot appear in one report and vanish from another.
 */
public final class ReleaseReports {

    /** SARIF driver + JUnit suite/report producer name. */
    public static final String TOOL_NAME = "protege-mcp";
    private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

    private ReleaseReports() {
    }

    /** Canonical pretty JSON of the gate result (the machine-composable form). */
    public static String json(GateResult gate) {
        ObjectMapper mapper = ContractJson.mapper()
                .copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        try {
            return mapper.writeValueAsString(gate);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render release gate JSON", e);
        }
    }

    /** Human-readable Markdown summary: verdict, per-stage table, then a bounded findings list. */
    public static String markdown(GateResult gate, String versionIri, int findingLimit) {
        StringBuilder md = new StringBuilder();
        md.append("# Release gate: ").append(gate.gate().json()).append("\n\n");
        if (versionIri != null) {
            md.append("- Version IRI: `").append(versionIri).append("`\n");
        }
        md.append("- Semantic fingerprint: `").append(gate.semanticFingerprint()).append("`\n");
        md.append("- Required stages: ").append(String.join(", ", gate.requiredStages()))
                .append("\n");
        md.append("- Stages run/skipped: ").append(gate.stagesRan()).append('/')
                .append(gate.stagesSkipped()).append("\n\n");

        md.append("## Stages\n\n");
        md.append("| Stage | Status | Findings |\n| --- | --- | --- |\n");
        for (StageResult stage : gate.stages()) {
            md.append("| `").append(stage.stage()).append("` | ").append(stage.status().json())
                    .append(" | ").append(stage.findings().size()).append(" |\n");
        }

        List<Finding> findings = gate.findings();
        md.append("\n## Findings (").append(findings.size()).append(")\n\n");
        if (findings.isEmpty()) {
            md.append("_No findings._\n");
        } else {
            int shown = Math.min(findings.size(), findingLimit);
            for (int i = 0; i < shown; i++) {
                Finding f = findings.get(i);
                md.append("- **").append(f.severity().json()).append("** `").append(f.id())
                        .append("` — ").append(escapeInline(f.message()));
                if (f.focusIri() != null) {
                    md.append(" (`").append(f.focusIri()).append("`)");
                }
                md.append('\n');
            }
            if (findings.size() > shown) {
                md.append("- _… ").append(findings.size() - shown).append(" more._\n");
            }
        }
        return md.toString();
    }

    /**
     * JUnit XML: one {@code <testsuite>} per stage, one {@code <testcase>} per finding (a
     * {@code <failure>} at or over the threshold, otherwise an informational pass) plus a synthetic
     * passing case for a clean stage; an errored stage is one erroring testcase, a skipped stage one
     * {@code <skipped/>} case. {@code timestamp} is an ISO-8601 string with no zone offset.
     */
    public static String junit(GateResult gate, String timestamp) {
        FindingSeverity failOn = FindingSeverity.fromJson((String) gate.details().get("fail_on"));
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        int suiteTests = 0;
        int suiteFailures = 0;
        int suiteErrors = 0;
        int suiteSkipped = 0;
        StringBuilder suites = new StringBuilder();
        for (StageResult stage : gate.stages()) {
            List<String> cases = new ArrayList<>();
            int tests = 0;
            int failures = 0;
            int errors = 0;
            int skipped = 0;
            if (stage.status() == StageStatus.SKIPPED) {
                cases.add(testcase(stage.stage(), "skipped",
                        "    <skipped message=\"" + attr(stage.message()) + "\"/>\n"));
                tests++;
                skipped++;
            } else if (stage.status() == StageStatus.ERROR) {
                cases.add(testcase(stage.stage(), "error",
                        "    <error message=\"" + attr(stage.message()) + "\">"
                                + text(stage.message()) + "</error>\n"));
                tests++;
                errors++;
            } else if (stage.findings().isEmpty()) {
                cases.add(testcase(stage.stage(), "pass", null));
                tests++;
            } else {
                for (Finding f : stage.findings()) {
                    if (f.severity().reaches(failOn)) {
                        cases.add(testcase(f.id(), f.severity().json(),
                                "    <failure message=\"" + attr(f.message()) + "\" type=\""
                                        + attr(f.id()) + "\">" + text(findingBody(f))
                                        + "</failure>\n"));
                        failures++;
                    } else {
                        cases.add(testcase(f.id(), f.severity().json(), null));
                    }
                    tests++;
                }
            }
            suites.append("  <testsuite name=\"").append(attr(stage.stage()))
                    .append("\" tests=\"").append(tests).append("\" failures=\"").append(failures)
                    .append("\" errors=\"").append(errors).append("\" skipped=\"").append(skipped)
                    .append("\" time=\"0\" timestamp=\"").append(attr(timestamp)).append("\">\n");
            cases.forEach(suites::append);
            suites.append("  </testsuite>\n");
            suiteTests += tests;
            suiteFailures += failures;
            suiteErrors += errors;
            suiteSkipped += skipped;
        }
        xml.append("<testsuites name=\"").append(TOOL_NAME).append("-release-gate\" tests=\"")
                .append(suiteTests).append("\" failures=\"").append(suiteFailures)
                .append("\" errors=\"").append(suiteErrors).append("\" skipped=\"")
                .append(suiteSkipped).append("\">\n");
        xml.append(suites);
        xml.append("</testsuites>\n");
        return xml.toString();
    }

    private static String testcase(String name, String classname, String body) {
        String open = "  <testcase name=\"" + attr(name) + "\" classname=\"" + attr(classname)
                + "\" time=\"0\"";
        if (body == null) {
            return open + "/>\n";
        }
        return open + ">\n" + body + "  </testcase>\n";
    }

    private static String findingBody(Finding f) {
        StringBuilder body = new StringBuilder(f.message());
        if (f.focusIri() != null) {
            body.append(" [focus: ").append(f.focusIri()).append(']');
        }
        return body.toString();
    }

    /**
     * SARIF 2.1.0 (single run). Each distinct finding {@code id} becomes a rule with short/full
     * description and help text; each finding becomes a result whose {@code level} maps
     * info→note / warning→warning / error→error, {@code startLine} 1 for ontology-level findings,
     * with a {@code partialFingerprints.primaryLocationLineHash} keyed on rule id + focus IRI so the
     * same logical finding deduplicates across runs.
     *
     * <p>{@code artifactLocation.uri} is the finding's {@code path} when present, else the
     * {@code fallbackUri} — which MUST be a repo-root-relative reference the caller controls (the
     * ontology document name, never an absolute local filesystem path): SARIF URIs are
     * repo-root-relative and an absolute path would leak the local directory layout into the shipped
     * report. A null/blank fallback degrades to the literal {@code "ontology"} placeholder.
     */
    public static Map<String, Object> sarif(GateResult gate, String fallbackUri) {
        Map<String, Boolean> ruleErrored = new TreeMap<>();
        List<Finding> findings = gate.findings();
        for (Finding f : findings) {
            ruleErrored.putIfAbsent(f.id(), Boolean.TRUE);
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String ruleId : ruleErrored.keySet()) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("id", ruleId);
            rule.put("shortDescription", Map.of("text", ruleId));
            rule.put("fullDescription", Map.of("text",
                    "protege-mcp release-gate finding of type " + ruleId + "."));
            rule.put("help", Map.of("text",
                    "See the protege-mcp release documentation for finding " + ruleId + "."));
            rules.add(rule);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", f.id());
            result.put("level", sarifLevel(f.severity()));
            result.put("message", Map.of("text", f.message()));
            String pathUri = f.path();
            String uri = pathUri != null ? pathUri
                    : (fallbackUri == null || fallbackUri.isBlank() ? "ontology" : fallbackUri);
            // A finding path must never be an absolute local filesystem path: SARIF URIs are
            // repo-root-relative and would otherwise leak the user's directory layout.
            if (uri.startsWith("/") || uri.matches("^[A-Za-z]:[\\\\/].*")) {
                uri = "ontology";
            }
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("startLine", 1);
            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("artifactLocation", Map.of("uri", uri));
            physical.put("region", region);
            result.put("locations", List.of(Map.of("physicalLocation", physical)));
            result.put("partialFingerprints", Map.of("primaryLocationLineHash",
                    ArtifactStore.sha256((f.id() + "|" + (f.focusIri() == null ? "" : f.focusIri()))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            results.add(result);
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", TOOL_NAME);
        driver.put("rules", rules);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("results", results);
        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", SARIF_SCHEMA);
        sarif.put("version", "2.1.0");
        sarif.put("runs", List.of(run));
        return sarif;
    }

    /** Render {@link #sarif} to canonical JSON text. */
    public static String sarifJson(GateResult gate, String fallbackUri) {
        ObjectMapper mapper = ContractJson.mapper().copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
        try {
            return mapper.writeValueAsString(sarif(gate, fallbackUri));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render SARIF", e);
        }
    }

    private static String sarifLevel(FindingSeverity severity) {
        return switch (severity) {
            case INFO -> "note";
            case WARNING -> "warning";
            case ERROR -> "error";
        };
    }

    private static String attr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String text(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeInline(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
