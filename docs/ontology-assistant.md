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
- Each turn uses its own **short-lived, non-refreshable MCP credential**, attributed to the selected
  provider and an opaque window/chat identity. It is revoked when the turn finishes, is stopped, fails
  to launch, or the view closes; it is never persisted or reused as the manual static admin token. Its
  30-minute lease is renewed while the CLI process remains active, so a legitimate long turn keeps working;
  an orphaned credential expires without renewal.
- **No API key is stored by Protégé.** Each CLI uses your existing login (Claude subscription/keychain;
  `codex login`).
- **Axiom edits default to the transactional change-set path.** Each Claude turn appends a write-workflow
  steering system prompt, and each new Codex thread opens with the same preamble (a resumed thread
  already carries it). It tells the model to preview each axiom edit with `preview_change_set` — or
  `create_terms`/`create_properties` with `preview=true` — review the isolated policy/QC gate, and only
  then `commit_change_set` against the exact revision it previewed. High-level operations without a
  change-set equivalent (rename/move/deprecate/delete, document operations) keep their own previews, and
  the direct axiom tools remain a disclosed fallback reserved for servers without the change-set tools
  or an edit you explicitly direct; the steering grants no bypass — the read-only and confirm-each-write
  gates apply to every write tool unchanged, whether the model follows it or not (the isolated QC and
  revision re-checks are what the change-set path adds).

## Prerequisites

- **Install and log in to at least one CLI:**
  - Claude Code — <https://docs.claude.com/en/docs/claude-code> (then `claude` works in your terminal), or
  - Codex — <https://github.com/openai/codex> (`codex login`).
- The **MCP server must be running.** The chat starts it automatically on your first message — unless
  you stopped the server yourself with **Stop** in the **MCP Server** view; an explicit Stop blocks
  every automatic start (added in `0.5.0`) until you press **Start** again.

Only CLIs that are actually detected on your system are offered as providers.

## Using it

1. Open the **Ontology Assistant** tab (a top-level tab), or add the **Ontology Assistant** view to any
   tab via **Window ▸ Views**.
2. **Pick a provider** — *Use Claude* / *Use Codex* (only installed CLIs appear) — and optionally a
   **model** (blank uses the CLI's own default). The transcript is one continuous conversation across
   providers. Each provider keeps its own native CLI session ID; when you switch, turns that session
   missed are handed off with your next message. Switching Codex → Claude → Codex therefore resumes
   the original Codex thread and catches it up with the Claude exchange. Changing models also keeps
   the current session; selecting the already-active model again has no effect.
3. Type a request and press **Enter** (**Shift+Enter** inserts a newline). Start with a read, then an
   edit:
   - *"What classes are in this ontology?"*
   - *"Create a class FooBar under Thing with label 'Foo Bar'."*
4. Watch the reply **stream** in. Replies render as **Markdown** while they stream (added in `0.5.0`) —
   headings, bold/italic, lists, quotes, inline code and code blocks, tables, and links (`http(s)` links
   are clickable). **Stop** cancels mid-turn. **Edit ▸ Undo** reverts any edit it made.
   Styled rendering is lossy to plain select-and-copy, so each reply keeps its **original Markdown**
   (also `0.5.0`): a small **copy button** under the turn's closing reply puts that markup on the
   clipboard, and a **right-click menu** on the transcript offers **Copy** (the selection, as displayed)
   and **Copy message as Markdown** for any assistant message under the pointer.
5. Two checkboxes sit next to **New chat** in the panel:
   - **Confirm each edit** — require a confirmation dialog before any edit applies (this is the MCP
     server's confirm-writes setting, toggled live).
   - **Show reasoning** — ask the CLI for the model's reasoning and show it in the transcript (gray
     italics). Takes effect from your next message. Current CLIs send no reasoning unless asked, so
     turning this on adds the provider's own opt-in flag to the run; on a much older Claude CLI that
     doesn't know the flag, the turn fails with an "unknown option" error — untick the box to send
     without it.

**New chat** clears the shared transcript and all provider session IDs for this view. Conversation
state is currently kept in memory; closing the view or restarting Protégé starts with an empty
transcript rather than silently resuming hidden CLI history. Cross-provider handoff is bounded and
keeps the newest missing turns if an unusually large exchange must be compacted.

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
> reads** to your model provider **via the CLI**. When you switch providers, the conversation turns
> that the newly active provider missed are sent to it as handoff context. This disclosure is also
> available under **Settings ▸ Ontology Assistant ▸ Privacy**; sending does not open a modal dialog.

- Each attached file or image is copied into its **own private temp folder**, and only that single-file
  copy is exposed to the CLI — never the rest of its containing folder. The temp copies are deleted when
  the turn finishes.
- **Cost and rate limits** are governed by your CLI's own subscription/account, not by Protégé.
- **Edits obey the MCP preferences** (read-only, confirm-each-write). A **Confirm each edit** checkbox
  in the panel toggles confirmation live.
- Assistant credentials never carry server-admin, external-filesystem, network, or unrestricted
  local-admin authority. Their project/ontology write profile is controlled separately in
  **Settings ▸ Ontology Assistant**; disabling it leaves read-only chat available.

## Settings (Settings ▸ Ontology Assistant)

- **CLI path overrides** — if Protégé was launched from the macOS **Dock/Finder**, it may not inherit
  your shell `PATH`, so a CLI can fail to resolve. Set an explicit path to the `claude` / `codex`
  executable here. The panel shows what was detected.
- **Assistant access** — choose whether per-turn credentials may use the bounded ontology/project write
  profile. Disable it for read-only Assistant use. The MCP server's global read-only setting always wins.
- **Privacy** — a non-blocking summary of what is sent to the selected model provider.

(The **Show reasoning** and **Confirm each edit** toggles live in the chat panel itself, next to
**New chat** — not in this settings page.)

## Troubleshooting

- **No providers offered** — no CLI was detected. Install one, or set its explicit path in
  **Settings ▸ Ontology Assistant** (common when Protégé is launched from the Dock/Finder without your
  shell `PATH`).
- **"Not logged in" / auth errors** — log in in your terminal first (`claude`, or `codex login`). The
  plugin spawns the CLI through a login shell so it can pick up your environment.
- **Edits don't apply** — check the Assistant access setting, MCP **read-only** mode, and any pending
  **confirm-each-write** dialog (Settings ▸ MCP), plus the **Confirm each edit** checkbox in the panel.
- **The chat says the server is stopped** — you stopped it explicitly (**Stop** in the **MCP Server**
  view), which blocks every automatic start, the chat's included. Press **Start** in the view to bring
  it back.
