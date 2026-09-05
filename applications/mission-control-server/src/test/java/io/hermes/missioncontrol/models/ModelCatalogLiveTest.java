package io.hermes.missioncontrol.models;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

/**
 * The live catalog path: the request each provider gets, the filtering of what comes back, and
 * the fallback when a provider will not answer.
 *
 * <p>The three fetch methods address api.anthropic.com, api.openai.com and openrouter.ai by
 * hostname, so the substitutable {@code send} is what makes them reachable here. Its own body is
 * exercised separately against a loopback server, since a non-2xx from a provider has to become a
 * fallback rather than a broken model list in the create-agent modal.
 */
class ModelCatalogLiveTest {

  private static final ModelCatalogProperties PROPS = new ModelCatalogProperties(
      "claude-fable-5,claude-opus-4-8",
      "gpt-5.2,gpt-5.2-mini",
      "Hermes-4-405B",
      "nousresearch/hermes-4-405b",
      "meta/llama-3.3-70b-instruct");

  private final List<HttpRequest> sent = new ArrayList<>();

  /** A service whose provider calls are answered from {@code responder} instead of the network. */
  private ModelCatalogService serviceAnswering(Function<HttpRequest, String> responder) {
    return new ModelCatalogService(PROPS, mock(ModelCatalogRepository.class), new ObjectMapper()) {
      @Override
      String send(HttpRequest request) {
        sent.add(request);
        return responder.apply(request);
      }
    };
  }

  private ModelCatalogService serviceFailing(Exception failure) {
    return new ModelCatalogService(PROPS, mock(ModelCatalogRepository.class), new ObjectMapper()) {
      @Override
      String send(HttpRequest request) throws Exception {
        sent.add(request);
        throw failure;
      }
    };
  }

  // ── the request each provider gets ──────────────────────────────────────

  @Test
  void anthropicIsAskedWithItsApiKeyAndPinnedApiVersion() {
    ModelCatalogService service = serviceAnswering(request ->
        "{\"data\":[{\"id\":\"claude-opus-5\"},{\"id\":\"claude-sonnet-5\"}]}");

    ModelCatalogDto catalog = service.live("anthropic", "sk-ant-key");

    assertEquals("https://api.anthropic.com/v1/models", sent.getFirst().uri().toString());
    assertEquals(Optional.of("sk-ant-key"), sent.getFirst().headers().firstValue("x-api-key"));
    assertEquals(Optional.of("2023-06-01"), sent.getFirst().headers().firstValue("anthropic-version"));
    // the provider's own order is kept, matching how the curated list is authored
    assertEquals(List.of("claude-opus-5", "claude-sonnet-5"), catalog.models());
    assertEquals("live", catalog.source());
  }

  @Test
  void openaiIsAskedWithABearerTokenAndOnlyChatCapableModelsSurvive() {
    // the list feeds a model picker: an embedding, audio or image model chosen there produces a
    // 400 from the provider on the agent's first message
    ModelCatalogService service = serviceAnswering(request -> """
        {"data":[
          {"id":"gpt-5.2"},{"id":"o3-mini"},{"id":"o1-preview"},
          {"id":"gpt-4o-audio-preview"},{"id":"gpt-4o-realtime-preview"},
          {"id":"text-embedding-3-large"},{"id":"gpt-image-1"},{"id":"tts-1"},
          {"id":"whisper-1"},{"id":"omni-moderation-latest"},{"id":"gpt-4o-transcribe"},
          {"id":"babbage-002"},{"id":"dall-e-3"}
        ]}
        """);

    List<String> models = service.live("openai-api", "sk-openai-key").models();

    assertEquals(Optional.of("Bearer sk-openai-key"),
        sent.getFirst().headers().firstValue("Authorization"));
    assertEquals(List.of("gpt-5.2", "o3-mini", "o1-preview"), models);
  }

  @Test
  void openrouterSendsAKeyOnlyWhenThereIsOneBecauseItsListIsPublic() {
    ModelCatalogService service = serviceAnswering(request ->
        "{\"data\":[{\"id\":\"nousresearch/hermes-4-405b\"}]}");

    service.live("openrouter", "sk-or-key");
    assertEquals(Optional.of("Bearer sk-or-key"), sent.getFirst().headers().firstValue("Authorization"));

    service.live("openrouter", "   ");
    service.live("openrouter", null);
    assertTrue(sent.get(1).headers().firstValue("Authorization").isEmpty());
    assertTrue(sent.get(2).headers().firstValue("Authorization").isEmpty());
    // namespaced ids must survive verbatim — they are the provider's model identifier
    assertEquals(List.of("nousresearch/hermes-4-405b"), service.live("openrouter", null).models());
  }

  @Test
  void aProviderWithNoLiveEndpointAnswersFromItsCuratedList() {
    // Google AI Studio answers 403 to an unauthenticated list and this app has no fetcher
    // for it, so there is nothing to call — the curated list is the whole answer
    ModelCatalogService service = serviceAnswering(request -> "{}");

    ModelCatalogDto catalog = service.live("gemini", "key");

    assertEquals(List.of(), catalog.models());
    assertTrue(sent.isEmpty(), "no provider call is made for a catalog-only provider");
  }

  @Test
  void aProviderNameIsNormalisedOnTheLivePathToo() {
    ModelCatalogService service = serviceAnswering(request -> "{\"data\":[{\"id\":\"claude-opus-5\"}]}");

    assertEquals("anthropic", service.live("  ANTHROPIC ", "sk-ant-key").provider());
    assertEquals(List.of("claude-opus-5"), service.live("Anthropic", "sk-ant-key").models());
  }

  // ── what comes back ─────────────────────────────────────────────────────

  @Test
  void entriesWithNoUsableIdAreSkipped() {
    ModelCatalogService service = serviceAnswering(request ->
        "{\"data\":[{\"id\":\"claude-opus-5\"},{\"id\":\"\"},{},{\"id\":\"claude-sonnet-5\"}]}");

    assertEquals(List.of("claude-opus-5", "claude-sonnet-5"), service.live("anthropic", "k").models());
  }

  @Test
  void anEmptyOrShapelessResponseYieldsAnEmptyLiveList() {
    ModelCatalogService service = serviceAnswering(request -> "{\"data\":[]}");
    assertTrue(service.live("anthropic", "k").models().isEmpty());

    ModelCatalogService noData = serviceAnswering(request -> "{\"object\":\"list\"}");
    assertTrue(noData.live("anthropic", "k").models().isEmpty());
  }

  // ── fallback ────────────────────────────────────────────────────────────

  @Test
  void aRefusedKeyFallsBackToTheCuratedListAndSaysTheSourceIsConfig() {
    // the modal must still offer something to pick, and it must not claim to be live
    ModelCatalogService service = serviceFailing(new IllegalStateException("provider returned HTTP 401"));

    ModelCatalogDto catalog = service.live("anthropic", "sk-wrong");

    assertEquals(List.of("claude-fable-5", "claude-opus-4-8"), catalog.models());
    assertEquals("config", catalog.source());
  }

  @Test
  void anUnparseableBodyFallsBackRatherThanPropagating() {
    ModelCatalogService service = serviceAnswering(request -> "<html>gateway timeout</html>");

    assertEquals("config", service.live("openai-api", "sk-openai-key").source());
    assertEquals(List.of("gpt-5.2", "gpt-5.2-mini"), service.live("openai-api", "k").models());
  }

  @Test
  void anInterruptedFetchRestoresTheInterruptFlagForTheCaller() {
    // swallowing the interrupt here would leave a request thread that cannot be cancelled
    ModelCatalogService service = serviceFailing(new InterruptedException("shutting down"));

    ModelCatalogDto catalog = service.live("anthropic", "sk-ant-key");

    assertEquals("config", catalog.source());
    assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
  }

  @Test
  void aLiveLookupForAnUnknownProviderIsEmptyRatherThanAFailure() {
    // the registry can list a provider with no curated CSV (gemini, xai); the live path offers a
    // free-text id for those instead of a 404
    ModelCatalogService service = serviceAnswering(request -> "{}");

    ModelCatalogDto catalog = service.live("gemini", "key");

    assertTrue(catalog.models().isEmpty());
    assertEquals("gemini", catalog.provider());
  }

  // ── the sender itself ───────────────────────────────────────────────────

  @Test
  void aTwoHundredResponseIsReturnedAsItsBody() throws Exception {
    route(200, "{\"data\":[]}");

    assertEquals("{\"data\":[]}", new ModelCatalogService(PROPS, mock(ModelCatalogRepository.class), new ObjectMapper()).send(get()));
  }

  @Test
  void aNonTwoHundredResponseBecomesAFailureCarryingTheStatus() throws Exception {
    route(429, "slow down");

    assertEquals("provider returned HTTP 429",
        assertThrows(IllegalStateException.class,
            () -> new ModelCatalogService(PROPS, mock(ModelCatalogRepository.class), new ObjectMapper()).send(get())).getMessage());
  }

  @Test
  void aRedirectIsNotFollowedIntoASuccessfulLookingBody() throws Exception {
    // a captive portal or proxy answering 302 must not read as a model list
    route(302, "");

    assertFalse(assertThrows(IllegalStateException.class,
        () -> new ModelCatalogService(PROPS, mock(ModelCatalogRepository.class), new ObjectMapper()).send(get())).getMessage().isBlank());
  }

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/models";
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private void route(int status, String body) {
    server.createContext("/v1/models", exchange -> {
      byte[] payload = body.getBytes(UTF_8);
      exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
      try (var out = exchange.getResponseBody()) {
        out.write(payload);
      }
    });
  }

  private HttpRequest get() {
    return HttpRequest.newBuilder(URI.create(baseUrl)).GET().build();
  }
}
