package io.hermes.missioncontrol.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.NoSuchElementException;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

/** The configured (offline) catalog path: provider switch + CSV parsing. */
class ModelCatalogServiceTest {

  private final ModelCatalogService service = new ModelCatalogService(new ModelCatalogProperties(
      "claude-fable-5,claude-opus-4-8",
      "gpt-5.2,gpt-5.2-mini",
      "Hermes-4-405B,Hermes-4-70B,Hermes-4-14B",
      "nousresearch/hermes-4-405b,anthropic/claude-sonnet-4",
      "meta/llama-3.3-70b-instruct"),
      // nothing refreshed yet, so every read here falls through to the curated list
      mock(ModelCatalogRepository.class),
      new ObjectMapper());

  @Test
  void nousCatalogFromProps() {
    ModelCatalogDto dto = service.configured("nous");
    assertEquals("nous", dto.provider());
    assertEquals(List.of("Hermes-4-405B", "Hermes-4-70B", "Hermes-4-14B"), dto.models());
    assertEquals("config", dto.source());
  }

  @Test
  void openrouterCatalogKeepsNamespacedIds() {
    assertEquals(
        List.of("nousresearch/hermes-4-405b", "anthropic/claude-sonnet-4"),
        service.configured("openrouter").models());
  }

  @Test
  void anthropicAndOpenaiStillResolve() {
    assertEquals(List.of("claude-fable-5", "claude-opus-4-8"), service.configured("anthropic").models());
    assertEquals(List.of("gpt-5.2", "gpt-5.2-mini"), service.configured("openai-api").models());
  }

  @Test
  void providerNameIsNormalizedCaseInsensitive() {
    assertEquals(List.of("Hermes-4-405B", "Hermes-4-70B", "Hermes-4-14B"),
        service.configured("  NOUS ").models());
  }

  @Test
  void unknownProviderThrows() {
    assertThrows(NoSuchElementException.class, () -> service.configured("ollama"));
  }

  @Test
  void liveFallsBackToEmptyForCatalogLessProviderInsteadOf404() {
    // gemini/xai/etc. are real registry providers with no curated CSV — live() must
    // return an empty list (free-text in the UI), not throw NoSuchElementException.
    // No api key + a catalog-less provider hits the switch default with no network.
    ModelCatalogDto dto = service.live("gemini", "");
    assertEquals("gemini", dto.provider());
    assertEquals(List.of(), dto.models());
  }
}
