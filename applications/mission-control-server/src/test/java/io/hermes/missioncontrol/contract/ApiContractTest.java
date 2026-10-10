package io.hermes.missioncontrol.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyProviderDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.CronJobDto;
import io.hermes.missioncontrol.agents.api.DeployedPart;
import io.hermes.missioncontrol.agents.api.CronJobsDto;
import io.hermes.missioncontrol.agents.api.WebhookPlatformDto;
import io.hermes.missioncontrol.agents.api.WebhookSubscriptionDto;
import io.hermes.missioncontrol.agents.api.WebhooksDto;
import io.hermes.missioncontrol.agents.api.AuxiliaryModelSpec;
import io.hermes.missioncontrol.agents.api.ContainerActivityDto;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.api.OutboundWebhookDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.MessagingStatusDto;
import io.hermes.missioncontrol.agents.web.ProvidersController.ProviderOptionDto;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.agents.templates.McpServerSpec;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateDto;
import io.hermes.missioncontrol.board.BoardTask;
import io.hermes.missioncontrol.prompts.Prompt;
import io.hermes.missioncontrol.prompts.PromptGroup;
import io.hermes.missioncontrol.skills.Skill;
import io.hermes.missioncontrol.skills.SkillController;
import io.hermes.missioncontrol.skills.SkillFile;
import io.hermes.missioncontrol.skills.SkillGuide;
import io.hermes.missioncontrol.skills.SkillGroup;
import io.hermes.missioncontrol.skills.GuideDeploy;
import io.hermes.missioncontrol.skills.UpstreamCheck;
import io.hermes.missioncontrol.credentials.api.CredentialDto;
import io.hermes.missioncontrol.credentials.api.CredentialEntryDto;
import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.docker.PublishedPortDto;
import io.hermes.missioncontrol.docker.ImageTagDto;
import io.hermes.missioncontrol.docker.ImageTagsDto;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.docker.StatsDto;
import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.web.ServerLogController.ServerInfoDto;
import io.hermes.missioncontrol.mcp.ConfigValueDto;
import io.hermes.missioncontrol.mcp.HealthcheckSpec;
import io.hermes.missioncontrol.mcp.McpGroupDeploy;
import io.hermes.missioncontrol.mcp.McpGroupDto;
import io.hermes.missioncontrol.mcp.McpServerDto;
import io.hermes.missioncontrol.mcp.RetainedResourceDto;
import io.hermes.missioncontrol.mcp.SupportServiceDto;
import io.hermes.missioncontrol.mcp.VolumeSpec;
import io.hermes.missioncontrol.models.ModelCatalogDto;
import io.hermes.missioncontrol.inference.InferenceEndpointDto;
import io.hermes.missioncontrol.inference.EndpointModelDto;
import io.hermes.missioncontrol.inference.PullStatusDto;
import io.hermes.missioncontrol.inference.RunningModelDto;
import io.hermes.missioncontrol.secrets.SecretRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The wire contract between this service and the Angular frontend.
 *
 * <p>Nothing else can see a break in it. The frontend's types are hand-written TypeScript,
 * not generated from anything here, so renaming a record component compiles, passes every
 * test on both sides, and renders {@code undefined} in the browser. That is the one class of
 * mistake a large backend refactor makes easy and a green suite hides.
 *
 * <p>This test reads the frontend's own interface declarations and compares each one against
 * the record it is written for. Jackson serializes a record by component name — this codebase
 * carries no {@code @Json*} annotations and no null-inclusion override — so a record's
 * components are exactly the JSON keys a response carries.
 *
 * <p>{@link #CONTRACT} pins the interface that actually reads a response. Where the frontend
 * maps a payload onto a domain model of its own, that is the {@code Api*} wire interface and
 * not the model — the model is free to differ, and the mapper between them is where that
 * difference is stated.
 *
 * <p>{@link #CONTRACT} states the contract once. Only one direction can break the UI — the
 * frontend reading a key no response carries — so that fails hard; a response carrying a key
 * the frontend ignores is reported too, but as {@link #UNREAD_PAYLOAD}. When a divergence is
 * genuine, record it in the matching map with its reason rather than loosening the check.
 */
class ApiContractTest {

  /** Frontend interface name → the record whose JSON it reads. */
  private static final Map<String, Class<?>> CONTRACT = new LinkedHashMap<>();

  static {
    // docker / fleet
    CONTRACT.put("ApiContainer", ContainerDto.class);
    CONTRACT.put("ApiPublishedPort", PublishedPortDto.class);
    CONTRACT.put("ApiStats", StatsDto.class);
    CONTRACT.put("ApiLogLine", LogLineDto.class);
    CONTRACT.put("ApiImageTag", ImageTagDto.class);
    CONTRACT.put("ApiImageTags", ImageTagsDto.class);
    CONTRACT.put("ApiDockerHost", DockerHostDto.class);
    // agents
    CONTRACT.put("ApiAgentProfile", AgentProfileDto.class);
    CONTRACT.put("ApiSkillRef", SkillDto.class);
    CONTRACT.put("ApiSkillContent", SkillContentDto.class);
    CONTRACT.put("ApiMcpServer", AgentMcpServerDto.class);
    CONTRACT.put("ApiMcpTestResult", McpTestResult.class);
    CONTRACT.put("ApiIntegration", IntegrationDto.class);
    CONTRACT.put("ApiGateway", GatewayDto.class);
    CONTRACT.put("ApiContainerActivity", ContainerActivityDto.class);
    CONTRACT.put("ApiSession", SessionDto.class);
    CONTRACT.put("ApiAuxiliaryModel", AuxiliaryModelSpec.class);
    // agent setup
    CONTRACT.put("ApiAgentSetup", AgentSetupDto.class);
    CONTRACT.put("ApiSetupApiKey", ApiKeyStatusDto.class);
    CONTRACT.put("ApiSetupAuthProvider", AuthProviderDto.class);
    CONTRACT.put("ApiSetupKeyProvider", ApiKeyProviderDto.class);
    CONTRACT.put("ApiSetupMessaging", MessagingStatusDto.class);
    CONTRACT.put("ApiModelProvider", ProviderOptionDto.class);
    // the dashboard's own process
    CONTRACT.put("ApiServerInfo", ServerInfoDto.class);
    // scheduled jobs
    CONTRACT.put("ApiCronJob", CronJobDto.class);
    CONTRACT.put("ApiCronJobs", CronJobsDto.class);
    // webhooks
    CONTRACT.put("ApiWebhookSubscription", WebhookSubscriptionDto.class);
    CONTRACT.put("ApiWebhookPlatform", WebhookPlatformDto.class);
    CONTRACT.put("ApiWebhooks", WebhooksDto.class);
    CONTRACT.put("ApiOutboundWebhook", OutboundWebhookDto.class);
    // templates
    CONTRACT.put("ApiProfileTemplate", ProfileTemplateDto.class);
    CONTRACT.put("ApiTemplateSecret", SecretRef.class);
    CONTRACT.put("TemplateMcp", McpServerSpec.class);
    // credentials
    CONTRACT.put("ApiCredential", CredentialDto.class);
    CONTRACT.put("ApiCredentialEntry", CredentialEntryDto.class);
    // mcp catalog
    CONTRACT.put("ApiMcpCatalogServer", McpServerDto.class);
    CONTRACT.put("ApiMcpConfigEntry", ConfigValueDto.class);
    CONTRACT.put("ApiMcpSupportService", SupportServiceDto.class);
    CONTRACT.put("ApiMcpNamedVolume", VolumeSpec.class);
    CONTRACT.put("ApiMcpHealthcheck", HealthcheckSpec.class);
    CONTRACT.put("ApiMcpRetainedResource", RetainedResourceDto.class);
    CONTRACT.put("ApiMcpGroup", McpGroupDto.class);
    CONTRACT.put("ApiMcpGroupAgent", McpGroupDto.McpGroupAgentDto.class);
    CONTRACT.put("ApiDeployedMcpGroup", McpGroupDeploy.Deployed.class);
    // models / providers
    CONTRACT.put("ApiModelCatalog", ModelCatalogDto.class);
    CONTRACT.put("ApiPullState", PullStatusDto.class);
    CONTRACT.put("ApiInferenceEndpoint", InferenceEndpointDto.class);
    CONTRACT.put("ApiEndpointModel", EndpointModelDto.class);
    CONTRACT.put("ApiRunningModel", RunningModelDto.class);
    // board
    CONTRACT.put("ApiBoardTask", BoardTask.class);
    // prompt library
    CONTRACT.put("ApiPrompt", Prompt.class);
    CONTRACT.put("ApiPromptGroup", PromptGroup.class);
    // skill library
    CONTRACT.put("ApiSkill", Skill.class);
    CONTRACT.put("ApiSkillFile", SkillFile.class);
    CONTRACT.put("ApiImportedSkill", SkillController.ImportedSkill.class);
    CONTRACT.put("ApiUpstream", UpstreamCheck.Upstream.class);
    CONTRACT.put("ApiSkillGroup", SkillGroup.class);
    // guides
    CONTRACT.put("ApiSkillGuide", SkillGuide.class);
    CONTRACT.put("ApiDeployedPart", DeployedPart.class);
    CONTRACT.put("ApiDeployedGuide", GuideDeploy.Deployed.class);
  }

  /**
   * Fields a frontend interface declares that a response never carries, because the frontend
   * reuses one interface per concept for both directions — these are request-only.
   *
   * <p>Harmless: the frontend sets them when posting and never reads them back. Listed so a
   * genuinely missing response field cannot hide among them.
   */
  private static final Map<String, Set<String>> INPUT_ONLY = Map.of();

  /**
   * Fields a response carries that no frontend interface declares.
   *
   * <p>These cannot break the UI — an unread key is ignored — but each one is backend work
   * whose result nothing renders, so they are listed rather than tolerated silently. A new
   * entry appearing here fails this test, which is the point: adding a field without wiring
   * it up should be a decision, not a drift.
   */
  private static final Map<String, Set<String>> UNREAD_PAYLOAD = Map.of(
      // the registry lookup resolves these; the tag picker shows only tag/pulled/remote
      // digest is read now — it is the only evidence a floating tag has moved
      "ApiImageTag", Set.of("lastUpdated", "sizeBytes"),
      "ApiImageTags", Set.of("registryCheckedAt"),
      // sourceServerId is request-only (the frontend extends TemplateMcp locally to send it,
      // and strips it again); environment/headers are the redacted key lists of a captured
      // snapshot, which the template editor does not surface yet
      "TemplateMcp", Set.of("sourceServerId", "environment", "headers"));

  /** The frontend files that declare the wire types. */
  private static final List<String> SOURCES =
      List.of("api/api-types.ts", "api/server-api.ts", "hermes-api.ts", "models.ts");

  @Test
  void everyResponseTypeCarriesTheKeysTheFrontendReads() {
    Path core = frontendCoreDir();
    assumeTrue(core != null,
        "mission-control-fe is not in this checkout — nothing to compare the contract against");

    Map<String, Set<String>> frontend = parseInterfaces(core);
    List<String> report = new ArrayList<>();

    for (Map.Entry<String, Class<?>> entry : CONTRACT.entrySet()) {
      String typeName = entry.getKey();
      Set<String> declared = frontend.get(typeName);
      if (declared == null) {
        report.add(typeName + ": the frontend no longer declares this interface — "
            + "either it was renamed, or " + entry.getValue().getSimpleName() + " is now unused");
        continue;
      }
      Set<String> served = jsonKeys(entry.getValue());
      String record = entry.getValue().getSimpleName();

      Set<String> missing = new TreeSet<>(declared);
      missing.removeAll(served);
      missing.removeAll(INPUT_ONLY.getOrDefault(typeName, Set.of()));
      if (!missing.isEmpty()) {
        report.add(typeName + " reads " + missing + " but " + record + " does not serve them"
            + " — the frontend sees undefined. If they are request-only, list them in INPUT_ONLY.");
      }

      Set<String> unread = new TreeSet<>(served);
      unread.removeAll(declared);
      unread.removeAll(UNREAD_PAYLOAD.getOrDefault(typeName, Set.of()));
      if (!unread.isEmpty()) {
        report.add(record + " serves " + unread + " which " + typeName + " does not declare"
            + " — wire it up in the frontend, or record it in UNREAD_PAYLOAD with the reason.");
      }

      Set<String> stale = new TreeSet<>(UNREAD_PAYLOAD.getOrDefault(typeName, Set.of()));
      stale.retainAll(declared);
      if (!stale.isEmpty()) {
        report.add(typeName + " now reads " + stale
            + " — drop them from UNREAD_PAYLOAD so the contract stays honest.");
      }
    }

    assertTrue(report.isEmpty(), () -> "wire contract drifted:\n  " + String.join("\n  ", report));
  }

  @Test
  void theContractCoversEveryInterfaceTheFrontendUsesAsAResponseType() {
    Path core = frontendCoreDir();
    assumeTrue(core != null, "mission-control-fe is not in this checkout");

    String api = clientSources(core);
    Map<String, String> aliases = parseAliases(api);
    Set<String> responseTypes = new TreeSet<>();
    Matcher m = Pattern.compile("Promise<([A-Za-z_]\\w*)(?:\\[\\])?(?:\\s*\\|\\s*undefined)?>")
        .matcher(api);
    while (m.find()) {
      String type = aliases.getOrDefault(m.group(1), m.group(1));
      responseTypes.add(type);
    }
    // not payload shapes: a bare value, nothing, or the chat history, which hermes builds
    // inside the container and this service only relays — there is no record here to pin
    responseTypes.removeAll(
        Set.of("T", "void", "boolean", "string", "number", "ApiChatMessage"));

    Set<String> uncovered = new TreeSet<>(responseTypes);
    uncovered.removeAll(CONTRACT.keySet());

    assertTrue(uncovered.isEmpty(), () -> "these response types are pinned by nothing: " + uncovered
        + " — add them to CONTRACT so a rename cannot pass unnoticed");
  }

  /**
   * Every {@code Api*} interface has to sit in a file {@link #SOURCES} names, or the contract
   * check above silently passes over it. Splitting the client across files is fine; moving a
   * wire type out of sight is what this refuses.
   */
  @Test
  void everyWireInterfaceSitsInAFileTheContractReads() {
    Path core = frontendCoreDir();
    assumeTrue(core != null, "mission-control-fe is not in this checkout");

    Set<String> scanned = parseInterfaces(core).keySet();
    Set<String> unscanned = new TreeSet<>();
    for (Path file : frontendSources(core)) {
      Matcher m = Pattern.compile("export interface (Api\\w+)").matcher(stripComments(read(file)));
      while (m.find()) {
        if (!scanned.contains(m.group(1))) unscanned.add(m.group(1) + " (" + core.relativize(file) + ")");
      }
    }

    assertTrue(unscanned.isEmpty(), () -> "wire interfaces the contract cannot see: " + unscanned
        + " — add the file to SOURCES");
  }

  // ── frontend parsing ────────────────────────────────────────────────────────

  /** Interface name → declared field names, across every frontend source. */
  private static Map<String, Set<String>> parseInterfaces(Path core) {
    Map<String, Set<String>> out = new LinkedHashMap<>();
    for (String source : SOURCES) {
      String text = stripComments(read(core.resolve(source)));
      Matcher m = Pattern.compile("export interface (\\w+)\\s*\\{([^}]*)}").matcher(text);
      while (m.find()) {
        Set<String> fields = new TreeSet<>();
        Matcher field = Pattern.compile("(?m)^\\s*(\\w+)\\??\\s*:").matcher(m.group(2));
        while (field.find()) fields.add(field.group(1));
        out.put(m.group(1), fields);
      }
    }
    return out;
  }

  /** Every TypeScript source under core, excluding specs. */
  private static List<Path> frontendSources(Path core) {
    try (Stream<Path> files = Files.walk(core)) {
      return files
          .filter(path -> path.toString().endsWith(".ts"))
          .filter(path -> !path.toString().endsWith(".spec.ts"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("could not walk " + core, e);
    }
  }

  /** The API client, however many files it is split across, as one text to scan. */
  private static String clientSources(Path core) {
    StringBuilder out = new StringBuilder(read(core.resolve("hermes-api.ts")));
    Path clients = core.resolve("api");
    if (Files.isDirectory(clients)) {
      for (Path file : frontendSources(clients)) out.append('\n').append(read(file));
    }
    return out.toString();
  }

  /** {@code export type ApiSkillContent = SkillContent;} → alias to target. */
  private static Map<String, String> parseAliases(String text) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher m = Pattern.compile("export type (\\w+)\\s*=\\s*(\\w+);").matcher(text);
    while (m.find()) out.put(m.group(1), m.group(2));
    return out;
  }

  private static String stripComments(String text) {
    return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
  }

  // ── backend introspection ───────────────────────────────────────────────────

  /**
   * The JSON keys a response of this type carries. Records serialize by component name, and
   * this codebase applies no Jackson renaming or null exclusion, so the components are the keys.
   */
  private static Set<String> jsonKeys(Class<?> type) {
    if (!type.isRecord()) {
      throw new IllegalStateException(type.getSimpleName() + " is not a record; this test "
          + "assumes component-name serialization and cannot introspect it");
    }
    return Arrays.stream(type.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(TreeSet::new, Set::add, Set::addAll);
  }

  // ── locating the frontend ───────────────────────────────────────────────────

  /** Walks up from the working directory, so this passes from the module or the repo root. */
  private static Path frontendCoreDir() {
    Path dir = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
      Path core = dir.resolve("applications/mission-control-fe/src/app/core");
      if (Files.isDirectory(core)) return core;
      core = dir.resolve("mission-control-fe/src/app/core");
      if (Files.isDirectory(core)) return core;
    }
    return null;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }
  }
}
