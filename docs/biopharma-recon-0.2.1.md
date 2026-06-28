# protege-mcp 0.2.1 — Evaluation via IOF Biopharma reconstruction

Tool-driven reconstruction of the IOF **Biopharma / Agent** module
(`https://github.com/iofoundry/ontology/tree/master/biopharma`) performed entirely through the
protege-mcp MCP tools, to (1) verify the tools work and (2) evaluate the 0.2.0 natural-language
layer and find gaps for 0.2.1. Date: 2026-06-27. Driven against a live Protégé with the IOF Core
closure (Core + BFO + AnnotationVocabulary, ~3000 axioms) loaded from the local clone.

The Agent module was chosen (with MaterialProcurementAndStorage object-property/deprecation patterns
folded in as targeted probes) because, though only 5 classes, it exercises nearly the whole tool
surface: decoupled `construct`-namespace term IRIs, defined (`equivalentClass`) vs primitive
(`subClassOf` restriction) classes, deeply nested `only`/`some` restrictions over BFO relations,
`union`/`intersection` fillers, the full `iof-av` annotation suite, typed/lang/IRI annotation values,
imports, and reasoning over defined classes. (Survey confirmed: biopharma uses **no QUDT** and has
**zero data properties** — those were deliberately not reconstructed.)

## Outcome

The Agent module was reconstructed faithfully: **45 axioms** (5 declarations, 3 equivalent-class
definitions, 8 subclass axioms incl. the nested BFO restrictions, 29 annotation assertions),
8 ontology-header annotations, 1 import. The reasoner classifies it **consistent, 0 unsatisfiable**;
`validate_ontology` reports **0 issues**; DL queries and inferred-superclass reads return the
intended classifications. Saved to `/tmp/agent-reconstructed.ttl`.

**Headline:** once the import closure is resolved, the tools express the full modelling complexity of
a real BFO/IOF ontology with no loss. Every gap below is about the *preconditions and ergonomics* of
NL-driven authoring — getting to a writable, resolved state and doing it without dozens of calls —
not about expressive power.

## What works well (0.2.0 strengths, confirmed live)

- **Full OWL 2 via `add_axiom` + Manchester.** Nested `(EngineeredSystem or Person)`,
  `'has realization' only (ManufacturingProcess and (realizes some (Capability and (capabilityOf some
  PieceOfEquipment))) and ('has participant' some (EngineeredSystem or Person)))` all parsed and
  applied correctly. BFO properties referenced by quoted label (`'has realization'`), IOF by local
  name.
- **`preview_changes` is the antidote to silent minting.** Previewing the 6 compound axioms returned
  `new_entities: []` for every one — proving all terms bound to real loaded entities before any write.
- **Annotation-property resolution binds to loaded vocabulary.** `iof-av` short names
  (`naturalLanguageDefinition`, `firstOrderLogicDefinition`, `isPrimitive`, `copyright`, `maturity`),
  `dcterms:*`, `skos:*`, `rdfs:*` CURIEs all resolved with no phantom minting once
  AnnotationVocabulary was loaded.
- **Typed / lang / IRI annotation values** all faithful: `xsd:boolean` (`isPrimitive true`),
  `xsd:anyURI` (license), `@en-US` labels, IRI-valued `rdfs:isDefinedBy` / `maturity` / `replacedBy`.
- **Reasoning + validation are correct and import-aware.** `run_reasoner` (consistent, 0 unsat),
  `validate_ontology` audits only owned (`construct`, `isDefinedBy`-scoped) classes without flagging
  thousands of imported terms, `execute_dl_query` (`designates some Agent` → `AgentIdentification`),
  `get_inferred_superclasses`, `explain_entailment`, `get_explanations` (2 justifications: the
  asserted subclass and the equivalence) all behaved correctly.
- **`add_import` fidelity:** correctly reports `resolved:false` with actionable guidance when a remote
  import can't be fetched. `load_ontology` resolved the whole Core→BFO→AnnotationVocabulary closure
  via the directory catalog. Object-property `domain`/`range`/`sub_object_property_of` work, incl. a
  **union domain** (`Agent or EngineeredSystem`) applied as a single axiom.
- **Round-trip:** `remove_axiom` exact-match (incl. datatype), `delete_entity` cascade,
  `undo_change`/`redo_change`, `save_ontology` (format by extension) all work.

## Gaps found (ranked) — 0.2.1 targets

### High

1. **No `set_active_ontology` (the #1 wall).** Edits target the active ontology; resolving the Core
   import required `load_ontology`, which **steals active** (→ Core) with no tool to switch back. The
   reconstruction was hard-blocked until the active ontology was changed *manually in the GUI*.
   Editing tools, `list_ontologies`, and `load_ontology` give the LLM no way to select which loaded
   ontology to edit. *Fix:* a `set_active_ontology` tool; `load_ontology`/`add_import` options to
   resolve/load **without** changing the active ontology.

2. **Silent phantom-entity minting with no signal in write results.** Every resolver mints a brand-new
   empty entity when an operand is an absolute IRI not yet present; a wrong/typo'd IRI quietly
   fabricates a phantom and the write-tool result does **not** say so. Only `preview_changes`'
   `new_entities` reveals it. *Fix:* echo newly-created entities in every write tool's result (like
   preview), and/or a strict mode that errors instead of minting.

3. **No batch / transactional apply.** `preview_changes` accepts an `operations[]` batch, but applying
   is one axiom per call. A faithful term took ~10 calls; the 5-class module ~80. There is no
   "apply this previewed batch." *Fix:* an `apply_changes`/`add_axioms` tool taking the same
   `operations[]` array.

4. **Manchester rejects full IRIs in compound expressions.** `<http://…/Identifier> and (…)` fails
   (`Expected: Class name / Object property name …`); only bare single-operand full IRIs resolve. So
   any restriction/defined-class axiom is unwritable until its referenced terms are loaded and
   resolvable by short name — coupling expressiveness to import resolution. *Fix:* let the Manchester
   entity checker resolve `<IRI>` forms (and optionally bare full IRIs) to existing/closure entities.

5. **`create_class` IRI minting fights the IOF decoupled-IRI convention.** With no explicit `iri`,
   `create_class("manufacturing operator")` minted `…/biopharma/Agent/manufacturing_operator`
   (ontology namespace, **not** the `construct` namespace the term belongs to), underscored the
   spaces, and auto-added `rdfs:label "manufacturing_operator"^^xsd:string` / for CamelCase names a
   CamelCase `xsd:string` label with **no language tag**. Faithful terms required passing explicit
   IRIs and then `remove_axiom`+`add_annotation` to fix every auto-label. *Fix:* configurable term
   namespace for `create_*`; lang-tagged labels (or no auto-label); a `set_label`/relabel upsert.

### Medium

6. **No atomic create-with-annotations / create-property-with-domain-range.** Building one property =
   `create_entity` + domain + range + sub-property = 4 calls; a class with its annotation suite ~10.
   No composite creator.

7. **No relabel / upsert anywhere.** Correcting the auto-label needs exact `remove_axiom` + `add`.
   `rename_entity` changes the IRI, not the label.

8. **Bare prefix map; no prefix or term-namespace management.** The fresh ontology exposes only
   owl/rdf/xml/xsd/rdfs; there is no tool to register `iof-av`/`skos`/`dcterms` prefixes or to set the
   entity-minting namespace. (CURIEs still resolved here because the imports closure declared the
   properties, but an offline/unloaded author has no recourse.)

9. **`load_ontology`/`add_import` import resolution is a manual two-step dance.** `add_import` is
   declaration-only; resolving needs a separate `load_ontology`/`merge`, or a catalog mapping no tool
   can create. No `add_import(document=path)` / catalog-registration affordance.

10. **Reads return rendered strings, not structured operands/IRIs.** `get_entity_context` neighbours
    and `get_axioms_for_entity` are Manchester/label strings with no `{iri}` or structured operands,
    so round-trip read-modify-write of a restriction means string-parsing.

### Low

11. **`undo_change`/`redo_change` are opaque** — "Undid the last change" with no description of what
    was reverted; single-step over a GUI-shared stack.

12. **`validate_ontology` never runs the reasoner and skips anonymous (restriction) superclasses** —
    a clean audit says nothing about satisfiability or the restriction modelling that carries most of
    the semantics. (Survey-level; reasoner is a separate call.)

## Proposed 0.2.1 scope

Additive, backward-compatible. Recommended tiers:

- **Tier 1 (unblockers):** `set_active_ontology`; surface minted/new entities in write-tool results;
  `apply_changes` batch apply.
- **Tier 2 (ergonomics):** `create_*` term-namespace + lang-tagged labels / `set_label`; Manchester
  `<IRI>` resolution; `add_import`/`load_ontology` resolve-without-stealing-active (+ document/path).
- **Tier 3 (polish):** prefix management; undo/redo descriptions; structured operands in reads;
  optional reasoner signal in `validate_ontology`.

## Implemented in 0.2.1

All three tiers shipped (additive, backward-compatible). Gap → fix:

| # | Gap | 0.2.1 change |
| --- | --- | --- |
| 1 | No way to switch the active ontology | **`set_active_ontology`** tool; **`load_ontology` `keep_active`** and **`add_import` `document=…`** resolve imports without stealing the active ontology |
| 2 | Silent phantom minting | Write tools (`add_axiom`, `add_subclass_of`, `add_annotation`) and `apply_changes` report `new_entities`; new **`strict`** flag refuses to mint from an unrecognized IRI/name |
| 3 | No batch apply | **`apply_changes`** applies a previewed `operations[]` batch in one call |
| 4 | Manchester rejects full IRIs | `resolveClassExpression` now falls back to the OWL API Manchester parser, which accepts `<IRI>` operands inside compound expressions |
| 5 | `create_class` namespace + auto-label | `create_class`/`create_entity` gain **`namespace`**, **`label`**, **`label_lang`**, **`no_label`** |
| 6 | No atomic create-with-domain/range | (Partially) `apply_changes` batches the property axioms; create-with-label is atomic via the create_* options |
| 7 | No relabel/upsert | **`set_label`** (removes same-language labels, adds the new one) |
| 8 | Bare prefix map | **`set_prefix`** registers/updates a prefix in the active ontology's format |
| 9 | Import-resolution two-step dance | `add_import` `document=…` + `load_ontology` `keep_active` close it |
| 10 | Rendered-string reads | `get_entity_context` neighbours are `{iri, display, type}` (named) / `{expression, anonymous:true}` |
| 11 | Opaque undo/redo | `undo_change`/`redo_change` report `axioms_before`/`axioms_after`/`net_axiom_change` |
| 12 | Validate never reasons | `validate_ontology` `with_reasoner=true` adds the consistency / unsatisfiable-class verdict |

Tool count: **37 → 41** (`set_active_ontology`, `apply_changes`, `set_label`, `set_prefix`).

## Live re-validation (0.2.1, after deploy)

Re-ran the workflow against the deployed build. Confirmed working live: `add_import(document=…)`
resolves Core's closure **and keeps Agent active** (the #1 gap — no GUI click); `set_active_ontology`;
`apply_changes` (a 21-op then 11-op batch incl. the nested BFO restriction, with correct `new_entities`);
`strict` (refused a typo'd `…/Agnet` IRI); Manchester `<IRI>` operands in a compound expression;
`create_class namespace=…` + `set_label` upsert (`removed_previous:1`); `set_prefix`; `validate_ontology
with_reasoner` (consistent, 0 unsatisfiable); structured `get_entity_context`; reasoning.

**One bug found and fixed during re-validation:** loading a document with `keep_active` (incl.
`add_import document=…`) brought the import into the active ontology's closure but left Protégé's
**entity finder stale** — imported terms didn't resolve by name (`search_entities`, Manchester
expressions) until the active ontology was toggled by hand. Root cause: keeping the active ontology
means `setActiveOntology` is a no-op, and Protégé recomputes its active-ontologies (imports-closure)
**cache** — which the finder/renderers read — only on a *real* active-ontology switch, not on a bare
`ACTIVE_ONTOLOGY_CHANGED` event. Fix: `OntologyDocumentTools.attach` activates the loaded primary and
then, for `keep_active`, switches **back** to the prior target — two real `setActiveOntology` calls
that force the closure recompute (replicating the manual round-trip that resolved it live). Rebuilt +
redeployed.
