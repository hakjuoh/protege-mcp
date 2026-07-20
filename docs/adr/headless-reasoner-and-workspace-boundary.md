---
title: "Headless reasoner and workspace boundary"
published: false
nav_exclude: true
---

# Headless reasoner and workspace boundary

- Status: accepted
- Date: 2026-07-18

## Context

The `0.7.0` CLI can validate project-policy files and compare asserted ontology documents, but it cannot
run the full project/release gate, update an import lock, prepare a release, or serve the headless MCP
subset. Those operations need two things the current executable deliberately lacks:

1. a real OWL reasoner with a reviewed redistribution contract; and
2. a filesystem workspace that provides the same snapshot, import, gate, revision, and artifact semantics
   as the Protégé adapter without importing Protégé APIs or pretending that filesystem writes have GUI Undo.

OWLAPI's structural reasoner is already available but is not a full validation reasoner: it cannot provide
the inconsistency and unsatisfiable-class verdict required by the existing project-QC reasoner stage.
Silently using it would make a headless pass weaker than a Protégé pass.

The plugin's tests already use `net.sourceforge.owlapi:org.semanticweb.hermit:1.3.8.431`. That artifact is
the HermiT line built for OWLAPI 4.x; its manifest imports OWLAPI `[4.3,5)`, and the existing real-reasoner
tests run it against this project's OWLAPI 4.5.29 runtime. Its sources state LGPL-3.0-or-later for HermiT.
The artifact contains an unmaintained LGPL-2.1 JAutomata fork under its root `rationals` packages and
declares separate runtime dependencies on BSD-licensed `dk.brics.automaton:automaton:1.11-8`, Apache-2.0
Axiom components, and the Commons Logging API. Current HermiT source lines still link directly to the same
three `rationals` types, so changing HermiT versions does not remove that dependency. HermiT's OSGi build
also nests copies of dependency JARs behind `Bundle-ClassPath`; a plain/shaded Java process cannot use
those nested copies directly.

AutomataLib `0.12.1` is a maintained, Apache-2.0 generic automata library with a 2025 release and Java 11+
support. It can represent HermiT's arbitrary object-property labels and nondeterministic transitions. It is
not a drop-in dependency because HermiT's existing bytecode links the old `rationals.*` class names.

## Decision

### Baseline reasoner

The standalone distribution embeds HermiT `1.3.8.431` as its one baseline reasoner for `0.7.2`.

- The CLI reports the exact implementation coordinate, version, factory class, configuration class,
  buffering mode, timeout, and advertised caveats in every reasoner-dependent gate result.
- A version-less policy reference `HermiT` resolves to that one implementation. A full name/version or
  factory id must match it exactly. A policy naming ELK, Pellet, JFact, or another unbundled reasoner fails
  with `reasoner_unavailable`; the CLI never substitutes HermiT under another name.
- The HermiT dependency excludes its transitive `owlapi-distribution`; the executable keeps one OWLAPI,
  `owlapi-osgidistribution` 4.5.29, matching the core and plugin contract.
- Maven must resolve `dk.brics.automaton` and Axiom as ordinary transitive artifacts and the shade step must
  flatten their classes onto the executable classpath. OWLAPI's existing `jcl-over-slf4j` bridge supplies
  the Commons Logging API, so HermiT's separate Commons Logging artifact is excluded rather than creating a
  duplicate class set. The inert nested dependency JARs inside the HermiT OSGi artifact are excluded from
  the shaded output after their runtime classes are supplied in those usable forms. This preserves HermiT's
  `xsd:anyURI`, `rdf:PlainLiteral` facet, and `rdf:XMLLiteral` code paths instead of deferring a
  `NoClassDefFoundError` to a rare datatype.
- The executable excludes HermiT's embedded `rationals/**` implementation. Three small compatibility types
  preserve only the constructors and methods linked by HermiT's object-property inclusion manager, while
  delegating NFA state and transition storage to AutomataLib `0.12.1`. A private non-null symbol represents
  HermiT's `null` epsilon label at the adapter boundary. No JAutomata converter, transformation, state
  factory, or other implementation class is shipped. This is preferred to copying or independently
  maintaining an NFA implementation in this project.
- HermiT is treated as an OWL 2 DL reasoner, not as a guarantee for every rule extension. Ontologies with
  unsupported SWRL built-ins or other rejected constructs produce a reasoner execution error, never a
  successful empty verdict. Outer project/tool timeouts interrupt and dispose the private reasoner and
  discard late results, following the accepted isolated-reasoner configuration-parity decision.
- Cross-adapter conformance is asserted only when both adapters use HermiT over the same immutable closure
  and compatible configuration. A user's different Protégé reasoner remains valid, but its result is not
  relabelled as HermiT parity.

### Redistribution requirements

HermiT may enter the executable/release assets only with all of the following checks in the same change:

- name HermiT `1.3.8.431` (LGPL-3.0-or-later), AutomataLib `0.12.1` (Apache-2.0),
  `dk.brics.automaton` `1.11-8` (BSD), Apache Axiom, and the `jcl-over-slf4j` compatibility bridge in
  `THIRD_PARTY_NOTICES.md`; record separately that the unmodified corresponding HermiT source archive still
  contains its unused JAutomata fork under LGPL-2.1;
- ship the complete GPL-2.0, LGPL-2.1, GPL-3.0, LGPL-3.0, and Apache-2.0 texts plus the exact component
  copyright/conditions for dk.brics.automaton, Jaxen, and Stax2, and the corresponding unmodified HermiT
  source JAR with the release; that source JAR includes the excluded `rationals` JAutomata sources, so the
  LGPL-2.1 text and pinned upstream licensing evidence remain in the source-compliance bundle even though
  those classes are absent from the executable;
- document how to rebuild/relink the executable with a modified compatible HermiT JAR, and do not prohibit
  reverse engineering needed to debug such modifications;
- never shade-relocate HermiT or OWLAPI packages; relinking guidance names the exact tagged application
  source and build command that reproduces the shaded JAR with a substituted HermiT dependency;
- make the release workflow verify that the binary contains the expected HermiT factory while the source
  and license assets are present and checksum-addressed; and
- review the exact resolved dependency tree. An unexpected Protégé runtime, second OWLAPI, or unreviewed
  reasoner dependency fails the build/release check.

This decision records engineering requirements, not legal advice. If the project cannot satisfy them, it must
not publish the reasoner-bearing CLI; it must not fall back to a weaker reasoner while claiming full QC.

### Shared service and adapter boundary

The ontology-engineering core owns Protégé-free application services and their immutable request/result
contracts:

- `ProjectWorkspace` captures one root ontology plus its authorized, resolved import closure, source
  coordinates/checksums, policy digest, and revision token.
- `ReasonerRuntime` creates one private reasoner for a captured workspace and exposes the bounded
  consistency, satisfiability, hierarchy, and inferred-data operations required by QC/diff/release.
- `ProjectQcService`, `ImportLockService`, and `ReleaseService` orchestrate the already-defined stages and
  return the same finding/gate/manifest/report contracts on every surface.
- Project-relative `ArtifactStore` remains the only report/release/audit writer and gains filesystem
  transaction support where needed.

Adapters own environment mechanics, not validation meaning:

- The Protégé adapter captures the live model and exact selected-plugin reasoner configuration, marshals
  through the model thread, and commits changes through one GUI Undo unit.
- The headless adapter reads only authorized project files, resolves catalogs/locks without network unless
  explicitly allowed, works in a temporary project workspace, checks source bytes/revision immediately
  before replacement, and commits with atomic replacement plus optional backup. A project-confined
  cross-process advisory lock serializes mutating CLI/stdio operations; inability to acquire or support the
  lock fails mutation closed rather than falling back to last-writer-wins. It promises no GUI Undo.
- The CLI and stdio MCP layers parse arguments, map exit/protocol errors, and select supported services;
  they do not reimplement QC or release decisions.

No public service accepts a caller-supplied `OWLOntologyManager` with hidden mappers or global state. A
workspace capture owns its manager, import mappings, reasoner, temporary files, and cleanup lifecycle.
Results identify the adapter, reasoner, policy digest, and exact captured revision.

## Consequences

- Full headless QC has an explicit, reproducible semantic baseline instead of depending on whatever
  reasoner happens to be installed in a developer's Protégé.
- The executable grows and acquires LGPL redistribution obligations. Release automation and notices are
  load-bearing; omitting them is a release failure.
- Plugin/CLI parity becomes testable without forcing the user's live Protégé reasoner selection to change.
- Policies that require another reasoner work interactively when installed but fail honestly headlessly.
- Moving orchestration into core is incremental: pure decisions and contracts move first; Protégé model,
  renderer, EDT, Undo, preferences, OAuth, and UI types remain prohibited in core.
- Filesystem mutation cannot reuse live Undo semantics. Temporary workspaces, checksum conflict detection,
  backups, manifests, and atomic replacement are the recovery boundary.

## Verification

- Dependency tests pin exactly one OWLAPI and no Protégé classes in the CLI closure. The shaded artifact
  must contain AutomataLib and the three compatibility types, and must not contain a legacy JAutomata-only
  class such as `rationals.converters.toAscii`.
- A shaded-artifact smoke test loads the HermiT factory and classifies satisfiable, unsatisfiable, and
  inconsistent fixtures, including `xsd:anyURI` facets, `rdf:PlainLiteral` pattern/length facets, and
  `rdf:XMLLiteral`; a complex role-chain fixture exercises the replacement NFA path. Unsupported input,
  missing transitive classes, and timeout paths fail closed.
- License/release tests require the GPL/LGPL texts, HermiT source JAR, notices, checksums, non-relocated
  packages, and relinking guidance.
- Cross-adapter fixtures compare stage status, stable finding identity, fingerprints, reasoner identity,
  manifests, and report checksums for the same captured project.
- Headless mutation tests cover symlink/path escapes, offline imports, tampered locks, source swaps,
  concurrent processes/advisory-lock failure, replacement failure, backup recovery, interruption, and
  stale-result rejection.
