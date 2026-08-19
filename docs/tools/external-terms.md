---
title: "External terms"
parent: "Tools"
nav_order: 12
---

# External terms
{: .no_toc }

Discover external terminology evidence, create immutable reuse proposals, and accept one proposal
explicitly. Discovery and proposal calls require a valid project policy version 2 with an enabled provider
declaration, the matching owner-controlled origin binding, and ontology read, project read, and network
authority. The narrow capability is `external-terms:read`; the established general `network:access`
capability implies it for backward compatibility, but the reverse is not true. Acceptance uses the captured
evidence without another network request and has its own curation and project-write gate. Runtime profiles
are `ols4` and `ontoportal`. BioPortal and AgroPortal are editable endpoint presets for the single
`ontoportal` profile, not separate profiles. Endpoint URLs and credentials never come from project files or
tool arguments.

## Compatibility status

The OntoPortal deployments do not expose a stable API-version identifier on these REST surfaces. The
BioPortal and AgroPortal preset endpoints are therefore evaluated independently against exact official
`ontologies_api` source commits; a date is not treated as a vendor version.

| Profile | Tested surface | Authentication | Status and limitations |
| --- | --- | --- | --- |
| `ols4` | EBI OLS4 `/api/search`, `/api/ontologies/{id}`, and `/api/ontologies/{id}/terms/{double-encoded-iri}`; snapshot 2026-08-18 | Anonymous | Production-supported. OLS localization is preserved when supplied; absent optional metadata remains absent. |
| `ontoportal` | Common `/search`, `/ontologies/{acronym}`, `/classes/{single-encoded-iri}`, and `/latest_submission` surface. BioPortal preset: `https://data.bioontology.org`, evaluated at `ncbo/ontologies_api@1c59646038f6dd8b5acde2e11ae62dc7a990e801`. AgroPortal preset: `https://data.agroportal.eu`, independently evaluated at `agroportal/ontologies_api@e0dcb4f741d13bb476b754220d2c7051da867c06`. | `Authorization: apikey token=<key>` | Experimental evaluation pending live-canary history. The URL remains editable for compatible deployments. Search labels/synonyms have no language tag and are reported as `und`; scores are deterministic positional scores, not vendor relevance equivalence. Inspection needs three successful bounded requests. |

All conformance fixtures are sanitized representative documents derived from the pinned official source
contracts, not live response captures. They are offline inputs under `core/src/test/resources/external`; ordinary
`mvn clean verify` never calls these services. This build runs no live provider canary; any separately
scheduled canary must stay outside the ordinary build and does not by itself change the compatibility statement.
OntoPortal pagination follows the returned `page`, `pageCount`, and `nextPage` metadata and therefore tolerates
a deployment clamping the requested page size, while still bounding each response to the caller's limit.

## Owner setup

The built-in Ontology Assistant enables project-approved terminology lookup by default under
**Settings → Ontology Assistant → General**. Turn credentials receive `external-terms:read`, not general
network access. External MCP clients may request either that exact capability or the broader
`network:access`, together with ontology and project read.

External terminology registries can be configured directly in Protégé via **Preferences → MCP → Externals**,
or by writing `~/.protege-mcp/providers/config.json` with owner-only permissions:

```sh
install -d -m 700 ~/.protege-mcp/providers
install -m 600 /dev/null ~/.protege-mcp/providers/config.json
```

```json
{
  "version": 1,
  "origins": [
    {
      "alias": "ebi",
      "profile": "ols4",
      "origin": "https://www.ebi.ac.uk/ols4"
    }
  ],
  "credentials": []
}
```

The origin is an exact HTTPS base with no trailing slash, query, fragment, user information, or
relative segments. The runtime rejects symlinks, non-regular files, duplicate JSON keys, unknown
fields, and group/world-accessible state. Merely opening Preferences creates no provider directory. On
the first save or provider use it creates the required owner-only state and cache under
`~/.protege-mcp/providers/cache`; cache entries are HMAC-bound to the current owner binding,
credential generation, canonical project root, and policy digest.

Then enable the same alias in the project's policy v2 file:

```yaml
external_terms:
  providers:
    - id: ebi-ols
      profile: ols4
      enabled: true
      origin_alias: ebi
      ontologies: [efo]
      languages: [en]
      ttl_seconds: 900
      freshness: cache_ok
      max_results: 25
```

The policy can only select an owner alias; it cannot supply or override an endpoint. The selected
owner-local alias is the provider allowlist: it may name any exact HTTPS base URL compatible with the
selected profile, and does not need to repeat its host in `network.allowed_hosts`. Live requests,
redirects, and cached evidence are rechecked against that exact owner-bound origin. The global
`network.default` and `network.allowed_hosts` settings remain separate controls for document and import
fetching. Language order is meaningful: the first authored language is the default. `ttl_seconds: 0` disables cache reads and
writes; positive values through 86400 seconds are honored. `fresh_required` disables both cache reads
and writes so fresh-only evidence does not consume owner cache capacity.

Credential records are optional for anonymous OLS4 and required by the documented OntoPortal preset
surfaces. Secrets live in separate owner-only `<credential-id>.cred` binary records under
`~/.protege-mcp/providers/credentials`; they are never accepted through MCP, policy, logs, errors, cache
payloads, or returned source URLs. A credential binding in `config.json` names `id`, `provider_id`,
`origin_alias`, `scheme`, its constrained header or query parameter, and optional `project_fingerprint`.
The supported placements are:

- `query_api_key`: add the stored value as the outbound `apikey` query parameter. This documented fallback
  is confined to the HTTPS request; it is excluded from cache identity, evidence URLs, errors, and logs.
- `ontoportal_api_key`: the OntoPortal default; it stores only the key and constructs
  `Authorization: apikey token=<key>`.
- `bearer` and `api_key`: compatibility modes for a prefixed Authorization bearer value or a constrained
  non-Authorization API-key header.

The Preferences editor preserves multiple credentials per origin and their provider/project scopes.
Metadata and secret changes are coordinated under one owner lock; a
failed metadata commit restores the prior records or reports that manual verification is required.

OntoPortal requests explicitly select `format=json`, omit JSON-LD context with `display_context=false`,
retain required hypermedia links with `display_links=true`, and use the documented `include`, `page`, and
`pagesize` fields. Search excludes ontology views with `include_views=false`. `download_format` is not sent
because the current provider contract never calls an ontology download endpoint.

The **Test Connection** action is a non-mutating liveness request. It uses the same HTTPS-only transport,
DNS/address checks, redirect rules, response bounds, retries, credential redaction, and query-free returned
source URL as provider calls. It does not bypass policy for a project operation: it is an explicit local-owner
configuration action and therefore has no project policy context. Saving Preferences is not required to test
a newly entered secret, and temporary secret bytes are wiped after the probe.
While the probe is running, the editor displays an animated status and disables its actions. A probe that
has not completed within 10 seconds is cancelled with guidance to check the origin and credential inputs.

### Troubleshooting

- `provider_origin_unbound`: the policy alias is absent, the profile differs, or the exact origin is
  invalid. Check `config.json`, its permissions, and the no-trailing-slash rule.
- `provider_network_denied`: the request denies network use, the external-terminology capability is
  absent, or the request/cache evidence escaped the exact owner-bound origin. `network: allow` cannot
  widen any of those constraints.
- `provider_credential_unbound` or `provider_credential_missing`: remove the policy credential reference
  for anonymous OLS4 or repair the owner-local binding/record.
- `provider_policy_changed`, `provider_authority_changed`, or `provider_acquisition_stale`: policy,
  owner binding, project identity, or credential generation changed during the call. Retry only after
  the intended local change is complete.
- `provider_redaction_failed`: provider evidence resembled a credential, signed URL, or other secret.
  It is neither returned nor cached.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `search_external_terms`

Search exactly one enabled provider. A result is evidence for review only; it never suppresses local
minting, chooses a reuse action, edits the ontology, or writes a mapping. Provider continuation state is
kept in server memory and represented by a five-minute opaque cursor scoped to the principal, grant,
and workspace. OAuth grant/client revocation erases matching cursors. In broker mode, any backend
that cannot immediately confirm the exact revocation fence is retried from an owner-only durable
journal, including when that window registers later.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `provider_id` | string | new search | none | Enabled provider id from project policy. |
| `query` | string | new search | none | Search text, normalized and bounded to 512 Unicode code points. |
| `ontologies` | string array | no | policy allowlist when at most 16 | At most 16 provider ontology filters, all allowed by policy. If policy allows more than 16, select an explicit subset. |
| `language` | language tag | no | first policy language or `en` | Requested result language. |
| `limit` | integer (1-100) | no | policy `max_results` | Page size, never above the policy maximum. |
| `cursor` | opaque string | continuation only | none | Cursor returned by the preceding page. Do not resend search fields. |
| `policy_path` | string | no | discovered policy | Optional already-authorized project policy path. |
| `network` | `deny` or `allow` | no | effective policy | Request-level restriction. `allow` never widens policy. |

**Returns**

- `provider_id`, `profile`: exact configured provider identity.
- `items`: deterministic provider evidence records with source ontology, term identity, labels,
  synonyms, descriptions, license/provenance, match explanation and score, provider version and
  timestamp, sanitized source URL, retry count, deprecation/replacement fields, and
  stable `term_fingerprint` and acquisition-complete `result_fingerprint`.
- `total`, `returned`: provider-reported hit count and returned evidence count.
- `fetched_at`, `retries`, `cache_hit`: page acquisition evidence.
- `next_cursor`, `cursor_expires_in_seconds`: an optional pair; either both are present or both are
  absent. They carry an opaque continuation and its five-minute lifetime.

Search text is never persisted. If returned evidence contains the raw normalized query (including a
typical exact-label hit), the page is deliberately served without caching and `cache_hit` remains false.

**Example**

```json
{ "provider_id": "ebi-ols", "query": "cell death", "ontologies": ["efo"], "language": "en" }
```

## `inspect_external_term`

Fetch direct provider evidence for one exact ontology and term IRI. Current policy, owner binding,
credential generation, and network authority are checked again on every cache hit and network call.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `provider_id` | string | yes | none | Enabled provider id from project policy. |
| `ontology` | string | yes | none | Provider ontology id allowed by policy. |
| `iri` | absolute IRI | yes | none | Exact external term IRI. |
| `language` | language tag | no | first policy language or `en` | Requested evidence language. |
| `fresh` | boolean | no | `false` | When true, bypass both provider cache reads and writes. Use `true` before creating a reuse proposal. |
| `policy_path` | string | no | discovered policy | Optional already-authorized project policy path. |
| `network` | `deny` or `allow` | no | effective policy | Request-level restriction. `allow` never widens policy. |

**Returns**

- `result`: complete bounded provider evidence, including stable content `term_fingerprint` and
  acquisition-complete `result_fingerprint`.
- `cache_hit`: whether the evidence came from the owner-bound cache after all current checks passed;
  always `false` when `fresh=true`.

**Example**

```json
{ "provider_id": "ebi-ols", "ontology": "efo", "iri": "https://example.org/EFO_0000001", "fresh": true }
```

## `propose_term_reuse`

Create an immutable reuse proposal without editing the ontology or mapping store. First call
`inspect_external_term` with `fresh=true`; search-result fingerprints are discovery evidence and are
not accepted because search results are partial, projection-specific evidence rather than the complete
direct inspection record. The proposal call bypasses
both provider cache reads and writes, inspects the exact term again, and requires that forced-fresh
term-content fingerprint to match the preceding direct inspection. It then binds the normalized operation to the
current model revision, canonical mapping revision, project-policy digest, canonical project root,
canonical policy source, canonical mapping target, mapping-store existence, principal, grant, and
workspace. Filesystem identities appear only as opaque SHA-256 fingerprints. Proposal state exists only in memory,
expires after 15 minutes, and is erased by matching OAuth revocation, window close, or restart.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `provider_id` | string | yes | none | Enabled provider id from project policy. |
| `ontology` | string | yes | none | Provider ontology id allowed by policy. |
| `iri` | absolute IRI | yes | none | Exact external term IRI. |
| `term_fingerprint` | SHA-256 digest | yes | none | Stable content fingerprint from the latest `inspect_external_term` result. It excludes ranking, request timestamp, source URL, and retry metadata. Proposal creation fails if forced-fresh term content differs. |
| `language` | language tag | no | first policy language or `en` | Requested evidence language. |
| `action` | enum | yes | none | `reuse_iri`, `add_mapping`, or `mint_local_with_mapping`. |
| `mapping` | string object | mapping actions | none | One structurally valid, at-most-128-column SSSOM row containing `subject_id`, `predicate_id`, and `object_id`. Column names are bounded SSSOM identifiers, each raw authored cell is at most 64 KiB, and total raw UTF-8 operation size is at most 256 KiB. Reference cells are trimmed for canonical storage. Both endpoints must identify ontology entities, be semantically distinct, and the row must reference the external IRI; these rules are checked before provider egress. Literal and `sssom:NoTermFound` endpoints are rejected. |
| `local_entity` | object | mint action | none | New entity `iri`, matching entity `type`, and 1-16 localized `labels`. |
| `policy_path` | string | no | discovered policy | Optional already-authorized project policy path. |
| `network` | `deny` or `allow` | no | effective policy | Request-level restriction. `allow` never widens policy. |

**Returns**

- `proposal_id`: opaque 256-bit identifier scoped to the authenticated principal, grant, and workspace.
- `expires_in_seconds`: always `900`.
- `proposal`: provider evidence, complete input identity, requested action, normalized suggested
  operations, and a deterministic `proposal_fingerprint`.

The proposal is evidence for a later explicit acceptance call. It is not a preview that secretly
imports an ontology, performs MIREOT, mints an entity, or writes an SSSOM row.

**Examples**

```json
{
  "provider_id": "ebi-ols",
  "ontology": "efo",
  "iri": "https://example.org/EFO_0000001",
  "term_fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "action": "reuse_iri"
}
```

```json
{
  "provider_id": "ebi-ols",
  "ontology": "efo",
  "iri": "https://example.org/EFO_0000001",
  "term_fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "action": "add_mapping",
  "mapping": {
    "subject_id": "https://example.org/local/Cell",
    "predicate_id": "skos:exactMatch",
    "object_id": "https://example.org/EFO_0000001"
  }
}
```

## `accept_reuse_proposal`

Explicitly accept one scoped proposal. The call reclaims the proposal for exclusive use and rechecks
its fingerprint, expiry, principal/grant/workspace scope, complete model revision, mapping revision,
mapping-store existence, canonical project/policy/mapping target identity, project-policy digest,
read-only setting, and confirmation state before an initial action. Acceptance
does not contact the provider again and never imports or performs MIREOT.

The tool requires ontology curation plus project read/write capabilities because the opaque proposal id
does not reveal which of the three action types it contains. Pass `confirm=true` for the explicit protocol
confirmation. When the live confirm-each-write preference is enabled, mapping and mint actions also show
the ordinary interactive confirmation dialog.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `proposal_id` | opaque string | yes | none | Exact id returned by `propose_term_reuse`. It is valid only for the issuing principal, grant, and workspace. |
| `proposal_fingerprint` | SHA-256 digest | yes | none | Exact proposal fingerprint returned with the proposal; prevents accepting a different scoped record by mistake. |
| `confirm` | boolean | yes | none | Must be `true`. |
| `policy_path` | string | no | discovered policy | The same explicit policy path used when proposing, if any. A mint continuation binds this value exactly. |
| `mapping_set_id` | absolute IRI | when a mapping action creates the sidecar | none | Initial SSSOM `mapping_set_id`; ignored after the canonical store exists. A mint continuation binds the supplied value. |
| `license` | absolute IRI | when a mapping action creates the sidecar | none | Initial SSSOM mapping-set license; ignored after the canonical store exists. A mint continuation binds the supplied value. |

**Returns**

- `reuse_iri`: `status=accepted`, `committed=false`, and a receipt containing the exact external IRI,
  provider/source identity, term fingerprint, and accepted model/mapping/policy coordinates.
- `add_mapping`: `status=accepted` and the ordinary SSSOM mapping CAS result. A stale model, policy, or
  original mapping revision changes nothing.
- `mint_local_with_mapping`: one ontology broadcast first declares the exact entity type and adds the
  proposal labels, producing a fingerprinted `mint_receipt`; the original mapping revision is then used
  for one mapping CAS. A fully completed saga returns `status=accepted`, the receipt, and mapping result.

Every successful envelope includes `interactive_confirmation`, which records whether this exact call
received an enabled confirm-each-write dialog approval. Audit confirmation references bind the safe
`proposal_fingerprint`; opaque proposal ids are not displayed as the approval subject.

If the ontology mint commits but mapping validation, authorization, locking, or CAS does not complete,
the successful response is `status=partial` and `committed=true`. It contains the immutable mint receipt,
a bounded `mapping_error`, an exact same-tool retry, and an explicit `add_mapping` manual-recovery request.
Recording the receipt renews the same proposal id for one bounded 15-minute continuation window; a
failed or prevented mint does not extend the original expiry. Mint execution freezes expiry only while
the model-thread commit is in progress, and a started commit is joined through completion so it cannot
outlive the request's write lock, authorization lease, or audit ticket. A retry never mints again: it verifies
the declaration and every proposed label in the active ontology, binds the originally supplied policy and
mapping-set setup arguments, and retries only the original mapping revision. Later ontology edits are not
rolled back. If the mapping revision has moved, review current mappings and create a new proposal or use the
returned manual-recovery request deliberately. The returned manual request intentionally preserves the
proposal's stale `expected_mapping_revision`; after reviewing the current store, replace that field with
the reviewed live revision before executing recovery. It is not an automatic overwrite instruction.

If an ontology broadcast applies only part of the requested mint, or if the complete mint cannot be
paired with a durable receipt, the tool removes only axioms that were absent before this call and then
compares the restored semantic and document fingerprints with the pre-mint baseline. A verified full
restoration fails with `mint_commit_reverted` and `effects_prevented=true`. The failed proposal is
permanently invalidated; create a fresh proposal before retrying. If any requested axiom remains, a
listener introduced another model change, or baseline verification fails, the error is
`mint_commit_incomplete` with `outcome_unknown=true`, `manual_cleanup_required=true`, the proposed
`entity_iri`, and `new_proposal_required=true`. Review the ontology, remove the recorded entity axioms
and any listener side effects as appropriate, and then create a fresh proposal. Neither branch writes
the SSSOM sidecar.

**Example**

```json
{
  "proposal_id": "0123456789abcdef0123456789abcdef01234567890",
  "proposal_fingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "mapping_set_id": "https://example.org/mappings",
  "license": "https://creativecommons.org/licenses/by/4.0/",
  "confirm": true
}
```
