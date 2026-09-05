package io.hermes.missioncontrol.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModelCatalogService {

  private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final List<String> OPENAI_EXCLUDED = List.of(
      "embedding", "audio", "realtime", "image", "tts", "whisper", "moderation", "transcribe");

  /**
   * The providers whose model list can be read with no credential at all, measured
   * against each endpoint rather than taken from documentation:
   *
   * <pre>
   *   openrouter  openrouter.ai/api/v1/models                200
   *   nvidia      integrate.api.nvidia.com/v1/models         200
   *   nous        inference-api.nousresearch.com/v1/models    200
   * </pre>
   *
   * <p>Everything else in {@link io.hermes.missioncontrol.agents.ModelProviderRegistry}
   * answers 401 (Anthropic, OpenAI, xAI, DeepSeek, Kimi, Z.AI, StepFun, MiniMax) or 403
   * (Google AI Studio) without a key, and so cannot be refreshed by a background job that
   * holds none. Those keep their curated list, and {@link #live} remains the way to read
   * them — with a key the caller supplies for that one request.
   */
  static final List<String> PUBLIC_CATALOGS = List.of("openrouter", "nvidia", "nous");

  private final ModelCatalogProperties props;
  private final ModelCatalogRepository repository;
  private final ObjectMapper objectMapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  public ModelCatalogService(
      ModelCatalogProperties props, ModelCatalogRepository repository, ObjectMapper objectMapper) {
    this.props = props;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  /**
   * What the picker should offer for this provider.
   *
   * <p>A refreshed list wins over the curated one: it came from the provider itself and
   * says so through {@code source}, which is how the page can tell an operator whether it
   * is looking at today's models or at what this app shipped with.
   */
  public ModelCatalogDto configured(String provider) {
    String normalized = normalize(provider);
    List<String> refreshed = repository.models(normalized);
    if (!refreshed.isEmpty()) {
      return new ModelCatalogDto(normalized, refreshed, "catalog");
    }
    return new ModelCatalogDto(normalized, configuredModels(normalized), "config");
  }

  /**
   * Re-reads every keyless provider and stores what came back. Never throws: one
   * provider being down must not stop the other two being refreshed, and the whole
   * job runs unattended twice a day with nobody to catch anything it raised.
   *
   * @return the providers that were actually updated
   */
  public List<String> refreshAll() {
    List<String> refreshed = new ArrayList<>();
    for (String provider : PUBLIC_CATALOGS) {
      if (refresh(provider)) refreshed.add(provider);
    }
    return refreshed;
  }

  /** One provider. False when it could not be read, or answered with nothing usable. */
  public boolean refresh(String provider) {
    String normalized = normalize(provider);
    try {
      List<String> models = fetch(normalized, null);
      if (models.isEmpty()) {
        // 200-with-nothing is far more likely a changed response shape than a vendor
        // with no models, and storing it would empty the picker on the strength of a guess
        log.warn("model catalog refresh for {} returned no models — keeping the previous list",
            normalized);
        return false;
      }
      repository.replace(normalized, models, System.currentTimeMillis());
      log.info("model catalog refreshed for {}: {} models", normalized, models.size());
      return true;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      // the stored list stays exactly as it was; a provider that is down for a day
      // costs the picker nothing
      log.warn("model catalog refresh for {} failed: {}", normalized, e.toString());
      return false;
    }
  }

  /** Live list from the provider API; falls back to the configured list. */
  public ModelCatalogDto live(String provider, String apiKey) {
    String normalized = normalize(provider);
    // resolve the fallback defensively: a provider the registry lists but has no
    // curated CSV for (gemini, xai, …) must yield an empty live list, not a 404
    List<String> configured = configuredModelsOrEmpty(normalized);
    try {
      List<String> models = fetch(normalized, apiKey);
      return new ModelCatalogDto(normalized, models, "live");
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("live model fetch for {} failed: {}", normalized, e.toString());
      return new ModelCatalogDto(normalized, configured, "config");
    }
  }

  private List<String> configuredModels(String provider) {
    String csv = switch (provider) {
      case "anthropic" -> props.anthropic();
      case "openai-api" -> props.openai();
      case "nous" -> props.nous();
      case "openrouter" -> props.openrouter();
      case "nvidia" -> props.nvidia();
      default -> throw new NoSuchElementException("unknown model provider: " + provider);
    };
    List<String> models = new ArrayList<>();
    for (String entry : (csv == null ? "" : csv).split(",")) {
      String trimmed = entry.trim();
      if (!trimmed.isEmpty()) models.add(trimmed);
    }
    return models;
  }

  /** {@link #configuredModels} but empty (not an exception) for providers with no
   *  curated list — the live path offers those a free-text id rather than a 404. */
  private List<String> configuredModelsOrEmpty(String provider) {
    try {
      return configuredModels(provider);
    } catch (NoSuchElementException e) {
      return List.of();
    }
  }

  /** The provider's own list, or empty for one this app has no fetcher for. */
  private List<String> fetch(String provider, String apiKey) throws Exception {
    return switch (provider) {
      case "anthropic" -> fetchAnthropic(apiKey);
      case "openai-api" -> fetchOpenai(apiKey);
      case "openrouter" -> fetchOpenrouter(apiKey);
      case "nvidia" -> fetchKeyless("https://integrate.api.nvidia.com/v1/models");
      // Nous is an OAuth account for *inference*, which is why nothing here holds a key for
      // it — but its model list is served without one, so the default provider no longer has
      // to sit on a hand-written list.
      case "nous" -> fetchKeyless("https://inference-api.nousresearch.com/v1/models");
      default -> List.of();
    };
  }

  /** A provider whose listing endpoint takes no credential; both are OpenAI-shaped. */
  private List<String> fetchKeyless(String url) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
    return modelIds(send(request), id -> true);
  }

  private List<String> fetchAnthropic(String apiKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models"))
        .timeout(TIMEOUT)
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .GET()
        .build();
    return modelIds(send(request), id -> true);
  }

  private List<String> fetchOpenai(String apiKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
        .timeout(TIMEOUT)
        .header("Authorization", "Bearer " + apiKey)
        .GET()
        .build();
    return modelIds(send(request), ModelCatalogService::isOpenaiChatModel);
  }

  /** OpenRouter's model list is public; the key is optional and only sent when present. */
  private List<String> fetchOpenrouter(String apiKey) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/models"))
        .timeout(TIMEOUT)
        .GET();
    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }
    return modelIds(send(builder.build()), id -> true);
  }

  /**
   * Sends a provider request and returns its body. Package-private and non-static so a test can
   * substitute it: the three fetch methods above address the real provider APIs by hostname, so
   * the request they build — and the filtering of what comes back — is otherwise unreachable
   * without calling Anthropic, OpenAI or OpenRouter for real.
   */
  String send(HttpRequest request) throws Exception {
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("provider returned HTTP " + response.statusCode());
    }
    return response.body();
  }

  /** Preserves the provider API's own order, matching the configured (curated)
   *  path which keeps its authored order — so both catalog sources order models
   *  the same way (by source) rather than one alpha-sorting and the other not. */
  private List<String> modelIds(String body, Predicate<String> filter) throws Exception {
    JsonNode root = objectMapper.readTree(body);
    List<String> models = new ArrayList<>();
    for (JsonNode entry : root.path("data")) {
      String id = entry.path("id").asText("");
      if (!id.isBlank() && filter.test(id)) models.add(id);
    }
    return models;
  }

  /** Chat-capable families only: gpt-* and o1/o3/... reasoning models. */
  private static boolean isOpenaiChatModel(String id) {
    String lower = id.toLowerCase(Locale.ROOT);
    if (!lower.startsWith("gpt-") && !lower.matches("o\\d.*")) return false;
    return OPENAI_EXCLUDED.stream().noneMatch(lower::contains);
  }

  /** The registry's spelling, so a catalog asked for under a key hermes has since renamed
   *  (`openai`, now `openai-api`) still answers, and answers under the current key. */
  private String normalize(String provider) {
    return io.hermes.missioncontrol.agents.ModelProviderRegistry.normalizeKey(provider);
  }
}
