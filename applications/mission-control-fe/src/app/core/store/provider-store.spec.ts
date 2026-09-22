import { describe, expect, it, vi } from 'vitest';
import { testSlices } from '../../testing/store';

const store = (providers: Record<string, unknown>) => {
  const { ctx, providers: store } = testSlices({ providers });
  return { ctx, store };
};

describe('ProviderStore LLM registry', () => {
  it('starts empty — the backend registry is the only one', () => {
    expect(store({}).store.llmProviders()).toEqual([]);
  });

  it('fills the picker from the backend registry', async () => {
    const registry = [{ key: 'nous', label: 'Nous', needsKey: true, oauth: false, hasCatalog: true, envVar: 'NOUS_API_KEY' }];
    const built = store({ registry: vi.fn().mockResolvedValue(registry) });

    await built.store.refreshRegistry();

    expect(built.store.llmProviders()).toEqual(registry);
  });

  it('resolves rather than throwing when the registry is unreachable, so the rest of the initial load survives', async () => {
    const failing = store({ registry: vi.fn().mockRejectedValue(new Error('offline')) });

    await expect(failing.store.refreshRegistry()).resolves.toBeUndefined();

    expect(failing.store.llmProviders()).toEqual([]);
  });
});

describe('ProviderStore models', () => {
  it('serves the configured catalog for a provider key, and says where it came from', async () => {
    const built = store({
      modelCatalog: vi.fn().mockResolvedValue({ models: ['m-1', 'm-2'], source: 'catalog' }),
    });

    expect(await built.store.modelCatalog('anthropic'))
      .toEqual({ models: ['m-1', 'm-2'], source: 'catalog' });
  });

  it('answers empty when the catalog cannot be read, rather than from a shipped copy', async () => {
    const built = store({ modelCatalog: vi.fn().mockRejectedValue(new Error('offline')) });

    // the picker shows nothing, which is what an unreachable backend looks like everywhere
    // else here — and the create it feeds could not be submitted anyway
    expect(await built.store.modelCatalog('anthropic')).toEqual({ models: [], source: null });
  });

  it('reads the live catalog with the operator\'s key', async () => {
    const modelCatalogLive = vi.fn().mockResolvedValue({ models: ['live-1'], source: 'live' });
    const built = store({ modelCatalogLive });

    expect(await built.store.modelCatalogLive('anthropic', { apiKey: 'sk-x' }))
      .toEqual({ models: ['live-1'], source: 'live' });
    expect(modelCatalogLive).toHaveBeenCalledWith('anthropic', { apiKey: 'sk-x' });
  });

  it('falls back to the configured catalog when the key is rejected', async () => {
    const built = store({
      modelCatalogLive: vi.fn().mockRejectedValue(new Error('401')),
      modelCatalog: vi.fn().mockResolvedValue({ models: ['configured'], source: 'config' }),
    });

    expect(await built.store.modelCatalogLive('anthropic', { apiKey: 'bad-key' }))
      .toEqual({ models: ['configured'], source: 'config' });
  });
});
