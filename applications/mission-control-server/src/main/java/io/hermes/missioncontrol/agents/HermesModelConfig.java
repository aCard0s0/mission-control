package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AuxiliaryModelSpec;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Which backend a profile talks to: the {@code model.*} and {@code auxiliary.*} halves of
 * {@code config.yaml}, the API keys they need, and the read-back that turns them into a DTO.
 *
 * <p>Split out of {@link HermesProfiles} because the write plan and the parser are two
 * halves of one round trip — the OpenRouter-namespace and clone-clearing invariants only
 * hold if they stay together, and both were live-only bugs once.
 */
@Component
class HermesModelConfig {

  /** The auxiliary side-task slots hermes seeds into {@code config.yaml}. Each one
   *  ships as {@code provider: auto}, and hermes' auto chain resolves that to the
   *  profile's own {@code model.provider} / {@code model.default} before it tries
   *  OpenRouter, Nous or a custom endpoint — so on a profile whose model map is
   *  empty, every task falls through to "no provider available" and compression,
   *  summarization and memory flush stop working. Pinning them to the same
   *  provider/model the profile already uses keeps them resolvable on their own.
   *
   *  <p>{@code vision} is deliberately excluded: its chain skips a main model that
   *  is known to be text-only and falls back to OpenRouter/Nous, so pinning would
   *  aim image payloads at a model that may reject them. */
  static final List<String> AUXILIARY_TASKS = List.of(
      "approval", "compression", "curator", "kanban_decomposer", "mcp", "monitor",
      "profile_describer", "skills_hub", "title_generation", "triage_specifier",
      "tts_audio_tags", "web_extract");

  /** A provider/model/endpoint triple — what {@code model.*} and each
   *  {@code auxiliary.<task>.*} block each resolve to. */
  record ModelTarget(String provider, String model, String baseUrl) {}

  record ConfigInfo(String provider, String model, String cwd) {}

  record ModelInfo(String provider, String model) {}

  private final HermesContainerFiles files;
  private final HermesCli cli;
  private final HermesEnvFile env;

  HermesModelConfig(HermesContainerFiles files, HermesCli cli, HermesEnvFile env) {
    this.files = files;
    this.cli = cli;
    this.env = env;
  }

  // ── writing ────────────────────────────────────────────────────────────────

  /** Sets the profile's model through hermes' own config writer
   *  ({@code hermes -p <name> config set model.<key> <value>}), which produces
   *  the documented {@code model: { provider, default, base_url }} mapping and
   *  keeps hermes' validation/migration in the loop. Only the model.* keys are
   *  touched — sibling config keys are preserved.
   *
   *  <p>The model id is passed verbatim to {@code model.default} — never
   *  concatenated as {@code provider/model} — so OpenRouter ids that already
   *  contain a slash (e.g. {@code anthropic/claude-sonnet-4}) are stored intact.
   *  Custom/local endpoints (ollama, vLLM, …) set {@code model.base_url} and own
   *  their routing, so they carry no provider; standard providers (nous,
   *  openrouter, anthropic, openai-api, …) set {@code model.provider}.
   *
   *  <p>The map is first wiped to an empty scalar ({@code model: ""}) so a
   *  {@code --clone}'d profile cannot leak ANY stale key — provider, base_url, or
   *  even a hand-set api_mode — from the source profile; the dotted sets then
   *  rebuild model from scratch with only the keys the chosen provider needs.
   *  (`hermes config set` mutates one key and preserves the rest of the map, so a
   *  full reset is the only way to guarantee no leak.)
   *
   *  <p>Hermes v0.21.0 (2026.8.31) took the wipe away: a bare {@code model} set is
   *  redirected to {@code model.default} and every sibling key is kept, so a fresh
   *  profile — which hermes now seeds with {@code base_url: https://openrouter.ai/…}
   *  — came out as {@code provider: openai} plus that endpoint. The routing key the
   *  plan does not set is therefore removed explicitly, as a null-valued entry the
   *  loop below turns into {@code config unset}. */
  void write(DockerHostRef host, String containerId, String name,
      String provider, String model, String baseUrl, ModelTarget auxiliary) {
    // taken under the profile's lock as one unit: the wipe-then-rebuild above is only safe
    // while nothing else is reading config.yaml to write it back, and one of these sets
    // deliberately leaves the file with `model: ""` for the next set to build back up
    files.serialized(containerId, name, () -> {
      for (String[] kv : modelConfigEntries(provider, model, baseUrl)) {
        if (kv[1] == null) {
          cli.unsetConfig(host, containerId, name, kv[0]);
        } else {
          setConfig(host, containerId, name, kv[0], kv[1]);
        }
      }
      for (String[] kv : auxiliaryConfigEntries(
          auxiliary.provider(), auxiliary.model(), auxiliary.baseUrl())) {
        setConfig(host, containerId, name, kv[0], kv[1]);
      }
    });
  }

  /** {@code terminal.cwd}, through hermes' own writer like the model keys. Here rather than
   *  beside SOUL.md because this class already reads it back ({@link ConfigInfo#cwd}), and a
   *  key read in one place and written in another is how the two drift. */
  void writeWorkingDir(DockerHostRef host, String containerId, String name, String cwd) {
    if (cwd == null || cwd.isBlank()) throw new IllegalArgumentException("missing working dir");
    files.serialized(containerId, name, () -> setConfig(host, containerId, name, "terminal.cwd", cwd.trim()));
  }

  /** Writes the profile's own API key, when the chosen provider takes one and the
   *  request carried a non-blank value. */
  void writeApiKey(DockerHostRef host, String containerId, String name, String provider, String apiKey) {
    String envKey = apiKeyVar(provider);
    if (envKey == null || apiKey == null || apiKey.isBlank()) return;
    env.write(host, containerId, name, envKey, apiKey);
  }

  /** Writes the override provider's API key into the profile's .env. Only runs when
   *  the override actually introduces a provider — a same-provider model swap is
   *  already covered by the main key, and re-writing it would let a blank field in
   *  the create form clobber a working one. */
  void writeAuxiliaryApiKey(
      DockerHostRef host, String containerId, String name, ModelTarget auxiliary, AuxiliaryModelSpec spec) {
    if (spec == null || isBlank(spec.apiKey()) || isBlank(spec.provider())) return;
    String envKey = apiKeyVar(auxiliary.provider());
    if (envKey == null) return;
    env.write(host, containerId, name, envKey, spec.apiKey());
  }

  /** Fails the create when the profile ended up without a usable model.
   *
   *  <p>{@code hermes profile create} never seeds {@code config.yaml} — it bootstraps
   *  dirs, {@code .env} and {@code SOUL.md}, and only copies a config under
   *  {@code --clone}. The file exists at all because the {@code config set} calls above
   *  bring it into existence, so a write that silently no-ops leaves a profile whose
   *  auxiliary chain has nothing to resolve to and whose gateway logs
   *  "no provider available ... compression, summarization, and memory flush will not
   *  work". Throwing here lets the caller's rollback drop the half-built profile
   *  instead of handing back an agent that degrades on first long session. */
  void assertConfigured(DockerHostRef host, String containerId, String name) {
    String configPath = ProfilePaths.configFile(name);
    ConfigInfo info = parseConfig(YamlValues.parseMap(files.readFile(host, containerId, configPath)));
    if (info.model().isBlank()) {
      throw new IllegalStateException(
          "profile '" + name + "' has no model in " + configPath
              + " — auxiliary tasks (compression, summarization, memory flush)"
              + " would have no provider to resolve to");
    }
  }

  private void setConfig(DockerHostRef host, String containerId, String name, String key, String value) {
    cli.setConfig(host, containerId, name, key, value);
  }

  // ── pure write planners ────────────────────────────────────────────────────

  /** Pure planner for {@link #write}: the ordered {@code (key, value)}
   *  config sets. The first entry wipes {@code model} to an empty scalar (kills
   *  any inherited map); {@code model.default} then promotes it back to a map;
   *  finally the one applicable routing key is set — {@code model.provider} for a
   *  standard provider, {@code model.base_url} for a custom/local endpoint, and
   *  neither when the provider is blank/auto. The routing key that is <em>not</em>
   *  set follows as a null-valued entry, meaning "unset it": on a hermes whose bare
   *  {@code model} set no longer wipes, that is what keeps a seeded or cloned
   *  {@code base_url} from riding along under a standard provider. Kept pure (no
   *  I/O) so the clone-reset contract is unit-testable. */
  static List<String[]> modelConfigEntries(String provider, String model, String baseUrl) {
    List<String[]> entries = new ArrayList<>();
    entries.add(new String[] {"model", ""});                                   // wipe any clone leftovers
    entries.add(new String[] {"model.default", model == null ? "" : model});   // promote back to a map
    boolean custom = baseUrl != null && !baseUrl.isBlank();
    if (custom) {
      entries.add(new String[] {"model.base_url", baseUrl});   // custom endpoint owns routing
      entries.add(new String[] {"model.provider", null});
    } else {
      String normalizedProvider = normalizeProvider(provider);
      if (!normalizedProvider.isBlank() && !"auto".equals(normalizedProvider)) {
        entries.add(new String[] {"model.provider", normalizedProvider});
      } else {
        entries.add(new String[] {"model.provider", null});    // auto: hermes decides, nothing pins it
      }
      entries.add(new String[] {"model.base_url", null});
    }
    return entries;
  }

  /** Pure planner for the {@code auxiliary.*} half of {@link #write}: the
   *  {@code (key, value)} sets that pin every task in {@link #AUXILIARY_TASKS} to the
   *  same backend the profile's main model uses. Written from the same seam as
   *  {@code model.*} so the two can never drift — a model change rewrites both.
   *
   *  <p>Returns an empty plan when there is nothing concrete to pin to (blank model,
   *  or a blank/auto provider with no custom endpoint); leaving those tasks on
   *  {@code auto} is strictly better than pinning them at a provider that does not
   *  resolve. A custom endpoint pins {@code provider: custom} plus its
   *  {@code base_url}, matching how hermes rewrites aliased providers to custom when
   *  a task carries an endpoint of its own. */
  static List<String[]> auxiliaryConfigEntries(String provider, String model, String baseUrl) {
    List<String[]> entries = new ArrayList<>();
    String pinnedModel = model == null ? "" : model.trim();
    if (pinnedModel.isBlank()) return entries;
    boolean custom = baseUrl != null && !baseUrl.isBlank();
    String pinnedProvider = custom ? "custom" : normalizeProvider(provider);
    if (!custom && (pinnedProvider.isBlank() || "auto".equals(pinnedProvider))) return entries;
    for (String task : AUXILIARY_TASKS) {
      String prefix = "auxiliary." + task + ".";
      entries.add(new String[] {prefix + "provider", pinnedProvider});
      entries.add(new String[] {prefix + "model", pinnedModel});
      if (custom) entries.add(new String[] {prefix + "base_url", baseUrl});
    }
    return entries;
  }

  /** Resolves which backend the auxiliary tasks get pinned to.
   *
   *  <p>The default is the profile's own main model: side tasks that silently ran
   *  on a different model than the conversation would be a surprise, and hermes'
   *  own {@code auto} means exactly this. An override is honoured only when it
   *  names a model — a spec carrying just a provider has nothing to pin to.
   *
   *  <p>The endpoint follows the provider: an override that names its own provider
   *  brings its own {@code base_url} (or none), while one that only swaps the model
   *  inherits the main endpoint, so "same local ollama, smaller model" needs no
   *  repeated URL. */
  static ModelTarget auxiliaryTarget(
      String provider, String model, String baseUrl, AuxiliaryModelSpec spec) {
    if (spec == null || isBlank(spec.model())) {
      return new ModelTarget(provider, model, baseUrl);
    }
    boolean ownProvider = !isBlank(spec.provider());
    String auxProvider = ownProvider ? spec.provider() : provider;
    String auxBaseUrl = !isBlank(spec.baseUrl()) ? spec.baseUrl() : (ownProvider ? null : baseUrl);
    return new ModelTarget(auxProvider, spec.model().trim(), auxBaseUrl);
  }

  static String normalizeProvider(String provider) {
    return ModelProviderRegistry.normalizeKey(provider);
  }

  /** The provider's API-key env var (or null for OAuth/keyless/unknown), from the
   *  shared {@link ModelProviderRegistry} so the key written into .env always
   *  matches the providers the UI offers. */
  static String apiKeyVar(String provider) {
    return ModelProviderRegistry.envVar(normalizeProvider(provider));
  }

  // ── reading back ───────────────────────────────────────────────────────────

  ConfigInfo parseConfig(Map<?, ?> map) {
    if (map == null || map.isEmpty()) return new ConfigInfo("auto", "", "");
    String provider = "auto";
    String model = "";
    Object modelNode = map.get("model");
    if (modelNode instanceof String modelString) {
      ModelInfo info = parseModelString(modelString);
      provider = info.provider();
      model = info.model();
    } else if (modelNode instanceof Map<?, ?> modelMap) {
      String providerValue = YamlValues.stringValue(modelMap.get("provider"));
      String defaultValue = YamlValues.stringValue(modelMap.get("default"));
      if (defaultValue.isBlank()) {
        defaultValue = YamlValues.stringValue(modelMap.get("model"));
      }
      if (!providerValue.isBlank()) {
        // a structured map already separates provider from the model id, so the
        // id is taken verbatim — splitting it would drop the namespace of an
        // OpenRouter id (anthropic/claude-sonnet-4 -> claude-sonnet-4), which the
        // provider can no longer resolve and which breaks template re-capture.
        provider = providerValue;
        model = defaultValue;
      } else {
        // no explicit provider (custom/ollama, or legacy): fall back to the
        // scalar "provider/model" convention.
        ModelInfo info = parseModelString(defaultValue);
        provider = info.provider();
        model = info.model().isBlank() ? defaultValue : info.model();
      }
    }
    String cwd = "";
    Object terminal = map.get("terminal");
    if (terminal instanceof Map<?, ?> terminalMap) {
      cwd = YamlValues.stringValue(terminalMap.get("cwd"));
    }
    return new ConfigInfo(provider, model, cwd);
  }

  ModelInfo parseModelString(String value) {
    if (value == null || value.isBlank()) {
      return new ModelInfo("auto", "");
    }
    String trimmed = value.trim();
    int idx = trimmed.indexOf('/');
    if (idx > 0) {
      return new ModelInfo(trimmed.substring(0, idx), trimmed.substring(idx + 1));
    }
    return new ModelInfo("auto", trimmed);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
