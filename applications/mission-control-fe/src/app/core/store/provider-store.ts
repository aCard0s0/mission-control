import { inject, Injectable, signal } from '@angular/core';
import { ApiLiveModelsRequest } from '../api/api-types';
import { LlmProvider, ModelCatalog } from '../models';
import { StoreContext } from './store-context';
import { toLlmProvider } from './wire-mappers';

/**
 * The registry of model *vendors* who can serve an Agent, and the model catalogs behind
 * them. A vendor is a capability description: what to call it in a picker, whether it wants
 * an API key or an OAuth login, and which models it offers.
 *
 * <p>The self-hosted servers an operator runs are {@link InferenceEndpointStore}. The
 * create-agent picker offers both in one dropdown — that merge is `providerOptions()`.
 */
@Injectable({ providedIn: 'root' })
export class ProviderStore {
  /** LLM provider registry for the create-agent / template pickers. Starts empty
   *  and is filled by `refreshRegistry()`, which `LiveSync.start()` awaits — the
   *  backend's list is the only one, so there is nothing to seed it with. A hardcoded
   *  mirror used to live here and had drifted to 12 of the registry's 32 entries,
   *  offering a short list nobody could tell from the real one. */
  readonly llmProviders = signal<LlmProvider[]>([]);

  private readonly ctx = inject(StoreContext);

  /** Loads the LLM provider registry. Swallows a failure rather than rejecting:
   *  `LiveSync.start()` awaits this inside a `Promise.all`, so throwing here would
   *  take every other initial load with it. An empty picker is what an unreachable
   *  backend looks like everywhere else in this app. */
  async refreshRegistry(): Promise<void> {
    try {
      this.llmProviders.set((await this.ctx.api.providers.registry()).map(toLlmProvider));
    } catch { /* no registry — the picker stays empty, like every other store */ }
  }

  /** Models a provider key can serve, from the backend's configured catalog, carrying the
   *  backend's own `source` through so a page can say which list it is showing.
   *
   *  <p>A failure answers empty rather than from a bundled copy. The copy that used to live
   *  here duplicated `mc.models` in application.yml under a "keep in sync" comment nothing
   *  enforced, and it could only ever fire when the request itself failed — every provider it
   *  carried has a server-side config list, so the endpoint does not 404 for them. With the
   *  backend unreachable the create-agent POST fails too, so it was populating a picker in a
   *  form that could not be submitted. */
  async modelCatalog(provider: string): Promise<ModelCatalog> {
    try {
      const answered = await this.ctx.api.providers.modelCatalog(provider);
      return { models: answered.models, source: answered.source };
    } catch {
      return { models: [], source: null };
    }
  }

  /** Fetch the catalog straight from the provider API, with a typed key or a saved credential. */
  async modelCatalogLive(provider: string, key: ApiLiveModelsRequest): Promise<ModelCatalog> {
    try {
      const answered = await this.ctx.api.providers.modelCatalogLive(provider, key);
      return { models: answered.models, source: answered.source };
    } catch {
      return this.modelCatalog(provider);
    }
  }
}
