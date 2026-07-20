---
title: Rebuilding the headless CLI with a modified HermiT
nav_exclude: true
---

# Rebuilding the headless CLI with a modified HermiT

The standalone `protege-mcp-cli-<version>-all.jar` links HermiT `1.3.8.431` into the shaded Java
executable. HermiT is LGPL-3.0-or-later. The executable excludes its unmaintained root `rationals`
JAutomata implementation and provides HermiT's linked three-type API through AutomataLib `0.12.1`
(Apache-2.0). The project ships the corresponding unmodified HermiT source JAR—which still contains the
excluded LGPL-2.1 JAutomata source—and the applicable license texts with every reasoner-bearing release.
See `THIRD_PARTY_NOTICES.md` for the exact dependency and evidence boundary.

Protégé MCP does not prohibit reverse engineering needed to debug a modified LGPL component. HermiT and
OWLAPI packages are deliberately not relocated during shading.

## Rebuild with a compatible modified reasoner

1. Check out the exact Protégé MCP release tag whose CLI you want to reproduce.
2. Build your modified HermiT against OWLAPI 4.x. Keep its binary and corresponding source JAR.
3. Install both under a distinct local version:

   ```bash
   mvn install:install-file \
     -Dfile=/path/to/org.semanticweb.hermit-modified.jar \
     -Dsources=/path/to/org.semanticweb.hermit-modified-sources.jar \
     -DgroupId=net.sourceforge.owlapi \
     -DartifactId=org.semanticweb.hermit \
     -Dversion=1.3.8.431-local1 \
     -Dpackaging=jar
   ```

4. Rebuild and run the shaded-distribution probes with the property override:

   ```bash
   mvn -Dhermit.version=1.3.8.431-local1 -pl cli -am clean verify
   ```

The rebuilt executable is `cli/target/protege-mcp-cli-<version>-all.jar`. The build fails if the modified
artifact pulls a second OWLAPI or Protégé runtime, lacks the required datatype dependencies, restores the
legacy JAutomata implementation, contains inert nested dependency JARs, cannot classify the smoke fixtures,
or lacks its corresponding source JAR.

This procedure changes only the standalone/headless reasoner. The Protégé plugin continues to use the
reasoner selected and installed in Protégé, with the accepted configuration-parity rules.
