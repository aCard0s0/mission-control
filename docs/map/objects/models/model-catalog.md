---
type: object
cluster: models
universe: live
status: verified
verified: main @ 637e0e1 · 2026-09-22
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/models/ModelCatalogService.java
---

# Model catalog

The list of **model names** available for one provider. Code name `ModelCatalogDto`.
Served at **`/api/models`**. Persisted in the `model_catalog` table.

## Why this shape

A curated fallback plus an opportunistic refresh. Some providers will not list their models
without a key, so the background job refreshes those only when a saved credential lends it one
(`models/ModelCatalogService.java:41`, `KEYED_CATALOGS` at `:52`, `savedKeyFor` at `:109`).
Any list read from a provider — by the job or by a live read an operator's key made possible —
is stored (`:169`), wins over the curated one and *says so* through `source`, which is how the
page tells an operator which one they are looking at (`models/ModelCatalogService.java:72`).

## Shape

`ModelCatalogDto(provider, models, source)` — `models/ModelCatalogDto.java:6`.

**`source` has three values**, and where each is emitted is the thing to know — the card used
to record that only two of them were named in the declarations, which is no longer true:

| Value | Emitted at | Means |
|---|---|---|
| `catalog` | `models/ModelCatalogService.java:80` | rows stored in `model_catalog` — by the refresh or an earlier live read |
| `config` | `:82`, `:174` | the curated compiled-in list |
| `live` | `:170` | fetched from the provider just now, with a key |

All three are now named on both sides: the record comment lists them
(`models/ModelCatalogDto.java:9`) and the frontend union is `'catalog' \| 'config' \| 'live'`
(`core/api/api-types.ts:504`), with no trailing `\| string` left to let an unnamed value
type-check. [docs/api.md](../../../api.md) had it right throughout.

The frontend carries it through as `ModelCatalog` (`core/models.ts`) and shows it as a hint on
the model field — `ModelPicker.sourceLabel` (`shared/model-picker.ts:61`). Which list a picker
loads for a chosen provider option — this catalog, an endpoint's installed models, or nothing —
is decided once, in `modelCatalogFor` (`shared/model-picker.ts:18`), shared by the create-agent
dialog and the blueprint editor. Worth showing rather
than hiding: a shipped list and a list read from the provider are identical in a dropdown, and
picking a model the provider no longer serves fails much later, at the agent's first turn.

The control itself is `mc-model-field` (`shared/model-field.ts`): a `<select>` while there is a
list, a text input otherwise, with a trailing "other…" entry back to free text. It replaced an
`<input list>` over a `<datalist>`, which a browser filters by the value the picker had already
preselected — so OpenAI's four seed models showed as one, and were read as the whole catalog.

The live read takes a typed key or a saved credential's id (`models/ModelCatalogController.java:18`);
the dialog fires it on key blur, on the ↻ button, and the moment a credential is picked
(`pages/agent-create-dialog.ts`, `refreshLive`).

`live` is deliberately **not** labelled — the operator supplied the key that fetched it — and
neither is an empty list, nor an endpoint's own installed models.

A failed read answers **empty**, not from a shipped copy. The frontend used to keep its own
mirror of `mc.models` for that case; it only fired when the backend was unreachable, at which
point the create it fed could not be submitted either.

## Connected to

- **owns:** `model_catalog` rows
- **owned-by:** [Provider](provider-registry.md) — only a provider with `hasCatalog` has one
- **joins:** [Provider](provider-registry.md) by provider key
- **looks-like-but-is-not:** the model list an [Inference endpoint](inference-endpoint.md)
  reports. That comes from the endpoint's own `/api/tags` or `/v1/models`, not from here.

## If you change this

- **Hits:** the model picker in create-agent, profile edit and templates; `ModelCatalogRefresher`
  (the background job); the `model_catalog` table and therefore `schema.sql` + `SchemaUpgrades`;
  `CredentialService.anyValueFor`, which the refresh borrows a key through.
- **Does not hit:** which providers exist ([Provider](provider-registry.md) is compiled in), and
  what a profile is actually *running* — that is read from the container, not from this catalog.

## Surfaces

| Surface | Role |
|---|---|
| `GET /api/models/{provider}` | reads |
| `POST /api/models/{provider}` | reads the provider, writes the stored list |
| `ModelCatalogRefresher` | writes |
| FE `core/api/providers-api.ts:20` | reads |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/models/`
