---
title: Versioning & releases
nav_order: 12
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

An in-progress version uses `## [x.y.z] - Unreleased`. Before its tag is pushed, that heading is
finalized to an ISO release date. Each finalized section is published **verbatim** as the body of its
[GitHub release](https://github.com/hakjuoh/protege-mcp/releases) — so the changelog, the GitHub release
notes, and this site all say the same thing.

## How a release is cut

Releases are automated by the
[**Release workflow**](https://github.com/hakjuoh/protege-mcp/blob/main/.github/workflows/release.yml).
Preparing source and advertising an update are deliberately separate. `update.properties` is fetched
directly from `main` by installed Protégé clients, so it must continue to name the last published asset
until the next asset actually exists.

1. On `develop`, prepare the new source version and an `Unreleased` changelog section. Leave
   `update.properties` on the last published release.
2. Commit the release finalization: change the changelog heading from `Unreleased` to the `YYYY-MM-DD`
   release date in both copies. On that commit, run `bash scripts/check-version-consistency.sh` and
   `mvn clean verify`. Apply the regression-evidence and two-method convergence rules in
   [`TESTING.md`](https://github.com/hakjuoh/protege-mcp/blob/main/TESTING.md); a review timeout or an
   unexecuted applicable live check is missing evidence, not a pass.
3. Tag that verified finalization commit as `vX.Y.Z` and push the tag. Do **not** advance the public
   descriptor yet.
4. Wait for the Release workflow to publish and verify `protege-mcp-X.Y.Z.jar`.
5. On `develop`, make a post-tag registry commit that updates `update.properties` to the new version
   and exact asset URL. Run `bash scripts/check-advertised-release.sh` and
   `bash scripts/check-version-consistency.sh --require-registry-current`.
6. Fast-forward or merge through that verified registry commit into `main`. This is the moment
   **Check for plugins** begins advertising the release; `main` never contains a future asset URL,
   and `main`'s CI enforces the strict registry gate to keep it that way.
7. Complete post-publish reconciliation. After the updated changelog is publicly visible on `main`,
   identify any historical release sections changed retrospectively during this release, regenerate
   each corresponding live GitHub Release body from its complete section on `main`, and verify the
   two bodies are byte-identical. Then replace any deployed/installed plugin jar with the newly
   published asset. Never edit a live release body before `main` contains the source note.

{: .note }
> **Re-cuts.** When a published tag is re-cut, rebase the post-tag registry commit back on top of the
> moved tag before advancing `main`, so `main` keeps passing the strict gate. The Release workflow
> deletes and recreates the GitHub release on a moved tag; the CI asset probe retries through that
> brief window, so an unrelated red run only means it raced the re-cut — re-run it.

Pushing a tag of the form `vX.Y.Z` triggers this automated portion:

1. **Checkout** the tag.
2. **Check version consistency** — source-version mirrors must agree with `pom.xml`, while the public
   update descriptor may safely lag (see [the next section](#what-a-release-bumps-the-version-consistency-gate)). Tags cut
   before the gate existed are skipped, so old releases can still be republished.
3. **Verify the tag and finalized changelog** — the tag must equal `v` + `pom.xml`'s `<version>` and
   that version's changelog heading must contain an ISO date, not `Unreleased`. Legacy tags that
   predate `CHANGELOG.md` skip this heading gate (and fall back to auto-generated release notes), so
   they can still be republished.
4. **Build, test, and enforce coverage** with `mvn -B clean verify` on JDK 17.
5. **Extract the release notes** — the `## [X.Y.Z]` section of `CHANGELOG.md` becomes the release body
   (falling back to auto-generated notes if that section is missing).
6. **Publish the GitHub release** titled `protege-mcp vX.Y.Z`, attaching `protege-mcp-X.Y.Z.jar`.

You can also run it manually from the Actions tab (**workflow_dispatch**) with a `tag` input.

## What a release bumps (the version-consistency gate)

`pom.xml`'s `<version>` is the source of truth for the source tree. Release preparation updates these
mirrors with it; the changes may be part of the feature work or a release-finalization commit:

| Where | What restates the version |
| --- | --- |
| `McpServerManager.SERVER_VERSION` | The version reported to MCP clients. |
| [`DESIGN.md`](https://github.com/hakjuoh/protege-mcp/blob/main/DESIGN.md) | The as-built server and bundle versions. |
| [`TESTING.md`](https://github.com/hakjuoh/protege-mcp/blob/main/TESTING.md) | The tested source version and release-evidence rules. |
| [`docs/_config.yml`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/_config.yml) | `version:`, exposed to the documentation as `site.version`. |
| [`docs/readme.html`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/readme.html) | The first version-history heading. This file is fetched raw by Protégé, so it cannot use Liquid. |
| `CHANGELOG.md` + [Changelog](changelog.html) | The newest section in both mirrored changelogs, including its complete body. |

The public [`update.properties`](https://github.com/hakjuoh/protege-mcp/blob/main/update.properties)
has a different invariant: its `version=` and `download=` must agree with each other and must name an
asset that already exists. During release preparation it intentionally lags the source version.

[`scripts/check-version-consistency.sh`](https://github.com/hakjuoh/protege-mcp/blob/main/scripts/check-version-consistency.sh)
enforces this list in both [CI](https://github.com/hakjuoh/protege-mcp/blob/main/.github/workflows/ci.yml)
and [Release](https://github.com/hakjuoh/protege-mcp/blob/main/.github/workflows/release.yml). CI also
probes the advertised URL on both `develop` and `main`, and on `main` it runs the strict
`--require-registry-current` mode: `develop` may lag the registry only while a release is in flight,
while `main` must always advertise the current released version. Run the source check before tagging:

```bash
bash scripts/check-version-consistency.sh
```

After the asset is published and the descriptor is updated, run the strict publication checks:

```bash
bash scripts/check-advertised-release.sh
bash scripts/check-version-consistency.sh --require-registry-current
```

## Updating the plugin registry (Check for plugins)

Only after the matching release asset exists, bump
[`update.properties`](https://github.com/hakjuoh/protege-mcp/blob/main/update.properties):

- `version` must be a valid **OSGi** version (`major.minor.micro[.qualifier]`) and **strictly greater**
  than the installed bundle version, or Protégé will not offer it.
- `download` must point at the new release's jar asset.

{: .warning }
> Never push a future `version`/`download` pair to `main`. Protégé reads this descriptor from raw
> `main`, so doing so creates a window where every installed client is offered a 404 update.

{: .warning }
> OSGi orders an empty qualifier *before* a non-empty one, so `0.1.0 < 0.1.0.SNAPSHOT`. To update over
> an installed dev/SNAPSHOT build, bump the micro/minor rather than relying on a qualifier.

## Finding a specific version

- [**Releases**](https://github.com/hakjuoh/protege-mcp/releases) — every version's notes and jar.
- [**Latest**](https://github.com/hakjuoh/protege-mcp/releases/latest) — the current release.
- [**Changelog**](changelog.html) — all notes on one page.
