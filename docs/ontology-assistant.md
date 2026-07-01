---
title: Ontology Assistant
nav_order: 4
---

# Ontology Assistant
{: .no_toc }

A chat assistant **inside Protégé** that reads and edits your live ontology — conversationally, with no
external client to configure.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What it is

The **Ontology Assistant** (introduced in `0.3.0`) is a chat tab and view built into the plugin.
Instead of calling a model API directly, it **drives a coding-agent CLI you already have installed** —
[Claude Code](https://docs.claude.com/en/docs/claude-code) (`claude`) or
[OpenAI Codex](https://github.com/openai/codex) (`codex`) — and points that CLI back at **this plugin's
own MCP server**.

So the assistant reads and edits through **exactly the same tools** an external MCP client uses:

- Changes appear in the Protégé **GUI** immediately and join the **Edit ▸ Undo** stack.
- The **read-only** and **confirm-each-write** gates still apply — the chat cannot escalate past them.
- **No API key is stored by Protégé.** Each CLI uses your existing login (Claude subscription/keychain;
  `codex login`).

## Prerequisites

- **Install and log in to at least one CLI:**
  - Claude Code — <https://docs.claude.com/en/docs/claude-code> (then `claude` works in your terminal), or
  - Codex — <https://github.com/openai/codex> (`codex login`).
- The **MCP server must be running.** The chat starts it automatically on your first message.

Only CLIs that are actually detected on your system are offered as providers.

## Using it

1. Open the **Ontology Assistant** tab (a top-level tab), or add the **Ontology Assistant** view to any
   tab via **Window ▸ Views**.
2. **Pick a provider** — *Use Claude* / *Use Codex* (only installed CLIs appear) — and optionally a
   **model** (blank uses the CLI's own default).
3. Type a request and press **Enter** (**Shift+Enter** inserts a newline). Start with a read, then an
   edit:
   - *"What classes are in this ontology?"*
   - *"Create a class FooBar under Thing with label 'Foo Bar'."*
4. Watch the reply **stream** in. **Stop** cancels mid-turn. **Edit ▸ Undo** reverts any edit it made.

### Attachments and long pastes

The chat input accepts more than plain text (added in `0.3.1`):

- **Long pasted text** is compacted in the input as `[Pasted content #N: … chars]`, while the full body
  still reaches the assistant. Large bodies are buffered to a temp file and referenced by path, so a
  paste can never overflow the command line.
- **Files and images** — via the **Attach** button, **drag-and-drop**, or **clipboard paste** — become
  placeholders such as `[Image #1]` or `[File #2: name]`. Codex receives images natively (`--image`);
  Claude is granted read access to the file (`--add-dir`).
- Deleting the placeholder before sending removes the attachment (backspace next to a placeholder
  deletes the whole token); a placeholder edited away before **Send** is reported and not sent.

## Privacy & cost

{: .warning }
> The chat sends your prompts, any attachments/pasted content, **and the ontology content the assistant
> reads** to your model provider **via the CLI**. A one-time banner discloses this before the first
> send.

- Each attached file or image is copied into its **own private temp folder**, and only that single-file
  copy is exposed to the CLI — never the rest of its containing folder. The temp copies are deleted when
  the turn finishes.
- **Cost and rate limits** are governed by your CLI's own subscription/account, not by Protégé.
- **Edits obey the MCP preferences** (read-only, confirm-each-write). A **Confirm each edit** checkbox
  in the panel toggles confirmation live.

## Settings (Settings ▸ Ontology Assistant)

- **CLI path overrides** — if Protégé was launched from the macOS **Dock/Finder**, it may not inherit
  your shell `PATH`, so a CLI can fail to resolve. Set an explicit path to the `claude` / `codex`
  executable here. The panel shows what was detected.
- **Reset egress consent** — re-arm the one-time disclosure banner.
- **Show thinking** — optionally include the model's reasoning in the transcript.

## Troubleshooting

- **No providers offered** — no CLI was detected. Install one, or set its explicit path in
  **Settings ▸ Ontology Assistant** (common when Protégé is launched from the Dock/Finder without your
  shell `PATH`).
- **"Not logged in" / auth errors** — log in in your terminal first (`claude`, or `codex login`). The
  plugin spawns the CLI through a login shell so it can pick up your environment.
- **Edits don't apply** — check **read-only** mode and any pending **confirm-each-write** dialog
  (Settings ▸ MCP), and the **Confirm each edit** checkbox in the panel.
