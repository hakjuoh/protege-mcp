---
title: Ontology project RO-Crate profile v1
permalink: /profiles/project-v1/
nav_exclude: true
---

# Ontology project RO-Crate profile v1

This document is the persistent human-readable profile description identified by
`https://hakjuoh.github.io/protege-mcp/profiles/project-v1/`.

The profile defines a small, standards-facing ontology project identity. It does not make
`.protege-mcp/project.yaml` an exchange standard: RO-Crate is the portable metadata layer, while
`project.yaml` remains a Protégé MCP execution and quality-control overlay.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY, and
OPTIONAL are interpreted as described by RFC 2119 and RFC 8174.

## Supported RO-Crate versions

An attached crate MUST declare exactly one of these policy format identifiers and use the matching
Recommendation context, specification identifier, and metadata filename:

| Policy format | JSON-LD context | Descriptor `conformsTo` | Metadata filename | Profile entity type |
| --- | --- | --- | --- | --- |
| `ro-crate-1.0` | `https://w3id.org/ro/crate/1.0/context` | `https://w3id.org/ro/crate/1.0` | `ro-crate-metadata.jsonld` | `CreativeWork` |
| `ro-crate-1.1` | `https://w3id.org/ro/crate/1.1/context` | `https://w3id.org/ro/crate/1.1` | `ro-crate-metadata.json` | `CreativeWork` |
| `ro-crate-1.2` | `https://w3id.org/ro/crate/1.2/context` | `https://w3id.org/ro/crate/1.2` | `ro-crate-metadata.json` | includes `Profile` |
| `ro-crate-1.3` | `https://w3id.org/ro/crate/1.3/context` | `https://w3id.org/ro/crate/1.3` | `ro-crate-metadata.json` | includes `Profile` |

RO-Crate 1.1 is the broad-compatibility default because it is the newest complete version shared by
the reviewed Java and Python library support matrices. This is a compatibility decision, not a claim
of measured market share. A consumer MAY infer 1.2 or 1.3 from exactly one recognized normative
`@context` when no version was requested explicitly; ambiguous or absent signals fall back to 1.1.
Versions 1.0 and 1.1 predate the formal
RO-Crate Profile model introduced in 1.2; they use the shared `dct:conformsTo` relationship and a
`CreativeWork` contextual entity as a compatibility representation. Consumers MUST NOT silently
rewrite one version as another.

## Required graph

The flattened `@graph` MUST contain:

1. the version-specific metadata descriptor, typed `CreativeWork`, whose `about` references `./` and
   whose `conformsTo` references the selected RO-Crate specification;
2. a root entity with `@id: ./`, typed `Dataset`, with a human-readable `name` and `description`, a
   `datePublished` value that is one calendar-valid ISO 8601 date string (year, year-month, day, or
   day-plus-time forms with strict range resolution; week, ordinal, and basic formats are not
   accepted by this profile), a `license` (contextual-entity reference or textual statement), an
   `identifier` equal to `project_id`, and `conformsTo` referencing this profile plus every declared
   additional profile — `description`, `datePublished`, and `license` are root-entity MUSTs in every
   supported Recommendation, and the profile enforces them because the descriptor asserts
   specification conformance;
3. one contextual entity for every declared profile, with an absolute `@id` and a `name`; its type
   follows the version table above;
4. `mainEntity` and `hasPart` references from the root to the root ontology artifact;
5. a root-artifact entity typed `File`, with `encodingFormat` and `about` referencing the root
   ontology IRI; and
6. an entity whose `@id` is the root ontology IRI, typed `Dataset`, whose `conformsTo` references the
   [OWL 2 overview](https://www.w3.org/TR/owl2-overview/).

Additional crate entities and properties MAY be supplied. In the flattened form this profile
validates, every `@graph` entry MUST be a JSON object with a non-empty `@id` that is unique within
the crate and a non-empty `@type`. The profile's portable core intentionally does not require
Protégé MCP validation assets, import locks, release output, or credentials to be listed. A more
specific profile MAY add those requirements.

## Context and storage rules

The metadata document MUST be stored under the selected version's metadata filename from the table
above; validation checks the on-disk name, not only the descriptor entity. Every `@context` entry
MUST be a string: the selected Recommendation context MUST be present, and no other supported
Recommendation context may appear beside it, so an offline consumer can never be ambiguous about
which specification governs the crate. Inline (object-form) context entries are rejected because
they could silently redefine core RO-Crate terms that offline validation cannot re-resolve.
Additional string entries are permitted but are not dereferenced.

## RDF dataset identity

The interoperable identity contract is W3C RDFC-1.0 canonicalization followed by SHA-256. Its scope is
`root-ontology`: ontology and version IRIs, ontology annotations, asserted axioms (including axiom
annotations), and direct `owl:imports` coordinates are included; imported ontology content is excluded.
The canonical N-Quads byte sequence — the canonical quads in Unicode code point order, each terminated
by a line feed — is hashed and reported as `sha256:<lowercase hex>`. The implementation is pinned to
the official W3C rdf-canon test vectors, including code point (not UTF-16) ordering of quads whose
first difference is a supplementary character.

This digest identifies the RDF dataset and is intended for cross-application comparison. It is distinct
from Protégé MCP fingerprint v2, which remains an OWL/editor revision and optimistic-concurrency token.
Consumers MUST NOT substitute either value for the other.

A producer MUST NOT publish a digest for a dataset its RDF rendering cannot serialize losslessly. The
host OWL RDF renderer silently drops rootless anonymous-individual structures (unanchored reference
cycles, anonymous inverse-property pairs, negative assertions among unanchored anonymous individuals,
anonymous annotation cycles), so this implementation verifies
the serialized dataset directly — every property and annotation assertion must appear under its
predicate — and the required QC stage fails closed instead of reporting a digest that ignores part of
the dataset. Structures the renderer emits faithfully (anchored cycles, self-loops, trees, diamonds)
keep fingerprinting.

Known upstream limitation: Titanium 2.0.0 assigns blank-node labels from internal hashes whose inputs
are sorted in UTF-16 code unit order. When two quads mentioning the same blank node first differ at a
supplementary character, the assigned labels can deviate from a fully conformant RDFC-1.0
implementation even though the emitted quad ordering itself is code point conformant. Within one
toolchain the digest remains deterministic.

## Validation and security

Validation is offline and bounded: metadata is limited to 4 MiB, 20,000 graph entities, 100 levels of
JSON nesting, strict duplicate-key rejection, and the configured canonicalization deadline (120 seconds
by default). Validation MUST NOT dereference contexts, profiles, imports, or arbitrary entity IRIs.

The implementation lives in the dependency-clean `ro_crate` package of the core module. Its public API
has no Protégé, OWLAPI, MCP, or project-policy imports (pinned by a seam test) so it can be extracted to
an independent Git project without changing the profile contract.
