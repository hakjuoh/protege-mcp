---
title: Versioning & releases
nav_order: 7
---

# Versioning & releases
{: .no_toc }

How versions are numbered, where release notes live, and how a release is cut.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Versioning scheme

Protégé MCP aims to follow [**Semantic Versioning**](https://semver.org/): `MAJOR.MINOR.PATCH`.

| Bump | When | Example |
| --- | --- | --- |
| **PATCH** | Bug fixes and behaviour-preserving improvements. | `0.3.2 → 0.3.3` |
| **MINOR** | New, backward-compatible capability (typically new tools or a new subsystem). | `0.3.0` added the Ontology Assistant; `0.3.2` added SPARQL. |
| **MAJOR** | Backward-incompatible changes. | — |

{: .note }
> The project is **pre-1.0**, so a MINOR release can carry sizeable new features (e.g. the whole
> Ontology Assistant landed in `0.3.0`). The tool count is called out in each release's notes.

The Maven project version in [`pom.xml`](https://github.com/hakjuoh/protege-mcp/blob/main/pom.xml) is the
single source of truth for the current version, and the release tag must match it (see below).

## Release notes

All notable changes are recorded in
[**`CHANGELOG.md`**](https://github.com/hakjuoh/protege-mcp/blob/main/CHANGELOG.md), following the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. The same content is mirrored on this
site's [**Changelog**](changelog.html) page.

Each release's `## [x.y.z]` section is published **verbatim** as the body of its
[GitHub release](https://github.com/hakjuoh/protege-mcp/releases) — so the changelog, the GitHub release
notes, and this site all say the same thing.

## How a release is cut

Releases are automated by the
[**Release workflow**](https://github.com/hakjuoh/protege-mcp/blob/main/.github/workflows/release.yml).
Pushing a tag of the form `vX.Y.Z` triggers it:

1. **Checkout** the tag.
2. **Verify the tag matches the Maven version** — the tag must equal `v` + `pom.xml`'s `<version>`, or
   the workflow fails. This guarantees the tag, the jar name, and the code all agree.
3. **Build and test** with `mvn -B clean package` on JDK 17.
4. **Extract the release notes** — the `## [X.Y.Z]` section of `CHANGELOG.md` becomes the release body
   (falling back to auto-generated notes if that section is missing).
5. **Publish the GitHub release** titled `protege-mcp vX.Y.Z`, attaching `protege-mcp-X.Y.Z.jar`.

You can also run it manually from the Actions tab (**workflow_dispatch**) with a `tag` input.

## Updating the plugin registry (Check for plugins)

For Protégé's **File ▸ Check for plugins** to offer an update, bump
[`update.properties`](https://github.com/hakjuoh/protege-mcp/blob/main/update.properties):

- `version` must be a valid **OSGi** version (`major.minor.micro[.qualifier]`) and **strictly greater**
  than the installed bundle version, or Protégé will not offer it.
- `download` must point at the new release's jar asset.

{: .warning }
> OSGi orders an empty qualifier *before* a non-empty one, so `0.1.0 < 0.1.0.SNAPSHOT`. To update over
> an installed dev/SNAPSHOT build, bump the micro/minor rather than relying on a qualifier.

## Finding a specific version

- [**Releases**](https://github.com/hakjuoh/protege-mcp/releases) — every version's notes and jar.
- [**Latest**](https://github.com/hakjuoh/protege-mcp/releases/latest) — the current release.
- [**Changelog**](changelog.html) — all notes on one page.
