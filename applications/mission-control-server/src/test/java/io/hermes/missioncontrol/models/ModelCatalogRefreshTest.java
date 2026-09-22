package io.hermes.missioncontrol.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.credentials.CredentialService;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The background refresh: which providers it reads, what it stores, and what it
 * refuses to store. Nothing here touches the network — {@code send} is substituted,
 * the same seam {@link ModelCatalogLiveTest} uses.
 */
class ModelCatalogRefreshTest {

  private static final ModelCatalogProperties PROPS = new ModelCatalogProperties(
      "claude-fable-5", "gpt-5.2", "Hermes-4-405B", "nousresearch/hermes-4-405b",
      "meta/llama-3.3-70b-instruct");

  private final List<HttpRequest> sent = new ArrayList<>();
  private final ModelCatalogRepository repository = mock(ModelCatalogRepository.class);
  private final CredentialService credentials = mock(CredentialService.class);

  private ModelCatalogService serviceAnswering(Function<HttpRequest, String> responder) {
    return new ModelCatalogService(PROPS, repository, credentials, new ObjectMapper()) {
      @Override
      String send(HttpRequest request) {
        sent.add(request);
        return responder.apply(request);
      }
    };
  }

  private static String models(String... ids) {
    StringBuilder json = new StringBuilder("{\"data\":[");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) json.append(',');
      json.append("{\"id\":\"").append(ids[i]).append("\"}");
    }
    return json.append("]}").toString();
  }

  @Test
  void itReadsTheProvidersWhoseListNeedsNoKey_andTheKeyedOnesItHasAFetcherFor() {
    // measured against each endpoint: everything else answers 401 or 403 unauthenticated,
    // so the keyed ones are read only when a saved credential lends the job a key
    assertEquals(List.of("openrouter", "nvidia", "nous"), ModelCatalogService.PUBLIC_CATALOGS);
    assertEquals(List.of("anthropic", "openai-api"), ModelCatalogService.KEYED_CATALOGS);
  }

  @Test
  void aRefreshReadsEveryKeylessProviderAndStoresWhatCameBack() {
    ModelCatalogService service = serviceAnswering(r -> models("a/one", "b/two"));

    assertEquals(List.of("openrouter", "nvidia", "nous"), service.refreshAll());

    verify(repository).replace(eq("openrouter"), eq(List.of("a/one", "b/two")), anyLong());
    verify(repository).replace(eq("nvidia"), eq(List.of("a/one", "b/two")), anyLong());
    verify(repository).replace(eq("nous"), eq(List.of("a/one", "b/two")), anyLong());
  }

  @Test
  void itCarriesNoCredentialToAKeylessProvider() {
    ModelCatalogService service = serviceAnswering(r -> models("a/one"));

    service.refreshAll();

    for (HttpRequest request : sent) {
      assertTrue(request.headers().firstValue("authorization").isEmpty(),
          "a keyless endpoint must not be sent an Authorization header: " + request.uri());
      assertTrue(request.headers().firstValue("x-api-key").isEmpty(),
          "nor an x-api-key: " + request.uri());
    }
  }

  @Test
  void aKeyedProviderIsReadWithASavedCredentialsKey_andSkippedWithoutOne() {
    // the operator saved an OpenAI key on the Credentials page; that is a key this job may
    // borrow, so OpenAI's list stops being the one this app shipped with
    when(credentials.anyValueFor("OPENAI_API_KEY")).thenReturn(Optional.of("sk-saved"));
    when(credentials.anyValueFor("ANTHROPIC_API_KEY")).thenReturn(Optional.empty());
    ModelCatalogService service = serviceAnswering(r -> models("gpt-6", "gpt-5.2"));

    assertEquals(List.of("openrouter", "nvidia", "nous", "openai-api"), service.refreshAll());

    HttpRequest openai = sent.stream()
        .filter(r -> r.uri().getHost().equals("api.openai.com")).findFirst().orElseThrow();
    assertEquals(Optional.of("Bearer sk-saved"), openai.headers().firstValue("Authorization"));
    verify(repository).replace(eq("openai-api"), eq(List.of("gpt-6", "gpt-5.2")), anyLong());
    assertTrue(sent.stream().noneMatch(r -> r.uri().getHost().equals("api.anthropic.com")),
        "a keyed provider with no saved key is not asked — it would only answer 401");
  }

  @Test
  void aSavedKeyThatCannotBeOpenedSkipsThatProviderRatherThanFailingTheJob() {
    when(credentials.anyValueFor("OPENAI_API_KEY"))
        .thenThrow(new IllegalStateException("cannot be decrypted (check MC_SECRET_KEY)"));
    ModelCatalogService service = serviceAnswering(r -> models("a/one"));

    assertEquals(List.of("openrouter", "nvidia", "nous"), service.refreshAll());
  }

  @Test
  void oneProviderBeingDownDoesNotStopTheOthers() {
    ModelCatalogService service = serviceAnswering(r -> {
      if (r.uri().getHost().contains("nvidia")) throw new IllegalStateException("provider returned HTTP 503");
      return models("a/one");
    });

    // the job runs unattended twice a day; a provider having a bad afternoon is not an outage
    assertEquals(List.of("openrouter", "nous"), service.refreshAll());
    verify(repository, never()).replace(eq("nvidia"), anyList(), anyLong());
  }

  @Test
  void anEmptyAnswerKeepsThePreviousListRatherThanEmptyingThePicker() {
    // 200-with-nothing is far likelier a changed response shape than a vendor with no models
    ModelCatalogService service = serviceAnswering(r -> "{\"data\":[]}");

    assertEquals(List.of(), service.refreshAll());
    verify(repository, never()).replace(anyString(), anyList(), anyLong());
  }

  @Test
  void theProvidersOwnOrderIsWhatGetsStored() {
    ModelCatalogService service = serviceAnswering(r -> models("zeta", "alpha", "mid"));

    service.refresh("nous");

    ArgumentCaptor<List<String>> stored = ArgumentCaptor.forClass(List.class);
    verify(repository).replace(eq("nous"), stored.capture(), anyLong());
    // not sorted: the curated path keeps its authored order, so a refreshed list that
    // alpha-sorted itself would reorder the picker depending on where its contents came from
    assertEquals(List.of("zeta", "alpha", "mid"), stored.getValue());
  }

  @Test
  void aRefreshedListIsWhatThePickerGets_andSaysWhereItCameFrom() {
    when(repository.models("nous")).thenReturn(List.of("Hermes-5-500B"));
    ModelCatalogService service = serviceAnswering(r -> models("unused"));

    ModelCatalogDto catalog = service.configured("nous");

    assertEquals(List.of("Hermes-5-500B"), catalog.models());
    assertEquals("catalog", catalog.source());
  }

  @Test
  void aProviderNeverRefreshedStillFallsBackToTheCuratedList() {
    ModelCatalogService service = serviceAnswering(r -> models("unused"));

    ModelCatalogDto catalog = service.configured("anthropic");

    assertEquals(List.of("claude-fable-5"), catalog.models());
    assertEquals("config", catalog.source());
  }

  @SuppressWarnings("unchecked")
  private static List<String> anyList() {
    return org.mockito.ArgumentMatchers.anyList();
  }
}
