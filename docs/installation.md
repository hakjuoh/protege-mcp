---
title: Installation
nav_order: 2
has_children: true
---

# Installation
{: .no_toc }

How to install Protégé MCP, the Java requirement it depends on, and how to confirm it loaded.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Requirements

Protégé must run on **Java 17 or newer**.

The MCP Java SDK and the embedded Jetty 12 host are compiled to Java 17 bytecode, and the bundle's
OSGi manifest declares `Require-Capability: osgi.ee … JavaSE 17`. There is no Java-11 build path —
every published MCP SDK version is Java 17 bytecode.

{: .warning }
> **Protégé's own downloads bundle Java 11.** Protégé itself only needs Java 11, so every desktop
> installer ships an 11 runtime. On that bundled JVM the plugin **silently fails to load** — the whole
> OSGi bundle fails to resolve, so it never appears in *any* view, tab, or menu. This is not a
> "hidden view" you can add from a menu; you must point Protégé at a Java 17+ JDK first (below). If it
> already looks like the plugin is missing, see [Troubleshooting](troubleshooting.html).

## Point Protégé at Java 17
{: #java-17 }

Protégé 5.6.x uses one native launcher on every platform (`Protege.exe`, `Protégé.app`, and the Linux
`protege` binary). It picks the JVM in this order — **first match wins**:

1. `java_home=` in a **`jvm.conf`** file
2. the **`PROTEGE_JAVA_HOME`** environment variable
3. Protégé's **bundled `jre`** (Java 11) — the default
4. `JAVA_HOME` — only as a last resort, if the choice above fails to load

So there are two good ways to select Java 17. The `jvm.conf` file is the simplest: it works the same
whether you launch from the Dock / Start menu / app menu or a terminal, and it outranks everything
else. (`run.bat` / `run.sh` scripts differ — see the per-OS notes below.)

### Option 1 — `jvm.conf` (recommended, all platforms)

Put a single line — the path to a JDK 17+ **home** (the folder that contains `bin/`) — in the
`jvm.conf` the launcher reads:

- **macOS / Linux:** `~/.Protege/conf/jvm.conf`
- **Windows:** `<Protégé install dir>\conf\jvm.conf`
  <br>(the launcher looks for the user-home copy via `HOME`, which Windows doesn't set by default, so
  use the install-dir file)

```properties
# ~/.Protege/conf/jvm.conf   (macOS/Linux)   —or—   <install>\conf\jvm.conf (Windows)
java_home=/path/to/jdk-17
```

Finding a JDK 17 home:

| OS | How | Example |
|----|-----|---------|
| macOS | `/usr/libexec/java_home -v 17` | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Linux | your distro's JVM dir | `/usr/lib/jvm/java-17-openjdk` |
| Windows | the JDK install folder (holds `bin\server\jvm.dll`) | `C:\Program Files\Eclipse Adoptium\jdk-17.0.13-hotspot` |

Restart Protégé, then confirm the JVM from the log banner (see [Troubleshooting](troubleshooting.html#step-1)).

### Option 2 — `PROTEGE_JAVA_HOME` (per-OS)

Use this if you'd rather set an environment variable than a file. The catch is getting a **GUI**
launch (Dock / Start menu / app menu) to actually *see* the variable — a plain shell `export` won't.

<details markdown="1">
<summary><strong>macOS</strong> — Dock / Finder / Spotlight launch</summary>

GUI apps are started by `launchd`, not your shell, so `export` in `~/.zshrc` never reaches Protégé.
Set it through `launchctl`, and persist it with a LaunchAgent:

```bash
# 1) this session (quit Protégé first — only processes started afterward inherit it)
launchctl setenv PROTEGE_JAVA_HOME "$(/usr/libexec/java_home -v 17)"

# 2) persist across reboots: ~/Library/LaunchAgents/<label>.plist with RunAtLoad=true,
#    ProgramArguments = /bin/launchctl setenv PROTEGE_JAVA_HOME <ABSOLUTE jdk-17 home>
#    (hard-code the absolute path — a plist string is not shell-expanded)
launchctl load -w ~/Library/LaunchAgents/<label>.plist
```

(A terminal launch of `Protégé.app/Contents/MacOS/protege` just needs the var exported in that shell.)
</details>

<details markdown="1">
<summary><strong>Windows</strong></summary>

Point `PROTEGE_JAVA_HOME` at the JDK **root** (the folder containing `bin\server\jvm.dll`), not `bin`:

- **GUI (recommended):** *Edit the system environment variables* → **Environment Variables…** →
  **New…** under *User variables* → name `PROTEGE_JAVA_HOME`, value e.g.
  `C:\Program Files\Eclipse Adoptium\jdk-17.0.13-hotspot`. Then **log off and back on** — Start-menu /
  double-click launches inherit their environment from the login-time `explorer.exe`, which won't see
  the value until the session is recreated.
- **Command line:** `setx PROTEGE_JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17..."`. `setx`
  only affects processes started *after* it runs, so still log off/on for a Start-menu launch — or, to
  test now, start `Protege.exe` from a **freshly opened** `cmd` window.

Note: `run.bat` ignores `PROTEGE_JAVA_HOME` (it hardcodes the bundled `jre\bin\java`) — use
`Protege.exe`, or the `jvm.conf` method above.
</details>

<details markdown="1">
<summary><strong>Linux</strong></summary>

```bash
# terminal ./run.sh (reads your shell env):
PROTEGE_JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./run.sh
#   persist for shells:  echo 'export PROTEGE_JAVA_HOME=/usr/lib/jvm/java-17-openjdk' >> ~/.profile

# desktop / application-menu launch (Exec=protege) — menu launches do NOT read ~/.profile
# on GNOME/Wayland, so export into the session instead, then log out/in:
mkdir -p ~/.config/environment.d
printf 'PROTEGE_JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n' > ~/.config/environment.d/protege.conf
```
</details>

## Install the plugin

You can install manually (drop in a jar) or let Protégé discover and update the plugin for you.

### Option A — Manual (drop-in jar)

1. Download `protege-mcp-<version>.jar` from the
   [latest release](https://github.com/hakjuoh/protege-mcp/releases/latest).
2. Drop it into Protégé's plugins directory:
   - **macOS:** `~/.Protege/plugins/` (per-user), or the in-bundle
     `Protégé.app/Contents/plugins/`.
   - **Windows / Linux:** the `plugins/` folder inside the Protégé install, e.g.
     `C:\Program Files\Protege-5.6.x\plugins\` or `<protege-install>/plugins/`.
3. Restart Protégé on a Java 17+ JVM (see [Point Protégé at Java 17](#java-17)).

{: .note }
> Keep exactly **one** `protege-mcp-*.jar` (or `io.github.hakjuoh.protege-mcp-*.jar`) in the plugins folder. The
> bundle is a **singleton**; two versions side by side will conflict. Delete the old jar before adding
> a new one.

### Option B — Check for plugins (auto-discovery + updates)

Protégé can discover the plugin and offer future updates from a self-hosted registry. See
[**Install via Check for plugins**](check-for-plugins.html) for the full walk-through. In short:

1. **Settings ▸ Plugins ▸ Plugin registry** →
   `https://raw.githubusercontent.com/hakjuoh/protege-mcp/main/protege-mcp.repository`
2. **File ▸ Check for plugins** → tick **Protege MCP Server** → install → restart.

## Verify it loaded

After restarting **on Java 17+**, you should see:

- A **MCP Server** view — add it from **Window ▸ Views ▸ Miscellaneous views**, or to any tab. It shows
  the server status, bound URL, and bearer token.
- A **MCP** settings tab under **Settings ▸ MCP** (port, bind address, shared broker and its idle
  linger, autostart, read-only, confirm-writes).
- An **Ontology Assistant** top-level tab, and an **Ontology Assistant** settings tab under
  **Settings ▸ Ontology Assistant**.

The bundle id is `io.github.hakjuoh.protege-mcp` (singleton).

Seeing none of these? The plugin almost certainly didn't load — nearly always because Protégé is on
Java 11. Head to [**Troubleshooting**](troubleshooting.html).

## Next steps

- [Connect an external MCP client](connect/) (Claude Code, Codex CLI, VS Code, Claude Desktop), or
- Open the [Ontology Assistant](ontology-assistant.html) and start chatting.
