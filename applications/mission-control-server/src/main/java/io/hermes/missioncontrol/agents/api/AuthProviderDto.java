package io.hermes.missioncontrol.agents.api;

/** @param providerKey the {@code ModelProviderRegistry} key this login serves, or null when the
 *  picker cannot point a profile at it */
public record AuthProviderDto(
    String label,
    boolean ok,
    String status,
    String hint,
    String providerKey) {
}
