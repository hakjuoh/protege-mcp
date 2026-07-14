package io.github.hakjuoh.protege_mcp.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;

/** Minimal Java 17 headless surface proving the core has no Protégé runtime dependency. */
public final class Main {

    public static final String VERSION = "0.6.0";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 1 && ("--version".equals(args[0]) || "version".equals(args[0]))) {
                out.println("protege-mcp-cli " + VERSION);
                return 0;
            }
            if (args.length == 3 && "validate-policy".equals(args[0])
                    && "--project".equals(args[1])) {
                return validatePolicy(Path.of(args[2]), out);
            }
            if (args.length == 5 && "diff".equals(args[0])
                    && "--left".equals(args[1]) && "--right".equals(args[3])) {
                return diff(Path.of(args[2]), Path.of(args[4]), out);
            }
            err.println("Usage: protege-mcp-cli --version | validate-policy --project FILE | "
                    + "diff --left ONTOLOGY --right ONTOLOGY");
            return 2;
        } catch (IllegalArgumentException e) {
            err.println("configuration error: " + message(e));
            return 2;
        } catch (Exception e) {
            err.println("execution error: " + message(e));
            return 3;
        }
    }

    private static int validatePolicy(Path path, PrintStream out) throws Exception {
        // Headless policy syntax/asset validation has no installed Protégé reasoner registry.
        // Runtime reasoner availability is checked by the adapter that executes project QC.
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy_loaded", policy.loaded());
        result.put("valid", policy.valid());
        result.put("policy_path", policy.path() == null ? null : policy.path().toString());
        result.put("project_root", policy.projectRoot() == null ? null : policy.projectRoot().toString());
        result.put("policy_digest", policy.digest());
        result.put("errors", policy.issues().stream().filter(issue -> "error".equals(issue.severity()))
                .map(issue -> issue.toJson()).toList());
        result.put("warnings", policy.issues().stream().filter(issue -> !"error".equals(issue.severity()))
                .map(issue -> issue.toJson()).toList());
        JSON.writeValue(out, result);
        out.println();
        return policy.valid() ? 0 : 2;
    }

    private static int diff(Path left, Path right, PrintStream out) throws Exception {
        // The comparison is asserted-only (imports are never diffed), so fetching owl:imports would
        // perform needless and surprising I/O. Load each root with a mapper that satisfies every import
        // from a private empty placeholder, then report every declared import so the deliberately
        // omitted closure stays visible.
        List<String> leftUnresolved = new ArrayList<>();
        List<String> rightUnresolved = new ArrayList<>();
        OWLOntology leftOntology = loadDocument(left, leftUnresolved);
        OWLOntology rightOntology = loadDocument(right, rightUnresolved);
        Map<String, Object> result = SemanticDiffService.diff(leftOntology, rightOntology, false, 100,
                rightUnresolved);
        result.put("left_unresolved_imports", List.copyOf(leftUnresolved));
        result.put("right_unresolved_imports", List.copyOf(rightUnresolved));
        JSON.writeValue(out, result);
        out.println();
        return 0;
    }

    private static OWLOntology loadDocument(Path path, List<String> unresolvedOut) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // Map every import IRI to ONE private empty placeholder document, so an untrusted checkout
        // cannot make this asserted-only CLI contact localhost, cloud metadata, the public network,
        // or any local file it names — and cannot amplify temp usage either: however many imports a
        // hostile document declares, the cost is exactly this placeholder file. Each import must
        // genuinely RESOLVE rather than merely fail under SILENT handling (OWLAPI's Manchester frame
        // parser dereferences each imported ontology without a null guard), and the placeholder must
        // stay ANONYMOUS (Turtle/RDF-XML set a root's own ontology IRI only at the END of the
        // streaming parse — a placeholder claiming an import IRI would collide with a self-importing
        // root's trailing SetOntologyID). The first import loads the placeholder; the manager
        // resolves every further import IRI to that already-loaded ontology by document IRI
        // (loadImports requests allowExists=true). The primary document is a FileDocumentSource and
        // does not consult this mapper.
        Path placeholder = Files.createTempFile("protege-mcp-cli-blocked-imports-", ".ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            IRI placeholderIri = IRI.create(placeholder.toUri());
            manager.getIRIMappers().add(importIri -> placeholderIri);
            OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setFollowRedirects(false);
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                    new FileDocumentSource(path.toFile()), config);
            // Every declared import was deliberately satisfied WITHOUT fetching its axioms; report
            // them all, sorted, so the omitted closure stays visible in the output.
            ontology.getImportsDeclarations().stream()
                    .map(declaration -> declaration.getIRI().toString())
                    .sorted()
                    .forEach(unresolvedOut::add);
            return ontology;
        } finally {
            try {
                Files.deleteIfExists(placeholder);
            } catch (IOException bestEffortTempCleanup) {
                // Cleanup of the private placeholder must neither fail a successful diff nor mask
                // the real load error.
            }
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
