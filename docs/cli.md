---
title: Headless CLI
nav_order: 5
---

# Headless CLI
{: .no_toc }

Version 0.6.0 ships a separately tested Java 17 artifact,
`protege-mcp-cli-<version>-all.jar`. It embeds OWLAPI, contains no Protégé editor classes, and does not
need a local Maven repository after download. The existing `protege-mcp-<version>.jar` remains the
Protégé OSGi plugin and is not a standalone launcher.

## Commands

```bash
java -jar protege-mcp-cli-0.6.0-all.jar --version
java -jar protege-mcp-cli-0.6.0-all.jar validate-policy --project .protege-mcp/project.yaml
java -jar protege-mcp-cli-0.6.0-all.jar diff --left previous.ttl --right current.ttl
```

`validate-policy` prints the resolved path/root, canonical policy digest, and structured validation
issues as JSON. It validates syntax, semantic references, and local assets; installed Protégé reasoner
availability is deliberately deferred to the adapter that executes project QC.

`diff` privately loads both ontology documents and prints the same asserted semantic categories used by
the plugin's `semantic_diff` core: headers/imports, entities, rename candidates, annotation changes,
asserted axiom groups, and conservative compatibility classification. The comparison is asserted-only,
so the CLI never fetches an `owl:imports` target while loading either document: every declared import is
satisfied by one shared private empty placeholder instead (a single temporary file per document, however
many imports are declared — some OWLAPI parsers cannot tolerate a wholly missing import), its axioms are
never loaded, and it is reported in the top-level `left_unresolved_imports` and
`right_unresolved_imports` arrays. This keeps the command deterministic, prevents network access, and
makes the deliberately omitted imports closure visible in its output.

Exit code `0` means success, `2` means usage/configuration error, and `3` means an execution or document
loading failure. Release assets include SHA-256 sidecars, the project license, and third-party notices.

The CLI surface is intentionally a 0.6 prototype. Release orchestration, import-lock writing, and stdio
MCP serving remain roadmap work rather than hidden or partially implemented commands.
