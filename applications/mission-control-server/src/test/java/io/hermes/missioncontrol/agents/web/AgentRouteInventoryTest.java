package io.hermes.missioncontrol.agents.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The complete set of {@code /api/agents} routes, pinned.
 *
 * <p>These endpoints are split across five controllers by sub-resource, each contributing a
 * fragment of the same URL space. Nothing else notices if a fragment drifts: the frontend
 * calls these paths as string literals, so a moved or dropped route is a 404 in the browser
 * and a green test suite. This is the one place the whole surface is stated.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRouteInventoryTest {

  private static final List<String> EXPECTED = List.of(
      "DELETE /api/agents/{hostId}/{containerId}/{name}",
      "DELETE /api/agents/{hostId}/{containerId}/{name}/mcp/{serverName}",
      "DELETE /api/agents/{hostId}/{containerId}/{name}/mcp/{serverName}/link",
      "DELETE /api/agents/{hostId}/{containerId}/{name}/cron/{jobId}",
      "DELETE /api/agents/{hostId}/{containerId}/{name}/sessions/{sessionId}",
      "DELETE /api/agents/{hostId}/{containerId}/{name}/skills/{skillName}",
      "DELETE /api/agents/{hostId}/{containerId}/{name}/webhooks/{route}",
      "GET /api/agents",
      "GET /api/agents/{hostId}/{containerId}/activity",
      "GET /api/agents/{hostId}/{containerId}/auth-providers",
      "GET /api/agents/{hostId}/{containerId}/{name}/cron",
      "GET /api/agents/{hostId}/{containerId}/{name}/integrations",
      "GET /api/agents/{hostId}/{containerId}/{name}/logs",
      "GET /api/agents/{hostId}/{containerId}/{name}/sessions",
      "GET /api/agents/{hostId}/{containerId}/{name}/sessions/{sessionId}",
      "GET /api/agents/{hostId}/{containerId}/{name}/setup",
      "GET /api/agents/{hostId}/{containerId}/{name}/skills/{skillName}/content",
      "GET /api/agents/{hostId}/{containerId}/{name}/webhooks",
      "GET /api/agents/{hostId}/{containerId}/{name}/webhooks/{route}/secret",
      "PATCH /api/agents/{hostId}/{containerId}/{name}/cron/{jobId}",
      "POST /api/agents",
      "POST /api/agents/{hostId}/{containerId}/{name}/cron",
      "POST /api/agents/{hostId}/{containerId}/{name}/cron/{jobId}/pause",
      "POST /api/agents/{hostId}/{containerId}/{name}/cron/{jobId}/resume",
      "POST /api/agents/{hostId}/{containerId}/{name}/cron/{jobId}/run",
      "POST /api/agents/{hostId}/{containerId}/{name}/env/init",
      "POST /api/agents/{hostId}/{containerId}/{name}/mcp",
      "POST /api/agents/{hostId}/{containerId}/{name}/mcp/catalog",
      "POST /api/agents/{hostId}/{containerId}/{name}/mcp/{serverName}/sync",
      "POST /api/agents/{hostId}/{containerId}/{name}/mcp/{serverName}/test",
      "POST /api/agents/{hostId}/{containerId}/{name}/pause",
      "POST /api/agents/{hostId}/{containerId}/{name}/resume",
      "POST /api/agents/{hostId}/{containerId}/{name}/skills",
      "POST /api/agents/{hostId}/{containerId}/{name}/webhooks",
      "POST /api/agents/{hostId}/{containerId}/{name}/webhooks/outbound",
      "PUT /api/agents/{hostId}/{containerId}/{name}/webhooks/outbound/{index}",
      "DELETE /api/agents/{hostId}/{containerId}/{name}/webhooks/outbound/{index}",
      "POST /api/agents/{hostId}/{containerId}/{name}/webhooks/{route}/test",
      "PUT /api/agents/{hostId}/{containerId}/{name}/config",
      "PUT /api/agents/{hostId}/{containerId}/{name}/env",
      "PUT /api/agents/{hostId}/{containerId}/{name}/mcp/{serverName}",
      "PUT /api/agents/{hostId}/{containerId}/{name}/mcp/{serverName}/enabled",
      "PUT /api/agents/{hostId}/{containerId}/{name}/skills/{skillName}",
      "PUT /api/agents/{hostId}/{containerId}/{name}/skills/{skillName}/content",
      "PUT /api/agents/{hostId}/{containerId}/{name}/soul",
      "PUT /api/agents/{hostId}/{containerId}/{name}/webhooks/platform");

  @Autowired
  private RequestMappingHandlerMapping mappings;

  @Test
  void everyAgentRouteIsMappedExactlyWhereTheFrontendExpectsIt() {
    Set<String> actual = new TreeSet<>();
    mappings.getHandlerMethods().keySet().forEach(info -> {
      info.getPathPatternsCondition().getPatternValues().stream()
          .filter(pattern -> pattern.startsWith("/api/agents"))
          .forEach(pattern -> info.getMethodsCondition().getMethods()
              .forEach(method -> actual.add(method.name() + " " + pattern)));
    });

    assertEquals(new TreeSet<>(EXPECTED), actual);
  }
}
