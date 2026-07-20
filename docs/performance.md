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

`performance/baseline-v1.json` records the reference measurements, reference machine, warm-up/sample
counts, and the allowed regression factor. Fast operations also receive a small noise floor. These values
are regression guards, not hardware-independent latency promises or end-user service-level objectives.
Changing a fixture or reference requires a reviewed baseline update with a retained measurement artifact.

The opt-in benchmark is excluded from normal `mvn verify`. Run the same enforced gate locally with JDK 17:

```bash
mvn -B -pl plugin -am \
  -Dtest=PerformanceBaselineTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprotege.performance=true \
  -Dprotege.performance.enforce=true \
  test
```

The machine-readable result is written to `plugin/target/performance-results.json`. GitHub Actions runs
the gate weekly and for every release, retaining that file as a workflow artifact so a failure can be
compared with prior measurements.
