package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.agents.api.DeployedPart;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Connects every server in a group to one agent, and reports what each one did.
 *
 * <p>Not in {@link McpGroupController} — where it was — for the reason {@code AgentLifecycle}
 * gives for the same move: several independent writes to an agent someone else owns, whose
 * failure handling is the interesting part, are not an implementation detail of an HTTP handler.
 * There they were reachable from nowhere else and assertable only through MockMvc.
 *
 * <p>No ordering is load-bearing here, unlike a guide's deploy: the servers are independent of
 * each other, and nothing is written afterwards that names what landed. What the two deploys do
 * share is the rule that an alias the agent already has is {@code skipped} rather than
 * {@code failed}, which neither decides for itself —
 * {@link AgentMcpCatalogService#connectIfAbsent} does.
 */
@Service
public class McpGroupDeploy {

  private static final Logger log = LoggerFactory.getLogger(McpGroupDeploy.class);

  private final McpRegistryService registry;
  private final AgentMcpCatalogService mcpCatalog;

  public McpGroupDeploy(McpRegistryService registry, AgentMcpCatalogService mcpCatalog) {
    this.registry = registry;
    this.mcpCatalog = mcpCatalog;
  }

  /** The refreshed profile, plus one row per server so a half-connected group is legible. */
  public record Deployed(AgentProfileDto profile, List<DeployedPart> parts) {
  }

  public Deployed onto(McpGroup group, DockerHostRef host, String containerId, String profile) {
    List<DeployedPart> parts = new ArrayList<>();
    AgentProfileDto latest = null;

    for (String serverId : group.serverIds()) {
      String name = serverId;
      try {
        name = registry.definition(serverId).name();
        AgentProfileDto connected = mcpCatalog.connectIfAbsent(host, containerId, profile,
            new ConnectCatalogMcpRequest(serverId, AgentMcpCatalogService.aliasFor(name)))
            .orElse(null);
        if (connected == null) {
          parts.add(new DeployedPart("mcp", name, DeployedPart.SKIPPED, "already connected"));
        } else {
          latest = connected;
          parts.add(DeployedPart.ok("mcp", name));
        }
      } catch (NoSuchElementException gone) {
        parts.add(new DeployedPart("mcp", name, DeployedPart.SKIPPED, "no longer in the catalog"));
      } catch (RuntimeException failure) {
        log.warn("mcp group deploy: server '{}' failed: {}", name, failure.getMessage());
        parts.add(new DeployedPart("mcp", name, DeployedPart.FAILED, failure.getMessage()));
      }
    }

    return new Deployed(latest, List.copyOf(parts));
  }
}
