# Testing & method-level coverage

This document describes the headless unit-test suite added to make the codebase safe to refactor
aggressively. Every pure/decision-making seam that can run without a live Protégé/OSGi/GUI runtime is
now pinned by method-level tests.

## Running the tests

```bash
mvn -o test          # runs the whole JUnit 5 suite (surefire), headless, offline-capable
mvn -o test -Dtest=OAuthStoreTest      # a single class
```

- Framework: **JUnit 5 (Jupiter)** only — no Mockito/AssertJ. Fakes are hand-rolled or built with
  `java.lang.reflect.Proxy`.
- `provided`-scope deps (OWLAPI 4.5.29, Protégé editor 5.6.6, Guava) plus the embedded MCP SDK,
  Jetty 12, Jakarta Servlet 6.1, Apache Jena ARQ and Jackson are all on the test classpath.
- The suite is **deterministic** (verified across repeated runs). OS-specific behaviour (POSIX
  login-shell wrapping, executable-bit semantics) is guarded with JUnit `Assumptions`.

At the time of writing: **2,790 tests, green** (2,781 plugin tests plus 9 standalone-CLI tests), across
`tools`, `prompts`, `contracts`, `oauth`, `server`, `chat`, `config`, the pure helpers of `ui`, and the
headless CLI. Coverage is measured by **JaCoCo** (`mvn verify`) and a floor on
the `tools`/`server`/`oauth` layers gates against regressions; the EDT/subprocess-bound `ui`/`chat`
surfaces are intentionally not gated.

Historical correction: the `v0.5.0` tag's copy of this page still said 2,044, but that tag's verified
release commit records the actual clean run as **2,488 tests**. The count above comes from the current
Surefire XML reports after a clean build.

## Public-contract and policy-schema harnesses

- `PublicContractSnapshotTest` pins the immutable 0.5.0 baseline (66 tool registrations and 11
  prompt registrations); the current 78-tool runtime surface is checked against it, allowing only
  reviewed additive drift. The tool goldens combine all MCP registration metadata and input schemas with
  the manual's documented result fields; prompt goldens also render every template with deterministic
  sentinel arguments. Compatibility checks allow additive optional surface while rejecting
  removed/changed arguments, new required prompt arguments, dropped result fields, unreviewed descriptions,
  workflow text or metadata, and undocumented registrations. Published baselines cannot be overwritten;
  snapshots are canonical LF files on every platform.
- `ProjectPolicySchemaTest` validates the minimal, general-OWL, and OBO YAML examples against the packaged
  policy v1 JSON Schema. Adversarial cases cover unknown/future fields, unsafe project roots, malformed
  identities/IRIs/dates/host allowlists and types, duplicate/unknown stages, invalid timeouts, and missing
  lock/stage assets.
- `ContractRecordsTest` round-trips the project/revision/finding/stage/gate records through Jackson and the
  packaged JSON Schema, then attacks missing/skipped/error stages, severity thresholds, duplicate stages,
  unknown/duplicate JSON fields, malformed fingerprints/non-canonical UUIDs, forged gate status/findings/
  counts, optional-stage semantics, and caller collection mutation.
- The 0.6.0 runtime slice adds `OntologyFingerprintsTest`, `ProjectPolicyLoaderTest`,
  `InvariantFilesTest`, and `ProjectQcToolsTest`, plus strict-suite and multi-file SHACL cases. They cover
  canonical save/reload fingerprints, anonymous-individual stability, YAML/path/symlink attacks, persisted
  asset failures, required-inference degradation, reasoner/ontology snapshot races, and the three-way gate.
  `IsolatedValidationSnapshotTest` additionally mutates the live active ontology and an imported ontology after
  capture, proving that profile/structural/governance/invariant/CQ/SHACL stages stay on one private revision,
  imported declarations remain visible to checks without being replayed, closure-only edits change the race
  token, and edits to the private preflight copy cannot leak into Protégé.
- `IsolatedReasonerSpecTest` and `IsolatedReasonerQcTest` pin the ADR 0002 boundary: exact plugin
  configuration-object identity, buffering-mode dispatch, default-overload injection, runtime policy caveats,
  malformed plugin metadata, no live classification/query access, import-spanning unsatisfiability, adversarial
  post-capture mutation, shared inferred materialization, and timeout interruption/stale-result rejection.

The direct aligned Jackson dependencies intentionally precede Protégé's provided jars in `pom.xml`.
Protégé's OSGi jar contains an old private `JsonFactory`; OSGi isolates it at runtime, but Surefire is a
flat classpath. This order prevents a split-version test JVM when the real JSON Schema validator runs.

## God-class decomposition (Phase 3, 2026-07-01)

With the safety net in place, the three god-classes were decomposed **behavior-preservingly** using an
*extract-class + keep-a-thin-delegating-facade* pattern (public entry points and call-sites unchanged, so
the whole suite stays green after every step):

- **`Tools` (754 → 383 LOC)** → 7 focused classes in `io.github.hakjuoh.protege_mcp.tools`: `ToolArgs`,
  `ToolResults`, `ToolSchemas`, `EntityResolver`, `EntityRendering`, `EntityJson`, `AnnotationBuilder`.
  `Tools` keeps one-line delegators plus the nested `Tools.Json` / `Tools.SchemaBuilder` types (referenced
  as types across the codebase, so they stay put).
- **`ToolCatalog`** → the hardcoded 16-provider list became a single package-private `PROVIDERS` list
  iterated by `buildAll`; `ToolCatalogTest`'s two duplicated lists now iterate that same source of truth.
- **`ChatView`** → pure logic extracted to headless classes in `io.github.hakjuoh.protege_mcp.chat`:
  `AttachmentFileManager` (scratch-dir lifecycle), `ChatComposer` (placeholder matching /
  active-attachment filtering), `ChatModels` (provider→model-pref-key + model-label normalization). The
  Swing class delegates. New direct tests: `AttachmentFileManagerTest`, `ChatComposerTest`, `ChatModelsTest`.
  The `ChatSendController` turn/threading state machine (Phase 12) was **deferred** — it is EDT/threading
  bound and not headless-coverable today.

## The `FakeModelManager` harness

`plugin/src/test/java/io/github/hakjuoh/protege_mcp/tools/FakeModelManager.java` is a `Proxy`-based test double for
`org.protege.editor.owl.model.OWLModelManager`, backed by a real in-memory `OWLOntology`. It
implements only what the pure tool cores actually call — data factory, active ontology, a
signature-backed entity finder, and short-form rendering — and **throws
`UnsupportedOperationException` for everything else** (expression-checker factory, reasoner manager,
workspace). Tests that need a deliberate Protégé “None” selection opt into a purpose-built wrapper
with a reasoner manager but no current factory. This lets the many `mm`-taking tool helpers be tested for their full-IRI / declared-entity
operand paths (e.g. all ~38 `Axioms` axiom types), while any stray dependence on the live runtime
fails loudly instead of silently NPE-ing.

```java
OWLModelManager mm = FakeModelManager.empty();     // fresh empty ontology
OWLModelManager mm = FakeModelManager.over(ont);   // over a hand-built ontology
```

It is package-private to `io.github.hakjuoh.protege_mcp.tools`, so only tests in that package can use it.

## Seams extracted for testability

The following were previously gated behind a live Protégé/GUI runtime. Each got a **behavior-preserving
seam** (a package-private overload / injected collaborator / extracted pure helper) so its decision
logic is now exercised headless. Public entry points are unchanged; production wiring still uses the
real collaborator.

| Class | Seam added | Test |
| --- | --- | --- |
| `OntologyAccess` | package-private `EdtGateway` interface + `OntologyAccess(kit, timeout, EdtGateway)` ctor — the EDT fast-path / async / timeout / post-timeout-cancellation / exception logic is now driven by an injected gateway | `OntologyAccessSeamTest` |
| `McpConfig` | package-private `load(Preferences)` / `regenerateToken(Preferences)` — defaults + blank-token minting tested against a `Proxy` `Preferences` | `McpConfigSeamTest` |
| `CliSupport` | package-private `resolveLoginShell(String)` + `loginShellWrap(cmd, osName, shellEnv)` — OS branch, shell fallback and argv quoting are now env-independent | `CliSupportSeamTest` |
| `ReasonerTools` | reasoner-injected cores `unsatisfiableClasses/inferredRelation/explainEntailment/dlQuery` (+ `structuralExplanation`/`relatedAssertedAxioms` made package-private) — driven by an OWL API `StructuralReasoner` | `ReasonerToolsSeamTest` |
| `ValidationTools` | reasoner-injected `reasonerVerdict(mm, reasoner, limit)` | `ValidationToolsReasonerSeamTest` |
| `SparqlTools` | reasoner-injected `snapshot(mm, reasoner, includeInferred)` — inferred-axiom materialisation tested with a `StructuralReasoner` | `SparqlToolsSeamTest` |
| `IsolatedValidationSnapshot` | one private loaded-closure copy + explicit-closure structural/governance/SPARQL seams; read-only captured-rendering adapter avoids live Protégé access off the EDT | `IsolatedValidationSnapshotTest` |
| `ui` pure logic | extracted `ServerViewText` (mask / date / connect-command) and `ChatText` (usage formatting / pasted-text heuristic / image classification); the Swing classes delegate | `ServerViewTextTest`, `ChatTextTest` |
| `Tools.tryManchesterClassExpression` | widened to package-private — the OWL API (full-IRI) Manchester path is covered; bare-name resolution still needs Protégé's expression checker | `ManchesterClassExpressionSeamTest` |

## Still not covered headless (integration territory)

- `McpServerHook.initialise/dispose` — OSGi `EditorKitHook` background start/stop/promote thread
  coordination; thin delegation, deliberately left for integration testing (not seamed).
- `EmbeddedClassificationWaiter.runAndWait` with a *live* classification (`classifyAsynchronously`) —
  tied to Protégé's reasoner-manager event lifecycle (the waiter's coordination logic itself IS tested
  via the EDT-gateway seam with proxy managers).
- `ReasonerTools.get_explanations` for the *explainable* axiom types — the clarkparsia justification
  (hitting-set) search needs a real DL reasoner (HermiT/Pellet); the fallback path is tested.
- `ui/*` component wiring (`ChatView`/`McpServerView`/panels) — Swing/AWT construction throws
  `HeadlessException`; only the extracted pure helpers are unit-tested.

## Suspected production issues — resolved (2026-07-01)

The three latent issues the characterization tests surfaced were reviewed and resolved:

1. **`OAuthStore.isValidAccessToken` honoured an orphaned token** (the `return true` sat *outside* the
   `if (c != null)` guard). **Fixed — fail-closed:** a token is now valid only while its owning client
   record exists (the `return true` moved inside the guard). This aligns with the revoke/evict design
   (both drop a client together with its tokens) and, since a token never outlives its client in normal
   operation, never affects legitimate flows — it only rejects tokens from corrupted persisted state.
   Pinned by `isValidAccessTokenFalseWhenClientMissing`.
2. **`OAuthStore.client(null)` / `consumeAuthCode(null)` / `refresh(null)` threw `NullPointerException`.**
   **Fixed — null-guarded** to return `null`, matching each method's documented "unknown → null"
   contract. Reachable via a malformed `POST /oauth/token` (omitting `code` / `refresh_token`), which
   now yields a clean `invalid_grant` 400 instead of a 500.
3. **`OAuthMetadataServlet` on a null request URI.** **Not a bug** — `HttpServletRequest.getRequestURI()`
   is non-null by the servlet contract for any served request, so this is unreachable through Jetty;
   production left unchanged.

Non-bugs worth knowing (plan guesses that were wrong; tests assert the real behaviour): the MCP SDK
builders throw `IllegalArgumentException` (not `NPE`) on null/empty name; `ToolCatalog.buildAll(null)`
does **not** NPE (lambdas defer the dereference); `ChatView.enqueue` does **not** no-op on empty text
(the guard lives in `append`).
