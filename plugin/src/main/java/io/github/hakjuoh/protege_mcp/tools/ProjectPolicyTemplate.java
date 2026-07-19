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
 * either populated with safe, asset-free defaults (filesystem/network/imports/reasoning/validation) or
 * commented out with guidance (prefixes, modules, annotations, iri_policy, lifecycle, the
 * asset-referencing validation stages, and release). The generated file references two files the user
 * must still create — the {@code root_artifact} and the RO-Crate metadata — so the bare template does
 * not validate on its own; {@link #validationHint(Template)} lists exactly what remains. Once those two
 * files exist, the emitted policy loads valid through {@code ProjectPolicyLoader}.
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
            String metadataPath, String reasoner, boolean rootOntologyPlaceholder, String profile) {
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
        boolean placeholder = rootOntologyIri == null;
        String rootOntology = placeholder ? PLACEHOLDER_IRI : rootOntologyIri;
        boolean obo = OBO.equals(profile);
        String rootArtifact = obo ? "ontology-edit.owl" : "ontology.ttl";
        String reasoner = obo ? "ELK" : "HermiT";
        String owlProfile = obo ? "EL" : "DL";
        String yaml = header()
                + required(yamlScalar(projectId), yamlScalar(rootOntology), rootArtifact,
                        reasoner, owlProfile)
                + validationBlock()
                + optionalBlocks(obo, rootOntology);
        return new Template(yaml, projectId, rootOntology, rootArtifact,
                "ro-crate-metadata.json", reasoner, placeholder, profile);
    }

    /** What the user must still create/edit before the generated policy validates. */
    static List<String> validationHint(Template t) {
        List<String> hint = new ArrayList<>();
        hint.add("Create the root artifact '" + t.rootArtifact() + "' by serializing your ontology to "
                + "that project-relative path (save_ontology with policy_bootstrap=true — the "
                + "explicit-path save stays authorized inside the project root while this policy "
                + "is still invalid).");
        hint.add("Create the RO-Crate metadata file '" + t.metadataPath() + "' — an ro-crate-1.1 crate "
                + "whose root dataset references '" + t.rootArtifact() + "'.");
        if (t.rootOntologyPlaceholder()) {
            hint.add("Set root_ontology to your ontology's IRI; it is currently the placeholder '"
                    + t.rootOntology() + "' because the active ontology is anonymous.");
        }
        hint.add("Confirm reasoning.reasoner names a reasoner installed in Protégé (currently '"
                + t.reasoner() + "'; a version-less name is resolved against the installed "
                + "reasoners and must match exactly one).");
        hint.add("Uncomment and edit any optional blocks you need (prefixes, modules, annotations, "
                + "iri_policy, lifecycle, validation invariants/shacl/competency_questions, the imports "
                + "lockfile, release) and create the files they reference.");
        hint.add("Run validate_project_policy (then run_project_qc) to confirm the completed policy is "
                + "valid before you commit it.");
        return hint;
    }

    // ------------------------------------------------------------------ template sections

    private static String header() {
        return """
                # Protégé MCP project policy (v1) — starter template.
                #
                # This file is SOURCE CODE: review it, complete it, and commit it with your ontology.
                # Protégé MCP discovers it at <project>/.protege-mcp/project.yaml by walking up from the
                # active ontology document. Unknown fields are rejected, so keep every key spelled
                # exactly as below. Run validate_project_policy after editing; the commented blocks near
                # the end stay inert until you uncomment and complete them.

                """;
    }

    private static String required(String projectId, String rootOntology, String rootArtifact,
            String reasoner, String owlProfile) {
        return ("""
                version: 1

                # A stable identifier for this ontology project (any non-empty string).
                project_id: %PROJECT_ID%

                # The ontology IRI this project governs. It MUST equal your ontology's IRI.
                root_ontology: %ROOT_ONTOLOGY%

                # The project base directory, relative to the discovery anchor. For the conventional
                # .protege-mcp/project.yaml location, '.' is the directory containing .protege-mcp/,
                # so your ontology and sources sit beside that directory rather than inside it.
                project_root: .

                # Standard interoperability contract (required). root_artifact and the RO-Crate metadata
                # are files you must create in the project (see the validation hint returned with this
                # template). RO-Crate 1.1 is the broad-compatibility default.
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

                # The reasoner used for reproducible QC. A version-less name (the convention) must
                # resolve to exactly ONE installed reasoner; a full display name pins that version.
                reasoning:
                  reasoner: %REASONER%
                  owl_profile: %OWL_PROFILE%
                  required: true
                  timeout_ms: 120000

                """)
                .replace("%PROJECT_ID%", projectId)
                .replace("%ROOT_ONTOLOGY%", rootOntology)
                .replace("%PROFILE_IRI%", ProjectInteroperability.PROFILE_IRI)
                .replace("%ROOT_ARTIFACT%", rootArtifact)
                .replace("%REASONER%", reasoner)
                .replace("%OWL_PROFILE%", owlProfile);
    }

    private static String validationBlock() {
        return """
                # QC stages that must run and the severity that fails the gate. Add invariants, cqs, or
                # shacl to required_stages only after you configure (and create files for) the matching
                # commented sub-blocks.
                validation:
                  required_stages: [interoperability, reasoner, profile, governance, structural]
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

                """;
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
