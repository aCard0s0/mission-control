package io.hermes.missioncontrol.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.docker.ContainerResources;
import io.hermes.missioncontrol.docker.ContainerUpdateService;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.docker.StatsDto;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import io.hermes.missioncontrol.docker.HostAccess;

/**
 * The container endpoints. The gateway is a mock — what is being pinned here is the layer
 * above it: which hosts get skipped, whether a host id or a daemon url reaches the gateway,
 * and that a body the validator rejects never touches Docker at all.
 */
class ContainersControllerTest {

  /** The ref the controller builds from a listed host row. */
  private static DockerHostRef ref(String hostId) {
    return new DockerHostRef(hostId, HOST.url());
  }

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///var/run/docker.sock");

  private DockerGateway docker;
  private HostService hosts;
  private ContainerUpdateService updates;
  private ProfileTemplateService templates;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    docker = mock(DockerGateway.class);
    hosts = mock(HostService.class);
    updates = mock(ContainerUpdateService.class);
    templates = mock(ProfileTemplateService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new ContainersController(docker, hosts, updates, templates))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static DockerHostDto host(String id, String url, String status) {
    return new DockerHostDto(id, id, url, "local", status, "docker", "1.47", 3L, null);
  }

  private static ContainerDto container(String id, String hostId) {
    return new ContainerDto(id, id.substring(0, 3), "hermes-" + id, hostId, "running",
        "hermes/agent:v1", "v1", null, null, 1L, null, List.of(), List.of());
  }

  @Test
  void listSkipsHostsThatAreNotConnected() throws Exception {
    when(hosts.list()).thenReturn(List.of(
        host("dh-up", HOST.url(), "connected"),
        host("dh-down", HOST.url(), "error")));
    when(docker.listContainers(ref("dh-up"), false)).thenReturn(List.of(container("abc123", "dh-up")));

    mvc.perform(get("/api/containers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    // the down host's status is already visible on /api/hosts; probing it here would
    // just make the inventory call as slow as the slowest dead daemon
    verify(docker).listContainers(ref("dh-up"), false);
    verify(docker, org.mockito.Mockito.never()).listContainers(eq(ref("dh-down")), anyBoolean());
  }

  @Test
  void listSurvivesOneHostThrowingAndStillReturnsTheOthers() throws Exception {
    when(hosts.list()).thenReturn(List.of(
        host("dh-broken", HOST.url(), "connected"),
        host("dh-ok", HOST.url(), "connected")));
    when(docker.listContainers(ref("dh-broken"), false))
        .thenThrow(new RuntimeException("daemon went away mid-list"));
    when(docker.listContainers(ref("dh-ok"), false)).thenReturn(List.of(container("abc123", "dh-ok")));

    // a host that dies between the probe and the listing must not take the whole fleet
    // view down with it
    mvc.perform(get("/api/containers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].hostId").value("dh-ok"));
  }

  @Test
  void listFiltersToOneHostWhenHostIdIsGivenAndForwardsTheAllFlag() throws Exception {
    when(hosts.list()).thenReturn(List.of(host("dh-a", HOST.url(), "connected"), host("dh-b", HOST.url(), "connected")));
    when(docker.listContainers(any(), anyBoolean())).thenReturn(List.of());

    mvc.perform(get("/api/containers").param("hostId", "dh-a").param("all", "true"))
        .andExpect(status().isOk());

    ArgumentCaptor<Boolean> all = ArgumentCaptor.forClass(Boolean.class);
    verify(docker).listContainers(eq(ref("dh-a")), all.capture());
    // all=true switches off the Hermes name/image filter — a silently dropped flag makes
    // the "show everything" toggle in the UI do nothing
    assertEquals(true, all.getValue());
    verify(docker, org.mockito.Mockito.never()).listContainers(eq(ref("dh-down")), anyBoolean());
  }

  @Test
  void statsAndLogsResolveTheHostUrlBeforeReachingTheDaemon() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    when(docker.stats(HOST, "abc123")).thenReturn(new StatsDto(12.5, 256, 2048, 1, 2, 99L));
    when(docker.logs(HOST, "abc123", 100, null)).thenReturn(List.of(new LogLineDto(1L, "info", "stdout", "up")));

    mvc.perform(get("/api/containers/dh-local/abc123/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cpuPercent").value(12.5));

    // the default tail is the contract the frontend relies on when it omits the param
    mvc.perform(get("/api/containers/dh-local/abc123/logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    verify(docker).stats(HOST, "abc123");
    verify(docker).logs(HOST, "abc123", 100, null);
  }

  @Test
  void theBatchedStatsEndpointAnswersAMapKeyedByContainerId() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    when(docker.stats(HOST, List.of("abc123", "def456")))
        .thenReturn(Map.of("abc123", new StatsDto(12.5, 256, 2048, 1, 2, 99L)));

    mvc.perform(get("/api/containers/dh-local/stats").param("ids", "abc123,def456"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.abc123.cpuPercent").value(12.5))
        // def456's stream has not delivered yet, so it is absent rather than reported as
        // zero — a card already showing a figure keeps it instead of blinking to 0%
        .andExpect(jsonPath("$.def456").doesNotExist());
  }

  @Test
  void aLogCursorIsForwardedSoAPollNeedNotRereadTheWholeTail() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    when(docker.logs(any(), anyString(), anyInt(), any())).thenReturn(List.of());

    mvc.perform(get("/api/containers/dh-local/abc123/logs").param("since", "1786701601500"))
        .andExpect(status().isOk());

    verify(docker).logs(HOST, "abc123", 100, 1_786_701_601_500L);
  }

  @Test
  void anExplicitTailIsForwardedToTheGateway() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    when(docker.logs(any(), anyString(), anyInt(), any())).thenReturn(List.of());

    mvc.perform(get("/api/containers/dh-local/abc123/logs").param("tail", "500"))
        .andExpect(status().isOk());

    verify(docker).logs(HOST, "abc123", 500, null);
  }

  @Test
  void deployRejectsAnInvalidContainerNameBeforeTouchingDocker() throws Exception {
    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"../evil\",\"version\":\"v1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verifyNoInteractions(docker);
    verifyNoInteractions(hosts);
  }

  @Test
  @SuppressWarnings("unchecked")
  void deployRejectsAnInvalidProfileNameAndReturnsTheNewContainerId() throws Exception {
    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"scout\",\"profiles\":[\"Bad Name\"]}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(docker);

    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    ArgumentCaptor<Consumer<String>> afterReady = ArgumentCaptor.forClass(Consumer.class);
    when(docker.deploy(eq(HOST), eq("scout"), eq("v1"), eq(List.of("default")), eq(ContainerResources.BASELINE),
        eq(HostAccess.NONE), afterReady.capture()))
        .thenReturn("newid123");

    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"scout\",\"version\":\"v1\",\"profiles\":[\"default\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("newid123"));
    // no blueprint named: the step the deployer runs after readiness does nothing at all
    afterReady.getValue().accept("newid123");
    verifyNoInteractions(templates);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aBlueprintForTheDefaultAgentIsResolvedFirstAndAppliedOnceTheContainerIsReady() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    ArgumentCaptor<Consumer<String>> afterReady = ArgumentCaptor.forClass(Consumer.class);
    when(docker.deploy(eq(HOST), eq("scout"), eq("v1"), isNull(), eq(ContainerResources.BASELINE),
        eq(HostAccess.NONE), afterReady.capture()))
        .thenReturn("newid123");

    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"scout\",\"version\":\"v1\",\"defaultTemplateId\":\" pt-1 \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("newid123"));

    // resolved before the deploy, so an unknown id never costs a volume
    verify(templates).get("pt-1");
    verify(templates, never()).applyToDefault(anyString(), any(), anyString());
    // and the step the deployer runs after readiness is the apply, onto the new container
    afterReady.getValue().accept("newid123");
    verify(templates).applyToDefault("pt-1", HOST, "newid123");
  }

  @Test
  void anUnknownBlueprintIs404BeforeAnythingIsCreated() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    when(templates.get("pt-gone")).thenThrow(new NoSuchElementException("unknown template: pt-gone"));

    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"scout\",\"defaultTemplateId\":\"pt-gone\"}"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(docker);
  }

  @Test
  void updateRejectsAnInvalidImageTagAndOtherwiseReturnsTheReplacementId() throws Exception {
    mvc.perform(post("/api/containers/dh-local/abc123/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"../evil\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(updates);

    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    when(updates.update(HOST, "abc123", "v2", HostAccess.NONE)).thenReturn("replacement456");

    // the container id changes on an update, so the caller has to be told the new one
    mvc.perform(post("/api/containers/dh-local/abc123/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"v2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("replacement456"));
  }

  @Test
  void updateCarriesHostAccessAndRefusesTheSameMountsADeployWould() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
    HostAccess dashboard = new HostAccess(
        List.of(new HostAccess.PortMapping(9119, 9119, "")),
        List.of(new HostAccess.EnvVar("HERMES_DASHBOARD", "1")), List.of());
    when(updates.update(HOST, "abc123", "v2", dashboard)).thenReturn("replacement456");

    // the tag it already runs is a legitimate target once host access comes with it — the
    // recreate is then the point, and the service decides, not the request's validation
    mvc.perform(post("/api/containers/dh-local/abc123/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"v2\",\"ports\":[{\"containerPort\":9119,\"hostPort\":9119,\"hostIp\":\"\"}],"
                + "\"env\":[{\"key\":\"HERMES_DASHBOARD\",\"value\":\"1\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("replacement456"));

    mvc.perform(post("/api/containers/dh-local/abc123/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"v2\",\"mounts\":[{\"source\":\"/var/run/docker.sock\","
                + "\"target\":\"/var/run/docker.sock\",\"readOnly\":false}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Docker socket")));
  }

  @Test
  void anUnknownHostIsANotFound() throws Exception {
    when(hosts.requireConnected("dh-ghost")).thenThrow(new NoSuchElementException("unknown docker host: dh-ghost"));

    mvc.perform(get("/api/containers/dh-ghost/abc123/stats"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown docker host: dh-ghost"));
  }

  @Test
  void startStopAndRemoveAllResolveTheHostUrl() throws Exception {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);

    mvc.perform(post("/api/containers/dh-local/abc123/start")).andExpect(status().isOk());
    mvc.perform(post("/api/containers/dh-local/abc123/stop")).andExpect(status().isOk());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/containers/dh-local/abc123"))
        .andExpect(status().isOk());

    verify(docker).start(HOST, "abc123");
    verify(docker).stop(HOST, "abc123");
    verify(docker).remove(HOST, "abc123");
  }
}
