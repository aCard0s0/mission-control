package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.ModelProviderRegistry.Provider;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The provider table's own invariants. It is a static list, so the risk is not that a
 * method breaks but that a row is added inconsistently with the other places that key off
 * it — the API-key env slot, and the catalog service's provider switch.
 */
class ModelProviderRegistryTest {

  /** Mirrors ModelCatalogService's provider switch — the set it can serve a catalog for. */
  private static final Set<String> PROVIDERS_WITH_A_CURATED_CATALOG =
      Set.of("anthropic", "openai-api", "nous", "openrouter", "nvidia");

  @Test
  void everyProviderKeyIsUniqueAndLowercase() {
    Set<String> seen = new HashSet<>();
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      assertTrue(seen.add(p.key()), "duplicate provider key: " + p.key());
      assertEquals(p.key().toLowerCase(Locale.ROOT), p.key(), "provider key must be lowercase");
      assertFalse(p.label() == null || p.label().isBlank(), "provider " + p.key() + " has no label");
    }
  }

  @Test
  void everyEnvVarIsUniqueSoNoTwoProvidersFightOverOneEnvSlot() {
    Set<String> seen = new HashSet<>();
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      if (p.envVar() == null) continue;
      // two providers sharing an env var would silently overwrite each other's key in
      // the agent's .env
      assertTrue(seen.add(p.envVar()), "duplicate env var: " + p.envVar());
    }
  }

  @Test
  void needsKeyIsTrueForExactlyTheKeyBasedNonOauthProviders() {
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      boolean expected = p.envVar() != null && !p.oauth();
      assertEquals(expected, p.needsKey(), "needsKey wrong for " + p.key());
      if (p.oauth()) {
        // OAuth providers authenticate out-of-band, so the UI must not ask for a key
        assertNull(p.envVar(), "oauth provider " + p.key() + " also declares an env var");
        assertFalse(p.needsKey());
      }
    }
  }

  @Test
  void byKeyIsCaseAndWhitespaceInsensitiveAndNullSafe() {
    assertEquals("anthropic", ModelProviderRegistry.byKey("  ANTHROPIC  ").key());
    assertEquals("ANTHROPIC_API_KEY", ModelProviderRegistry.envVar(" Anthropic "));

    assertNull(ModelProviderRegistry.byKey(null));
    assertNull(ModelProviderRegistry.byKey("mystery"));
    assertNull(ModelProviderRegistry.envVar("mystery"));
    // an OAuth provider resolves but has no env var
    assertNotNull(ModelProviderRegistry.byKey("nous"));
    assertNull(ModelProviderRegistry.envVar("nous"));
  }

  @Test
  void theSpellingHermesRetiredForOpenAiStillResolvesToItsRow() {
    // hermes v0.21.0 split `openai` into openai-api (API key) and openai-codex (ChatGPT login);
    // the runtime resolver refuses the old key, so anything stored under it has to be folded
    assertEquals("openai-api", ModelProviderRegistry.normalizeKey("openai"));
    assertEquals("openai-api", ModelProviderRegistry.normalizeKey(" OpenAI "));
    assertEquals("openai-api", ModelProviderRegistry.byKey("openai").key());
    assertEquals("OPENAI_API_KEY", ModelProviderRegistry.envVar("openai"));
    assertNull(ModelProviderRegistry.byKey("openai-codex"), "a browser-login row is not offered");
    // and the Nous spellings collapse the same way through byKey
    assertEquals("nous", ModelProviderRegistry.byKey("nous-portal").key());
  }

  @Test
  void nousIsFirstBecauseThePickerDefaultsToTheTopEntry() {
    assertEquals("nous", ModelProviderRegistry.PROVIDERS.getFirst().key());
  }

  @Test
  void everyKeyBasedProviderIsReportableOnTheSetupPageUnderTheSameVariable() {
    // Two tables describe one .env from different screens: this registry resolves a provider's
    // variable for the agent card and for the write into .env, HermesEnvCatalog.API_KEYS drives the
    // setup page, and a template capture records which of those keys were set. A variable named
    // in only one of them shows a credential as set on one screen and missing on the other, and
    // captures the wrong key. API_KEYS now derives its provider rows from here, so this asserts
    // that nothing was left behind.
    Set<String> reportedOnTheSetupPage = HermesEnvCatalog.API_KEYS.stream()
        .map(HermesEnvCatalog.ApiKeySpec::envVar)
        .collect(Collectors.toSet());

    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      if (p.envVar() == null) continue;
      assertTrue(reportedOnTheSetupPage.contains(p.envVar()),
          "provider " + p.key() + " writes " + p.envVar()
              + " but the setup page has no row for it");
    }
  }

  @Test
  void aSetupRowForAProviderWithNoKeyFailsLoudlyRatherThanReportingItUnset() {
    // forProvider is what stops a row naming its own variable. Pointing one at an OAuth or
    // unknown provider yields no variable at all, and silently accepting that would report
    // every key for it as unset with nothing to explain why.
    assertThrows(IllegalStateException.class,
        () -> HermesEnvCatalog.ApiKeySpec.forProvider("nous", "Nous"));
    assertThrows(IllegalStateException.class,
        () -> HermesEnvCatalog.ApiKeySpec.forProvider("mystery", "Mystery"));
  }

  @Test
  void everyProviderClaimingACatalogIsOneTheCatalogServiceCanActuallyServe() {
    // hasCatalog drives the UI into GET /api/models/{provider}. A provider marked with a
    // catalog that the service has no curated list for answers 404 and breaks the picker.
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      assertEquals(
          PROVIDERS_WITH_A_CURATED_CATALOG.contains(p.key()),
          p.hasCatalog(),
          "hasCatalog for " + p.key() + " disagrees with ModelCatalogService's provider switch");
    }
  }
}
