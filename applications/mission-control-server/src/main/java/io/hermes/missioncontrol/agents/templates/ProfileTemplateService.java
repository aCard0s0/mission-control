package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.ModelProviderRegistry;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.common.IdList;
import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.secrets.SecretInput;
import io.hermes.missioncontrol.secrets.SecretRef;
import io.hermes.missioncontrol.secrets.StoredSecret;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reusable {@link ProfileTemplate}s: their CRUD, the API view of them, and capturing one
 * from a running agent.
 *
 * <p>The three collaborators are injected rather than built here. Building them meant this
 * constructor also took their dependencies — a cipher it never used — and it forced a second
 * constructor that passed {@code null} for the MCP registry so a CRUD-only test could skip it,
 * which in turn needed a null check in {@link TemplateMcpSnapshots} that reported a test wiring
 * accident to the operator as a 503.
 *
 * <p>The two halves that are not about the record itself belong to a collaborator:
 * {@link TemplateApplier} writes a template onto a live profile and owns the rollback rule
 * that goes with it, and {@link TemplateMcpSnapshots} decides what an MCP entry becomes when
 * it is stored. {@link TemplateSecrets} is how all three reach the trust boundary — secrets are
 * encrypted on the way in (kept on blank input), never returned to the client (only a
 * set/recoverable flag), and decrypted only when applied to a live agent. The rules themselves
 * are {@link io.hermes.missioncontrol.secrets.SecretsAtRest}, shared with the MCP catalog.
 */
@Service
public class ProfileTemplateService {

  /** Where a blueprint lands when it was not filed by hand. */
  static final String DEFAULT_CATEGORY = "general";
  /** Snapshots of a live agent file themselves, so they group without being named. */
  static final String CAPTURED_CATEGORY = "captured";

  /** The one env-key rule, shared with the two other places that check it. */
  private static final Pattern ENV_KEY = Pattern.compile(EnvEntry.KEY_PATTERN);
  /** Generous ceiling for a single secret value (API keys/tokens are short). */
  private static final int MAX_SECRET_LEN = 65_536;

  private final ProfileTemplateRepository repository;
  private final TemplateSecrets secrets;
  private final TemplateApplier applier;
  private final TemplateMcpSnapshots snapshots;
  private final HermesProfiles profiles;
  private final HermesSetup setup;
  private final CredentialService credentials;

  public ProfileTemplateService(
      ProfileTemplateRepository repository,
      TemplateSecrets secrets,
      TemplateApplier applier,
      TemplateMcpSnapshots snapshots,
      HermesProfiles profiles,
      HermesSetup setup,
      CredentialService credentials) {
    this.repository = repository;
    this.secrets = secrets;
    this.applier = applier;
    this.snapshots = snapshots;
    this.profiles = profiles;
    this.setup = setup;
    this.credentials = credentials;
  }

  // ── CRUD ─────────────────────────────────────────────────────────────────

  public List<ProfileTemplateDto> list() {
    return repository.findAll().stream().map(this::toDto).toList();
  }

  public ProfileTemplateDto get(String id) {
    return toDto(require(id));
  }

  @Transactional
  public ProfileTemplateDto create(UpsertProfileTemplateRequest request) {
    if (repository.existsByName(request.name())) {
      throw new IllegalArgumentException("a template named '" + request.name() + "' already exists");
    }
    long now = System.currentTimeMillis();
    ProfileTemplate template = build(newId(), request, null, now, now);
    repository.insert(template);
    return toDto(template);
  }

  @Transactional
  public ProfileTemplateDto update(String id, UpsertProfileTemplateRequest request) {
    ProfileTemplate existing = require(id);
    // mirror create()'s friendly 400 instead of letting the UNIQUE(name)
    // constraint surface as an opaque DB error when renaming onto another template
    if (repository.existsByNameExcept(request.name(), id)) {
      throw new IllegalArgumentException("a template named '" + request.name() + "' already exists");
    }
    ProfileTemplate template = build(id, request, existing, existing.createdAt(), System.currentTimeMillis());
    repository.update(template);
    return toDto(template);
  }

  public void delete(String id) {
    repository.delete(id);
  }

  // ── apply / deploy ─────────────────────────────────────────────────────────

  /** Create a new agent in {@code containerId} from a template and apply it. */
  public AgentProfileDto deploy(String id, DockerHostRef host, String containerId, String name) {
    return applier.deployNew(require(id), host, containerId, name);
  }

  /**
   * Create the caller-configured base profile and apply a template as one owned operation.
   *
   * <p>The profile comes from the caller's own model settings rather than the template's, so
   * it is created here and not by {@link TemplateApplier#deployNew} — but this call owns it,
   * so a failure while applying rolls it back.
   */
  public AgentProfileDto createFromTemplate(String id, DockerHostRef host, ProfileSpec spec) {
    ProfileTemplate template = require(id);
    // the profile stays inside the creating window until the blueprint has landed on it: the
    // Agents page would otherwise list it — and a shell opened on it would start hermes — while
    // its key is still a few writes away
    return profiles.whileCreating(spec.containerId(), spec.name(), () -> {
      profiles.createProfileBare(host, spec);
      try {
        return applier.layerOnto(template, host, spec.containerId(), spec.name());
      } catch (RuntimeException failure) {
        applier.rollback(host, spec.containerId(), spec.name(), failure);
        throw failure;
      }
    });
  }

  /**
   * Applies a template, model settings included, to a new container's {@code default} profile.
   *
   * <p>The one profile this code never creates: the image initializes it on first boot, so
   * there is no {@code hermes profile create} to run and nothing profile-shaped to roll back —
   * the container deploy that calls this rolls the whole container back on a failure instead.
   */
  public AgentProfileDto applyToDefault(String id, DockerHostRef host, String containerId) {
    return applier.configureAndApply(require(id), host, containerId, "default");
  }

  // ── capture from a running agent ───────────────────────────────────────────
  // No @Transactional: this reads the agent over docker (slow) before its single
  // atomic insert, and the datasource pool is size 1 — holding the sole connection
  // across those execs would serialize the whole app.
  public ProfileTemplateDto captureFromAgent(
      DockerHostRef host, String containerId, String agentName, String templateName) {
    AgentProfileDto agent = profiles.get(host, containerId, agentName);
    AgentSetupDto agentSetup = setup.setup(host, containerId, agentName);

    List<String> skills = agent.skills().stream()
        .filter(SkillDto::enabled)
        .map(SkillDto::name)
        .toList();
    List<McpServerSpec> mcp = agent.mcp().stream()
        .map(m -> new McpServerSpec(
            m.name(), m.transport(), m.url(), m.command(), m.args(), !"disabled".equals(m.status())))
        .toList();
    // we cannot read raw .env values back — capture which keys are set, blank value
    List<StoredSecret> captured = agentSetup.apiKeys().stream()
        .filter(ApiKeyStatusDto::set)
        .map(k -> new StoredSecret(k.envVar(), null))
        .toList();

    long now = System.currentTimeMillis();
    String name = uniqueName((templateName == null || templateName.isBlank())
        ? agentName + "-template" : templateName);
    ProfileTemplate template = new ProfileTemplate(
        newId(), name, "", "Captured from " + agentName, CAPTURED_CATEGORY,
        ModelProviderRegistry.normalizeKey(agent.provider()), agent.model(), "", agent.cwd(),
        agent.soul(), agent.memoryMd(), skills, mcp, captured, now, now);
    repository.insert(template);
    return toDto(template);
  }

  // ── record assembly ────────────────────────────────────────────────────────

  /**
   * Blank becomes {@link #DEFAULT_CATEGORY}, and the value is folded to lower case — the same
   * rule the prompt library uses, and for the same reason: the page builds its filter chips
   * from the stored values, so {@code Ops} and {@code ops} must not become two chips.
   */
  private static String category(String raw) {
    return raw == null || raw.isBlank() ? DEFAULT_CATEGORY : raw.trim().toLowerCase(Locale.ROOT);
  }

  private ProfileTemplate build(
      String id, UpsertProfileTemplateRequest r, ProfileTemplate existing, long created, long updated) {
    return new ProfileTemplate(
        id, r.name(), nz(r.icon()), nz(r.description()), category(r.category()),
        ModelProviderRegistry.normalizeKey(r.provider()), nz(r.model()),
        nz(r.baseUrl()), nz(r.cwd()), nz(r.soul()), nz(r.memory()),
        nz(r.skills()), IdList.normalize(r.librarySkillIds()), IdList.normalize(r.guideIds()),
        snapshots.materialize(r.mcpServers(), existing),
        storedSecrets(r.secrets(), existing), created, updated);
  }

  /**
   * Encrypts the supplied secrets, keeping the stored value wherever the input is blank — the
   * editor never receives ciphertext, so blank is how it says "unchanged".
   *
   * <p>Three states, not two. A key with a stored envelope is re-sealed under the current key.
   * A key captured from a running agent has no envelope at all — {@code captureFromAgent}
   * records which variables were set, never their values — and that placeholder is kept as-is
   * so it keeps prompting the operator for the value. A key with neither is refused: the
   * request asked to store a secret and sent none, and dropping it silently was how a template
   * came to be deployed without a credential it appeared to carry.
   */
  private List<StoredSecret> storedSecrets(List<SecretInput> input, ProfileTemplate existing) {
    Map<String, String> prior = new HashMap<>();   // enc may be null for capture-only placeholder keys
    if (existing != null) {
      for (StoredSecret s : existing.secrets()) {
        prior.put(s.key(), s.enc());
      }
    }
    List<StoredSecret> stored = new ArrayList<>();
    for (SecretInput s : nz(input)) {
      if (s == null || s.key() == null || s.key().isBlank()) {
        continue;   // an editor row nobody filled in is not a secret
      }
      String key = s.key().trim();
      if (!ENV_KEY.matcher(key).matches()) {
        throw new IllegalArgumentException("invalid secret key: " + key);
      }
      if (s.credentialId() != null && !s.credentialId().isBlank()) {
        // ciphertext to ciphertext: both sides are sealed under the same MC_SECRET_KEY, so this
        // copy never decrypts. envelopeFor refuses one this key cannot open rather than
        // carrying the loss forward into a second row where it looks freshly stored.
        stored.add(new StoredSecret(key, credentials.envelopeFor(s.credentialId(), key)));
        continue;
      }
      String value = s.value();
      if (value != null && value.length() > MAX_SECRET_LEN) {
        throw new IllegalArgumentException("secret value too large for " + key);
      }
      boolean placeholder = (value == null || value.isBlank())
          && prior.containsKey(key) && prior.get(key) == null;
      stored.add(placeholder
          ? new StoredSecret(key, null)
          : new StoredSecret(key, secrets.encryptOrKeep(value, prior.get(key), key)));
    }
    return stored;
  }

  private ProfileTemplateDto toDto(ProfileTemplate t) {
    // never echo secret material (not even a suffix) to the client — surface only
    // whether a value is stored and whether it still decrypts with the current key
    List<SecretRef> refs = t.secrets().stream()
        .map(s -> new SecretRef(s.key(), s.enc() != null, secrets.isRecoverable(s.enc())))
        .toList();
    List<McpServerSpec> mcp = t.mcpServers().stream().map(TemplateSecrets::redacted).toList();
    return new ProfileTemplateDto(
        t.id(), t.name(), t.icon(), t.description(), t.category(),
        // served under the registry's current key: a blueprint saved as `openai` before hermes
        // renamed it has to pick the right option in the editor and deploy under a name hermes
        // still resolves, and the next save then stores it that way for good
        ModelProviderRegistry.normalizeKey(t.provider()), t.model(), t.baseUrl(), t.cwd(),
        t.soul(), t.memory(), t.skills(), t.librarySkillIds(), t.guideIds(), mcp, refs, t.createdAt(), t.updatedAt());
  }

  private ProfileTemplate require(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown template: " + id));
  }

  private String uniqueName(String base) {
    String name = base;
    int n = 2;
    while (repository.existsByName(name)) {
      name = base + "-" + n++;
    }
    return name;
  }

  private String newId() {
    return "pt-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static <T> List<T> nz(List<T> value) {
    return value == null ? List.of() : value;
  }
}
