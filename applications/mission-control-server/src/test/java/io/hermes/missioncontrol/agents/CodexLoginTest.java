package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.hermes.missioncontrol.agents.api.CodexLoginDto;
import org.junit.jupiter.api.Test;

/** Hermes' device-flow transcript, as {@code hermes auth add openai-codex --no-browser} prints it
 *  (captured from nousresearch/hermes-agent, 2026-10-10), read into the dashboard's states. */
class CodexLoginTest {

  private static final String WAITING = """
      Signing in to OpenAI Codex...
      (Hermes creates its own session — won't affect Codex CLI or VS Code)

      To continue, follow these steps:

        1. Open this URL in your browser:
           \u001B[94mhttps://auth.openai.com/codex/device\u001B[0m

        2. Enter this code:
           \u001B[94mOY4P-34AG8\u001B[0m

      Waiting for sign-in... (press Ctrl+C to cancel)
      """;

  @Test
  void aWaitingLoginCarriesTheUrlAndCodeWithoutColour() {
    assertEquals(
        new CodexLoginDto("pending", "https://auth.openai.com/codex/device", "OY4P-34AG8", null),
        CodexLogin.parse(WAITING));
  }

  @Test
  void hermesExitingZeroIsSuccess() {
    assertEquals(new CodexLoginDto("succeeded", null, null, null),
        CodexLogin.parse(WAITING + "Login successful!\nmc-exit=0\n"));
  }

  @Test
  void aFailureReportsHermesLastLine() {
    assertEquals(new CodexLoginDto("failed", null, null, "Login timed out after 15 minutes."),
        CodexLogin.parse(WAITING + "Login timed out after 15 minutes.\nmc-exit=1\n"));
  }

  @Test
  void aRunThatDiedWithoutItsExitLineIsAFailureToRetry() {
    assertEquals("failed", CodexLogin.parse(WAITING + "mc-exit=gone\n").state());
  }

  @Test
  void noTranscriptMeansNoLoginWasStarted() {
    assertEquals(new CodexLoginDto("none", null, null, null), CodexLogin.parse(""));
  }

  @Test
  void aRunStillStartingIsPendingWithNothingToShowYet() {
    assertEquals(new CodexLoginDto("pending", null, null, null),
        CodexLogin.parse("Signing in to OpenAI Codex...\n  2. Enter this code:\n"));
  }

  @Test
  void aSilentFailureStillSaysSomething() {
    assertEquals("hermes exited without saying why", CodexLogin.parse("mc-exit=2\n").message());
  }

  @Test
  void anUnreadableTranscriptMeansNone() {
    assertEquals("none", CodexLogin.parse(null).state());
  }
}
