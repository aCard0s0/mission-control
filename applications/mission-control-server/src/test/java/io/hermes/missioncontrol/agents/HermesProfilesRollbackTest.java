package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HermesProfilesRollbackTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  @Test
  void baseConfigurationFailureDeletesNewProfile() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "ops", "anthropic", "model", null, null, null, null);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""))
        .thenThrow(new RuntimeException("config failed"))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(RuntimeException.class, () -> profiles.createProfileBare(HOST, spec));

    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "delete", "ops", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }

  @Test
  void aMixedCaseNameIsCreatedAndRolledBackUnderTheNameHermesFoldsItTo() {
    // hermes lower-cases the name on create, so `Coach` lives at profiles/coach: a delete that
    // said Coach found no directory and left the half-built profile behind
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "Coach", "anthropic", "model", null, null, null, null);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""))
        .thenThrow(new RuntimeException("config failed"))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(RuntimeException.class, () -> profiles.createProfileBare(HOST, spec));

    assertEquals("coach", spec.name());
    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "create", "coach"),
        "Hermes command", true, false, Duration.ofSeconds(30));
    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "delete", "coach", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }

  @Test
  void aProfileCountsAsCreatingFromTheFirstExecUntilTheCreateIsOver() {
    // the directory exists after the first exec, so this is the window the inventory must
    // not report the profile in, and a stop would roll it back
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "ops", "anthropic", "model", null, null, null, null);
    List<List<String>> seenDuring = new ArrayList<>();
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenAnswer(call -> {
          seenDuring.add(profiles.creating("cid"));
          return new DockerExecService.ExecResult(0, "", "");
        });

    assertThrows(IllegalStateException.class, () -> profiles.createProfileBare(HOST, spec));

    assertEquals(false, seenDuring.isEmpty());
    seenDuring.forEach(seen -> assertEquals(List.of("ops"), seen));
    assertEquals(List.of(), profiles.creating("cid"));
    assertEquals(List.of(), profiles.creating("other"));
  }

  @Test
  void aSpecWithNoNameIsRefusedBeforeAnyFold() {
    assertThrows(IllegalArgumentException.class,
        () -> new ProfileSpec("cid", null, "anthropic", "model", null, null, null, null));
  }

  @Test
  void configuringAnExistingProfileNeverCreatesOrDeletesIt() {
    // the default profile a container deploy hands a blueprint: the image made it, the deploy
    // owns the rollback, so a failure here is thrown and nothing profile-shaped is undone
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "default", "anthropic", "model", null, null, null, null);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenThrow(new RuntimeException("config failed"));

    assertThrows(RuntimeException.class, () -> profiles.configureModel(HOST, spec));

    verify(dockerExec, never()).runAsUser(
        eq(HOST), eq("cid"), eq("hermes"), eq(List.of("hermes", "profile", "create", "default")),
        anyString(), anyBoolean(), anyBoolean(), any(Duration.class));
    verify(dockerExec, never()).runAsUser(
        eq(HOST), eq("cid"), eq("hermes"), eq(List.of("hermes", "profile", "delete", "default", "--yes")),
        anyString(), anyBoolean(), anyBoolean(), any(Duration.class));
  }

  @Test
  void aCallerCanHoldTheCreatingWindowOpenPastTheBareCreate() {
    // a blueprint deploy layers a dozen writes onto the profile after createProfileBare returns;
    // the window has to cover them, and the bare create's own close must not end it early
    HermesProfiles profiles = AgentsWiring.profiles(mock(DockerExecService.class));

    List<String> seenInside = profiles.whileCreating("cid", "ops", () -> {
      // createProfileBare's own window for the same profile, opened and closed while ours is up
      profiles.whileCreating("cid", "ops", () -> null);
      return profiles.creating("cid");
    });

    assertEquals(List.of("ops"), seenInside);
    assertEquals(List.of(), profiles.creating("cid"));
  }

  @Test
  void theWindowClosesWhenTheWorkThrows() {
    HermesProfiles profiles = AgentsWiring.profiles(mock(DockerExecService.class));

    assertThrows(IllegalStateException.class, () -> profiles.whileCreating("cid", "ops", () -> {
      throw new IllegalStateException("apply failed");
    }));

    assertEquals(List.of(), profiles.creating("cid"));
  }

  @Test
  void configWriteThatLeavesNoModelDeletesNewProfile() {
    // every exec "succeeds" but config.yaml reads back empty — the state that
    // produced a profile whose auxiliary chain had no provider to resolve to
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "ops", "anthropic", "model", null, null, null, null);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(IllegalStateException.class, () -> profiles.createProfileBare(HOST, spec));

    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "delete", "ops", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }
}
