package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import io.hermes.missioncontrol.skills.GuideDeploy;
import io.hermes.missioncontrol.skills.SkillDeployer;
import io.hermes.missioncontrol.skills.SkillGuideRepository;
import io.hermes.missioncontrol.skills.SkillRepository;
import java.util.function.Supplier;
import org.mockito.Mockito;

/**
 * Builds the template collaborator graph the way Spring does, for tests that drive a whole flow
 * through {@link ProfileTemplateService}.
 *
 * <p>The same role {@code AgentsWiring} and {@code McpWiring} play in their packages. Its
 * existence is why {@link ProfileTemplateService} no longer needs a second constructor that
 * passed {@code null} for the MCP registry: a test that does not reach the registry passes null
 * here instead, where it stays a statement about that test rather than a shape production code
 * has to support — and {@link TemplateMcpSnapshots} no longer needs the null check that reported
 * it to an operator as a 503.
 *
 * <p>A null collaborator is deliberate where a test asserts a path never reaches it: a mock
 * would silently no-op, while null fails loudly.
 */
final class TemplatesWiring {

  private TemplatesWiring() {}

  /**
   * A mocked {@link HermesProfiles} whose {@code whileCreating} still runs the work handed to
   * it. Same reason {@code AgentsWiring.mockFiles} does this for {@code serialized}: the deploy
   * flows now run inside that window, and a bare mock would return null without creating or
   * applying anything — every deploy assertion would then pass vacuously or fail on a null.
   */
  @SuppressWarnings("unchecked")
  static HermesProfiles profilesMock() {
    HermesProfiles profiles = Mockito.mock(HermesProfiles.class);
    Mockito.when(profiles.whileCreating(Mockito.anyString(), Mockito.anyString(), Mockito.any(Supplier.class)))
        .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
    return profiles;
  }

  /** The skill library and guide collaborators a deploy resolves its references through. */
  record Libraries(
      SkillRepository skills, SkillDeployer skillDeployer,
      SkillGuideRepository guides, GuideDeploy guideDeploy) {

    /** For the flows whose template names neither a library skill nor a guide. */
    static final Libraries NONE = new Libraries(null, null, null, null);
  }

  static ProfileTemplateService service(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup,
      McpRegistryService registry,
      CredentialService credentials,
      Libraries libraries) {
    TemplateSecrets secrets = new TemplateSecrets(new SecretsAtRest(cipher));
    return new ProfileTemplateService(
        repository,
        secrets,
        applier(profiles, setup, secrets, libraries),
        new TemplateMcpSnapshots(registry, secrets),
        profiles,
        setup,
        credentials);
  }

  static ProfileTemplateService service(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup,
      McpRegistryService registry,
      CredentialService credentials) {
    return service(repository, cipher, profiles, setup, registry, credentials, Libraries.NONE);
  }

  /** For the flows where no secret names a saved credential. */
  static ProfileTemplateService service(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup,
      McpRegistryService registry) {
    return service(repository, cipher, profiles, setup, registry, null);
  }

  /** For the flows that never reach the catalog. */
  static ProfileTemplateService service(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup) {
    return service(repository, cipher, profiles, setup, null, null);
  }

  /** For the deploys that resolve library skills or guides, and nothing else. */
  static ProfileTemplateService service(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup,
      Libraries libraries) {
    return service(repository, cipher, profiles, setup, null, null, libraries);
  }

  /**
   * The applier on its own, for the two tests that assert what layering onto a caller-owned
   * profile does. They used to reach it through {@code ProfileTemplateService.applyExisting},
   * which nothing in production called — the service's own flows go through
   * {@link TemplateApplier#deployNew} and {@link TemplateApplier#layerOnto} directly.
   */
  static TemplateApplier applier(HermesProfiles profiles, HermesSetup setup, SecretCipher cipher) {
    return applier(profiles, setup, new TemplateSecrets(new SecretsAtRest(cipher)), Libraries.NONE);
  }

  private static TemplateApplier applier(
      HermesProfiles profiles, HermesSetup setup, TemplateSecrets secrets, Libraries libraries) {
    return new TemplateApplier(
        profiles, setup, secrets,
        libraries.skills(), libraries.skillDeployer(), libraries.guides(), libraries.guideDeploy());
  }
}
