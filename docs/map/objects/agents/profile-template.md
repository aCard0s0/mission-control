---
type: object
cluster: agents
universe: live
status: verified
verified: claude/hermes-openai-api-key @ 6b6a014 · 2026-09-05
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/templates/
---

# Profile template

A reusable recipe for creating a [profile](profile.md): provider, model, SOUL, memory, skills,
MCP servers, guides and its own API keys. `ProfileTemplate` / `ProfileTemplateDto`,
`profile_templates` table, served at **`/api/profile-templates`**.

## Why this shape

The one dashboard-owned concept in this cluster — a template has no home inside hermes, so it
lives in SQLite. It is also why [`ProfileSpec`](profile.md) exists: the template deploy path
creates a profile without ever serving an HTTP request.

**Template secrets are encrypted at rest** with the same key as MCP config values
(`MC_SECRET_KEY`). `TemplateSecrets` holds the shapes; the rules live in
`secrets/SecretsAtRest` — shared, because this package and `mcp/McpConfigStore` had implemented
all four of them separately and they **had drifted** (`agents/templates/TemplateSecrets.java:11`).
The drift that mattered: a blank submission means "I did not touch this secret", because the
editor never received the ciphertext to send back (`encryptOrKeep`, `:40`).

## Three lists of skills, resolved three ways

`skills` holds Skills Hub ids and deploys through `hermes skills install`; `library_skill_ids`
names rows of the [skill library](../dashboard/skill-library.md) and deploys through the
library's own `SkillDeployer`, so a `local` row — which the Hub has never heard of — lands as
files; `guide_ids` names [guides](../dashboard/guide.md), each of which goes on through
`GuideDeploy` — its skills, its MCP servers, and the umbrella `SKILL.md` — in the order that
class keeps. The library and the guides are **references, not copies**: picking one in the
editor stores its id, and the next deploy reads whatever the row says then.

A reference that is gone behaves differently here than on a guide's own deploy. A guide layers
onto an agent that exists and reports the part as `skipped`; a template is creating the agent
the operator asked for, so `TemplateApplier` fails the deploy and the rollback runs. The
difference is deliberate, and so is saying it out loud: hermes answers exit 0 to a hub install
of a name it cannot find, so a template that fell back to that path would report a success it
did not have.

## The name hermes gives it

`hermes profile create` folds the name to lower case (its rule is
`[a-z0-9][a-z0-9_-]{0,63}`), so a blueprint called `Coach` created `profiles/coach`, every
later `-p Coach` missed it — argparse read the name as a subcommand and answered with its usage
text — and the rollback's `test -d profiles/Coach` found nothing, leaving a half-built profile
on hermes' default model. `ProfileSpec`'s canonical constructor folds the name once, so the
create, the config writes, the `.env` and the rollback all address the same directory; the
deploy dialog shows the folded name before the click.

## Shape

`profile_templates` — `schema.sql:38`. Columns added **after the table shipped** — `icon`,
`category`, `library_skill_ids`, `guide_ids` — are handled by `config/SchemaUpgrades.java`
rather than by editing the CREATE statement alone; that is the pattern for any further column.

JSON-encoded columns: `skills` (Skills Hub ids), `library_skill_ids` (skill library row ids),
`guide_ids` (guide ids), `mcp_servers` (`McpServerSpec`), `secrets` (`{key, enc}`, AES-GCM
ciphertext).

`cwd` is written on deploy as `terminal.cwd` through `HermesProfiles.setWorkingDir` — the editor
had offered a working dir since the table shipped, and nothing applied it.

`provider` is stored and served through `ModelProviderRegistry.normalizeKey`, so a blueprint
saved as `openai` before hermes renamed that key reads, deploys and re-saves as `openai-api` —
see [provider](../models/provider-registry.md), "When hermes renames a key".

## Connected to

- **owns:** its encrypted secrets and its MCP snapshot (`TemplateMcpSnapshots`,
  `TemplateMcpConfigValue`)
- **owned-by:** the dashboard
- **joins:** [Provider](../models/provider-registry.md) by `provider`;
  [MCP server entry](../mcp/mcp-server-entry.md) via `mcp_servers` snapshots;
  [skill library](../dashboard/skill-library.md) rows and [guides](../dashboard/guide.md) by id
  — **not** by foreign key, for the reason the guide card gives; produces a
  [profile](profile.md) through `TemplateApplier` — a new one (`deployNew`), a caller's own
  (`layerOnto`, model left alone), or a new container's `default` (`configureAndApply`, model
  written too, because the image made that profile with hermes' defaults and nobody else has)
- **looks-like-but-is-not:** a profile. A template is inert until deployed.
- **looks-like-but-is-not:** a [guide](../dashboard/guide.md). A guide is one of the things a
  template may carry, and layers onto an agent that exists; a template creates the agent.

## If you change this

- **Hits:** `TemplateApplier`, `ProfileTemplateService`, `ProfileTemplatesController`,
  `pages/profile-deploy-dialog.ts`, `pages/profile-editor-panel.ts`,
  `core/store/template-store.ts`; `schema.sql` **and** `SchemaUpgrades` for any column;
  `secrets/SecretsAtRest` if you touch a secret's shape — which also hits MCP config values,
  since they share it; `skills/SkillDeployer` and `skills/GuideDeploy` if you change how a
  library skill or a guide lands, since a template deploys them through the same two seams a
  guide does.
- **Does not hit:** existing profiles. A template edit never reaches a profile already deployed
  from it; there is no back-reference. Nor does deleting a library skill or a guide reach a
  template that names it: the editor marks the reference as gone, and the next deploy refuses it.

## Surfaces

| Surface | Role |
|---|---|
| `/api/profile-templates` | reads / writes |
| SQLite `profile_templates` | stored |
| `MC_SECRET_KEY` | encrypts the secrets column |
| `SkillDeployer`, `GuideDeploy` | how a library skill and a guide reach the new profile |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/templates/`
- Key rotation (`MC_SECRET_KEY_PREVIOUS`): `docs/architecture.md`, environment variables
