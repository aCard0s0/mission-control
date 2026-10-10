package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.CodexLoginDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Drives {@code hermes auth add openai-codex}, the ChatGPT-subscription login, from the dashboard.
 *
 * <p>The login is a device flow: hermes prints a URL and a one-time code, then polls OpenAI for up
 * to 15 minutes until someone enters that code in a browser, and only then writes the token into
 * the container's {@code auth.json}. No request can wait that long, so the command is started
 * detached ({@code setsid}, its own process group) with its output in a file under {@code /tmp},
 * and {@link #status} reads that file back. Nothing is held in the JVM — the container is the
 * only record, so a restart of Mission Control loses nothing and a recreated container reads as
 * {@code none}.
 *
 * <p>One login per container: starting again kills the previous process group first, so repeated
 * clicks cannot pile up pollers (see {@code DockerExecService#boundedInContainer} for what a pile
 * of abandoned execs did to a 2 GB container).
 */
@Service
public class CodexLogin {

  /** Kills the last run, starts a fresh one, and waits (≤ 20 s) until hermes shows its code. */
  static final String START = """
      d=/tmp/mc-codex-login
      mkdir -p "$d"
      [ -s "$d/pid" ] && kill -TERM -"$(cat "$d/pid")" 2>/dev/null
      rm -f "$d/out" "$d/pid"
      setsid sh -c 'echo $$ >"$0/pid"; hermes auth add openai-codex --no-browser; echo "mc-exit=$?"' "$d" </dev/null >"$d/out" 2>&1 &
      i=0
      while [ $i -lt 40 ] && ! grep -qE 'Waiting for sign-in|mc-exit=' "$d/out" 2>/dev/null; do sleep 0.5; i=$((i+1)); done
      cat "$d/out"
      """;

  /** The run's output, plus {@code mc-exit=gone} when it died without reaching its own exit line
   *  — killed, or the container restarted under it. */
  static final String READ = """
      d=/tmp/mc-codex-login
      [ -f "$d/out" ] || exit 0
      cat "$d/out"
      grep -q 'mc-exit=' "$d/out" || kill -0 "$(cat "$d/pid" 2>/dev/null)" 2>/dev/null || echo 'mc-exit=gone'
      """;

  private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");
  private static final Pattern URL = Pattern.compile("https://\\S+");
  private static final Pattern EXIT = Pattern.compile("mc-exit=(\\S+)");

  private final HermesContainerFiles files;

  public CodexLogin(HermesContainerFiles files) {
    this.files = files;
  }

  public CodexLoginDto start(DockerHostRef host, String containerId) {
    return parse(files.exec(host, containerId, List.of("sh", "-c", START)).stdout());
  }

  public CodexLoginDto status(DockerHostRef host, String containerId) {
    return parse(files.exec(host, containerId, List.of("sh", "-c", READ)).stdout());
  }

  /** Reads hermes' device-flow transcript ({@code hermes_cli/auth_codex.py}). */
  static CodexLoginDto parse(String output) {
    String text = ANSI.matcher(output == null ? "" : output).replaceAll("");
    if (text.isBlank()) return new CodexLoginDto("none", null, null, null);

    Matcher exit = EXIT.matcher(text);
    if (exit.find()) {
      if ("0".equals(exit.group(1))) return new CodexLoginDto("succeeded", null, null, null);
      String message = "gone".equals(exit.group(1))
          ? "the login stopped before it finished — start it again"
          : lastLine(text.substring(0, exit.start()));
      return new CodexLoginDto("failed", null, null, message);
    }

    Matcher url = URL.matcher(text);
    return new CodexLoginDto(
        "pending", url.find() ? url.group() : null, lineAfter(text, "Enter this code:"), null);
  }

  private static String lineAfter(String text, String marker) {
    List<String> lines = text.lines().map(String::trim).toList();
    for (int i = 0; i + 1 < lines.size(); i++) {
      if (lines.get(i).endsWith(marker) && !lines.get(i + 1).isEmpty()) return lines.get(i + 1);
    }
    return null;
  }

  private static String lastLine(String text) {
    List<String> lines = text.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
    return lines.isEmpty() ? "hermes exited without saying why" : lines.getLast();
  }
}
