package io.hermes.missioncontrol.agents.api;

/** @param problem what {@code hermes auth list} says is wrong with the pooled credential
 *  behind this key — {@code auth failed invalid_api_key (401) …} — or null when nothing is */
public record ApiKeyStatusDto(
    String label,
    String envVar,
    boolean set,
    String masked,
    String problem) {
}
