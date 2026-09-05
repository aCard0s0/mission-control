# Mission Control — Backend API

Base: same origin as the dashboard (combined image) or `MC_API_BASE_URL`.
All responses are JSON. Errors: `{ "error": "<message>" }` with 400 / 404 / 409 / 502 (docker) / 503.
A request the daemon itself rejects (a malformed image reference, an unacceptable body) is a 400, not a
502 — 502 is reserved for the daemon or its registry failing, including rejected registry credentials.

## Meta

| Method & path | Returns |
|---|---|
| `GET /health` | `{ status, version, dockerConnected }` |
| `GET /config.js` | frontend runtime config as JS (from `MC_*` env, `no-store`) |
| `GET /api/server/info` | `{ version, retained, startedAt }` — what the Server Logs page header shows. Separate from `/health`, which the launcher polls and should not grow fields for one page |
| `GET /api/server/logs` | the dashboard's **own** log tail — `?tail=200` (max 1000), `?level=error\|warn\|info\|debug` (anything else is a 400, not an empty page). Newest first, in the same `{ ts, level, source, msg }` shape a container tail returns. Served from an in-memory ring; what falls out of it is still in `docker logs` |

## Docker hosts — registry in SQLite, status probed live (10s cache)

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/hosts` | — | each: `{ id, name, url, kind, status, engine, apiVersion, latencyMs, note }` |
| `POST /api/hosts` | `{ name, url }` | url must be `tcp://host:port`; duplicate urls rejected |
| `POST /api/hosts/{id}/check` | — | forces a fresh probe |
| `DELETE /api/hosts/{id}` | — | local socket host is not removable (400) |

## Containers — read through to the daemon, never cached

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/containers` | `?hostId=`, `?all=true` | filtered by `MC_CONTAINER_FILTER` unless `all`; skips unreachable hosts. `imageDigest` is the registry manifest digest of the image the container runs, or null when it was never pulled from a registry — the only evidence that a container on a floating tag such as `latest` is behind. `release` is the Hermes release the image carries (`2026.8.19`, from `hermes_cli/__init__.py`, read once per image over exec), or null when the container is not running or the image does not say — what the UI shows in place of a floating tag. `ports` lists the container ports the daemon publishes on the host (`{ containerPort, hostIp, hostPort }`, one row per container port, the IPv4 row when an all-interfaces bind is listed twice) — read from the listing, never remembered, so a port mapped by hand counts the same as one a deploy asked for |
| `GET /api/containers/{hostId}/{id}/stats` | — | one-shot sample; `rxBytes`/`txBytes` are cumulative — clients compute rates. `ramMb` excludes the reclaimable page cache, matching what `docker stats` reports rather than raw `memory_stats.usage`. 503 if the daemon returns no sample; a stopped container is a sample of zeros, not an error. |
| `GET /api/containers/{hostId}/{id}/logs` | `?tail=100` (max 500) | container-scoped `{ ts, level, source, msg }`; multiline frames are split, empty records dropped, and explicit severity preserved |
| `POST /api/containers` | `{ hostId, name, version?, profiles?, memoryMb?, cpus?, ports?, env?, mounts?, defaultTemplateId? }` | creates + starts `MC_HERMES_IMAGE:version`, waits for default-profile initialization, then creates each requested named profile. `defaultTemplateId` names a [profile template](#profile-templates--reusable-agent-blueprints-dashboard-owned) to apply to the `default` profile once the gateway is ready — its model settings, key, soul, memory, working dir, skills, MCP servers and guides; an unknown id is 404 before anything is created, and a blueprint that fails rolls the container and volume back like any other step. `version` is validated as an image tag (same rule as the update endpoint) — blank or absent means `latest`. `memoryMb`/`cpus` cap the container; absent means the [Hermes recommendation](https://hermes-agent.nousresearch.com/docs/user-guide/docker) of 2048 MB / 2 cores, never *no* limit. Both are refused below the vendor minimum (1024 MB, 1 core). The ceiling is create-time and is carried onto the replacement by an image update. `ports` (`{ containerPort, hostPort, hostIp? }`, a blank `hostIp` binds `127.0.0.1`), `env` (`{ key, value }`) and `mounts` (`{ source, target, readOnly }`) are the operator's **host access** — create-time, applied to the gateway container only, carried by an image update; a mount of the Docker socket, or onto `/opt/data` or `/opt/hermes`, is 400 with the reason. A writable mount widens `HERMES_WRITE_SAFE_ROOT` unless the request sets it. Every deploy also maps `host.docker.internal` to the host gateway and gives `/dev/shm` 1 GB. Any failure rolls back the container and managed volume; an existing same-name volume returns 409. A gateway that never reports ready is 503, not 500. |
| `POST /api/containers/{hostId}/{id}/start` | — | |
| `POST /api/containers/{hostId}/{id}/stop` | — | 10s graceful timeout. 409 while a profile inside is still being created (`creatingProfiles` on the activity route): the stop would fail that create and roll the profile back |
| `POST /api/containers/{hostId}/{id}/update` | `{ version, ports?, env?, mounts? }` | recreates the container on another tag, reusing its data volume, and returns the **new** `{ id }`. Pulls first, then stops, parks the old container aside, creates the replacement under the same name/labels/networks, and only removes the parked original once readiness passes — a failure restores it. Never re-seeds profiles and never touches the volume. `ports`, `env` and `mounts` are **host access** in the deploy's shape and under its rules (a Docker-socket mount, or one onto `/opt/data` or `/opt/hermes`, is 400), laid over what the container already has: a port asked for again is remapped rather than bound twice, a variable replaces the line carrying its key, a writable mount widens `HERMES_WRITE_SAFE_ROOT` unless the request sets it; nothing is removed. With any of them present the tag the container already runs is a legitimate `version` — the recreate is the point. 400 if the container is not Mission Control-managed or runs another image; 409 if it already runs that tag and nothing was asked for. Also 409 while a profile inside is still being created, for the same reason as `stop`. Held open through readiness, so it can take minutes on a cold pull. |
| `DELETE /api/containers/{hostId}/{id}` | — | force removes the container and its recorded Mission Control-managed volume; unowned/external mounts are preserved |

Container DTO: `{ id, shortId, name, hostId, status, image, version, imageDigest, release, startedAt, sizeRootFsGb, profiles }`
with `status ∈ running | stopped | unhealthy | unknown`.

## MCP server catalog — SQLite definitions + managed Compose lifecycle

Managed entries belong to one immutable Docker host and are rendered into that
daemon's `mission-control-mcp` Compose project. External HTTP/SSE and stdio
entries are registry-only and therefore have no container lifecycle or logs.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/mcp-servers` | — | Redacted catalog records plus desired/runtime/operation state and revisions. `linkedAgents` counts the agent profiles carrying the entry — what a delete disables first. `imageAsOf` is when a managed service's container last started, which is the last time its image was pulled or verified, since every start and apply runs `--pull always`; null unless running. `imageUpdate` is whether Docker Hub publishes a different digest on the image's tag than the container runs — the same digest rule the Hermes containers use — and null whenever that cannot be told (no container, no repo digest, not a Hub image, `MC_REGISTRY_TAGS=false`, registry unreachable with nothing cached) |
| `POST /api/mcp-servers` | structured server definition | Managed creates return 202 and asynchronously pull/create a stopped service; external/stdio return 201. `repoUrl` is optional and must be `http://` or `https://` — it is rendered as a link, so the scheme is checked here rather than trusted to the client |
| `PUT /api/mcp-servers/{id}` | complete structured definition | Kind and managed `hostId` are immutable; running deployment changes remain pending until Apply |

`repoUrl` is documentation: where the entry comes from, for a person to open. Nothing fetches
it — unlike a skill's repository, which `UpstreamCheck` parses to ask GitHub about releases —
and it never reaches a profile's MCP config. It is a top-level column beside `description`
rather than part of `config_json`, which is *how the thing runs*.

Editing it bumps the entry's revision like any other edit, so a managed server with agents
linked to it will show **apply required** afterwards. That is existing behaviour for
`description` too, not a rule this field introduces.
| `DELETE /api/mcp-servers/{id}` | — | Disables/unlinks Agent copies first; managed deletion returns 202 and preserves named volumes |
| `POST …/{id}/start` | — | 202; applies pending config and starts the main/support services |
| `POST …/{id}/stop` | — | 202; stops the main/support services without deleting them |
| `POST …/{id}/apply` | — | 202; recreates a running service or refreshes its stopped container |
| `POST …/{id}/check` | — | Bounded, no-redirect HTTP reachability check for external entries only |
| `GET …/{id}/logs` | `?tail=200` (max 500 per container) | Merged Docker tail for a managed server and its private support services |
| `GET /api/mcp-servers/retained-resources` | — | Named volumes preserved by catalog deletion |
| `DELETE …/retained-resources/{id}` | — | Permanently purges one retained Mission Control-owned volume |

Catalog input uses `kind ∈ managed | external | stdio`. Managed fields include
`hostId`, `image`, optional `platform`, list-form `entrypoint`/`command`,
`internalPort`, optional `publishedPort`, `path`, optional `crossHostUrl`,
environment/headers, named volumes, healthcheck, and private support services.
External entries use `transport + url + headers`; stdio entries use
`stdioCommand + args + environment`. Configuration values have
`{ key, value?, secret, clear? }`; secret values are never returned, only
`set/recoverable` flags. Raw Compose YAML, bind mounts, Docker socket mounts,
host networking, privileged mode, devices, and capabilities are not accepted.

## Agents — Hermes profiles read through `docker exec`

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/agents` | `?hostId=&containerId=` | one DTO per profile (`/opt/data` = `default`, plus `/opt/data/profiles/*`) |
| `POST /api/agents` | `{ hostId, containerId, name, provider, model, apiKey?, apiKeyCredentialId?, cloneFrom?, auxiliary? }` | `hermes profile create`, then sets model + auxiliary tasks + provider API key. `apiKeyCredentialId` names a [saved credential](#credentials--dashboard-owned-state-in-sqlite) to take the key from instead of `apiKey`, and wins when both arrive |
| `DELETE /api/agents/{hostId}/{containerId}/{name}` | — | `hermes profile delete --yes` |
| `GET  …/{name}/logs` | `?tail=100` (max 500) | profile-scoped supervised gateway log from `/opt/data/logs/gateways/{name}`; returns `{ ts, level, source, msg }` |
| `PUT  …/{name}/soul` | `{ soul }` | writes `SOUL.md` |
| `PUT  …/{name}/skills/{skillName}` | `{ enabled }` | toggles `skills.platform_disabled.cli` in `config.yaml` |
| `POST …/{name}/skills` | `{ name }` | `hermes skills install --force` |
| `DELETE …/{name}/skills/{skillName}` | — | removes the skill directory (the CLI uninstall is interactive-only) |
| `POST …/{name}/mcp` | `{ name, transport, url?, command?, args?, enabled?, headers?, environment? }` | adds/upserts one `mcp_servers` entry; persists explicit SSE transport and per-server stdio env |
| `PUT …/{name}/mcp/{serverName}` | same as POST | atomically updates or renames the saved entry; `headers: {}` explicitly clears headers |
| `PUT …/{name}/mcp/{serverName}/enabled` | `{ enabled }` | non-destructive disconnect/reconnect; preserves all connection configuration |
| `DELETE …/{name}/mcp/{serverName}` | — | permanently forgets the saved entry |
| `POST …/{name}/mcp/{serverName}/test` | — | runs Hermes' MCP handshake; returns `{ status, tools, latencyMs, error, checkedAt }` |
| `POST …/{name}/mcp/catalog` | `{ serverId, alias }` | materializes a catalog definition; same-host managed entries attach the Agent container to the MCP network |
| `POST …/{name}/mcp/{serverName}/sync` | — | manually applies the current catalog revision while preserving enabled state |
| `DELETE …/{name}/mcp/{serverName}/link` | — | converts a linked entry into an Agent-local custom definition without deleting config |
| `GET  …/{name}/integrations` | — | every platform in `gateway_state.json`, whatever it is called — not an allowlist |
| `GET  …/{containerId}/activity` | — | what a stop would interrupt: `{ activeAgents, busyProfiles, pausedProfiles, unreadable, creatingProfiles }`, one exec per profile. `creatingProfiles` are still inside `POST /api/agents` — on disk but unconfigured, and hidden from `GET /api/agents` until the create finishes; a stop now fails that create and rolls the profile back. Read on the click, never on the fleet poll |
| `POST …/{name}/pause` | `{ reason? }` | `hermes pause` — holds cron dispatch, kanban dispatch and new gateway turns; in-flight work finishes |
| `POST …/{name}/resume` | — | `hermes resume`; dispatch picks up on the next tick, no restart |
| `POST …/{name}/webhooks/outbound` | `{ url, events[], name?, matcher?, timeout?, secretEnv? }` | appends to `hooks.outbound` in `config.yaml` |
| `PUT …/{name}/webhooks/outbound/{index}` | same as POST | rewrites the target at that position; an inline `secret:` set by hand is preserved |
| `DELETE …/{name}/webhooks/outbound/{index}` | — | 404 on a stale index rather than rewriting its neighbour |
| `PUT  …/{name}/config` | `{ configYaml }` | full config.yaml replace — validated as a YAML mapping (400 otherwise); platform tokens (slack, whatsapp, honcho, …) and `model.default` / `model.base_url` overrides live here |
| `GET  …/{name}/setup` | — | the profile's `.env` and what `hermes status` makes of it; degrades to the `.env` alone when status cannot run |
| `PUT  …/{name}/env` | `{ entries: [{ key, value, credentialId? }] }` | batch `.env` write; **a blank value removes the variable**. `credentialId` names a [saved credential](#credentials--dashboard-owned-state-in-sqlite) to take this key's value from, resolved server-side so the browser never holds it |
| `POST …/{name}/env/init` | — | seeds the `.env` only if the profile has none; answers with the setup either way |
| `GET  …/{containerId}/auth-providers` | — | which vendors this **container** holds credentials for. Container-scoped, not per profile: credentials live in the data volume, so it answers from the `default` profile and takes no name |
| `GET  …/{name}/skills/{skillName}/content` | — | `{ name, path, body, files }` — the skill's own markdown, plus the other files in its directory |
| `PUT  …/{name}/skills/{skillName}/content` | `{ body }` | rewrites that markdown |
| `GET  …/{name}/sessions` | — | recorded conversations, read from the profile's own SQLite store |
| `GET  …/{name}/sessions/{sessionId}` | — | that conversation's messages, passed through as raw JSON |
| `DELETE …/{name}/sessions/{sessionId}` | — | forgets one conversation |

Every agent DTO also carries `gateway`: the profile's own view of itself out of
`gateway_state.json` — `state`/`desiredState` (which differ while it drains),
`activeAgents` (turns in flight), `agentVersion` (the hermes actually running, not the
image tag) and `sessionStore` — plus `paused`/`pauseReason`/`pausedAt` from the `ESTOP`
sentinel. Presence of that sentinel *is* the pause: hermes honours a bare `touch`, so an
unparseable body still reads as paused.

Outbound webhook targets are addressed **by position**, because that is the only handle
hermes gives one — `name` is optional and not unique. They carry no `secret` field in
either direction: hermes accepts an inline secret and calls it discouraged in its own
schema, so only `secretEnv` (the variable's name) travels, and a literal secret already in
the file is reported as `literalSecret: true` without its value and left untouched by an
edit. Hermes reads `hooks.outbound` at gateway startup, so a change lands on the agent's
next restart.

Configured MCP servers report `unknown` until tested, then `connected` or
`error`; disabled entries report `disabled`. Every server DTO also carries an
explicit `enabled` boolean so clients do not need to derive persistence state
from probe status. Catalog-linked entries also expose catalog/synced revisions
and `updateAvailable`. Probe results remain cached only while the server
definition is unchanged.

Create (`POST /api/agents`) accepts optional `baseUrl`; when set, the profile's
`model.default` + `model.base_url` are written directly (ollama / any
OpenAI-compatible endpoint) and no provider API key is required.

Create also pins hermes' auxiliary side tasks — compression, summarization,
memory flush and the rest — to the profile's own provider/model, and fails the
create (rolling the profile back) if `config.yaml` ends up without a model.
`hermes profile create` never seeds `config.yaml`, and every auxiliary slot ships
as `provider: auto`, which resolves through the main model before OpenRouter /
Nous / a custom endpoint — so a profile with no model config logs
`no provider available … compression, summarization, and memory flush will not
work` on its first long session.

Optional `auxiliary: { provider?, model, baseUrl?, apiKey? }` runs those side
tasks on a different model — useful when the main model is expensive, since side
tasks are frequent, short and mechanical. `model` is the only required field; a
blank `provider` means "same provider as the main model" and inherits its
endpoint. `vision` is deliberately left on `auto`: its chain skips a main model
known to be text-only and falls back to OpenRouter/Nous, so pinning it would aim
image payloads at a model that may reject them.

## Scheduled jobs and inbound webhooks — hermes' own, driven the way profiles are

Both are per profile while their pages are per container, so each listing fans out over the
container's profiles. **Reads come from the files hermes owns; writes go through its CLI** —
hermes parses the schedule expression, mints the job id, computes the next run and generates
each route's HMAC secret, so a write composed here would be a second implementation of its
rules. The reasoning is in
[architecture.md](architecture.md#scheduled-jobs-and-inbound-webhooks).

Every cron mutation answers with the **whole schedule as hermes now holds it**, so the
dashboard never guesses what a create or an edit produced. hermes exits 0 whether or not it
did what was asked — `Failed to create job: Invalid schedule '…'` and `Job not found: …` are
exit 0 too (v0.20.5) — so the verdict is read off its stdout: a `not found` is a 404, any other
`Failed …` is a 400 carrying hermes' first line.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET  …/{name}/cron` | — | read from `<profile>/cron/jobs.json`, not from the table `hermes cron list` prints. Reports when the gateway is down: hermes stores jobs either way, but nothing fires them |
| `POST …/{name}/cron` | `{ schedule, prompt, name?, deliver?, repeat?, skills? }` | `schedule` and `prompt` are what hermes needs; blank optionals are left off the command line. The positionals follow a `--`, so a schedule that reads like a flag is not parsed as one — hermes answered `--help` with its usage and exit 0, which read here as a job created |
| `PATCH …/{name}/cron/{jobId}` | same fields, all optional | a null is left alone |
| `POST …/{name}/cron/{jobId}/pause` | — | holds this **job**; not the profile-wide `…/{name}/pause` above |
| `POST …/{name}/cron/{jobId}/resume` | — | the counterpart to the row above |
| `POST …/{name}/cron/{jobId}/run` | — | asks for the job on the next scheduler tick rather than waiting for its schedule |
| `DELETE …/{name}/cron/{jobId}` | — | |

Only a `cron` schedule carries an expression — `once` stores a timestamp and `interval` a
minute count — so the UI shows hermes' own display string, the one form every kind has.

Inbound routes live in `<profile>/webhook_subscriptions.json`, keyed by route name. **Mission
Control never carries webhook traffic**, so nothing here is an endpoint a provider would call.
`WebhookPlatformDto.published` is read from the container's port bindings on the daemon: true
when the listener's port is published — asked for on the deploy form or on a container update,
or mapped by hand when the container was recreated — and the page says a route is unreachable
until then.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET  …/{name}/webhooks` | — | routes plus the listener's state. Secrets appear only as a masked tail |
| `PUT  …/{name}/webhooks/platform` | `{ enabled, host?, port? }` | turns the listener on or off and says where it binds. **One listener port per container, not per profile**: profiles share a network namespace, so this refuses a port another profile already holds and walks a defaulted one up from 8644 |
| `POST …/{name}/webhooks` | `{ name, prompt?, description?, events?, skills?, deliver?, deliverChatId?, deliverOnly? }` | only `name` is required — hermes generates the HMAC secret, so no secret ever travels through the dashboard to reach it. 409 while the listener is off: hermes answers the command with a setup walkthrough and exit 0, so the dashboard would otherwise report a route it never created |
| `GET  …/{name}/webhooks/{route}/secret` | — | the full HMAC secret the sending provider needs. **Its own endpoint on purpose**: a secret must not ride along in the listing the dashboard polls |
| `POST …/{name}/webhooks/{route}/test` | — | fires hermes' own test POST at the route; 409 while the listener is off, as above; 404 for a route hermes does not hold — it says `No subscription named` and exits 0 |
| `DELETE …/{name}/webhooks/{route}` | — | 409 while the listener is off, 404 for a route hermes does not hold, as above |

## Profile templates — reusable agent blueprints, dashboard-owned

The one concept in this area with no home inside hermes, so it lives in SQLite. Template API
keys and captured MCP config values are encrypted at rest under `MC_SECRET_KEY`; a blank
secret on an update means *keep the one you hold*, because the editor never receives
ciphertext to send back.

| Method & path | Body | Notes |
|---|---|---|
| `GET /api/profile-templates` | — | |
| `GET /api/profile-templates/{id}` | — | |
| `POST /api/profile-templates` | `{ name, icon?, description?, category?, provider?, model?, baseUrl?, cwd?, soul?, memory?, skills?, librarySkillIds?, guideIds?, mcpServers?, secrets? }` | `skills` are Skills Hub ids installed by name; `librarySkillIds` and `guideIds` name rows of the [skill library](#skills--dashboard-owned-state-in-sqlite) and its guides, resolved when the template is deployed. Each secret is `{ key, value?, credentialId? }` — see [credentials](#credentials--dashboard-owned-state-in-sqlite) for the id form |
| `PUT /api/profile-templates/{id}` | same as POST | |
| `DELETE /api/profile-templates/{id}` | — | |
| `POST /api/profile-templates/capture` | `{ hostId, containerId, name, templateName? }` | builds a template out of a live profile |
| `POST /api/profile-templates/{id}/deploy` | `{ hostId, containerId, name? }` | creates the profile and answers with it — enriched with its catalog links like every other profile this API returns. `name` is folded to lower case, which is what `hermes profile create` does with it. A library skill or guide the template names that is gone answers 404 and the profile is rolled back; a guide's parts go on through the same path as its own deploy, and one that fails rolls the profile back too |

## Credentials — dashboard-owned state in SQLite

A key or token saved once and offered as a dropdown wherever one is typed: an agent's `.env`,
the create-agent dialog, a blueprint's keys tab. Encrypted at rest under `MC_SECRET_KEY`
through the same boundary the templates and the MCP catalog use, so a blank secret on an update
means *keep the one you hold*.

A credential is a **bundle** of variables, not one key, because that is the shape of the things
being saved: a messaging platform is a bot token plus a home channel, and a self-hosted provider
is a base URL plus a key. One row per variable would make an operator save and pick the halves
separately.

**No route here resolves a value, so none can return one.** A secret entry reports `set` and
`recoverable` and carries no `value`, not even a suffix; a non-secret entry's value is returned,
because a home channel is nothing to hide and a picker that could not show it would be useless.
The three writes that take a credential id belong to the resources they write, listed below, and
each resolves the id server-side — so key material never reaches the browser at all, which is
stricter than `GET /api/agents/.../webhooks/{route}/secret`.

**Autofill only.** Picking a credential copies its value into the target then and there and
nothing records that it happened. Deleting one breaks nothing already written, and rotating one
changes nothing already written — a profile's `.env` is a file inside a container, so no stored
association could propagate to it without a re-push either way.

| Method & path | Body | Notes |
|---|---|---|
| `GET /api/credentials` | — | by name, `NOCASE` — this list is a dropdown, so an option that moves when an unrelated row is renamed makes the picker unreadable |
| `POST /api/credentials` | `{ name, description?, entries?: [{ key, value, secret }] }` | `name` is `NOCASE UNIQUE`. Each `key` must match `[A-Z][A-Z0-9_]{1,63}` — the strict `.env` form, not the looser MCP env-key rule, because a `.env` is the only place these land. Max 32 entries. A blank value on a *new* secret is a 400, not a save that stored nothing |
| `PUT /api/credentials/{id}` | same body | replaces the entry list — an entry left out is one the editor deleted, so there is no `clear` flag. A blank secret keeps and re-seals the stored envelope; one this key can no longer open is preserved, never overwritten. 404 rather than an insert |
| `DELETE /api/credentials/{id}` | — | idempotent, and reaches nothing |

Credential DTO: `{ id, name, description, entries, createdAt, updatedAt }`, where each entry is
`{ key, value, secret, set, recoverable }` and `value` is null whenever `secret`.

There is deliberately no apply-to-an-agent route. Picking a credential on the Setup tab fills
every row that credential covers, so a bundle is already one choice; a route that wrote them in
one request would save a button press and duplicate the resolution below.

Three routes take a credential id instead of a typed value, each resolving it server-side. A
secret the current key cannot decrypt fails the whole write with a 409 rather than writing a
blank:

| Route | Field | Resolves against |
|---|---|---|
| `PUT /api/agents/{hostId}/{containerId}/{name}/env` | `entries[].credentialId` | that entry's own `key` |
| `POST /api/agents` | `apiKeyCredentialId` | the chosen provider's API-key variable, from `ModelProviderRegistry` — a provider that takes no key is a 400, not a silent drop |
| `POST` / `PUT /api/profile-templates` | `secrets[].credentialId` | that secret's own `key`. Copies the credential's sealed envelope verbatim: same `MC_SECRET_KEY` on both sides, so nothing decrypts. An unrecoverable envelope is refused rather than carried forward, where it would look freshly stored |

## Model catalogs — what the create-agent form offers

| Method & path | Body | Notes |
|---|---|---|
| `GET /api/providers` | — | the LLM **vendor** registry: `{ key, label, needsKey, oauth, hasCatalog, envVar }` per vendor. Compiled in, not stored — it mirrors hermes' own `CANONICAL_PROVIDERS` order and provider records, and a database row would let it drift from the CLI it has to agree with. The picker, the "needs an API key?" rule and the provider→catalog decision all read this one list. API-key OpenAI is `openai-api`, hermes' spelling since v0.21.0; the retired `openai` is accepted on every write and folded to it |
| `GET /api/models/{provider}` | — | what the picker offers. A list stored by the background refresh wins (`source: catalog`); otherwise the curated `mc.models` list from `application.yml` (`source: config`). 404 for a provider with neither |
| `POST /api/models/{provider}` | `{ apiKey }` | live fetch from the provider's `/v1/models` (truth source); falls back to the config list on any failure |

**Background model-catalog refresh.** Twice a day (`@Scheduled`, 12h fixed delay, first run ~45s after boot) Mission Control re-reads the model list of every provider whose listing endpoint needs no credential, and stores it. Measured against each endpoint unauthenticated:

| provider | endpoint | unauthenticated |
| --- | --- | --- |
| OpenRouter | `openrouter.ai/api/v1/models` | 200 — refreshed |
| NVIDIA NIM | `integrate.api.nvidia.com/v1/models` | 200 — refreshed |
| Nous | `inference-api.nousresearch.com/v1/models` | 200 — refreshed |
| Anthropic, OpenAI, xAI, DeepSeek, Kimi, Z.AI, StepFun, MiniMax | their `/v1/models` | 401 — curated list only |
| Google AI Studio | `generativelanguage.googleapis.com/v1beta/models` | 403 — curated list only |

The eight-plus keyed providers keep their curated list; `POST /api/models/{provider}` with a caller-supplied key remains the way to read them live. A provider that fails, or answers 200 with no models, keeps whatever was stored before rather than emptying the picker. Set `MC_MODEL_CATALOG_REFRESH=false` to switch the job off.

## Inference endpoints — self-hosted model servers in SQLite

Endpoints are registered by url. Agents never come through this API at all — they consume
an endpoint over its OpenAI-compatible `{url}/v1` surface, which is why any runtime serving
that works as a `baseUrl` above regardless of what is registered here. This page is
*management*, and how much of it an endpoint supports depends on its protocol.

### Kinds

**`kind` is probed, never stored.** Only the url is persisted. Which protocol answers there is
a property of the server, so it is resolved by the same probe that reports status — cached
10s — and reported on every response.

| `kind` | Probe | Lists models | Manages models |
|---|---|---|---|
| `ollama` | `GET {url}/api/version` | `GET {url}/api/tags` — name, params, family, size, modified | yes — pull, delete, load, unload, and `GET {url}/api/ps` for what is resident |
| `openai` | `GET {url}/v1/models` | `GET {url}/v1/models` — id and optional timestamp only | **no** |
| `null` | nothing answered | — | — |

`openai` covers LM Studio, MLX (`mlx-lm.server`), vLLM and llama.cpp's server. None of them
has an HTTP pull, delete or load — models get onto the box and into memory out of band — so
those calls are refused with 400 rather than attempted, and `canManageModels` on the response
tells the dashboard to hide the controls. `GET …/running` is the one exception: it answers
`[]` rather than 400, because "cannot report" and "nothing resident" render the same.

Detection asks ollama **first**, and the order is load-bearing: ollama serves an
OpenAI-compatible `/v1` *as well as* its own API, so probing `/v1/models` first would file
every ollama server as `openai` and silently strip its pull and delete. The order is the
clients' `@Order`, which is how Spring hands them over.

Because nothing is stored, registering does **not** require the server to be up: an endpoint
that is switched off is added, reports `status: error` and `kind: null`, and resolves itself
when it answers. It also cannot go stale — put a different server behind the same url and
the next probe says so.

Adding a protocol is one `EndpointClient` bean. There is no schema change and no list to
keep in sync.

The route and the table are `inference-endpoints` / `inference_endpoints`. Both shipped as
`model-providers` / `model_providers` and were renamed once the concept had a name — an
endpoint is a url you run, not a vendor. `SchemaUpgrades` carries an older database across
and drops the old table. Not to be confused with `/api/providers`, which is the model
**vendor** registry (Anthropic, DeepSeek, Ollama Cloud) and their API keys.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/inference-endpoints` | — | probe resolves status **and** `kind` (10s cache); `version` is null for `openai` |
| `POST /api/inference-endpoints` | `{ name, url }` | http(s) urls only; duplicates rejected; a server that is down is still registered |
| `POST /api/inference-endpoints/{id}/check` | — | fresh probe |
| `DELETE /api/inference-endpoints/{id}` | — | |
| `GET /api/inference-endpoints/{id}/models` | — | proxied per kind |
| `GET /api/inference-endpoints/{id}/running` | — | what is loaded in memory, and the VRAM it holds; `[]` where the protocol cannot say |
| `POST /api/inference-endpoints/{id}/models/pull` | `{ name }` | 202; async pull, progress via `GET …/pulls`; **400 unless `canManageModels`** |
| `POST /api/inference-endpoints/{id}/models/delete` | `{ name }` | **400 unless `canManageModels`** |
| `POST /api/inference-endpoints/{id}/models/load` | `{ name }` | pins the model in memory (`keep_alive: -1`); **blocks** while the weights load, up to 3 min; **400 unless `canManageModels`** |
| `POST /api/inference-endpoints/{id}/models/unload` | `{ name }` | frees its VRAM immediately; **400 unless `canManageModels`** |

A pull is streamed from ollama, so `GET …/pulls` reports `detail` as `47% · pulling <digest>`
while it runs and as the failure reason if it fails. A pull that has begun streaming is a 200
whatever happens next — an unknown model arrives as `{"error": …}` inside the body — so that
line is the only thing distinguishing a failed pull from a slow one.

Load pins with `keep_alive: -1` rather than ollama's default five minutes: it is a button an
operator pressed, and a start that wears off while they watch the row reads as a bug. Unload
is the other half of that.

## Images

| Method & path | Params | Notes |
|---|---|---|
| `GET /api/images/tags` | `?hostId=`, `?remote=true` | tags of `MC_HERMES_IMAGE` from the host's image store merged with the registry's published tags, newest first. `repository` is always the bare repository — a tag on `MC_HERMES_IMAGE` is stripped, so it still matches the `image` every container DTO reports. |

Returns `{ repository, tags, entries, newest, registryStatus, registryDetail, registryCheckedAt }`.
`tags` is every known tag as a flat list; `entries` is the same order as
`{ tag, pulled, remote, lastUpdated, sizeBytes, digest }`, so callers can tell a
locally cached tag from one that still needs a pull. `newest` is the highest
pinned release — floating tags (`latest`, `main`, `edge`, `nightly`, `dev`) are
excluded, since calling a moving pointer "newest" would mark every pinned
container permanently out of date.

Remote lookup is Docker Hub only and is cached per repository for 10 minutes
(failures for 1 minute), so callers may poll this freely. It never fails the
request: `registryStatus` reports `ok | cached | unavailable | unsupported |
disabled` and the response falls back to local tags. Repositories on another
registry report `unsupported`; `MC_REGISTRY_TAGS=false` reports `disabled`.
Ordering handles calendar tags of any depth, so `v2026.7.7.2` ranks between
`v2026.7.20` and `v2026.7.7`.

## Web terminal — WebSocket bridge to `docker exec`

| Endpoint | Params | Notes |
|---|---|---|
| `WS /ws/terminal` | `?hostId=&containerId=` | spawns `bash -i` (or `sh -i`) with a tty inside the container |

Protocol: binary frames carry raw terminal bytes both ways; text frames carry
client control messages — `{ "type": "resize", "cols": n, "rows": n }`.
Handshake enforces same-origin (or the dev origins `localhost:4200/4300`).
The exec ends when the socket closes (stdin EOF exits the shell).

The shell runs as `mc.terminal.user` (`MC_TERMINAL_USER`, default `hermes`) — the
same user the profile-scoped execs above use, so a command typed here cannot
leave root-owned files in `/opt/data`. Set it empty for an image with no `hermes`
account.

## Ops board — dashboard-owned state in SQLite

| Method & path | Body / params |
|---|---|
| `GET /api/board/tasks` | `?containerId=` |
| `POST /api/board/tasks` | `{ containerId, agentId?, title, column?, priority?, tags? }` |
| `PATCH /api/board/tasks/{id}` | `{ column }` — `queued | running | review | done` |
| `DELETE /api/board/tasks/{id}` | — |

## Prompt library — dashboard-owned state in SQLite

Reusable prompt text with a category, notes and tags. Nothing inside a Hermes container
reads this: it is a dictionary the dashboard keeps so a prompt can be found again and
pasted where it is needed.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/prompts` | `?category=` | newest edit first; the filter is case-insensitive and a blank one is not a filter |
| `GET /api/prompts/{id}` | — | 404 for an id nobody holds |
| `POST /api/prompts` | `{ title, body, category?, notes?, tags? }` | `title`/`body` required; a blank category becomes `general`, categories and tags are trimmed and lower-cased, blank/duplicate tags dropped (max 12) |
| `PUT /api/prompts/{id}` | same body | replaces everything an editor owns and keeps `createdAt`; 404 rather than an insert when the prompt is gone |
| `DELETE /api/prompts/{id}` | — | idempotent |

Prompt DTO: `{ id, title, body, category, notes, tags, createdAt, updatedAt }`.

A fresh install is seeded with one sample prompt, once — the marker lives in
`prompt_meta`, so a sample an operator deletes does not come back at the next boot.

## Skill library — dashboard-owned state in SQLite

Skills the dashboard holds, deployable onto any agent. Distinct from the per-agent routes
under `/api/agents/…/skills`, which read and edit what one profile already has: a library
row may live on no agent at all.

A row's `kind` decides what it holds and how it deploys, and the two are mutually
exclusive. A `hub` row is a pointer — the Skills Hub owns the content, so the row carries
only the id and a deploy shells `hermes skills install`. A `local` row owns its files,
because nothing else can install it: hermes has no `skills create`, so a skill authored in
the dashboard or written by an agent's own curator has no hub id, and writing the files out
is the only way to place it. Keeping a copy of a hub skill here would be a second source of
truth that goes stale the moment the Hub moves, which is why the split exists.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/skills` | `?category=` | newest edit first; a blank category is not a filter |
| `POST /api/skills` | `{ kind, name, description?, category?, repoUrl?, version?, files? }` | `kind` is `hub` or `local`; `name` must match `[a-zA-Z0-9][a-zA-Z0-9_.-]*` — it becomes both an argv word and a directory name. A blank category becomes `general` and is lower-cased. A `local` row needs a root `SKILL.md`, and a `hub` row carrying files is a 400. `repoUrl` is optional and must be `http://` or `https://`, the same rule the MCP catalog applies — it is rendered as a link |
| `PUT /api/skills/{id}` | same body | replaces everything an editor owns and keeps `createdAt`; 404 rather than an insert when the skill is gone |
| `DELETE /api/skills/{id}` | — | idempotent. Removes the library row only — a copy already deployed onto an agent stays exactly where it is |
| `POST /api/skills/{id}/deploy` | `{ hostId, containerId, profile }` | puts the skill on one agent and answers with the refreshed profile. A `hub` row runs `hermes skills install <name> --force`; a `local` row has its files written into `<profileDir>/skills/<name>/` |
| `GET  /api/skills/{id}/upstream` | — | whether the skill's repository has moved on; see below |
| `POST /api/skills/import` | `{ hostId, containerId, profile, skillName, category? }` | copies a skill off an agent into the library, always as `local`. Re-importing a name updates that row rather than colliding with the unique index, keeping the description and repo link an operator typed |

Skill DTO: `{ id, kind, name, description, category, repoUrl, version, files, createdAt,
updatedAt }`, where `files` is `[{ path, body }]` and is null for a hub row. Import answers
`{ skill, skipped }` — `skipped` names files left behind because they hold a NUL byte: the
exec pipe is UTF-8, so a binary asset cannot round-trip through it, and the importer says so
rather than storing the corruption.

The upstream check answers `{ status, latest, detail, checkedAt }` with `status` one of
`current | update | unknown | unsupported | unavailable`. It is a call of its own rather
than a field on the row, because it reaches the network and the library list is read on
every page load.

**The stored `repoUrl` is never fetched.** It is parsed to an owner and repository, both
validated against GitHub's own name charset, and the API URL is built from those two words —
so a URL an operator typed cannot make the server issue a request of their choosing. Anything
that is not a `https://github.com/<owner>/<repo>` root answers `unsupported` without a
lookup.

The two rules are deliberately not the same rule. **The save** admits `http://` and
`https://` and nothing else, because that is the only question a store rendering an `href`
gets to ask — `common/Text.httpLinkOrNull`, shared with the MCP catalog so the two cannot
drift again. **The check** is stricter still and refuses everything else with `unsupported`,
because it is the one that reaches the network. A link that saves and reports `unsupported`
is a valid state: a skill kept from GitLab is a row worth having.

The save rule is newer than the field, so **a row stored before it fails its next save** —
the editor sends `repoUrl` back with everything else, so an operator editing an unrelated
field on such a row gets a 400 naming it, and clearing the field is the way out. That is on
purpose: validating only a *changed* value would let a stored `javascript:` outlive every
future save, which is the one thing a guard on an `href` must not allow. Such a value is
already inert — Angular refuses to bind it and the upstream check reads nothing from it.

GitHub is read at `releases/latest`, falling back to the newest tag for a repository
that cuts no releases.

Readings are cached ten minutes per repository (one minute for a failure) and the check never
throws — an unreachable GitHub answers `unavailable`, since the row is usable without it.
`update` means the two version strings differ after dropping a leading `v`, **not** that
upstream is ahead: `version` is free text an operator typed, so ordering it would be a guess
presented as a fact. Both values are reported and the person decides.

A local deploy is an **overlay, not a sync**. It writes the files the row holds and removes
nothing, so a file renamed in the library leaves its old copy on the agent; removing that
one is the agent's own Skills tab.

A skill deployed from the library writes into `<profileDir>/skills/<name>/` and nowhere
else: every relative path is validated segment by segment against the profile-name rule
before it is concatenated, at most three segments deep — matching the `find -maxdepth 3`
that lists them back — and nothing is written if any path in the set is rejected.

## Prompt groups — dashboard-owned state in SQLite

How the prompt library is filed: a named set of prompts. Organization only — no behaviour and
no route that reaches a container, because a prompt is text for a person to paste.

A different axis from a prompt's `category` and `tags`, which are a word and a loose label on
one row: a group is a record, so it can be renamed, described and emptied as a unit.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/prompt-groups` | — | by name, not newest edit: these are the headers the prompt list is filed under, so their order is the page's reading order |
| `POST /api/prompt-groups` | `{ name, description?, promptIds? }` | `name` carries no charset rule — nothing writes a group anywhere — but it is `NOCASE UNIQUE`, so a second `TRIAGE` beside `triage` is a 409. Blank, null and duplicate prompt ids are dropped, order kept |
| `PUT /api/prompt-groups/{id}` | same body | replaces the membership; keeps `createdAt`; 404 rather than an insert |
| `DELETE /api/prompt-groups/{id}` | — | idempotent. Every prompt the group named stays in the library — only the filing goes |

Group DTO: `{ id, name, description, promptIds, createdAt, updatedAt }`.

`promptIds` is stored as given and never checked against the `prompts` table, for the same
reason a skill group's ids are not: the rows can be deleted at any time, so they are resolved
on read and what is gone is dropped.

The near-twin of [skill groups](#skill-groups--dashboard-owned-state-in-sqlite), and
deliberately a separate table and controller rather than one polymorphic `groups` table with a
`kind` column — the two hold ids from different tables, and every read would have to be told
which.

## MCP groups — dashboard-owned state in SQLite

A named set of catalog entries, deployable onto an agent in one action. The only group in this
application whose noun *does* something: a skill group and a prompt group file a library, this
one also has a deploy, because the set an agent needs is usually several servers at once.

**It records no agents.** Deploying a group connects each of its servers to one agent, and every
one of those connections is already a row in `mcp_agent_links`. Which agents a group reaches is
therefore derived from those links on every read, so the count can only ever say what the links
say. A stored group-to-agent association would be a second source of truth: disconnect one
server on the agent's own MCP tab and it would still claim the group was connected.

Many-to-many in both directions falls out of that with no table saying so — the same group
deploys onto as many agents as you like, an agent's links may come from several groups and from
servers connected individually, and a server in two groups counts toward both.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/mcp-groups` | — | by name. Each group carries `agents`, derived from `mcp_agent_links`: one row per agent connected to *any* of its servers, with `linked` counting how many, most complete first |
| `POST /api/mcp-groups` | `{ name, description?, serverIds? }` | `name` carries no charset rule — unlike an alias it never reaches a profile's config — but it is `NOCASE UNIQUE`, so a second `RESEARCH` beside `research` is a 409. Blank, null and duplicate server ids are dropped, order kept: that is the order a deploy connects them in |
| `PUT /api/mcp-groups/{id}` | same body | replaces the membership; keeps `createdAt`; 404 rather than an insert |
| `DELETE /api/mcp-groups/{id}` | — | idempotent, and reaches no agent: every connection the group ever made stays, and so does every server in the catalog. Only the set goes — disconnecting is the agent's own MCP tab |
| `POST /api/mcp-groups/{id}/deploy` | `{ hostId, containerId, profile }` | → `{ profile, parts }` — connects every server in the group to one agent, one `DeployedPart` per server |

Group DTO: `{ id, name, description, serverIds, agents, createdAt, updatedAt }`, where each
agent is `{ hostId, containerId, profile, linked }`.

The deploy follows the same rule a guide's does — *surface the error, do not roll back* — because
it is several independent writes to an agent someone else owns. Undoing half of it would mean
disconnecting servers that may have been on that agent before the group ever ran.

**An alias the agent already has is `skipped`, not `failed`**, with the reason `already
connected`. Topping up an agent that holds part of the group is the ordinary use of this button,
so calling it a failure would paint the normal case red. A guide's deploy answers the same way,
and both get the answer from `AgentMcpCatalogService.connectIfAbsent` rather than from the text
of a conflict — which is how the two used to disagree. A server gone from the catalog is
`skipped` with `no longer in the catalog`; anything else is `failed` with its message.

## Skill groups — dashboard-owned state in SQLite

How the library is filed. A group is a named set of skills and, optionally, the guide that
explains them — organization only, so there is no deploy here and no route that reaches a
container.

A different axis from a skill's `category`, which is one word on one skill: a group is a row,
so it can be renamed, described, pointed at a guide, and hold skills that disagree about
their category.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/skill-groups` | — | by name, not newest edit: these are the headers the skills list is filed under, so their order is the page's reading order |
| `POST /api/skill-groups` | `{ name, description?, skillIds?, guideId? }` | `name` is a label and carries no charset rule — nothing writes a group to disk — but it is `NOCASE UNIQUE`, so a second `PDF` beside `pdf` is a 409. Blank and duplicate skill ids are dropped, order kept. A blank `guideId` is stored as null |
| `PUT /api/skill-groups/{id}` | same body | replaces the membership and the guide link; keeps `createdAt`; 404 rather than an insert |
| `DELETE /api/skill-groups/{id}` | — | idempotent, and reaches nothing: every skill the group named stays in the library and the guide it pointed at stays where it is. Only the filing goes |

Group DTO: `{ id, name, description, skillIds, guideId, createdAt, updatedAt }`.

`skillIds` and `guideId` are stored as given and never checked against the other tables. The
rows behind them can be deleted at any time — production runs with `PRAGMA foreign_keys` off,
so a CASCADE would be decoration — so both are resolved on read and the page marks what is
gone. Validating on write would only move the lie earlier.

The association points group → guide rather than the other way about. A guide already owns
the set it deploys; a group that wants deploying links the guide that does it rather than
growing a deploy of its own.

## Guides — dashboard-owned state in SQLite

A guide is prose that teaches how to use several library skills together, with the MCP
servers they need, plus the ids of both. Deploying one is three things at once: every skill
onto the agent, every MCP server linked to it, and the guide itself written into the agent's
skills directory as an umbrella `SKILL.md`.

That last part is the point. Hermes' own curator authors umbrella skills exactly like this,
so the agent reads the guide too and knows when to reach for the set rather than for one of
the parts. The guide's `name` is therefore a directory name as well as a label, and carries
the same charset rule as a skill's.

| Method & path | Body / params | Notes |
|---|---|---|
| `GET /api/skill-guides` | `?category=` | newest edit first |
| `POST /api/skill-guides` | `{ name, description?, body, category?, skillIds?, mcpServerIds? }` | `name` must match `[a-zA-Z0-9][a-zA-Z0-9_.-]*`; `body` is required — a guide with no prose teaches nothing. Blank and duplicate ids are dropped, order kept |
| `PUT /api/skill-guides/{id}` | same body | keeps `createdAt`; 404 rather than an insert |
| `DELETE /api/skill-guides/{id}` | — | idempotent, and reaches no agent: everything the guide ever deployed stays where it is, including its umbrella skill |
| `POST /api/skill-guides/{id}/deploy` | `{ hostId, containerId, profile }` | → `{ profile, parts }` — see below |

Guide DTO: `{ id, name, description, body, category, skillIds, mcpServerIds, createdAt,
updatedAt }`.

**The deploy is not atomic, and says so.** It is several independent writes to an agent
someone else owns, and they fail one at a time: a skill deleted from the library since the
guide named it, an MCP alias already taken on that agent, a managed server that is not
running. So it follows the rule `TemplateApplier` states for layering onto a profile the
caller does not own — surface the error, do not roll back — and answers with one row per
part rather than a single status:

```
{ "kind": "skill" | "mcp" | "guide",
  "name": "pdf-tools",
  "status": "deployed" | "skipped" | "failed",
  "detail": null }
```

`skipped` means the part is gone from the library or the catalog, or — for an MCP server — is
already on the agent (`already connected`), which the umbrella document still names because the
agent can in fact reach it. `failed` means the write was attempted and refused, and `detail`
carries the reason. `profile` is null when the agent
could not be read back afterwards — the parts had already landed by then, so the report is
answered without it rather than thrown away with a 500.

A guide whose `name` matches a library skill's is refused at deploy, as a `failed` part: both
resolve to `skills/<name>/`, so writing the umbrella there would replace that skill's own
`SKILL.md`. The skills the guide names still deploy; only the umbrella is held back. Undoing half a guide would mean
removing skills and MCP entries that may have been on that agent before the guide ever ran,
so reporting beats guessing.

The umbrella skill is written **last** and names only the parts that actually landed. Telling
an agent to reach for a skill that then failed to deploy is worse than not mentioning it. Its
frontmatter is generated rather than authored — `HermesSkills.parseSkillMeta` has to parse it,
and a description containing a colon would otherwise produce a skill hermes cannot read. A
redeploy overwrites that file, so an edit made to it on the agent does not survive.

## Roadmap (not implemented)

- SSE/WebSocket streaming for logs and stats (currently polled).
- TLS for remote daemons; authentication for the dashboard itself.
