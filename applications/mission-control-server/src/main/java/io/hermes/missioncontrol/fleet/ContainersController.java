package io.hermes.missioncontrol.fleet;

import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.docker.ContainerUpdateService;
import io.hermes.missioncontrol.docker.DeployRequest;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.docker.StatsDto;
import io.hermes.missioncontrol.docker.UpdateContainerRequest;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/containers")
public class ContainersController {

  private static final Logger log = LoggerFactory.getLogger(ContainersController.class);

  private final DockerGateway docker;
  private final HostService hosts;
  private final ContainerUpdateService updates;
  private final ProfileTemplateService templates;

  public ContainersController(
      DockerGateway docker, HostService hosts, ContainerUpdateService updates,
      ProfileTemplateService templates) {
    this.docker = docker;
    this.hosts = hosts;
    this.updates = updates;
    this.templates = templates;
  }

  /**
   * Inventory across hosts. Filtered to Hermes-related containers unless
   * all=true; hosts that fail to answer are skipped (their status is already
   * visible on /api/hosts).
   *
   * <p>The only endpoint here that does not resolve through
   * {@code HostService.requireConnected}: it is already iterating listed rows and
   * filtering on the status they carry, so it builds each ref from the row it holds
   * rather than asking for the same verdict a second time.
   */
  @GetMapping
  public List<ContainerDto> list(
      @RequestParam(required = false) String hostId,
      @RequestParam(defaultValue = "false") boolean all) {
    List<ContainerDto> result = new ArrayList<>();
    for (var host : hosts.list()) {
      if (hostId != null && !hostId.equals(host.id())) continue;
      if (!"connected".equals(host.status())) continue;
      try {
        result.addAll(docker.listContainers(new DockerHostRef(host.id(), host.url()), all));
      } catch (Exception e) {
        log.warn("listing containers on {} failed: {}", host.id(), e.getMessage());
      }
    }
    return result;
  }

  // Every endpoint below resolves through requireConnected, so a daemon that is down is
  // reported as a 503 before the container is touched. They used to take the host row's url
  // unprobed, which left the same outage surfacing as a 502 'docker daemon error' — the
  // failure ImagesController's comment already said every such endpoint should avoid.
  /**
   * The newest sample for every named container on one host, in one request.
   *
   * <p>The fleet view asks about every running container three seconds apart. Asking per
   * container meant a request each, every one of them blocked for the second or two the
   * daemon spends taking the two samples a CPU delta needs — so the cost grew with both the
   * container count and the number of open dashboards, and past six containers the fan-out no
   * longer fitted inside its own period. This answers all of them from the live streams
   * {@link io.hermes.missioncontrol.docker.ContainerStatsStreams} holds, which makes it a
   * memory read.
   *
   * <p>The caller names the containers rather than having this list the daemon: it has just
   * listed them, and repeating that here would put the most expensive call on the fleet view's
   * fastest timer. A container with no sample yet is absent from the map rather than zeroed.
   */
  @GetMapping("/{hostId}/stats")
  public Map<String, StatsDto> stats(
      @PathVariable String hostId,
      @RequestParam List<String> ids) {
    return docker.stats(hosts.requireConnected(hostId), ids);
  }

  /** Retained for a single container; the fleet view uses the batched form above. */
  @GetMapping("/{hostId}/{id}/stats")
  public StatsDto stats(@PathVariable String hostId, @PathVariable String id) {
    return docker.stats(hosts.requireConnected(hostId), id);
  }

  /**
   * The tail, or — with {@code since} — only what has arrived after it.
   *
   * <p>{@code since} is an epoch-millisecond cursor the caller took from the newest line it
   * already holds. Docker resolves it to whole seconds, so the reply can repeat that line;
   * the caller drops what it recognises.
   */
  @GetMapping("/{hostId}/{id}/logs")
  public List<LogLineDto> logs(
      @PathVariable String hostId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int tail,
      @RequestParam(required = false) Long since) {
    return docker.logs(hosts.requireConnected(hostId), id, tail, since);
  }

  /**
   * The blueprint for the default agent, when one is named, goes on after the gateway is ready
   * and inside the deployer's rollback guard. It is resolved here first, so an id that names
   * nothing answers 404 before a volume exists rather than after a container has been pulled,
   * created and rolled back for it.
   */
  @PostMapping
  public Map<String, String> deploy(@Valid @RequestBody DeployRequest request) {
    DockerHostRef host = hosts.requireConnected(request.hostId());
    Consumer<String> afterReady = containerId -> { };
    if (request.hasDefaultTemplate()) {
      String templateId = request.defaultTemplateId().trim();
      templates.get(templateId);
      afterReady = containerId -> templates.applyToDefault(templateId, host, containerId);
    }
    String containerId = docker.deploy(
        host, request.name(), request.version(), request.profiles(), request.resources(),
        request.hostAccess(), afterReady);
    return Map.of("id", containerId);
  }

  @PostMapping("/{hostId}/{id}/start")
  public void start(@PathVariable String hostId, @PathVariable String id) {
    docker.start(hosts.requireConnected(hostId), id);
  }

  @PostMapping("/{hostId}/{id}/stop")
  public void stop(@PathVariable String hostId, @PathVariable String id) {
    docker.stop(hosts.requireConnected(hostId), id);
  }

  /**
   * Recreates the container on another image tag — or on the same one, to open host access it
   * was deployed without — reusing its data volume. The container id changes, so the
   * replacement's id is returned.
   */
  @PostMapping("/{hostId}/{id}/update")
  public Map<String, String> update(
      @PathVariable String hostId,
      @PathVariable String id,
      @Valid @RequestBody UpdateContainerRequest request) {
    return Map.of("id", updates.update(
        hosts.requireConnected(hostId), id, request.version(), request.hostAccess()));
  }

  @DeleteMapping("/{hostId}/{id}")
  public void remove(@PathVariable String hostId, @PathVariable String id) {
    docker.remove(hosts.requireConnected(hostId), id);
  }
}
