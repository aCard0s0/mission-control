---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ ae0ebd6 · 2026-08-29
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/
---

# Skill library

Skills the dashboard holds, deployable onto any agent. `Skill`, `skills` table, served at
**`/api/skills`**.

Not the same noun as the [Skill](../_index.md) an agent has — see *looks-like-but-is-not*.

## Why this shape

**Hermes has no `skills create`.** `hermes skills install <id>` resolves an id against the
Skills Hub and does nothing else, so a skill authored in the dashboard, or written by an
agent's own curator, has no id anything can install it by. That single fact produces the
whole design: the library splits by origin rather than picking one mechanism.

| `kind` | Row holds | Deploy runs |
|---|---|---|
| `hub` | id, description, repo link. No content | `HermesSkills.install` → `hermes skills install <name> --force` |
| `local` | the file set | `HermesSkills.writeSkillFiles` → the files, written out |

Both halves are load-bearing. Storing a copy of a hub skill would be a second source of
truth that goes stale the moment the Hub moves. Refusing to store local content would make
dashboard-authored and curator-authored skills undeployable. The CHECK constraint at
`schema.sql:207` and the branch at `skills/SkillController.java:223` are the two places that stay
in step.

A local deploy is an **overlay, not a sync**: it writes what the row holds and removes
nothing, so a file renamed in the library leaves its old copy on the agent.

## Shape

- `skills` — `schema.sql:205`; `kind` CHECK at `:207`; `name` is `COLLATE NOCASE UNIQUE`
  (`:213`) so `pdf` and `PDF` cannot both address `skills/pdf`; `files` is a JSON array in
  TEXT, NULL for a hub row (`:220`); index on `category` (`:225`)
- `Skill` / `SkillFile` — `skills/Skill.java`, `skills/SkillFile.java`
- `SkillRepository` — plain JdbcTemplate, the `prompts` shape
- `SkillController` — `/api/skills`, six routes, no service layer

## The path guard

The relative path of each file is the only string in the application an operator types that
is then concatenated into a container path. `ProfilePaths.skillFile` (`agents/ProfilePaths.java:63`)
owns the rule: **every `/`-separated segment must pass `isValidName` on its own**, which
turns the existing profile-name whitelist into a per-segment one. `split("/", -1)` keeps
trailing empties, so `a/` and `a//b` are rejected rather than silently collapsing.

Depth is capped at three (`agents/ProfilePaths.java:49`) because `HermesSkills.listSkillFiles` runs
`find -maxdepth 3` — a file written deeper is invisible to the call that lists it back.

Every path is resolved **before the first write** (`agents/HermesSkills.java:157`). A path checked
as it is written would leave a half-deployed skill behind the rejection, and hermes would
still try to load it.

## The upstream check

`skills/UpstreamCheck.java` answers whether a row's repository has moved on. On demand and
cached, never on a timer: the answer changes on the order of days and unauthenticated GitHub
allows sixty requests an hour per address.

**The stored URL is never fetched.** It is parsed to an owner and repository — with `URI`,
not a regex, because the cases that matter are `github.com.evil.test`, a host in the path and
userinfo before the real host — and the API URL is built from those two validated words. A
URL an operator typed that reached `HttpClient` would be a request this server makes wherever
they said.

`update` means the two version strings differ, **not** that upstream is ahead. `version` is
free text, so ordering it would be a guess presented as a fact; both values are reported and
the person decides.

The cache key is operator-supplied, so unlike `docker/RegistryTagService` — whose own comment
says its map only holds because its key is a single configured value — expired entries are
swept on write, the shape `agents/HermesProfileMcp` uses.

## The repository link, and two rules that are not one rule

Saving a row admits `http://` and `https://` and nothing else — the rule is
`common/Text.java:38`, applied at `skills/SkillController.java:239` and shared with an
[MCP catalog entry](../mcp/mcp-server-entry.md), which stores the same field and renders it
the same `href`. The two disagreed until that helper existed: only the catalog checked it.

The save rule stops there on purpose. `skills/UpstreamCheck.java:240` accepts far less —
https, `github.com`, exactly two path segments — but that is the rule for *reaching the
network*, and folding it into the save would refuse a skill kept anywhere but GitHub. A link
that saves and reports `unsupported` is a valid state.

**A row stored before the guard fails its next save.** The editor round-trips `repoUrl`, so
editing an unrelated field on such a row answers 400 naming it, and clearing the field is the
way out. The alternative — checking only a *changed* value — would let a stored `javascript:`
outlive every future save, and a guard on an `href` that grandfathers its own failures is not
one. The value was already inert: Angular refuses to bind it, and the check above reads
nothing from it.

## Checked against a real agent

Verified end to end against `nousresearch/hermes-agent:latest` (v2026.8.19) in a throwaway
container, not only against the exec-seam fake: a multi-file local deploy lands every file
including a three-segment path and hermes lists the skill with its frontmatter parsed;
`default` writes at `/opt/data/skills/` and mints no `profiles/default`; an import round-trips
a curator-authored skill to another profile with a PNG reported skipped; and a row carrying
`../../../../../../etc/cron.d/pwned`, inserted straight into SQLite to bypass the controller,
is refused with **nothing** written — not even the valid `SKILL.md` earlier in the same set.

## Connected to

- **owns:** nothing on an agent. A deployed copy is not tracked.
- **owned-by:** the dashboard
- **joins:** a profile, only at the moment of a deploy or an import; and any number of
  [skill groups](skill-group.md), which name rows here to file them. A group holds the
  ids, so nothing on a row says which group claims it, and deleting a group leaves the
  row alone.
- **looks-like-but-is-not:** the **Skill** an agent has (`agents/HermesSkills.java`,
  `SkillDto`), read through from that container's disk and listed on the agent's own Skills
  tab. Different lifetime, different owner: that one exists because a container has it, this
  one exists because an operator kept it. The FE spells the collision out too —
  `models.ts` `SkillRef` vs `Skill`, and `api.agents` vs `api.skills`.
- **looks-like-but-is-not:** a **Profile template**, which creates a whole new agent. Its
  `skills` column holds Skills Hub ids to install by name; its `library_skill_ids` column names
  rows *here*, by id, the way a [guide](guide.md) does — resolved when the blueprint is deployed,
  through the same `SkillDeployer`. A blueprint creates an agent; this layers one skill onto an
  agent that exists.
- **looks-like-but-is-not:** a [skill group](skill-group.md). A group is filing and has
  no deploy at all; `category` on a row here is a filter, not a group. Both are on this
  page and neither is the other.

## If you change this

- **Hits:** `SkillRepository`, `SkillController`, `common/Text.java:38` (the `repoUrl` rule, shared
  with the MCP catalog — a change there hits both stores), `HermesProfiles.installSkillFiles`
  (`agents/HermesProfiles.java:354`), `HermesSkills.writeSkillFiles`/`readSkillFiles`,
  `ProfilePaths.skillFile`, `pages/skills.ts`, `pages/skill-deploy-dialog.ts`,
  `core/store/skill-store.ts`, and the `save to library` button on
  `pages/agent-skills-panel.html`.
- **Does not hit:** a hub row never writes a file; a local row never shells
  `hermes skills install`. **Deleting a library row does not touch any deployed copy** —
  this is a stamp, not a live link, deliberately unlike an [MCP agent
  link](../mcp/agent-mcp-link.md), which exists because an MCP entry keeps drifting against
  a catalog revision. Files on a disk do not drift on their own, so there is no reverse
  link and no cascade. Removing a deployed skill is the agent's own Skills tab.

## Surfaces

| Surface | Role |
|---|---|
| `/api/skills` | reads / writes the library |
| `POST /api/skills/{id}/deploy` | the one write that reaches a container |
| `POST /api/skills/import` | the one read that reaches a container |
| `GET /api/skills/{id}/upstream` | the one call that reaches the internet |
| SQLite `skills` | stored |
| `HermesContainerFiles` | the exec seam every deploy goes through |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/skills/`
- The container write: `agents/HermesSkills.java:157`
- Routes and the *why*: [docs/api.md](../../../api.md)
- The rule this widened: [mission_control_guidelines.md](../../../mission_control_guidelines.md)
