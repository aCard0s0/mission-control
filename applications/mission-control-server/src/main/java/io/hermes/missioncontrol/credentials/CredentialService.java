package io.hermes.missioncontrol.credentials;

import io.hermes.missioncontrol.common.Text;
import io.hermes.missioncontrol.credentials.api.CredentialDto;
import io.hermes.missioncontrol.credentials.api.CredentialEntryDto;
import io.hermes.missioncontrol.credentials.api.CredentialEntryInput;
import io.hermes.missioncontrol.credentials.api.UpsertCredentialRequest;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Saved credentials, and the trust boundary around them.
 *
 * <p>The four rules a stored secret obeys are {@link SecretsAtRest}'s and are called, not
 * copied. {@code mcp/McpConfigStore} and {@code agents/templates/TemplateSecrets} each
 * implemented those rules over their own value record and drifted apart; the class javadoc on
 * {@code SecretsAtRest} records what that cost. What lives here instead is this store's own
 * shape: which entries are secret at all, and the three forms a resolved credential is asked
 * for.
 *
 * <p>Two resolvers, because the callers need two different things and each is about to hand the
 * answer to something that must not receive a blank:
 *
 * <ul>
 *   <li>{@link #valueFor} — one entry in the clear, for an agent's {@code .env} and for the
 *       create-agent path.
 *   <li>{@link #envelopeFor} — one entry still sealed, for a blueprint's secrets list. Same
 *       {@code MC_SECRET_KEY} on both sides, so that copy never decrypts.
 * </ul>
 *
 * <p>No resolver is reachable from a controller that returns its result. A picker posts an id
 * and the value goes to a file, a profile or another row — never back to the browser.
 */
@Service
public class CredentialService {

  private final CredentialRepository repository;
  private final SecretsAtRest secrets;

  public CredentialService(CredentialRepository repository, SecretsAtRest secrets) {
    this.repository = repository;
    this.secrets = secrets;
  }

  // ── CRUD ───────────────────────────────────────────────────────────────────

  public List<CredentialDto> list() {
    return repository.findAll().stream().map(this::toDto).toList();
  }

  public CredentialDto create(UpsertCredentialRequest request) {
    long now = System.currentTimeMillis();
    Credential created = build(
        "cr-" + UUID.randomUUID().toString().substring(0, 8), request, null, now, now);
    repository.insert(created);
    return toDto(created);
  }

  public CredentialDto update(String id, UpsertCredentialRequest request) {
    Credential existing = require(id);
    Credential updated = build(id, request, existing, existing.createdAt(), System.currentTimeMillis());
    repository.update(updated);
    return toDto(updated);
  }

  /** Idempotent, and reaches nothing. Every key this credential ever filled stays where it was
   *  written — see the note on {@link Credential} for why that is the whole design. */
  public void delete(String id) {
    repository.delete(id);
  }

  // ── the boundary ───────────────────────────────────────────────────────────

  /**
   * Encrypts a submitted credential, keeping the stored envelope wherever a secret entry's
   * value is blank.
   *
   * <p>A blank secret with nothing to keep throws rather than storing an empty string:
   * {@link SecretsAtRest#sealOrKeep} treats that as the caller asking to store a secret and
   * supplying none, and the alternative reports a save that did not happen.
   */
  private Credential build(
      String id, UpsertCredentialRequest request, Credential existing, long createdAt, long now) {
    Map<String, CredentialEntry> prior = byKey(existing);
    List<CredentialEntry> entries = new ArrayList<>();
    for (CredentialEntryInput input : request.entries() == null ? List.<CredentialEntryInput>of() : request.entries()) {
      if (input == null) continue;
      String key = input.key().trim();
      String stored = input.secret()
          ? secrets.sealOrKeep(input.value(), priorEnvelope(prior, key), key)
          : input.value() == null ? "" : input.value();
      entries.add(new CredentialEntry(key, stored, input.secret()));
    }
    return new Credential(id, request.name().trim(), Text.blankToNull(request.description()),
        List.copyOf(entries), createdAt, now);
  }

  /**
   * The envelope a blank submission may carry forward, or null when there is none to keep.
   *
   * <p>An entry that was not marked secret holds readable plaintext, so promoting it to a
   * secret's envelope would hand back something that was never encrypted. Same rule, and the
   * same reason, as {@code McpConfigStore.priorEnvelope}.
   */
  private static String priorEnvelope(Map<String, CredentialEntry> prior, String key) {
    CredentialEntry old = prior.get(key);
    return old == null || !old.secret() ? null : old.value();
  }

  /** Secret values are reported as set/recoverable and never returned; a plain entry's value
   *  is, because a home channel or a base URL is nothing to hide and a picker that could not
   *  show it would be useless for the pair it belongs to. */
  private CredentialDto toDto(Credential credential) {
    List<CredentialEntryDto> entries = credential.entries().stream().map(entry -> {
      if (!entry.secret()) {
        return new CredentialEntryDto(entry.key(), entry.value(), false, true, true);
      }
      boolean set = entry.value() != null && !entry.value().isBlank();
      return new CredentialEntryDto(
          entry.key(), null, true, set, set && secrets.isRecoverable(entry.value()));
    }).toList();
    return new CredentialDto(credential.id(), credential.name(), credential.description(),
        entries, credential.createdAt(), credential.updatedAt());
  }

  // ── resolvers ──────────────────────────────────────────────────────────────

  /**
   * One entry in the clear.
   *
   * <p>Fails the whole write rather than substituting a blank for an entry it cannot open — the
   * caller is about to put this somewhere an operator will read as configured.
   */
  public String valueFor(String id, String key) {
    Credential credential = require(id);
    CredentialEntry entry = entry(credential, key);
    if (!entry.secret()) return entry.value() == null ? "" : entry.value();
    assertRecoverable(credential, entry);
    return secrets.open(entry.value());
  }

  /**
   * The first usable saved value for one variable, in the clear, or empty when no credential
   * holds one it can open.
   *
   * <p>For a caller with no operator at hand to pick from the dropdown — the model-catalog
   * refresh reads a keyed provider's list with whichever saved key fits, and any working one
   * will do. The value goes to the provider's own API, never back to a browser.
   */
  public Optional<String> anyValueFor(String key) {
    for (Credential credential : repository.findAll()) {
      for (CredentialEntry entry : credential.entries()) {
        if (!entry.key().equals(key) || entry.value() == null || entry.value().isBlank()) continue;
        if (!entry.secret()) return Optional.of(entry.value());
        if (secrets.isRecoverable(entry.value())) return Optional.of(secrets.open(entry.value()));
      }
    }
    return Optional.empty();
  }

  /**
   * One entry still sealed, for a store that holds envelopes rather than values.
   *
   * <p>Only a stored secret has one, which is all the blueprint picker offers — so a plain
   * entry reaching here is a hand-made call, and it gets the same refusal an unopenable
   * envelope does rather than a branch that seals plaintext on the way past.
   */
  public String envelopeFor(String id, String key) {
    Credential credential = require(id);
    CredentialEntry entry = entry(credential, key);
    assertRecoverable(credential, entry);
    return entry.value();
  }

  // ── lookups ────────────────────────────────────────────────────────────────

  private Credential require(String id) {
    return repository.find(id)
        .orElseThrow(() -> new NoSuchElementException("unknown credential: " + id));
  }

  /** A credential that does not hold the key the caller needs is a bad request, not an empty
   *  answer: the picker offers only credentials that do, so this is a stale page or a hand-made
   *  call, and writing a blank over a working key is the outcome worth refusing. */
  private static CredentialEntry entry(Credential credential, String key) {
    for (CredentialEntry entry : credential.entries()) {
      if (entry.key().equals(key)) return entry;
    }
    throw new IllegalArgumentException(
        "credential '" + credential.name() + "' has no entry for " + key);
  }

  private void assertRecoverable(Credential credential, CredentialEntry entry) {
    if (entry.value() == null || entry.value().isBlank()) {
      throw new ResourceConflictException(
          "credential '" + credential.name() + "' has no value for " + entry.key());
    }
    if (!secrets.isRecoverable(entry.value())) {
      throw new ResourceConflictException("credential '" + credential.name() + "' cannot be "
          + "decrypted (check MC_SECRET_KEY) — re-enter " + entry.key());
    }
  }

  private static Map<String, CredentialEntry> byKey(Credential credential) {
    Map<String, CredentialEntry> map = new LinkedHashMap<>();
    if (credential != null) {
      for (CredentialEntry entry : credential.entries()) map.put(entry.key(), entry);
    }
    return map;
  }
}
