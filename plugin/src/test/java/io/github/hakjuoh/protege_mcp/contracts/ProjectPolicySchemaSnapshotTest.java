package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

/** Immutable hashes for the public policy schemas shipped with the 0.8 contract baseline. */
class ProjectPolicySchemaSnapshotTest {

    @Test
    void versionOneSchemaRemainsByteForByteCompatible() throws Exception {
        assertEquals("487bad09090764763949fddd0d439a1af433154891d0e62ff3d3fa5ca3fb11e8",
                digest("/schema/project-policy-v1.schema.json"));
    }

    @Test
    void versionTwoSchemaMatchesTheReviewedBaseline() throws Exception {
        assertEquals("cd5bac8870193631ea975538e2cb62f066acfcc028fa4c6862815241d2c18e1b",
                digest("/schema/project-policy-v2.schema.json"));
    }

    private static String digest(String resource) throws Exception {
        try (InputStream input = ProjectPolicySchemaSnapshotTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, () -> "missing schema resource " + resource);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.readAllBytes());
            StringBuilder out = new StringBuilder(64);
            for (byte value : hash) {
                out.append(Character.forDigit((value >>> 4) & 0xf, 16));
                out.append(Character.forDigit(value & 0xf, 16));
            }
            return out.toString();
        }
    }
}
