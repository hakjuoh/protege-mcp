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

- **Transport.** The embedded MCP server binds **loopback only** (`127.0.0.1`) and authenticates every
  `/mcp` request with a bearer token (OAuth 2.0 + PKCE `S256`, or a static fallback token). Bearer-token
  (not cookie) auth is inherently resistant to CSRF and DNS-rebinding.
- **Editing.** All ontology reads/edits flow through Protégé's `OWLModelManager`, join the shared undo
  stack, and can be gated by a **read-only** switch and an optional **write-confirmation** dialog. The
  confirmation fails **closed**.
- **LLM egress.** The in-app *Ontology Assistant* spawns the user's local `claude` / `codex` CLI. Prompts
  and any attached content are sent to the third-party LLM provider the user has configured, and only
  after the user has **consented**. The plugin stores no API key; it reuses the CLI's own login. The MCP
  bearer token is passed to the CLI **out-of-band** (an environment variable for Codex, an owner-only
  `0600` config file for Claude) so it is not exposed on the process command line.

### In scope
Authentication/authorization bypass of `/mcp`; the OAuth/PKCE flow; token handling and disclosure;
command/argument injection into the spawned CLIs; path traversal or over-broad directory grants via
attachments; any write that bypasses the read-only / confirmation gates.

### Out of scope
Attacks that require an already-compromised local account (the model is a single trusted desktop user);
the security of the third-party LLM providers themselves; and issues in Protégé or its bundled libraries
that are not reachable through this plugin (please report those upstream).
