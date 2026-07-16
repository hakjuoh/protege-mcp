---
title: Troubleshooting
nav_order: 10
---

# Troubleshooting
{: .no_toc }

When the plugin doesn't show up or the server won't start, this page walks the log-based diagnosis.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The plugin doesn't appear at all

**Symptom:** after installing the jar and restarting, there's no **MCP Server** view under
**Window ▸ Views ▸ Miscellaneous views**, no **Ontology Assistant** tab, and no **MCP** /
**Ontology Assistant** entries under **Settings**.

Almost always this means **Protégé is running on Java 11** and the plugin needs **Java 17+**. The
entire OSGi bundle fails to resolve, so *none* of its views/tabs/settings register. It is **not** a
hidden view you can add from a menu — hunting through **Window ▸ Views** won't help until the bundle
loads.

### Step 1 — check which Java Protégé is on
{: #step-1 }

Open Protégé's log:

| OS | Log file |
|----|----------|
| macOS / Linux | `~/.Protege/logs/protege.log` |
| Windows | `%USERPROFILE%\.Protege\logs\protege.log` |

Search it for `Java: JVM`. You'll find a banner line like:

```
… [FelixDispatchQueue] INFO  ProtegeApplication    Java: JVM 11.0.25+9  Memory: 51539M
```

If that version is **below 17**, that's the problem — go to [Step 3](#step-3). (Grep for the
`Java: JVM` substring rather than a specific thread name; the exact thread/prefix varies.)

### Step 2 — confirm the bundle failed to resolve

Search the same log for the bundle id `io.github.hakjuoh.protege-mcp`. On a too-old JVM you'll see an error and a
warning like:

```
ERROR  FrameworkSlf4jLogger  Error starting …/io.github.hakjuoh.protege-mcp-<version>.jar
org.osgi.framework.BundleException: Unable to resolve io.github.hakjuoh.protege-mcp …:
  missing requirement … osgi.ee; (&(osgi.ee=JavaSE)(version=17))
…
WARN   ProtegeApplication  Plugin: Protege MCP Server (<version>) was not successfully started.
```

The actionable signal is **`osgi.ee=JavaSE` naming a version higher than the running JVM**. (The exact
punctuation varies with the Apache Felix build Protégé ships; the meaning is the same: the plugin
wants a newer Java than Protégé started on.)

### Step 3 — point Protégé at Java 17
{: #step-3 }

Follow [**Point Protégé at Java 17**](installation.html#java-17). The quickest cross-platform fix is a
`jvm.conf` with `java_home=/path/to/jdk-17`.

After restarting, re-check the log:

- the banner should now read `Java: JVM 17…`, and
- you should see `McpServerController … MCP server listening on http://127.0.0.1:<port>/mcp`.

The **MCP Server** view, **Ontology Assistant** tab, and settings tabs will now be present.

## The banner shows Java 17, but the view still isn't there

- Make sure there is exactly **one** `io.github.hakjuoh.protege-mcp-*.jar` / `protege-mcp-*.jar` in the plugins
  folder — the bundle is a **singleton**, and two copies conflict. Delete the old one.
- The status view lives under **Window ▸ Views ▸ Miscellaneous views ▸ MCP Server**; it can also be
  added to any tab.
- **Fully quit** and relaunch Protégé (closing the window is not enough).
- Re-check the log for any other `BundleException` / `ClassNotFoundException` from `io.github.hakjuoh.protege-mcp`.

## "jre/bin/java: No such file or directory" on launch

This is a **different** failure — the launcher couldn't find a JVM *at all* (a bad
`java_home` / `PROTEGE_JAVA_HOME` path, or a JDK that was moved/uninstalled), so Protégé never starts.
It is not the `osgi.ee` error above (which appears only after the JVM starts but is too old). Fix the
path to point at a real JDK 17 home.

## The server didn't start / "address already in use"

If Protégé loads but the MCP server doesn't come up, check the log for a Jetty bind error (e.g.
*address already in use*). Another process is holding the port. Change the port under
**Settings ▸ MCP** and restart, or free the port. The **MCP Server** view shows the current bound URL
and status.

Note that a busy port alone no longer stops MCP access. In the default **shared-broker** mode the
configured port is owned by the broker process, which serves every window and instance at once — a
second Protégé window/instance simply registers with it, so there is nothing to conflict. In
standalone mode (broker toggled off or unavailable), when the configured port is already in use each
window's server **falls back to an ephemeral port** and keeps working — the log then shows a
*configured port … is already in use* warning instead of a bind error, and the **MCP Server** view
shows the actual URL. The **Ontology Assistant** talks to its own window's server directly in both
modes, so chat always works.

If the broker seems stuck (unresponsive but still holding the port), check `~/.protege-mcp/broker.log`
and kill the broker process — its pid is in `~/.protege-mcp/broker.json`. Instances re-spawn a fresh
broker on demand within seconds; a broker that merely lost its instances exits by itself.

The broker runs from jar copies staged under `~/.protege-mcp/jars/`, so it normally holds no handle on
the plugin jar in Protégé's plugins directory — replacing the jar during an update works even while a
broker is still winding down. (If staging ever fails — say, the home directory is unwritable — the
broker falls back to the original jar and `~/.Protege/logs/protege.log` records a *could not stage*
warning; on Windows the plugin jar then stays locked until that broker exits.) Old copies are cleaned
up on later starts; the directory is safe to delete whenever no broker process is running.

## Still stuck?

Open an issue with the relevant `protege.log` excerpt (the `Java: JVM` banner and any
`io.github.hakjuoh.protege-mcp` lines) at
[github.com/hakjuoh/protege-mcp/issues](https://github.com/hakjuoh/protege-mcp/issues).
