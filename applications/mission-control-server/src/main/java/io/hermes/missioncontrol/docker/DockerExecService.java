package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Bounded non-interactive {@code docker exec} with argument-safe failures. */
@Service
public class DockerExecService {

  public record ExecResult(int exitCode, String stdout, String stderr) {}

  /**
   * Stand-in status for an exec the daemon reported no exit code for. Non-zero, so the
   * unchecked callers that use an exit code as a boolean ({@code fileExists},
   * {@code dirExists}) read "no" rather than "yes" when the answer is unknown.
   */
  public static final int EXIT_STATUS_UNAVAILABLE = -1;

  /** How long past the caller's wait a command may run — see {@link #boundedInContainer}. */
  private static final int KILL_GRACE_SECONDS = 5;

  private final DockerClients clients;

  public DockerExecService(DockerClients clients) {
    this.clients = clients;
  }

  public ExecResult run(
      DockerHostRef host,
      String containerId,
      List<String> command,
      String operation,
      boolean check,
      boolean sensitive,
      Duration timeout) {
    return runAsUser(host, containerId, null, command, operation, check, sensitive, timeout);
  }

  /** Runs a bounded exec as a specific container user. A null/blank user keeps
   * Docker's default user; Hermes profile mutations pass {@code hermes} so
   * files remain readable by the supervised gateway processes. */
  public ExecResult runAsUser(
      DockerHostRef host,
      String containerId,
      String user,
      List<String> command,
      String operation,
      boolean check,
      boolean sensitive,
      Duration timeout) {
    return runAsUser(host, containerId, user, command, operation, check, sensitive, timeout, null);
  }

  /**
   * As {@link #runAsUser(DockerHostRef, String, String, List, String, boolean, boolean,
   * Duration)}, with {@code stdin} fed to the command over the attached stream.
   *
   * <p>This is how a file body larger than one argument reaches the container: Linux caps a
   * single argv word at 131071 bytes, and the daemon refuses the exec outright past it. The
   * command has to read <em>exactly</em> the bytes it is given ({@code head -c "$n"}), because
   * the transport never half-closes the hijacked connection — a reader that waits for EOF
   * waits for the timeout instead.
   */
  public ExecResult runAsUser(
      DockerHostRef host,
      String containerId,
      String user,
      List<String> command,
      String operation,
      boolean check,
      boolean sensitive,
      Duration timeout,
      byte[] stdin) {
    // streaming: an exec attach is silent for as long as the command runs, so a socket
    // timeout here would cap every caller's budget at the transport's ceiling
    DockerClient client = clients.streamingForUrl(host.url());
    ExecCreateCmdResponse exec;
    try {
      var create = client.execCreateCmd(containerId)
          .withAttachStdout(true)
          .withAttachStderr(true)
          .withAttachStdin(stdin != null)
          .withCmd(boundedInContainer(command, timeout));
      if (user != null && !user.isBlank()) create.withUser(user);
      exec = create.exec();
    } catch (ConflictException notRunning) {
      // The daemon refuses an exec in a container that is not running. Translated here so
      // callers can recognise the case without importing docker-java: the sensitive wrap
      // below would otherwise flatten it into an opaque 503, and an untranslated one made
      // the agents package depend on this client's exception hierarchy.
      throw new ContainerNotRunningException(
          operation + " needs a running container: " + containerId, notRunning);
    } catch (RuntimeException e) {
      if (sensitive) throw new UpstreamUnavailableException(operation + " failed", e);
      throw e;
    }

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        if (frame.getPayload() == null) return;
        if (frame.getStreamType() == StreamType.STDERR) stderr.writeBytes(frame.getPayload());
        else stdout.writeBytes(frame.getPayload());
      }
    };

    boolean finished;
    try {
      var start = client.execStartCmd(exec.getId());
      if (stdin != null) start.withStdIn(new ByteArrayInputStream(stdin));
      finished = start.exec(callback)
          .awaitCompletion(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException(operation + " interrupted", e);
    } catch (RuntimeException e) {
      if (sensitive) throw new UpstreamUnavailableException(operation + " failed", e);
      // awaitCompletion rethrows whatever broke the stream (via throwFirstError), wrapping a
      // checked cause such as SocketTimeoutException in a bare RuntimeException. Left alone
      // that reaches the advice's catch-all as a 500 — reporting the daemon going away as a
      // Mission Control defect, complete with a stack trace at ERROR.
      if (isTransportFailure(e)) {
        throw new UpstreamUnavailableException(operation + " lost its connection to the daemon", e);
      }
      throw e;
    } finally {
      try {
        callback.close();
      } catch (Exception ignored) { }
    }
    if (!finished) throw new UpstreamUnavailableException(operation + " timed out");

    Integer inspected;
    try {
      inspected = client.inspectExecCmd(exec.getId()).exec().getExitCode();
    } catch (RuntimeException e) {
      if (sensitive) throw new UpstreamUnavailableException(operation + " failed", e);
      throw e;
    }
    String out = stdout.toString(StandardCharsets.UTF_8);
    String err = stderr.toString(StandardCharsets.UTF_8);
    if (inspected == null) {
      // the daemon has no exit status for this exec — it never completed, or the
      // inspection raced it. Treating that as 0 tells every caller the command
      // succeeded, which for a config write or a profile delete means reporting a
      // mutation that may not have happened.
      if (check) throw new UpstreamUnavailableException(operation + " exit status unavailable");
      return new ExecResult(EXIT_STATUS_UNAVAILABLE, out, err);
    }
    int exitCode = inspected;
    if (check && exitCode != 0) {
      throw commandFailure(operation, exitCode, sensitive, out, err);
    }
    return new ExecResult(exitCode, out, err);
  }

  /**
   * {@code command} behind coreutils {@code timeout}, so the container stops it once we stop
   * waiting. The daemon has no call that cancels an exec: a command we gave up on ran to the
   * end, and a poll that kept timing out kept adding one more. A create dialog asking for
   * {@code hermes status} every three seconds left a 2 GB container running over a hundred of
   * them, OOM-killing at random, and its {@code hermes profile create} timed out behind them.
   *
   * <p>A few seconds past the wait, so the caller still reads "timed out" rather than the exit
   * code {@code timeout} answers with; {@code -k} follows with SIGKILL for a command that
   * ignores the TERM. Every exec here targets a Hermes image, which ships GNU coreutils.
   */
  static String[] boundedInContainer(List<String> command, Duration timeout) {
    String grace = String.valueOf(KILL_GRACE_SECONDS);
    String limit = String.valueOf(Math.max(1, timeout.toSeconds()) + KILL_GRACE_SECONDS);
    List<String> argv = new ArrayList<>(List.of("timeout", "-k", grace, limit));
    argv.addAll(command);
    return argv.toArray(new String[0]);
  }

  /** True for a failure that is the connection breaking rather than a defect in this code. */
  static boolean isTransportFailure(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.io.IOException
          || cause instanceof com.github.dockerjava.api.exception.DockerException
          || cause instanceof com.github.dockerjava.api.exception.DockerClientException) {
        return true;
      }
      if (cause.getCause() == cause) break;
    }
    return false;
  }

  /**
   * The failure a non-zero exit becomes. Typed rather than a bare {@code RuntimeException}, which
   * no advice matched and the HTTP catch-all therefore answered as a 500 defect — see
   * {@link ContainerCommandFailedException}.
   */
  static ContainerCommandFailedException commandFailure(
      String operation, int exitCode, boolean sensitive, String stdout, String stderr) {
    if (sensitive) {
      return new ContainerCommandFailedException(
          operation + " failed with exit code " + exitCode);
    }
    String detail = stderr.trim().isEmpty() ? stdout.trim() : stderr.trim();
    if (detail.isEmpty()) detail = "exit code " + exitCode;
    if (detail.length() > 500) detail = detail.substring(0, 500);
    return new ContainerCommandFailedException(operation + " failed: " + detail);
  }
}
