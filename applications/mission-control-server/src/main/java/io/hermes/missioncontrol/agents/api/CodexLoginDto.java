package io.hermes.missioncontrol.agents.api;

/**
 * Where a container's OpenAI Codex (ChatGPT) device login stands.
 *
 * @param state   {@code none} (never started, or the container was recreated), {@code pending}
 *                (waiting for the operator to enter {@code code} at {@code url}),
 *                {@code succeeded} or {@code failed}
 * @param url     the page to enter the code on, while pending
 * @param code    the one-time device code, while pending
 * @param message hermes' last line when the login failed, else null
 */
public record CodexLoginDto(String state, String url, String code, String message) {
}
