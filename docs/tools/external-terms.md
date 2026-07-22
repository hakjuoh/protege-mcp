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
capabilities. Acceptance uses the captured evidence without another network request and has its own curation
and project-write gate. Version 0.8.0 supports the `ols4` profile. Endpoint URLs and credentials never come
from project files or tool arguments.

## Owner setup

The owner must bind every endpoint locally before a project can use it. For the anonymous EBI OLS4
profile, create `~/.protege-mcp/providers/config.json` with owner-only permissions:

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
fields, and group/world-accessible state. On first use it creates the cache under
`~/.protege-mcp/providers/cache`; cache entries are HMAC-bound to the current owner binding,
credential generation, canonical project root, and policy digest.

Then enable the same alias in the project's policy v2 file:

```yaml
network:
  default: allow
  allowed_hosts: [www.ebi.ac.uk]
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

The policy can only select an owner alias; it cannot supply or override an endpoint. Language order
is meaningful: the first authored language is the default. `ttl_seconds: 0` disables cache reads and
writes; positive values through 86400 seconds are honored. `fresh_required` disables both cache reads
and writes so fresh-only evidence does not consume owner cache capacity.

Credential records are optional and are not needed by the supported anonymous EBI OLS4 workflow.
Secrets live in owner-only binary records under `~/.protege-mcp/providers/credentials`, are rotated by
the local owner credential service, and are never accepted through MCP, policy, logs, errors, cache
payloads, or URLs. A credential binding in `config.json` names `id`, `provider_id`, `origin_alias`,
`scheme` (`bearer` or `api_key`), optional `header`, and optional `project_fingerprint`. Deleting or
rotating its local record immediately invalidates in-flight publication and old cache scope. Do not add
`credential_id` to project policy unless that owner-local record has already been provisioned.

### Troubleshooting

- `provider_origin_unbound`: the policy alias is absent, the profile differs, or the exact origin is
  invalid. Check `config.json`, its permissions, and the no-trailing-slash rule.
- `provider_network_denied`: the effective project/request network policy does not allow the exact
  origin. `network: allow` on a request cannot widen a denied project policy.
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
