package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.HermesModelConfig.ConfigInfo;
import io.hermes.missioncontrol.agents.HermesModelConfig.ModelTarget;
import io.hermes.missioncontrol.agents.api.AuxiliaryModelSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the model-config write planner and the read-back parser —
 * the two seams that carry the OpenRouter-namespace and clone-clearing
 * invariants (both were live-only bugs once, so they get unit coverage here).
 *
 * <p>No container is involved: the planners are static and the parser only reads a map,
 * which is why they were split out of the profile facade in the first place.
 */
class HermesModelConfigTest {

  /** The parser needs no collaborators, so nulls are safe for the pure read path. */
  private final HermesModelConfig modelConfig = new HermesModelConfig(null, null, null);

  /** The keys a plan sets. A null value is an unset, and is read through {@link #unsets}. */
  private static Map<String, String> entries(String provider, String model, String baseUrl) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String[] kv : HermesModelConfig.modelConfigEntries(provider, model, baseUrl)) {
      if (kv[1] != null) out.put(kv[0], kv[1]);
    }
    return out;
  }

  /** The keys a plan removes, in order. */
  private static List<String> unsets(String provider, String model, String baseUrl) {
    return HermesModelConfig.modelConfigEntries(provider, model, baseUrl).stream()
        .filter(kv -> kv[1] == null).map(kv -> kv[0]).toList();
  }

  // ── write planner: modelConfigEntries ──────────────────────────

  @Test
  void standardProviderSetsProviderAndNoBaseUrl() {
    Map<String, String> e = entries("nous", "Hermes-4-405B", null);
    assertEquals("Hermes-4-405B", e.get("model.default"));
    assertEquals("nous", e.get("model.provider"));
    assertEquals(false, e.containsKey("model.base_url"), "standard provider writes no base_url");
  }

  @Test
  void theRetiredOpenAiSpellingIsWrittenAsTheKeyHermesResolves() {
    // `provider: openai` passed `hermes status` (it only reads the env var) and failed the
    // runtime resolver with "Unknown provider 'openai'" — the agent then asked to be set up
    Map<String, String> e = entries("openai", "gpt-5.2", null);
    assertEquals("openai-api", e.get("model.provider"));
    assertEquals("openai-api", auxEntries("openai", "gpt-5.2", null).get("auxiliary.compression.provider"));
  }

  @Test
  void openrouterModelIdKeptVerbatimNotConcatenated() {
    Map<String, String> e = entries("openrouter", "anthropic/claude-sonnet-4", null);
    // the slashed id is written as-is to model.default — never provider/model
    assertEquals("anthropic/claude-sonnet-4", e.get("model.default"));
    assertEquals("openrouter", e.get("model.provider"));
  }

  @Test
  void customEndpointSetsBaseUrlAndNoProvider() {
    Map<String, String> e = entries("ollama", "qwen3:8b", "http://host.docker.internal:11434/v1");
    assertEquals("qwen3:8b", e.get("model.default"));
    assertEquals("http://host.docker.internal:11434/v1", e.get("model.base_url"));
    assertEquals(false, e.containsKey("model.provider"), "custom endpoint writes no provider");
  }

  @Test
  void blankOrAutoProviderWritesNeitherRoutingKey() {
    for (String p : new String[] {null, "", "auto"}) {
      Map<String, String> e = entries(p, "some-model", null);
      assertEquals(false, e.containsKey("model.provider"), "provider=" + p);
      assertEquals(false, e.containsKey("model.base_url"), "provider=" + p);
    }
  }

  @Test
  void theRoutingKeyAPlanDoesNotSetIsRemovedSoASeededOrClonedOneCannotRideAlong() {
    // hermes v0.21.0 seeds a fresh profile with base_url: https://openrouter.ai/… and no longer
    // wipes the model map on a bare `model` set, so `provider: openai` came out of a create still
    // pointed at openrouter. The wipe stays for older builds; this is what clears it on newer ones.
    assertEquals(List.of("model.base_url"), unsets("openai", "gpt-5.5", null));
    assertEquals(List.of("model.provider"), unsets("ollama", "qwen3:8b", "http://host.docker.internal:11434/v1"));
    for (String p : new String[] {null, "", "auto"}) {
      assertEquals(List.of("model.provider", "model.base_url"), unsets(p, "m", null), "provider=" + p);
    }
  }

  @Test
  void everyUnsetComesAfterTheSetsSoAWriteNeverRemovesWhatItJustPut() {
    for (String[] c : new String[][] {
        {"nous", "Hermes-4-405B", null},
        {"ollama", "qwen3:8b", "http://x/v1"},
        {"auto", "m", null}}) {
      List<String[]> plan = HermesModelConfig.modelConfigEntries(c[0], c[1], c[2]);
      int firstUnset = plan.size();
      for (int i = 0; i < plan.size(); i++) {
        if (plan.get(i)[1] == null) { firstUnset = i; break; }
      }
      for (int i = firstUnset; i < plan.size(); i++) {
        assertEquals(null, plan.get(i)[1], "a set after an unset for provider=" + c[0]);
      }
    }
  }

  @Test
  void everyWriteWipesModelFirstSoCloneCannotLeak() {
    // the clone guard: the FIRST set always resets model to an empty scalar,
    // so no stale key (provider, base_url, api_mode, …) can survive a --clone
    for (String[] c : new String[][] {
        {"nous", "Hermes-4-405B", null},
        {"openrouter", "anthropic/claude-sonnet-4", null},
        {"ollama", "qwen3:8b", "http://x/v1"},
        {"auto", "m", null}}) {
      List<String[]> plan = HermesModelConfig.modelConfigEntries(c[0], c[1], c[2]);
      assertEquals("model", plan.get(0)[0], "first set must wipe the model map");
      assertEquals("", plan.get(0)[1]);
      assertEquals("model.default", plan.get(1)[0], "default written right after the wipe");
    }
  }

  // ── write planner: auxiliaryConfigEntries ──────────────────────────────────

  private static Map<String, String> auxEntries(String provider, String model, String baseUrl) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String[] kv : HermesModelConfig.auxiliaryConfigEntries(provider, model, baseUrl)) {
      out.put(kv[0], kv[1]);
    }
    return out;
  }

  @Test
  void auxiliaryTasksArePinnedToTheProfilesOwnProviderAndModel() {
    // auto resolves through the main model, so an unpinned task dies with the
    // model map — compression/summarization/memory flush are the visible casualties
    Map<String, String> e = auxEntries("nous", "Hermes-4-405B", null);
    assertEquals("nous", e.get("auxiliary.compression.provider"));
    assertEquals("Hermes-4-405B", e.get("auxiliary.compression.model"));
    assertEquals("nous", e.get("auxiliary.curator.provider"));
    assertEquals("Hermes-4-405B", e.get("auxiliary.curator.model"));
  }

  @Test
  void visionIsLeftOnAutoSoItsFallbackChainStillApplies() {
    Map<String, String> e = auxEntries("openai-codex", "gpt-5.5", null);
    assertFalse(e.containsKey("auxiliary.vision.provider"), "vision must keep its own fallback chain");
    assertFalse(e.containsKey("auxiliary.vision.model"));
  }

  @Test
  void openrouterAuxiliaryModelIdKeptVerbatim() {
    Map<String, String> e = auxEntries("openrouter", "anthropic/claude-sonnet-4", null);
    assertEquals("anthropic/claude-sonnet-4", e.get("auxiliary.compression.model"));
    assertEquals("openrouter", e.get("auxiliary.compression.provider"));
  }

  @Test
  void customEndpointPinsCustomProviderAndBaseUrl() {
    Map<String, String> e = auxEntries("ollama", "qwen3:8b", "http://host.docker.internal:11434/v1");
    assertEquals("custom", e.get("auxiliary.compression.provider"));
    assertEquals("qwen3:8b", e.get("auxiliary.compression.model"));
    assertEquals("http://host.docker.internal:11434/v1", e.get("auxiliary.compression.base_url"));
  }

  @Test
  void nothingConcreteToPinLeavesEveryTaskOnAuto() {
    // pinning at a provider that does not resolve is worse than the auto chain
    assertTrue(auxEntries("nous", "", null).isEmpty(), "blank model");
    assertTrue(auxEntries("nous", null, null).isEmpty(), "null model");
    assertTrue(auxEntries("auto", "some-model", null).isEmpty(), "auto provider, no endpoint");
    assertTrue(auxEntries(null, "some-model", null).isEmpty(), "null provider, no endpoint");
  }

  @Test
  void auxiliaryPinUsesTheSameProviderNormalizationAsTheModelWrite() {
    // "nousresearch" -> "nous" on both halves, or the aux tasks point at a
    // provider id hermes' resolver does not know
    Map<String, String> model = entries("nousresearch", "Hermes-4-405B", null);
    Map<String, String> aux = auxEntries("nousresearch", "Hermes-4-405B", null);
    assertEquals(model.get("model.provider"), aux.get("auxiliary.compression.provider"));
  }

  // ── override resolver: auxiliaryTarget ─────────────────────────────────────

  @Test
  void noOverrideRunsSideTasksOnTheMainModel() {
    for (AuxiliaryModelSpec spec : new AuxiliaryModelSpec[] {
        null,
        new AuxiliaryModelSpec(null, null, null, null),
        new AuxiliaryModelSpec("openrouter", "", null, null)}) {   // provider alone pins nothing
      ModelTarget t =
          HermesModelConfig.auxiliaryTarget("nous", "Hermes-4-405B", null, spec);
      assertEquals("nous", t.provider());
      assertEquals("Hermes-4-405B", t.model());
    }
  }

  @Test
  void overrideSendsSideTasksToItsOwnProviderAndModel() {
    ModelTarget t = HermesModelConfig.auxiliaryTarget(
        "anthropic", "claude-opus-4-8", null,
        new AuxiliaryModelSpec("openrouter", "anthropic/claude-haiku-4-5", null, "sk-or-x"));
    assertEquals("openrouter", t.provider());
    assertEquals("anthropic/claude-haiku-4-5", t.model());
    assertEquals(null, t.baseUrl(), "an override with its own provider carries its own endpoint");
  }

  @Test
  void modelOnlyOverrideKeepsTheMainProviderAndEndpoint() {
    // "same local ollama, smaller model" must not need the URL repeated
    ModelTarget t = HermesModelConfig.auxiliaryTarget(
        "ollama", "qwen3:32b", "http://host.docker.internal:11434/v1",
        new AuxiliaryModelSpec("", "qwen3:8b", null, null));
    assertEquals("ollama", t.provider());
    assertEquals("qwen3:8b", t.model());
    assertEquals("http://host.docker.internal:11434/v1", t.baseUrl());
  }

  @Test
  void overrideFlowsIntoTheAuxiliaryWritePlan() {
    ModelTarget t = HermesModelConfig.auxiliaryTarget(
        "anthropic", "claude-opus-4-8", null,
        new AuxiliaryModelSpec("openrouter", "anthropic/claude-haiku-4-5", null, null));
    Map<String, String> e = auxEntries(t.provider(), t.model(), t.baseUrl());
    assertEquals("openrouter", e.get("auxiliary.compression.provider"));
    assertEquals("anthropic/claude-haiku-4-5", e.get("auxiliary.compression.model"));
    // and the main model is untouched by the override
    assertEquals("claude-opus-4-8", entries("anthropic", "claude-opus-4-8", null).get("model.default"));
  }

  // ── read-back parser: parseConfig ──────────────────────────────────────────

  @Test
  void structuredMapWithProviderKeepsNamespacedModel() {
    ConfigInfo info = modelConfig.parseConfig(Map.of(
        "model", Map.of("provider", "openrouter", "default", "anthropic/claude-sonnet-4")));
    assertEquals("openrouter", info.provider());
    // must NOT be truncated to "claude-sonnet-4"
    assertEquals("anthropic/claude-sonnet-4", info.model());
  }

  @Test
  void structuredNousRoundTrips() {
    ConfigInfo info = modelConfig.parseConfig(Map.of(
        "model", Map.of("provider", "nous", "default", "Hermes-4-405B", "base_url", "")));
    assertEquals("nous", info.provider());
    assertEquals("Hermes-4-405B", info.model());
  }

  @Test
  void customEndpointMapReadsBlankProviderAsAuto() {
    ConfigInfo info = modelConfig.parseConfig(Map.of(
        "model", Map.of("provider", "", "default", "qwen3:8b", "base_url", "http://x/v1")));
    assertEquals("auto", info.provider());
    assertEquals("qwen3:8b", info.model());
  }

  @Test
  void legacyScalarModelStringStillSplits() {
    ConfigInfo info = modelConfig.parseConfig(Map.of("model", "anthropic/claude-opus-4-8"));
    assertEquals("anthropic", info.provider());
    assertEquals("claude-opus-4-8", info.model());
  }

  @Test
  void emptyConfigDefaults() {
    ConfigInfo info = modelConfig.parseConfig(Map.of());
    assertEquals("auto", info.provider());
    assertEquals("", info.model());
  }

  @Test
  void terminalCwdIsRead() {
    ConfigInfo info = modelConfig.parseConfig(new LinkedHashMap<>(Map.of(
        "model", "nous/Hermes-4-405B",
        "terminal", Map.of("cwd", "/work"))));
    assertEquals("/work", info.cwd());
  }

  @Test
  void roundTripWritePlanThenParseIsStableForOpenrouter() {
    // simulate: write plan -> resulting model map -> parse back
    Map<String, String> plan = entries("openrouter", "anthropic/claude-sonnet-4", null);
    Map<String, Object> modelMap = new LinkedHashMap<>();
    modelMap.put("provider", plan.get("model.provider"));
    modelMap.put("default", plan.get("model.default"));
    modelMap.put("base_url", plan.get("model.base_url"));
    ConfigInfo info = modelConfig.parseConfig(Map.of("model", modelMap));
    assertEquals("openrouter", info.provider());
    assertEquals("anthropic/claude-sonnet-4", info.model());
  }

  @Test
  void planWipesThenSetsDefault() {
    List<String[]> plan = HermesModelConfig.modelConfigEntries("nous", "Hermes-4-405B", null);
    assertEquals("model", plan.get(0)[0], "wipe first");
    assertEquals("", plan.get(0)[1]);
    assertEquals("model.default", plan.get(1)[0], "then promote back to a map with the default");
  }
}
