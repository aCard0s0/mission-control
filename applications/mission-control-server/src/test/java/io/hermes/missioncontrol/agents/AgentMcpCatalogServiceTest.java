package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.GatewayDto;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentMcpCatalogServiceTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  private final McpRegistryService registry = mock(McpRegistryService.class);
  private final AgentMcpLinkRepository links = mock(AgentMcpLinkRepository.class);
  private final HostService hosts = mock(HostService.class);
  private final DockerGateway docker = mock(DockerGateway.class);
  private final HermesProfiles profiles = mock(HermesProfiles.class);
  private final AgentMcpCatalogService service =
      new AgentMcpCatalogService(registry, links, hosts, docker, profiles);

  @Test
  void sameHostConnectAttachesNetworkAndMaterializesSecretsServerSide() {
    var catalog = mock(McpServerDto.class);
    when(catalog.id()).thenReturn("mcp-1");
    when(catalog.name()).thenReturn("Tools");
    when(catalog.kind()).thenReturn("managed");
    when(catalog.hostId()).thenReturn("dh-local");
    when(catalog.runtimeState()).thenReturn("running");
    when(catalog.transport()).thenReturn("http");
    when(catalog.revision()).thenReturn(7L);
    // connect reads the live view, because it refuses a server that is not running
    when(registry.live("mcp-1")).thenReturn(catalog);
    when(registry.sameHostConnectionUrl("mcp-1")).thenReturn("http://mcp-tools:1100/mcp");
    when(registry.materializedHeaders("mcp-1"))
        .thenReturn(Map.of("Authorization", "Bearer secret"));
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(
        org.mockito.ArgumentMatchers.eq(HOST),
        org.mockito.ArgumentMatchers.eq("container"),
        org.mockito.ArgumentMatchers.eq("default"),
        org.mockito.ArgumentMatchers.any(McpServerDefinition.class)))
        .thenReturn(profile(List.of(new AgentMcpServerDto(
            "tools", "tools", "http", true, "unknown", 0, null, null, null,
            "http://mcp-tools:1100/mcp", null, null))));

    AgentProfileDto result = service.connect(
        HOST, "container", "default",
        new ConnectCatalogMcpRequest("mcp-1", "tools"));

    // the same constant the renderer declares the network under: an Agent joining a name
    // the stack does not create reaches no managed server, and nothing else would notice
    verify(docker).connectNetwork(HOST, "container", ManagedMcpStack.NETWORK);
    ArgumentCaptor<McpServerDefinition> definition =
        ArgumentCaptor.forClass(McpServerDefinition.class);
    verify(profiles).addMcpServer(
        org.mockito.ArgumentMatchers.eq(HOST),
        org.mockito.ArgumentMatchers.eq("container"),
        org.mockito.ArgumentMatchers.eq("default"), definition.capture());
    assertEquals("http://mcp-tools:1100/mcp", definition.getValue().url());
    assertEquals("Bearer secret", definition.getValue().headers().get("Authorization"));
    verify(links).upsert(org.mockito.ArgumentMatchers.argThat(
        value -> "mcp-1".equals(value.serverId()) && value.syncedRevision() == 7));
    // and the row goes in before the profile write, so that write's own read-back — every
    // profile read passes through CatalogLinkOverlay — already reports the entry as catalog-
    // owned. Reversed, the response called the server it had just connected custom.
    InOrder order = inOrder(links, profiles);
    order.verify(links).upsert(org.mockito.ArgumentMatchers.any());
    order.verify(profiles).addMcpServer(any(), anyString(), anyString(), any());
    assertEquals(profile(List.of(custom("tools"))), result);
  }

  @Test
  void crossHostManagedConnectRequiresExplicitUrl() {
    var catalog = mock(McpServerDto.class);
    when(catalog.id()).thenReturn("mcp-1");
    when(catalog.name()).thenReturn("Tools");
    when(catalog.kind()).thenReturn("managed");
    when(catalog.hostId()).thenReturn("dh-remote");
    when(catalog.runtimeState()).thenReturn("running");
    when(catalog.transport()).thenReturn("http");
    when(registry.live("mcp-1")).thenReturn(catalog);
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
        service.connect(HOST, "container", "default",
            new ConnectCatalogMcpRequest("mcp-1", "tools")));

    assertTrue(error.getMessage().contains("cross-host URL"));
  }

  private static AgentProfileDto profile(List<AgentMcpServerDto> mcp) {
    return new AgentProfileDto(
        "container:default", "container", "default", "", "idle", "nous", "model", "",
        "/opt/data", "", "", "mcp_servers: {}\n", List.of(), mcp, List.of(), GatewayDto.unknown(), 0);
  }

  // ── aliases ─────────────────────────────────────────────────────────────
  //
  // The alias becomes a key in the Agent's config.yaml and the identity of the link row, so it
  // is validated on the way in on every path that takes one.

  @Test
  void aDisplayNameBecomesAnAliasForTheCallersThatHaveNoOperatorToAsk() {
    // the two the catalog seeds with a space in the name, which a deploy used to fail on
    assertEquals("Sequential-Thinking", AgentMcpCatalogService.aliasFor("Sequential Thinking"));
    assertEquals("Postgres-MCP", AgentMcpCatalogService.aliasFor("Postgres MCP"));
    // already an alias: unchanged
    assertEquals("Context7", AgentMcpCatalogService.aliasFor("Context7"));
    assertEquals("my.server_1", AgentMcpCatalogService.aliasFor("my.server_1"));
    // the alias has to start alphanumeric and reads better not trailing a separator
    assertEquals("etc", AgentMcpCatalogService.aliasFor("  ../etc  "));
    assertEquals("Redis", AgentMcpCatalogService.aliasFor("-Redis-"));
    assertEquals("a-b", AgentMcpCatalogService.aliasFor("a  ()  b"));
    // a 100-character name is the longest the catalog takes, and the longest alias there is
    assertEquals("a".repeat(100), AgentMcpCatalogService.aliasFor("a".repeat(100)));
    // nothing in it to find
    for (String empty : List.of("", "   ", "()")) {
      assertEquals("invalid MCP alias", assertThrows(IllegalArgumentException.class,
          () -> AgentMcpCatalogService.aliasFor(empty)).getMessage());
    }
  }

  @Test
  void anAliasThatIsNotASafeIdentifierIsRefusedOnEveryPathThatTakesOne() {
    for (String bad : List.of("", "   ", "../etc", "-leading", "a".repeat(101), "with space")) {
      assertEquals("invalid MCP alias", assertThrows(IllegalArgumentException.class,
          () -> service.connect(HOST, "container", "default",
              new ConnectCatalogMcpRequest("mcp-1", bad))).getMessage());
      assertThrows(IllegalArgumentException.class,
          () -> service.sync(HOST, "container", "default", bad));
      assertThrows(IllegalArgumentException.class,
          () -> service.unlink(HOST, "container", "default", bad));
      assertThrows(IllegalArgumentException.class,
          () -> service.assertCustom(HOST, "container", "default", bad));
      assertThrows(IllegalArgumentException.class,
          () -> service.forgetLink(HOST, "container", "default", bad));
    }
    verifyNoInteractions(profiles);
    verifyNoInteractions(links);
  }

  @Test
  void anAliasIsTrimmedBeforeItIsUsedAsAKey() {
    // ' tools ' and 'tools' must not become two entries, or a sync would edit the wrong one
    service.forgetLink(HOST, "container", "default", "  tools  ");

    verify(links).delete("dh-local", "container", "default", "tools");
  }

  // ── connect ─────────────────────────────────────────────────────────────

  @Test
  void connectingRefusesAnAliasTheAgentAlreadyUses() {
    // the alias is a config.yaml key: connecting over it would silently replace a custom entry
    registryHas(managedCatalog("dh-local", "running"));
    hostIsUp();
    when(profiles.get(HOST, "container", "default"))
        .thenReturn(profile(List.of(custom("tools"))));

    ResourceConflictException failure = assertThrows(ResourceConflictException.class, () ->
        service.connect(HOST, "container", "default", new ConnectCatalogMcpRequest("mcp-1", "tools")));

    assertEquals("an MCP server named 'tools' already exists on this Agent", failure.getMessage());
    verify(profiles, never()).addMcpServer(any(), anyString(), anyString(), any());
    verify(links, never()).upsert(any());
  }

  @Test
  void connectingRefusesAManagedCatalogServerThatIsNotRunning() {
    // its Compose service name would resolve to nothing, so the Agent would hold a dead entry
    registryHas(managedCatalog("dh-local", "exited"));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));

    assertEquals("managed MCP server is not running: Tools",
        assertThrows(ResourceConflictException.class, () ->
            service.connect(HOST, "container", "default",
                new ConnectCatalogMcpRequest("mcp-1", "tools"))).getMessage());
    verify(docker, never()).connectNetwork(any(), anyString(), anyString());
  }

  @Test
  void connectingAnExternalCatalogServerCopiesItsUrlAndHeadersAndTouchesNoNetwork() {
    McpServerDto external = catalog("external", "http");
    when(external.url()).thenReturn("https://tools.example.test/mcp");
    registryHas(external);
    when(registry.materializedHeaders("mcp-1")).thenReturn(Map.of("Authorization", "Bearer secret"));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(any(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("tools"))));

    service.connect(HOST, "container", "default", new ConnectCatalogMcpRequest("mcp-1", "tools"));

    McpServerDefinition written = capturedAdd();
    assertEquals("https://tools.example.test/mcp", written.url());
    assertEquals("Bearer secret", written.headers().get("Authorization"));
    assertNull(written.environment(), "environment belongs to stdio servers only");
    verify(docker, never()).connectNetwork(any(), anyString(), anyString());
  }

  @Test
  void connectingAStdioCatalogServerCopiesItsCommandArgsAndMaterializedEnvironment() {
    registryHas(stdioCatalog("npx", List.of("-y", "@example/files")));
    when(registry.materializedEnvironment("mcp-1")).thenReturn(Map.of("ROOT", "/data"));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(any(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));

    service.connect(HOST, "container", "default", new ConnectCatalogMcpRequest("mcp-1", "files"));

    McpServerDefinition written = capturedAdd();
    assertEquals("npx", written.command());
    assertEquals(List.of("-y", "@example/files"), written.args());
    assertEquals("/data", written.environment().get("ROOT"));
    assertNull(written.url());
  }

  @Test
  void aStdioCatalogServerWithNoCommandCannotBeConnected() {
    registryHas(stdioCatalog(null, List.of()));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));

    assertEquals("catalog stdio server has no command: Tools",
        assertThrows(IllegalArgumentException.class, () ->
            service.connect(HOST, "container", "default",
                new ConnectCatalogMcpRequest("mcp-1", "files"))).getMessage());
  }

  @Test
  void stdioArgumentsSurviveAsTheListTheCatalogHolds() {
    // config.yaml carries args as a YAML list, and the catalog already holds one, so the
    // definition passes it straight through. It used to be quoted into a single string and
    // tokenized back, a round trip that silently dropped an empty argument.
    registryHas(stdioCatalog("npx", List.of(
        "-y", "my project", "it's", "say \"hi\"", "", "--flag=a b")));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(any(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));

    service.connect(HOST, "container", "default", new ConnectCatalogMcpRequest("mcp-1", "files"));

    assertEquals(List.of("-y", "my project", "it's", "say \"hi\"", "", "--flag=a b"),
        capturedAdd().args());
  }

  @Test
  void aStdioServerWithNoArgumentsCarriesNoArguments() {
    registryHas(stdioCatalog("npx", List.of()));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(any(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));

    service.connect(HOST, "container", "default", new ConnectCatalogMcpRequest("mcp-1", "files"));

    assertEquals(List.of(), capturedAdd().args());
  }

  // ── sync ────────────────────────────────────────────────────────────────

  @Test
  void syncingAnEntryThatIsNotLinkedIsANotFound() {
    assertEquals("MCP entry is not linked to the catalog: tools",
        assertThrows(NoSuchElementException.class,
            () -> service.sync(HOST, "container", "default", "tools")).getMessage());
    verify(profiles, never()).updateMcpServer(any(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void syncingAnEntryTheAgentNoLongerHasIsANotFound() {
    // the operator may have deleted it from config.yaml by hand; the link is stale
    linkExists("tools", 2);
    registryHas(managedCatalog("dh-local", "running"));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));

    assertEquals("unknown MCP server on Agent: tools",
        assertThrows(NoSuchElementException.class,
            () -> service.sync(HOST, "container", "default", "tools")).getMessage());
  }

  @Test
  void syncingKeepsTheEntryDisabledIfTheOperatorHadDisabledIt() {
    // a sync updates the definition, not the operator's decision to disconnect it
    linkExists("tools", 2);
    registryHas(managedCatalog("dh-local", "running"));
    when(registry.sameHostConnectionUrl("mcp-1")).thenReturn("http://mcp-tools:1100/mcp");
    hostIsUp();
    when(profiles.get(HOST, "container", "default"))
        .thenReturn(profile(List.of(disabled("tools"))));
    when(profiles.updateMcpServer(any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(disabled("tools"))));

    service.sync(HOST, "container", "default", "tools");

    ArgumentCaptor<McpServerDefinition> written =
        ArgumentCaptor.forClass(McpServerDefinition.class);
    verify(profiles).updateMcpServer(eq(HOST), eq("container"), eq("default"),
        eq("tools"), written.capture());
    assertEquals(Boolean.FALSE, written.getValue().enabled());
  }

  @Test
  void syncingRecordsTheCatalogRevisionItSyncedToAndKeepsTheOriginalCreationTime() {
    linkExists("tools", 2);
    registryHas(managedCatalog("dh-local", "running"));
    when(registry.sameHostConnectionUrl("mcp-1")).thenReturn("http://mcp-tools:1100/mcp");
    hostIsUp();
    when(profiles.get(HOST, "container", "default"))
        .thenReturn(profile(List.of(custom("tools"))));
    when(profiles.updateMcpServer(any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("tools"))));

    service.sync(HOST, "container", "default", "tools");

    ArgumentCaptor<AgentMcpLink> saved = ArgumentCaptor.forClass(AgentMcpLink.class);
    verify(links).upsert(saved.capture());
    assertEquals(7L, saved.getValue().syncedRevision(), "the link must record what it synced to");
    assertEquals(1_000L, saved.getValue().createdAt(), "createdAt belongs to the original connect");
    assertTrue(saved.getValue().updatedAt() >= 1_000L);
  }

  // ── unlink, assertCustom, forget ─────────────────────────────────────────

  @Test
  void unlinkingAnEntryThatIsNotLinkedIsANotFound() {
    assertEquals("MCP entry is not linked to the catalog: tools",
        assertThrows(NoSuchElementException.class,
            () -> service.unlink(HOST, "container", "default", "tools")).getMessage());
    verify(links, never()).delete(any(), anyString(), anyString(), anyString());
  }

  @Test
  void unlinkingDropsTheLinkAndLeavesTheAgentEntryInPlaceAsCustom() {
    // the point of unlink: keep the working definition, stop tracking the catalog
    linkExists("tools", 2);
    hostIsUp();
    when(profiles.get(HOST, "container", "default"))
        .thenReturn(profile(List.of(custom("tools"))));

    AgentProfileDto result = service.unlink(HOST, "container", "default", "tools");

    verify(links).delete("dh-local", "container", "default", "tools");
    verify(profiles, never()).removeMcpServer(any(), anyString(), anyString(), anyString());
    assertEquals("custom", result.mcp().getFirst().origin());
  }

  @Test
  void aCatalogLinkedEntryCannotBeEditedDirectlyButACustomOneCan() {
    linkExists("tools", 2);

    assertEquals("catalog-linked MCP entries must be customized before direct editing",
        assertThrows(ResourceConflictException.class,
            () -> service.assertCustom(HOST, "container", "default", "tools")).getMessage());

    when(links.find("dh-local", "container", "default", "mine")).thenReturn(Optional.empty());
    service.assertCustom(HOST, "container", "default", "mine");
  }

  @Test
  void deletingAnAgentDropsAllItsLinksInOneStatement() {
    // one statement rather than one delete per alias: a partial failure used to leave a profile
    // holding some of its links
    service.deleteAgentLinks(HOST, "container", "default");

    verify(links).deleteByAgent("dh-local", "container", "default");
    verify(links, never()).delete(any(), anyString(), anyString(), anyString());
  }

  // ── catalog deletion ────────────────────────────────────────────────────

  @Test
  void deletingACatalogServerDisablesEveryAgentCopyBeforeDroppingItsLink() {
    // ordering is the safety property: a link removed before the entry is disabled leaves a live
    // connection to a server that is about to disappear, with nothing recording where it came from
    when(links.findByServer("mcp-1")).thenReturn(List.of(link("tools", "container")));
    hostIsUp();
    when(profiles.setMcpServerEnabled(HOST, "container", "default", "tools", false))
        .thenReturn(profile(List.of(disabled("tools"))));

    service.disableAndUnlinkForDeletion("mcp-1");

    InOrder order = inOrder(profiles, links);
    order.verify(profiles).setMcpServerEnabled(HOST, "container", "default", "tools", false);
    order.verify(links).delete("dh-local", "container", "default", "tools");
  }

  @Test
  void aLinkWhoseAgentOrEntryIsGoneIsDiscardedRatherThanBlockingTheDeletion() {
    when(links.findByServer("mcp-1")).thenReturn(List.of(link("gone", "container")));
    hostIsUp();
    when(profiles.setMcpServerEnabled(HOST, "container", "default", "gone", false))
        .thenThrow(new NoSuchElementException("unknown MCP server on Agent: gone"));

    service.disableAndUnlinkForDeletion("mcp-1");

    verify(links).delete("dh-local", "container", "default", "gone");
  }

  @Test
  void anEntryThatCameBackStillEnabledAbortsTheDeletionAndKeepsItsLink() {
    // the write claimed to succeed but the entry is still enabled: deleting the catalog record
    // now would leave a live connection nothing can turn off
    when(links.findByServer("mcp-1")).thenReturn(List.of(link("tools", "container")));
    hostIsUp();
    when(profiles.setMcpServerEnabled(HOST, "container", "default", "tools", false))
        .thenReturn(profile(List.of(custom("tools"))));

    assertEquals("could not disable MCP entry tools",
        assertThrows(ResourceConflictException.class,
            () -> service.disableAndUnlinkForDeletion("mcp-1")).getMessage());
    verify(links, never()).delete(any(), anyString(), anyString(), anyString());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private void hostIsUp() {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
  }

  private void registryHas(McpServerDto source) {
    when(registry.live("mcp-1")).thenReturn(source);
    when(registry.definition("mcp-1")).thenReturn(source);
  }

  private void linkExists(String alias, long syncedRevision) {
    when(links.find("dh-local", "container", "default", alias)).thenReturn(Optional.of(
        new AgentMcpLink("dh-local", "container", "default", alias, "mcp-1", syncedRevision, 1_000L, 1_000L)));
  }

  private static AgentMcpLink link(String alias, String containerId) {
    return new AgentMcpLink("dh-local", containerId, "default", alias, "mcp-1", 2, 1_000L, 1_000L);
  }

  private static McpServerDto catalog(String kind, String transport) {
    McpServerDto source = mock(McpServerDto.class);
    when(source.id()).thenReturn("mcp-1");
    when(source.name()).thenReturn("Tools");
    when(source.kind()).thenReturn(kind);
    when(source.transport()).thenReturn(transport);
    when(source.revision()).thenReturn(7L);
    return source;
  }

  private static McpServerDto managedCatalog(String hostId, String runtimeState) {
    McpServerDto source = catalog("managed", "http");
    when(source.hostId()).thenReturn(hostId);
    when(source.runtimeState()).thenReturn(runtimeState);
    return source;
  }

  private static McpServerDto stdioCatalog(String command, List<String> args) {
    McpServerDto source = catalog("stdio", "stdio");
    when(source.stdioCommand()).thenReturn(command);
    when(source.args()).thenReturn(args);
    return source;
  }

  private McpServerDefinition capturedAdd() {
    ArgumentCaptor<McpServerDefinition> definition =
        ArgumentCaptor.forClass(McpServerDefinition.class);
    verify(profiles).addMcpServer(any(), anyString(), anyString(), definition.capture());
    return definition.getValue();
  }

  private static AgentMcpServerDto custom(String name) {
    return new AgentMcpServerDto(name, name, "http", true, "unknown", 0, null, null, null,
        "http://mcp-tools:1100/mcp", null, null);
  }

  private static AgentMcpServerDto disabled(String name) {
    return new AgentMcpServerDto(name, name, "http", false, "unknown", 0, null, null, null,
        "http://mcp-tools:1100/mcp", null, null);
  }

  @Test
  void connectingAManagedCatalogServerFromAnotherHostUsesItsCrossHostUrl() {
    // the MCP network is local to each daemon, so the service name resolves nowhere from here
    McpServerDto source = managedCatalog("dh-remote", "running");
    when(source.crossHostUrl()).thenReturn("https://peer.test/mcp");
    registryHas(source);
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(any(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("tools"))));

    service.connect(HOST, "container", "default", new ConnectCatalogMcpRequest("mcp-1","tools"));

    assertEquals("https://peer.test/mcp", capturedAdd().url());
    verify(docker, never()).connectNetwork(any(), anyString(), anyString());
  }

  @Test
  void aStdioCatalogServerNeedsNoNetworkAndNoUrl() {
    registryHas(stdioCatalog("npx", List.of("a b")));
    hostIsUp();
    when(profiles.get(HOST, "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(any(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));

    service.connect(HOST, "container", "default", new ConnectCatalogMcpRequest("mcp-1","files"));

    assertNull(capturedAdd().url());
    // one argument carrying a space stays one argument
    assertEquals(List.of("a b"), capturedAdd().args());
    verify(docker, never()).connectNetwork(any(), anyString(), anyString());
  }
}
