package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.ProfileSpec.blankToNull;

import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyProviderDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.agents.api.MessagingStatusDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.secrets.Secrets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent setup status: merges the {@code hermes status} report with the profile {@code .env}.
 *
 * <p>Two readings of one screen. The {@code .env} is authoritative for whether a credential is
 * set and what its masked tail is; {@code hermes status} fills in the providers configured
 * outside it — an OAuth login, a key held elsewhere in the image — and is degraded to nothing
 * when the command cannot run.
 *
 * <p>A third reading, {@code hermes auth list}, says whether a key still <em>works</em>. Hermes
 * pools every credential it has seen and remembers the last failure against each — a 401 on a
 * revoked key, a 402 on an exhausted account — and that pool outlives the {@code .env} line it
 * came from, so a profile can read as fully configured while every turn dies on a dead key.
 * Neither of the other two sources knows this; the pool is the only place it is written.
 *
 * <p>What a {@code .env} may contain is {@link HermesEnvCatalog}'s, and how one is read and
 * written is {@link HermesEnvFile}'s. This class held all three, which is why the template it
 * generated had to be reached from {@code HermesEnvFile} by a static call back into it while
 * taking that same class as a collaborator.
 */
@Service
public class HermesSetup {

  private static final Logger log = LoggerFactory.getLogger(HermesSetup.class);

  private static final String SECTION_API_KEYS = "API Keys";
  private static final String SECTION_AUTH_PROVIDERS = "Auth Providers";
  private static final String SECTION_API_KEY_PROVIDERS = "API-Key Providers";
  private static final String SECTION_MESSAGING = "Messaging Platforms";

  private static final char CHECK = '✓';
  private static final char CROSS = '✗';
  private static final String SECTION_MARK = "◆";

  private static final Pattern RUN_HINT = Pattern.compile("run:\\s*([^)]+)");
  private static final Pattern AUTH_LIST_HEADER = Pattern.compile("(\\S+) \\(\\d+ credentials?\\):");
  private static final Pattern AUTH_LIST_ROW =
      Pattern.compile("#\\d+\\s+\\S+\\s+\\S+\\s+id=\\S+\\s+priority=\\d+\\s+\\S+(.*)");
  private static final String ACTIVE_MARK = "←";
  private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");

  private final HermesContainerFiles files;
  private final HermesEnvFile envFile;

  public HermesSetup(HermesContainerFiles files, HermesEnvFile envFile) {
    this.files = files;
    this.envFile = envFile;
  }

  public AgentSetupDto setup(DockerHostRef host, String containerId, String name) {
    String envPath = ProfilePaths.profileDir(name) + "/.env";
    boolean envExists = files.fileExists(host, containerId, envPath);
    Map<String, String> env = HermesEnvFile.parse(files.readFile(host, containerId, envPath));
    StatusReport report = runStatus(host, containerId, name);
    Map<String, String> problems = pooledProblems(host, containerId, name);

    List<ApiKeyStatusDto> apiKeys = new ArrayList<>();
    for (HermesEnvCatalog.ApiKeySpec spec : HermesEnvCatalog.API_KEYS) {
      String value = envValue(env, spec);
      String problem = problems.get(spec.envVar());
      if (value != null) {
        apiKeys.add(new ApiKeyStatusDto(spec.label(), spec.envVar(), true, mask(value), problem));
      } else {
        StatusRow row = report == null ? null : report.row(SECTION_API_KEYS, spec.label());
        apiKeys.add(new ApiKeyStatusDto(
            spec.label(), spec.envVar(), row != null && row.ok(), null, problem));
      }
    }

    List<AuthProviderDto> authProviders = new ArrayList<>();
    List<ApiKeyProviderDto> apiKeyProviders = new ArrayList<>();
    if (report != null) {
      for (StatusRow row : report.rows(SECTION_AUTH_PROVIDERS)) {
        authProviders.add(new AuthProviderDto(row.label(), row.ok(), row.status(),
            hint(row.status()), HermesEnvCatalog.AUTH_PROVIDER_KEYS.get(row.label())));
      }
      for (StatusRow row : report.rows(SECTION_API_KEY_PROVIDERS)) {
        apiKeyProviders.add(new ApiKeyProviderDto(row.label(), row.ok(), row.status()));
      }
    }

    List<MessagingStatusDto> messaging = new ArrayList<>();
    for (HermesEnvCatalog.MessagingSpec spec : HermesEnvCatalog.MESSAGING) {
      StatusRow row = report == null ? null : report.row(SECTION_MESSAGING, spec.label());
      boolean tokenSet = isSet(env.get(spec.tokenVar()));
      boolean ok = row != null ? row.ok() : tokenSet;
      String status = row != null ? row.status() : (tokenSet ? "configured" : "not configured");
      String homeChannel = spec.homeVar() == null ? null : blankToNull(env.get(spec.homeVar()));
      messaging.add(new MessagingStatusDto(spec.label(), ok, status, spec.tokenVar(), spec.homeVar(), homeChannel));
    }

    return new AgentSetupDto(envPath, envExists, apiKeys, authProviders, apiKeyProviders, messaging);
  }

  /**
   * Applies a set of variables, blank meaning "remove". Both halves of every entry are checked
   * up front by {@link HermesEnvFile#assertWritable} so a partly-applied batch cannot be the
   * first thing an invalid key or value is discovered by.
   */
  public AgentSetupDto putEnv(DockerHostRef host, String containerId, String name, List<EnvEntry> entries) {
    List<EnvEntry> toApply = entries == null ? List.of() : entries;
    for (EnvEntry entry : toApply) {
      if (entry == null) throw new IllegalArgumentException("invalid env key: null");
      HermesEnvFile.assertWritable(entry.key(), entry.value());
    }
    for (EnvEntry entry : toApply) {
      if (entry.value() == null || entry.value().isBlank()) {
        envFile.remove(host, containerId, name, entry.key());
      } else {
        envFile.write(host, containerId, name, entry.key(), entry.value());
      }
    }
    return setup(host, containerId, name);
  }

  public AgentSetupDto initEnv(DockerHostRef host, String containerId, String name) {
    envFile.seedIfMissing(host, containerId, name);
    return setup(host, containerId, name);
  }

  /** Degrades to null when `hermes status` cannot run — callers then report
   *  from the .env alone. */
  private StatusReport runStatus(DockerHostRef host, String containerId, String name) {
    List<String> command = ProfilePaths.hermesCli(name, "status");
    try {
      return parseStatus(files.exec(host, containerId, command).stdout());
    } catch (RuntimeException e) {
      // degrading to the .env alone makes every externally-configured provider look
      // unconfigured, which is indistinguishable from "not set up" without this line
      log.warn("`hermes status` failed for profile {} in {} — reporting from .env only: {}",
          name, containerId, e.toString());
      return null;
    }
  }

  /**
   * What is wrong with each pooled credential, keyed by the {@code .env} variable its provider
   * reads. Empty when {@code hermes auth list} cannot run: a key nothing is known about is
   * reported as it always was, not as broken.
   */
  private Map<String, String> pooledProblems(DockerHostRef host, String containerId, String name) {
    List<String> command = ProfilePaths.hermesCli(name, "auth", "list");
    try {
      return parseAuthList(files.exec(host, containerId, command).stdout());
    } catch (RuntimeException e) {
      log.warn("`hermes auth list` failed for profile {} in {} — pooled credential status "
          + "unknown: {}", name, containerId, e.toString());
      return Map.of();
    }
  }

  /**
   * {@code hermes auth list} prints one block per provider — {@code <key> (N credentials):} —
   * with a row per credential: {@code #1 <label> <type> id=… priority=… <source> [<status>] [←]}.
   * A healthy row ends at its source; whatever follows is hermes describing a failure, and the
   * first such row wins for its provider. Providers the registry has no variable for — an OAuth
   * login, a key this dashboard does not manage — are skipped: there is no key row to flag.
   */
  static Map<String, String> parseAuthList(String output) {
    Map<String, String> problems = new LinkedHashMap<>();
    String envVar = null;
    for (String line : ANSI.matcher(output == null ? "" : output).replaceAll("").split("\\R")) {
      String trimmed = line.trim();
      Matcher header = AUTH_LIST_HEADER.matcher(trimmed);
      if (header.matches()) {
        envVar = ModelProviderRegistry.envVar(header.group(1));
        continue;
      }
      Matcher row = AUTH_LIST_ROW.matcher(trimmed);
      if (envVar == null || !row.matches()) continue;
      String status = row.group(1).replace(ACTIVE_MARK, "").trim();
      if (!status.isEmpty()) problems.putIfAbsent(envVar, status);
    }
    return problems;
  }

  /** Sections headed by "◆ <name>"; rows are 2-space indented
   *  "<label>  <✓|✗> <status>"; deeper-indented detail lines are skipped. */
  private StatusReport parseStatus(String output) {
    Map<String, List<StatusRow>> sections = new LinkedHashMap<>();
    String section = null;
    for (String line : ANSI.matcher(output == null ? "" : output).replaceAll("").split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (trimmed.startsWith(SECTION_MARK)) {
        section = trimmed.substring(1).trim();
        sections.putIfAbsent(section, new ArrayList<>());
        continue;
      }
      if (section == null || indentOf(line) > 2) continue;
      int mark = markIndex(trimmed);
      if (mark < 0) continue;
      String label = trimmed.substring(0, mark).trim();
      String status = trimmed.substring(mark + 1).trim();
      sections.get(section).add(new StatusRow(label, trimmed.charAt(mark) == CHECK, status));
    }
    return new StatusReport(sections);
  }

  private String envValue(Map<String, String> env, HermesEnvCatalog.ApiKeySpec spec) {
    String value = env.get(spec.envVar());
    if (isSet(value)) return value;
    for (String alt : spec.altVars()) {
      value = env.get(alt);
      if (isSet(value)) return value;
    }
    return null;
  }

  private String mask(String value) {
    return Secrets.mask(value);
  }

  private String hint(String status) {
    Matcher matcher = RUN_HINT.matcher(status);
    return matcher.find() ? matcher.group(1).trim() : null;
  }

  private boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private int indentOf(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') i++;
    return i;
  }

  private int markIndex(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == CHECK || c == CROSS) return i;
    }
    return -1;
  }

  record StatusRow(String label, boolean ok, String status) {}

  private record StatusReport(Map<String, List<StatusRow>> sections) {
    List<StatusRow> rows(String section) {
      return sections.getOrDefault(section, List.of());
    }

    StatusRow row(String section, String label) {
      for (StatusRow row : rows(section)) {
        if (row.label().equals(label)) return row;
      }
      return null;
    }
  }
}
