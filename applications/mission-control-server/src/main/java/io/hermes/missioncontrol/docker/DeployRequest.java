package io.hermes.missioncontrol.docker;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import jakarta.validation.Valid;

/**
 * @param version image tag to deploy; null or blank means 'latest'. Constrained exactly
 *     like {@link UpdateContainerRequest#version()} — the same value reaches the same
 *     daemon, and without the rule a typo here is reported as a 502 daemon failure only
 *     after the managed volume has already been created.
 * @param memoryMb memory ceiling; null takes {@link ContainerResources#BASELINE}. Bounded
 *     below by the vendor's stated minimum rather than by Docker's, so a deploy cannot
 *     quietly produce an agent the vendor documents as too small to run.
 * @param cpus CPU ceiling in cores, fractions allowed; null takes the baseline.
 * @param defaultTemplateId a profile template to apply to the {@code default} profile once the
 *     gateway is ready — its model settings, key, soul, memory, skills, MCP servers and guides.
 *     Null leaves the default agent as the image initializes it. Resolved by the controller,
 *     which is where the agents package is reachable from; this package only carries the id.
 */
public record DeployRequest(
    @NotBlank String hostId,
    // Docker's rule for a container name, which coincides with the profile-name rule
    // today and is free to stop doing so — not ProfileSpec.NAME_PATTERN
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*", message = "invalid container name") String name,
    @Pattern(regexp = "|[A-Za-z0-9_][A-Za-z0-9._-]{0,127}", message = "invalid image tag") String version,
    @Size(max = 50) List<@Pattern(
        regexp = "default|[a-z0-9][a-z0-9_-]{0,63}",
        message = "invalid profile name") String> profiles,
    @Min(ContainerResources.MIN_MEMORY_MB) @Max(ContainerResources.MAX_MEMORY_MB)
    Integer memoryMb,
    @DecimalMin("1.0") @DecimalMax("64.0") Double cpus,
    @Valid @Size(max = 32) List<HostAccess.PortMapping> ports,
    @Valid @Size(max = 64) List<HostAccess.EnvVar> env,
    @Valid @Size(max = 16) List<HostAccess.Mount> mounts,
    @Size(max = 64) String defaultTemplateId) {

  /** A deploy that leaves the default agent as the image makes it. */
  public DeployRequest(
      String hostId, String name, String version, List<String> profiles, Integer memoryMb,
      Double cpus, List<HostAccess.PortMapping> ports, List<HostAccess.EnvVar> env,
      List<HostAccess.Mount> mounts) {
    this(hostId, name, version, profiles, memoryMb, cpus, ports, env, mounts, null);
  }

  /** True when a blueprint is to be applied to the default agent. */
  public boolean hasDefaultTemplate() {
    return defaultTemplateId != null && !defaultTemplateId.isBlank();
  }

  /**
   * What the operator asked to open to the host. The path rules are checked here rather than
   * at binding time, so a refused mount answers 400 with its reason instead of a parse error.
   */
  public HostAccess hostAccess() {
    return new HostAccess(ports, env, mounts);
  }

  /** What this deploy should run under: what it asked for, or the recommendation. */
  public ContainerResources resources() {
    return ContainerResources.orBaseline(memoryMb, cpus);
  }
}
