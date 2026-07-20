# Third-party notices

Protégé MCP incorporates or, in its standalone CLI artifact, redistributes third-party software.
The authoritative copyright and license texts are included by those dependencies in their JAR
metadata and source distributions. Major runtime components include:

- OWLAPI 4.5 (LGPL-3.0 or Apache-2.0, as offered by the project)
- HermiT 1.3.8.431 (LGPL-3.0-or-later; Oxford University Computing Laboratory). The executable excludes
  HermiT's unmaintained root-packaged JAutomata implementation and supplies the three `rationals` API types
  HermiT links through AutomataLib 0.12.1 (Apache-2.0; TU Dortmund University and contributors). The
  unmodified corresponding HermiT source JAR still includes the excluded JAutomata source, whose LGPL-2.1
  status is recorded by the archived upstream readme at
  `docs/evidence/hermit-upstream-readme-65d3890.txt`. The separately resolved
  `dk.brics.automaton` 1.11-8 is
  BSD-3-Clause (Copyright 2001-2011 Anders Møller / Aarhus University). Apache Axiom's runtime closure —
  Geronimo JavaMail and StAX specifications, Apache Mime4j, and Woodstox core — uses Apache-2.0. Jaxen
  (Copyright 2003-2006 The Werken Company) and Stax2 API (Copyright 2004-2010 Woodstox Project) use
  BSD-3-Clause. The existing `jcl-over-slf4j` bridge supplies the Commons Logging API under its published
  license without redistributing HermiT's older implementation.
- Apache Jena and Apache Commons components (Apache-2.0)
- Jackson, SnakeYAML, NetworkNT JSON Schema Validator, Guava, and Jetty (Apache-2.0 or EPL/Apache
  dual-license terms published by their respective projects)
- Model Context Protocol Java SDK and CommonMark Java (their published open-source licenses)
- Eclipse RDF4J/Rio components transitively distributed by OWLAPI (their published BSD/EPL terms)
- Titanium RDF Dataset Canonicalization 2.0.0 and its RDF API/N-Quads components (Apache-2.0). This
  Java-17-compatible line is used for W3C RDFC-1.0; Titanium 3.0.0 requires Java 21.

The reasoner-bearing release also ships the GPL-2.0, LGPL-2.1, GPL-3.0, LGPL-3.0, Apache-2.0, and exact
dk.brics/Jaxen/Stax2 BSD notices, the unmodified HermiT source JAR, and the relinking instructions in
`docs/headless-relinking.md`. This notice is informational and does not replace those license files or any
license packaged with a dependency. Run `mvn dependency:tree` for the exact dependency set of a source
revision.
