package io.hermes.missioncontrol.agents;

import java.util.List;
import java.util.Locale;

/**
 * The model providers offered in the create-agent / template UIs and the single
 * source of truth for provider → API-key env var mapping. Keys, labels and env
 * vars mirror hermes' own {@code CANONICAL_PROVIDERS} picker order
 * (hermes_cli/models.py) and its provider records (hermes_cli/auth.py); ollama
 * instances are handled separately (registered per-container, not listed here).
 *
 * <p>{@code hasCatalog} marks the providers Mission Control can list models for
 * ({@link io.hermes.missioncontrol.models.ModelCatalogService}); the rest take a
 * free-text model id in the UI, because their listing endpoint refuses a request
 * carrying no key and this app holds none.
 *
 * <p>Three authentication shapes, and the record spells all three:
 * <ul>
 *   <li><b>key</b> — an {@code envVar} and {@code oauth=false}: the UI collects a key
 *   <li><b>portal OAuth</b> — {@code oauth=true}: authenticated out of band by
 *       {@code hermes portal}, so no key field
 *   <li><b>keyless, not portal</b> — no {@code envVar} and {@code oauth=false}: the
 *       provider authenticates from ambient cloud credentials (Vertex's service-account
 *       JSON or ADC, Bedrock's IAM). The UI collects nothing; the operator sets those up
 *       inside the container, which is why they are offered rather than hidden
 * </ul>
 *
 * <p>Deliberately not every row hermes lists. Skipped: {@code lmstudio} and
 * {@code copilot-acp}, which drive a desktop app or spawn a local binary and cannot work
 * from a container; {@code moa}, which resolves to a named Mixture-of-Agents preset
 * that a freshly created profile does not have yet — picking it at create time would
 * produce an agent that cannot answer; and the browser-login OAuth rows ({@code openai-codex},
 * {@code xai-oauth}, {@code minimax-oauth}, {@code qwen-oauth}), which need a device flow the
 * dashboard cannot drive.
 *
 * <p>Hermes v0.21.0 (2026.8.31) split its {@code openai} row into {@code openai-api} (API key,
 * api.openai.com) and {@code openai-codex} (ChatGPT subscription). The old key is gone: a
 * profile whose {@code config.yaml} still says {@code provider: openai} passes
 * {@code hermes status} — which only reads the env var — and then fails the runtime resolver
 * with {@code Unknown provider 'openai'}, which the interactive CLI reports as "No inference
 * provider is configured yet". {@link #normalizeKey} folds the old spelling to the new one, so
 * a blueprint or credential saved before the rename keeps working.
 */
public final class ModelProviderRegistry {

  public record Provider(String key, String label, String envVar, boolean oauth, boolean hasCatalog) {
    /** True when the UI must collect an API key (key-based, non-OAuth providers). */
    public boolean needsKey() {
      return envVar != null && !oauth;
    }
  }

  private ModelProviderRegistry() {}

  /** Ordered as hermes orders its own picker — Nous first (the default account). */
  public static final List<Provider> PROVIDERS = List.of(
      new Provider("nous", "Nous (account)", null, true, true),
      new Provider("fireworks", "Fireworks AI", "FIREWORKS_API_KEY", false, false),
      new Provider("openrouter", "OpenRouter", "OPENROUTER_API_KEY", false, true),
      new Provider("novita", "NovitaAI", "NOVITA_API_KEY", false, false),
      new Provider("anthropic", "Anthropic", "ANTHROPIC_API_KEY", false, true),
      new Provider("openai-api", "OpenAI API", "OPENAI_API_KEY", false, true),
      new Provider("alibaba", "Qwen Cloud", "DASHSCOPE_API_KEY", false, false),
      new Provider("xiaomi", "Xiaomi MiMo", "XIAOMI_API_KEY", false, false),
      new Provider("tencent-tokenhub", "Tencent TokenHub", "TOKENHUB_API_KEY", false, false),
      // its /v1/models is served without a key, so the picker can offer a list
      new Provider("nvidia", "NVIDIA NIM", "NVIDIA_API_KEY", false, true),
      new Provider("copilot", "GitHub Copilot", "COPILOT_GITHUB_TOKEN", false, false),
      new Provider("huggingface", "Hugging Face", "HF_TOKEN", false, false),
      new Provider("gemini", "Google AI Studio", "GOOGLE_API_KEY", false, false),
      // service-account JSON or ADC inside the container, never a key through this UI
      new Provider("vertex", "Google Vertex AI", null, false, false),
      new Provider("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", false, false),
      new Provider("deepinfra", "DeepInfra", "DEEPINFRA_API_KEY", false, false),
      new Provider("xai", "xAI / Grok", "XAI_API_KEY", false, false),
      new Provider("zai", "Z.AI / GLM", "GLM_API_KEY", false, false),
      new Provider("kimi-coding", "Kimi / Moonshot", "KIMI_API_KEY", false, false),
      new Provider("kimi-coding-cn", "Kimi / Moonshot (China)", "KIMI_CN_API_KEY", false, false),
      new Provider("stepfun", "StepFun", "STEPFUN_API_KEY", false, false),
      new Provider("minimax", "MiniMax", "MINIMAX_API_KEY", false, false),
      new Provider("minimax-cn", "MiniMax (China)", "MINIMAX_CN_API_KEY", false, false),
      new Provider("ollama-cloud", "Ollama Cloud", "OLLAMA_API_KEY", false, false),
      new Provider("arcee", "Arcee AI", "ARCEEAI_API_KEY", false, false),
      new Provider("gmi", "GMI Cloud", "GMI_API_KEY", false, false),
      new Provider("kilocode", "Kilo Code", "KILOCODE_API_KEY", false, false),
      new Provider("opencode-zen", "OpenCode Zen", "OPENCODE_ZEN_API_KEY", false, false),
      new Provider("opencode-go", "OpenCode Go", "OPENCODE_GO_API_KEY", false, false),
      new Provider("ai-gateway", "Vercel AI Gateway", "AI_GATEWAY_API_KEY", false, false),
      new Provider("azure-foundry", "Azure Foundry", "AZURE_FOUNDRY_API_KEY", false, false),
      // IAM or an API key configured in the container's AWS environment
      new Provider("bedrock", "AWS Bedrock", null, false, false));

  /**
   * Collapses a provider name to the key this registry lists it under. Hermes accepts
   * several spellings of the Nous provider ({@code nous}, {@code nous-portal}, …), and
   * {@code openai} is what API-key OpenAI was called before hermes v0.21.0 renamed it; every
   * path that resolves an env var or writes {@code model.provider} must agree on one.
   */
  public static String normalizeKey(String provider) {
    if (provider == null) return "";
    String trimmed = provider.trim().toLowerCase(Locale.ROOT);
    if (trimmed.startsWith("nous")) return "nous";
    return "openai".equals(trimmed) ? "openai-api" : trimmed;
  }

  /** The row for a key in any spelling {@link #normalizeKey} accepts, or null. */
  public static Provider byKey(String key) {
    if (key == null) return null;
    String k = normalizeKey(key);
    for (Provider p : PROVIDERS) {
      if (p.key().equals(k)) return p;
    }
    return null;
  }

  /** The API-key env var for a provider, or null for OAuth/unknown/keyless. */
  public static String envVar(String key) {
    Provider p = byKey(key);
    return p == null ? null : p.envVar();
  }
}
