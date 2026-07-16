---
title: "ADR 0003: project v1 standard interoperability"
nav_exclude: true
---

# ADR 0003: project v1 standard interoperability

- Status: accepted
- Date: 2026-07-15

## Context

`project.yaml` and ontology fingerprint v2 are useful Protégé MCP contracts, but neither is an industry
exchange standard. Freezing policy v1 without a portable identity layer would force other applications to
understand a product-specific YAML document and OWLAPI-oriented digest.

RO-Crate has four Recommendations in scope (1.0–1.3), with a filename transition after 1.0 and a formal
Profiles Vocabulary model beginning in 1.2. W3C RDFC-1.0 supplies serializer- and blank-node-independent
RDF dataset canonicalization.

Available public implementations did not provide one suitable Java 17 dependency covering the complete
1.0–1.3 validation contract at review time. `ro-crate-java` documented 1.1 support and partial 1.2 work;
`ro-crate-py` documented 1.0–1.2 support. Neither documented 1.3 support. Titanium RDFC 3.0.0 implements
the desired algorithm but requires Java 21, while Protégé MCP supports Java 17.

## Decision

- Policy v1 requires an `interoperability` block and a matching attached RO-Crate.
- Formats `ro-crate-1.0` through `ro-crate-1.3` are supported; 1.1 is the broad-compatibility
  default because it is the newest complete version shared by the reviewed Java/Python implementations.
  This does not assert measured industry market share. Versions 1.2/1.3 remain available when a user or
  target environment selects them, and are inferred from an existing crate's unambiguous normative
  context when no version was authored.
- Validation applies each version's normative context, specification identifier, metadata filename, and
  profile representation. Older versions are compatibility modes, not aliases for 1.3.
- The portable core contains the root ontology identity and artifact. `project.yaml` remains the local
  execution overlay.
- W3C RDFC-1.0 + SHA-256 identifies the asserted root ontology RDF dataset. Fingerprint v2 remains the
  separate workspace/revision token.
- RO-Crate parsing and profile validation live in core's dependency-clean `ro_crate` package with
  library-owned request, version, result, and diagnostic types. The package imports nothing beyond the
  JDK and Jackson (pinned by a seam test) and is intentionally extractable to another Git project.
- Titanium RDFC stays at 2.0.0 because it is Java-17-compatible. Version 3.0.0 is deferred until the host
  runtime moves to Java 21 or the upstream project offers a compatible line.
- Titanium 2.0.0 sorts serialized quads with Java's UTF-16 code unit comparison, while RDFC-1.0 requires
  Unicode code point order (UTF-8 byte order); the two diverge when quads first differ at a supplementary
  character. The emitted canonical quads are therefore re-sorted into code point order locally, pinned by
  a regression test. The same UTF-16 comparison inside titanium's first-degree blank-node hashing is not
  reachable from outside the library; that residual deviation only affects blank-node label assignment
  when two quads mentioning the same blank node first differ at a supplementary character.
- The profile enforces the base Recommendations' root-entity MUSTs (`description`, `datePublished`,
  `license`), the on-disk metadata filename, and string-only, unambiguous `@context` declarations, so a
  crate that passes required QC is also acceptable to standard RO-Crate tooling.
- OWLAPI's RDF renderer silently drops rootless anonymous-individual structures (unanchored reference
  cycles, anonymous inverse-property pairs, negative assertions among unanchored anonymous individuals,
  self-referential anonymous type expressions, sameAs/differentFrom-linked anonymous cycles, anonymous
  annotation cycles), which would let distinct
  datasets fingerprint identically. Rather than model the renderer's reachability rules, the digest
  verifies the serialized dataset directly — every object/data/annotation assertion must appear under
  its (inverse-simplified) predicate — and fails closed on any loss, while faithfully rendered shapes
  (anchored cycles, self-loops, trees, diamonds) keep fingerprinting. Assertions that misuse reserved
  RDF/RDFS/OWL vocabulary as their property (forbidden in OWL 2 DL) also fail closed, because
  mapping-emitted triples would make the verification unattributable; the built-in rdfs:/owl:
  annotation properties remain verifiable. Measured scale: a realistic
  200k-axiom ontology canonicalizes in under 2 seconds (about a 70x margin under the 120 s default
  deadline), while clusters of thousands of mutually-referencing blank nodes degrade super-linearly
  and are cut off by the deadline rather than hanging QC.

## Consequences

Existing draft v1 policies must add portable metadata before 0.6.0. This is intentionally done before the
first release of v1, avoiding a later incompatible reinterpretation. Project QC gains a required
`interoperability` stage and returns an `rdf_dataset_fingerprint` independently of fingerprint v2.

The bundled validator is a bounded profile validator, not a general RO-Crate object model, JSON-LD reasoner,
packager, or replacement for the upstream projects. Future extraction requires build/release metadata and
coordinates to change, but not consumer-facing validation types or the profile rules.
