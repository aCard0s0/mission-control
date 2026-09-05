---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 7d99214 · 2026-09-03
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/credentials/CredentialService.java
---

# Credential

A key or token the operator saves once, offered as a dropdown wherever one would otherwise be
typed: an agent's `.env`, the create-agent dialog, a [blueprint's](../agents/profile-template.md)
keys tab. Dashboard-owned, in SQLite, encrypted through [Secret](secret.md).

The product word and the code noun are the same — `credentials`, `Credential`,
`/api/credentials`. That is the point of the name: `provider` already means
[four things](../models/CONTEXT.md), `auth provider` is the read-only OAuth panel on the Setup
tab, and `secret` is the at-rest concept plus a `profile_templates` column.

## Why this shape

**A bundle of variables, not one key.** `HermesEnvCatalog.MESSAGING` pairs a bot token with a
home channel (`TELEGRAM_BOT_TOKEN` + `TELEGRAM_HOME_CHANNEL`), and a self-hosted provider takes
a base URL alongside its key. One row per variable would make an operator save and pick the
halves separately, so a picked credential fills every row it covers at once.

**The value never reaches the browser.** A picker posts the credential's *id* and the server
resolves it inside the trust boundary. Stricter than the one existing exception,
`GET …/webhooks/{route}/secret`, which returns a full HMAC secret. A secret entry's DTO carries
`set`/`recoverable` and no `value`, not even a suffix; a *non-secret* entry's value is returned,
because a home channel is nothing to hide and a picker that could not show it would be useless
for the pair it belongs to.

**Autofill only — no dependents, by decision.** Nothing records that a credential filled
something. Deleting one breaks nothing already written and rotating one changes nothing already
written. This is not a simplification waiting to be undone: a profile's `.env` is a file inside
a container, so a stored association could not propagate to it without a re-push whichever way
it pointed. Rotation across agents therefore needs a *push*, not a link.

**Two resolvers, and no apply route.** Each resolver is about to hand its answer to something
that must not receive a blank (`credentials/CredentialService.java:28`). An
apply-a-whole-credential-to-an-agent route was built and then cut: the Setup tab's picker
already fills every row a credential covers, so the route saved one button press, duplicated the
resolution, and nothing in the UI reached it.

## Shape

- Table `credentials`: `id`, `name` (`COLLATE NOCASE UNIQUE`), `description`, `entries_json`,
  `created_at`, `updated_at`
- `entries_json` is `[{key, value, secret}]` — the same shape as the `StoredValue` list inside
  `mcp_servers.config_json`. A secret entry's `value` is an `enc:v1:` envelope; a plain entry's
  is cleartext.
- Entry keys are validated against `EnvEntry.KEY_PATTERN` — the strict `.env` form
  `[A-Z][A-Z0-9_]{1,63}`, **not** the looser `ConfigValueInput.ENV_KEY_PATTERN`, because a
  `.env` is the only place these land. Max 32 entries.
- No FKs and no id lists — production runs with `PRAGMA foreign_keys` off.
- Resolvers: `valueFor(id, key)` → one value in the clear · `envelopeFor(id, key)` →
  still-sealed ciphertext for a store that holds envelopes.

Citations: `applications/mission-control-server/src/main/resources/schema.sql:311`,
`credentials/CredentialService.java:28`, `credentials/CredentialController.java:29`,
`agents/api/EnvEntry.java:40`

## Connected to

- **owns:** the `credentials` table and `/api/credentials`
- **owned-by:** [Secret](secret.md) — the fourth caller of `SecretsAtRest`, which owns the four
  rules this store obeys
- **joins:** nothing, deliberately. Three *request* fields carry an id into other writes —
  `EnvEntry.credentialId`, `CreateAgentRequest.apiKeyCredentialId`, `SecretInput.credentialId` —
  and each is resolved and discarded before the write lands.
- **looks-like-but-is-not:** an **auth provider** (`agents/web/AgentSetupController.java:36`),
  which is a read-only report of what one container is logged into; a **provider** in any of its
  [four senses](../models/CONTEXT.md); a [profile template's](../agents/profile-template.md)
  `secrets`, which is a blueprint's own copy and not a library.

## If you change this

- **Hits:** [Secret](secret.md) if you touch the sealing — this is now the **fourth** caller of
  `SecretsAtRest` and the reason that class exists is that two callers drifted. The three
  request fields above, each with its own resolution point:
  `agents/web/AgentSetupController.java:80` (`.env` writes),
  `agents/web/AgentsController.java:95` (create-agent, resolved against
  `ModelProviderRegistry.envVar`), and `agents/templates/ProfileTemplateService.java:247`
  (blueprint, ciphertext to ciphertext).
- **Hits:** the [API contract](api-contract.md) — four routes, so `api-contract.txt`,
  `docs/api.md` and `ApiContractTest.CONTRACT` all move.
- **Does not hit:** anything already written. There is no propagation path by design — see
  *Autofill only* above. Also **does not hit** runtime visibility: encryption at rest is not
  encryption in use, and a resolved value lands in a `.env` inside a container.
- **Does not hit:** the top two panels of the Setup tab. `API-Key Providers` and
  `Auth Providers` are `hermes status` output, and OAuth needs a terminal login.

## Surfaces

| Surface | Role |
|---|---|
| `pages/credentials.ts` | the library — flat list, search, inline editor. **No groups**; see below |
| `pages/agent-setup-panel.ts` | picker per row on API KEYS and MESSAGING PLATFORMS (writes) |
| `pages/agent-create-dialog.ts` | picker beside the API-key field, filtered by the provider's `envVar` (writes) |
| `pages/profile-editor-panel.ts` | picker on the keys tab, flattened to `credential · KEY` (writes) |
| `core/store/credential-store.ts` | boot fetch only, **never polled**. One of the four libraries `LiveSync` still loads at boot, because three of its four readers are pickers on other pages that never load it |
| SQLite `credentials.entries_json` | ciphertext at rest |

**No credential groups.** The three group families
([skill](skill-group.md), [prompt](prompt-group.md), [MCP](../mcp/mcp-group.md)) file libraries
with hundreds of rows; a handful of credentials does not need a fourth. The four slices are one
class now (`core/store/library-store.ts`, `694cab2`) — a fifth would be four lines, and that is
not the reason to add one.

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/credentials/`
- Routes: [../../../api.md](../../../api.md) — "Credentials"
