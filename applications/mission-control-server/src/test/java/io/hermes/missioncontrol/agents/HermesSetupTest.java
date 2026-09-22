package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.MessagingStatusDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Setup reporting merges two sources — the profile {@code .env} and the {@code hermes
 * status} report — and its failure mode is quiet: a parsing slip makes every provider look
 * unconfigured, which is indistinguishable from a genuinely empty agent.
 *
 * <p>The status fixtures here are hand-written to the format the parser documents. They pin
 * the parser's own rules; they are not evidence about what the hermes CLI actually emits, so
 * a CLI format change is still a live risk this suite cannot see.
 */
class HermesSetupTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///var/run/docker.sock");
  private static final String CONTAINER = "c1";
  private static final String PROFILE = "default";

  private HermesContainerFiles files;
  private HermesSetup setup;

  @BeforeEach
  void setUp() {
    files = AgentsWiring.mockFiles();
    setup = new HermesSetup(files, mock(HermesEnvFile.class));
    when(files.fileExists(any(), anyString(), anyString())).thenReturn(true);
    when(files.readFile(any(), anyString(), anyString())).thenReturn("");
    statusOutput("");
  }

  private void statusOutput(String stdout) {
    when(files.exec(any(), anyString(), any()))
        .thenReturn(new io.hermes.missioncontrol.docker.DockerExecService.ExecResult(0, stdout, ""));
  }

  /** What {@code hermes auth list} answers; {@code hermes status} keeps answering {@code stdout}
   *  from {@link #statusOutput}. */
  private void authListOutput(String stdout) {
    when(files.exec(any(), anyString(), argThat(cmd -> cmd != null && cmd.contains("auth"))))
        .thenReturn(new io.hermes.missioncontrol.docker.DockerExecService.ExecResult(0, stdout, ""));
  }

  /** The default profile lives at Hermes' home, not under {@code profiles/} — see
   *  {@link ProfilePaths#profileDir}. */
  private void envFile(String contents) {
    when(files.readFile(HOST, CONTAINER, "/opt/data/.env")).thenReturn(contents);
  }

  private AgentSetupDto run() {
    return setup.setup(HOST, CONTAINER, PROFILE);
  }

  private static ApiKeyStatusDto key(AgentSetupDto dto, String envVar) {
    return dto.apiKeys().stream().filter(k -> k.envVar().equals(envVar)).findFirst().orElseThrow();
  }

  @Test
  void aKeyInTheEnvIsReportedSetAndMaskedToItsLastFourCharacters() {
    envFile("ANTHROPIC_API_KEY=sk-ant-secret-value-1234");

    ApiKeyStatusDto anthropic = key(run(), "ANTHROPIC_API_KEY");
    assertTrue(anthropic.set());
    assertEquals("...1234", anthropic.masked());
  }

  @Test
  void aKeyTooShortToHaveAHiddenPartRevealsNoneOfItsCharacters() {
    // a mask that appends the whole value discloses the secret it exists to hide, and a
    // short key is exactly the case where the suffix *is* the whole key
    envFile("OPENAI_API_KEY=abc");

    assertEquals("...", key(run(), "OPENAI_API_KEY").masked());
  }

  @Test
  void anAlternateVariableIsUsedWhenThePrimaryIsAbsent() {
    // ANTHROPIC_TOKEN is the documented fallback for ANTHROPIC_API_KEY
    envFile("ANTHROPIC_TOKEN=sk-ant-fallback-9876");

    ApiKeyStatusDto anthropic = key(run(), "ANTHROPIC_API_KEY");
    assertTrue(anthropic.set());
    assertEquals("...9876", anthropic.masked());
  }

  @Test
  void aBlankValueDoesNotCountAsConfigured() {
    envFile("OPENAI_API_KEY=   ");

    ApiKeyStatusDto openai = key(run(), "OPENAI_API_KEY");
    assertFalse(openai.set());
    assertNull(openai.masked());
  }

  @Test
  void commentedAndMalformedEnvLinesAreIgnored() {
    envFile("""
        # OPENAI_API_KEY=commented-out
        this line has no equals sign
        =leading-equals-no-key
        OPENAI_API_KEY=real-value-4321
        """);

    assertEquals("...4321", key(run(), "OPENAI_API_KEY").masked());
  }

  @Test
  void statusRowsFillInProvidersConfiguredOutsideTheEnvFile() {
    statusOutput("""
        ◆ API Keys
          OpenAI  ✓ configured
          DeepSeek  ✗ not configured
        """);

    assertTrue(key(run(), "OPENAI_API_KEY").set());
    assertFalse(key(run(), "DEEPSEEK_API_KEY").set());
    // the status report says nothing about the value, so nothing is masked
    assertNull(key(run(), "OPENAI_API_KEY").masked());
  }

  @Test
  void theEnvFileWinsOverTheStatusReport() {
    statusOutput("""
        ◆ API Keys
          OpenAI  ✗ not configured
        """);
    envFile("OPENAI_API_KEY=sk-real-5555");

    ApiKeyStatusDto openai = key(run(), "OPENAI_API_KEY");
    assertTrue(openai.set());
    assertEquals("...5555", openai.masked());
  }

  @Test
  void ansiColourCodesAreStrippedBeforeParsing() {
    statusOutput("[1m◆ API Keys[0m\n  [32mOpenAI[0m  ✓ configured\n");

    assertTrue(key(run(), "OPENAI_API_KEY").set());
  }

  @Test
  void aFailingPooledCredentialIsFlaggedOnTheKeyRowItsProviderReads() {
    // the format `hermes auth list` prints, one block per provider; the codex block is an
    // OAuth login with no .env variable and must not be pinned on any key row
    authListOutput("""
        openai-api (1 credentials):
          #1  OPENAI_API_KEY       api_key id=cd6000 priority=0 env:OPENAI_API_KEY auth failed invalid_api_key (401) (re-auth may be required)

        openai-codex (1 credentials):
          #1  device_code          oauth   id=25a6f2 priority=0 device_code ←

        anthropic (1 credentials):
          #1  ANTHROPIC_API_KEY    api_key id=9a1b2c priority=0 env:ANTHROPIC_API_KEY ←
        """);

    AgentSetupDto dto = run();
    assertEquals("auth failed invalid_api_key (401) (re-auth may be required)",
        key(dto, "OPENAI_API_KEY").problem());
    // a healthy row ends at its source; the active marker is not a problem
    assertNull(key(dto, "ANTHROPIC_API_KEY").problem());
    assertNull(key(dto, "OPENROUTER_API_KEY").problem());
  }

  @Test
  void aPooledProblemSurvivesTheKeyBeingGoneFromTheEnvFile() {
    // hermes keeps the pool entry after the .env line is deleted — this is the case the
    // other two sources cannot see: `hermes status` says "not set", the .env agrees, and
    // every turn still dies on the remembered key
    envFile("");
    authListOutput("""
        openai-api (1 credentials):
          #1  OPENAI_API_KEY  api_key id=60af95 priority=0 env:OPENAI_API_KEY billing credit_balance_exhausted (402)
        """);

    ApiKeyStatusDto openai = key(run(), "OPENAI_API_KEY");
    assertFalse(openai.set());
    assertEquals("billing credit_balance_exhausted (402)", openai.problem());
  }

  @Test
  void aFailingAuthListLeavesEveryKeyWithoutAProblemRatherThanFailingTheRequest() {
    when(files.exec(any(), anyString(), argThat(cmd -> cmd != null && cmd.contains("auth"))))
        .thenThrow(new RuntimeException("exec failed"));
    envFile("OPENAI_API_KEY=sk-1234567890abcd\n");

    ApiKeyStatusDto openai = key(run(), "OPENAI_API_KEY");
    assertTrue(openai.set());
    assertNull(openai.problem());
  }

  @Test
  void anAuthProviderNamesTheRegistryKeyItLogsInto() {
    statusOutput("""
        ◆ Auth Providers
          Nous Portal   ✗ not logged in (run: hermes portal)
          OpenAI Codex  ✓ logged in
          Qwen OAuth    ✗ not logged in (run: qwen auth qwen-oauth)
        """);

    List<AuthProviderDto> rows = run().authProviders();
    assertEquals("nous", rows.get(0).providerKey());
    assertEquals("openai-codex", rows.get(1).providerKey());
    // reported, but nothing in the picker to point a profile at
    assertNull(rows.get(2).providerKey());
  }

  @Test
  void authAndApiKeyProviderSectionsAreReportedSeparately() {
    statusOutput("""
        ◆ Auth Providers
          Nous Portal  ✓ signed in
          Claude  ✗ not signed in (run: hermes portal)
        ◆ API-Key Providers
          OpenRouter  ✓ key present
        """);

    AgentSetupDto dto = run();
    assertEquals(2, dto.authProviders().size());
    assertEquals("Nous Portal", dto.authProviders().getFirst().label());
    assertTrue(dto.authProviders().getFirst().ok());

    AuthProviderDto claude = dto.authProviders().get(1);
    assertFalse(claude.ok());
    assertEquals("hermes portal", claude.hint());

    assertEquals(1, dto.apiKeyProviders().size());
    assertEquals("OpenRouter", dto.apiKeyProviders().getFirst().label());
  }

  @Test
  void deeplyIndentedDetailLinesAreNotMistakenForRows() {
    statusOutput("""
        ◆ Auth Providers
          Nous Portal  ✓ signed in
              token expires in 30 days ✓
        """);

    assertEquals(1, run().authProviders().size());
  }

  @Test
  void messagingFallsBackToTheEnvWhenTheStatusReportIsSilent() {
    envFile("""
        TELEGRAM_BOT_TOKEN=bot-token
        TELEGRAM_HOME_CHANNEL=@ops
        """);

    MessagingStatusDto telegram = run().messaging().stream()
        .filter(m -> m.label().equals("Telegram")).findFirst().orElseThrow();
    assertTrue(telegram.ok());
    assertEquals("configured", telegram.status());
    assertEquals("@ops", telegram.homeChannel());
  }

  @Test
  void aFailingStatusCommandDegradesToTheEnvInsteadOfFailingTheRequest() {
    when(files.exec(any(), anyString(), any()))
        .thenThrow(new RuntimeException("container is not running"));
    envFile("OPENAI_API_KEY=sk-still-here-7777");

    AgentSetupDto dto = run();
    assertTrue(key(dto, "OPENAI_API_KEY").set());
    assertTrue(dto.authProviders().isEmpty());
    assertFalse(dto.messaging().isEmpty());
  }

  @Test
  void aNamedProfileAsksHermesForThatProfile() {
    setup.setup(HOST, CONTAINER, "scout");

    org.mockito.Mockito.verify(files)
        .exec(HOST, CONTAINER, List.of("hermes", "-p", "scout", "status"));
  }

  @Test
  void theDefaultProfileOmitsTheProfileFlag() {
    run();

    org.mockito.Mockito.verify(files).exec(HOST, CONTAINER, List.of("hermes", "status"));
  }

  @Test
  void theEnvTemplateDocumentsEveryKnownVariable() {
    String template = HermesEnvCatalog.template();

    for (HermesEnvCatalog.ApiKeySpec spec : HermesEnvCatalog.API_KEYS) {
      assertTrue(template.contains(spec.envVar()), "template is missing " + spec.envVar());
    }
    for (HermesEnvCatalog.MessagingSpec spec : HermesEnvCatalog.MESSAGING) {
      assertTrue(template.contains(spec.tokenVar()), "template is missing " + spec.tokenVar());
    }
    // every line is commented out, so seeding a profile with it enables nothing
    template.lines().filter(line -> !line.isBlank())
        .forEach(line -> assertTrue(line.startsWith("#"), "uncommented template line: " + line));
  }

  // ── parsing the .env back ────────────────────────────────────────────────

  @Test
  void commentsBlankLinesAndValuelessLinesAreIgnoredWhenReadingEnv() {
    // the seeded template is entirely commented out, so a parser that read '#' lines as values
    // would report every provider as configured
    envFile("""
        # ANTHROPIC_API_KEY=commented-out

          OPENAI_API_KEY = sk-openai-abcdefgh
        NOT_AN_ASSIGNMENT
        =novalue
        """);

    AgentSetupDto dto = run();

    assertTrue(key(dto, "OPENAI_API_KEY").set(), "a padded assignment is still an assignment");
    assertFalse(key(dto, "ANTHROPIC_API_KEY").set(), "a commented key is not set");
  }

  @Test
  void anAbsentEnvFileReportsNothingSetRatherThanFailing() {
    when(files.fileExists(any(), anyString(), anyString())).thenReturn(false);
    envFile("");

    AgentSetupDto dto = run();

    assertFalse(dto.envExists());
    assertFalse(key(dto, "ANTHROPIC_API_KEY").set());
  }

  @Test
  void aStatusLineOutsideAnySectionOrIndentedAsDetailIsSkipped() {
    // the report starts with a banner, and each row may carry indented detail lines; taking
    // either as a row would invent providers the agent does not have
    statusOutput("""
        hermes 1.2.0 — profile default
        \u001B[1m◆ API Keys\u001B[0m
          OpenAI  ✓ configured
              key loaded from /opt/data/profiles/default/.env
          NoMarkHere is not a row
        """);

    AgentSetupDto dto = run();

    assertTrue(key(dto, "OPENAI_API_KEY").set(), "the row's own ✓ marks it configured");
    assertFalse(dto.apiKeys().isEmpty());
  }

  @Test
  void anEnvKeyThatIsBlankOrMalformedBlocksTheWholeBatch() {
    for (String key : java.util.Arrays.asList("", "   ", "lower_case", "1LEADING", null)) {
      assertThrows(IllegalArgumentException.class,
          () -> setup.putEnv(HOST, CONTAINER, PROFILE, List.of(new EnvEntry(key, "value"))));
    }
  }

  @Test
  void aStatusReportThatCouldNotBeReadLeavesEveryProviderUnknownRatherThanFailing() {
    // 'hermes status' fails on a container whose gateway has never started; the .env half of
    // the report is still worth showing
    when(files.exec(any(), anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenThrow(new io.hermes.missioncontrol.errors.UpstreamUnavailableException("exec failed"));
    envFile("ANTHROPIC_API_KEY=sk-ant-abcdefgh\n");

    AgentSetupDto dto = run();

    assertTrue(key(dto, "ANTHROPIC_API_KEY").set(), "the .env is still read");
    assertFalse(key(dto, "OPENAI_API_KEY").set());
  }
}
