package io.hermes.missioncontrol.docker;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Stateless gateway over the Docker Engine API. The daemon is the source of truth — nothing
 * here is cached or persisted.
 *
 * <p>Not a generic Docker adapter, despite the package name, and worth being honest about:
 * a deploy seeds Hermes profiles, the inventory filters the fleet by the configured Hermes
 * image, an upgrade checks a container runs that image and reuses its data volume, and
 * readiness waits on {@code hermes gateway status}. What the whole package models is the
 * Docker side of a fleet of Mission Control-managed Hermes containers. The vocabulary that
 * makes a container one of ours lives in {@link ManagedContainer}.
 *
 * <p>This class is the one entry point the rest of the application talks to; each concern
 * behind it is a collaborator of its own:
 *
 * <ul>
 *   <li>{@link ContainerInventory} — the daemon's version, and which containers are the fleet
 *   <li>{@link ContainerStatsReader} — one resource sample per container
 *   <li>{@link ContainerLogReader} — the stdout/stderr tail and its severity rules
 *   <li>{@link DockerNetworks} — idempotent attachment to a user-defined network
 *   <li>{@link ImageStore} — the configured Hermes image, its local tags, and pulls
 *   <li>{@link HermesDeployer} — creating a container, its volume and its seed profiles
 *   <li>{@link ContainerUpgrader} — retagging a managed container, with rollback
 *   <li>{@link ContainerLifecycle} — start, stop, and removal with the data volume
 * </ul>
 */
@Service
public class DockerGateway {

  private final ContainerInventory inventory;
  private final ContainerStatsReader statsReader;
  private final ContainerStatsStreams statsStreams;
  private final ContainerLogReader logReader;
  private final DockerNetworks networks;
  private final ImageStore images;
  private final HermesDeployer deployer;
  private final ContainerUpgrader upgrader;
  private final ContainerLifecycle lifecycle;

  public DockerGateway(
      ContainerInventory inventory,
      ContainerStatsReader statsReader,
      ContainerStatsStreams statsStreams,
      ContainerLogReader logReader,
      DockerNetworks networks,
      ImageStore images,
      HermesDeployer deployer,
      ContainerUpgrader upgrader,
      ContainerLifecycle lifecycle) {
    this.inventory = inventory;
    this.statsReader = statsReader;
    this.statsStreams = statsStreams;
    this.logReader = logReader;
    this.networks = networks;
    this.images = images;
    this.deployer = deployer;
    this.upgrader = upgrader;
    this.lifecycle = lifecycle;
  }

  // ── daemon probing ───────────────────────────────────────────────────────

  /**
   * Deliberately still takes a bare url: this is the call that decides whether a daemon
   * answers at all, so it runs before a {@link DockerHostRef} for that host can exist.
   * Everything past this point takes the ref.
   */
  public DaemonInfo ping(String url) {
    return inventory.ping(url);
  }

  // ── inventory ────────────────────────────────────────────────────────────

  public List<ContainerDto> listContainers(DockerHostRef host, boolean includeAll) {
    return inventory.listContainers(host, includeAll);
  }

  // ── stats / logs ─────────────────────────────────────────────────────────

  public StatsDto stats(DockerHostRef host, String containerId) {
    return statsReader.stats(host, containerId);
  }

  /** The newest sample for each named container, from {@link ContainerStatsStreams}. */
  public Map<String, StatsDto> stats(DockerHostRef host, List<String> containerIds) {
    return statsStreams.samples(host, containerIds);
  }

  public List<LogLineDto> logs(DockerHostRef host, String containerId, int tail, Long since) {
    return logReader.logs(host, containerId, tail, since);
  }

  // ── networks ─────────────────────────────────────────────────────────────

  public void connectNetwork(DockerHostRef host, String containerId, String networkName) {
    networks.connect(host, containerId, networkName);
  }

  public void connectNetwork(
      DockerHostRef host, String containerId, String networkName, List<String> aliases) {
    networks.connect(host, containerId, networkName, aliases);
  }

  // ── images ──────────────────────────────────────────────────────────────

  public Set<String> localImageTags(DockerHostRef host) {
    return images.localImageTags(host);
  }

  public String hermesImageRepository() {
    return images.hermesImageRepository();
  }

  // ── lifecycle ────────────────────────────────────────────────────────────

  /** A deploy with nothing to do after the gateway is ready. */
  public String deploy(
      DockerHostRef host, String name, String version, List<String> profiles,
      ContainerResources resources, HostAccess access) {
    return deployer.deploy(host, name, version, profiles, resources, access);
  }

  /** {@code afterReady} runs inside the deployer's rollback guard — see {@link HermesDeployer#deploy}. */
  public String deploy(
      DockerHostRef host, String name, String version, List<String> profiles,
      ContainerResources resources, HostAccess access, java.util.function.Consumer<String> afterReady) {
    return deployer.deploy(host, name, version, profiles, resources, access, afterReady);
  }

  public ManagedContainerSpec inspectManaged(DockerHostRef host, String containerId) {
    return upgrader.inspectManaged(host, containerId);
  }

  public UpgradeResult upgrade(DockerHostRef host, String containerId, String version) {
    return upgrade(host, containerId, version, HostAccess.NONE);
  }

  public UpgradeResult upgrade(
      DockerHostRef host, String containerId, String version, HostAccess access) {
    lifecycle.assertNothingInFlight(containerId);   // an upgrade stops the container too
    return upgrader.upgrade(host, containerId, version, access);
  }

  public void start(DockerHostRef host, String containerId) {
    lifecycle.start(host, containerId);
  }

  public void stop(DockerHostRef host, String containerId) {
    lifecycle.stop(host, containerId);
  }

  public void remove(DockerHostRef host, String containerId) {
    lifecycle.remove(host, containerId);
  }
}
