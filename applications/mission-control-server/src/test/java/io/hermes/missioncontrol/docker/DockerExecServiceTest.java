package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class DockerExecServiceTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  @Test
  void sensitiveFailureNeverContainsCommandOutput() {
    ContainerCommandFailedException failure = DockerExecService.commandFailure(
        "write profile environment", 1, true, "", "failed for sk-ant-secret-value");

    assertTrue(failure.getMessage().contains("write profile environment"));
    assertFalse(failure.getMessage().contains("sk-ant-secret-value"));
  }

  @Test
  void aFailureDetailPrefersStderrButFallsBackToStdout() {
    // this one line is all the operator gets in the error toast, so it has to carry the
    // daemon's own reason rather than whatever the command printed on its way out
    String both = DockerExecService.commandFailure(
        "write profile config", 1, false,
        "usage: hermes config set <key> <value>", "permission denied: /opt/data/config.json")
        .getMessage();
    assertTrue(both.contains("permission denied: /opt/data/config.json"));
    assertFalse(both.contains("usage: hermes config set"));

    // plenty of Hermes subcommands report their failure on stdout and leave stderr empty
    String stdoutOnly = DockerExecService.commandFailure(
        "read gateway status", 1, false, "gateway is not running", "  \n ").getMessage();
    assertTrue(stdoutOnly.contains("gateway is not running"));

    // a command that fails silently must still name the operation and its status
    String silent = DockerExecService.commandFailure("delete profile", 7, false, "", "").getMessage();
    assertTrue(silent.contains("delete profile"));
    assertTrue(silent.contains("exit code 7"));
  }

  @Test
  void aVeryLongFailureDetailIsTruncatedSoOneBadCommandCannotFloodTheResponse() {
    String flood = "cannot write /opt/data/profiles/ops.json: " + "x".repeat(2000);

    String message = DockerExecService.commandFailure("write profile config", 1, false, "", flood)
        .getMessage();

    // the whole message is serialised into the HTTP error body the dashboard renders;
    // a couple of kilobytes of daemon spam buries the operation that actually failed
    assertTrue(message.length() < 600, "failure detail must stay bounded, was " + message.length());
    // truncating must still leave the head of the reason, not just the operation name
    assertTrue(message.contains("write profile config"));
    assertTrue(message.contains("cannot write /opt/data/profiles/ops.json"));
  }

  @Test
  void explicitHermesUserIsAppliedToDockerExec() {
    DockerClients clients = stubbedClients(0);

    new DockerExecService(clients).runAsUser(
        HOST, "cid", "hermes", List.of("true"), "test",
        true, false, Duration.ofSeconds(1));

    verify(create).withUser("hermes");
  }

  @Test
  void theContainerStopsACommandShortlyAfterTheWaitForItEnds() {
    DockerClients clients = stubbedClients(0);

    new DockerExecService(clients).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "status"), "read status",
        true, false, Duration.ofSeconds(30));

    // the daemon cannot cancel an exec: without the bound, a command nobody waits for runs on
    verify(create).withCmd("timeout", "-k", "5", "35", "hermes", "status");
  }

  @Test
  void theSixArgumentOverloadRunsWithTheDaemonDefaultUser() {
    DockerClients clients = stubbedClients(0);

    DockerExecService.ExecResult result = new DockerExecService(clients).run(
        HOST, "cid", List.of("hermes", "gateway", "status"), "read gateway status",
        true, false, Duration.ofSeconds(1));

    assertEquals(0, result.exitCode());
    // callers that do not name a user get the image's own default; forcing hermes here
    // would break every exec against a container that has no such account
    verify(create, never()).withUser(anyString());
  }

  @Test
  void anUnknownExitCodeIsNotReportedAsSuccess() {
    // the daemon has no exit status: the exec never completed, or the inspection raced it.
    // Reporting 0 tells HermesProfiles that a config write or a profile delete succeeded
    // when nothing confirms it did.
    DockerClients clients = stubbedClients(null);

    assertThrows(UpstreamUnavailableException.class, () -> new DockerExecService(clients).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "delete"), "delete profile",
        true, false, Duration.ofSeconds(1)));
  }

  @Test
  void anUncheckedExecWithNoExitStatusReportsFailureRatherThanZero() {
    // fileExists/dirExists read the exit code as a boolean and pass check=false; an
    // unknown status must read as "no", not as "yes"
    DockerClients clients = stubbedClients(null);

    DockerExecService.ExecResult result = new DockerExecService(clients).runAsUser(
        HOST, "cid", "hermes", List.of("test", "-f", "/x"), "check file",
        false, false, Duration.ofSeconds(1));

    assertNotEquals(0, result.exitCode());
  }

  @Test
  void aNonZeroExitCodeIsReturnedRatherThanThrownWhenCheckIsFalse() {
    DockerClients clients = stubbedClients(1);

    DockerExecService.ExecResult result = new DockerExecService(clients).runAsUser(
        HOST, "cid", "hermes", List.of("test", "-f", "/absent"), "check file",
        false, false, Duration.ofSeconds(1));

    assertEquals(1, result.exitCode());
  }

  @Test
  void aCheckedNonZeroExitThrowsATypeTheHttpLayerCanMapToAClientError() {
    // the type is the contract: as a bare RuntimeException this reached the advice's
    // catch-all, so every hermes CLI rejection was answered 500 with a stack trace at ERROR
    DockerClients clients = stubbedClients(1, frame(StreamType.STDERR, "unknown skill: reserch"));

    ContainerCommandFailedException failure = assertThrows(ContainerCommandFailedException.class,
        () -> new DockerExecService(clients).runAsUser(
            HOST, "cid", "hermes", List.of("hermes", "skills", "install", "reserch"),
            "install skill", true, false, Duration.ofSeconds(1)));

    assertTrue(failure.getMessage().contains("unknown skill: reserch"), failure.getMessage());
  }

  @Test
  void stdoutAndStderrAreCapturedIntoSeparateBuffers() {
    // the daemon interleaves the two streams frame by frame; callers parse stdout as data
    // (JSON, a file listing) and only ever show stderr as a diagnostic
    DockerClients clients = stubbedClients(0,
        frame(StreamType.STDOUT, "{\"profiles\":"),
        frame(StreamType.STDERR, "warning: TERM unset"),
        frame(StreamType.STDOUT, "[\"ops\"]}"),
        frame(StreamType.STDERR, "\nwarning: no tty"));

    DockerExecService.ExecResult result = new DockerExecService(clients).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "list"), "list profiles",
        true, false, Duration.ofSeconds(1));

    assertEquals("{\"profiles\":[\"ops\"]}", result.stdout());
    assertEquals("warning: TERM unset\nwarning: no tty", result.stderr());
  }

  @Test
  void aCommandThatOutlivesItsTimeoutIsReportedAsUpstreamUnavailable() {
    DockerClients clients = stubbedClients(0);
    // a command still running when the budget expires: the callback never completes, so
    // awaitCompletion reports false rather than throwing
    doAnswer(invocation -> invocation.getArgument(0)).when(start).exec(any());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> new DockerExecService(clients).runAsUser(
            HOST, "cid", "hermes", List.of("hermes", "gateway", "status"),
            "read gateway status", true, false, Duration.ofMillis(1)));

    // a hung daemon is a 503, and the operator needs to know which call hung
    assertTrue(failure.getMessage().contains("read gateway status"));
  }

  @Test
  void aSensitiveExecFailureNeverLeaksTheCommandOutput() {
    DockerClients clients = stubbedClients(0);
    doThrow(new RuntimeException("cannot exec: ANTHROPIC_API_KEY=sk-ant-secret-value"))
        .when(create).exec();

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> new DockerExecService(clients).runAsUser(
            HOST, "cid", "hermes", List.of("hermes", "profile", "env", "set"),
            "write profile environment", true, true, Duration.ofSeconds(1)));

    assertTrue(failure.getMessage().contains("write profile environment"));
    assertFalse(failure.getMessage().contains("sk-ant-secret-value"));
    // the daemon's own words are kept on the cause, where only the server log sees them
    assertTrue(failure.getCause().getMessage().contains("sk-ant-secret-value"));
  }

  private ExecCreateCmd create;
  private ExecStartCmd start;
  private InspectExecCmd inspect;

  private static Frame frame(StreamType stream, String text) {
    return new Frame(stream, text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The whole docker-java exec chain, answering {@code exitCode} from the inspection and
   * pushing {@code frames} through the callback before it completes.
   */
  private DockerClients stubbedClients(Integer exitCode, Frame... frames) {
    DockerClients clients = mock(DockerClients.class);
    DockerClient client = mock(DockerClient.class);
    create = mock(ExecCreateCmd.class, Answers.RETURNS_SELF);
    ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
    start = mock(ExecStartCmd.class);
    inspect = mock(InspectExecCmd.class);
    InspectExecResponse inspected = mock(InspectExecResponse.class);
    // an exec attach is silent while the command runs, so the service uses the streaming
    // client — one with no socket timeout to cut the caller's budget short
    when(clients.streamingForUrl("unix:///sock")).thenReturn(client);
    when(client.execCreateCmd("cid")).thenReturn(create);
    when(create.exec()).thenReturn(created);
    when(created.getId()).thenReturn("exec-id");
    when(client.execStartCmd("exec-id")).thenReturn(start);
    when(start.exec(any())).thenAnswer(invocation -> {
      ResultCallback<Frame> callback = invocation.getArgument(0);
      for (Frame frame : frames) {
        callback.onNext(frame);
      }
      callback.onComplete();
      return callback;
    });
    when(client.inspectExecCmd("exec-id")).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getExitCode()).thenReturn(exitCode);
    return clients;
  }

  // ── what counts as the connection breaking ──────────────────────────────

  @Test
  void anIoOrDockerFailureIsTransportAndAnythingElseIsNot() {
    // the caller retries a transport failure and surfaces everything else as a defect
    assertTrue(DockerExecService.isTransportFailure(new java.io.IOException("broken pipe")));
    assertTrue(DockerExecService.isTransportFailure(
        new com.github.dockerjava.api.exception.DockerException("boom", 500)));
    assertTrue(DockerExecService.isTransportFailure(
        new com.github.dockerjava.api.exception.DockerClientException("no transport")));
    // wrapped a level or two down, which is how docker-java surfaces most of them
    assertTrue(DockerExecService.isTransportFailure(
        new RuntimeException("wrapped", new IllegalStateException("deeper",
            new java.io.IOException("connection reset")))));

    assertFalse(DockerExecService.isTransportFailure(new IllegalArgumentException("bad argument")));
    assertFalse(DockerExecService.isTransportFailure(new RuntimeException("no cause")));
  }

  @Test
  void aSelfReferentialCauseChainTerminatesRatherThanSpinning() {
    // a cause that points at itself would otherwise loop forever inside the walk
    RuntimeException looped = new RuntimeException("looped") {
      @Override
      public synchronized Throwable getCause() {
        return this;
      }
    };

    assertFalse(DockerExecService.isTransportFailure(looped));
  }

  @Test
  void aBrokenStreamIsReportedAsTheDaemonGoingAwayNotAsADefect() {
    // awaitCompletion rethrows whatever broke the stream in a bare RuntimeException; left alone
    // that reaches the advice's catch-all as a 500 with a stack trace at ERROR
    DockerClients clients = stubbedClients(0);
    doThrow(new RuntimeException("stream broke",
        new java.net.SocketTimeoutException("read timed out")))
        .when(start).exec(any());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> new DockerExecService(clients).runAsUser(HOST, "cid", "hermes",
            List.of("hermes", "gateway", "status"), "read gateway status",
            true, false, Duration.ofSeconds(1)));

    assertTrue(failure.getMessage().contains("lost its connection to the daemon"), failure.getMessage());
  }

  @Test
  void aFailureThatIsNotTheTransportKeepsItsOwnType() {
    // an IllegalArgumentException from our own argv is a defect, and must not be dressed up
    // as a daemon outage
    DockerClients clients = stubbedClients(0);
    doThrow(new IllegalArgumentException("bad exec spec")).when(start).exec(any());

    assertThrows(IllegalArgumentException.class,
        () -> new DockerExecService(clients).runAsUser(HOST, "cid", "hermes",
            List.of("true"), "check file", false, false, Duration.ofSeconds(1)));
  }

  @Test
  void aSensitiveCommandNeverLeaksItsOutputThroughAStreamFailureEither() {
    DockerClients clients = stubbedClients(0);
    doThrow(new RuntimeException("stream broke: ANTHROPIC_API_KEY=sk-ant-secret-value"))
        .when(start).exec(any());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> new DockerExecService(clients).runAsUser(HOST, "cid", "hermes",
            List.of("hermes", "profile", "env", "set"), "write profile environment",
            true, true, Duration.ofSeconds(1)));

    assertTrue(failure.getMessage().contains("write profile environment"));
    assertFalse(failure.getMessage().contains("sk-ant-secret-value"));
  }

  @Test
  void anInspectionThatFailsOnASensitiveCommandIsAlsoRedacted() {
    DockerClients clients = stubbedClients(0);
    doThrow(new RuntimeException("cannot inspect: ANTHROPIC_API_KEY=sk-ant-secret-value"))
        .when(inspect).exec();

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> new DockerExecService(clients).runAsUser(HOST, "cid", "hermes",
            List.of("hermes", "profile", "env", "set"), "write profile environment",
            true, true, Duration.ofSeconds(1)));

    assertFalse(failure.getMessage().contains("sk-ant-secret-value"));

    // the same failure on an ordinary command keeps its own type
    DockerClients plain = stubbedClients(0);
    doThrow(new IllegalStateException("cannot inspect")).when(inspect).exec();
    assertThrows(IllegalStateException.class,
        () -> new DockerExecService(plain).runAsUser(HOST, "cid", "hermes",
            List.of("true"), "check file", false, false, Duration.ofSeconds(1)));
  }

  @Test
  void aFrameWithNoPayloadIsIgnoredRatherThanCountedAsOutput() {
    DockerClients clients = stubbedClients(0,
        new Frame(StreamType.STDOUT, null), frame(StreamType.STDOUT, "real output"));

    DockerExecService.ExecResult result = new DockerExecService(clients).runAsUser(
        HOST, "cid", "hermes", List.of("true"), "read", true, false,
        Duration.ofSeconds(1));

    assertEquals("real output", result.stdout());
  }
}
