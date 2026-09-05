package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.HermesModelConfig.ConfigInfo;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.ContainerNotRunningException;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.agents.api.ContainerActivityDto;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The facade every agent endpoint calls, and the wiring it is responsible for.
 *
 * <p>{@link HermesProfiles} owns almost no logic — each method resolves the profile's paths and
 * hands off to the collaborator that owns the concern. What it does own is which collaborator,
 * with which arguments, and whether the caller gets a refreshed profile back: a mutation that
 * forgets the re-read hands the dashboard the state from before the write, and a path built from
 * the wrong name edits another agent. That is what this pins.
 */
class HermesProfilesDelegationTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///sock");
  private static final String CONTAINER = "c1";
  private static final String PROFILE = "scout";
  private static final String DIR = "/opt/data/profiles/scout";

  /** What the container holds, so the batched read can answer by path. */
  private final Map<String, String> documents = new LinkedHashMap<>();
  private HermesContainerFiles files;
  private HermesEnvFile env;
  private HermesModelConfig modelConfig;
  private HermesSkills skills;
  private HermesProfileMcp mcp;
  private HermesSessions sessions;
  private HermesGatewayLogs gatewayLogs;
  private HermesGatewayState gatewayState;
  private HermesProfiles profiles;

  @BeforeEach
  void setUp() {
    files = AgentsWiring.mockFiles();
    env = mock(HermesEnvFile.class);
    modelConfig = mock(HermesModelConfig.class);
    skills = mock(HermesSkills.class);
    mcp = mock(HermesProfileMcp.class);
    sessions = mock(HermesSessions.class);
    gatewayLogs = mock(HermesGatewayLogs.class);
    gatewayState = mock(HermesGatewayState.class);
    profiles = new HermesProfiles(files, env, modelConfig, skills, mcp, sessions, gatewayLogs,
        gatewayState, new ProfileInventory(files), AgentsWiring.catalogLinks());

    when(files.requireProfileDir(HOST, CONTAINER, PROFILE)).thenReturn(DIR);
    when(files.readFile(any(), anyString(), anyString())).thenReturn("");
    // the four profile documents come back from one exec, so the stub answers by path
    when(files.readFiles(any(), anyString(), anyList())).thenAnswer(call -> {
      Map<String, String> answered = new LinkedHashMap<>();
      for (String path : call.<List<String>>getArgument(2)) {
        answered.put(path, documents.getOrDefault(path, ""));
      }
      return answered;
    });
    when(modelConfig.parseConfig(any())).thenReturn(new ConfigInfo("anthropic", "claude-opus-5", "/work"));
    when(gatewayState.read(any(), anyString(), anyString()))
        .thenReturn(new HermesGatewayState.Reading(GatewayDto.unknown(), List.of()));
  }

  // ── the writes a blueprint adds ─────────────────────────────────────────

  @Test
  void theWorkingDirGoesThroughTheModelConfigWriterUntouched() {
    profiles.setWorkingDir(HOST, CONTAINER, PROFILE, "/work");

    verify(modelConfig).writeWorkingDir(HOST, CONTAINER, PROFILE, "/work");
  }

  @Test
  void configuringAnExistingProfileWritesTheModelThenTheKeysAndCreatesNothing() {
    ProfileSpec spec = new ProfileSpec(CONTAINER, PROFILE, "anthropic", "claude-opus-5", "sk-ant", null, null, null);

    profiles.configureModel(HOST, spec);

    InOrder order = inOrder(modelConfig, env);
    order.verify(modelConfig).write(eq(HOST), eq(CONTAINER), eq(PROFILE), eq("anthropic"), eq("claude-opus-5"),
        isNull(), any());
    order.verify(modelConfig).assertConfigured(HOST, CONTAINER, PROFILE);
    order.verify(env).seedIfMissing(HOST, CONTAINER, PROFILE);
    order.verify(modelConfig).writeApiKey(HOST, CONTAINER, PROFILE, "anthropic", "sk-ant");
    verify(files, never()).exec(any(), anyString(), anyList());
  }

  // ── reading ─────────────────────────────────────────────────────────────

  @Test
  void readingOneProfileAssemblesItFromEveryCollaborator() {
    documents.put(DIR + "/SOUL.md", "be useful");
    documents.put(DIR + "/MEMORY.md", "remembered");

    AgentProfileDto profile = profiles.get(HOST, CONTAINER, PROFILE);

    assertEquals(PROFILE, profile.name());
    assertEquals("Profile", profile.role());
    assertEquals("anthropic", profile.provider());
    assertEquals("claude-opus-5", profile.model());
    assertEquals("/work", profile.cwd());
    assertEquals("be useful", profile.soul());
    assertEquals("remembered", profile.memoryMd());
    verify(skills).list(HOST, CONTAINER, PROFILE, Map.of());
    verify(mcp).list(HOST, CONTAINER, PROFILE, Map.of());
    verify(gatewayState).read(HOST, CONTAINER, PROFILE);
  }

  @Test
  void theDefaultProfileIsLabelledAsSuchAndReadFromTheHermesHome() {
    when(files.requireProfileDir(HOST, CONTAINER, "default")).thenReturn("/opt/data");

    AgentProfileDto profile = profiles.get(HOST, CONTAINER, "default");

    assertEquals("Default profile", profile.role());
    // one exec for all four documents, not one each: this runs per profile on a 12s poll
    verify(files).readFiles(HOST, CONTAINER, List.of(
        "/opt/data/config.yaml", "/opt/data/SOUL.md", "/opt/data/MEMORY.md", "/opt/data/.env"));
  }

  @Test
  void listingReadsTheDefaultProfileAndEveryValidlyNamedDirectory() {
    when(files.dirExists(HOST, CONTAINER, "/opt/data")).thenReturn(true);
    when(files.exec(any(), anyString(), any())).thenReturn(
        new io.hermes.missioncontrol.docker.DockerExecService.ExecResult(
            0, "scout\ndefault\n../escape\n\nscribe\n", ""));
    when(files.requireProfileDir(any(), anyString(), anyString())).thenReturn(DIR);

    List<AgentProfileDto> listed = profiles.list(HOST, CONTAINER);

    // 'default' is added once from the home directory, not twice; a name that could escape the
    // profiles directory is dropped before it is concatenated into a container path
    assertEquals(List.of("default", "scout", "scribe"),
        listed.stream().map(AgentProfileDto::name).toList());
  }

  @Test
  void aContainerWithNoHermesHomeListsOnlyItsNamedProfiles() {
    when(files.dirExists(HOST, CONTAINER, "/opt/data")).thenReturn(false);
    when(files.exec(any(), anyString(), any())).thenReturn(
        new io.hermes.missioncontrol.docker.DockerExecService.ExecResult(0, "scout\n", ""));
    when(files.requireProfileDir(any(), anyString(), anyString())).thenReturn(DIR);

    assertEquals(List.of("scout"),
        profiles.list(HOST, CONTAINER).stream().map(AgentProfileDto::name).toList());
  }

  @Test
  void aStoppedContainerListsNothingRatherThanFailingTheAgentsPage() {
    // Docker answers 409 when a stale dashboard client asks to exec in a stopped container;
    // inventory is simply unavailable until it restarts
    when(files.dirExists(any(), anyString(), anyString()))
        .thenThrow(new io.hermes.missioncontrol.docker.ContainerNotRunningException(
            "Hermes command needs a running container: c1", null));

    assertTrue(profiles.list(HOST, CONTAINER).isEmpty());
  }

  // ── documents ───────────────────────────────────────────────────────────

  @Test
  void theSoulAndMemoryAreWrittenIntoTheProfileDirectory() {
    profiles.updateSoul(HOST, CONTAINER, PROFILE, "be useful");
    profiles.updateMemory(HOST, CONTAINER, PROFILE, null);

    // atomic: a reader must see the old document or the new one, never a half-written file
    verify(files).writeFileAtomically(HOST, CONTAINER, DIR + "/SOUL.md", "be useful");
    // a null document is written as empty rather than as the string "null"
    verify(files).writeFileAtomically(HOST, CONTAINER, DIR + "/MEMORY.md", "");
  }

  @Test
  void aConfigThatIsNotAMappingIsRefusedBeforeItOverwritesTheFile() {
    assertEquals("config.yaml must be a YAML mapping",
        assertThrows(IllegalArgumentException.class,
            () -> profiles.updateConfig(HOST, CONTAINER, PROFILE, "- a list\n")).getMessage());

    verify(files, never()).writeFileAtomically(any(), anyString(), anyString(), anyString());
  }

  @Test
  void aValidConfigIsWrittenAndTheProfileIsReadBack() {
    AgentProfileDto updated = profiles.updateConfig(HOST, CONTAINER, PROFILE, "model: opus\n");

    // config.yaml has five editors; a torn read of it is not blank, so it slips past the
    // emptiness guard that stops the agents poll dropping a profile's catalog links
    verify(files).writeFileAtomically(HOST, CONTAINER, DIR + "/config.yaml", "model: opus\n");
    assertEquals(PROFILE, updated.name(), "the caller gets the state after the write");
  }

  // ── skills ──────────────────────────────────────────────────────────────

  @Test
  void everySkillEndpointHandsOffToTheSkillsCollaboratorAndReturnsTheFreshProfile() {
    when(skills.readContent(HOST, CONTAINER, PROFILE, "refactor"))
        .thenReturn(new SkillContentDto("refactor", DIR + "/skills/refactor", "body", List.of()));

    assertEquals(PROFILE, profiles.setSkillEnabled(HOST, CONTAINER, PROFILE, "refactor", false).name());
    assertEquals(PROFILE, profiles.installSkill(HOST, CONTAINER, PROFILE, "refactor").name());
    assertEquals(PROFILE, profiles.uninstallSkill(HOST, CONTAINER, PROFILE, "refactor").name());
    assertEquals(PROFILE, profiles.updateSkillContent(HOST, CONTAINER, PROFILE, "refactor", "body").name());
    assertEquals("body", profiles.readSkillContent(HOST, CONTAINER, PROFILE, "refactor").body());

    verify(skills).setEnabled(HOST, CONTAINER, PROFILE, "refactor", false);
    verify(skills).install(HOST, CONTAINER, PROFILE, "refactor");
    verify(skills).uninstall(HOST, CONTAINER, PROFILE, "refactor");
    verify(skills).updateContent(HOST, CONTAINER, PROFILE, "refactor", "body");
  }

  // ── mcp ─────────────────────────────────────────────────────────────────

  @Test
  void everyMcpEndpointHandsOffToTheMcpCollaboratorAndReturnsTheFreshProfile() {
    McpServerDefinition definition = McpServerDefinition.from(new AddMcpServerRequest(
        "files", "http", "http://x:1/mcp", null, null, true, null, null));
    when(mcp.test(HOST, CONTAINER, PROFILE, "files"))
        .thenReturn(new McpTestResult("files", "connected", 3, 12L, null, 1L));

    assertEquals(PROFILE, profiles.addMcpServer(HOST, CONTAINER, PROFILE, definition).name());
    assertEquals(PROFILE, profiles.updateMcpServer(HOST, CONTAINER, PROFILE, "files", definition).name());
    assertEquals(PROFILE, profiles.setMcpServerEnabled(HOST, CONTAINER, PROFILE, "files", false).name());
    assertEquals(PROFILE, profiles.removeMcpServer(HOST, CONTAINER, PROFILE, "files").name());
    assertEquals("connected", profiles.testMcpServer(HOST, CONTAINER, PROFILE, "files").status());

    verify(mcp).add(HOST, CONTAINER, PROFILE, definition);
    verify(mcp).update(HOST, CONTAINER, PROFILE, "files", definition);
    verify(mcp).setEnabled(HOST, CONTAINER, PROFILE, "files", false);
    verify(mcp).remove(HOST, CONTAINER, PROFILE, "files");
  }

  // ── delete ──────────────────────────────────────────────────────────────

  @Test
  void deletingAProfileAlsoDropsTheGatewayLogDirHermesLeavesBehind() {
    when(files.dirExists(HOST, CONTAINER, DIR)).thenReturn(true);

    profiles.delete(HOST, CONTAINER, PROFILE);

    verify(files).exec(HOST, CONTAINER, List.of("hermes", "profile", "delete", PROFILE, "--yes"));
    verify(files).removeTree(HOST, CONTAINER, "/opt/data/logs/gateways/" + PROFILE);
    verify(mcp).evictProfile(HOST, CONTAINER, PROFILE);
  }

  // ── what a stop would interrupt ─────────────────────────────────────────

  @Test
  void activityAddsUpTurnsInFlightAcrossEveryProfileInTheContainer() {
    when(files.exec(any(), anyString(), any()))
        .thenReturn(new ExecResult(0, PROFILE + "\nops\n", ""));
    when(gatewayState.read(HOST, CONTAINER, PROFILE)).thenReturn(reading(2, false, "running"));
    when(gatewayState.read(HOST, CONTAINER, "ops")).thenReturn(reading(1, false, "running"));

    ContainerActivityDto activity = profiles.activity(HOST, CONTAINER);

    assertEquals(3, activity.activeAgents());
    assertEquals(List.of(PROFILE, "ops"), activity.busyProfiles());
    assertEquals(List.of(), activity.unreadable());
  }

  @Test
  void anIdleProfileIsNotListedAndAPausedOneIsNamedSeparately() {
    when(files.exec(any(), anyString(), any()))
        .thenReturn(new ExecResult(0, PROFILE + "\nops\n", ""));
    when(gatewayState.read(HOST, CONTAINER, PROFILE)).thenReturn(reading(0, false, "running"));
    when(gatewayState.read(HOST, CONTAINER, "ops")).thenReturn(reading(0, true, "running"));

    ContainerActivityDto activity = profiles.activity(HOST, CONTAINER);

    assertEquals(0, activity.activeAgents());
    assertEquals(List.of(), activity.busyProfiles());
    assertEquals(List.of("ops"), activity.pausedProfiles());
  }

  @Test
  void aProfileStillBeingCreatedIsNamedAsSuchAndLeftOutOfTheInventory() {
    // the create's second step is the window: the directory is there, the gateway is not,
    // and a stop here fails the step and rolls the profile back
    when(files.exec(any(), anyString(), any()))
        .thenReturn(new ExecResult(0, PROFILE + "\nops\n", ""));
    when(gatewayState.read(HOST, CONTAINER, PROFILE)).thenReturn(reading(0, false, "running"));
    ContainerActivityDto[] during = new ContainerActivityDto[1];
    List<AgentProfileDto>[] listed = new List[1];
    doAnswer(call -> {
      during[0] = profiles.activity(HOST, CONTAINER);
      listed[0] = profiles.list(HOST, CONTAINER);
      return null;
    }).when(modelConfig).write(any(), anyString(), anyString(), any(), any(), any(), any());

    profiles.createProfileBare(HOST, new ProfileSpec(
        CONTAINER, "ops", "anthropic", "model", null, null, null, null));

    assertEquals(List.of("ops"), during[0].creatingProfiles());
    assertEquals(List.of(), during[0].busyProfiles());
    assertEquals(List.of(), during[0].unreadable());
    assertEquals(List.of(PROFILE), listed[0].stream().map(AgentProfileDto::name).toList());
    assertEquals(List.of(), profiles.activity(HOST, CONTAINER).creatingProfiles());
  }

  @Test
  void aProfileWithNoGatewayStateIsUnreadableRatherThanQuietlyIdle() {
    // the difference between "nothing is running" and "we could not tell" is the whole point:
    // reporting the second as the first is how a stop kills work while claiming it was safe
    when(files.exec(any(), anyString(), any())).thenReturn(new ExecResult(0, PROFILE + "\n", ""));
    when(gatewayState.read(HOST, CONTAINER, PROFILE)).thenReturn(reading(0, false, ""));

    ContainerActivityDto activity = profiles.activity(HOST, CONTAINER);

    assertEquals(List.of(PROFILE), activity.unreadable());
    assertEquals(0, activity.activeAgents());
  }

  @Test
  void aStoppedContainerReportsIdleRatherThanFailingTheCheck() {
    // the caller is asking "is stopping this safe"; for one already down the answer is yes
    when(files.exec(any(), anyString(), any()))
        .thenThrow(new ContainerNotRunningException(CONTAINER, new RuntimeException("stopped")));

    assertEquals(0, profiles.activity(HOST, CONTAINER).activeAgents());
  }

  private static HermesGatewayState.Reading reading(int active, boolean paused, String state) {
    return new HermesGatewayState.Reading(
        new GatewayDto(state, state, active, "0.20.5", "ok", paused, null), List.of());
  }

  // ── emergency stop ──────────────────────────────────────────────────────

  @Test
  void pausingRunsHermesOwnEmergencyStopRatherThanTouchingTheContainer() {
    profiles.pause(HOST, CONTAINER, PROFILE, "rotating credentials");

    verify(files).exec(HOST, CONTAINER,
        List.of("hermes", "-p", PROFILE, "pause", "--reason", "rotating credentials"));
  }

  @Test
  void aPauseWithNoReasonGivenPassesNoReasonFlagAtAll() {
    // hermes stores an empty --reason as the reason, which reads worse than none
    profiles.pause(HOST, CONTAINER, PROFILE, "   ");

    verify(files).exec(HOST, CONTAINER, List.of("hermes", "-p", PROFILE, "pause"));
  }

  @Test
  void resumingLiftsThePauseAndReReadsTheProfile() {
    assertEquals(PROFILE, profiles.resume(HOST, CONTAINER, PROFILE).name());

    verify(files).exec(HOST, CONTAINER, List.of("hermes", "-p", PROFILE, "resume"));
  }

  // ── reads that pass straight through ────────────────────────────────────

  @Test
  void integrationsLogsAndSessionsAreReadFromTheirOwnCollaborators() {
    when(gatewayLogs.read(HOST, CONTAINER, PROFILE, 50))
        .thenReturn(List.of(new LogLineDto(1L, "info", PROFILE, "started")));
    when(sessions.list(HOST, CONTAINER, PROFILE))
        .thenReturn(List.of(new SessionDto("s-1", "first", "cli", 1L, 2, "done")));
    when(sessions.readMessages(HOST, CONTAINER, PROFILE, "s-1")).thenReturn("[]");

    assertTrue(profiles.integrations(HOST, CONTAINER, PROFILE).isEmpty());
    assertEquals("started", profiles.logs(HOST, CONTAINER, PROFILE, 50).getFirst().msg());
    assertEquals("s-1", profiles.listSessions(HOST, CONTAINER, PROFILE).getFirst().id());
    assertEquals("[]", profiles.readSessionMessages(HOST, CONTAINER, PROFILE, "s-1"));

    profiles.deleteSession(HOST, CONTAINER, PROFILE, "s-1");
    verify(sessions).delete(HOST, CONTAINER, PROFILE, "s-1");
    verify(gatewayState).integrations(HOST, CONTAINER, PROFILE);
    verify(gatewayLogs).read(HOST, CONTAINER, PROFILE, 50);
  }
}
