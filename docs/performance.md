---
title: Performance regression tests
parent: Contributing
nav_order: 3
---

# Performance regression tests

The release benchmark uses deterministic generated ontologies at three representative sizes. The fixture
definition is versioned in `performance/fixtures-v1.json`. Every generated graph combines a branching
taxonomy with existential restrictions, disjointness, inverse/transitive/symmetric object properties,
data properties, and named individuals; the medium and large graphs also include equivalent definitions
and general class inclusions. Each fixture measures the same six existing paths:

- isolated project/QC snapshot capture;
- HermiT class-hierarchy and class-assertion reasoning;
- asserted SPARQL cache snapshot construction and Turtle serialization;
- Jena SHACL validation;
- asserted semantic diff; and
- verified serialization with an isolated round-trip reload.

`performance/materialization-v1.json` separately pins the 0.8 materialization ceiling: 50,000 produced
axioms, at most 512 MiB sampled incremental peak heap, a 500-axiom live batch and 100 ms model-thread
stall ceiling, five-second measured cancellation effectiveness, and the common 2.0x/250 ms regression
rule. `MaterializationScaleTest` exercises the complete 50,000-axiom category and records completeness,
elapsed time, sampled peak heap, and cancellation evidence. `MaterializationLiveScaleTest` invokes the
production Swing EDT gateway and exact Protege adapter commit core, including authorization rechecks,
collision analysis, revision fingerprints, listeners, history, and the single-Undo assertion. The fixture
uses a test OWLModelManager adapter; the separate live integration harness covers packaged Protege/OSGi wiring.

`performance/baseline-v1.json` records the reference measurements, reference machine, warm-up/sample
counts, and the allowed regression factor. Fast operations also receive a small noise floor. These values
are regression guards, not hardware-independent latency promises or end-user service-level objectives.
Changing a fixture or reference requires a reviewed baseline update with a retained measurement artifact.

The opt-in benchmark is excluded from normal `mvn verify`. Run the same enforced gate locally with JDK 17:

```bash
mvn -B -pl plugin -am \
  -Dtest=PerformanceBaselineTest,MaterializationScaleTest,MaterializationLiveScaleTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprotege.performance=true \
  -Dprotege.performance.enforce=true \
  test
```

The machine-readable results are written to `plugin/target/performance-results.json`,
`core/target/materialization-performance-results.json`, and
`plugin/target/materialization-live-performance-results.json`. GitHub Actions runs all three gates weekly
and for every release, retaining all result files so a failure can be compared with prior measurements.
