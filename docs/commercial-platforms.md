---
title: Commercial platform interoperability
published: false
nav_exclude: true
---

# Commercial ontology platform interoperability
{: .no_toc }

Protégé MCP 0.6.0 can exchange standards-based ontology and validation artifacts with commercial
ontology and knowledge-graph platforms, but it does **not** ship a vendor-specific connector. Today the
supported boundary is a versioned RO-Crate project profile, verified file exchange, and user-authored
CI/API automation. Live bidirectional
synchronization, remote revision mapping, and cross-product transactions are roadmap work.

This page records the public product landscape and the integration boundary as reviewed on
**2026-07-15**. Vendor capabilities, editions, APIs, and licensing can change; an entry here is neither a
compatibility certification nor an endorsement.

## Current 0.6.0 boundary

| Capability | Portable today | Boundary or caveat |
| --- | --- | --- |
| OWL/RDF ontology documents | Yes | Use a serialization accepted by both products and verify an export round trip. Protégé MCP explicitly supports verified saves for its OWLAPI-backed formats; a target may support only a subset. |
| Project discovery metadata | Yes | The project-v1 profile validates RO-Crate 1.0, 1.1, 1.2, or 1.3. Version 1.1 is the broad-compatibility default; 1.2/1.3 may be selected explicitly or inferred from an existing crate context. A vendor still needs RO-Crate import support or a small adapter to consume the metadata. |
| Cross-application dataset identity | Yes | W3C RDFC-1.0 + SHA-256 identifies the asserted root-ontology RDF dataset independently of serialization and blank-node labels. Imported content is outside this root digest. |
| SHACL shapes | Usually | SHACL is a W3C standard, but engine versions, entailment, custom functions, severity handling, and dataset construction can change a result. |
| SPARQL invariants and competency questions | Usually | The SPARQL query is portable; Protégé MCP's CQ headers, aggregate contract, bounds, and required-stage behavior are not automatically understood by another product. |
| Asserted semantic comparison | Yes | Export two documents and use `semantic_diff`, or the headless CLI `diff`. The 0.6.0 CLI deliberately excludes imported axioms and does not perform inferred comparison. |
| Policy validation in CI | Yes, locally | `validate-policy` validates `.protege-mcp/project.yaml`, the attached RO-Crate, and local assets. Commercial products need not consume the YAML execution overlay. Full headless project QC remains roadmap work. |
| Verified artifact publication | Manually or with user automation | `save_ontology` can produce a verified local artifact. Uploading it through a vendor REST, RDF repository, command-line, or import interface is outside the 0.6.0 product contract. |
| Fingerprint v2 and import lock | As attached evidence | These remain Protégé MCP contracts, distinct from the standard RDFC dataset fingerprint. A target may store them as release metadata but must not reinterpret them as its native revision or dependency lock. |
| Workspace revision and change sets | No | `get_model_revision` and preview/commit/discard bind to one live Protégé workspace. They do not map to a remote product's transaction, branch, or approval id. |
| MCP federation | No | An AI client may connect to Protégé MCP and another product's MCP server separately. MCP alone supplies neither atomic cross-server commit nor automatic synchronization. |

The absence of a vendor adapter was verified in the 0.6.0 source: no TopBraid, metaphactory,
PoolParty, GraphDB, Stardog, or Semaphore client is present. The public headless CLI contains only
`validate-policy` and asserted `diff` commands.

## Recommended integration shape

Treat Protégé MCP as the authoring and release-QC side until an adapter is implemented:

```text
Protégé workspace
  -> preview / project QC / commit
  -> verified ontology + validation assets + release evidence
  -> Git and CI
  -> explicit target adapter or vendor import/API
  -> commercial platform
```

For a reverse flow, export an immutable target snapshot, load it as a separate document, run
`semantic_diff`, and then preview any intended changes against the live Protégé revision. Do not overwrite
the authoring workspace directly from a mutable remote graph.

The release gate, not a successful HTTP upload, is the semantic authority. A remote platform can use a
different reasoner, ruleset, SHACL dataset, named-graph layout, or import-resolution policy. Publication
must therefore record the target product/version and verify the uploaded graph independently.

## Product profiles

### TopBraid EDG

TopQuadrant positions [TopBraid EDG](https://www.topquadrant.com/topbraid-edg/) as a semantic data
governance platform with taxonomy/ontology modeling, inference and reasoning, policy-as-code, data
quality, collaboration/workflows, and embedded Copilots.

- **Closest fit:** enterprise ontology governance and steward approval around an authored model.
- **Usable now:** exchange supported RDF/OWL and SHACL artifacts, with Protégé MCP policy and digests
  retained as sidecar release evidence.
- **Connector research required:** supported import/export fidelity by edition, repository/graph identity,
  workflow and revision APIs, authentication, bulk limits, and whether annotated OWL axioms survive a
  round trip.
- **Do not assume:** an EDG workflow approval is equivalent to a Protégé MCP change-set commit, or that
  EDG policy-as-code consumes `.protege-mcp/project.yaml`.

### metaphactory

metaphacts describes [metaphactory semantic knowledge modeling](https://metaphacts.com/solutions/semantic-knowledge-modeling)
as collaborative, open-standards modeling with OWL and SHACL, AI-assisted quality checks and
recommendations, and model-driven applications.

- **Closest fit:** browser-based collaboration between ontology engineers, subject-matter experts, and
  downstream application builders.
- **Usable now:** standards-based document exchange and comparison of exported snapshots.
- **Connector research required:** the supported repository/API surface, revision and optimistic-lock
  semantics, named-graph conventions, permission model, and preservation of ontology headers, imports,
  and annotated axioms.
- **Do not assume:** an AI modeling recommendation has passed the Protégé MCP project gate.

### PoolParty Semantic Suite

PoolParty documents [ontology management](https://www.poolparty.biz/ontology-management) based on SKOS
and OWL, custom schemes, SHACL validation, reusable public ontologies, and APIs/integrations. Its
[Generative AI bundle](https://www.poolparty.biz/generative-ai-bundle) adds knowledge-graph grounding,
retrieval-augmented generation, a Taxonomy Advisor, and an Ontology Manager.

- **Closest fit:** taxonomy/ontology management tied to content enrichment, search, and GraphRAG.
- **Usable now:** exchange SKOS/OWL/RDF and SHACL artifacts supported by the installed PoolParty modules.
- **Connector research required:** which OWL constructs and annotation patterns round-trip, API and bulk
  import contracts, project/workflow revisions, and taxonomy-specific lifecycle mapping.
- **Do not assume:** a SKOS concept scheme preserves arbitrary expressive OWL axioms. A lossy conversion
  must be explicit and reported.

### GraphDB Enterprise

Ontotext/Graphwise presents [GraphDB](https://www.ontotext.com/products/graphdb/) as an RDF database for
semantic metadata, ontologies, reasoning, provenance, data quality, APIs, GraphRAG, and enterprise MCP
integration.

- **Closest fit:** a deployment target that serves a released ontology/knowledge graph to applications
  and AI agents.
- **Usable now:** publish a verified RDF artifact using separately maintained vendor or standards-based
  automation, then export/read back a snapshot for comparison.
- **Connector research required:** repository creation and configuration, RDF4J/REST or graph-store
  behavior, transactions, target revision tokens, inference materialization, SHACL configuration, MCP
  authorization, and backup/rollback.
- **Do not assume:** GraphDB's MCP endpoint and Protégé MCP form one MCP server or one transaction. They
  are independent principals and state machines even when one AI client uses both.

### Stardog

[Stardog](https://www.stardog.com/platform/) describes a semantic AI platform with RDF knowledge graphs,
virtualization/materialization, an inference engine, data quality, open standards, and Voicebox for
LLM-assisted model maintenance and query authoring.

- **Closest fit:** an operational enterprise knowledge graph spanning materialized and virtualized data.
- **Usable now:** upload a verified RDF release through separately maintained Stardog automation and
  compare a subsequent export to the source artifact.
- **Connector research required:** database and named-graph identity, transaction/revision controls,
  reasoning and constraint configuration, virtual-graph boundaries, API limits, and rollback.
- **Do not assume:** a query-time virtual or inferred result belongs in the asserted ontology release.

### Progress Semaphore

[Progress Semaphore](https://www.progress.com/semaphore) focuses on standards-based knowledge models,
collaborative semantic modeling, metadata governance, AI/NLP classification, and integration with
downstream enterprise systems.

- **Closest fit:** governed taxonomies and semantic metadata used for content classification and search.
- **Usable now:** exchange the supported taxonomy/ontology export and validation assets, then compare the
  exported asserted model.
- **Connector research required:** exact OWL/SKOS/RDF coverage, model and workflow APIs, version identity,
  lifecycle representation, and bulk publishing behavior.
- **Do not assume:** NLP classification metadata or a business taxonomy is logically equivalent to an
  OWL ontology asserted in Protégé.

## Interoperability contract for future adapters

A future adapter must keep the standards layer separate from Protégé MCP-specific evidence.

**Standards layer**

- RO-Crate project metadata (1.0–1.3) and the project-v1 profile; 1.1 is the compatibility default.
- W3C RDFC-1.0 + SHA-256 root-dataset identity.
- OWL 2 and the target's documented RDF serializations.
- SHACL shapes, with engine and entailment settings recorded.
- SPARQL queries, with dataset and inference semantics recorded.
- Ontology/import IRIs and version IRIs, without silently replacing them with a vendor project id.

**Protégé MCP evidence layer**

- Policy schema version and policy digest.
- Semantic/document fingerprints and their stability flags.
- Import lock and verification result.
- QC stage outcomes and exact reasoner configuration.
- Semantic diff and, when implemented, release manifest and impact report.

The evidence layer may be attached as metadata or an artifact, but a connector must not claim the target
product natively enforces it. Likewise, a target revision must not be presented as the Protégé workspace
revision.

## Public RO-Crate implementation baseline

The 2026-07-15 review did not find one drop-in Java 17 library that documents complete RO-Crate
1.0–1.3 support:

| Library | Documented support at review | Consequence |
| --- | --- | --- |
| [KIT ro-crate-java](https://github.com/kit-data-manager/ro-crate-java) 2.1.1 | Complete 1.1; its README labels 1.2 support as work in progress and does not claim 1.3 | 1.1 is the strongest documented Java interoperability baseline, but the library is not used as a 1.0–1.3 validator. |
| [ResearchObject ro-crate-py](https://github.com/ResearchObject/ro-crate-py) 0.15.1 | 1.0, 1.1, and 1.2; new crates default to 1.2; no documented 1.3 support | Useful external fixture/round-trip implementation for 1.0–1.2, not proof of 1.3 support. |

There is no authoritative adoption survey proving 1.1 has the largest installed share. Protégé MCP uses
1.1 as the broad-compatibility default because it is the newest complete version common to these reviewed
Java/Python support matrices. The internal validator therefore lives in core's dependency-clean
`ro_crate` package with no Protégé, OWLAPI, MCP, or policy imports (pinned by a seam test); it can be split into an independent Git
project when its release lifecycle warrants that. It is a bounded project-profile validator, not a general
RO-Crate authoring library.

## Required round-trip test

Every supported target/profile needs a checked-in fixture covering:

1. Ontology ID and version IRI.
2. Direct imports and a local catalog mapping.
3. Classes, object/data/annotation properties, and individuals.
4. Annotated axioms, language-tagged strings, and lifecycle annotations.
5. At least one construct near the target's documented OWL support boundary.
6. SHACL shapes and SPARQL validation assets.

The test publishes the verified artifact, reads back an asserted snapshot, normalizes only documented
serializer effects, and runs `semantic_diff`. Any dropped or rewritten construct is a capability result,
not an ignored difference. Inference and SHACL parity are evaluated separately because asserted identity
does not imply identical runtime semantics.

## Security and operational requirements

- Keep credentials out of `.protege-mcp/project.yaml`, ontology annotations, release bundles, logs, and
  MCP results. Use a secret reference resolved by the deployment environment.
- Require HTTPS and an explicit endpoint/host allowlist. Redirects, proxy behavior, and private-address
  access need the same threat model as future network-enabled import loading.
- Separate read/inspect, plan, publish, delete/replace, and administration capabilities. A successful QC
  result grants no remote-write authority by itself.
- Default to dry-run publication with a bounded change summary. Re-check the remote revision immediately
  before mutation and fail closed on drift.
- Publish to a staging repository/graph first, verify it, then promote through a product-supported atomic
  operation where available. Never emulate atomicity when the target does not provide it.
- Record product, edition, API version, repository/graph, principal identity, request id, pre/post remote
  revision, artifact digest, and verification result in the audit trail without recording secrets.
- Keep network-dependent vendor tests opt-in or scheduled; ordinary unit tests and local builds remain
  offline-capable.

## Delivery status and roadmap

| Level | Outcome | Status |
| --- | --- | --- |
| 0. Verified exchange | Versioned RO-Crate, RDFC identity, local verified save, manual/vendor-tool import, exported snapshot diff | Available with 0.6.0 primitives; target procedure is user-maintained |
| 1. Vendor-neutral publication | Capability discovery plus planned/pinned publication over a standard RDF repository protocol | Planned |
| 2. Validated product profiles | Tested GraphDB/Stardog profiles and documented file/API profiles for authoring/governance products | Planned |
| 3. Native workflow adapters | Product-specific revision, approval, staging/promote, and rollback integration where APIs permit | Conditional on official API access and licensing |
| 4. Governed reverse synchronization | Pull snapshot, semantic impact review, conflict detection, and explicit local commit | Deferred until release, audit, and remote-revision contracts are stable |

Implementation is governed by **M9 — Commercial platform interoperability** in
[`PLAN.md`](https://github.com/hakjuoh/protege-mcp/blob/main/PLAN.md). M9 depends on the release
manifest/headless work rather than bypassing it; vendor SDKs and credentials must remain outside the
ontology-engineering core.

## Primary sources

Vendor product claims above were checked against these official pages on 2026-07-15:

- [TopBraid EDG](https://www.topquadrant.com/topbraid-edg/)
- [metaphactory semantic knowledge modeling](https://metaphacts.com/solutions/semantic-knowledge-modeling)
- [PoolParty ontology management](https://www.poolparty.biz/ontology-management) and
  [Generative AI bundle](https://www.poolparty.biz/generative-ai-bundle)
- [GraphDB](https://www.ontotext.com/products/graphdb/)
- [Stardog Semantic AI Platform](https://www.stardog.com/platform/)
- [Progress Semaphore](https://www.progress.com/semaphore)

Relevant standards references:

- [RO-Crate 1.0](https://www.researchobject.org/ro-crate/specification/1.0/index.html),
  [1.1](https://www.researchobject.org/ro-crate/specification/1.1/index.html),
  [1.2](https://www.researchobject.org/ro-crate/specification/1.2/index.html), and
  [1.3](https://www.researchobject.org/ro-crate/specification/1.3/index.html)
- [W3C RDF Dataset Canonicalization 1.0](https://www.w3.org/TR/rdf-canon/)
- [OWL 2 Web Ontology Language](https://www.w3.org/TR/owl2-overview/)
- [RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/)
- [SHACL](https://www.w3.org/TR/shacl/)
- [SPARQL 1.1](https://www.w3.org/TR/sparql11-overview/)
