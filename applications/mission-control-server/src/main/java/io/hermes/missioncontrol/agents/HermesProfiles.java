package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.docker.ContainerNotRunningException;
import io.hermes.missioncontrol.docker.ContainerWork;
import io.hermes.missioncontrol.agents.HermesModelConfig.ConfigInfo;
import io.hermes.missioncontrol.agents.HermesModelConfig.ModelTarget;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ContainerActivityDto;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.agents.api.SkillFilesDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A Hermes container's agent profiles, as the dashboard sees them.
 *
 * <p>This class owns the profile lifecycle (create, clone, delete) and the read-back that
 * assembles an {@link AgentProfileDto}. Everything else a profile is made of belongs to a
 * collaborator, each of which owns one file or one concern inside the container:
 *
 * <ul>
 *   <li>{@link HermesContainerFiles} — the exec seam and every container read/write
 *   <li>{@link HermesEnvFile} — {@code .env}, where API keys live
 *   <li>{@link HermesModelConfig} — the {@code model.*} / {@code auxiliary.*} round trip
 *   <li>{@link HermesSkills} — SKILL.md files and the enable/disable list
 *   <li>{@link HermesProfileMcp} — the {@code mcp_servers} block and its probe cache
 *   <li>{@link HermesSessions} — the conversation store in {@code state.db}
 *   <li>{@link HermesGatewayLogs} — the per-profile s6 gateway log
 *   <li>{@link HermesGatewayState} — the platforms and runtime state in
 *       {@code gateway_state.json}, and the {@code ESTOP} pause sentinel
 *   <li>{@link ProfileInventory} — which profiles the container has at all
 *   <li>{@link CatalogLinkOverlay} — the dashboard-owned MCP catalog links, laid over the
 *       entries the container reports
 * </ul>
 *
 * <p>The mutating endpoints all return the whole profile, so each one reads as
 * "delegate the edit, then re-read" — that re-read is why this facade exists rather
 * than the controller wiring the collaborators up itself.
 */
@Service
public class HermesProfiles implements ContainerWork {

  private static final Logger log = LoggerFactory.getLogger(HermesProfiles.class);

  /**
   * {@code containerId/name} of every profile whose create is between {@code hermes profile
   * create} and the last configuration write. The directory exists for that whole window, so
   * without this the inventory lists a profile the dashboard is about to either finish or
   * delete — and a stop in that window is what makes it the second one.
   */
  private final Set<String> creating = ConcurrentHashMap.newKeySet();

  private final HermesContainerFiles files;
  private final HermesEnvFile env;
  private final HermesModelConfig modelConfig;
  private final HermesSkills skills;
  private final HermesProfileMcp mcp;
  private final HermesSessions sessions;
  private final HermesGatewayLogs gatewayLogs;
  private final HermesGatewayState gatewayState;
  private final ProfileInventory inventory;
  private final CatalogLinkOverlay catalogLinks;

  public HermesProfiles(
      HermesContainerFiles files,
      HermesEnvFile env,
      HermesModelConfig modelConfig,
      HermesSkills skills,
      HermesProfileMcp mcp,
      HermesSessions sessions,
      HermesGatewayLogs gatewayLogs,
      HermesGatewayState gatewayState,
      ProfileInventory inventory,
      CatalogLinkOverlay catalogLinks) {
    this.files = files;
    this.env = env;
    this.modelConfig = modelConfig;
    this.skills = skills;
    this.mcp = mcp;
    this.sessions = sessions;
    this.gatewayLogs = gatewayLogs;
    this.gatewayState = gatewayState;
    this.inventory = inventory;
    this.catalogLinks = catalogLinks;
  }

  // ── inventory ──────────────────────────────────────────────────────────────

  public List<AgentProfileDto> list(DockerHostRef host, String containerId) {
    try {
      List<AgentProfileDto> profiles = new ArrayList<>();
      for (String name : inventory.names(host, containerId)) {
        if (creating.contains(creatingKey(containerId, name))) continue;   // not yet an agent
        profiles.add(readProfile(host, containerId, name));
      }
      return profiles;
    } catch (ContainerNotRunningException stopped) {
      // A stale dashboard client asking to exec inside a stopped container. Inventory is
      // simply unavailable until it restarts.
      return List.of();
    }
  }

  /** Reads a single profile's current state (config, soul, memory, skills, mcp). */
  public AgentProfileDto get(DockerHostRef host, String containerId, String name) {
    return readProfile(host, containerId, name);
  }

  /**
   * The four documents a profile is described by, then its skills, MCP entries and gateway state.
   *
   * <p>The four are read in one exec rather than four. This runs once per profile inside
   * {@link #list}, which the agents page polls every twelve seconds, so each read here is paid
   * for by every profile in the container on every poll.
   *
   * <p>The one place an {@link AgentProfileDto} is built, which is why the catalog-link overlay
   * is applied here: a profile that leaves this method is the only kind of profile there is, so
   * nothing downstream can forget to ask for it — see {@link CatalogLinkOverlay}.
   */
  private AgentProfileDto readProfile(DockerHostRef host, String containerId, String name) {
    String dir = ProfilePaths.profileDir(name);
    Map<String, String> documents = files.readFiles(host, containerId, List.of(
        dir + "/config.yaml", dir + "/SOUL.md", dir + "/MEMORY.md", dir + "/.env"));
    String configYaml = documents.get(dir + "/config.yaml");
    String soul = documents.get(dir + "/SOUL.md");
    String memoryMd = documents.get(dir + "/MEMORY.md");
    String envFile = documents.get(dir + "/.env");
    Map<?, ?> configMap = YamlValues.parseMap(configYaml);
    ConfigInfo config = modelConfig.parseConfig(configMap);
    List<SkillDto> skillList = skills.list(host, containerId, name, configMap);
    List<AgentMcpServerDto> mcpList = mcp.list(host, containerId, name, configMap);
    HermesGatewayState.Reading gateway = gatewayState.read(host, containerId, name);
    return catalogLinks.enrich(host, new AgentProfileDto(
        ProfilePaths.profileId(containerId, name),
        containerId,
        name,
        "default".equals(name) ? "Default profile" : "Profile",
        "idle",
        config.provider(),
        config.model(),
        HermesEnvFile.maskApiKey(envFile, config.provider()),
        config.cwd().isBlank() ? ProfilePaths.HERMES_HOME : config.cwd(),
        soul,
        memoryMd,
        configYaml,
        skillList,
        mcpList,
        gateway.integrations(),
        gateway.gateway(),
        System.currentTimeMillis()));
  }

  // ── lifecycle ──────────────────────────────────────────────────────────────

  public AgentProfileDto create(DockerHostRef host, ProfileSpec spec) {
    String profileName = createProfileBare(host, spec);
    return readProfile(host, spec.containerId(), profileName);
  }

  /** Creates and configures the profile but skips the read-back. The template
   *  create/deploy flow re-reads the profile after layering its blueprint, so the
   *  read here would be thrown away — callers that need the DTO use {@link #create}.
   *  Returns the created profile name. */
  public String createProfileBare(DockerHostRef host, ProfileSpec spec) {
    String profileName = spec.name();
    String containerId = spec.containerId();
    List<String> command = new ArrayList<>(List.of("hermes", "profile", "create", profileName));
    String cloneFrom = spec.cloneFrom();
    if (cloneFrom != null) {
      command.addAll(List.of("--clone", "--clone-from", cloneFrom));
    }
    String key = creatingKey(containerId, profileName);
    // false when a caller already holds the window through whileCreating: then it is theirs to
    // close, and closing it here would list the profile while they are still writing to it
    boolean marked = creating.add(key);
    boolean created = false;
    try {
      files.exec(host, containerId, command);
      created = true;
      configureModel(host, spec);
      return profileName;
    } catch (RuntimeException failure) {
      if (created) {
        // said out loud: the profile was on disk and the dashboard may have shown it
        log.warn("rolling back profile {} in {}: {}", profileName, containerId, failure.getMessage());
        try {
          delete(host, containerId, profileName);
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    } finally {
      if (marked) creating.remove(key);
    }
  }

  /**
   * Keeps {@code name} inside the creating window for the whole of {@code work}.
   *
   * <p>For the callers that layer more onto a profile after {@link #createProfileBare} returns —
   * a blueprint's soul, skills, MCP entries and keys. Without this the window closed with the
   * bare create, the Agents page listed the profile a dozen writes early, and a shell opened on
   * it started hermes before its API key had landed, which hermes reports as "No inference
   * provider is configured yet". One try/finally here rather than a begin/end pair, because a
   * window left open would refuse every stop of that container until the dashboard restarted.
   */
  public <T> T whileCreating(String containerId, String name, Supplier<T> work) {
    String key = creatingKey(containerId, name);
    boolean marked = creating.add(key);
    try {
      return work.get();
    } finally {
      if (marked) creating.remove(key);
    }
  }

  /**
   * Writes the model settings a spec carries onto a profile that exists: {@code model.*} and the
   * auxiliary pins, the {@code .env} seed and the API key(s). The second half of
   * {@link #createProfileBare}, on its own for the one profile this class never creates — the
   * {@code default} profile the image initializes, which a container deploy may hand a blueprint.
   * Throws rather than rolls back: what owns the profile decides what a failure costs.
   */
  public void configureModel(DockerHostRef host, ProfileSpec spec) {
    String containerId = spec.containerId();
    String profileName = spec.name();
    ModelTarget auxiliary = HermesModelConfig.auxiliaryTarget(
        spec.provider(), spec.model(), spec.baseUrl(), spec.auxiliary());
    modelConfig.write(host, containerId, profileName,
        spec.provider(), spec.model(), spec.baseUrl(), auxiliary);
    modelConfig.assertConfigured(host, containerId, profileName);
    env.seedIfMissing(host, containerId, profileName);
    modelConfig.writeApiKey(host, containerId, profileName, spec.provider(), spec.apiKey());
    modelConfig.writeAuxiliaryApiKey(host, containerId, profileName, auxiliary, spec.auxiliary());
  }

  /** The directory the agent's terminal tool starts in — {@code terminal.cwd}. */
  public void setWorkingDir(DockerHostRef host, String containerId, String name, String cwd) {
    modelConfig.writeWorkingDir(host, containerId, name, cwd);
  }

  private static String creatingKey(String containerId, String name) {
    return containerId + '/' + name;
  }

  /** Names still inside {@link #createProfileBare}, for this container. */
  @Override
  public List<String> creating(String containerId) {
    String prefix = containerId + '/';
    return creating.stream()
        .filter(k -> k.startsWith(prefix))
        .map(k -> k.substring(prefix.length()))
        .sorted()
        .toList();
  }

  /**
   * Removes the profile from the container. Idempotent: a profile that is already gone is not
   * asked to be deleted again.
   *
   * <p>The guard is what makes retrying a delete useful. {@code hermes profile delete} exits
   * non-zero on a name it does not know, so without it a delete whose dashboard-side cleanup
   * failed could never be retried — the retry died here, before reaching the cleanup that was
   * the only thing left to do.
   */
  public void delete(DockerHostRef host, String containerId, String name) {
    if (files.dirExists(host, containerId, ProfilePaths.profileDir(name))) {
      files.exec(host, containerId, List.of("hermes", "profile", "delete", name, "--yes"));
      // hermes leaves the supervised gateway's log directory behind, so without this every
      // profile ever deleted stays on the volume as three empty files under logs/gateways
      files.removeTree(host, containerId, ProfilePaths.gatewayLogDir(name));
    }
    mcp.evictProfile(host, containerId, name);
  }

  // ── documents ──────────────────────────────────────────────────────────────

  public void updateSoul(DockerHostRef host, String containerId, String name, String soul) {
    writeProfileFile(host, containerId, name, "SOUL.md", soul);
  }

  public void updateMemory(DockerHostRef host, String containerId, String name, String memory) {
    writeProfileFile(host, containerId, name, "MEMORY.md", memory);
  }

  /**
   * Replaces one of a profile's documents.
   *
   * <p>Atomic, and taken under the profile's lock, because {@code config.yaml} arrives here
   * whole from the editor while four other paths are reading it, changing one key, and writing
   * it back. A non-atomic write also lets a reader see the file half-replaced — and a truncated
   * {@code config.yaml} is not blank, so it slips past
   * {@code AgentMcpCatalogService.dropStrandedLinks}' emptiness guard and reads as a profile
   * whose MCP entries were all removed.
   */
  private void writeProfileFile(
      DockerHostRef host, String containerId, String name, String fileName, String content) {
    files.serialized(containerId, name, () -> {
      String path = files.requireProfileDir(host, containerId, name) + "/" + fileName;
      files.writeFileAtomically(host, containerId, path, content == null ? "" : content);
    });
  }

  public AgentProfileDto updateConfig(DockerHostRef host, String containerId, String name, String configYaml) {
    YamlValues.requireMapping(configYaml, "config.yaml must be a YAML mapping");
    writeProfileFile(host, containerId, name, "config.yaml", configYaml);
    return readProfile(host, containerId, name);
  }

  // ── skills ─────────────────────────────────────────────────────────────────

  public AgentProfileDto setSkillEnabled(
      DockerHostRef host, String containerId, String profileName, String skillName, boolean enabled) {
    skills.setEnabled(host, containerId, profileName, skillName, enabled);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto installSkill(
      DockerHostRef host, String containerId, String profileName, String skillId) {
    skills.install(host, containerId, profileName, skillId);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto uninstallSkill(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    skills.uninstall(host, containerId, profileName, skillName);
    return readProfile(host, containerId, profileName);
  }

  public SkillContentDto readSkillContent(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    return skills.readContent(host, containerId, profileName, skillName);
  }

  /** Writes a library skill's whole file set onto the profile. Unlike {@link #installSkill}
   *  this never runs {@code hermes skills install} — the library owns the content, so there
   *  is nothing for the Skills Hub to resolve. */
  public AgentProfileDto installSkillFiles(
      DockerHostRef host, String containerId, String profileName, String skillName,
      Map<String, String> skillFiles) {
    skills.writeSkillFiles(host, containerId, profileName, skillName, skillFiles);
    return readProfile(host, containerId, profileName);
  }

  /** A skill's files with their contents, for importing one off an agent into the library. */
  public SkillFilesDto readSkillFiles(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    return skills.readSkillFiles(host, containerId, profileName, skillName);
  }

  /** Overwrites a skill's SKILL.md, then re-reads the profile so the refreshed
   *  name/version/description/source flow back to the caller. */
  public AgentProfileDto updateSkillContent(
      DockerHostRef host, String containerId, String profileName, String skillName, String body) {
    skills.updateContent(host, containerId, profileName, skillName, body);
    return readProfile(host, containerId, profileName);
  }

  // ── MCP servers ────────────────────────────────────────────────────────────

  public AgentProfileDto addMcpServer(
      DockerHostRef host, String containerId, String profileName, McpServerDefinition definition) {
    mcp.add(host, containerId, profileName, definition);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto updateMcpServer(
      DockerHostRef host, String containerId, String profileName, String serverName,
      McpServerDefinition definition) {
    mcp.update(host, containerId, profileName, serverName, definition);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto setMcpServerEnabled(
      DockerHostRef host, String containerId, String profileName, String serverName, boolean enabled) {
    mcp.setEnabled(host, containerId, profileName, serverName, enabled);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto removeMcpServer(
      DockerHostRef host, String containerId, String profileName, String serverName) {
    mcp.remove(host, containerId, profileName, serverName);
    return readProfile(host, containerId, profileName);
  }

  /** Probes a single MCP server with Hermes' own MCP initialize handshake. */
  public McpTestResult testMcpServer(
      DockerHostRef host, String containerId, String profileName, String serverName) {
    return mcp.test(host, containerId, profileName, serverName);
  }

  // ── observability ──────────────────────────────────────────────────────────

  public List<IntegrationDto> integrations(DockerHostRef host, String containerId, String profileName) {
    return gatewayState.integrations(host, containerId, profileName);
  }

  /**
   * What stopping this container right now would interrupt, across every profile in it.
   *
   * <p>Deliberately not part of the container inventory. That polls every host on a timer,
   * and answering this means one exec per profile — the cost belongs on the click that is
   * about to destroy work, not on the fleet view that runs whether anyone is looking.
   *
   * <p>A stopped container has nothing in flight and nothing to exec into, so it reports
   * idle rather than failing: the caller is asking "is this safe", and for a container that
   * is already down the answer is yes.
   */
  public ContainerActivityDto activity(DockerHostRef host, String containerId) {
    try {
      int active = 0;
      List<String> busy = new ArrayList<>();
      List<String> paused = new ArrayList<>();
      List<String> unreadable = new ArrayList<>();
      List<String> creatingNow = creating(containerId);
      for (String name : inventory.names(host, containerId)) {
        // it has no gateway yet; what a stop interrupts is the create itself
        if (creatingNow.contains(name)) continue;
        GatewayDto gateway = gatewayState.read(host, containerId, name).gateway();
        if (gateway.paused()) paused.add(name);
        if (gateway.activeAgents() > 0) {
          active += gateway.activeAgents();
          busy.add(name);
        } else if (gateway.state().isBlank() && !gateway.paused()) {
          // no gateway_state.json and no sentinel: the gateway has written nothing, so
          // "nothing is running" is an absence of evidence rather than evidence of absence
          unreadable.add(name);
        }
      }
      return new ContainerActivityDto(active, List.copyOf(busy), List.copyOf(paused),
          List.copyOf(unreadable), creatingNow);
    } catch (ContainerNotRunningException stopped) {
      return new ContainerActivityDto(0, List.of(), List.of(), List.of(), List.of());
    }
  }

  // ── emergency stop ─────────────────────────────────────────────────────────

  /**
   * Engages hermes' own emergency stop for this profile: cron dispatch, kanban dispatch and
   * new gateway turns stop on their next check, and in-flight work is left to finish.
   *
   * <p>The reason for going through {@code hermes pause} rather than stopping the container
   * is the difference between the two — a {@code docker stop} kills the turns that are
   * running, which is what an operator reaching for a panic button almost never wants.
   */
  public AgentProfileDto pause(
      DockerHostRef host, String containerId, String profileName, String reason) {
    List<String> command = new ArrayList<>(ProfilePaths.hermesCli(profileName, "pause"));
    if (reason != null && !reason.isBlank()) {
      command.addAll(List.of("--reason", reason.trim()));
    }
    files.exec(host, containerId, command);
    return readProfile(host, containerId, profileName);
  }

  /** Lifts the pause. Dispatch picks up on the next tick; no restart is involved. */
  public AgentProfileDto resume(DockerHostRef host, String containerId, String profileName) {
    files.exec(host, containerId, ProfilePaths.hermesCli(profileName, "resume"));
    return readProfile(host, containerId, profileName);
  }

  /** Reads the profile-specific s6 gateway log, including rotated files, rather
   * than reusing Docker's container-wide stdout/stderr stream. */
  public List<LogLineDto> logs(DockerHostRef host, String containerId, String profileName, int tail) {
    return gatewayLogs.read(host, containerId, profileName, tail);
  }

  // ── sessions ───────────────────────────────────────────────────────────────

  public List<SessionDto> listSessions(DockerHostRef host, String containerId, String profileName) {
    return sessions.list(host, containerId, profileName);
  }

  /** Returns the chat history (messages) for a session as a JSON array string. */
  public String readSessionMessages(
      DockerHostRef host, String containerId, String profileName, String sessionId) {
    return sessions.readMessages(host, containerId, profileName, sessionId);
  }

  public void deleteSession(
      DockerHostRef host, String containerId, String profileName, String sessionId) {
    sessions.delete(host, containerId, profileName, sessionId);
  }
}
