package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An agent's credentials and the setup report that merges them with {@code hermes status}. */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}")
class AgentSetupController {

  private final HermesSetup setup;
  private final HostService hosts;
  private final CredentialService credentials;

  AgentSetupController(HermesSetup setup, HostService hosts, CredentialService credentials) {
    this.setup = setup;
    this.hosts = hosts;
    this.credentials = credentials;
  }

  /** Container-level auth-provider status (e.g. Nous Portal OAuth login), read
   *  from the default profile's `hermes status`. OAuth tokens live at the
   *  container level (auth.json), so this reflects whether a newly-created agent
   *  on this container can reach providers like Nous before it even exists —
   *  surfaced in the create-agent modal. */
  @GetMapping("/auth-providers")
  public List<AuthProviderDto> authProviders(
      @PathVariable String hostId, @PathVariable String containerId) {
    return setup.setup(hosts.requireConnected(hostId), containerId, "default").authProviders();
  }

  @GetMapping("/{name}/setup")
  public AgentSetupDto setup(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return setup.setup(hosts.requireConnected(hostId), containerId, name);
  }

  @PutMapping("/{name}/env")
  public AgentSetupDto putEnv(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody SetEnvRequest request) {
    return setup.putEnv(
        hosts.requireConnected(hostId), containerId, name, resolved(request.entries()));
  }

  @PostMapping("/{name}/env/init")
  public AgentSetupDto initEnv(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return setup.initEnv(hosts.requireConnected(hostId), containerId, name);
  }

  /**
   * Turns every entry that names a saved credential into one that carries its value.
   *
   * <p>Here rather than in {@code HermesSetup}, so the writer keeps its collaborators and the
   * template deploy path — which builds {@code EnvEntry} directly and runs no bean validation
   * — is untouched. This is also the layer the value must not travel above: it arrives as an
   * id from the browser and leaves as cleartext bound for a file.
   */
  private List<EnvEntry> resolved(List<EnvEntry> entries) {
    if (entries == null) return List.of();
    List<EnvEntry> out = new ArrayList<>(entries.size());
    for (EnvEntry entry : entries) {
      if (entry == null) {
        out.add(null);   // HermesSetup names the null entry; swallowing it here hides a bad body
      } else if (entry.credentialId() == null || entry.credentialId().isBlank()) {
        // rebuilt rather than passed through, so a blank id an editor sent instead of omitting
        // the field does not travel down as one
        out.add(new EnvEntry(entry.key(), entry.value()));
      } else {
        out.add(new EnvEntry(entry.key(), credentials.valueFor(entry.credentialId(), entry.key())));
      }
    }
    return out;
  }

  /** A batch of {@code .env} writes. A blank value removes the variable, unless the entry names
   *  a credential to take one from. */
  public record SetEnvRequest(@Valid List<@Valid EnvEntry> entries) {
  }
}
