# License-text evidence for the headless reasoner

These verbatim license texts accompany the reasoner-bearing standalone CLI. They do not
replace the project's own BSD-2-Clause [`LICENSE`](../LICENSE).

| File | Applies to | Canonical text source |
| --- | --- | --- |
| `GPL-2.0.txt` | GPL text referenced by LGPL-2.1 | <https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt> |
| `LGPL-2.1.txt` | JAutomata source retained in HermiT's corresponding source archive (excluded from the executable) | <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt> |
| `GPL-3.0.txt` | GPL text referenced by LGPL-3.0 | <https://raw.githubusercontent.com/spdx/license-list-data/5bf6d9610255540bfbee6890765a616042bf1e11/text/GPL-3.0-only.txt> |
| `LGPL-3.0.txt` | HermiT 1.3.8.431 | <https://raw.githubusercontent.com/spdx/license-list-data/5bf6d9610255540bfbee6890765a616042bf1e11/text/LGPL-3.0-only.txt> |
| `Apache-2.0.txt` | AutomataLib, Axiom, and the Apache/Geronimo/Woodstox-core runtime closure | <https://www.apache.org/licenses/LICENSE-2.0.txt> |
| `dk.brics.automaton-BSD.txt` | dk.brics.automaton 1.11-8 | Maven source JAR header / <https://www.brics.dk/automaton/> |
| `Jaxen-BSD.txt` | Jaxen 1.1.4 | <https://raw.githubusercontent.com/jaxen-xpath/jaxen/b2a0c7c990f74cf86a8855ed58cae883b4bf5f49/LICENSE.txt> |
| `Stax2-BSD.txt` | Stax2 API 3.1.1 | `META-INF/LICENSE` in the published JAR |

HermiT's source headers state LGPL-3.0-or-later. Its
[archived upstream readme](../docs/evidence/hermit-upstream-readme-65d3890.txt) identifies the bundled
JAutomata fork as LGPL-2.1; the Maven source JAR contains the complete `rationals` source tree. See
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) and the
[relinking guide](../docs/headless-relinking.md) for the full evidence and rebuild path.
