---
type: process
status: verified
verified: claude/hermes-openai-api-key @ 6b6a014 · 2026-09-05
consumes: [container, image, profile, profile-template]
produces: [container, profile]
---

# deploy-agent

Create a Hermes container, its data volume, and its seed profiles — as one transaction that
rolls back.

## Input → Movement → Output

A [host](../objects/docker/docker-host.md), an [image](../objects/docker/image.md) tag and a list
of profile names (optionally from a [template](../objects/agents/profile-template.md)) go in.
A volume is created, one or more one-shot bootstrap containers seed it, then the gateway
container is created and waited on. Out comes a running
[container](../objects/docker/container.md) with named [profiles](../objects/agents/profile.md)
in it — or nothing at all.

## Why this shape

`HermesDeployer` is split out of `DockerGateway` because **a deploy is a multi-resource
transaction**: a volume, one or more one-shot bootstrap containers, then the gateway itself.
Everything after the volume creation runs inside a rollback guard, and keeping that guard
readable is the point of the split (`docker/HermesDeployer.java:34`).

The current Hermes image initializes the default profile on first boot, so readiness is *waited
on* rather than assumed — and if readiness or named-profile creation fails, **the container and
volume are rolled back**.

## Steps

1. Resolve the host to a `DockerHostRef` at the edge — nothing downstream resolves hosts
   (`docker/ContainerUpdateService.java:37` explains the same rule).
2. Create the `mc-hermes-<name>` volume (`docker/ManagedContainer.java:42`).
3. Run bootstrap one-shots, labelled `mc.bootstrap` (`:38`), to seed the volume.
4. Create the gateway container with `gateway run`, the volume at `/opt/data`, restart policy
   `unless-stopped`, and the `mc.*` labels (`docker/ManagedContainer.java:82`) — plus whatever
   host access the request named: published ports, environment, bind mounts
   (`docker/HostAccess.java`), and always `host.docker.internal` and a 1 GB `/dev/shm`.
5. Bounded readiness checks (`docker/DeploymentReadiness.java`), then create the requested named
   profiles.
6. When the request names one, apply a [template](../objects/agents/profile-template.md) to the
   `default` profile the image just made — model settings included, through
   `TemplateApplier.configureAndApply`. The deployer runs it as an `afterReady` step it is handed
   by `fleet/ContainersController.java`, which is where the agents package is reachable from:
   `docker/` must not depend on `agents/templates`, so the step crosses as a `Consumer<String>`.
7. On any failure after step 2, step 6 included: roll back the container **and** the volume
   (`docker/HermesDeployer.java:162`).

## If you change this

- **Hits:** `ManagedContainer` labels — and therefore `ContainerUpgrader`, `ContainerLifecycle`
  and `ContainerInventory`, which all read them; `TemplateApplier` when deploying from a
  template, and `HermesProfiles.configureModel` for the default profile's model settings;
  `pages/containers.ts` (the deploy modal's blueprint select), `pages/agent-create-dialog.ts`,
  `profile-deploy-dialog.ts`.
- **Hits, only when asked:** host access. `HostAccess` validates ports, environment and mounts
  (the Docker socket and anything on `/opt/data` or `/opt/hermes` are refused), `HermesDeployer`
  applies them to the gateway container alone, and a writable mount widens
  `HERMES_WRITE_SAFE_ROOT`. A published port is what makes a profile's
  [webhook listener](../objects/agents/webhook-subscription.md) reachable; nothing is published
  the operator did not name, because the listener's port is decided long after the container is
  created. `ContainerUpgrader` carries all of it onto a replacement, and lays an update's own on
  top — [upgrade-image](upgrade-image.md) is how a port is added to an Agent deployed without one.
- **Does not hit:** the MCP network: that happens when a
  [link](../objects/mcp/agent-mcp-link.md) is made.

## Surfaces

| Surface | Role |
|---|---|
| `POST /api/containers` | entry |
| the Docker daemon | volume, containers, labels |
| `hermes gateway status` | readiness |

## See

- Objects: [container](../objects/docker/container.md), [profile](../objects/agents/profile.md)
- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/docker/HermesDeployer.java`
- `docs/architecture.md`, "Container deploy defaults"
