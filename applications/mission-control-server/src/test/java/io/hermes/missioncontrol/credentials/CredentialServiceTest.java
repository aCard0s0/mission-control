package io.hermes.missioncontrol.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.credentials.api.CredentialDto;
import io.hermes.missioncontrol.credentials.api.CredentialEntryDto;
import io.hermes.missioncontrol.credentials.api.CredentialEntryInput;
import io.hermes.missioncontrol.credentials.api.UpsertCredentialRequest;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The credential store's trust boundary, over real sqlite.
 *
 * <p>The rules being pinned here are {@link SecretsAtRest}'s, reached through a fourth caller.
 * What matters is that this store calls them rather than reimplementing them: the two stores
 * that did reimplement them drifted, and a blank submission became a 400 in one and a silent
 * data loss in the other.
 *
 * <p>The ciphers are static: {@link SecretCipher} runs 210k PBKDF2 iterations per construction,
 * which would otherwise be the whole cost of this suite.
 */
class CredentialServiceTest {

  private static final SecretCipher CIPHER = new SecretCipher("test-secret", "", false);
  /** A different key: anything CIPHER wrote is unrecoverable to this one. */
  private static final SecretCipher OTHER_KEY = new SecretCipher("a-different-secret", "", false);

  private SqliteTestDatabase database;
  private CredentialRepository repository;
  private CredentialService service;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new CredentialRepository(database.jdbc(), new ObjectMapper());
    service = new CredentialService(repository, new SecretsAtRest(CIPHER));
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static UpsertCredentialRequest request(String name, CredentialEntryInput... entries) {
    return new UpsertCredentialRequest(name, "the production key", List.of(entries));
  }

  private static CredentialEntryInput secret(String key, String value) {
    return new CredentialEntryInput(key, value, true);
  }

  private static CredentialEntryInput plain(String key, String value) {
    return new CredentialEntryInput(key, value, false);
  }

  private static CredentialEntryDto entryOf(CredentialDto dto, String key) {
    return dto.entries().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
  }

  private CredentialEntry stored(String id, String key) {
    return repository.find(id).orElseThrow().entries().stream()
        .filter(e -> e.key().equals(key)).findFirst().orElseThrow();
  }

  // ── storing ────────────────────────────────────────────────────────────────

  @Test
  void aSecretIsEncryptedAtRestAndAPlainEntryIsNot() {
    CredentialDto saved = service.create(request("telegram ops",
        secret("TELEGRAM_BOT_TOKEN", "bot-live-1234"),
        plain("TELEGRAM_HOME_CHANNEL", "#ops")));

    CredentialEntry token = stored(saved.id(), "TELEGRAM_BOT_TOKEN");
    assertFalse(token.value().contains("bot-live-1234"), "the token is stored in the clear");
    assertEquals("bot-live-1234", CIPHER.decrypt(token.value()));
    assertEquals("#ops", stored(saved.id(), "TELEGRAM_HOME_CHANNEL").value());
  }

  @Test
  void aBlankSecretOnANewCredentialIsRefusedRatherThanStoredEmpty() {
    // the caller asked to save a credential and supplied none; dropping it reports a success
    // that did not happen
    assertThrows(IllegalArgumentException.class,
        () -> service.create(request("anthropic", secret("ANTHROPIC_API_KEY", ""))));
  }

  @Test
  void aBlankSecretInAnUpdateCarriesTheStoredValueForward() {
    // the editor never receives ciphertext, so blank is how it says "unchanged"
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-original")));

    service.update(saved.id(), request("anthropic", secret("ANTHROPIC_API_KEY", "")));

    assertEquals("sk-original", CIPHER.decrypt(stored(saved.id(), "ANTHROPIC_API_KEY").value()));
  }

  @Test
  void keepingASecretResealsItUnderTheCurrentKey() {
    // rule 2: a save is a rotation opportunity, so MC_SECRET_KEY_PREVIOUS stops being
    // load-bearing once each secret has been through one save
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-original")));
    String first = stored(saved.id(), "ANTHROPIC_API_KEY").value();

    service.update(saved.id(), request("anthropic", secret("ANTHROPIC_API_KEY", "")));

    assertNotEquals(first, stored(saved.id(), "ANTHROPIC_API_KEY").value());
    assertEquals("sk-original", CIPHER.decrypt(stored(saved.id(), "ANTHROPIC_API_KEY").value()));
  }

  @Test
  void anEnvelopeThisKeyCannotOpenIsPreservedByAnUnrelatedEdit() {
    // rule 3: it is the only copy of the credential, and the operator has to be able to see
    // the loss and replace it rather than have a rename destroy it
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-original")));
    String sealed = stored(saved.id(), "ANTHROPIC_API_KEY").value();

    CredentialService wrongKey = new CredentialService(repository, new SecretsAtRest(OTHER_KEY));
    wrongKey.update(saved.id(), request("anthropic renamed", secret("ANTHROPIC_API_KEY", "")));

    assertEquals(sealed, stored(saved.id(), "ANTHROPIC_API_KEY").value());
    assertEquals("sk-original", CIPHER.decrypt(sealed));
  }

  @Test
  void aPlainEntryIsNotPromotedToASecretsEnvelopeOnABlankSubmission() {
    // it holds readable plaintext; carrying it forward as a secret would hand back something
    // that was never encrypted
    CredentialDto saved = service.create(request("signal", plain("SIGNAL_HTTP_URL", "http://signal:8080")));

    assertThrows(IllegalArgumentException.class,
        () -> service.update(saved.id(), request("signal", secret("SIGNAL_HTTP_URL", ""))));
  }

  @Test
  void anEntryLeftOutOfAnUpdateIsRemoved() {
    // the editor submits the whole list, so an absent entry is a deleted one — there is no
    // clear flag to carry
    CredentialDto saved = service.create(request("telegram ops",
        secret("TELEGRAM_BOT_TOKEN", "bot-1"), plain("TELEGRAM_HOME_CHANNEL", "#ops")));

    service.update(saved.id(), request("telegram ops", secret("TELEGRAM_BOT_TOKEN", "")));

    assertEquals(List.of("TELEGRAM_BOT_TOKEN"),
        repository.find(saved.id()).orElseThrow().entries().stream()
            .map(CredentialEntry::key).toList());
  }

  @Test
  void anyValueForAnswersTheFirstSavedKeyItCanOpen_andEmptyWhenNoneFits() {
    // the model-catalog refresh borrows whichever saved key fits the provider's variable
    assertEquals(Optional.empty(), service.anyValueFor("OPENAI_API_KEY"));
    service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-ant")));
    service.create(request("openai", secret("OPENAI_API_KEY", "sk-oai")));

    assertEquals(Optional.of("sk-oai"), service.anyValueFor("OPENAI_API_KEY"));
    assertEquals(Optional.empty(), service.anyValueFor("XAI_API_KEY"));
    // an envelope this key cannot open is passed over, not thrown: the job has nobody to tell
    assertEquals(Optional.empty(),
        new CredentialService(repository, new SecretsAtRest(OTHER_KEY)).anyValueFor("OPENAI_API_KEY"));
  }

  @Test
  void aBlankDescriptionIsStoredAsAbsent() {
    CredentialDto saved = service.create(
        new UpsertCredentialRequest("anthropic", "  ", List.of(secret("ANTHROPIC_API_KEY", "sk"))));

    assertNull(saved.description());
  }

  @Test
  void aNullEntryListSavesACredentialThatHoldsNothingYet() {
    CredentialDto saved = service.create(new UpsertCredentialRequest("empty", null, null));

    assertEquals(List.of(), saved.entries());
  }

  @Test
  void aNullEntryInTheListIsSkippedRatherThanFailingTheSave() {
    CredentialDto saved = service.create(new UpsertCredentialRequest(
        "anthropic", null, java.util.Arrays.asList(null, secret("ANTHROPIC_API_KEY", "sk"))));

    assertEquals(List.of("ANTHROPIC_API_KEY"), saved.entries().stream()
        .map(CredentialEntryDto::key).toList());
  }

  @Test
  void anUpdateToAnUnknownCredentialIsNotFound() {
    assertThrows(NoSuchElementException.class,
        () -> service.update("cr-nope", request("anthropic", secret("ANTHROPIC_API_KEY", "sk"))));
  }

  // ── the API view ───────────────────────────────────────────────────────────

  @Test
  void aSecretIsReportedAsSetAndNeverReturned() {
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-live-1234")));

    CredentialEntryDto entry = entryOf(saved, "ANTHROPIC_API_KEY");
    assertTrue(entry.secret());
    assertTrue(entry.set());
    assertTrue(entry.recoverable());
    assertNull(entry.value(), "not even a suffix of the key may leave the server");
  }

  @Test
  void aPlainEntrysValueIsReturnedBecauseThePickerHasToShowIt() {
    // a home channel or a base URL is nothing to hide, and a picker that could not show it
    // would be useless for the pair it belongs to
    CredentialDto saved = service.create(request("telegram ops",
        secret("TELEGRAM_BOT_TOKEN", "bot-1"), plain("TELEGRAM_HOME_CHANNEL", "#ops")));

    CredentialEntryDto entry = entryOf(saved, "TELEGRAM_HOME_CHANNEL");
    assertFalse(entry.secret());
    assertEquals("#ops", entry.value());
    assertTrue(entry.set());
  }

  @Test
  void aSecretThisKeyCannotOpenIsReportedSetButNotRecoverable() {
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-original")));

    CredentialService wrongKey = new CredentialService(repository, new SecretsAtRest(OTHER_KEY));
    CredentialEntryDto entry = entryOf(
        wrongKey.list().stream().filter(c -> c.id().equals(saved.id())).findFirst().orElseThrow(),
        "ANTHROPIC_API_KEY");

    assertTrue(entry.set(), "the envelope is there — it just cannot be opened");
    assertFalse(entry.recoverable());
  }

  @Test
  void theListIsTheRedactedView() {
    service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-live")));

    assertEquals(1, service.list().size());
    assertNull(service.list().get(0).entries().get(0).value());
  }

  // ── resolving ──────────────────────────────────────────────────────────────

  @Test
  void valueForAnswersOneEntryInTheClear() {
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-live-1234")));

    assertEquals("sk-live-1234", service.valueFor(saved.id(), "ANTHROPIC_API_KEY"));
  }

  @Test
  void aKeyThisCredentialDoesNotHoldIsABadRequest() {
    // the picker offers only credentials that hold the key, so this is a stale page or a
    // hand-made call — and writing a blank over a working key is the outcome worth refusing
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk")));

    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> service.valueFor(saved.id(), "OPENAI_API_KEY")).getMessage().contains("OPENAI_API_KEY"));
  }

  @Test
  void anUnknownCredentialIsNotFound() {
    assertThrows(NoSuchElementException.class, () -> service.valueFor("cr-nope", "ANTHROPIC_API_KEY"));
    assertThrows(NoSuchElementException.class, () -> service.envelopeFor("cr-nope", "ANTHROPIC_API_KEY"));
  }

  @Test
  void aSecretThatNoLongerDecryptsFailsTheWriteRatherThanWritingABlank() {
    // the caller is about to put this somewhere an operator will read as configured
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-original")));

    CredentialService wrongKey = new CredentialService(repository, new SecretsAtRest(OTHER_KEY));

    assertTrue(assertThrows(ResourceConflictException.class,
        () -> wrongKey.valueFor(saved.id(), "ANTHROPIC_API_KEY")).getMessage()
        .contains("MC_SECRET_KEY"));
    assertThrows(ResourceConflictException.class,
        () -> wrongKey.envelopeFor(saved.id(), "ANTHROPIC_API_KEY"));
  }

  @Test
  void aSecretEntryWithNoStoredValueFailsTheWrite() {
    // only reachable by hand-editing the row, but the message has to name the key rather than
    // let an empty envelope reach the writer
    repository.insert(new Credential("cr-1", "anthropic", null,
        List.of(new CredentialEntry("ANTHROPIC_API_KEY", null, true)), 1L, 1L));

    assertTrue(assertThrows(ResourceConflictException.class,
        () -> service.valueFor("cr-1", "ANTHROPIC_API_KEY")).getMessage()
        .contains("ANTHROPIC_API_KEY"));
  }

  @Test
  void envelopeForAnswersTheStoredCiphertextUntouched() {
    // ciphertext to ciphertext: a blueprint copy is sealed under the same key, so nothing
    // decrypts on that path
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk-live-1234")));

    String envelope = service.envelopeFor(saved.id(), "ANTHROPIC_API_KEY");

    assertEquals(stored(saved.id(), "ANTHROPIC_API_KEY").value(), envelope);
    assertEquals("sk-live-1234", CIPHER.decrypt(envelope));
  }

  @Test
  void deletingACredentialLeavesEverythingItEverFilledWhereItWasWritten() {
    // autofill only — the row has no dependents, which is the whole design
    CredentialDto saved = service.create(request("anthropic", secret("ANTHROPIC_API_KEY", "sk")));

    service.delete(saved.id());

    assertEquals(List.of(), service.list());
  }
}
