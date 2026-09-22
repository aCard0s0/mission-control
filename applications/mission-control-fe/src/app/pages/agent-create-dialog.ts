import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivityStore } from '../core/store/activity-store';
import { AgentSetupStore } from '../core/store/agent-setup-store';
import { CredentialStore } from '../core/store/credential-store';
import { AgentStore } from '../core/store/agent-store';
import { InferenceEndpointStore } from '../core/store/inference-endpoint-store';
import { ProviderStore } from '../core/store/provider-store';
import { TemplateStore } from '../core/store/template-store';
import {
  AuthProvider, AuxiliaryModel, Credential, HermesContainer, ModelCatalog,
} from '../core/models';
import { ModelField } from '../shared/model-field';
import { ModelPicker, modelCatalogFor } from '../shared/model-picker';
import {
  OLLAMA_PREFIX, providerOptions, providerOptionFor, resolveProviderOption, templateProvidesKey,
} from '../shared/provider-resolve';
import { Scrim } from '../shared/scrim';

/** Hermes' own account, and what the form opens on. */
const DEFAULT_PROVIDER = 'nous';

/**
 * The new-agent form. Everything it collects is provider-shaped: which provider
 * serves the main model, whether that provider needs a key, and whether the
 * cheap side tasks should run somewhere else. Each model field is a
 * {@link ModelPicker}, so the two never share a load guard.
 *
 * The dialog owns its own state and reports only the outcome — a profile id, or
 * a plain close — so the page never has to reset a field.
 */
@Component({
  selector: 'mc-agent-create-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, ModelField, Scrim],
  templateUrl: './agent-create-dialog.html',
  styleUrl: './agent-create-dialog.scss',
})
export class AgentCreateDialog {
  /** The container the profile is created in — the page only opens on one. */
  readonly container = input.required<HermesContainer>();

  /** The id of the profile the backend created. */
  readonly created = output<string>();
  readonly closed = output<void>();

  protected readonly agents = inject(AgentStore);
  protected readonly templates = inject(TemplateStore);
  private readonly providers = inject(ProviderStore);
  private readonly endpoints = inject(InferenceEndpointStore);
  private readonly setup = inject(AgentSetupStore);
  private readonly credentials = inject(CredentialStore);
  private readonly activity = inject(ActivityStore);

  protected readonly busy = signal(false);

  /** Set when a create came back empty. The form stays put — this one collects a
   *  provider key, and closing on failure made the operator type it again. */
  protected readonly failed = signal(false);

  /** True once this dialog is closed; the create it started runs on without it. */
  private gone = false;

  /** The LLM registry — minus the OAuth logins this container does not hold — plus one entry
   *  per registered ollama instance. */
  protected readonly providerChoices = computed(() =>
    providerOptions(this.providers.llmProviders().filter(p => this.offered(p)), this.endpoints.endpoints()));

  protected name = '';
  protected provider = DEFAULT_PROVIDER;
  protected apiKey = '';
  /** A saved credential picked instead of typing the key, or '' for none. */
  protected apiKeyCredentialId = '';
  protected cloneFrom = '';
  protected fromTemplate = '';

  // Auxiliary side tasks (compression, summarization, memory flush, …) run on the
  // main model unless this override is on — worth turning on when the main model
  // is expensive, since side tasks are frequent, short and mechanical.
  protected auxOverride = false;
  protected auxProvider = '';
  protected auxApiKey = '';

  protected readonly main = new ModelPicker();
  protected readonly aux = new ModelPicker();

  // The container's OAuth logins, as `hermes status` reports them. An OAuth provider is
  // authenticated out of band — `hermes portal`, `hermes auth add openai-codex` — in the web
  // terminal, once per container, and every profile in it shares the result.
  private readonly authProviders = signal<AuthProvider[]>([]);

  constructor() {
    inject(DestroyRef).onDestroy(() => { this.gone = true; });
    void this.main.load(this.catalogFor(this.provider));
    // the container arrives with the input, which is bound after construction
    effect(() => {
      const container = this.container();
      // a stopped container cannot be asked — the call is a 409 and a console error for nothing
      if (container.status === 'stopped') { this.authProviders.set([]); return; }
      untracked(() => void this.loadAuthProviders(container.id));
    });
  }

  private async loadAuthProviders(containerId: string): Promise<void> {
    this.authProviders.set(await this.setup.authProviders(containerId));
  }

  /** The container's login for an OAuth provider, or null when the report does not name it. */
  protected authFor(providerKey: string): AuthProvider | null {
    return this.authProviders().find(a => a.providerKey === providerKey) ?? null;
  }

  /**
   * Whether the picker lists a provider. A key-based one always. An OAuth one only once this
   * container reports its login: hermes needs a device flow this dashboard cannot drive, so
   * offering the row anywhere else would build an agent that cannot answer. The default
   * account is the exception — it is what the form opens on, and its row warns instead.
   */
  private offered(p: { key: string; oauth: boolean }): boolean {
    if (!p.oauth || p.key === DEFAULT_PROVIDER) return true;
    return this.authFor(p.key)?.ok ?? false;
  }

  protected onProvider(option: string): void {
    this.provider = option;
    this.main.model = '';   // drop the prior provider's model; the load re-selects
    void this.main.load(this.catalogFor(option));
  }

  /** Prefill provider and model from the chosen template (keys come with it). */
  protected onTemplate(id: string): void {
    this.fromTemplate = id;
    const template = this.templates.byId(id);
    if (!template) return;
    const option = providerOptionFor(
      template.provider, template.baseUrl, this.providerChoices(), this.endpoints.endpoints());
    if (option) {
      this.provider = option;
      // hand the template's model in as the preferred selection, so the catalog
      // load's guard governs it too — a provider switch mid-load cannot let this
      // stale model land on the new provider
      void this.main.load(this.catalogFor(option), { preferred: template.model });
    } else if (template.model) {
      this.main.model = template.model;
    }
  }

  /**
   * Saved credentials holding this provider's API-key variable.
   *
   * Filtered by the variable rather than by the provider name, which is what the server
   * resolves too — the registry names the variable once (`LlmProvider.envVar`), so a
   * credential saved for `ANTHROPIC_API_KEY` is offered for every provider that reads it.
   */
  protected credentialChoices(): Credential[] {
    const envVar = this.providerInfo(this.provider)?.envVar;
    return envVar ? this.credentials.providing(envVar) : [];
  }

  /** Choosing a credential clears the typed key: they are alternatives, and the server
   *  prefers the id, so leaving a stale character on screen would misreport what was used. */
  protected onCredentialPick(id: string): void {
    this.apiKeyCredentialId = id;
    if (id) { this.apiKey = ''; this.refreshLive(); }
  }

  /** Live catalog refresh straight from the provider API, for a catalog-backed provider the
   *  operator has just typed a key for — or picked a saved credential for, in which case the
   *  server reads the key: this page never holds it. */
  protected refreshLive(): void {
    if (!this.apiKeyRequired() || !this.hasCatalog(this.provider)) return;
    const key = this.apiKeyCredentialId
      ? { credentialId: this.apiKeyCredentialId }
      : { apiKey: this.apiKey.trim() };
    if (!key.credentialId && !key.apiKey) return;
    void this.main.load(this.providers.modelCatalogLive(this.provider, key), { keepOnError: true });
  }

  /** The override's own key, once typed, reads its provider live too — same rule as the main
   *  key, for the case where the side tasks go to a vendor the main model does not use. */
  protected refreshAuxLive(): void {
    const key = this.auxApiKey.trim();
    if (!this.auxApiKeyRequired() || !key || !this.hasCatalog(this.auxProvider)) return;
    void this.aux.load(
      this.providers.modelCatalogLive(this.auxProvider, { apiKey: key }), { keepOnError: true });
  }

  /** Turning the override on starts it from the main provider, so the common case
   *  — same provider, cheaper model — is one field away. Turning it off clears the
   *  fields rather than leaving them to be silently re-sent. */
  protected onAuxOverride(on: boolean): void {
    this.auxOverride = on;
    if (!on) {
      this.auxProvider = this.auxApiKey = '';
      this.aux.reset();
      return;
    }
    this.auxProvider = this.auxProvider || this.provider;
    void this.aux.load(this.catalogFor(this.auxProvider));
  }

  protected onAuxProvider(option: string): void {
    this.auxProvider = option;
    this.aux.model = '';
    void this.aux.load(this.catalogFor(option));
  }

  protected apiKeyRequired(): boolean {
    const info = this.providerInfo(this.provider);
    if (!info?.needsKey) return false;   // ollama/nous → false
    // a template can carry the provider key — but only skip the prompt when it
    // holds a usable one for THIS provider's env var
    if (this.fromTemplate) {
      return !templateProvidesKey(this.templates.byId(this.fromTemplate), info);
    }
    return true;
  }

  /** The override needs its own key only when it introduces a provider the main
   *  model does not already authenticate. */
  protected auxApiKeyRequired(): boolean {
    if (!this.auxOverride || this.auxProvider === this.provider) return false;
    if (this.auxProvider.startsWith(OLLAMA_PREFIX)) return false;
    return this.providerInfo(this.auxProvider)?.needsKey ?? false;
  }

  /** An override that names no model is not an override — the toggle is on but the
   *  form is incomplete, which the create button refuses. */
  protected auxIncomplete(): boolean {
    if (!this.auxOverride) return false;
    return !this.aux.model.trim() || (this.auxApiKeyRequired() && !this.auxApiKey.trim());
  }

  protected async create(): Promise<void> {
    const name = this.name.trim().toLowerCase().replace(/\s+/g, '-');
    if (!name || !this.main.model || this.busy()) return;
    if (this.apiKeyRequired() && !this.apiKey.trim() && !this.apiKeyCredentialId) return;
    if (this.auxIncomplete()) return;
    const primary = resolveProviderOption(this.provider, this.endpoints.endpoints());
    if (!primary) return;
    const auxiliary = this.auxiliaryOverride();
    if (this.auxOverride && !auxiliary) return;   // named an ollama instance that vanished

    this.busy.set(true);
    this.failed.set(false);
    const id = await this.activity.run(`creating ${name}`, () => this.agents.create({
      containerId: this.container().id,
      name,
      provider: primary.provider,
      model: this.main.model,
      apiKey: this.apiKey.trim(),
      apiKeyCredentialId: this.apiKeyCredentialId || undefined,
      cloneFrom: this.cloneFrom || undefined,
      baseUrl: primary.baseUrl,
      fromTemplate: this.fromTemplate || undefined,
      auxiliary,
    }));

    // Closed while the create was in flight: it finished on its own and the store has already
    // said how it went, so there is nothing here left to route or to correct.
    if (this.gone) return;

    this.busy.set(false);
    // the store has already reported why a failed create failed; this dialog only has to stay,
    // because the operator's provider key is in a field that closing would empty
    if (id) this.created.emit(id);
    else this.failed.set(true);
  }

  /** The auxiliary payload, or undefined when side tasks should follow the main
   *  model. An override on the main provider sends no provider or endpoint of its
   *  own — the backend then inherits the main ones, so switching only the model
   *  cannot drift the two apart. */
  private auxiliaryOverride(): AuxiliaryModel | undefined {
    if (!this.auxOverride || !this.aux.model.trim()) return undefined;
    if (this.auxProvider === this.provider) return { model: this.aux.model.trim() };
    const resolved = resolveProviderOption(this.auxProvider, this.endpoints.endpoints());
    if (!resolved) return undefined;
    return {
      provider: resolved.provider,
      model: this.aux.model.trim(),
      baseUrl: resolved.baseUrl,
      apiKey: this.auxApiKey.trim() || undefined,
    };
  }

  /** Registry entry for a provider option (null for an ollama instance). */
  protected providerInfo(option: string) {
    return this.providers.llmProviders().find(p => p.key === option) ?? null;
  }

  /** Whether Mission Control can list models for this provider: ollama instances
   *  and the catalog-backed cloud providers can, the rest take a free-text id. */
  private hasCatalog(option: string): boolean {
    if (option.startsWith(OLLAMA_PREFIX)) return true;
    return this.providerInfo(option)?.hasCatalog ?? false;
  }

  private catalogFor(option: string): Promise<ModelCatalog> {
    return modelCatalogFor(option, this.providers, this.endpoints);
  }
}
