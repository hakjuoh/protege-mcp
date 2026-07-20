---
title: RO-Crate & RDFC
nav_order: 8
permalink: /interoperability/
---

# RO-Crate and RDFC interoperability
{: .no_toc }

Protégé MCP 0.6.0 gives an ontology project a portable metadata record and a reproducible RDF dataset
identity. **RO-Crate** describes the project and its root ontology artifact; **RDFC-1.0 + SHA-256**
identifies the asserted root RDF dataset independently of statement order and blank-node labels.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What each part does

| Part | Role | Where you see it |
| --- | --- | --- |
| RO-Crate | Portable JSON-LD metadata for the ontology project, its root file, profile, title, publication date, and license. | `ro-crate-metadata.json` (or `.jsonld` for RO-Crate 1.0) |
| RDFC-1.0 + SHA-256 | Canonicalizes the root RDF dataset and hashes the canonical N-Quads, so insignificant ordering and blank-node labels do not change its identity. | `rdf_dataset_fingerprint` from `run_project_qc` |
| Protégé MCP project policy | Tells Protégé MCP which crate, root artifact, canonicalization, reasoner, and QC stages the project requires. It is an execution overlay, not an exchange standard. | `.protege-mcp/project.yaml` |

RO-Crate metadata can be read by another RO-Crate-aware application without understanding
`project.yaml`. Conversely, the RDFC digest identifies RDF content; it does not replace project
metadata, a file checksum, or an import lock.

## Project layout

A minimal project keeps the policy, crate metadata, and ontology artifact under one project root:

```text
my-ontology/
├── .protege-mcp/
│   └── project.yaml
├── ontology.ttl
└── ro-crate-metadata.json
```

The policy declares the portable layer explicitly:

```yaml
version: 1
project_id: example-ontology
root_ontology: https://example.org/ontology
project_root: .

interoperability:
  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
  additional_profiles: []
  root_artifact: ontology.ttl
  metadata:
    path: ro-crate-metadata.json
    format: ro-crate-1.1
  canonicalization:
    algorithm: RDFC-1.0
    hash: SHA-256
    scope: root-ontology
    timeout_ms: 120000

filesystem:
  allow_external_paths: false
network:
  default: deny
  allowed_hosts: []

reasoning:
  reasoner: HermiT
  owl_profile: DL
  required: true
  timeout_ms: 120000

imports:
  mode: unlocked
  fail_on_missing: true
  network: deny

validation:
  required_stages: [interoperability, reasoner, profile, governance, structural]
  fail_on: warning
```

Paths are resolved from the effective project root, not from the process working directory. Protégé
MCP rejects URL-shaped paths, traversal, and symlink escapes unless the local administrator has
explicitly enabled the external-path compatibility profile.

## Minimal RO-Crate metadata

For the policy above, a matching RO-Crate 1.1 descriptor is:

```json
{
  "@context": "https://w3id.org/ro/crate/1.1/context",
  "@graph": [
    {
      "@id": "ro-crate-metadata.json",
      "@type": "CreativeWork",
      "about": {"@id": "./"},
      "conformsTo": {"@id": "https://w3id.org/ro/crate/1.1"}
    },
    {
      "@id": "./",
      "@type": "Dataset",
      "name": "Example ontology project",
      "description": "An ontology project packaged for reproducible validation.",
      "datePublished": "2026-07-16",
      "license": "https://spdx.org/licenses/BSD-2-Clause.html",
      "identifier": "example-ontology",
      "conformsTo": {
        "@id": "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/"
      },
      "mainEntity": {"@id": "ontology.ttl"},
      "hasPart": {"@id": "ontology.ttl"}
    },
    {
      "@id": "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/",
      "@type": "CreativeWork",
      "name": "Ontology project RO-Crate profile v1"
    },
    {
      "@id": "ontology.ttl",
      "@type": "File",
      "encodingFormat": "text/turtle",
      "about": {"@id": "https://example.org/ontology"}
    },
    {
      "@id": "https://example.org/ontology",
      "@type": "Dataset",
      "conformsTo": {"@id": "https://www.w3.org/TR/owl2-overview/"}
    }
  ]
}
```

The root `identifier` must equal the policy's `project_id`. The root artifact and ontology IRI must
also agree with `root_artifact` and `root_ontology`.

## Supported RO-Crate versions

| Policy format | Required context | Metadata filename | Profile entity |
| --- | --- | --- | --- |
| `ro-crate-1.0` | `https://w3id.org/ro/crate/1.0/context` | `ro-crate-metadata.jsonld` | `CreativeWork` |
| `ro-crate-1.1` | `https://w3id.org/ro/crate/1.1/context` | `ro-crate-metadata.json` | `CreativeWork` |
| `ro-crate-1.2` | `https://w3id.org/ro/crate/1.2/context` | `ro-crate-metadata.json` | `Profile` |
| `ro-crate-1.3` | `https://w3id.org/ro/crate/1.3/context` | `ro-crate-metadata.json` | `Profile` |

RO-Crate 1.1 is the compatibility default. Select another version when the receiving environment
requires it. If both `metadata.format` and `metadata.path` are omitted, Protégé MCP can retain an
unambiguous 1.2 or 1.3 context from an existing `ro-crate-metadata.json`; otherwise it materializes
the 1.1 defaults. It never silently rewrites a crate from one declared version to another.

The complete required graph and version rules are defined by the
[Ontology project RO-Crate profile v1](../profiles/project-v1/).

## Validate and run the interoperability gate

Use these v0.6.0 tools:

1. [`get_project_policy`](../tools/quality.html#get_project_policy) shows the discovered policy,
   effective defaults, resolved crate/root-artifact paths, and validation messages.
2. [`validate_project_policy`](../tools/quality.html#validate_project_policy) requires a policy and
   validates its RO-Crate metadata without running ontology QC.
3. [`run_project_qc`](../tools/quality.html#run_project_qc) runs the required `interoperability`
   stage on the same isolated snapshot as the other stages.

A successful project-QC result includes:

- `rdf_dataset_fingerprint`: `sha256:<lowercase hex>` over RDFC-1.0 canonical N-Quads;
- `rdf_dataset_identity`: the canonicalization, hash, scope, RO-Crate version, and profile coordinates;
- `semantic_fingerprint`: Protégé MCP's separate OWL/editor revision identity;
- an `interoperability` stage with `status=pass`.

An invalid/missing crate, a canonicalization timeout, or a dataset that cannot be rendered losslessly
makes the required stage an execution `error`; it cannot become a false pass.

## What the RDFC digest covers

The `root-ontology` scope includes:

- ontology and version IRIs;
- ontology annotations;
- asserted axioms, including axiom annotations;
- direct `owl:imports` coordinates.

It excludes the contents of imported ontologies. Use
[`write_import_lock`](../tools/documents.html#write_import_lock) and
[`verify_import_lock`](../tools/documents.html#verify_import_lock) to checksum local dependency
artifacts.

RDFC-1.0 canonicalizes the RDF dataset before hashing, so triple/quad order and parser-assigned
blank-node identifiers do not affect the result. Protégé MCP verifies the serialized dataset before
hashing and fails closed when the host RDF renderer would omit an asserted anonymous-individual
structure.

Do not compare `rdf_dataset_fingerprint` with `semantic_fingerprint`: they intentionally use different
contracts. The former is the standards-facing RDF dataset identity; the latter is the editor-aware
revision token used by transactional previews and optimistic concurrency.

## Offline and bounded validation

RO-Crate validation never dereferences JSON-LD contexts, profiles, imports, or arbitrary entity IRIs.
Metadata is limited to 4 MiB, 20,000 graph entities, and 100 JSON nesting levels, with duplicate JSON
keys rejected. Canonicalization is bounded by the policy timeout (120 seconds by default).

Protégé MCP validates this exchange boundary but does not ship a vendor-specific repository connector.
Move the ontology file, RO-Crate metadata, policy, and verified import artifacts through the file/API
workflow supported by the receiving system. See [Commercial platform interoperability](../commercial-platforms.html)
for the current product profiles, non-goals, and connector requirements.
