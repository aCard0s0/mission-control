package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.HermesModelConfig.ConfigInfo;
import io.hermes.missioncontrol.agents.HermesModelConfig.ModelTarget;
import io.hermes.missioncontrol.agents.api.AuxiliaryModelSpec;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Writing the API keys a profile's model settings need, and refusing a profile that ended up
 * without a model.
 *
 * <p>{@link HermesModelConfigTest} covers the pure planners; these are the decisions with a
 * {@code .env} write behind them. Two of them matter: a provider that takes no key must not have
 * one written for it, and an override that only swaps the model must not re-write the main key —
 * a blank field in the create form would otherwise clobber a working credential.
 */
class HermesModelConfigWritesTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///sock");
  private static final String CONTAINER = "c1";
  private static final String PROFILE = "scout";

  private HermesContainerFiles files;
  private HermesEnvFile env;
  private HermesModelConfig modelConfig;

  @BeforeEach
  void setUp() {
    files = AgentsWiring.mockFiles();
    env = mock(HermesEnvFile.class);
    modelConfig = new HermesModelConfig(files, new HermesCli(files), env);
  }

  // ── the profile's own key ───────────────────────────────────────────────

  @Test
  void aProvidersKeyIsWrittenUnderTheVariableThatProviderReads() {
    modelConfig.writeApiKey(HOST, CONTAINER, PROFILE, "anthropic", "sk-ant-real");

    verify(env).write(HOST, CONTAINER, PROFILE, "ANTHROPIC_API_KEY", "sk-ant-real");
  }

  @Test
  void aProviderThatTakesNoKeyNeverGetsOneWritten() {
    // nous authenticates with an account token held at the container level, not a profile key
    modelConfig.writeApiKey(HOST, CONTAINER, PROFILE, "nous", "sk-ant-real");
    // and a provider the registry does not know has no variable to write to
    modelConfig.writeApiKey(HOST, CONTAINER, PROFILE, "who-knows", "sk-ant-real");
    modelConfig.writeApiKey(HOST, CONTAINER, PROFILE, null, "sk-ant-real");

    verifyNoInteractions(env);
  }

  @Test
  void anAbsentKeyIsNotWrittenAsAnEmptyVariable() {
    // an empty ANTHROPIC_API_KEY is worse than none: the agent starts and fails its first call
    modelConfig.writeApiKey(HOST, CONTAINER, PROFILE, "anthropic", null);
    modelConfig.writeApiKey(HOST, CONTAINER, PROFILE, "anthropic", "   ");

    verifyNoInteractions(env);
  }

  // ── the override's key ──────────────────────────────────────────────────

  @Test
  void anOverrideThatIntroducesItsOwnProviderGetsItsOwnKeyWritten() {
    ModelTarget auxiliary = new ModelTarget("openai", "gpt-5.2-mini", null);

    modelConfig.writeAuxiliaryApiKey(HOST, CONTAINER, PROFILE, auxiliary,
        new AuxiliaryModelSpec("openai", "gpt-5.2-mini", null, "sk-openai-real"));

    verify(env).write(HOST, CONTAINER, PROFILE, "OPENAI_API_KEY", "sk-openai-real");
  }

  @Test
  void aSameProviderModelSwapNeverRewritesTheMainKey() {
    // the main key already covers it, and a blank field in the create form would clobber it
    ModelTarget auxiliary = new ModelTarget("anthropic", "claude-haiku-4-5", null);

    modelConfig.writeAuxiliaryApiKey(HOST, CONTAINER, PROFILE, auxiliary,
        new AuxiliaryModelSpec(null, "claude-haiku-4-5", null, "sk-ant-real"));
    modelConfig.writeAuxiliaryApiKey(HOST, CONTAINER, PROFILE, auxiliary,
        new AuxiliaryModelSpec("   ", "claude-haiku-4-5", null, "sk-ant-real"));

    verifyNoInteractions(env);
  }

  @Test
  void anOverrideWithNoKeyOrNoSpecAtAllWritesNothing() {
    ModelTarget auxiliary = new ModelTarget("openai", "gpt-5.2-mini", null);

    modelConfig.writeAuxiliaryApiKey(HOST, CONTAINER, PROFILE, auxiliary, null);
    modelConfig.writeAuxiliaryApiKey(HOST, CONTAINER, PROFILE, auxiliary,
        new AuxiliaryModelSpec("openai", "gpt-5.2-mini", null, null));
    modelConfig.writeAuxiliaryApiKey(HOST, CONTAINER, PROFILE, auxiliary,
        new AuxiliaryModelSpec("openai", "gpt-5.2-mini", null, "  "));

    verifyNoInteractions(env);
  }

  @Test
  void anOverrideOnAKeylessProviderWritesNothingEither() {
    ModelTarget auxiliary = new ModelTarget("nous", "Hermes-4-70B", null);

    modelConfig.writeAuxiliaryApiKey(HOST, CONTAINER, PROFILE, auxiliary,
        new AuxiliaryModelSpec("nous", "Hermes-4-70B", null, "token"));

    verifyNoInteractions(env);
  }

  // ── the routing key the provider does not use ───────────────────────────

  @Test
  void theOtherRoutingKeyIsUnsetAfterTheSetsAndWithoutCheckingTheExit() {
    // hermes answers 1 to unsetting a key that is already absent, and builds before v0.21.0 —
    // where the bare `model` set still wipes the map — have no `unset` at all
    modelConfig.write(HOST, CONTAINER, PROFILE, "openai", "gpt-5.5", null,
        new ModelTarget("openai", "gpt-5.5", null));

    InOrder order = inOrder(files);
    order.verify(files).exec(HOST, CONTAINER,
        List.of("hermes", "-p", PROFILE, "config", "set", "model.provider", "openai-api"), true);
    order.verify(files).exec(HOST, CONTAINER,
        List.of("hermes", "-p", PROFILE, "config", "unset", "model.base_url"), false);
    verify(files, never()).exec(HOST, CONTAINER,
        List.of("hermes", "-p", PROFILE, "config", "unset", "model.provider"), false);
  }

  @Test
  void aCustomEndpointUnsetsTheProviderInstead() {
    modelConfig.write(HOST, CONTAINER, PROFILE, "ollama", "qwen3:8b", "http://x:11434/v1",
        new ModelTarget("ollama", "qwen3:8b", "http://x:11434/v1"));

    verify(files).exec(HOST, CONTAINER,
        List.of("hermes", "-p", PROFILE, "config", "unset", "model.provider"), false);
    verify(files, never()).exec(HOST, CONTAINER,
        List.of("hermes", "-p", PROFILE, "config", "unset", "model.base_url"), false);
  }

  // ── the working dir ─────────────────────────────────────────────────────

  @Test
  void theWorkingDirGoesThroughHermesOwnWriterAndABlankOneIsRefused() {
    modelConfig.writeWorkingDir(HOST, CONTAINER, PROFILE, " /work ");

    verify(files).exec(HOST, CONTAINER,
        List.of("hermes", "-p", PROFILE, "config", "set", "terminal.cwd", "/work"), true);
    assertThrows(IllegalArgumentException.class,
        () -> modelConfig.writeWorkingDir(HOST, CONTAINER, PROFILE, "  "));
  }

  // ── refusing a profile with no model ────────────────────────────────────

  @Test
  void aProfileWhoseConfigNamesNoModelIsRefusedSoTheCallerCanRollItBack() {
    // hermes profile create never seeds config.yaml; if the config writes silently no-op the
    // agent's auxiliary chain has nothing to resolve to and its gateway logs say so at runtime
    when(files.readFile(any(), anyString(), anyString())).thenReturn("terminal:\n  cwd: /work\n");

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> modelConfig.assertConfigured(HOST, CONTAINER, PROFILE));

    assertEquals(true, failure.getMessage().contains("has no model in"));
    assertEquals(true, failure.getMessage().contains("compression, summarization, memory flush"));
  }

  @Test
  void aProfileWithAModelPassesTheCheck() {
    when(files.readFile(any(), anyString(), anyString()))
        .thenReturn("model:\n  provider: anthropic\n  default: claude-opus-5\n");

    modelConfig.assertConfigured(HOST, CONTAINER, PROFILE);

    verify(files).readFile(HOST, CONTAINER, "/opt/data/profiles/scout/config.yaml");
    verify(env, never()).write(any(), anyString(), anyString(), anyString(), anyString());
  }

  // ── reading a config back ───────────────────────────────────────────────

  @Test
  void aConfigWithNothingUsableInItReadsAsAutoWithNoModel() {
    for (Map<?, ?> map : List.of(Map.of(), Map.of("model", List.of("opus")), Map.of("terminal", "none"))) {
      ConfigInfo info = modelConfig.parseConfig(map);
      assertEquals("auto", info.provider());
      assertEquals("", info.model());
      assertEquals("", info.cwd());
    }
    assertEquals("auto", modelConfig.parseConfig(null).provider());
  }

  @Test
  void aStructuredBlockWithNoDefaultFallsBackToItsModelKey() {
    // an older hermes wrote model.model rather than model.default
    ConfigInfo info = modelConfig.parseConfig(
        YamlValues.parseMap("model:\n  provider: openrouter\n  model: anthropic/claude-sonnet-4\n"));

    assertEquals("openrouter", info.provider());
    assertEquals("anthropic/claude-sonnet-4", info.model(), "the namespace must survive");
  }

  @Test
  void aStructuredBlockWithNoProviderAndNoSlashKeepsTheModelAsWritten() {
    ConfigInfo info = modelConfig.parseConfig(YamlValues.parseMap("model:\n  default: qwen3:8b\n"));

    assertEquals("auto", info.provider());
    assertEquals("qwen3:8b", info.model());
  }

  @Test
  void aScalarModelStringWithNothingBeforeTheSlashIsNotSplit() {
    // "/opus" has no provider half; treating "" as the provider would write a broken config
    assertEquals("auto", modelConfig.parseModelString("/opus").provider());
    assertEquals("/opus", modelConfig.parseModelString("/opus").model());
    assertEquals("auto", modelConfig.parseModelString(null).provider());
    assertEquals("", modelConfig.parseModelString("   ").model());
  }
}
