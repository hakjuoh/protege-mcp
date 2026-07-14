package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/** Method-level tests for the shared policy/preflight capture helpers in {@link RevisionTools}. */
class RevisionToolsTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";

    @Test
    void preflightDigestCapsDirectoryAssetWalksDuringTraversal(@TempDir Path temp) throws Exception {
        ProjectPolicy policy = policyWithCqDirectory(temp, 5);
        assertNotNull(RevisionTools.preflightDigest(policy),
                "a small asset directory hashes fine under the default cap");

        // The walk visits the directory itself plus five files; a cap of 3 must be exceeded DURING
        // the traversal (before any hashing), never by a post-walk count of an already-crawled tree.
        ToolArgException capped = assertThrows(ToolArgException.class,
                () -> RevisionTools.preflightDigest(policy, 3));
        assertTrue(capped.getMessage().contains("exceeded 3 directory entries"), capped.getMessage());
        assertTrue(capped.getMessage().contains("cqs"), capped.getMessage());
    }

    @Test
    void preflightWalkCapMatchesTheLoaderScanLimit() {
        // Must stay in step with the policy loader's asset-scan limit (see the constant's doc); a
        // preflight that hashes what the loader refused to scan would decide on different data.
        assertEquals(10_000, RevisionTools.MAX_PREFLIGHT_ASSET_ENTRIES);
    }

    /** A valid explicit policy whose only resolved asset is a robot-sparql-dir CQ directory. */
    private static ProjectPolicy policyWithCqDirectory(Path temp, int files) throws Exception {
        Path cqs = Files.createDirectories(temp.resolve("cqs"));
        for (int i = 0; i < files; i++) {
            Files.writeString(cqs.resolve("CQ-" + i + ".rq"),
                    "# id: CQ-" + i + "\n# expected: nonEmpty\nASK { ?s ?p ?o }\n");
        }
        Path policyFile = temp.resolve("policy.yaml");
        Files.writeString(policyFile, "version: 1\n"
                + "project_id: walk-cap\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
                + "validation:\n"
                + "  required_stages: [cqs]\n"
                + "  competency_questions:\n"
                + "    convention: robot-sparql-dir\n"
                + "    path: cqs\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyFile, null, ONTOLOGY_IRI, List.of());
        assertTrue(policy.valid(), () -> String.valueOf(policy.issues()));
        return policy;
    }
}
