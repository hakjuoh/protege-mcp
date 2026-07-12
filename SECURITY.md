# Security Policy

## Supported versions

Protégé MCP is pre-1.0 and ships as a single plugin JAR. Security fixes are made against the latest
released `0.x` line only; please upgrade to the newest release before reporting.

| Version | Supported |
| ------- | --------- |
| latest `0.x` | ✅ |
| older `0.x`  | ❌ (upgrade first) |

## Reporting a vulnerability

Please report suspected vulnerabilities **privately** — do not open a public issue for a security
problem.

- Use GitHub's **[Report a vulnerability](https://github.com/hakjuoh/protege-mcp/security/advisories/new)**
  (repository **Security** tab → *Advisories* → *Report a vulnerability*). This opens a private
  advisory visible only to the maintainers.

Please include: the affected version, the platform and how Protégé was launched, a description of the
issue, and a proof-of-concept or reproduction steps if you have one. You will get an acknowledgement,
and a fix or mitigation plan once the report is triaged.

## Security model and scope

Protégé MCP is designed for a **single user on their own desktop**. Understanding this scope helps
frame what is and isn't in-scope:

- **Transport.** The MCP endpoint binds **loopback** (`127.0.0.1`) **by default** and authenticates
  every `/mcp` request with a bearer token (OAuth 2.0 + PKCE `S256`, or a static fallback token). Since
  0.5.0 a bind-address preference can expose a non-loopback interface; it is opt-in, warned about in the
  UI, and OAuth **authorization** (the browser consent flow) stays restricted to **same-machine**
  clients even then. Bearer-token (not cookie) auth is inherently resistant to CSRF and DNS-rebinding.
- **Broker (0.5.0).** By default the configured port is owned by a small standalone **broker** process
  shared by every Protégé window and instance. It terminates auth exactly as above and proxies each MCP
  session to a per-window backend that stays on **loopback regardless** of the bind-address preference;
  its control plane (`/internal/*`) is guarded by an owner-only file secret. Broker state lives under
  `~/.protege-mcp/` (`0700`), including OAuth client registrations and tokens persisted to `oauth.json`
  — superseded, abandoned, or long-inactive registrations are swept automatically.
- **Editing.** All ontology reads/edits flow through Protégé's `OWLModelManager`, join the shared undo
  stack, and can be gated by a **read-only** switch and an optional **write-confirmation** dialog. The
  confirmation fails **closed**.
- **LLM egress.** The in-app *Ontology Assistant* spawns the user's local `claude` / `codex` CLI. Prompts
  and any attached content are sent to the third-party LLM provider the user has configured, and only
  after the user has **consented**. The plugin stores no API key; it reuses the CLI's own login. The MCP
  bearer token is passed to the CLI **out-of-band** (an environment variable for Codex, an owner-only
  `0600` config file for Claude) so it is not exposed on the process command line.

### In scope
Authentication/authorization bypass of `/mcp` (standalone or through the broker); the broker's
`/internal` control-plane guard; the OAuth/PKCE flow; token handling and disclosure (including
`~/.protege-mcp/oauth.json`); command/argument injection into the spawned CLIs; path traversal or
over-broad directory grants via attachments; any write that bypasses the read-only / confirmation gates.

### Out of scope
Attacks that require an already-compromised local account (the model is a single trusted desktop user);
the security of the third-party LLM providers themselves; and issues in Protégé or its bundled libraries
that are not reachable through this plugin (please report those upstream).

## Third-party dependency CVEs

Dependency scanners (e.g. GitHub Dependabot) will flag CVEs against libraries listed in `pom.xml`. Not
every alert describes an exploitable path in this plugin, because the build has two dependency layers:

- **`provided` (runtime-supplied by Protégé, never embedded).** `owlapi`, `guava`, `slf4j` are supplied
  by the running Protégé/OWLAPI OSGi runtime and pinned via `Import-Package` version ranges. They are
  **not** shipped in the released JAR, and the plugin cannot change which version the platform loads.
- **`compile` (embedded/inlined into the bundle).** The MCP + Jetty + Jackson + Reactor + Jena stack is
  inlined; these are the versions this project actually distributes and can patch.

For a `provided` dependency, bumping its `pom.xml` version does not change the shipped artifact or the
runtime, and widening its `Import-Package` range beyond what the platform exports breaks OSGi resolution.
Such alerts are triaged by **reachability** (does the plugin, or an API it drives, invoke the vulnerable
code?) and dismissed as *"vulnerable code is not actually used"* when unreachable.

**Guava (`com.google.guava:guava` 18.0, `provided`).** Supplied at runtime by Protégé's own
`guava.jar` (18.0.0) and pinned to `[18.0,19)`; not embedded here. The plugin uses guava only through
OWLAPI 4.5.x return types (`Optional`, `Multimap`). Three alerts were reviewed and dismissed as
unreachable:

| CVE | Vulnerable API | Why unreachable here |
| --- | --- | --- |
| CVE-2023-2976 | `Files.createTempDir()` | Plugin creates temp files via JDK `java.nio.file.Files.createTempFile`, never guava. |
| CVE-2020-8908 | `FileBackedOutputStream` | Never referenced in source or via the OWLAPI APIs the plugin drives. |
| CVE-2018-10237 | `AtomicDoubleArray` / `CompoundOrdering` deserialization | The plugin does no Java deserialization (no `ObjectInputStream`); the MCP transport is JSON over a locally-bound servlet (loopback by default) with no Jetty session store. |

The real remediation ("run on a newer guava") belongs to Protégé Desktop and is outside this plugin's
control. Note the released JAR does embed *relocated/shaded* guava copies under
`com.github.jsonldjava.shaded.*` (jsonld-java) and `org.apache.jena.ext.*` (Jena) — distinct Maven
coordinates that these guava alerts do not target and that have no callers outside their own shaded
packages.
