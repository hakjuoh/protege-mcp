package io.github.hakjuoh.protege_mcp.tools;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Network-policy enforcement and unsupported-scheme fallback for isolated ontology loads. */
final class OntologyNetworkSupport {

    private OntologyNetworkSupport() {}

    /**
     * Lowest-priority import mapper that replaces only otherwise-unmapped forbidden remote imports
     * with one private empty document, records them, and then fails the whole policy-aware load
     * with an explicit error. Workspace and sibling-catalog mappers are added later and therefore
     * win, so an HTTP ontology IRI mapped to a local locked file remains an offline load.
     */
    static final class NetworkImportBlocker implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final DirectAccessPolicy.NetworkRule rule;
        private final Path placeholder;
        private final IRI placeholderIri;
        private final Map<String, String> blocked = new LinkedHashMap<>();
        private String catalogRefusal;
        private final OWLOntologyIRIMapper mapper;

        private NetworkImportBlocker(
                OWLOntologyManager manager, DirectAccessPolicy.NetworkRule rule, Path placeholder) {
            this.manager = manager;
            this.rule = rule;
            this.placeholder = placeholder;
            this.placeholderIri = IRI.create(placeholder.toUri());
            this.mapper =
                    logical -> {
                        URI uri;
                        try {
                            uri = logical.toURI();
                        } catch (RuntimeException invalid) {
                            return null;
                        }
                        String scheme = uri.getScheme();
                        if ("file".equalsIgnoreCase(scheme)) {
                            try {
                                return IRI.create(rule.authorizeFileImport(uri).toUri());
                            } catch (IllegalArgumentException | ToolArgException denied) {
                                blocked.putIfAbsent(
                                        logical.toString(),
                                        "the local import path is outside the authorized project"
                                            + " filesystem ("
                                                + denied.getMessage()
                                                + ")");
                                return placeholderIri;
                            }
                        }
                        if (scheme == null
                                || !("http".equalsIgnoreCase(scheme)
                                        || "https".equalsIgnoreCase(scheme)
                                        || "ftp".equalsIgnoreCase(scheme)
                                        || "jar".equalsIgnoreCase(scheme))) {
                            return null;
                        }
                        if (rule.permits(uri)) {
                            return null;
                        }
                        blocked.putIfAbsent(logical.toString(), null);
                        return placeholderIri;
                    };
            manager.getIRIMappers().add(mapper);
        }

        static NetworkImportBlocker install(
                OWLOntologyManager manager, DirectAccessPolicy.NetworkRule rule) {
            try {
                Path placeholder = Files.createTempFile("protege-mcp-network-denied-", ".ofn");
                Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
                return new NetworkImportBlocker(manager, rule, placeholder);
            } catch (IOException e) {
                throw new ToolArgException(
                        "Could not prepare policy-aware import loading: "
                                + (e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage()));
            }
        }

        void failIfBlocked(String source, Collection<String> unresolvedImports) {
            if (!blocked.isEmpty()) {
                Map.Entry<String, String> firstEntry = blocked.entrySet().iterator().next();
                String first = firstEntry.getKey();
                String reason =
                        first.regionMatches(true, 0, "jar:", 0, 4)
                                ? "nested jar: import sources are refused because they obscure the"
                                      + " filesystem/host boundary"
                                : firstEntry.getValue() != null
                                        ? firstEntry.getValue()
                                        : denialAttribution();
                boolean requestDenied =
                        DirectAccessPolicy.DenialSource.REQUEST == rule.denialSource();
                throw new ToolArgException(
                        "Could not load ontology document '"
                                + source
                                + "': remote import '"
                                + first
                                + "' was required, but "
                                + reason
                                + ". "
                                + (requestDenied
                                        // A request-caused refusal must not tell the caller to loosen
                                        // reviewed policy; the caller's own argument is the denial.
                                        ? "Provide a local workspace/catalog mapping or drop the"
                                              + " request's network=deny argument."
                                        : "Provide a local workspace/catalog mapping or change the"
                                              + " reviewed policy."));
            }
            if (catalogRefusal == null) {
                return;
            }
            if (!unresolvedImports.isEmpty()) {
                String first = unresolvedImports.iterator().next();
                throw new ToolArgException(
                        "Could not load ontology document '"
                                + source
                                + "': required import '"
                                + first
                                + "' remained unresolved because "
                                + catalogRefusal
                                + ".");
            }
            // When installed, the folder catalog is the highest-priority mapper: the project source
            // of truth overriding workspace hints and direct dereference. So an import this load
            // resolved through ANY other channel (a workspace mapping, a direct file read, a
            // permitted network fetch) may have been pinned to different reviewed content by the
            // refused catalog. Succeeding would silently substitute that channel for the catalog;
            // only a load that resolved no import at all can safely ignore the refusal.
            String resolvedWithoutCatalog = firstResolvedImport();
            if (resolvedWithoutCatalog != null) {
                throw new ToolArgException(
                        "Could not load ontology document '"
                                + source
                                + "': import '"
                                + resolvedWithoutCatalog
                                + "' had to resolve without the folder catalog, which could pin it"
                                + " to different reviewed content, because "
                                + catalogRefusal
                                + ".");
            }
        }

        /**
         * The attribution string for this rule's own denial source (ADR 0005 decision 3). Every
         * released string is byte-identical for its released source; the invalid-policy and
         * restricted-no-policy cases previously misreported a missing capability / a phantom {@code
         * imports.network=deny} and now name their actual cause, and a request-level deny reports
         * {@code request network=deny}.
         */
        private String denialAttribution() {
            switch (rule.denialSource()) {
                case REQUEST:
                    return "request network=deny";
                case CAPABILITY:
                    return "the authenticated principal lacks network:access";
                case INVALID_POLICY:
                    return "the effective project policy is invalid, so network access fails closed"
                               + " until it validates";
                case COMPATIBILITY_PREFERENCE:
                    return "no project policy is loaded and remote fetches require both the"
                               + " local-admin profile and the 'Allow unrestricted local-admin"
                               + " paths when no project policy is loaded' compatibility preference"
                               + " (Protégé ▸ Preferences ▸ MCP)";
                case POLICY:
                    return "imports.network=deny";
                default:
                    return "the host is outside network.allowed_hosts";
            }
        }

        /**
         * The lexicographically first import IRI that actually resolved during this load, or null
         * when the closure needed no import resolution. Placeholder ontologies are removed before
         * {@link #failIfBlocked} runs, so a fallback-satisfied IRI never counts as resolved here.
         */
        private String firstResolvedImport() {
            String first = null;
            for (OWLOntology ontology : manager.getOntologies()) {
                for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
                    OWLOntology target = manager.getImportedOntology(declaration);
                    if (target == null || target.equals(ontology)) {
                        // Unresolved (reported separately), or a vacuous self-import: OWLAPI links
                        // an already-registered ontology ID before consulting any IRI mapper, so
                        // the refused catalog could not have redirected this edge, and the loop
                        // adds no content beyond the document the caller explicitly targeted.
                        continue;
                    }
                    String iri = declaration.getIRI().toString();
                    if (first == null || iri.compareTo(first) < 0) {
                        first = iri;
                    }
                }
            }
            return first;
        }

        /** Record a blocked resolution so {@link #failIfBlocked} can explain a failed load. */
        void recordBlocked(String logical, String reason) {
            blocked.putIfAbsent(logical, reason);
        }

        /**
         * Remember that the sibling catalog could not be installed safely. Its mere presence is not
         * an error: the load fails only when the closure needed import resolution at all — an
         * import left unresolved may have needed the catalog, and an import resolved through
         * another channel may have been pinned differently by it. A document that resolves no
         * import loads normally.
         */
        void recordCatalogRefusal(String reason) {
            if (catalogRefusal == null) {
                catalogRefusal = reason;
            }
        }

        /** The composed rule's denial source, for posture-attributing catalog refusals. */
        DirectAccessPolicy.DenialSource denialSource() {
            return rule.denialSource();
        }

        /**
         * Whether the network rule RESTRICTS remote access — i.e. it is not fully open to any host.
         * Only then does the strict folder-catalog gate apply: when the rule already permits
         * arbitrary remote fetches (network=allow with no host allowlist, e.g. the default
         * no-policy posture), the resolver's own delegation/DTD fetches are authorized anyway, so
         * refusing catalog delegation would break legitimate offline/less-restricted loads for no
         * security benefit.
         */
        boolean networkRestricted() {
            return !(rule.allowed() && rule.capabilityAllowed() && rule.allowedHosts().isEmpty());
        }

        /**
         * Whether the policy confines the filesystem (out-of-project reads refused). The
         * folder-catalog gate must also run in this posture even when the network is open: the
         * resolver's own catalog reads (a DOCTYPE's external entity, or an out-of-project
         * delegation target) would otherwise read files outside the project before any mapping is
         * authorized.
         */
        boolean filesystemConfined() {
            DirectAccessPolicy.Rules fs = rule.filesystemRules();
            return fs != null && fs.confinesFilesystem();
        }

        /**
         * Authorize READING a local catalog file against the filesystem policy — project
         * containment, symlink-resolved via canonicalCandidate — so a catalog-v001.xml (or a local
         * delegation catalog it points at) that is a symlink to, or a {@code ../} escape out of,
         * the project is refused instead of read. Returns true when the policy permits reading it
         * (or there is no filesystem policy to enforce, the compatibility path).
         */
        boolean catalogFileAuthorized(Path file) {
            DirectAccessPolicy.Rules fs = rule.filesystemRules();
            if (fs == null) {
                return true;
            }
            try {
                fs.implicitPath(file, false);
                return true;
            } catch (RuntimeException denied) {
                return false;
            }
        }

        IRI authorizeMapping(IRI logical, IRI mapped) {
            IRI authorized = OntologyImportResolver.authorizedMappedDocument(mapped, rule);
            if (authorized != null) return authorized;
            String detail = null;
            try {
                URI uri = mapped == null ? null : mapped.toURI();
                if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                    detail = rule.fileImportDenial(uri);
                }
            } catch (RuntimeException ignored) {
                // The generic denial below remains sufficient and does not expose parser internals.
            }
            blocked.putIfAbsent(
                    logical.toString(),
                    "the workspace/catalog mapping target '"
                            + mapped
                            + "' is outside the authorized filesystem/network policy"
                            + (detail == null ? "" : " (" + detail + ")"));
            return placeholderIri;
        }

        @Override
        public void close() {
            manager.getIRIMappers().remove(mapper);
            for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
                if (placeholderIri.equals(manager.getOntologyDocumentIRI(ontology))) {
                    manager.removeOntology(ontology);
                }
            }
            try {
                Files.deleteIfExists(placeholder);
            } catch (IOException ignored) {
                // Private empty file; cleanup must not mask the policy result.
            }
        }
    }

    /**
     * OWLAPI 4 applies {@link MissingImportHandlingStrategy} to ordinary document failures, but an
     * import IRI with no URL/document handler (for example {@code urn:example:missing}) escapes
     * earlier as an unchecked {@code OWLOntologyFactoryNotFoundException}. Let higher-priority
     * workspace/catalog mappers resolve it first; if none does, this last-resort mapper temporarily
     * satisfies only unsupported schemes from one private empty document. After the root parse
     * completes the placeholder is removed and every such IRI is restored to the caller's
     * unresolved-import list, so warn/silent/error retain their documented semantics and no
     * placeholder can enter the Protégé workspace.
     */
    static final class UnsupportedImportFallback implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final Path placeholder;
        private final IRI placeholderIri;
        private final Set<String> unresolved = new LinkedHashSet<>();
        private final OWLOntologyIRIMapper mapper;

        private UnsupportedImportFallback(OWLOntologyManager manager, Path placeholder) {
            this.manager = manager;
            this.placeholder = placeholder;
            this.placeholderIri = IRI.create(placeholder.toUri());
            this.mapper =
                    logical -> {
                        URI uri;
                        try {
                            uri = logical.toURI();
                        } catch (RuntimeException invalid) {
                            unresolved.add(logical.toString());
                            return placeholderIri;
                        }
                        if (hasUrlHandler(uri)) {
                            return null;
                        }
                        unresolved.add(logical.toString());
                        return placeholderIri;
                    };
            // Added FIRST. OWLAPI's priority collection consults later workspace/catalog mappers
            // first,
            // so a legitimate local mapping for a URN wins and never reaches this fallback.
            manager.getIRIMappers().add(mapper);
        }

        private static boolean hasUrlHandler(URI uri) {
            try {
                // URI.toURL() resolves the protocol handler but does not open a connection. This
                // matches OWLAPI's supported http/https/file/ftp/jar boundary without network I/O.
                uri.toURL();
                return true;
            } catch (IOException | IllegalArgumentException unsupported) {
                return false;
            }
        }

        static UnsupportedImportFallback install(OWLOntologyManager manager) {
            try {
                Path placeholder = Files.createTempFile("protege-mcp-unsupported-import-", ".ofn");
                Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
                return new UnsupportedImportFallback(manager, placeholder);
            } catch (IOException e) {
                throw new ToolArgException(
                        "Could not prepare isolated missing-import handling: "
                                + (e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage()));
            }
        }

        List<String> unresolved() {
            return List.copyOf(unresolved);
        }

        void removePlaceholderOntology() {
            for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
                if (placeholderIri.equals(manager.getOntologyDocumentIRI(ontology))) {
                    manager.removeOntology(ontology);
                }
            }
        }

        @Override
        public void close() {
            manager.getIRIMappers().remove(mapper);
            removePlaceholderOntology();
            try {
                Files.deleteIfExists(placeholder);
            } catch (IOException ignored) {
                // Private empty file only; cleanup must not mask the actual ontology result.
            }
        }
    }

    static void finishUnsupportedImports(
            OWLOntology primary,
            UnsupportedImportFallback fallback,
            Collection<String> unresolvedImports,
            MissingImportsMode mode,
            String normalized) {
        fallback.removePlaceholderOntology();
        // Streaming parsers can encounter a URN before assigning the importing ontology's ID, for
        // any closure member. Judge the completed graph after removing the private placeholder so
        // a child self-import is not reported as both resolved and unresolved.
        Set<String> resolved = new LinkedHashSet<>();
        for (Map<String, Object> row : ImportTools.analyze(primary).resolvedImports) {
            Object iri = row.get("import_iri");
            if (iri != null) {
                resolved.add(String.valueOf(iri));
            }
        }
        unresolvedImports.removeIf(resolved::contains);
        List<String> unsupported =
                fallback.unresolved().stream().filter(iri -> !resolved.contains(iri)).toList();
        for (String iri : unsupported) {
            if (!unresolvedImports.contains(iri)) {
                unresolvedImports.add(iri);
            }
        }
        if (mode == MissingImportsMode.ERROR && !unsupported.isEmpty()) {
            throw new ToolArgException(
                    "Could not load ontology document '"
                            + normalized
                            + "': required import '"
                            + unsupported.get(0)
                            + "' could not be loaded: no OWL document factory can dereference that"
                            + " IRI.");
        }
    }
}
