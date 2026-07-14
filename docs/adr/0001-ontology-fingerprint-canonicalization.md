# ADR 0001: ontology fingerprint canonicalization

- Status: accepted for canonicalization version 1
- Date: 2026-07-13

## Decision

Version 1 fingerprints the active ontology's identity, direct import declarations, ontology annotations,
and axioms (including axiom annotations). Imported content is not copied into the semantic fingerprint;
import IRIs are semantic coordinates and a future deterministic import-lock digest is part of the document
fingerprint.

Every OWL object is rendered independently in OWL Functional Syntax with a fixed empty prefix manager and
normalized line endings. Those canonical object strings are sorted and length-delimited before SHA-256 is
applied. The live document fingerprint additionally covers the semantic fingerprint, document IRI, format
key, lexically sorted prefix map, and optional import-lock digest. It follows that a prefix-only change is a
document conflict but not a semantic change.

Because normal OWL serializers may materialize implicit declaration axioms, version 1 synthesizes one
unannotated declaration for every non-built-in signature entity before hashing. Explicit annotated
declarations remain separate inputs. The normalization is signature-driven, so it covers exactly the
entities still present in the remaining signature: adding or removing an unannotated declaration of an
entity that occurs elsewhere in the ontology is normalized away, while a declaration that is the entity's
sole occurrence adds or removes that entity from the signature and therefore does change the semantic
fingerprint. Declaration annotations remain fingerprint-visible in either case.

The algorithm is explicitly versioned. Renderer, OWLAPI, or byte-level changes must not silently retain the
same canonicalization version; cross-version comparison is unsupported unless a migration defines it.

## Anonymous individuals

OWLAPI 4 exposes parser-local `NodeID` values for anonymous individuals. Version 1 does not claim to solve
RDF graph isomorphism and does not expose those raw values. It still returns a hashed, same-session token for
optimistic conflict detection, but marks it `stability=session_only` and `release_stable=false` with a
warning. Strict release code must error instead of publishing that digest as stable.

## Consequences

- Axiom insertion order, set iteration order, live renderer choice, and project prefix aliases do not change
  the semantic fingerprint.
- Ontology annotations, import declarations, axiom annotations, or ontology/version IRIs do change it.
- Format, document IRI, prefixes, or import-lock changes affect only the document fingerprint.
- Fingerprints cover active content plus import coordinates, not a silently changing imports closure.
- Anonymous-individual ontologies require a future graph-isomorphism-safe canonicalization version before a
  strict reproducible release can use the digest.
