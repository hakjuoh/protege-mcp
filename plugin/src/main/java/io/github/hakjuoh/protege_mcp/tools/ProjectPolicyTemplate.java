package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.policy.ProjectInteroperability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure generator for a commented, schema-valid <em>starter</em> {@code .protege-mcp/project.yaml}.
 *
 * <p>This is a one-shot scaffold: it emits YAML whose REQUIRED blocks (version, project_id,
 * root_ontology, interoperability) are populated from the live ontology and whose OPTIONAL blocks are
 * either populated with safe, asset-free defaults (filesystem/audit/network/imports/reasoning/validation) or
 * commented out with guidance (prefixes, modules, annotations, iri_policy, lifecycle, the
 * asset-referencing validation stages, and release). The writing tool supplies the active ontology's
 * existing project-relative document path as {@code root_artifact}, creates a matching RO-Crate
 * metadata file when needed, and chooses a uniquely installed reasoner (or omits that optional stage),
 * so the emitted policy validates immediately.
 *
 * <p>No Protégé types here: the caller captures the ontology IRI/document on the model thread and this
 * class renders off it.
 */
final class ProjectPolicyTemplate {

    static final String GENERAL = "general";
    static final String OBO = "obo";

    /** Placeholder root ontology used only when the active ontology is anonymous. */
    private static final String PLACEHOLDER_IRI = "https://example.org/ontology";

    private ProjectPolicyTemplate() {
    }

    /** The generated template plus the derived coordinates the caller reports and hints on. */
    record Template(String yaml, String projectId, String rootOntology, String rootArtifact,
            String metadataPath, String reasoner, boolean rootOntologyPlaceholder, String profile,
            int version) {
    }

    /** {@code general} or {@code obo}; null signals an invalid value (caller returns an arg error). */
    static String normalizeProfile(String raw) {
        if (raw == null) {
            return GENERAL;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return GENERAL.equals(value) || OBO.equals(value) ? value : null;
    }

    /**
     * A project id derived from the active ontology IRI's last path segment, sanitized to a safe slug;
     * {@code "my-project"} when there is no usable IRI segment.
     */
    static String deriveProjectId(String rootOntologyIri) {
        if (rootOntologyIri == null) {
            return "my-project";
        }
        String local = WriteTools.localName(rootOntologyIri);
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            slug.append((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' ? c : '-');
        }
        String cleaned = slug.toString().replaceAll("-{2,}", "-");
        while (cleaned.startsWith("-") || cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isBlank() ? "my-project" : cleaned;
    }

    static Template render(String profile, String projectId, String rootOntologyIri) {
        return render(profile, projectId, rootOntologyIri, 3);
    }

    static Template render(String profile, String projectId, String rootOntologyIri, int version) {
        return render(profile, projectId, rootOntologyIri, version,
                OBO.equals(profile) ? "ontology-edit.owl" : "ontology.ttl", "HermiT");
    }

    static Template render(String profile, String projectId, String rootOntologyIri, int version,
            String rootArtifact, String reasoner) {
        if (version < 1 || version > 3) {
            throw new IllegalArgumentException("policy template version must be 1, 2, or 3");
        }
        boolean placeholder = rootOntologyIri == null;
        String rootOntology = placeholder ? PLACEHOLDER_IRI : rootOntologyIri;
        boolean obo = OBO.equals(profile);
        String owlProfile = obo ? "EL" : "DL";
        String yaml = header(version)
                + required(version, yamlScalar(projectId), yamlScalar(rootOntology), rootArtifact,
                        reasoner, owlProfile)
                + workspaceBlock(version, yamlScalar(rootOntology), rootArtifact, placeholder)
                + validationBlock(reasoner != null)
                + (version >= 2 ? v2Blocks() : "")
                + optionalBlocks(obo, rootOntology);
        return new Template(yaml, projectId, rootOntology, rootArtifact,
                "ro-crate-metadata.json", reasoner, placeholder, profile, version);
    }

    /** Review guidance for optional capabilities after the generated policy validates. */
    static List<String> validationHint(Template t) {
        List<String> hint = new ArrayList<>();
        if (t.rootOntologyPlaceholder()) {
            hint.add("Set root_ontology to the project's release/interoperability entry-point IRI; "
                    + "it is currently the placeholder '" + t.rootOntology()
                    + "' because the ontology used to create the starter is anonymous.");
            if (t.version() >= 3) {
                hint.add("After assigning the anonymous ontology its real IRI, add one matching "
                        + "workspace.ontologies binding for its project document.");
            }
        }
        if (t.reasoner() != null) {
            hint.add("The reasoner stage uses the uniquely installed reasoner '" + t.reasoner()
                    + "'. Review that choice if project QC should use a different reasoner.");
        } else {
            hint.add("No uniquely selectable reasoner was installed, so the optional reasoner QC "
                    + "stage was omitted. Add it after installing and selecting a reasoner.");
        }
        hint.add("Uncomment and edit any optional blocks you need (prefixes, modules, entity_search, "
                + "annotations, iri_policy, lifecycle, validation invariants/shacl/competency_questions, "
                + "the imports lockfile, release) and create the files they reference.");
        if (t.version() >= 2) {
            hint.add("Review the version " + t.version()
                    + " external_terms, mappings, jobs, and materialization bounds. "
                    + "Provider origin aliases and credential bindings must be configured owner-locally; "
                    + "never put endpoint URLs or secret values in this policy.");
        }
        hint.add("Runtime audit streams stay owner-only outside the project. Add "
                + "'.protege-mcp/audit-export*.jsonl' to VCS ignore rules unless an explicitly exported "
                + "review artifact is meant to be committed.");
        hint.add("Run validate_project_policy (then run_project_qc) to confirm the completed policy is "
                + "valid before you commit it.");
        return hint;
    }

    // ------------------------------------------------------------------ template sections

    private static String header(int version) {
        return ("""
                # Protégé MCP project policy (v%VERSION%)
                #
                # This file is SOURCE CODE: review it, complete it, and commit it with your ontology.
                # Protégé MCP discovers it at <project>/.protege-mcp/project.yaml by walking up from the
                # active ontology document. Unknown fields are rejected, so keep every key spelled
                # exactly as below. Run validate_project_policy after editing; the commented blocks near
                # the end stay inert until you uncomment and complete them.

                """).replace("%VERSION%", Integer.toString(version));
    }

    private static String required(int version, String projectId, String rootOntology, String rootArtifact,
            String reasoner, String owlProfile) {
        return ("""
                version: %VERSION%

                # A stable identifier for this ontology project (any non-empty string).
                project_id: %PROJECT_ID%

                # Release/interoperability entry point retained across Policy v1-v3. It seeds whole-project operations
                # but does not restrict which project or external ontology can be active in Protégé.
                root_ontology: %ROOT_ONTOLOGY%

                # The project base directory, relative to the discovery anchor. For the conventional
                # .protege-mcp/project.yaml location, '.' is the directory containing .protege-mcp/,
                # so your ontology and sources sit beside that directory rather than inside it.
                project_root: .

                # Standard interoperability contract (required). root_artifact is the active ontology
                # document and the template writer creates RO-Crate metadata for it when missing.
                # RO-Crate 1.1 is the broad-compatibility default.
                interoperability:
                  profile: %PROFILE_IRI%
                  additional_profiles: []
                  # Serialize your ontology to this project-relative file.
                  root_artifact: %ROOT_ARTIFACT%
                  metadata:
                    path: ro-crate-metadata.json
                    format: ro-crate-1.1
                  canonicalization:
                    algorithm: RDFC-1.0
                    hash: SHA-256
                    scope: root-ontology
                    timeout_ms: 120000

                # Security defaults are explicit so a reviewed policy never depends on the process
                # working directory or ambient network state.
                filesystem:
                  allow_external_paths: false
                # Runtime events are stored owner-only outside the project/VCS tree. These settings bound
                # retention and rotation; they never place audit content in this policy.
                audit:
                  retention_days: 90
                  max_file_bytes: 10485760
                  max_files: 10
                network:
                  default: deny
                  allowed_hosts: []

                # Import handling. Switch mode to 'locked' and set a lockfile to pin every import by
                # checksum (create the lockfile with write_import_lock first).
                imports:
                  mode: unlocked
                  fail_on_missing: true
                  network: deny
                  # lockfile: imports.lock.json

                """)
                .replace("%VERSION%", Integer.toString(version))
                .replace("%PROJECT_ID%", projectId)
                .replace("%ROOT_ONTOLOGY%", rootOntology)
                .replace("%PROFILE_IRI%", ProjectInteroperability.PROFILE_IRI)
                .replace("%ROOT_ARTIFACT%", yamlScalar(rootArtifact))
                + reasonerBlock(reasoner, owlProfile);
    }

    private static String workspaceBlock(int version, String rootOntology, String rootArtifact,
            boolean placeholderOntology) {
        if (version < 3) return "";
        if (placeholderOntology) {
            return ("""
                    # Physical project membership is known, but this anonymous ontology has no logical
                    # IRI binding yet. Add one workspace.ontologies row after assigning its real IRI.
                    workspace:
                      files: [%ROOT_ARTIFACT%]

                    """).replace("%ROOT_ARTIFACT%", yamlScalar(rootArtifact));
        }
        return ("""
                # Physical project membership and logical ontology/document bindings are independent.
                # One ontology IRI may have multiple project documents, but has one binding row.
                workspace:
                  files: [%ROOT_ARTIFACT%]
                  ontologies:
                    - iri: %ROOT_ONTOLOGY%
                      documents: [%ROOT_ARTIFACT%]

                """)
                .replace("%ROOT_ONTOLOGY%", rootOntology)
                .replace("%ROOT_ARTIFACT%", yamlScalar(rootArtifact));
    }

    private static String reasonerBlock(String reasoner, String owlProfile) {
        if (reasoner == null) {
            return """
                    # No uniquely selectable reasoner was available when this template was generated.
                    # Add a reasoning block and the reasoner validation stage after installing one.

                    """;
        }
        return ("""
                # The reasoner used for reproducible QC. The exact installed display name avoids an
                # ambiguous version-less match when multiple reasoner plugins are installed.
                reasoning:
                  reasoner: %REASONER%
                  owl_profile: %OWL_PROFILE%
                  required: true
                  timeout_ms: 120000

                """)
                .replace("%REASONER%", yamlScalar(reasoner))
                .replace("%OWL_PROFILE%", owlProfile);
    }

    private static String v2Blocks() {
        return """
                # Version 2 feature policy. Network endpoints and credential bindings are owner-local;
                # the project names only a reviewed provider profile and origin alias.
                external_terms:
                  providers: []

                # Canonical SSSOM 1.0 sidecar and mapping-governance defaults. An absent store is valid
                # until a mapping operation creates it with confirmation and an expected revision.
                mappings:
                  path: .protege-mcp/mappings.sssom.tsv
                  allowed_predicates: []
                  allowed_sources: []
                  allowed_licenses: []
                  require_license: false
                  required_findings: []
                  directional_cycle_policy:
                    skos:broadMatch: error
                    skos:narrowMatch: error
                  many_to_one_rules: []

                # These values are product maxima; a project may only tighten them.
                jobs:
                  allowed_types: [classification, project_qc, semantic_diff, inference_materialization]
                  workers: 2
                  queue_capacity: 32
                  active_per_principal: 8
                  retained_per_principal: 32
                  retained_per_backend: 128
                  retention_seconds: 3600

                materialization:
                  allowed_reasoners: []
                  allowed_categories: [subclass_axioms, equivalent_class_axioms, class_assertions,
                    property_hierarchy_axioms, object_property_assertions, data_property_assertions]
                  allowed_destinations: [new_ontology, project_file]
                  allow_source_write: false
                  max_axioms_per_category: 50000
                  max_axioms_total: 50000
                  max_bytes: 67108864
                  timeout_ms: 120000

                """;
    }

    private static String validationBlock(boolean includeReasoner) {
        return ("""
                # QC stages that must run and the severity that fails the gate. Add invariants, cqs, or
                # shacl to required_stages only after you configure (and create files for) the matching
                # commented sub-blocks.
                validation:
                  required_stages: [%REQUIRED_STAGES%]
                  fail_on: warning
                  structural:
                    disabled: []
                    severity_overrides: {}
                  # invariants:
                  #   paths: [quality/invariants/*.rq]
                  # shacl:
                  #   paths: [quality/shapes.ttl]
                  # competency_questions:
                  #   convention: robot-sparql-dir
                  #   path: cqs

                """).replace("%REQUIRED_STAGES%", includeReasoner
                        ? "interoperability, reasoner, profile, governance, structural"
                        : "interoperability, profile, governance, structural");
    }

    private static String optionalBlocks(boolean obo, String rootOntology) {
        String banner = """
                # ---------------------------------------------------------------------------------------
                # OPTIONAL blocks — uncomment and edit the ones you need, create any files they name, and
                # add their stage to validation.required_stages where noted. They are commented so the
                # template validates as soon as the root_artifact and RO-Crate metadata exist.
                # ---------------------------------------------------------------------------------------

                """;
        return banner + (obo ? oboOptional() : generalOptional(rootOntology));
    }

    private static String generalOptional(String rootOntology) {
        return """
                # CURIE prefixes used by the annotation/lifecycle rules below.
                # prefixes:
                #   ex: https://example.org/ontology/
                #   dcterms: http://purl.org/dc/terms/
                #   rdfs: http://www.w3.org/2000/01/rdf-schema#
                #   skos: http://www.w3.org/2004/02/skos/core#

                # Governed modules that make up the ontology (each path is a file you maintain).
                # modules:
                #   - ontology_iri: %ROOT_ONTOLOGY%
                #     path: ontology.ttl
                #     owned_namespaces:
                #       - https://example.org/ontology/

                # Annotation governance (needs the prefixes above uncommented).
                # annotations:
                #   labels:
                #     properties: [rdfs:label]
                #     required_languages: [en]
                #     one_preferred_per_language: true
                #   definitions:
                #     properties: [skos:definition]
                #     required: true
                #     required_languages: [en]
                #   required: [dcterms:source]

                # Local term discovery. 'und' means an untagged literal; order is priority order.
                # entity_search:
                #   preferred_properties: [rdfs:label, skos:prefLabel]
                #   synonym_properties: [skos:altLabel]
                #   preferred_languages: [en]
                #   fallback_languages: [und]

                # IRI shape policy (Java regex).
                # iri_policy:
                #   required_namespaces:
                #     - https://example.org/ontology/
                #   pattern: '^https://example[.]org/ontology/[A-Z][A-Za-z0-9_]+$'

                # Lifecycle / deprecation policy.
                # lifecycle:
                #   status_property: ex:status
                #   allowed_values: [active, deprecated]
                #   deprecated_values: [deprecated]
                #   replaced_by_properties: [dcterms:isReplacedBy]
                #   require_replacement: true

                # Release bundle settings used by run_release_gate / prepare_release.
                # release:
                #   format: turtle
                #   output_dir: dist
                #   require_version_iri: true
                #   require_clean_round_trip: true
                """
                .replace("%ROOT_ONTOLOGY%", rootOntology);
    }

    private static String oboOptional() {
        return """
                # CURIE prefixes used by the annotation/lifecycle rules below.
                # prefixes:
                #   EXAMPLE: http://purl.obolibrary.org/obo/EXAMPLE_
                #   IAO: http://purl.obolibrary.org/obo/IAO_
                #   oboInOwl: http://www.geneontology.org/formats/oboInOwl#
                #   rdfs: http://www.w3.org/2000/01/rdf-schema#
                #   skos: http://www.w3.org/2004/02/skos/core#

                # Governed modules that make up the ontology (each path is a file you maintain).
                # modules:
                #   - ontology_iri: http://purl.obolibrary.org/obo/example.owl
                #     path: ontology-edit.owl
                #     owned_namespaces:
                #       - http://purl.obolibrary.org/obo/EXAMPLE_

                # Annotation governance (needs the prefixes above uncommented).
                # annotations:
                #   labels:
                #     properties: [rdfs:label]
                #     required_languages: [en]
                #     one_preferred_per_language: true
                #   definitions:
                #     properties: ['IAO:0000115']
                #     required: true
                #     required_languages: [en]
                #   required: ['oboInOwl:hasDbXref']

                # Local term discovery. 'und' means an untagged literal; order is priority order.
                # entity_search:
                #   preferred_properties: [rdfs:label, skos:prefLabel]
                #   synonym_properties: [skos:altLabel, oboInOwl:hasExactSynonym,
                #     oboInOwl:hasRelatedSynonym]
                #   preferred_languages: [en]
                #   fallback_languages: [und]

                # IRI shape policy (OBO numeric IDs).
                # iri_policy:
                #   required_namespaces:
                #     - http://purl.obolibrary.org/obo/EXAMPLE_
                #   pattern: '^http://purl[.]obolibrary[.]org/obo/EXAMPLE_[0-9]{7}$'

                # Lifecycle / deprecation policy.
                # lifecycle:
                #   status_property: oboInOwl:hasObsolescenceReason
                #   allowed_values: [active, obsolete]
                #   deprecated_values: [obsolete]
                #   replaced_by_properties: ['IAO:0100001']
                #   require_replacement: true

                # Release bundle settings used by run_release_gate / prepare_release.
                # release:
                #   format: obo
                #   output_dir: releases
                #   require_version_iri: true
                #   require_clean_round_trip: true
                """;
    }

    // ------------------------------------------------------------------ YAML scalar safety

    /**
     * Render a scalar as a safe YAML plain scalar, single-quoting only when a plain scalar would
     * misparse. IRIs (whose {@code :} is never followed by a space) and slugs stay unquoted, matching
     * the shipped example style; a user-supplied project_id with YAML indicators is quoted.
     */
    static String yamlScalar(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        boolean needsQuote = value.startsWith(" ") || value.endsWith(" ")
                || value.contains(": ") || value.endsWith(":")
                || value.contains(" #") || value.contains("\n") || value.contains("\t")
                || "!&*?|>%@\"'#,[]{}".indexOf(value.charAt(0)) >= 0
                || (value.charAt(0) == '-' && (value.length() == 1 || value.charAt(1) == ' '))
                || isYamlReserved(value);
        return needsQuote ? "'" + value.replace("'", "''") + "'" : value;
    }

    private static boolean isYamlReserved(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "true":
            case "false":
            case "null":
            case "yes":
            case "no":
            case "on":
            case "off":
            case "~":
                return true;
            default:
                return value.matches("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
        }
    }
}
