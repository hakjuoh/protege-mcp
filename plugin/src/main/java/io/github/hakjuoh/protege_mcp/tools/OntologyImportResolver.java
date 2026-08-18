package io.github.hakjuoh.protege_mcp.tools;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.xmlcatalog.owlapi.XMLCatalogIRIMapper;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Resolves reusable workspace imports, document sources and sibling XML catalogs. */
final class OntologyImportResolver {

    private OntologyImportResolver() {}

    static List<OntologyImportMapping> workspaceImportMappings(OWLModelManager mm) {
        OWLOntologyManager manager = mm.getOWLOntologyManager();
        Map<String, String> mappings = new TreeMap<>();
        List<OWLOntology> ontologies = new ArrayList<>(manager.getOntologies());
        ontologies.sort(
                (left, right) ->
                        ontologyLabel(left.getOntologyID())
                                .compareTo(ontologyLabel(right.getOntologyID())));

        // Preserve the manager's actual declaration resolution first. This also covers catalogs
        // where an owl:imports document IRI differs from the loaded ontology's logical IRI.
        for (OWLOntology source : ontologies) {
            List<OWLImportsDeclaration> declarations =
                    new ArrayList<>(source.getImportsDeclarations());
            declarations.sort(
                    (left, right) -> left.getIRI().toString().compareTo(right.getIRI().toString()));
            for (OWLImportsDeclaration declaration : declarations) {
                OWLOntology target = manager.getImportedOntology(declaration);
                if (target != null) {
                    putReusableMapping(
                            mappings, declaration.getIRI(), manager.getOntologyDocumentIRI(target));
                }
            }
        }
        for (OWLOntology ontology : ontologies) {
            IRI document = manager.getOntologyDocumentIRI(ontology);
            OWLOntologyID id = ontology.getOntologyID();
            if (id.getOntologyIRI().isPresent()) {
                putReusableMapping(mappings, id.getOntologyIRI().get(), document);
            }
            if (id.getVersionIRI().isPresent()) {
                putReusableMapping(mappings, id.getVersionIRI().get(), document);
            }
        }

        List<OntologyImportMapping> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            out.add(
                    new OntologyImportMapping(
                            IRI.create(entry.getKey()), IRI.create(entry.getValue())));
        }
        return out;
    }

    private static void putReusableMapping(
            Map<String, String> mappings, IRI logical, IRI document) {
        if (logical == null || document == null || !reusableDocument(document)) {
            return;
        }
        mappings.putIfAbsent(logical.toString(), document.toString());
    }

    private static boolean reusableDocument(IRI document) {
        try {
            String scheme = document.toURI().getScheme();
            return "file".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void addWorkspaceImportMappers(
            OWLOntologyManager manager,
            List<OntologyImportMapping> mappings,
            OntologyNetworkSupport.NetworkImportBlocker blocker) {
        for (OntologyImportMapping mapping : mappings) {
            manager.getIRIMappers()
                    .add(
                            logical ->
                                    mapping.logical.equals(logical)
                                            ? blocker.authorizeMapping(logical, mapping.document)
                                            : null);
        }
    }

    static IRI authorizedMappedDocument(IRI document, DirectAccessPolicy.NetworkRule networkRule) {
        if (document == null) return null;
        final URI uri;
        try {
            uri = document.toURI();
        } catch (RuntimeException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            try {
                return IRI.create(networkRule.authorizeFileImport(uri).toUri());
            } catch (IllegalArgumentException | ToolArgException denied) {
                return null;
            }
        }
        if ("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)
                || "ftp".equalsIgnoreCase(scheme)) {
            return networkRule.permits(uri) ? document : null;
        }
        return null;
    }

    static String ontologyLabel(OWLOntologyID id) {
        if (id.isAnonymous()) {
            return "(anonymous ontology)";
        }
        StringBuilder label = new StringBuilder();
        if (id.getOntologyIRI().isPresent()) {
            label.append(id.getOntologyIRI().get());
        }
        if (id.getVersionIRI().isPresent()) {
            label.append(" version ").append(id.getVersionIRI().get());
        }
        return label.length() == 0 ? id.toString() : label.toString();
    }

    static OWLOntologyDocumentSource documentSource(String source) {
        // An existing local file (plain path or file: URI) wins over IRI parsing — this also
        // handles a
        // Windows drive path like "C:/x.rdf", which java.net.URI would otherwise treat as scheme
        // "c".
        File asFile = localFile(source);
        if (asFile != null) {
            return new FileDocumentSource(asFile.getAbsoluteFile());
        }
        IRI iri = looksLikeWindowsPath(source) ? null : Tools.asIri(source);
        if (iri != null) {
            return new IRIDocumentSource(iri);
        }
        throw new ToolArgException(
                "Ontology document is not a readable file and is not an absolute IRI: "
                        + new File(source).getAbsoluteFile());
    }

    /**
     * The local {@link File} a source string denotes — a plain path OR a {@code file:} URI — or
     * {@code null} if it is not an existing local file. Shared so a path and a {@code file:} IRI
     * are treated identically everywhere (document source AND sibling-catalog resolution).
     */
    static File localFile(String source) {
        if (source == null) {
            return null;
        }
        File asPath = new File(source);
        if (asPath.isFile()) {
            return asPath;
        }
        try {
            URI uri = new URI(source);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File asUri = new File(uri);
                if (asUri.isFile()) {
                    return asUri;
                }
            }
        } catch (Exception ignored) {
            // not a file: URI
        }
        return null;
    }

    /** A bare Windows drive path such as {@code C:\x} or {@code C:/x} — not a document IRI. */
    private static boolean looksLikeWindowsPath(String source) {
        return source.length() >= 2
                && Character.isLetter(source.charAt(0))
                && source.charAt(1) == ':';
    }

    /**
     * If {@code source} is a local file (plain path or {@code file:} URI) with a sibling Protégé
     * catalog ({@code catalog-v001.xml}) in its folder, register an {@link XMLCatalogIRIMapper} on
     * {@code manager} so the document's owl:imports resolve to the local files the catalog maps
     * them to, like Protégé's File ▸ Open (offline import resolution). An import declaration whose
     * IRI matches the imported ontology's IRI/version links in-memory after load; a
     * bare-document-URL declaration loads the document, and reopening through the catalog resolves
     * it. No-op for a non-file source or when no catalog is present; a malformed catalog is
     * ignored, not fatal.
     */
    static void addFolderCatalogMapper(OWLOntologyManager manager, String source) {
        File asFile = localFile(source);
        if (asFile == null || asFile.getAbsoluteFile().getParentFile() == null) return;
        File catalog = new File(asFile.getAbsoluteFile().getParentFile(), "catalog-v001.xml");
        if (!catalog.isFile()) return;
        try {
            manager.getIRIMappers().add(new XMLCatalogIRIMapper(catalog));
        } catch (IOException | RuntimeException ignored) {
            // Compatibility overload: a missing/malformed catalog never blocks the root document.
        }
    }

    static void addFolderCatalogMapper(
            OWLOntologyManager manager,
            String source,
            OntologyNetworkSupport.NetworkImportBlocker blocker) {
        File asFile = localFile(source);
        if (asFile == null) {
            return;
        }
        File folder = asFile.getAbsoluteFile().getParentFile();
        if (folder == null) {
            return;
        }
        File catalog = new File(folder, "catalog-v001.xml");
        if (!catalog.isFile()) {
            return;
        }
        // XMLCatalogIRIMapper.getDocumentIRI() dereferences catalog DELEGATIONS (<nextCatalog>,
        // <delegateURI>, ...) and a DOCTYPE's external DTD/entities over the network WHILE
        // resolving, before authorizeMapping ever sees a mapping. It may follow redirects and read
        // files we cannot bound. Predicting which files that third-party resolver reads (delegation
        // chains, xml:base scoping, symlink-vs-canonical bases) is not a sound gate. Instead, install
        // it ONLY for a catalog that is in-project, is not a symlink, parses cleanly, and contains no
        // DOCTYPE or delegation element. A delegation-free catalog's <uri>/<rewrite*> targets are
        // resolved by the library and gated by authorizeMapping, so a remote or out-of-project
        // target is still refused without a pre-authorization fetch.
        // Apply the strict gate under any CONFINING policy — network-restricted (SSRF risk) OR
        // filesystem-confined (the resolver's own catalog reads, a DOCTYPE's external entity or an
        // out-of-project delegation target, would read files outside the project before
        // authorization).
        // When BOTH axes are fully open (network=allow with no allowlist AND allow_external_paths,
        // or the default no-policy posture), the resolver may follow delegations/DTDs freely.
        // Refusing them would break legitimate offline loads using a local <nextCatalog> chain for
        // no security benefit. Under a confining policy, such a catalog is refused with an
        // inline-to-fix message rather than attempting to re-resolve the third-party graph.
        if (blocker.networkRestricted() || blocker.filesystemConfined()) {
            String unsafe = unsafeFolderCatalog(catalog, blocker);
            if (unsafe != null) {
                // Attribute the confining posture to its actual source (ADR 0005 decision 5): when
                // only the caller's own network=deny engaged the gate, the refusal must name the
                // request instead of training the user to loosen the reviewed policy.
                boolean requestConfined =
                        !blocker.filesystemConfined()
                                && DirectAccessPolicy.DenialSource.REQUEST
                                        == blocker.denialSource();
                blocker.recordCatalogRefusal(
                        "the folder catalog is not safe to resolve offline under "
                                + (requestConfined
                                        ? "the request's network=deny argument"
                                        : "a confining project policy")
                                + " ("
                                + unsafe
                                + "): a symlinked/out-of-project catalog, a DOCTYPE, or catalog"
                                + " delegation (nextCatalog/delegateURI) is refused so nothing can"
                                + " be read out of scope or dereferenced over the network before"
                                + " authorization. Inline the mappings into catalog-v001.xml to"
                                + " resolve them offline");
                return;
            }
        }
        try {
            XMLCatalogIRIMapper delegate = new XMLCatalogIRIMapper(catalog);
            manager.getIRIMappers()
                    .add(
                            logical -> {
                                IRI mapped = delegate.getDocumentIRI(logical);
                                return mapped == null
                                        ? null
                                        : blocker.authorizeMapping(logical, mapped);
                            });
        } catch (IOException | RuntimeException ignored) {
            // A missing/malformed catalog must not block loading the document itself.
        }
    }

    /** Catalog delegation elements: any of these makes the resolver read/fetch another catalog. */
    private static final String[] CATALOG_DELEGATION_ELEMENTS = {
        "nextCatalog", "delegateURI", "delegatePublic", "delegateSystem"
    };

    /**
     * Returns the reason a folder catalog is UNSAFE to hand to {@link XMLCatalogIRIMapper} under a
     * confining policy (network-restricted or filesystem-confined), or null when it is safe. Never
     * touches the network. A catalog is safe only if it is an in-project, non-symlink, parseable
     * file with NO DOCTYPE and NO delegation element — the resolver then performs only local {@code
     * <uri>}/{@code <rewrite*>} mapping whose targets {@code authorizeMapping} re-gates.
     * Delegation/DOCTYPE are refused OUTRIGHT rather than predicting which files the library's own
     * resolver would dereference: its delegation-chain, {@code xml:base} and symlink handling
     * differ subtly from any re-implementation, so emulation is not a sound gate. (No policy loaded
     * → the compatibility overload above still uses the full resolver; this stricter gate applies
     * only under a confining policy.)
     */
    private static String unsafeFolderCatalog(
            File catalog, OntologyNetworkSupport.NetworkImportBlocker blocker) {
        if (!catalog.isFile()) {
            return null;
        }
        Path path;
        try {
            path = catalog.getAbsoluteFile().toPath().normalize();
        } catch (RuntimeException invalid) {
            return catalog.getPath(); // cannot resolve — fail closed
        }
        // A symlinked catalog file is read out of its lexical location by the resolver; refuse it
        // (a
        // normal project catalog-v001.xml is a plain file). Containment below separately resolves
        // symlinks/.. to refuse an out-of-project escape.
        if (Files.isSymbolicLink(path)) {
            return "symlinked catalog " + path;
        }
        if (!blocker.catalogFileAuthorized(path)) {
            return "catalog outside the project filesystem " + path;
        }
        org.w3c.dom.Document document;
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // XXE-safe: no external entity/DTD/schema access, so this scan itself never touches the
            // network or external files (an internal DOCTYPE is still parsed so we can detect it
            // below).
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            document = factory.newDocumentBuilder().parse(path.toFile());
        } catch (Exception unparseable) {
            // Fail closed: the resolver's own (unhardened) parser might read what this one cannot.
            return "unparseable catalog " + path;
        }
        // Any DOCTYPE: the resolver's unhardened parser fetches external DTD and internal-subset
        // external parameter/general entities pre-auth. A valid OASIS catalog never declares one.
        if (document.getDoctype() != null) {
            return "catalog declares a DOCTYPE (its unhardened parser may fetch an external"
                       + " DTD/entity)";
        }
        // Any delegation element: the resolver would read/fetch another catalog during
        // getDocumentIRI,
        // before authorizeMapping, in ways this scan cannot soundly predict. Detect presence only.
        for (String elementName : CATALOG_DELEGATION_ELEMENTS) {
            if (document.getElementsByTagNameNS("*", elementName).getLength() > 0) {
                return "catalog uses <" + elementName + "> delegation";
            }
        }
        return null;
    }

    static String normalizeSource(String source) {
        String trimmed = source.trim();
        String prefix = "https://github.com/";
        int blob = trimmed.indexOf("/blob/");
        if (trimmed.startsWith(prefix) && blob > prefix.length()) {
            String repoPart = trimmed.substring(prefix.length(), blob);
            String blobPart = trimmed.substring(blob + "/blob/".length());
            String[] repo = repoPart.split("/", 2);
            String[] branchAndPath = blobPart.split("/", 2);
            if (repo.length == 2 && branchAndPath.length == 2) {
                return "https://raw.githubusercontent.com/"
                        + repo[0]
                        + "/"
                        + repo[1]
                        + "/"
                        + branchAndPath[0]
                        + "/"
                        + branchAndPath[1];
            }
        }
        return trimmed;
    }
}
