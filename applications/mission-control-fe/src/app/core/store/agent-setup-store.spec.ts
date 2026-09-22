import { describe, expect, it, vi } from 'vitest';
import { ApiAgentSetup } from '../hermes-api';
import { AgentSetup } from '../models';
import { toAgentSetup } from './wire-mappers';
import { apiProfile, liveError, loadedAgentSlices } from '../../testing/store';

const setup = (patch: Partial<ApiAgentSetup> = {}): ApiAgentSetup => ({
  envPath: '/opt/data/atlas/.env',
  envExists: true,
  apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: true, masked: '…9f2c', problem: null }],
  authProviders: [],
  apiKeyProviders: [],
  messaging: [],
  ...patch,
});

/** What the store answers with for a given payload — the mapped shape, not the payload. */
const mapped = (patch: Partial<ApiAgentSetup> = {}): AgentSetup => toAgentSetup(setup(patch));

/** One profile in one container, with the `/api/agents` client stubbed. */
const loaded = async (agentsApi: Record<string, unknown>) => {
  const slices = await loadedAgentSlices({ agents: agentsApi },
    { profiles: [apiProfile('atlas')] });
  return { ...slices, store: slices.setup };
};

describe('AgentSetupStore credentials', () => {
  it('has nothing for a profile until one has been read', async () => {
    const { store } = await loaded({});

    expect(store.setupOf('a-atlas')).toBeNull();
    expect(store.isSetupLoading('a-atlas')).toBe(false);
  });

  it('reads the setup once and answers the cached copy after that', async () => {
    const read = vi.fn().mockResolvedValue(setup());
    const { store } = await loaded({ setup: read });

    expect(await store.setup('a-atlas')).toEqual(mapped());
    expect(await store.setup('a-atlas')).toEqual(mapped());
    expect(read).toHaveBeenCalledTimes(1);
    expect(store.setupOf('a-atlas')).toEqual(mapped());
  });

  it('re-reads on demand, because the refresh button exists to bypass the cache', async () => {
    const read = vi.fn()
      .mockResolvedValueOnce(setup())
      .mockResolvedValue(setup({ envExists: false }));
    const { store } = await loaded({ setup: read });
    await store.setup('a-atlas');

    expect(await store.setup('a-atlas', true)).toEqual(mapped({ envExists: false }));
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('reports a read in flight, and lets a second view wait rather than fetch again', async () => {
    let land!: (value: ApiAgentSetup) => void;
    const read = vi.fn().mockReturnValue(new Promise<ApiAgentSetup>(r => { land = r; }));
    const { store } = await loaded({ setup: read });

    const first = store.setup('a-atlas');
    expect(store.isSetupLoading('a-atlas')).toBe(true);
    const second = await store.setup('a-atlas');

    expect(second).toBeNull();          // nothing cached yet — the first read owns it
    expect(read).toHaveBeenCalledTimes(1);
    land(setup());
    await first;
    expect(store.isSetupLoading('a-atlas')).toBe(false);
  });

  it('keeps the cached copy when a forced re-read fails, so the tab does not blank', async () => {
    const read = vi.fn()
      .mockResolvedValueOnce(setup())
      .mockRejectedValue(new Error('container stopped'));
    const { store, ctx } = await loaded({ setup: read });
    await store.setup('a-atlas');

    expect(await store.setup('a-atlas', true)).toBeNull();
    expect(store.setupOf('a-atlas')).toEqual(mapped());
    expect(liveError(ctx)).toBe('setup load failed: container stopped');
  });

  it('answers null for a profile it does not hold', async () => {
    const read = vi.fn();
    const { store } = await loaded({ setup: read });

    expect(await store.setup('a-ghost')).toBeNull();
    expect(read).not.toHaveBeenCalled();
  });

  it('replaces the cache with what a write answered', async () => {
    const keys = [{ label: 'OpenAI', envVar: 'OPENAI_API_KEY', set: true, masked: '…abcd', problem: null }];
    const { store } = await loaded({
      setEnv: vi.fn().mockResolvedValue(setup({ apiKeys: keys })),
    });

    expect(await store.setEnv('a-atlas', [{ key: 'OPENAI_API_KEY', value: 'sk-x' }]))
      .toEqual(mapped({ apiKeys: keys }));
    expect(store.setupOf('a-atlas')).toEqual(mapped({ apiKeys: keys }));
  });

  it('reports a rejected write and leaves the cache alone', async () => {
    const { store, ctx } = await loaded({
      setup: vi.fn().mockResolvedValue(setup()),
      setEnv: vi.fn().mockRejectedValue(new Error('read-only volume')),
    });
    await store.setup('a-atlas');

    expect(await store.setEnv('a-atlas', [{ key: 'K', value: 'v' }])).toBeNull();
    expect(store.setupOf('a-atlas')).toEqual(mapped());
    expect(liveError(ctx)).toBe('env save failed: read-only volume');
  });

  it('writes the .env template and caches the result', async () => {
    const { store } = await loaded({
      initEnv: vi.fn().mockResolvedValue(setup({ envExists: true })),
    });

    expect(await store.initEnv('a-atlas')).toEqual(mapped({ envExists: true }));
    expect(store.setupOf('a-atlas')).toEqual(mapped({ envExists: true }));
  });

  it('reports a failed .env init', async () => {
    const { store, ctx } = await loaded({ initEnv: vi.fn().mockRejectedValue(new Error('denied')) });

    expect(await store.initEnv('a-atlas')).toBeNull();
    expect(liveError(ctx)).toBe('env init failed: denied');
  });

  it('does not write on behalf of a profile it does not hold', async () => {
    const setEnv = vi.fn();
    const initEnv = vi.fn();
    const { store } = await loaded({ setEnv, initEnv });

    expect(await store.setEnv('a-ghost', [])).toBeNull();
    expect(await store.initEnv('a-ghost')).toBeNull();
    expect(setEnv).not.toHaveBeenCalled();
    expect(initEnv).not.toHaveBeenCalled();
  });

  it('forgets a profile\'s credentials when the profile is gone', async () => {
    const { store } = await loaded({ setup: vi.fn().mockResolvedValue(setup()) });
    await store.setup('a-atlas');

    store.forget('a-atlas');
    store.forget('a-atlas');            // idempotent — nothing left to drop

    expect(store.setupOf('a-atlas')).toBeNull();
  });
});

describe('AgentSetupStore auth providers', () => {
  it('reads container-level auth status before any profile exists', async () => {
    const providers = [{ label: 'Nous Portal', ok: true, status: 'authorized', hint: null, providerKey: 'nous' }];
    const { store } = await loaded({ authProviders: vi.fn().mockResolvedValue(providers) });

    expect(await store.authProviders('c-1')).toEqual(providers);
  });

  it('degrades to an empty list so the create modal still works without the badge', async () => {
    const { store } = await loaded({
      authProviders: vi.fn().mockRejectedValue(new Error('not supported')),
    });

    expect(await store.authProviders('c-1')).toEqual([]);
  });

  it('answers empty for a container it does not hold', async () => {
    const authProviders = vi.fn();
    const { store } = await loaded({ authProviders });

    expect(await store.authProviders('c-missing')).toEqual([]);
    expect(authProviders).not.toHaveBeenCalled();
  });
});

describe('AgentSetupStore sessions', () => {
  const wire = (patch: Record<string, unknown> = {}) => ({
    id: 'sess-1', title: 'Monday digest', platform: 'cli', startedAt: 1, messages: 2,
    status: 'open', ...patch,
  });

  it('maps a listing onto the two states the UI shows', async () => {
    const { store } = await loaded({
      sessions: vi.fn().mockResolvedValue([wire(), wire({ id: 'sess-2', status: 'ended' })]),
    });

    expect(await store.sessions('a-atlas')).toEqual([
      expect.objectContaining({ id: 'sess-1', status: 'open' }),
      expect.objectContaining({ id: 'sess-2', status: 'closed' }),
    ]);
  });

  it('answers null when a listing fails, which the page shows as nothing loaded', async () => {
    const { store, ctx } = await loaded({
      sessions: vi.fn().mockRejectedValue(new Error('no session dir')),
    });

    expect(await store.sessions('a-atlas')).toBeNull();
    expect(liveError(ctx)).toBe('sessions load failed: no session dir');
  });

  // a plain user turn carries no tool call and no reasoning; the viewer binds those fields
  // either way, so the mapper names them rather than leaving them off the object
  it('reads one session\'s messages, filling in the turns a plain one omits', async () => {
    const messages = [{ role: 'user', content: 'status?', ts: 1 }];
    const { store } = await loaded({ sessionMessages: vi.fn().mockResolvedValue(messages) });

    expect(await store.sessionMessages('a-atlas', 'sess-1')).toEqual([{
      role: 'user', content: 'status?', ts: 1,
      toolName: null, toolCalls: null, reasoning: null,
    }]);
  });

  it('surfaces why a session could not be read', async () => {
    const { store, ctx } = await loaded({
      sessionMessages: vi.fn().mockRejectedValue(new Error('corrupt transcript')),
    });

    expect(await store.sessionMessages('a-atlas', 'sess-1')).toBeNull();
    expect(liveError(ctx)).toBe('session load failed: corrupt transcript');
  });

  it('deletes a session file, and does nothing for a profile it does not hold', async () => {
    const deleteSession = vi.fn().mockResolvedValue(undefined);
    const { store } = await loaded({ deleteSession });

    await store.deleteSession('a-atlas', 'sess-1');
    expect(deleteSession).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'atlas' }), 'sess-1');

    await store.deleteSession('a-ghost', 'sess-1');
    expect(deleteSession).toHaveBeenCalledTimes(1);
  });

  it('answers null for session reads on a profile it does not hold', async () => {
    const sessions = vi.fn();
    const { store } = await loaded({ sessions });

    expect(await store.sessions('a-ghost')).toBeNull();
    expect(await store.sessionMessages('a-ghost', 'sess-1')).toBeNull();
    expect(sessions).not.toHaveBeenCalled();
  });
});
