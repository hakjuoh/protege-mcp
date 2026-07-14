# Third-party notices

Protégé MCP incorporates or, in its standalone CLI artifact, redistributes third-party software.
The authoritative copyright and license texts are included by those dependencies in their JAR
metadata and source distributions. Major runtime components include:

- OWLAPI 4.5 (LGPL-3.0 or Apache-2.0, as offered by the project)
- Apache Jena and Apache Commons components (Apache-2.0)
- Jackson, SnakeYAML, NetworkNT JSON Schema Validator, Guava, and Jetty (Apache-2.0 or EPL/Apache
  dual-license terms published by their respective projects)
- Model Context Protocol Java SDK and CommonMark Java (their published open-source licenses)
- Eclipse RDF4J/Rio components transitively distributed by OWLAPI (their published BSD/EPL terms)

This notice is informational and does not replace any license file packaged with a dependency.
Run `mvn dependency:tree` for the exact dependency set of a source revision.
