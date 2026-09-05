package io.hermes.missioncontrol.agents;

import java.util.List;

/**
 * Every variable a profile's {@code .env} can hold that this dashboard knows about, and the
 * commented-out template it seeds a new profile with.
 *
 * <p>Split out of {@link HermesSetup} to break a cycle: {@code HermesSetup} takes
 * {@link HermesEnvFile} as a collaborator, while {@code HermesEnvFile.seedIfMissing} called
 * back into {@code HermesSetup.envTemplate()} to get the file's initial contents. Both now
 * read the tables from here and neither knows about the other in that direction.
 *
 * <p>Labels mirror the provider tables in {@code /opt/hermes/hermes_cli/status.py} inside the
 * hermes image, because they are matched against that command's output. The {@code .env} is
 * the source of truth for set/masked; the status output only fills in providers configured
 * outside it.
 */
final class HermesEnvCatalog {

  /**
   * A row for a model provider takes its variable from {@link ModelProviderRegistry} rather
   * than naming one. The two tables both describe the same {@code .env}, reached from
   * different screens — {@code HermesEnvFile.maskApiKey} resolves the provider's variable
   * through the registry for the agent card, this table drives the setup page, and a capture
   * writes a template's keys from it — so a variable named in only one of them shows a
   * credential as set on one screen and missing on the other, and captures the wrong one. The
   * labels stay separate on purpose: these have to match hermes' status output, the
   * registry's are for the UI picker.
   */
  static final List<ApiKeySpec> API_KEYS = List.of(
      ApiKeySpec.forProvider("openrouter", "OpenRouter"),
      ApiKeySpec.forProvider("openai-api", "OpenAI"),
      ApiKeySpec.forProvider("anthropic", "Anthropic", "ANTHROPIC_TOKEN"),
      ApiKeySpec.forProvider("gemini", "Google / Gemini", "GEMINI_API_KEY"),
      ApiKeySpec.forProvider("deepseek", "DeepSeek"),
      ApiKeySpec.forProvider("xai", "xAI / Grok"),
      ApiKeySpec.forProvider("nvidia", "NVIDIA NIM"),
      ApiKeySpec.forProvider("zai", "Z.AI / GLM"),
      ApiKeySpec.forProvider("kimi-coding", "Kimi"),
      ApiKeySpec.forProvider("stepfun", "StepFun Step Plan"),
      ApiKeySpec.forProvider("minimax", "MiniMax"),
      ApiKeySpec.forProvider("minimax-cn", "MiniMax-CN"),
      ApiKeySpec.forProvider("deepinfra", "DeepInfra"),
      // Providers hermes offers in its own picker but does not print a row for in `status`.
      // The labels below are ours, and will not match anything in that output — which costs
      // nothing, because the .env is what says whether a key is set. The rows exist so that a
      // provider the create-agent picker offers has somewhere to put its key.
      ApiKeySpec.forProvider("fireworks", "Fireworks AI"),
      ApiKeySpec.forProvider("novita", "NovitaAI"),
      ApiKeySpec.forProvider("alibaba", "Qwen Cloud"),
      ApiKeySpec.forProvider("xiaomi", "Xiaomi MiMo"),
      ApiKeySpec.forProvider("tencent-tokenhub", "Tencent TokenHub"),
      ApiKeySpec.forProvider("copilot", "GitHub Copilot", "GH_TOKEN"),
      ApiKeySpec.forProvider("huggingface", "Hugging Face"),
      ApiKeySpec.forProvider("kimi-coding-cn", "Kimi / Moonshot (China)"),
      ApiKeySpec.forProvider("ollama-cloud", "Ollama Cloud"),
      ApiKeySpec.forProvider("arcee", "Arcee AI"),
      ApiKeySpec.forProvider("gmi", "GMI Cloud"),
      ApiKeySpec.forProvider("kilocode", "Kilo Code"),
      ApiKeySpec.forProvider("opencode-zen", "OpenCode Zen"),
      ApiKeySpec.forProvider("opencode-go", "OpenCode Go"),
      ApiKeySpec.forProvider("ai-gateway", "Vercel AI Gateway"),
      ApiKeySpec.forProvider("azure-foundry", "Azure Foundry"),
      // no registry provider behind these: tool credentials, none of which a profile's model
      // can be pointed at
      new ApiKeySpec("Firecrawl", "FIRECRAWL_API_KEY", List.of(), false),
      new ApiKeySpec("Tavily", "TAVILY_API_KEY", List.of(), false),
      new ApiKeySpec("Browser Use", "BROWSER_USE_API_KEY", List.of(), true),
      new ApiKeySpec("Browserbase", "BROWSERBASE_API_KEY", List.of(), true),
      new ApiKeySpec("FAL", "FAL_KEY", List.of(), false),
      new ApiKeySpec("ElevenLabs", "ELEVENLABS_API_KEY", List.of(), false),
      new ApiKeySpec("GitHub", "GITHUB_TOKEN", List.of(), false));

  static final List<MessagingSpec> MESSAGING = List.of(
      new MessagingSpec("Telegram", "TELEGRAM_BOT_TOKEN", "TELEGRAM_HOME_CHANNEL"),
      new MessagingSpec("Discord", "DISCORD_BOT_TOKEN", "DISCORD_HOME_CHANNEL"),
      new MessagingSpec("WhatsApp", "WHATSAPP_ENABLED", null),
      new MessagingSpec("Signal", "SIGNAL_HTTP_URL", "SIGNAL_HOME_CHANNEL"),
      new MessagingSpec("Slack", "SLACK_BOT_TOKEN", null),
      new MessagingSpec("Email", "EMAIL_ADDRESS", "EMAIL_HOME_ADDRESS"),
      new MessagingSpec("SMS", "TWILIO_ACCOUNT_SID", "SMS_HOME_CHANNEL"),
      new MessagingSpec("DingTalk", "DINGTALK_CLIENT_ID", null),
      new MessagingSpec("Feishu", "FEISHU_APP_ID", "FEISHU_HOME_CHANNEL"),
      new MessagingSpec("WeCom", "WECOM_BOT_ID", "WECOM_HOME_CHANNEL"),
      new MessagingSpec("WeCom Callback", "WECOM_CALLBACK_CORP_ID", null),
      new MessagingSpec("Weixin", "WEIXIN_ACCOUNT_ID", "WEIXIN_HOME_CHANNEL"),
      new MessagingSpec("BlueBubbles", "BLUEBUBBLES_SERVER_URL", "BLUEBUBBLES_HOME_CHANNEL"),
      new MessagingSpec("QQBot", "QQ_APP_ID", "QQ_HOME_CHANNEL"),
      new MessagingSpec("Yuanbao", "YUANBAO_APP_ID", "YUANBAO_HOME_CHANNEL"));

  private HermesEnvCatalog() {}

  /** Commented-out .env template documenting every supported variable. */
  static String template() {
    StringBuilder sb = new StringBuilder("""
        # hermes profile environment
        # Uncomment a variable and fill in its value to enable it.
        # OAuth providers are not configured here — run 'hermes portal'
        # (auth) or 'hermes model' (model selection) from the web terminal.

        # ── model & tool API keys
        """);
    for (ApiKeySpec spec : API_KEYS) {
      sb.append("# ").append(spec.envVar()).append("=  # ").append(spec.label());
      if (!spec.altVars().isEmpty()) {
        sb.append(" (alt: ").append(String.join(", ", spec.altVars())).append(")");
      }
      if (spec.optional()) {
        sb.append(" (optional)");
      }
      sb.append("\n");
    }
    sb.append("\n");
    sb.append("# ── messaging platforms\n");
    for (MessagingSpec spec : MESSAGING) {
      sb.append("# ").append(spec.tokenVar()).append("=  # ").append(spec.label()).append("\n");
      if (spec.homeVar() != null) {
        sb.append("# ").append(spec.homeVar()).append("=  # ").append(spec.label())
            .append(" home channel\n");
      }
    }
    return sb.toString();
  }

  record ApiKeySpec(String label, String envVar, List<String> altVars, boolean optional) {
    /**
     * A row for a provider {@link ModelProviderRegistry} already owns, which is therefore the
     * one place its API-key variable is named. A provider key with no variable there — an OAuth
     * provider, or a typo — is a wiring mistake and fails at class load rather than reporting
     * every key for that provider as unset.
     */
    static ApiKeySpec forProvider(String providerKey, String label, String... altVars) {
      String envVar = ModelProviderRegistry.envVar(providerKey);
      if (envVar == null) {
        throw new IllegalStateException(
            "no API-key variable for model provider '" + providerKey + "'");
      }
      return new ApiKeySpec(label, envVar, List.of(altVars), false);
    }
  }

  record MessagingSpec(String label, String tokenVar, String homeVar) {}
}
