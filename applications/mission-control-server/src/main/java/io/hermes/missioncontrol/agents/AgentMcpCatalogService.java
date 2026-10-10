package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.mcp.ManagedMcpStack;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import io.hermes.missioncontrol.mcp.McpServerKind;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Materializes catalog definitions into Hermes profile YAML while the catalog
 * link itself remains dashboard-owned SQLite state.
 *
 * <p>Writing the link is this class's; laying it back over a profile that is being read is
 * {@link CatalogLinkOverlay}'s, which every profile read passes through. The two ends used to
 * be here together, which made "a profile leaves the API enriched" a rule each controller had
 * to apply for itself.
 */
@Service
public class AgentMcpCatalogService {

  private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");

  private final McpRegistryService registry;
  private final AgentMcpLinkRepository links;
  private final HostService hosts;
  private final DockerGateway docker;
  private final HermesProfiles profiles;

  public AgentMcpCatalogService(
      McpRegistryService registry,
      AgentMcpLinkRepository links,
      HostService hosts,
      DockerGateway docker,
      HermesProfiles profiles) {
    this.registry = registry;
    this.links = links;
    this.hosts = hosts;
    this.docker = docker;
    this.profiles = profiles;
  }

  /**
   * Connects a catalog server to an Agent, refusing an alias the Agent already has.
   *
   * <p>For the one route that connects one server because someone asked for that server: an
   * alias already there is a conflict, because nothing was done and the caller should know. A
   * caller connecting a <em>set</em> wants {@link #connectIfAbsent} instead.
   */
  public AgentProfileDto connect(
      DockerHostRef host, String containerId, String profile, ConnectCatalogMcpRequest request) {
    return connectIfAbsent(host, containerId, profile, request)
        .orElseThrow(() -> new ResourceConflictException("an MCP server named '"
            + alias(request.alias()) + "' already exists on this Agent"));
  }

  /**
   * As {@link #connect}, with an alias the Agent already has reported rather than thrown:
   * {@link Optional#empty()} means nothing was written because the server is already there.
   *
   * <p>Here because both callers that connect a set of servers — a
   * {@code /api/mcp-groups/{id}/deploy} and a {@code /api/skill-guides/{id}/deploy} — need it,
   * and topping up an Agent that has some of the set already is the ordinary use of both. They
   * used to recognise the case by matching {@code getMessage()} against a copy of the prose
   * {@link #connect} throws, one of them did not, and so the same event was {@code skipped} on
   * one route and {@code failed} on the other.
   */
  public Optional<AgentProfileDto> connectIfAbsent(
      DockerHostRef host, String containerId, String profile, ConnectCatalogMcpRequest request) {
    String alias = alias(request.alias());
    // the one call here that acts on whether the server is up, so the one that pays for a
    // runtime refresh — see McpRegistryService.definition/live
    var source = registry.live(request.serverId());
    AgentProfileDto current = profiles.get(host, containerId, profile);
    if (current.mcp().stream().anyMatch(server -> alias.equals(server.name()))) {
      return Optional.empty();
    }
    if (McpServerKind.MANAGED.is(source.kind()) && !"running".equals(source.runtimeState())) {
      throw new ResourceConflictException("managed MCP server is not running: " + source.name());
    }
    McpServerDefinition definition = materialize(host, containerId, source, alias, true);
    // The link row goes in first, so that the profile write's own read-back already carries it
    // — every profile is read through CatalogLinkOverlay, and one taken before the row exists
    // reports the server it just connected as custom. The two halves are ordered the other way
    // round in AgentLifecycle, where the fragile half failing must leave nothing behind; here
    // the reverse is true. A link written for a profile write that then fails is a stranded
    // row, which the overlay drops on the next read of that profile and a retry simply
    // overwrites — where a profile write landing without its link leaves an entry that reads
    // as custom, with nothing recording that it came from the catalog and nothing to heal it.
    long now = System.currentTimeMillis();
    links.upsert(new AgentMcpLink(
        host.id(), containerId, profile, alias, source.id(), source.revision(), now, now));
    return Optional.of(profiles.addMcpServer(host, containerId, profile, definition));
  }

  public AgentProfileDto sync(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    String alias = alias(serverAlias);
    AgentMcpLink link = links.find(host.id(), containerId, profile, alias)
        .orElseThrow(() -> new NoSuchElementException("MCP entry is not linked to the catalog: " + alias));
    // re-materializes an existing link from the stored definition; nothing here reads the
    // server's runtime state, so this does not refresh it
    var source = registry.definition(link.serverId());
    AgentProfileDto current = profiles.get(host, containerId, profile);
    AgentMcpServerDto existing = current.mcp().stream()
        .filter(server -> alias.equals(server.name()))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("unknown MCP server on Agent: " + alias));
    McpServerDefinition definition = materialize(
        host, containerId, source, alias, existing.enabled());
    // Here the config write goes first, unlike connect: the row records which revision was
    // applied, so writing it before the write it claims would report an agent as in sync with
    // a definition it never received. The cost is the extra read below — the read-back inside
    // updateMcpServer predates the new revision, so it cannot be the answer.
    profiles.updateMcpServer(host, containerId, profile, alias, definition);
    links.upsert(new AgentMcpLink(
        host.id(), containerId, profile, alias, source.id(), source.revision(),
        link.createdAt(), System.currentTimeMillis()));
    return profiles.get(host, containerId, profile);
  }

  public AgentProfileDto unlink(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    String alias = alias(serverAlias);
    if (links.find(host.id(), containerId, profile, alias).isEmpty()) {
      throw new NoSuchElementException("MCP entry is not linked to the catalog: " + alias);
    }
    links.delete(host.id(), containerId, profile, alias);
    return profiles.get(host, containerId, profile);
  }

  public void assertCustom(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    if (links.find(host.id(), containerId, profile, alias(serverAlias)).isPresent()) {
      throw new ResourceConflictException(
          "catalog-linked MCP entries must be customized before direct editing");
    }
  }

  public void forgetLink(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    links.delete(host.id(), containerId, profile, alias(serverAlias));
  }

  public void deleteAgentLinks(DockerHostRef host, String containerId, String profile) {
    // one statement rather than one per alias: a failure partway through the old loop
    // left the profile holding some of its links and not others
    links.deleteByAgent(host.id(), containerId, profile);
  }

  /**
   * Global catalog deletion is safe and retryable: every reachable Agent copy
   * is disabled before its link is removed. A failure aborts container/catalog
   * deletion; entries already processed remain safely disabled.
   */
  public void disableAndUnlinkForDeletion(String serverId) {
    List<AgentMcpLink> linked = List.copyOf(links.findByServer(serverId));
    for (AgentMcpLink link : linked) {
      AgentProfileDto updated;
      try {
        updated = profiles.setMcpServerEnabled(
            hosts.requireConnected(link.hostId()), link.containerId(), link.profile(),
            link.alias(), false);
      } catch (NoSuchElementException staleLink) {
        // The profile/entry may have been removed outside Mission Control. No
        // live connection remains to disable, so discard only the stale link.
        links.delete(link.hostId(), link.containerId(), link.profile(), link.alias());
        continue;
      }
      if (updated.mcp().stream().noneMatch(
          server -> link.alias().equals(server.name()) && !server.enabled())) {
        throw new ResourceConflictException("could not disable MCP entry " + link.alias());
      }
      links.delete(link.hostId(), link.containerId(), link.profile(), link.alias());
    }
  }

  private McpServerDefinition materialize(
      DockerHostRef agentHost,
      String containerId,
      McpServerDto source,
      String alias,
      boolean enabled) {
    if (McpServerKind.STDIO.is(source.kind())) {
      String command = source.stdioCommand();
      if (command == null || command.isBlank()) {
        throw new IllegalArgumentException("catalog stdio server has no command: " + source.name());
      }
      // the catalog stores args as a list already; no shell tokenizing round trip needed
      return new McpServerDefinition(
          alias, McpServerDefinition.Transport.STDIO, null, command,
          source.args() == null ? List.of() : source.args(), enabled, null,
          registry.materializedEnvironment(source.id()));
    }
    // Joining the network is part of making the endpoint reachable, not part of working out
    // what it is — so it happens at this level, where a reader sees the Agent's networking
    // being changed. It used to sit inside the endpoint resolver below, which read as a pure
    // lookup and quietly reconfigured a container.
    joinMcpNetwork(agentHost, containerId, source);
    return new McpServerDefinition(
        alias, McpServerDefinition.Transport.of(source.transport()),
        connectionEndpoint(agentHost, source), null, List.of(),
        enabled, registry.materializedHeaders(source.id()), null);
  }

  /**
   * Attaches the Agent's container to the shared MCP network, when that is how it will reach
   * this server: a managed entry on the Agent's own host is addressed by Compose service name,
   * which resolves only from the network. Idempotent, so connecting a second server is a no-op.
   *
   * <p>Nothing to do for an external entry, a stdio one, or a managed server on another host —
   * those are reached by a URL that does not depend on this network.
   */
  private void joinMcpNetwork(
      DockerHostRef agentHost, String containerId, McpServerDto source) {
    if (!McpServerKind.MANAGED.is(source.kind()) || !agentHost.id().equals(source.hostId())) return;
    docker.connectNetwork(agentHost, containerId, ManagedMcpStack.NETWORK);
  }

  /** Where an Agent on this host reaches a network catalog entry: a managed server on its own
   *  host by Compose service name over the shared MCP network, one on another host by the
   *  address its operator published. A pure lookup — see {@link #joinMcpNetwork}. */
  private String connectionEndpoint(DockerHostRef agentHost, McpServerDto source) {
    if (!McpServerKind.MANAGED.is(source.kind())) return source.url();
    if (agentHost.id().equals(source.hostId())) {
      return registry.sameHostConnectionUrl(source.id());
    }
    String crossHost = source.crossHostUrl();
    if (crossHost == null || crossHost.isBlank()) {
      throw new IllegalArgumentException(
          "managed MCP server needs a cross-host URL for this Agent: " + source.name());
    }
    return crossHost;
  }

  private static String alias(String value) {
    String result = value == null ? "" : value.trim();
    if (!ALIAS.matcher(result).matches()) throw new IllegalArgumentException("invalid MCP alias");
    return result;
  }

  /**
   * A catalog entry's display name turned into an alias, for the callers that have no operator
   * to ask: a group deploy and a guide deploy both connect every server they name.
   *
   * <p>A display name is prose and an alias is a config key, so the two alphabets differ — and
   * passing the name straight through failed every entry whose name is not already an alias,
   * which included two of the four the catalog seeds (<em>Sequential Thinking</em>,
   * <em>Postgres MCP</em>). Deliberately not folded into {@link #alias}: that one is also the
   * lookup key on sync, unlink, custom and forget, where coercing what the caller asked for
   * into something valid would answer about a different link than the one named.
   *
   * <p>A name of nothing but punctuation still throws — there is no alias in it to find.
   */
  public static String aliasFor(String displayName) {
    String slug = (displayName == null ? "" : displayName.trim())
        .replaceAll("[^A-Za-z0-9_.-]+", "-")
        .replaceAll("^[^A-Za-z0-9]+", "")
        .replaceAll("[^A-Za-z0-9]+$", "");
    return alias(slug);
  }
}
