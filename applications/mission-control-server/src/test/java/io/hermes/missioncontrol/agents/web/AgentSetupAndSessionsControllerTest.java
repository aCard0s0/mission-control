package io.hermes.missioncontrol.agents.web;

import static io.hermes.missioncontrol.agents.web.AgentWebFixture.BASE;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.CONTAINER;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.HOST;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.PROFILE;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.hostIsConnected;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.hostIsDown;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The credential and conversation endpoints. The env writer is the one that matters: it takes
 * API keys, so a malformed variable name has to be refused by validation before anything is
 * written into the container's {@code .env}.
 */
class AgentSetupAndSessionsControllerTest {

  private HermesSetup setup;
  private HermesProfiles profiles;
  private HostService hosts;
  private CredentialService credentials;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    setup = mock(HermesSetup.class);
    profiles = mock(HermesProfiles.class);
    hosts = mock(HostService.class);
    credentials = mock(CredentialService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(
            new AgentSetupController(setup, hosts, credentials),
            new AgentSessionsController(profiles, hosts))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  // ── credentials ─────────────────────────────────────────────────────────

  @Test
  void containerLevelAuthProvidersAreReadThroughTheDefaultProfile() throws Exception {
    // OAuth tokens live in the container's auth.json, not in a profile, so this reports what a
    // not-yet-created agent would inherit
    hostIsConnected(hosts);
    when(setup.setup(HOST, CONTAINER, "default")).thenReturn(setupReport(
        List.of(new AuthProviderDto("Nous Portal", true, "authenticated", null, "nous"))));

    mvc.perform(get("/api/agents/" + HOST.id() + "/" + CONTAINER + "/auth-providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].label").value("Nous Portal"));

    verify(setup).setup(HOST, CONTAINER, "default");
  }

  @Test
  void theSetupReportForAProfileDelegates() throws Exception {
    hostIsConnected(hosts);
    when(setup.setup(HOST, CONTAINER, PROFILE)).thenReturn(setupReport(List.of()));

    mvc.perform(get(BASE + "/setup"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.envPath").value("/opt/data/profiles/scout/.env"));
  }

  @Test
  void anEnvKeyThatIsNotAShellStyleNameIsRefusedBeforeAnythingIsWritten() throws Exception {
    mvc.perform(put(BASE + "/env").contentType(MediaType.APPLICATION_JSON)
            .content("{\"entries\":[{\"key\":\"anthropic-api-key\",\"value\":\"sk-ant-x\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verifyNoInteractions(setup);
    verifyNoInteractions(hosts);
  }

  @Test
  void anOverlongEnvValueIsRefused() throws Exception {
    mvc.perform(put(BASE + "/env").contentType(MediaType.APPLICATION_JSON)
            .content("{\"entries\":[{\"key\":\"ANTHROPIC_API_KEY\",\"value\":\""
                + "x".repeat(8_193) + "\"}]}"))
        .andExpect(status().isBadRequest());

    verify(setup, never()).putEnv(any(), anyString(), anyString(), anyList());
  }

  @Test
  void aValidEnvWriteReachesTheContainerWithItsEntries() throws Exception {
    hostIsConnected(hosts);
    when(setup.putEnv(eq(HOST), eq(CONTAINER), eq(PROFILE), anyList())).thenReturn(setupReport(List.of()));

    mvc.perform(put(BASE + "/env").contentType(MediaType.APPLICATION_JSON)
            .content("{\"entries\":[{\"key\":\"ANTHROPIC_API_KEY\",\"value\":\"sk-ant-x\"}]}"))
        .andExpect(status().isOk());

    verify(setup).putEnv(HOST, CONTAINER, PROFILE,
        List.of(new EnvEntry("ANTHROPIC_API_KEY", "sk-ant-x")));
  }

  @Test
  void anEntryNamingACredentialIsResolvedBeforeTheWriterSeesIt() throws Exception {
    // the browser posts an id and never holds the key; nothing below this layer knows
    // credentials exist, so putEnv receives the two-argument form
    hostIsConnected(hosts);
    when(credentials.valueFor("cr-1", "ANTHROPIC_API_KEY")).thenReturn("sk-from-the-vault");
    when(setup.putEnv(eq(HOST), eq(CONTAINER), eq(PROFILE), anyList())).thenReturn(setupReport(List.of()));

    mvc.perform(put(BASE + "/env").contentType(MediaType.APPLICATION_JSON)
            .content("{\"entries\":[{\"key\":\"ANTHROPIC_API_KEY\",\"credentialId\":\"cr-1\"}]}"))
        .andExpect(status().isOk());

    verify(setup).putEnv(HOST, CONTAINER, PROFILE,
        List.of(new EnvEntry("ANTHROPIC_API_KEY", "sk-from-the-vault")));
  }

  @Test
  void aBatchMayMixTypedValuesAndPickedCredentials() throws Exception {
    hostIsConnected(hosts);
    when(credentials.valueFor("cr-1", "TELEGRAM_BOT_TOKEN")).thenReturn("bot-from-the-vault");
    when(setup.putEnv(eq(HOST), eq(CONTAINER), eq(PROFILE), anyList())).thenReturn(setupReport(List.of()));

    mvc.perform(put(BASE + "/env").contentType(MediaType.APPLICATION_JSON)
            .content("{\"entries\":[{\"key\":\"TELEGRAM_BOT_TOKEN\",\"credentialId\":\"cr-1\"},"
                + "{\"key\":\"TELEGRAM_HOME_CHANNEL\",\"value\":\"#ops\"}]}"))
        .andExpect(status().isOk());

    verify(setup).putEnv(HOST, CONTAINER, PROFILE, List.of(
        new EnvEntry("TELEGRAM_BOT_TOKEN", "bot-from-the-vault"),
        new EnvEntry("TELEGRAM_HOME_CHANNEL", "#ops")));
  }

  @Test
  void aBlankCredentialIdIsNoCredentialAtAll() throws Exception {
    // an editor that sends "" rather than omitting the field must still be able to clear a
    // variable, which is what a blank value means
    hostIsConnected(hosts);
    when(setup.putEnv(eq(HOST), eq(CONTAINER), eq(PROFILE), anyList())).thenReturn(setupReport(List.of()));

    mvc.perform(put(BASE + "/env").contentType(MediaType.APPLICATION_JSON)
            .content("{\"entries\":[{\"key\":\"ANTHROPIC_API_KEY\",\"value\":\"\",\"credentialId\":\"\"}]}"))
        .andExpect(status().isOk());

    verifyNoInteractions(credentials);
    verify(setup).putEnv(HOST, CONTAINER, PROFILE,
        List.of(new EnvEntry("ANTHROPIC_API_KEY", "")));
  }

  @Test
  void anEnvWriteWithNoEntriesAtAllStillReadsTheSetupBack() throws Exception {
    hostIsConnected(hosts);
    when(setup.putEnv(eq(HOST), eq(CONTAINER), eq(PROFILE), anyList())).thenReturn(setupReport(List.of()));

    mvc.perform(put(BASE + "/env").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());

    verify(setup).putEnv(HOST, CONTAINER, PROFILE, List.of());
  }

  @Test
  void initialisingEnvDelegates() throws Exception {
    hostIsConnected(hosts);
    when(setup.initEnv(HOST, CONTAINER, PROFILE)).thenReturn(setupReport(List.of()));

    mvc.perform(post(BASE + "/env/init")).andExpect(status().isOk());

    verify(setup).initEnv(HOST, CONTAINER, PROFILE);
  }

  // ── sessions ────────────────────────────────────────────────────────────

  @Test
  void listingSessionsDelegates() throws Exception {
    hostIsConnected(hosts);
    when(profiles.listSessions(HOST, CONTAINER, PROFILE))
        .thenReturn(List.of(new SessionDto("s-1", "first run", "cli", 1_700_000_000_000L, 4, "done")));

    mvc.perform(get(BASE + "/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("s-1"));
  }

  @Test
  void sessionMessagesAreReturnedAsTheStoredJsonWithoutReSerialising() throws Exception {
    // the in-container query already emits a JSON array; re-parsing it here would only add a
    // way for the response to differ from what the agent recorded
    hostIsConnected(hosts);
    when(profiles.readSessionMessages(HOST, CONTAINER, PROFILE, "s-1"))
        .thenReturn("[{\"role\":\"user\",\"text\":\"hello\"}]");

    mvc.perform(get(BASE + "/sessions/s-1"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string("[{\"role\":\"user\",\"text\":\"hello\"}]"));
  }

  @Test
  void deletingASessionDelegates() throws Exception {
    hostIsConnected(hosts);

    mvc.perform(delete(BASE + "/sessions/s-1")).andExpect(status().isOk());

    verify(profiles).deleteSession(HOST, CONTAINER, PROFILE, "s-1");
  }

  @Test
  void aDisconnectedHostFailsBothControllersBeforeTheContainerIsTouched() throws Exception {
    hostIsDown(hosts);

    mvc.perform(get(BASE + "/setup")).andExpect(status().isServiceUnavailable());
    mvc.perform(post(BASE + "/env/init")).andExpect(status().isServiceUnavailable());
    mvc.perform(get(BASE + "/sessions")).andExpect(status().isServiceUnavailable());
    mvc.perform(delete(BASE + "/sessions/s-1")).andExpect(status().isServiceUnavailable());

    verifyNoInteractions(setup);
    verifyNoInteractions(profiles);
  }

  private static AgentSetupDto setupReport(List<AuthProviderDto> authProviders) {
    return new AgentSetupDto("/opt/data/profiles/scout/.env", true,
        List.of(), authProviders, List.of(), List.of());
  }
}
