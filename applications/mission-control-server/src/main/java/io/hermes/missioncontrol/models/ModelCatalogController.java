package io.hermes.missioncontrol.models;

import io.hermes.missioncontrol.agents.ModelProviderRegistry;
import io.hermes.missioncontrol.credentials.CredentialService;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
public class ModelCatalogController {

  /** A typed key, or the id of a saved credential to take it from — one of the two. */
  public record LiveModelsRequest(String apiKey, @Size(max = 64) String credentialId) {}

  private final ModelCatalogService catalog;
  private final CredentialService credentials;

  public ModelCatalogController(ModelCatalogService catalog, CredentialService credentials) {
    this.catalog = catalog;
    this.credentials = credentials;
  }

  @GetMapping("/{provider}")
  public ModelCatalogDto configured(@PathVariable String provider) {
    return catalog.configured(provider);
  }

  @PostMapping("/{provider}")
  public ModelCatalogDto live(@PathVariable String provider, @RequestBody LiveModelsRequest request) {
    return catalog.live(provider, apiKey(provider, request));
  }

  /**
   * The key the provider is asked with. Same rule as the create-agent path: the variable a
   * credential is read under comes from the provider, not the client, so an id names which
   * value may be read and nothing more. A blank key is refused here rather than sent — it would
   * reach the provider as an unauthenticated request and burn a round trip to learn a 401.
   */
  private String apiKey(String provider, LiveModelsRequest request) {
    if (request.credentialId() != null && !request.credentialId().isBlank()) {
      String envVar = ModelProviderRegistry.envVar(provider);
      if (envVar == null) {
        throw new IllegalArgumentException("provider '" + provider + "' takes no API key");
      }
      return credentials.valueFor(request.credentialId(), envVar);
    }
    if (request.apiKey() == null || request.apiKey().isBlank()) {
      throw new IllegalArgumentException("apiKey or credentialId is required");
    }
    return request.apiKey().trim();
  }
}
