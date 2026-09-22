---
type: object
cluster: models
universe: live
status: verified
verified: claude/codex-picker @ 6cc1283 · 2026-09-22
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/api/AuthProviderDto.java
---

# Auth provider

Which vendors **one container already holds credentials for**. Code name `AuthProviderDto`.
Served at **`/api/agents/{hostId}/{containerId}/auth-providers`**.

Fourth of the four "provider"-shaped nouns, and the only one that is per-container.

## Why this shape

Container-scoped rather than profile-scoped, and the controller shows why: it answers from the
`"default"` profile's setup (`agents/web/AgentSetupController.java:39`). Credentials live in the
container's data volume, not in one profile — so asking any profile answers for all of them, and
the endpoint takes no profile name.

Read-only. Mission Control reports what is there; hermes owns the credential store.

Each row names the [Provider](provider-registry.md) key it serves — `providerKey`, mapped from
hermes' label in `HermesEnvCatalog.AUTH_PROVIDER_KEYS` (`Nous Portal` → `nous`, `OpenAI Codex`
→ `openai-codex`), null for a login the picker has no row for. That key is what lets the
create-agent dialog offer an OAuth provider only where the container is logged into it.

## What this does not tell you

Whether a key still *works*. An OAuth login can be green here while every turn of a profile
dies on a revoked API key, because the profile's `config.yaml` points at `openai-api` and
hermes' credential pool remembers the 401 against it. The pool is a third source neither this
endpoint nor the `.env` reads; `HermesSetup` asks `hermes auth list` for it and puts the answer
on the per-profile setup's `apiKeys[].problem` — the pool outlives the `.env` line it was
seeded from, so that is the one place a dead key shows.

## Shape

`AuthProviderDto` — `agents/api/AuthProviderDto.java`. Derived from `HermesSetup`, which reads
the container. See also `ApiKeyStatusDto` and `ApiKeyProviderDto` in the same package.

## Connected to

- **owns:** nothing — this is a read of hermes' state
- **owned-by:** the container's `/opt/data` volume, via hermes
- **joins:** [Provider](provider-registry.md) by provider key — that list says a vendor *needs*
  a key, this one says whether this container *has* one
- **looks-like-but-is-not:** [Inference endpoint](inference-endpoint.md) status. An endpoint
  being reachable is not a credential.

## If you change this

- **Hits:** the agent Setup panel (`pages/agent-setup-panel.ts`); `HermesSetup`;
  `core/store/agent-setup-store.ts`; the create-agent dialog's provider list
  (`pages/agent-create-dialog.ts`, `offered`), which reads `providerKey` and `ok` to decide
  whether an OAuth row is shown at all.
- **Does not hit:** the [Provider](provider-registry.md) list, which is compiled in and
  container-independent. Does not hit any other container — this is per-container by
  construction.

## Surfaces

| Surface | Role |
|---|---|
| `/api/agents/{hostId}/{containerId}/auth-providers` | reads |
| hermes, inside the container | owns the truth |
| FE `pages/agent-setup-panel.ts` | reads |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/HermesSetup.java`
- Controller: `agents/web/AgentSetupController.java:36`
