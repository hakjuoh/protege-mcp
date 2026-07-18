package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code lock_mode} on the DOCUMENT-LOADING operations (ADR 0005 decision 4): the to-be-loaded
 * closure's coordinates and SHA-256 hashes are verified BEFORE the workspace is mutated, resolving
 * the lockfile against the loaded document (beside-document default; the policy-declared lockfile
 * when the loaded document is the project's resolved root artifact). A mismatch — or
 * {@code required} with no lockfile — refuses the load with a structured error and mutates
 * nothing; verification reports through the tool's structured result, not QC findings.
 */
class OntologyDocumentToolsLockModeTest {

    private static final String ROOT_IRI = "https://example.org/lockmode/root";
    private static final String IMPORTED_IRI = "https://example.org/lockmode/imported";
    private static final DirectAccessPolicy.NetworkRule OPEN_RULE =
            new DirectAccessPolicy.NetworkRule(true, Set.of(), true, true);

    private Preferences prefs;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void pinWritePrefs() {
        prefs = McpConfig.prefs();
        savedReadOnly = String.valueOf(prefs.getBoolean(McpConfig.KEY_READ_ONLY, false));
        savedConfirm = String.valueOf(prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restoreWritePrefs() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, Boolean.parseBoolean(savedReadOnly));
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, Boolean.parseBoolean(savedConfirm));
    }

    @Test
    void loadVerifiesTheBesideDocumentLockAndLabelsItsTrust(@TempDir Path temp) throws Exception {
        writeClosureAndLock(temp);
        OWLModelManager mm = workspace(temp);
        ToolContext ctx = context(mm);

        Map<String, Object> loaded = structured(OntologyDocumentTools.doLoad(ctx,
                temp.resolve("root.ttl").toString(), 1_000, false, MissingImportsMode.ERROR,
                OPEN_RULE, LockMode.VERIFY, noPolicyReadRules()));
        Map<?, ?> verification = (Map<?, ?>) loaded.get("import_lock_verification");
        assertEquals(true, verification.get("valid"), verification::toString);
        assertEquals(ImportLockTools.SOURCE_BESIDE_DOCUMENT, verification.get("lockfile_source"));
        assertTrue(String.valueOf(verification.get("lockfile_note")).contains("accident-safety"),
                verification::toString);
        assertFalse(verification.containsKey("loaded_content_verified"),
                "the loaded-content attestation is gate-only and must not appear on a load");
        assertTrue(mm.getOntologies().stream().anyMatch(o -> o.getOntologyID().getOntologyIRI()
                .transform(IRI::toString).or("").equals(ROOT_IRI)), "the verified load attaches");
    }

    @Test
    void aSymlinkedBesideDocumentLockEscapingTheProjectRefusesTheLoadUnread(@TempDir Path temp)
            throws Exception {
        // Finding-1 regression pin on the LOAD surface: under a confining policy
        // (allow_external_paths=false) a symlinked imports.lock.json escaping project_root must be
        // refused through canonical containment BEFORE any existence probe or read — never
        // hash-compared, never echoed into the caller-visible error, and nothing attached.
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        writeClosureAndLock(project);
        Files.delete(project.resolve("imports.lock.json"));
        Files.writeString(outside.resolve("imports.lock.json"),
                "{\"version\":1,\"imports\":[]}\n");
        try {
            Files.createSymbolicLink(project.resolve("imports.lock.json"),
                    outside.resolve("imports.lock.json"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e.getMessage());
        }
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("lock-mode-load-symlink", ROOT_IRI)
                        + "filesystem:\n  allow_external_paths: false\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        OWLModelManager mm = workspace(temp);
        ToolContext ctx = context(mm);
        Set<OWLOntology> before = Set.copyOf(mm.getOntologies());

        ToolArgException refused = assertThrows(ToolArgException.class,
                () -> OntologyDocumentTools.doLoad(ctx, project.resolve("root.ttl").toString(),
                        1_000, false, MissingImportsMode.ERROR, OPEN_RULE, LockMode.VERIFY,
                        readRules(policy)));
        assertTrue(refused.getMessage().contains("outside project_root"), refused.getMessage());
        assertFalse(refused.getMessage().contains("mismatched_entries"),
                "the escaping lock must be refused unread, never compared: " + refused.getMessage());
        assertEquals(before, Set.copyOf(mm.getOntologies()),
                "a refused load must leave the workspace untouched");
    }

    @Test
    void loadMismatchRefusesBeforeAnyWorkspaceMutation(@TempDir Path temp) throws Exception {
        writeClosureAndLock(temp);
        Files.writeString(temp.resolve("imported.ttl"),
                Files.readString(temp.resolve("imported.ttl")) + "# tampered\n");
        OWLModelManager mm = workspace(temp);
        ToolContext ctx = context(mm);
        Set<OWLOntology> before = Set.copyOf(mm.getOntologies());

        ToolArgException refused = assertThrows(ToolArgException.class,
                () -> OntologyDocumentTools.doLoad(ctx, temp.resolve("root.ttl").toString(), 1_000,
                        false, MissingImportsMode.ERROR, OPEN_RULE, LockMode.VERIFY,
                        noPolicyReadRules()));
        assertTrue(refused.getMessage().contains("refused the load"), refused.getMessage());
        assertTrue(refused.getMessage().contains("mismatched_entries"), refused.getMessage());
        assertEquals(before, Set.copyOf(mm.getOntologies()),
                "a refused load must leave the workspace untouched");
    }

    @Test
    void requiredRefusesWhenNoLockfileExistsWhileVerifySkipsCleanly(@TempDir Path temp)
            throws Exception {
        writeClosureAndLock(temp);
        Files.delete(temp.resolve("imports.lock.json"));
        OWLModelManager mm = workspace(temp);
        ToolContext ctx = context(mm);
        Set<OWLOntology> before = Set.copyOf(mm.getOntologies());

        ToolArgException refused = assertThrows(ToolArgException.class,
                () -> OntologyDocumentTools.doLoad(ctx, temp.resolve("root.ttl").toString(), 1_000,
                        false, MissingImportsMode.ERROR, OPEN_RULE, LockMode.REQUIRED,
                        noPolicyReadRules()));
        assertTrue(refused.getMessage().contains("lock_mode=required"), refused.getMessage());
        assertEquals(before, Set.copyOf(mm.getOntologies()));

        Map<String, Object> skipped = structured(OntologyDocumentTools.doLoad(ctx,
                temp.resolve("root.ttl").toString(), 1_000, false, MissingImportsMode.ERROR,
                OPEN_RULE, LockMode.VERIFY, noPolicyReadRules()));
        Map<?, ?> verification = (Map<?, ?>) skipped.get("import_lock_verification");
        assertEquals(true, verification.get("skipped"), verification::toString);
        assertEquals(ImportLockTools.SOURCE_BESIDE_DOCUMENT, verification.get("lockfile_source"));
    }

    @Test
    void theProjectRootArtifactVerifiesAgainstThePolicyDeclaredLockfile(@TempDir Path temp)
            throws Exception {
        writeClosureAndLock(temp);
        // Declared lockfile at a NON-default name: only policy resolution can find it.
        Files.move(temp.resolve("imports.lock.json"), temp.resolve("declared.lock.json"));
        Path policyPath = temp.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath, "version: 1\n"
                + "project_id: lock-mode-load\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + ProjectPolicyFixtures.interoperabilityYaml("root.ttl", "ro-crate-1.1")
                + "imports:\n  lockfile: declared.lock.json\n"
                + "validation:\n  required_stages: [structural]\n");
        // The fixture materializer rewrites root.ttl; restore the import-bearing document (the
        // RO-Crate content itself is irrelevant to a loading operation).
        writeRoot(temp);
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());

        OWLModelManager mm = workspace(temp);
        ToolContext ctx = context(mm);
        Map<String, Object> loaded = structured(OntologyDocumentTools.doLoad(ctx,
                temp.resolve("root.ttl").toString(), 1_000, false, MissingImportsMode.ERROR,
                OPEN_RULE, LockMode.VERIFY, readRules(policy)));
        Map<?, ?> verification = (Map<?, ?>) loaded.get("import_lock_verification");
        assertEquals(true, verification.get("valid"), verification::toString);
        assertEquals(ImportLockTools.SOURCE_POLICY_DECLARED, verification.get("lockfile_source"),
                "the project's root artifact must verify against the policy-declared lockfile");
        assertFalse(verification.containsKey("lockfile_note"),
                "a policy-pinned lockfile needs no accident-safety caveat");

        // A NON-root-artifact document in the same project keeps the beside-document default —
        // here no imports.lock.json exists, so verify skips instead of borrowing the policy lock.
        Files.copy(temp.resolve("root.ttl"), temp.resolve("other.ttl"));
        String other = Files.readString(temp.resolve("other.ttl"))
                .replace(ROOT_IRI, ROOT_IRI + "-other");
        Files.writeString(temp.resolve("other.ttl"), other);
        Map<String, Object> besides = structured(OntologyDocumentTools.doLoad(ctx,
                temp.resolve("other.ttl").toString(), 1_000, true, MissingImportsMode.ERROR,
                OPEN_RULE, LockMode.VERIFY, readRules(policy)));
        Map<?, ?> otherVerification = (Map<?, ?>) besides.get("import_lock_verification");
        assertEquals(true, otherVerification.get("skipped"), otherVerification::toString);
        assertEquals(ImportLockTools.SOURCE_BESIDE_DOCUMENT,
                otherVerification.get("lockfile_source"));
    }

    @Test
    void mergeVerifiesBeforeApplyAndRefusalMutatesNothing(@TempDir Path temp) throws Exception {
        writeClosureAndLock(temp);
        OWLModelManager mm = workspace(temp);
        ToolContext ctx = context(mm);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("source", temp.resolve("root.ttl").toString());
        args.put("lock_mode", "verify");
        Map<String, Object> merged = structured(call(ctx, "merge_ontology_document", args));
        Map<?, ?> verification = (Map<?, ?>) merged.get("import_lock_verification");
        assertEquals(true, verification.get("valid"), verification::toString);
        assertEquals(ImportLockTools.SOURCE_BESIDE_DOCUMENT, verification.get("lockfile_source"));
        assertFalse(verification.containsKey("loaded_content_verified"),
                "the loaded-content attestation is gate-only and must not appear on a merge");

        Files.writeString(temp.resolve("imported.ttl"),
                Files.readString(temp.resolve("imported.ttl")) + "# tampered\n");
        OWLOntology active = mm.getActiveOntology();
        int axiomsBefore = active.getAxiomCount();
        int importsBefore = active.getImportsDeclarations().size();
        CallToolResult refused = call(ctx, "merge_ontology_document", args);
        assertEquals(Boolean.TRUE, refused.isError(), () -> String.valueOf(refused.content()));
        assertTrue(String.valueOf(refused.content()).contains("refused the load"),
                () -> String.valueOf(refused.content()));
        assertEquals(axiomsBefore, active.getAxiomCount(),
                "a refused merge must not change the active ontology");
        assertEquals(importsBefore, active.getImportsDeclarations().size());
    }

    @Test
    void loadHandlerParsesLockModeAndNetworkStrictly(@TempDir Path temp) throws Exception {
        writeClosureAndLock(temp);
        ToolContext ctx = context(workspace(temp));

        Map<String, Object> badLock = new LinkedHashMap<>();
        badLock.put("source", temp.resolve("root.ttl").toString());
        badLock.put("lock_mode", "maybe");
        CallToolResult lockRejected = call(ctx, "load_ontology", badLock);
        assertEquals(Boolean.TRUE, lockRejected.isError());
        assertTrue(String.valueOf(lockRejected.content()).contains("Invalid lock_mode"),
                () -> String.valueOf(lockRejected.content()));

        Map<String, Object> badNetwork = new LinkedHashMap<>();
        badNetwork.put("source", temp.resolve("root.ttl").toString());
        badNetwork.put("network", "offline");
        CallToolResult networkRejected = call(ctx, "load_ontology", badNetwork);
        assertEquals(Boolean.TRUE, networkRejected.isError());
        assertTrue(String.valueOf(networkRejected.content()).contains("Invalid network"),
                () -> String.valueOf(networkRejected.content()));

        // network=allow + lock_mode=verify end-to-end through the registered handler: a local
        // offline load is unaffected by the allow abstention and reports the verification.
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("source", temp.resolve("root.ttl").toString());
        ok.put("network", "allow");
        ok.put("lock_mode", "verify");
        ok.put("missing_imports", "error");
        Map<String, Object> loaded = structured(call(ctx, "load_ontology", ok));
        assertEquals(true,
                ((Map<?, ?>) loaded.get("import_lock_verification")).get("valid"));
    }

    // ------------------------------------------------------------------ fixtures

    private static void writeRoot(Path temp) throws Exception {
        Files.writeString(temp.resolve("root.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <%s> a owl:Ontology ; owl:imports <%s> .
                """.formatted(ROOT_IRI, IMPORTED_IRI));
    }

    /** root.ttl importing imported.ttl (via sibling catalog) plus a matching imports.lock.json. */
    private static void writeClosureAndLock(Path temp) throws Exception {
        Path importedPath = temp.resolve("imported.ttl");
        Files.writeString(importedPath, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        writeRoot(temp);
        Files.writeString(temp.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <uri name="%s" uri="imported.ttl"/>
                </catalog>
                """.formatted(IMPORTED_IRI));
        String sha = RevisionTools.sha256File(importedPath).substring("sha256:".length());
        Files.writeString(temp.resolve("imports.lock.json"), "{\"version\":1,\"imports\":[{"
                + "\"ontology_iri\":\"" + IMPORTED_IRI + "\","
                + "\"version_iri\":null,\"document\":\"imported.ttl\","
                + "\"sha256\":\"" + sha + "\",\"direct\":true}]}\n");
    }

    private static OWLModelManager workspace(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("https://example.org/workspace"));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active,
                IRI.create(temp.resolve("workspace.ttl").toUri()));
        OWLModelManager base = FakeModelManager.over(active);
        java.util.concurrent.atomic.AtomicReference<OWLOntology> activeRef =
                new java.util.concurrent.atomic.AtomicReference<>(active);
        // The shared fake fails loudly on the workspace-graph accessors attach() needs; expose the
        // backing manager's graph (the OntologyDocumentAttachTest idiom) and delegate the rest.
        return (OWLModelManager) java.lang.reflect.Proxy.newProxyInstance(
                OntologyDocumentToolsLockModeTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getOntologies" -> manager.getOntologies();
                    case "getActiveOntology" -> activeRef.get();
                    case "getActiveOntologies" -> activeRef.get().getImportsClosure();
                    case "setActiveOntology" -> {
                        activeRef.set((OWLOntology) args[0]);
                        yield null;
                    }
                    case "fireEvent" -> null;
                    default -> {
                        try {
                            yield method.invoke(base, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    private static ToolContext context(OWLModelManager mm) {
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    /** No-policy request rules for path authorization (project read only, compat mode on). */
    private static DirectAccessPolicy.Rules noPolicyReadRules() {
        return readRules(ProjectPolicy.notFound());
    }

    /** Request-scoped rules over {@code policy}, as the load/merge handlers build them. */
    private static DirectAccessPolicy.Rules readRules(ProjectPolicy policy) {
        return new DirectAccessPolicy.Rules(policy,
                new io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal(1, "test",
                        "test-client", "Test",
                        Set.of(DirectAccessPolicy.PROJECT_READ), null));
    }

    /** Invoke a registered document tool exactly as the server would (null-exchange test seam). */
    private static CallToolResult call(ToolContext ctx, String tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        OntologyDocumentTools.register(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals(tool)) {
                return spec.callHandler().apply(null, new CallToolRequest(tool, args));
            }
        }
        throw new AssertionError("no tool named " + tool);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
