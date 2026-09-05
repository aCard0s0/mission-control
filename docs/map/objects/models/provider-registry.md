---
type: object
cluster: models
universe: live
status: verified
verified: claude/hermes-openai-api-key @ e4e23e4 · 2026-09-05
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/ModelProviderRegistry.java
---

# Provider

An upstream LLM vendor an Agent can be pointed at — "Anthropic", "OpenRouter", "Ollama Cloud".
Code name `ModelProviderRegistry.Provider`. Served at **`/api/providers`**.

## Why this shape

Compiled-in, not stored. The list mirrors hermes' own `CANONICAL_PROVIDERS` picker order
(`hermes_cli/models.py`) and its provider records (`hermes_cli/auth.py`) — so it is a
*mirror of another program's constant*, and a database row would let it drift from the CLI it
has to agree with. Three authentication shapes exist and the record spells all three:
an env-var API key, OAuth with no key, and neither.

The one job the registry does that no caller could do alone: the picker, the "needs an API
key?" rule, and the provider→catalog decision all read the same list, so they cannot disagree
(`agents/web/ProvidersController.java:9`).

## When hermes renames a key

A mirror drifts when the original moves. Hermes v0.21.0 (2026.8.31) split `openai` into
`openai-api` (API key, api.openai.com) and `openai-codex` (ChatGPT subscription) and dropped
the old key. The failure that produced is quiet: a profile whose `config.yaml` says
`provider: openai` passes `hermes status`, which only reads the env var and prints
`OpenAI ✓`, and then fails hermes' runtime resolver with `Unknown provider 'openai'` — which
the interactive CLI reports as "No inference provider is configured yet — let's fix that".

So the row is `openai-api`, and `normalizeKey` (`agents/ModelProviderRegistry.java:100`) folds
the retired spelling to it. Every path that writes `model.provider` or resolves an env var
already goes through that method, and `byKey` does now too, so a blueprint or a live profile
saved under the old key deploys, is served and masks its key under the new one. The
[profile template](../agents/profile-template.md) also normalizes `provider` on save and on
the wire, so a re-save stores the current key for good.

The cheap check when hermes moves again: `hermes -p <name> status` is not it. Run hermes' own
resolver — `resolve_runtime_provider(requested=<key>)` from `hermes_cli.runtime_provider`, with
the profile's `.env` exported — and compare `CANONICAL_PROVIDERS` slugs against `PROVIDERS`.

## Shape

`Provider(key, label, envVar, oauth, hasCatalog)` — `agents/ModelProviderRegistry.java:47`.
~28 entries, `agents/ModelProviderRegistry.java:57`.

- `envVar` null means the provider takes no key (OAuth or resolved elsewhere).
- `hasCatalog` gates whether [Model catalog](model-catalog.md) can list names for it.

## Connected to

- **owns:** nothing — it is a constant
- **owned-by:** hermes' own provider list, upstream. Adding an entry here that hermes does not
  know is a ghost in the picker.
- **joins:** [Model catalog](model-catalog.md) by `key`; [Auth provider](auth-provider.md) by `key`
- **looks-like-but-is-not:** [Inference endpoint](inference-endpoint.md), which *is* a database
  row. The two are unrelated — and the endpoint route was called `/api/model-providers` until it
  was renamed for exactly this reason.

## If you change this

- **Hits:** the create-agent and template UIs (the picker reads `/api/providers`); the
  "needs an API key" prompt; whether a provider offers a model catalog at all; the
  `.env` key name written into a profile on create.
- **Does not hit:** [Inference endpoint](inference-endpoint.md) rows. A self-hosted Ollama
  is not a registry entry and adding one here does not create one. Nor does it hit
  `inference_endpoints` in SQLite. That table used to be called `model_providers`, which is what
  made this pair worth two cards.

## Surfaces

| Surface | Role |
|---|---|
| `/api/providers` | reads |
| FE `core/api/providers-api.ts`, `core/store/provider-store.ts` | reads |
| hermes CLI | the upstream this mirrors — never written by us |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/ModelProviderRegistry.java`
- Controller: `agents/web/ProvidersController.java`
