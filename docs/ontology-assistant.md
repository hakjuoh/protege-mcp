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
- Each turn uses its own **short-lived, single-turn MCP credential**, attributed to the selected
  provider and an opaque window/chat identity. It is revoked when the turn finishes, is stopped, fails
  to launch, or the view closes; it is never persisted or reused as the manual static admin token, and it
  carries no refresh token — nothing can be exchanged for a fresh one. Its own 30-minute lease is extended
  while the CLI process remains active, so a legitimate long turn keeps working; an orphaned credential
  expires without renewal.
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
   **model** and a **reasoning effort** (added in `0.8.0`). `(default)` in either picker sends no flag,
   so the CLI's own configured default decides. The model list is the catalog you maintain under
   **Settings ▸ Ontology Assistant ▸ Available models**; the effort list is that CLI's accepted levels,
   narrowed for Codex to the levels the selected model advertises when your local Codex metadata
   describes that model — including at **(default)**, where the model your `config.toml` names at the
   top level is the one that will run, however you spell that line as long as the value fits on it: the key
   bare or quoted, the value in any of TOML's four string syntaxes, and a basic string's escapes resolved
   the way Codex resolves them, so the escaped digit in `model = "gpt-\u0035"` runs gpt-5 — while a literal
   `'…'` value takes no escapes at all, and a `"""` or `'''` value whose body carries on to the next line,
   legal TOML though it is, is read as no configured model: every level is offered, just as for a profile,
   rather than a guess at what the continuation holds. A
   `config.toml` that instead keeps its model under a profile
   table, or that selects a profile — written `profile =` or `"profile" =`, which TOML treats as the same
   key, and with the value written any way TOML allows, `"""…"""` over two lines included — is left alone:
   every level is offered rather than a guess at which
   table wins — and only real top-level keys are read, so a `model =` line inside a multi-line
   string value, or inside a comment, is text rather than configuration, a `"""` the value escapes does
   not end it (a `'''` value, which takes no escapes, ends at the first one), an array written over several
   lines is a value even where one of its lines looks like a table header — and is over once its `]` is
   read, even where that bracket shares the line that ended a string element of it, so the `model` under
   such an array still narrows — and a stray byte-order mark
   hides neither a header nor a `profile =` line. For a model the metadata does
   not describe, every
   level current Codex releases accept is offered; one the model rejects fails that turn with the CLI's
   own error, and the transcript adds a note naming the effort picker as what asked for that value. Both
   selections are remembered per provider. The transcript is one continuous conversation across
   providers. Each provider keeps its own native CLI session ID; when you switch, turns that session
   missed are handed off with your next message. Switching Codex → Claude → Codex therefore resumes
   the original Codex thread and catches it up with the Claude exchange. Only a turn that completed
   counts as part of that conversation: a turn you stopped, or one that failed without answering, is left
   out, so the question it never answered is replayed with your next message instead of being treated as
   already seen. A turn that reported a failure, recovered and then answered does count — the reply is
   proof its provider took the question. Changing the model or the
   effort also keeps the current session; selecting the already-active value again has no effect.
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
     knows neither that flag nor the reasoning-effort flag, the turn fails with an "unknown option"
     error — untick the box, set the effort picker back to **(default)**, and resend, or update the CLI.
     A CLI that accepts a reasoning option and then ignores it — a `claude` release that warns about an
     effort value it does not know and answers at its own setting — still produces a complete reply, so
     the transcript quotes that warning as a note after the turn: the reply did not come from the value
     you picked. The warning has to be about the option this turn actually passed: an option name ends
     where the CLI's own ends, so a release that warns about some unrelated `--effortless` preset is not
     warning about `--effort`, and adds no note. That note only follows a turn that *completed*; a turn that failed reports the failure
     itself, and is never also told it ran on the CLI's default. Codex is read the same way: complaining
     about the effort override and answering anyway is reported as ignored, not as a refusal — including
     when the complaint arrives in the event stream and Codex then answers, since what decides the wording
     is whether a reply arrived, not whether something went wrong on the way to it. A reply is text you can
     read: a turn that produced nothing, or nothing but blank space, ran at no effort at all and is told
     nothing about the CLI's default — whether its stream said why, or it simply exited cleanly and
     silently, which is the one turn where this note is the only account of it there is, so it reports the
     reply as missing instead of describing one that never arrived.

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
- **Available models** (added in `0.8.0`) — the ordered model catalog the chat's picker offers, kept
  separately for Claude Code and Codex. The plugin hard-codes no model ids: until you first save a
  catalog it starts from the model you already had selected, followed by whatever that CLI's own local
  metadata names (Claude Code's `settings.json` / `settings.local.json`, Codex's `config.toml` and
  model cache). That `config.toml` is read as configuration rather than as prose, exactly as the effort
  narrowing above reads it: a `model =` line inside a comment or inside a multi-line string body seeds no
  model, and an escaped quote inside a value is a character of the id rather than the end of it. A
  profile's model is seeded every way TOML spells it — `profiles.work.model = "…"` and the inline
  `profiles.work = { model = "…" }` as much as `model = "…"` under `[profiles.work]` — and a model id used
  as a key seeds itself in every spelling TOML gives that entry: a key under a `[models]` table, quoted or
  bare and whether it is assigned or dotted into, a `[models."gpt-…"]` header, and the top-level
  `models."gpt-…" = { … }` and `models = { "gpt-…" = … }` forms. A key is read as an id only under `models`
  itself, so a table that keys real ids for another purpose (`[tui.model_availability_nux]` counts how often
  each was mentioned to you) offers none of them, an unquoted `gpt-5.5-codex` — two keys, not one — offers
  neither part, and `[[models]]` offers nothing at all. Metadata
  that is missing or oversized contributes nothing, as does a JSON file that cannot be parsed — including an
  entry whose model id, or a reasoning level whose name, is not a string in the metadata at all — so a CLI
  with neither source simply offers **(default)**. A Codex cache entry marked as unlisted, or as not served
  over the API, is left out; but "marked" means the field says so in the kind of value it is supposed to be,
  so an entry whose visibility is a number, or whose availability flag is not a boolean, is a cache written
  to a schema this release does not know and is offered like any other — a hidden model is the worse error,
  since nothing in the panel could ever explain the absence. A reasoning level also has to look like one: Codex takes
  the effort as a single bare config token, so a level whose name is a sentence, carries a newline, runs
  long, or steps outside plain ASCII letters and digits is not offered, and the effort list is bounded in
  length the same way the model list is — the picker is a list to choose from, not a rendering of whatever
  the metadata happens to hold. `config.toml` has no such all-or-nothing step: it is read
  line by line rather than parsed, so a value left unterminated seeds nothing itself while the assignments
  around it are still read, and no stray bracket empties the picker. Type an id and press **Add** (or **Enter**), or select a
  row and press **Update** (or **Enter**); every row carries an **X** to delete it, and a selected row
  also shows **↑** / **↓** immediately before **X** to move it. Each edit is only staged — the line under
  the field says so — and **nothing is stored until you press OK** in the Preferences dialog; **Cancel**
  discards the whole set of edits. The chat's picker follows the saved order, and an Ontology Assistant
  that is already open picks up the new list as soon as you press OK. A model you deleted is no longer
  selected anywhere: that provider falls back to **(default)** rather than quietly running the next turn
  on an id the catalog no longer offers. Saving an empty list is meaningful: only **(default)** remains,
  and turns then omit `--model` / `-m` entirely so the CLI's own configuration chooses.
- **Assistant access** — choose whether per-turn credentials may use the bounded ontology/project write
  profile. Disable it for read-only Assistant use. The MCP server's global read-only setting always wins.
- **Privacy** — a non-blocking summary of what is sent to the selected model provider.

(The **Show reasoning** and **Confirm each edit** toggles live in the chat panel itself, next to
**New chat** — not in this settings page.)

## Troubleshooting

- **No providers offered** — no CLI was detected. Install one, or set its explicit path in
  **Settings ▸ Ontology Assistant** (common when Protégé is launched from the Dock/Finder without your
  shell `PATH`).
- **The model picker only offers (default)** — that provider's catalog is empty because you had no
  model selected and its local metadata named none. Add the ids you use under **Settings ▸ Ontology
  Assistant ▸ Available models**. A model that is no longer in the catalog — because you deleted it —
  falls back to **(default)** rather than being sent to the CLI.
- **The reasoning effort I picked was refused, or a note says it was ignored** — which levels a model
  supports is the model's business, and the CLI reports it in its own words (Codex fails the turn with
  the API's "supported values are …" list; either CLI may instead warn about the value and answer at its
  own effort). Both cases add a note after the turn, because neither diagnostic mentions the picker.
  Choose a value from the list the error names, or set the effort picker back to **(default)** and let
  the CLI decide. The note does not say who refused the value, and offers that list only if there is one:
  the same wording covers a Codex release whose own parser does not know the `model_reasoning_effort`
  setting at all, which names no value and accepts none — there, **(default)** is the way out. If the
  refused value happens to be the one both pickers are set to, the note says the error does not decide
  between them and names both: change one at a time. A diagnostic that only *names* the setting refuses
  nothing and adds no note — a Codex debug or config line echoing back the `model_reasoning_effort` it was
  handed is the CLI repeating your choice, not rejecting it, and a refusal on some *other* line is about
  whatever that line names: a turn that echoes your effort and then fails on a mistyped model id blames the
  model id, because a diagnostic is lines and not one sentence — and so is a refusal that lands on the very
  line your effort was echoed on: an error naming the *model* as what it refuses is the model's, whatever
  configuration it printed alongside, while one that refuses both, or refuses the effort *for* a named model,
  is still this picker's. A refusal is read from whatever words
  it uses to refuse, a value called invalid, unsupported, unknown, unrecognized, out of range, unavailable
  or not enabled for your model or account, not permitted, or simply
  rejected among them. A turn that fails over and over shows its failures up to a bound and then one line
  saying the rest are not shown, so a CLI stuck in a retry loop cannot bury the reply.
- **"The CLI exited without an answer and without reporting why"** — the turn ended cleanly and produced
  nothing: no reply, no error from the CLI, no warning to quote. There is nothing to diagnose from the
  transcript, so the message says exactly that instead of leaving a blank exchange. Send the message
  again; if it keeps happening, run that CLI in a terminal with the same message and see what it reports.
  The failed turn is not added to the conversation, so switching providers still asks your question.
- **"Not logged in" / auth errors** — log in in your terminal first (`claude`, or `codex login`). The
  plugin spawns the CLI through a login shell so it can pick up your environment.
- **Edits don't apply** — check the Assistant access setting, MCP **read-only** mode, and any pending
  **confirm-each-write** dialog (Settings ▸ MCP), plus the **Confirm each edit** checkbox in the panel.
- **The chat says the server is stopped** — you stopped it explicitly (**Stop** in the **MCP Server**
  view), which blocks every automatic start, the chat's included. Press **Start** in the view to bring
  it back.
