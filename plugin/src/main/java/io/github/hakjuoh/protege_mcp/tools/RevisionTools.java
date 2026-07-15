package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/** Public model-revision envelope and shared policy/import-lock capture helpers. */
public final class RevisionTools {

    private RevisionTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("get_model_revision",
                "Return the complete optimistic-concurrency envelope for this Protégé window: a "
                        + "per-backend workspace UUID, monotonic session revision, canonical semantic "
                        + "fingerprint, and live document fingerprint. The document coordinate is "
                        + "recomputed on every call, so a prefix-only GUI edit conflicts even though "
                        + "Protégé emits no model event. The result also reports active ontology, "
                        + "document, dirty/reasoner state, fingerprint stability, and effective policy "
                        + "digest. Read-only.",
                Tools.schema()
                        .str("policy_path", "Optional explicit local project policy; otherwise discover "
                                + ".protege-mcp/project.yaml from the active ontology document upward.")
                        .build(),
                (ex, req) -> Tools.guard(() -> get(ctx, Tools.args(req))));
    }

    static io.modelcontextprotocol.spec.McpSchema.CallToolResult get(ToolContext ctx,
            Map<String, Object> arguments) {
        PolicyState policy = resolvePolicy(ctx, Tools.optString(arguments, "policy_path"));
        String importLockDigest = digestImportLock(policy.policy);
        return ctx.access().compute(mm -> {
            WorkspaceRevisionTracker.RevisionSnapshot snapshot = ctx.revisions().current(
                    mm, importLockDigest, policy.policy.digest());
            OWLOntology active = mm.getActiveOntology();
            OWLOntologyID id = active.getOntologyID();
            IRI document = mm.getOWLOntologyManager().getOntologyDocumentIRI(active);
            Map<String, Object> ontology = new LinkedHashMap<>();
            ontology.put("ontology_iri", iri(id.getOntologyIRI().orNull()));
            ontology.put("version_iri", iri(id.getVersionIRI().orNull()));
            ontology.put("document_iri", iri(document));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("revision", revisionJson(snapshot.revision()));
            result.put("workspace_id", snapshot.revision().workspaceId());
            result.put("session_revision", snapshot.revision().sessionRevision());
            result.put("semantic_fingerprint", snapshot.revision().semanticFingerprint());
            result.put("document_fingerprint", snapshot.revision().documentFingerprint());
            result.put("ontology", ontology);
            boolean dirty = mm.getDirtyOntologies().contains(active);
            result.put("dirty", dirty);
            result.put("dirty_semantics", "Protégé saved-state flag: true means the ontology was "
                    + "edited since its last load/save. Protégé does not clear this flag when Undo "
                    + "restores identical content; compare semantic_fingerprint for content identity.");
            result.put("reasoner", ProjectPolicyTools.selectedReasoner(mm));
            result.put("fingerprint_stability", snapshot.fingerprint().stability());
            result.put("release_stable", snapshot.fingerprint().releaseStable());
            result.put("fingerprint_warnings", snapshot.fingerprint().warnings());
            result.put("policy_loaded", policy.policy.loaded());
            if (policy.policy.path() != null) {
                result.put("policy_path", policy.policy.path().toString());
            }
            if (policy.policy.digest() != null) {
                result.put("policy_digest", policy.policy.digest());
            }
            result.put("policy_valid", policy.policy.valid());
            if (policy.error != null) {
                result.put("policy_error", policy.error);
            }
            if (importLockDigest != null) {
                result.put("import_lock_digest", importLockDigest);
            }
            return Tools.ok(result);
        });
    }

    static Map<String, Object> revisionJson(ModelRevision revision) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("workspace_id", revision.workspaceId());
        json.put("session_revision", revision.sessionRevision());
        json.put("semantic_fingerprint", revision.semanticFingerprint());
        json.put("document_fingerprint", revision.documentFingerprint());
        return json;
    }

    static PolicyState resolvePolicy(ToolContext ctx, String configured) {
        Path explicit = null;
        String error = null;
        if (configured != null) {
            try {
                explicit = Path.of(configured);
            } catch (InvalidPathException e) {
                error = "Invalid policy_path '" + configured + "': " + e.getMessage();
            }
        }
        ProjectPolicyTools.PolicyContext live = ctx.access().compute(ProjectPolicyTools::capture);
        ProjectPolicy policy = error == null
                ? ProjectPolicyLoader.load(explicit, live.documentPath(), live.activeOntologyIri(),
                        live.installedReasoners())
                : ProjectPolicy.notFound();
        return new PolicyState(policy, live, error);
    }

    static String digestImportLock(ProjectPolicy policy) {
        List<Path> paths = policy.assets().getOrDefault("import_lock", Collections.emptyList());
        if (paths.size() != 1 || !Files.isRegularFile(paths.get(0))) {
            return null;
        }
        try {
            return sha256File(paths.get(0)); // bounded streaming hash — no unbounded readAllBytes
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Upper bound on entries visited while hashing directory assets into ONE preflight digest
     * (summed across every asset directory). The walk can run on the Protégé model thread inside
     * the commit hop — the policy decision must be made there, and a hop body that has started
     * cannot be cancelled by the hop's wait bound — so a runaway traversal of a huge tree must fail
     * closed DURING the walk. Must stay in step with the policy loader's (package-private)
     * MAX_ASSET_FILES asset-scan limit.
     */
    static final int MAX_PREFLIGHT_ASSET_ENTRIES = 10_000;

    /** Digest the effective policy plus every resolved validation/import asset used by preflight. */
    static String preflightDigest(ProjectPolicy policy) {
        return preflightDigest(policy, MAX_PREFLIGHT_ASSET_ENTRIES);
    }

    /** Seam for tests: {@link #preflightDigest(ProjectPolicy)} with an explicit directory-walk cap. */
    static String preflightDigest(ProjectPolicy policy, int maxAssetEntries) {
        if (policy == null || !policy.loaded()) {
            return null;
        }
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        updateDigest(digest, "policy_digest", policy.digest());
        updateDigest(digest, "policy_path", policy.path() == null ? null : policy.path().toString());
        updateDigest(digest, "project_root",
                policy.projectRoot() == null ? null : policy.projectRoot().toString());
        List<Path> files = new ArrayList<>();
        int[] visited = new int[1];
        policy.assets().forEach((key, paths) -> {
            // release_output is an OUTPUT location (release.output_dir), not a QC input. It may not exist
            // yet and its contents are release artifacts, so hashing it would break or destabilize the
            // preflight digest. Digest only the assets the QC/preflight actually reads.
            if ("release_output".equals(key)) {
                return;
            }
            for (Path path : paths) {
                if (Files.isDirectory(path)) {
                    // Confinement: hash only regular files whose real path stays under the asset
                    // directory's real path, so a child symlink cannot pull an external file into the
                    // digest (or, worse, be followed and read). Matches the loader's confinement intent.
                    Path base;
                    try {
                        base = path.toRealPath();
                    } catch (IOException e) {
                        throw new ToolArgException("Could not resolve policy asset directory '" + path
                                + "': " + e.getMessage());
                    }
                    try (var stream = Files.walk(path)) {
                        stream.forEach(candidate -> {
                            // The cap binds DURING the walk: Files.walk is lazy, so throwing here
                            // stops the traversal before an unbounded tree is crawled (or hashed).
                            if (++visited[0] > maxAssetEntries) {
                                throw new ToolArgException("Policy asset scan exceeded "
                                        + maxAssetEntries + " directory entries under '" + path
                                        + "'. Point the policy asset at a smaller directory (or "
                                        + "split it) so preflight can hash it in bounded time.");
                            }
                            if (!Files.isRegularFile(candidate)) {
                                return;
                            }
                            try {
                                if (candidate.toRealPath().startsWith(base)) {
                                    files.add(candidate);
                                }
                            } catch (IOException ignored) {
                                // Unreadable/vanished entry: skip it rather than fold an unstable path.
                            }
                        });
                    } catch (IOException e) {
                        throw new ToolArgException("Could not hash policy asset directory '" + path
                                + "': " + e.getMessage());
                    }
                } else {
                    files.add(path);
                }
            }
        });
        files.stream().distinct().sorted(Comparator.comparing(Path::toString)).forEach(path -> {
            updateDigest(digest, "asset_path", path.toString());
            try {
                // Stream the hash with a per-file size cap: an oversized (or hostile) asset must not be
                // slurped whole into the Protégé JVM heap.
                updateDigest(digest, "asset_sha256", sha256File(path));
            } catch (IOException e) {
                throw new ToolArgException("Could not hash policy asset '" + path + "': "
                        + e.getMessage());
            }
        });
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return "sha256:" + hex;
    }

    private static void updateDigest(java.security.MessageDigest digest, String key, String value) {
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] valueBytes = (value == null ? "" : value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(8).putInt(keyBytes.length)
                .putInt(valueBytes.length).array());
        digest.update(keyBytes);
        digest.update(valueBytes);
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return "sha256:" + hex;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Cap for reads that buffer the whole file into heap (JSON lock, config). NOT applied to the
     * streaming hash, which is fixed-memory and must accept legitimately large ontology documents. */
    static final long MAX_BUFFERED_BYTES = 64L * 1024 * 1024;

    /**
     * Stream-hash a file in fixed memory (an 8 KiB buffer), rejecting anything that is not a regular
     * file so a FIFO cannot cause an unbounded/blocking read. There is deliberately NO byte cap:
     * streaming already bounds heap regardless of file size, and import ontology documents can be very
     * large (a size limit here would wrongly fail lock/preflight on a big-but-valid ontology).
     */
    static String sha256File(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("not a regular file: " + path);
        }
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (java.io.InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return "sha256:" + hex;
    }

    private static String iri(IRI value) {
        return value == null ? null : value.toString();
    }

    static record PolicyState(ProjectPolicy policy, ProjectPolicyTools.PolicyContext live, String error) { }
}
