package io.hermes.missioncontrol.agents.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.McpServerDefinition;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretInput;
import io.hermes.missioncontrol.secrets.SecretRef;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** CRUD-edge behaviour: rename-collision guard and the no-secret-leak DTO mapping. */
class ProfileTemplateServiceTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  private final ProfileTemplateRepository repository = Mockito.mock(ProfileTemplateRepository.class);
  // a real cipher (dev key) — encryption/decryption is exercised end to end
  private final SecretCipher cipher = new SecretCipher("unit-test-key", "", true);
  // create/update never touch the docker-backed collaborators, so null is safe
  private final ProfileTemplateService service =
      TemplatesWiring.service(repository, cipher, null, null);

  private static UpsertProfileTemplateRequest request(String name, List<SecretInput> secrets) {
    return new UpsertProfileTemplateRequest(
        name, "", "desc", "ops", "anthropic", "claude-opus-4-8", "", "/opt/data",
        "soul", "memory", List.of(), List.of(), List.of(), List.of(), secrets);
  }

  @Test
  void updateRejectsRenameOntoAnExistingName() {
    ProfileTemplate existing = new ProfileTemplate(
        "pt-1", "old", "", "", "ops", "anthropic", "m", "", "", "", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));
    when(repository.existsByNameExcept("taken", "pt-1")).thenReturn(true);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> service.update("pt-1", request("taken", List.of())));
    assertTrue(e.getMessage().contains("already exists"));
    verify(repository, never()).update(any());
  }

  @Test
  void updateKeepingItsOwnNameProceeds() {
    ProfileTemplate existing = new ProfileTemplate(
        "pt-1", "ops", "", "", "ops", "anthropic", "m", "", "", "", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));
    when(repository.existsByNameExcept("ops", "pt-1")).thenReturn(false);

    service.update("pt-1", request("ops", List.of()));
    verify(repository).update(any());
  }

  @Test
  void storedSecretIsEncryptedAndNeverEchoedToTheClient() {
    when(repository.existsByName("ops")).thenReturn(false);

    ProfileTemplateDto dto = service.create(
        request("ops", List.of(new SecretInput("ANTHROPIC_API_KEY", "sk-ant-raw-secret"))));

    SecretRef ref = dto.secrets().get(0);
    assertEquals("ANTHROPIC_API_KEY", ref.key());
    assertTrue(ref.set(), "a value was supplied");
    assertTrue(ref.recoverable(), "value decrypts under the current key");
    // SecretRef exposes only key/set/recoverable — there is no field that could
    // carry the raw value or a suffix of it back to the client.
    assertFalse(ref.toString().contains("sk-ant"), "no secret material in the DTO");
  }

  @Test
  void aSecretNamingACredentialStoresThatCredentialsEnvelopeVerbatim() {
    // ciphertext to ciphertext: both stores are sealed under the same MC_SECRET_KEY, so a
    // blueprint can carry a key the editor never held and nothing decrypts on the way
    CredentialService credentials = Mockito.mock(CredentialService.class);
    ProfileTemplateService withVault =
        TemplatesWiring.service(repository, cipher, null, null, null, credentials);
    when(credentials.envelopeFor("cr-1", "ANTHROPIC_API_KEY"))
        .thenReturn(cipher.encrypt("sk-from-the-vault"));
    when(repository.existsByName("ops")).thenReturn(false);

    ProfileTemplateDto dto = withVault.create(request("ops",
        List.of(new SecretInput("ANTHROPIC_API_KEY", null, "cr-1"))));

    assertTrue(dto.secrets().get(0).set());
    assertTrue(dto.secrets().get(0).recoverable());
    ArgumentCaptor<ProfileTemplate> saved = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).insert(saved.capture());
    assertEquals("sk-from-the-vault", cipher.decrypt(saved.getValue().secrets().get(0).enc()));
  }

  @Test
  void aCredentialThisKeyCannotOpenIsRefusedRatherThanCopiedForward() {
    // carrying a dead envelope into a second row makes the loss look freshly stored, and the
    // operator only discovers it when a deploy writes nothing
    CredentialService credentials = Mockito.mock(CredentialService.class);
    ProfileTemplateService withVault =
        TemplatesWiring.service(repository, cipher, null, null, null, credentials);
    when(credentials.envelopeFor("cr-1", "ANTHROPIC_API_KEY"))
        .thenThrow(new ResourceConflictException("credential 'anthropic' cannot be decrypted"));
    when(repository.existsByName("ops")).thenReturn(false);

    assertThrows(ResourceConflictException.class, () -> withVault.create(request("ops",
        List.of(new SecretInput("ANTHROPIC_API_KEY", null, "cr-1")))));
    verify(repository, never()).insert(any());
  }

  @Test
  void aTypedSecretNeverReachesTheCredentialStore() {
    CredentialService credentials = Mockito.mock(CredentialService.class);
    ProfileTemplateService withVault =
        TemplatesWiring.service(repository, cipher, null, null, null, credentials);
    when(repository.existsByName("ops")).thenReturn(false);

    withVault.create(request("ops", List.of(new SecretInput("ANTHROPIC_API_KEY", "sk-typed"))));

    verifyNoInteractions(credentials);
  }

  @Test
  void aBlankCredentialIdFallsBackToTheTypedValue() {
    // an editor that sends "" rather than omitting the field must still be able to save a
    // typed key
    CredentialService credentials = Mockito.mock(CredentialService.class);
    ProfileTemplateService withVault =
        TemplatesWiring.service(repository, cipher, null, null, null, credentials);
    when(repository.existsByName("ops")).thenReturn(false);

    withVault.create(request("ops", List.of(new SecretInput("ANTHROPIC_API_KEY", "sk-typed", "  "))));

    verifyNoInteractions(credentials);
    ArgumentCaptor<ProfileTemplate> saved = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).insert(saved.capture());
    assertEquals("sk-typed", cipher.decrypt(saved.getValue().secrets().get(0).enc()));
  }

  @Test
  void createFromTemplateRollsBackProfileWhenBlueprintFails() {
    HermesProfiles profiles = TemplatesWiring.profilesMock();
    HermesSetup setup = Mockito.mock(HermesSetup.class);
    ProfileTemplateService ownedService =
        TemplatesWiring.service(repository, cipher, profiles, setup);
    ProfileTemplate template = new ProfileTemplate(
        "pt-1", "ops", "", "", "ops", "anthropic", "model", "", "", "soul", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(template));
    ProfileSpec create = new ProfileSpec(
        "cid", "ops", "anthropic", "model", null, null, null, null);
    doThrow(new RuntimeException("soul write failed"))
        .when(profiles).updateSoul(HOST, "cid", "ops", "soul");

    assertThrows(RuntimeException.class,
        () -> ownedService.createFromTemplate("pt-1", HOST, create));

    verify(profiles).createProfileBare(HOST, create);
    verify(profiles).delete(HOST, "cid", "ops");
    // and the profile stayed inside the creating window while the blueprint was layered on
    verify(profiles).whileCreating(eq("cid"), eq("ops"), any());
  }

  @Test
  void aProviderIsStoredAndServedUnderTheRegistrysCurrentKey() {
    // hermes v0.21.0 renamed API-key OpenAI to openai-api; a blueprint saved as `openai` before
    // that has to deploy under a name hermes still resolves, and pick the right editor option
    when(repository.existsByName(any())).thenReturn(false);
    ProfileTemplate legacy = new ProfileTemplate(
        "pt-1", "ops", "", "", "ops", "openai", "gpt-5.2", "", "", "", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(legacy));

    assertEquals("openai-api", service.get("pt-1").provider());

    service.create(new UpsertProfileTemplateRequest(
        "sre", "", "desc", "ops", "OpenAI", "gpt-5.2", "", "/opt/data", "", "",
        List.of(), List.of(), List.of(), List.of(), List.of()));
    ArgumentCaptor<ProfileTemplate> written = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).insert(written.capture());
    assertEquals("openai-api", written.getValue().provider());
  }

  @Test
  void catalogInputBecomesDetachedEncryptedSnapshot() {
    McpRegistryService registry = Mockito.mock(McpRegistryService.class);
    McpServerDto catalog = Mockito.mock(McpServerDto.class);
    when(catalog.id()).thenReturn("mcp-1");
    when(catalog.name()).thenReturn("Remote tools");
    when(catalog.kind()).thenReturn("external");
    when(catalog.transport()).thenReturn("http");
    when(catalog.connectionUrl()).thenReturn("https://tools.example.test/mcp");
    when(registry.definition("mcp-1")).thenReturn(catalog);
    when(registry.materializedHeaders("mcp-1"))
        .thenReturn(Map.of("Authorization", "Bearer raw-catalog-secret"));
    when(repository.existsByName("ops")).thenReturn(false);
    ProfileTemplateService catalogService =
        TemplatesWiring.service(repository, cipher, null, null, registry);
    UpsertProfileTemplateRequest input = new UpsertProfileTemplateRequest(
        "ops", "", "", "ops", "nous", "model", "", "/opt/data", "", "", List.of(), List.of(), List.of(),
        List.of(new McpServerSpec(
            "tools", null, null, null, null, true, "mcp-1", null, null)),
        List.of());

    ProfileTemplateDto response = catalogService.create(input);

    ArgumentCaptor<ProfileTemplate> stored = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).insert(stored.capture());
    McpServerSpec snapshot = stored.getValue().mcpServers().getFirst();
    assertEquals("tools", snapshot.name());
    assertEquals("http", snapshot.transport());
    assertEquals("https://tools.example.test/mcp", snapshot.url());
    assertEquals(null, snapshot.sourceServerId(), "the catalog link is input-only");
    assertFalse(snapshot.headers().getFirst().encryptedValue().contains("raw-catalog-secret"));
    assertEquals(null, response.mcpServers().getFirst().headers().getFirst().encryptedValue());
    assertFalse(response.toString().contains("raw-catalog-secret"));
  }

  @Test
  void laterTemplateUpdatePreservesSnapshotWithoutConsultingCatalog() {
    McpRegistryService registry = Mockito.mock(McpRegistryService.class);
    String encrypted = cipher.encrypt("Bearer retained-secret");
    McpServerSpec snapshot = new McpServerSpec(
        "tools", "http", "https://tools.example.test/mcp", null, null, true, null,
        List.of(), List.of(new TemplateMcpConfigValue("Authorization", encrypted)));
    ProfileTemplate existing = new ProfileTemplate(
        "pt-1", "ops", "", "", "ops", "nous", "model", "", "/opt/data", "", "",
        List.of(), List.of(snapshot), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));
    when(repository.existsByNameExcept("ops", "pt-1")).thenReturn(false);
    ProfileTemplateService catalogService =
        TemplatesWiring.service(repository, cipher, null, null, registry);
    UpsertProfileTemplateRequest input = new UpsertProfileTemplateRequest(
        "ops", "", "updated", "ops", "nous", "model", "", "/opt/data", "", "", List.of(), List.of(), List.of(),
        List.of(new McpServerSpec(
            "tools", "http", "https://tools.example.test/mcp", null, null, true)),
        List.of());

    catalogService.update("pt-1", input);

    ArgumentCaptor<ProfileTemplate> updated = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).update(updated.capture());
    String rotated = updated.getValue().mcpServers().getFirst().headers().getFirst().encryptedValue();
    assertNotEquals(encrypted, rotated);
    assertEquals("Bearer retained-secret", cipher.decrypt(rotated));
    verifyNoInteractions(registry);
  }

  @Test
  void applyingSnapshotDecryptsHeadersAndStdioEnvironmentOnlyAtRuntime() {
    HermesProfiles profiles = Mockito.mock(HermesProfiles.class);
    HermesSetup setup = Mockito.mock(HermesSetup.class);
    McpServerSpec network = new McpServerSpec(
        "remote", "http", "https://tools.example.test/mcp", null, null, true, null,
        List.of(), List.of(new TemplateMcpConfigValue(
            "Authorization", cipher.encrypt("Bearer runtime-token"))));
    McpServerSpec stdio = new McpServerSpec(
        "local", "stdio", null, "npx", "-y @acme/server", true, null,
        List.of(new TemplateMcpConfigValue("npm_config_token", cipher.encrypt("stdio-token"))),
        List.of());
    ProfileTemplate template = new ProfileTemplate(
        "pt-1", "ops", "", "", "ops", "nous", "model", "", "/opt/data", "", "",
        List.of(), List.of(network, stdio), List.of(), 1L, 1L);
    TemplatesWiring.applier(profiles, setup, cipher).layerOnto(template, HOST, "cid", "ops");

    ArgumentCaptor<McpServerDefinition> requests =
        ArgumentCaptor.forClass(McpServerDefinition.class);
    verify(profiles, Mockito.times(2))
        .addMcpServer(Mockito.eq(HOST), Mockito.eq("cid"), Mockito.eq("ops"), requests.capture());
    assertEquals(Map.of("Authorization", "Bearer runtime-token"),
        requests.getAllValues().getFirst().headers());
    assertEquals(Map.of("npm_config_token", "stdio-token"),
        requests.getAllValues().get(1).environment());
    verify(setup, never()).putEnv(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyList());
  }

  @Test
  void theCrudPathsReadWriteAndDeleteThroughTheRepository() {
    ProfileTemplate stored = new ProfileTemplate("pt-1", "ops", "", "", "ops", "anthropic", "m", "", "",
        "", "", List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findAll()).thenReturn(List.of(stored));
    when(repository.findById("pt-1")).thenReturn(Optional.of(stored));

    assertEquals(List.of("ops"), service.list().stream().map(ProfileTemplateDto::name).toList());
    assertEquals("ops", service.get("pt-1").name());
    service.delete("pt-1");
    verify(repository).delete("pt-1");
  }

  @Test
  void anUnknownTemplateIdIsANotFoundOnEveryPathThatTakesOne() {
    when(repository.findById("pt-nope")).thenReturn(Optional.empty());

    assertThrows(NoSuchElementException.class, () -> service.get("pt-nope"));
    assertThrows(NoSuchElementException.class, () -> service.update("pt-nope", request("ops", List.of())));
    assertThrows(NoSuchElementException.class,
        () -> service.deploy("pt-nope", HOST, "c1", "scout"));
  }

  @Test
  void aBlankCategoryBecomesGeneralAndOneTypedInAnyCaseIsFolded() {
    when(repository.existsByName(any())).thenReturn(false);

    service.create(new UpsertProfileTemplateRequest(
        "ops", "", "desc", "  ", "anthropic", "m", "", "/opt/data", "", "",
        List.of(), List.of(), List.of(), List.of(), List.of()));
    service.create(new UpsertProfileTemplateRequest(
        "sre", "", "desc", " Incident Response ", "anthropic", "m", "", "/opt/data", "", "",
        List.of(), List.of(), List.of(), List.of(), List.of()));

    ArgumentCaptor<ProfileTemplate> written = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository, times(2)).insert(written.capture());
    // the page builds its filter chips from these, so 'Ops' and 'ops' must not be two chips
    assertEquals(List.of("general", "incident response"),
        written.getAllValues().stream().map(ProfileTemplate::category).toList());
  }

  @Test
  void libraryAndGuideIdsAreTrimmedDedupedAndKeptInTheOperatorsOrder() {
    when(repository.existsByName(any())).thenReturn(false);

    service.create(new UpsertProfileTemplateRequest(
        "ops", "", "desc", "ops", "anthropic", "m", "", "/opt/data", "", "",
        List.of("web-research"), List.of(" s-2 ", "s-1", "s-2", "  "),
        java.util.Arrays.asList("g-1", null, "g-1"),   // List.of refuses a null; a stored row can carry one
        List.of(), List.of()));

    ArgumentCaptor<ProfileTemplate> written = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).insert(written.capture());
    assertEquals(List.of("web-research"), written.getValue().skills());
    assertEquals(List.of("s-2", "s-1"), written.getValue().librarySkillIds());
    assertEquals(List.of("g-1"), written.getValue().guideIds());
  }

  @Test
  void theIconTheEditorSentIsStoredAndABlankOneStaysBlank() {
    when(repository.existsByName(any())).thenReturn(false);

    service.create(new UpsertProfileTemplateRequest(
        "ops", "shield", "desc", "ops", "anthropic", "m", "", "/opt/data", "", "",
        List.of(), List.of(), List.of(), List.of(), List.of()));
    service.create(new UpsertProfileTemplateRequest(
        "sre", null, "desc", "ops", "anthropic", "m", "", "/opt/data", "", "",
        List.of(), List.of(), List.of(), List.of(), List.of()));

    ArgumentCaptor<ProfileTemplate> written = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository, times(2)).insert(written.capture());
    // never null: the column is read straight into a record the client serializes
    assertEquals(List.of("shield", ""),
        written.getAllValues().stream().map(ProfileTemplate::icon).toList());
  }

  @Test
  void editingAnExistingBlueprintRefilesItUnderTheCategoryTheEditorSent() {
    ProfileTemplate existing = new ProfileTemplate(
        "pt-1", "ops", "", "", "general", "anthropic", "m", "", "", "", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));
    when(repository.existsByNameExcept("ops", "pt-1")).thenReturn(false);

    service.update("pt-1", new UpsertProfileTemplateRequest(
        "ops", "", "desc", "Review", "anthropic", "m", "", "/opt/data", "", "",
        List.of(), List.of(), List.of(), List.of(), List.of()));

    ArgumentCaptor<ProfileTemplate> written = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).update(written.capture());
    assertEquals("review", written.getValue().category());
  }

  @Test
  void creatingRefusesANameThatIsAlreadyTaken() {
    when(repository.existsByName("ops")).thenReturn(true);

    assertEquals("a template named 'ops' already exists",
        assertThrows(IllegalArgumentException.class,
            () -> service.create(request("ops", List.of()))).getMessage());
    verify(repository, never()).insert(any());
  }

  @Test
  void creatingStoresTheRecordAndAnswersWithItsDto() {
    when(repository.existsByName("ops")).thenReturn(false);

    ProfileTemplateDto created = service.create(request("ops", List.of()));

    assertEquals("ops", created.name());
    assertTrue(created.id().startsWith("pt-"));
    verify(repository).insert(any(ProfileTemplate.class));
  }
}
