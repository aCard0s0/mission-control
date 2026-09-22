import { ApiLiveModelsRequest, ApiModelCatalog, ApiModelProvider } from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/providers` + `/api/models` — the LLM vendor registry and its model catalogs,
 * which drive the create-agent and template pickers.
 *
 * <p>A provider here is an upstream vendor: what to call it, whether it wants an API key
 * or an OAuth login, and which models it serves. The self-hosted servers an operator runs
 * are {@link InferenceEndpointsApi}, a different axis entirely.
 */
export class ProvidersApi {
  constructor(private readonly http: ApiHttp) {}

  /** The LLM vendor registry — single source of truth for the picker. Not the endpoints. */
  registry(): Promise<ApiModelProvider[]> {
    return this.http.get('/api/providers');
  }

  modelCatalog(provider: string): Promise<ApiModelCatalog> {
    return this.http.get(`/api/models/${seg(provider)}`);
  }

  /** Reads the catalog straight from the provider API, with a typed key or a saved credential. */
  modelCatalogLive(provider: string, key: ApiLiveModelsRequest): Promise<ApiModelCatalog> {
    return this.http.post(`/api/models/${seg(provider)}`, key);
  }
}
