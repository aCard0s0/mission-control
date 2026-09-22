import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StoreSlices, liveError, storeSlices, stubBackend } from '../../testing/store';

// The probe, the first load fan-out and the pollers used to run only against a
// real backend. These drive the real HermesApi against a stubbed fetch, so the
// URL composition and the wire mapping are exercised together with the slice
// that asked for them.
const CONTAINER = {
  id: 'c-live', shortId: 'aa11bb2', name: 'hermes-live', hostId: 'dh-remote',
  status: 'running', image: 'nousresearch/hermes-agent', version: 'v2026.8.3',
  startedAt: 10, sizeRootFsGb: 2, profiles: ['atlas'],
};

const STOPPED = {
  ...CONTAINER, id: 'c-stopped', name: 'hermes-cold', status: 'stopped', startedAt: null,
};

const TEMPLATE = {
  id: 'pt-ops', name: 'ops-sre', description: 'SRE copilot', provider: 'anthropic',
  model: 'claude-fable-5', baseUrl: '', cwd: '/opt/data', soul: '', memory: '',
  skills: [], mcpServers: [], secrets: [], createdAt: 1, updatedAt: 2,
};

const CATALOG_SERVER = {
  id: 'mcp-browser', name: 'browser', description: '', kind: 'managed', hostId: 'dh-remote',
  transport: 'http', url: null, image: 'mcp/playwright:latest', platform: null,
  entrypoint: [], command: [], stdioCommand: null, args: [], internalPort: 1100,
  publishedPort: null, path: '/mcp', crossHostUrl: null, connectionUrl: 'http://browser:1100/mcp',
  headers: [], environment: [], volumes: [], healthcheck: null, supportServices: [],
  desiredState: 'stopped', runtimeState: 'stopped', operationState: 'idle', operationError: null,
  checkStatus: 'unknown', checkError: null, checkedAt: null, latencyMs: null, linkedAgents: 0, imageAsOf: null, imageUpdate: null,
  revision: 1, appliedRevision: 1, pendingChanges: false, serviceKey: 'browser',
  createdAt: 1, updatedAt: 1,
};

const PROFILE = {
  id: 'a-live', containerId: 'c-live', name: 'atlas', role: 'Ops', state: 'active',
  provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…9f2c', cwd: '/srv/ops',
  soul: '# SOUL', memoryMd: '# MEMORY', configYaml: 'provider: anthropic',
  skills: [{ id: 's1', name: 'ops', source: 'bundled', version: '1', description: '', enabled: true }],
  mcp: [{ id: 'm1', name: 'github', transport: 'http', status: 'connected', tools: 4, latencyMs: 30 }],
  integrations: [{ kind: 'filesystem', status: 'up', detail: '/srv/ops (rw)' }],
  lastActive: 20,
};

/**
 * A store already holding one running container and one profile, loaded the way
 * the pollers load them. The describes below are about what happens after the
 * inventory is there, so each starts from this rather than from an empty store.
 */
const loaded = async (): Promise<StoreSlices> => {
  const store = storeSlices();
  stubBackend(store.ctx, {
    containers: { list: vi.fn().mockResolvedValue([CONTAINER, STOPPED]) },
    agents: { list: vi.fn().mockResolvedValue([PROFILE, { ...PROFILE, id: 'a-cold', containerId: 'c-stopped' }]) },
    mcp: { list: vi.fn().mockResolvedValue([CATALOG_SERVER]) },
    templates: { list: vi.fn().mockResolvedValue([TEMPLATE]) },
  });
  await store.containers.refresh();
  await store.agents.refresh();
  await store.catalog.refresh();
  await store.templates.refresh();
  return store;
};


/** The slices as the application boots them: DI builds the graph, and the app
 *  initializer starts the probe. Nothing else in the store starts the clock. */
const booted = () => {
  const store = storeSlices();
  void store.liveSync.probeBackend();
  return store;
};

describe('LiveSync bootstrap', () => {
  /** Answers the endpoints the first live load touches; anything else 404s so a
   *  missing route shows up as a failure rather than as empty state. */
  const backend = (overrides: Record<string, unknown> = {}) => {
    const routes: Record<string, unknown> = {
      '/health': { status: 'ok', version: '1.0.0', dockerConnected: true },
      '/api/hosts': [{
        id: 'dh-remote', name: 'remote', url: 'tcp://10.0.0.2:2375', kind: 'remote',
        status: 'connected', engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 12, note: null,
      }],
      '/api/inference-endpoints': [],
      '/api/providers': [{ key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true, envVar: 'ANTHROPIC_API_KEY' }],
      '/api/containers': [CONTAINER],
      '/api/board/tasks': [{ id: 't1', containerId: 'c-live', agentId: null, title: 'Ship it', column: 'queued', priority: 'high', tags: null, createdAt: 1 }],
      '/api/profile-templates': [{ id: 'pt1', name: 'ops-template', createdAt: 1, updatedAt: 2 }],
      '/api/mcp-servers': [],
      '/api/mcp-servers/retained-resources': [],
      '/api/agents': [PROFILE],
      '/api/containers/dh-remote/stats': {
        'c-live': {
          cpuPercent: 12, ramMb: 512, ramTotalMb: 4096, rxBytes: 1_024_000, txBytes: 512_000,
          sampledAt: 1_000,
        },
      },
      '/api/containers/dh-remote/c-live/logs': [{ ts: 5, level: 'info', source: 'system', msg: 'ready' }],
      '/api/images/tags': { repository: 'nousresearch/hermes-agent', tags: ['v2026.8.3'], registryStatus: 'ok' },
      ...overrides,
    };
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      calls.push(url);
      const path = url.split('?')[0];
      const body = routes[path];
      return Promise.resolve(body === undefined
        ? new Response(JSON.stringify({ error: `no route for ${path}` }), { status: 404 })
        : new Response(JSON.stringify(body)));
    }));
    return calls;
  };

  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    setHidden(false);
  });

  /** jsdom always reports a visible tab and exposes no way to change it. */
  const setHidden = (hidden: boolean) => {
    Object.defineProperty(document, 'hidden', { value: hidden, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  };

  it('starts empty and says it is connecting, before any answer arrives', () => {
    backend();
    const store = booted();

    expect(store.ctx.backendStatus()).toBe('connecting');
    expect(store.containers.containers()).toEqual([]);
    expect(store.agents.agents()).toEqual([]);
    expect(store.liveSync.notice()).toBe('connecting to backend…');
    // the local docker row is a placeholder until the backend reports hosts
    expect(store.hosts.hosts()).toHaveLength(1);
    expect(store.hosts.overall()).toBe('disconnected');
  });

  it('reports an unreachable backend and keeps retrying', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new Error('connection refused')));
    vi.stubGlobal('fetch', fetchMock);
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);

    expect(store.ctx.backendStatus()).toBe('unreachable');
    expect(store.liveSync.notice()).toContain('backend unreachable');
    const attempts = fetchMock.mock.calls.length;

    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetchMock.mock.calls.length).toBeGreaterThan(attempts);
  });

  it('names the configured base url in the banner, so a wrong one is visible', async () => {
    window.__MC_CONFIG__ = {
      apiBaseUrl: 'http://mc.internal:9999', dockerSocket: 'unix:///x',
    };
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('refused'))));
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);

    expect(store.liveSync.notice()).toContain('http://mc.internal:9999');
  });

  it('loads every domain once the backend answers, and drops the banner', async () => {
    backend();
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);

    expect(store.ctx.backendStatus()).toBe('connected');
    expect(store.liveSync.notice()).toBeNull();
    expect(store.hosts.hosts()[0]).toMatchObject({ id: 'dh-remote', status: 'connected' });
    expect(store.hosts.overall()).toBe('connected');
    expect(store.containers.containers()[0]).toMatchObject({ id: 'c-live', version: 'v2026.8.3', disk: 2 });
    expect(store.providers.llmProviders()[0]).toMatchObject({ key: 'anthropic' });
    expect(store.templates.templates()[0]).toMatchObject({ id: 'pt1', name: 'ops-template' });
    expect(store.containers.selectedContainerId()).toBe('c-live');
  });

  it('leaves a library nothing but its own page reads to that page', async () => {
    const calls = backend();
    booted();
    await vi.advanceTimersByTimeAsync(0);

    const paths = calls.map(url => url.split('?')[0]);
    // read by a dialog, a panel or a picker that never loads them, so boot must
    expect(paths).toEqual(expect.arrayContaining(['/api/skills', '/api/credentials']));
    // loaded by the page that owns them, on entry. Fetching them here as well meant a deep
    // link to that page asked for each of them twice at once
    expect(paths).not.toContain('/api/prompts');
    expect(paths).not.toContain('/api/prompt-groups');
    expect(paths).not.toContain('/api/skill-groups');
    expect(paths).not.toContain('/api/skill-guides');
    expect(paths).not.toContain('/api/mcp-groups');
  });

  it('adopts the profiles of every container it found', async () => {
    backend();
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);

    expect(store.agents.forSelectedContainer()[0]).toMatchObject({
      id: 'a-live', name: 'atlas', provider: 'anthropic', soul: '# SOUL',
    });
    // absent wire fields become the model's defaults rather than undefined
    expect(store.agents.byId('a-live')).toMatchObject({
      sessions: [], msgsToday: 0, errorRate: 0,
    });
    expect(store.agents.byId('a-live')?.mcp[0]).toMatchObject({
      name: 'github', enabled: true, origin: 'custom', error: null, checkedAt: null,
    });
  });

  it('fills the board and the logs of the container it selected', async () => {
    backend();
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);

    expect(store.board.forSelectedContainer()[0]).toMatchObject({ id: 't1', agentId: '', tags: [] });
    expect(store.logs.selectedLogs()[0]).toMatchObject({ msg: 'ready', agentId: null });
    expect(store.logs.updatedAt()).not.toBeNull();
  });

  it('keeps polling each domain on its own period', async () => {
    const calls = backend();
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);
    const containerPolls = calls.filter(u => u === '/api/containers').length;
    const statsPolls = calls.filter(u => u.includes('/stats')).length;

    await vi.advanceTimersByTimeAsync(10_000);

    expect(calls.filter(u => u === '/api/containers').length).toBeGreaterThan(containerPolls);
    // stats run far more often than the inventory
    expect(calls.filter(u => u.includes('/stats')).length).toBeGreaterThan(statsPolls + 1);
    expect(store.ctx.backendStatus()).toBe('connected');
  });

  it('polls nothing while the tab is hidden', async () => {
    const calls = backend();
    booted();
    await vi.advanceTimersByTimeAsync(0);
    setHidden(true);
    const beforeHidden = calls.length;

    await vi.advanceTimersByTimeAsync(30_000);

    // three inventory periods and ten stats periods, every one of which ends at a Docker
    // daemon — answering questions a backgrounded tab is not showing anyone
    expect(calls.length).toBe(beforeHidden);
  });

  it('catches up the polls that came due while it was hidden', async () => {
    const calls = backend();
    booted();
    await vi.advanceTimersByTimeAsync(0);
    setHidden(true);
    await vi.advanceTimersByTimeAsync(30_000);
    const whileHidden = calls.length;

    setHidden(false);
    await vi.advanceTimersByTimeAsync(0);

    // coming back to the tab shows current state, not whatever the last foreground tick left
    expect(calls.length).toBeGreaterThan(whileHidden);
  });

  it('raises the banner again when the backend dies mid-session', async () => {
    // every domain poll keeps its last state quietly on failure, so the health poll is the
    // only thing that can say the screen has gone stale — 'connected' used to be one-shot
    backend();
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.ctx.backendStatus()).toBe('connected');

    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('gone'))));
    await vi.advanceTimersByTimeAsync(10_000);

    expect(store.ctx.backendStatus()).toBe('unreachable');
    expect(store.liveSync.notice()).toContain('backend unreachable');
  });

  it('drops the banner once the backend answers again', async () => {
    backend();
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);
    const inventory = store.containers.containers().map(c => c.id);

    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('gone'))));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(store.ctx.backendStatus()).toBe('unreachable');

    backend();   // the backend comes back
    await vi.advanceTimersByTimeAsync(10_000);

    expect(store.ctx.backendStatus()).toBe('connected');
    expect(store.liveSync.notice()).toBeNull();
    // held throughout the outage, refreshed by the domain polls once it was back
    expect(store.containers.containers().map(c => c.id)).toEqual(inventory);
  });

  it('retries the whole first load when part of it fails, instead of freezing behind a connected banner', async () => {
    const calls = backend();
    const store = storeSlices();
    // every refresh swallows its own transport errors, so a start() failure has to be
    // injected above them — the case this guards is any future load that can reject
    vi.spyOn(store.hosts, 'refresh').mockRejectedValueOnce(new Error('boom'));
    void store.liveSync.probeBackend();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.ctx.backendStatus()).toBe('unreachable');

    await vi.advanceTimersByTimeAsync(10_000);
    expect(store.ctx.backendStatus()).toBe('connected');

    // the retry must run start() again — a started flag left set made it a no-op:
    // no pollers, a frozen dashboard, and a banner saying connected
    const afterRetry = calls.filter(u => u === '/api/containers').length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(calls.filter(u => u === '/api/containers').length).toBeGreaterThan(afterRetry);
  });

  it('never polls webhooks — that read fans out one exec per profile, and only its page shows it', async () => {
    const calls = backend();
    booted();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(60_000);

    expect(calls.some(url => url.includes('/webhooks'))).toBe(false);
  });

  it('survives a backend that answers health and then falls over', async () => {
    const routes = backend();
    const store = booted();
    await vi.advanceTimersByTimeAsync(0);
    const loaded = store.containers.containers();
    expect(loaded).toHaveLength(1);

    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('gone'))));
    await vi.advanceTimersByTimeAsync(15_000);

    // a failed refresh keeps the last known inventory rather than blanking the UI
    expect(store.containers.containers()).toEqual(loaded);
    expect(store.agents.agents()).toHaveLength(1);
    expect(routes.length).toBeGreaterThan(0);
  });
});

// The pollers and the catalog lifecycle carry the arithmetic and the state
// machines that a pass-through action does not.
describe('LiveSync pollers', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('derives network rate from the byte counters, not from the counters', async () => {
    const store = await loaded();
    const target = store.containers.containers().find(c => c.status === 'running')!;
    // every running container is sampled each tick, so the queue is per container
    const queued = [
      { cpuPercent: 10, ramMb: 400, ramTotalMb: 4096, rxBytes: 0, txBytes: 0, sampledAt: 1_000 },
      { cpuPercent: 20, ramMb: 500, ramTotalMb: 4096, rxBytes: 1_024_000, txBytes: 512_000, sampledAt: 2_000 },
    ];
    const idle = { cpuPercent: 1, ramMb: 1, ramTotalMb: 2, rxBytes: 0, txBytes: 0, sampledAt: 1_000 };
    const statsBatch = vi.fn((_hostId: string, ids: string[]) =>
      Promise.resolve(Object.fromEntries(ids.map(id =>
        [id, id === target.id ? queued.shift() ?? idle : idle]))));
    stubBackend(store.ctx, { containers: { statsBatch } });
    const pollStats = () => store.containers.pollStats();

    // the first sample has nothing to compare against, so the rate reads zero
    await pollStats();
    expect(store.containers.containers().find(c => c.id === target.id)).toMatchObject({
      cpu: 10, ram: 400, netIn: 0, netOut: 0,
    });

    await pollStats();
    // 1,024,000 bytes over one second, in KB/s
    expect(store.containers.containers().find(c => c.id === target.id)).toMatchObject({
      cpu: 20, netIn: 1_000, netOut: 500,
    });
  });

  it('never reports a negative rate when a counter resets', async () => {
    const store = await loaded();
    // a restarted container reports counters below the last sample
    const counters = [5_000_000, 0];
    let tick = 0;
    const statsBatch = vi.fn((_hostId: string, ids: string[]) =>
      Promise.resolve(Object.fromEntries(ids.map(id => [id, {
        cpuPercent: 5, ramMb: 1, ramTotalMb: 2,
        rxBytes: counters[Math.min(tick, 1)], txBytes: counters[Math.min(tick, 1)],
        sampledAt: 1_000 + tick * 1_000,
      }]))));
    stubBackend(store.ctx, { containers: { statsBatch } });
    const pollStats = () => store.containers.pollStats();

    await pollStats();
    tick = 1;
    await pollStats();

    expect(store.containers.containers().every(c => c.netIn >= 0 && c.netOut >= 0)).toBe(true);
  });

  it('does not ask a stopped container for its profiles, and keeps the ones it had', async () => {
    const store = await loaded();
    const stopped = store.containers.containers().find(c => c.status === 'stopped')!;
    const kept = store.agents.agents().filter(a => a.containerId === stopped.id);
    expect(kept.length).toBeGreaterThan(0);
    const list = vi.fn().mockResolvedValue([]);
    stubBackend(store.ctx, { agents: { list } });

    await store.agents.refresh();

    expect(list.mock.calls.some(call => call[1] === stopped.id)).toBe(false);
    expect(store.agents.agents().filter(a => a.containerId === stopped.id)).toEqual(kept);
  });

  it('keeps the last known profiles of a container that failed this tick', async () => {
    const store = await loaded();
    const running = store.containers.containers().find(c => c.status === 'running')!;
    const kept = store.agents.agents().filter(a => a.containerId === running.id);
    stubBackend(store.ctx, { agents: { list: vi.fn().mockRejectedValue(new Error('daemon busy')) } });

    await store.agents.refresh();

    expect(store.agents.agents().filter(a => a.containerId === running.id)).toEqual(kept);
  });

  it('leaves an in-flight MCP probe alone while a refresh lands', async () => {
    const store = await loaded();
    const agent = store.agents.agents().find(a => a.mcp.length)!;
    const container = store.containers.containers().find(c => c.id === agent.containerId)!;
    store.agents.update(agent.id, (a: any) => ({
      ...a, mcp: a.mcp.map((m: any, i: number) => i === 0 ? { ...m, status: 'checking' } : m),
    }));
    const probing = agent.mcp[0].name;
    stubBackend(store.ctx, {
      agents: {
        list: vi.fn().mockImplementation((_host: string, containerId: string) => Promise.resolve(
          containerId === container.id
            ? [{ ...agent, mcp: agent.mcp.map(m => ({ ...m, status: 'unknown' })) }]
            : [])),
      },
    });

    await store.agents.refresh();

    const refreshed = store.agents.byId(agent.id)!;
    expect(refreshed.mcp.find(m => m.name === probing)?.status).toBe('checking');
    expect(refreshed.mcp.filter(m => m.name !== probing).every(m => m.status === 'unknown')).toBe(true);
  });
});

describe('live MCP catalog lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('shows the operation as in flight, then polls until the backend settles', async () => {
    const store = await loaded();
    const server = store.catalog.servers().find(s => s.kind === 'managed')!;
    const run = vi.fn().mockResolvedValue({ ...server, operationState: 'pulling' });
    const list = vi.fn().mockResolvedValue([{
      ...server, operationState: 'idle', runtimeState: 'running', desiredState: 'running',
    }]);
    stubBackend(store.ctx, { mcp: { run, list } });

    const started = store.catalog.start(server.id);
    await vi.advanceTimersByTimeAsync(0);
    // the row reports the pull the backend is doing, not a finished start
    expect(store.catalog.byId(server.id)?.operationState).toBe('pulling');

    await vi.advanceTimersByTimeAsync(1_500);
    expect(await started).toBe(true);
    expect(store.catalog.byId(server.id)).toMatchObject({
      operationState: 'idle', runtimeState: 'running',
    });
    expect(list).toHaveBeenCalled();
  });

  it('records why an operation failed, on the row and in a toast', async () => {
    const store = await loaded();
    const server = store.catalog.servers().find(s => s.kind === 'managed')!;
    stubBackend(store.ctx, { mcp: { run: vi.fn().mockRejectedValue(new Error('port 5432 already published')) } });

    expect(await store.catalog.start(server.id)).toBe(false);

    expect(store.catalog.byId(server.id)).toMatchObject({
      operationState: 'error', operationError: 'port 5432 already published',
    });
    expect(liveError(store.ctx)).toBe('MCP server start failed: port 5432 already published');
  });

  it('answers a check with what the probe found, leaving the operation state alone', async () => {
    const store = await loaded();
    const server = store.catalog.servers().find(s => s.kind === 'managed')!;
    stubBackend(store.ctx, {
      mcp: {
        run: vi.fn().mockResolvedValue({
          ...server, checkStatus: 'connected', latencyMs: 18, checkedAt: 99,
        }),
      },
    });

    expect(await store.catalog.check(server.id)).toBe(true);
    expect(store.catalog.byId(server.id)).toMatchObject({
      checkStatus: 'connected', latencyMs: 18, checkedAt: 99, operationState: 'idle',
    });
  });

  it('reports a failed check without claiming the server stopped', async () => {
    const store = await loaded();
    const server = store.catalog.servers().find(s => s.kind === 'managed')!;
    stubBackend(store.ctx, { mcp: { run: vi.fn().mockRejectedValue(new Error('handshake timeout')) } });

    expect(await store.catalog.check(server.id)).toBe(false);
    expect(store.catalog.byId(server.id)).toMatchObject({
      checkStatus: 'error', checkError: 'handshake timeout',
      operationState: 'idle', operationError: null,
    });
  });

  it('refuses to write an Agent config against a server that never came up', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    const container = store.containers.containers().find(c => c.id === agent.containerId)!;
    const server = store.catalog.servers().find(s => s.kind === 'managed' && s.hostId === container.hostId)!;
    const connectCatalog = vi.fn();
    stubBackend(store.ctx, {
      mcp: {
        run: vi.fn().mockResolvedValue({ ...server, operationState: 'starting' }),
        list: vi.fn().mockResolvedValue([{
          ...server, operationState: 'error', runtimeState: 'error',
          operationError: 'image pull failed',
        }]),
      },
      agents: { mcp: { connectCatalog } },
    });

    const connecting = store.agentMcp.connectCatalog(agent.id, server.id, 'browser');
    await vi.advanceTimersByTimeAsync(2_000);

    expect(await connecting).toBe(false);
    expect(connectCatalog).not.toHaveBeenCalled();
    expect(liveError(store.ctx)).toContain('MCP server start failed: image pull failed');
  });
});

// The registries and the profile-scoped writes share one shape: apply what the
// backend answered, or toast and leave the last known state alone.
describe('live registries', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('adds a docker host by asking the backend, then re-reading the list', async () => {
    const store = await loaded();
    const add = vi.fn().mockResolvedValue({});
    const list = vi.fn().mockResolvedValue([{
      id: 'dh-new', name: 'prod', url: 'tcp://10.0.0.2:2375', kind: 'remote',
      status: 'connected', engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 9, note: null,
    }]);
    stubBackend(store.ctx, { hosts: { add, list } });

    store.hosts.add('prod', 'tcp://10.0.0.2:2375');
    await vi.advanceTimersByTimeAsync(0);

    expect(add).toHaveBeenCalledWith('prod', 'tcp://10.0.0.2:2375');
    expect(store.hosts.hosts()).toEqual([expect.objectContaining({ id: 'dh-new' })]);
  });

  it('reports a refused host and re-reads rather than inventing a state', async () => {
    const store = await loaded();
    const list = vi.fn().mockResolvedValue([]);
    stubBackend(store.ctx, {
      hosts: { check: vi.fn().mockRejectedValue(new Error('x509: certificate expired')), list },
    });

    store.hosts.check('dh-local');
    await vi.advanceTimersByTimeAsync(0);

    expect(liveError(store.ctx)).toBe('host check failed: x509: certificate expired');
    expect(list).toHaveBeenCalled();
  });

  it('never removes the local socket, backend or not', async () => {
    const store = await loaded();
    const remove = vi.fn();
    stubBackend(store.ctx, { hosts: { remove } });

    store.hosts.remove('dh-local');

    expect(remove).not.toHaveBeenCalled();
  });

  it('keeps the bootstrap provider mirror when the registry cannot be read', async () => {
    const store = await loaded();
    const mirrored = store.providers.llmProviders();
    stubBackend(store.ctx, { providers: { registry: vi.fn().mockRejectedValue(new Error('offline')) } });

    await store.providers.refreshRegistry();

    expect(store.providers.llmProviders()).toEqual(mirrored);
  });

  it('ignores an empty registry, which would leave the picker unusable', async () => {
    const store = await loaded();
    const mirrored = store.providers.llmProviders();
    stubBackend(store.ctx, { providers: { registry: vi.fn().mockResolvedValue([]) } });

    await store.providers.refreshRegistry();

    expect(store.providers.llmProviders()).toEqual(mirrored);
  });

  it('answers an empty model list when a catalog lookup fails', async () => {
    const store = await loaded();
    stubBackend(store.ctx, {
      providers: {
        modelCatalog: vi.fn().mockRejectedValue(new Error('provider 502')),
        modelCatalogLive: vi.fn().mockRejectedValue(new Error('bad key')),
      },
    });

    const fromConfig = await store.providers.modelCatalog('anthropic');
    const fromKey = await store.providers.modelCatalogLive('anthropic', { apiKey: 'sk-ant-x' });

    // no shipped copy stands in: an unreachable backend shows nothing, here as everywhere
    expect(fromConfig).toEqual({ models: [], source: null });
    expect(fromKey).toEqual(fromConfig);
  });

  it('hands a failed model list to the page, and keeps a failed pull poll quiet', async () => {
    const store = await loaded();
    stubBackend(store.ctx, {
      endpoints: {
        models: vi.fn().mockRejectedValue(new Error('ollama down')),
        pullStatus: vi.fn().mockRejectedValue(new Error('ollama down')),
      },
    });

    // the page renders the reason inline; the picker in the create dialog has its own fallback
    await expect(store.endpoints.models('mp-local')).rejects.toThrow('ollama down');
    // pull progress is polled, so that read still degrades to empty without a toast per tick
    expect(await store.endpoints.pullStatus('mp-local')).toEqual([]);
    expect(liveError(store.ctx)).toBeNull();
  });

  it('creates a template, then updates that same one', async () => {
    const store = await loaded();
    const create = vi.fn().mockResolvedValue({ id: 'pt-new', name: 'ops', createdAt: 1, updatedAt: 1 });
    const update = vi.fn().mockResolvedValue({ id: 'pt-new', name: 'ops v2', createdAt: 1, updatedAt: 2 });
    stubBackend(store.ctx, { templates: { create, update } });
    const input = {
      name: 'ops', icon: '', description: '', category: 'ops', provider: 'anthropic', model: 'claude-fable-5',
      baseUrl: '', cwd: '', soul: '', memory: '', skills: [], librarySkillIds: [], guideIds: [],
      mcpServers: [], secrets: [],
    };

    expect(await store.templates.save(input)).toBe('pt-new');
    expect(create).toHaveBeenCalledWith(input);

    expect(await store.templates.save({ ...input, name: 'ops v2' }, 'pt-new')).toBe('pt-new');
    expect(update).toHaveBeenCalledWith('pt-new', expect.objectContaining({ name: 'ops v2' }));
    // the row is replaced, not duplicated
    expect(store.templates.templates().filter(t => t.id === 'pt-new')).toHaveLength(1);
    expect(store.templates.byId('pt-new')?.name).toBe('ops v2');
  });

  it('reports a failed template save as an empty id, so the editor stays open', async () => {
    const store = await loaded();
    const before = store.templates.templates().length;
    stubBackend(store.ctx, { templates: { create: vi.fn().mockRejectedValue(new Error('name taken')) } });

    expect(await store.templates.save({
      name: 'ops', icon: '', description: '', category: '', provider: '', model: '', baseUrl: '', cwd: '',
      soul: '', memory: '', skills: [], librarySkillIds: [], guideIds: [], mcpServers: [], secrets: [],
    })).toBe('');
    expect(liveError(store.ctx)).toBe('save template failed: name taken');
    expect(store.templates.templates()).toHaveLength(before);
  });

  it('keeps a template the backend refused to delete', async () => {
    const store = await loaded();
    const target = store.templates.templates()[0];
    stubBackend(store.ctx, { templates: { remove: vi.fn().mockRejectedValue(new Error('in use')) } });

    await store.templates.remove(target.id);

    expect(store.templates.byId(target.id)).not.toBeNull();
    expect(liveError(store.ctx)).toBe('delete template failed: in use');
  });

  it('adopts the profile a template deploy created', async () => {
    const store = await loaded();
    const template = store.templates.templates()[0];
    const container = store.containers.containers()[0];
    const deploy = vi.fn().mockResolvedValue({
      id: 'a-deployed', containerId: container.id, name: 'from-template', role: 'ops',
      state: 'idle', provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '',
      cwd: '/srv', soul: '', memoryMd: '', configYaml: '', skills: [], mcp: [],
      integrations: [], lastActive: 1,
    });
    stubBackend(store.ctx, { templates: { deploy } });

    expect(await store.templates.deploy(template.id, container.id, 'from-template')).toBe('a-deployed');
    expect(deploy).toHaveBeenCalledWith(template.id, {
      hostId: container.hostId, containerId: container.id, name: 'from-template',
    });
    expect(store.agents.byId('a-deployed')).toMatchObject({ name: 'from-template' });
  });
});

describe('live profile writes', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  /** A profile payload shaped like the backend's, echoing one field back. */
  const echo = (agent: { id: string; containerId: string; name: string }, patch: object = {}) => ({
    id: agent.id, containerId: agent.containerId, name: agent.name, role: 'ops', state: 'idle',
    provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…key', cwd: '/srv',
    soul: '', memoryMd: '', configYaml: '', skills: [], mcp: [], integrations: [], lastActive: 1,
    ...patch,
  });

  it('applies the profile a config save answered with', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    stubBackend(store.ctx, {
      agents: {
        updateConfig: vi.fn().mockResolvedValue(echo(agent, { configYaml: 'model: from-backend' })),
      },
    });

    expect(await store.agents.updateConfig(agent.id, 'model: typed')).toBe(true);
    // what lands is the backend's version, not the text that was typed
    expect(store.agents.byId(agent.id)?.configYaml).toBe('model: from-backend');
  });

  it('reports a rejected config save and keeps the file as it was', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    const original = agent.configYaml;
    stubBackend(store.ctx, {
      agents: { updateConfig: vi.fn().mockRejectedValue(new Error('invalid yaml at line 3')) },
    });

    expect(await store.agents.updateConfig(agent.id, 'broken: [')).toBe(false);
    expect(store.agents.byId(agent.id)?.configYaml).toBe(original);
    expect(liveError(store.ctx)).toBe('config save failed: invalid yaml at line 3');
  });

  it('applies the skill list a toggle answered with', async () => {
    const store = await loaded();
    const agent = store.agents.agents().find(a => a.skills.length)!;
    const skill = agent.skills[0];
    const setEnabled = vi.fn().mockResolvedValue(echo(agent, {
      skills: [{ ...skill, enabled: !skill.enabled }],
    }));
    stubBackend(store.ctx, { agents: { skills: { setEnabled } } });

    store.skills.toggle(agent.id, skill.id);
    await vi.advanceTimersByTimeAsync(0);

    expect(setEnabled).toHaveBeenCalledWith(
      expect.objectContaining({ name: agent.name }), skill.name, !skill.enabled);
    expect(store.agents.byId(agent.id)?.skills).toEqual([
      expect.objectContaining({ name: skill.name, enabled: !skill.enabled }),
    ]);
  });

  it('answers null for a failed setup read, and says why', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    stubBackend(store.ctx, {
      agents: { setup: vi.fn().mockRejectedValue(new Error('hermes status timed out')) },
    });

    expect(await store.setup.setup(agent.id)).toBeNull();
    expect(liveError(store.ctx)).toBe('setup load failed: hermes status timed out');
  });

  it('returns the refreshed setup an env write answered with', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    const answered = {
      envPath: `/opt/data/profiles/${agent.name}/.env`, envExists: true,
      apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: true, masked: '…9f2c', problem: null }],
      authProviders: [], apiKeyProviders: [], messaging: [],
    };
    const setEnv = vi.fn().mockResolvedValue(answered);
    stubBackend(store.ctx, { agents: { setEnv } });

    expect(await store.setup.setEnv(agent.id, [{ key: 'ANTHROPIC_API_KEY', value: 'sk-ant-x' }]))
      .toEqual(answered);
    expect(setEnv).toHaveBeenCalledWith(
      expect.objectContaining({ name: agent.name }),
      [{ key: 'ANTHROPIC_API_KEY', value: 'sk-ant-x' }]);
  });

  it('degrades an auth-provider read to an empty list, so the modal still opens', async () => {
    const store = await loaded();
    const container = store.containers.containers()[0];
    stubBackend(store.ctx, {
      agents: { authProviders: vi.fn().mockRejectedValue(new Error('no default profile')) },
    });

    expect(await store.setup.authProviders(container.id)).toEqual([]);
    expect(liveError(store.ctx)).toBeNull();
  });

  it('drops a removed profile only after the backend confirms it', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    stubBackend(store.ctx, { agents: { remove: vi.fn().mockRejectedValue(new Error('profile busy')) } });

    store.removal.remove(agent.id);
    await vi.advanceTimersByTimeAsync(0);

    expect(store.agents.byId(agent.id)).not.toBeNull();
    expect(liveError(store.ctx)).toBe('remove profile failed: profile busy');
  });
});

// Reading a profile's setup runs `hermes status` inside the container, which
// takes seconds. The cache is what keeps a tab switch from paying for it again,
// so its rules are worth pinning: read once, replace on write, force on refresh.
describe('live setup cache', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  const answer = (name: string, patch: object = {}) => ({
    envPath: `/opt/data/profiles/${name}/.env`, envExists: true,
    apiKeys: [], authProviders: [], apiKeyProviders: [], messaging: [], ...patch,
  });

  it('reads a profile once and serves the cached copy after that', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    const setup = vi.fn().mockResolvedValue(answer(agent.name));
    stubBackend(store.ctx, { agents: { setup } });

    expect(store.setup.setupOf(agent.id)).toBeNull();
    await store.setup.setup(agent.id);
    await store.setup.setup(agent.id);

    expect(setup).toHaveBeenCalledTimes(1);
    expect(store.setup.setupOf(agent.id)).toMatchObject({ envExists: true });
  });

  it('re-reads only when the caller forces it', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    const setup = vi.fn()
      .mockResolvedValueOnce(answer(agent.name, { envExists: false }))
      .mockResolvedValueOnce(answer(agent.name, { envExists: true }));
    stubBackend(store.ctx, { agents: { setup } });

    await store.setup.setup(agent.id);
    expect(store.setup.setupOf(agent.id)?.envExists).toBe(false);

    await store.setup.setup(agent.id, true);
    expect(setup).toHaveBeenCalledTimes(2);
    expect(store.setup.setupOf(agent.id)?.envExists).toBe(true);
  });

  it('caches each profile separately', async () => {
    const store = await loaded();
    const [first, second] = store.agents.agents();
    stubBackend(store.ctx, {
      agents: {
        setup: vi.fn().mockImplementation((ref: { name: string }) =>
          Promise.resolve(answer(ref.name))),
      },
    });

    await store.setup.setup(first.id);
    await store.setup.setup(second.id);

    expect(store.setup.setupOf(first.id)?.envPath).toContain(first.name);
    expect(store.setup.setupOf(second.id)?.envPath).toContain(second.name);
  });

  it('collapses two concurrent reads of the same profile into one call', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    let release!: (value: unknown) => void;
    const pending = new Promise(resolve => { release = resolve; });
    const setup = vi.fn().mockReturnValue(pending.then(() => answer(agent.name)));
    stubBackend(store.ctx, { agents: { setup } });

    const first = store.setup.setup(agent.id);
    const second = store.setup.setup(agent.id);
    expect(store.setup.isSetupLoading(agent.id)).toBe(true);
    release(null);
    await first;
    await second;

    expect(setup).toHaveBeenCalledTimes(1);
    expect(store.setup.isSetupLoading(agent.id)).toBe(false);
  });

  it('replaces the cached copy with what a write answered', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    stubBackend(store.ctx, {
      agents: {
        setup: vi.fn().mockResolvedValue(answer(agent.name, { envExists: false })),
        setEnv: vi.fn().mockResolvedValue(answer(agent.name, {
          envExists: true,
          apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: true, masked: '…9f2c', problem: null }],
        })),
        initEnv: vi.fn().mockResolvedValue(answer(agent.name, { envExists: true })),
      },
    });

    await store.setup.setup(agent.id);
    await store.setup.setEnv(agent.id, [{ key: 'ANTHROPIC_API_KEY', value: 'sk-ant-x' }]);

    expect(store.setup.setupOf(agent.id)).toMatchObject({
      envExists: true, apiKeys: [expect.objectContaining({ set: true })],
    });
  });

  it('keeps the last good copy when a refresh fails', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    const setup = vi.fn()
      .mockResolvedValueOnce(answer(agent.name))
      .mockRejectedValueOnce(new Error('hermes status timed out'));
    stubBackend(store.ctx, { agents: { setup } });

    await store.setup.setup(agent.id);
    expect(await store.setup.setup(agent.id, true)).toBeNull();

    expect(store.setup.setupOf(agent.id)).toMatchObject({ envExists: true });
    expect(liveError(store.ctx)).toBe('setup load failed: hermes status timed out');
  });

  it('forgets a deleted profile\'s credentials along with the profile', async () => {
    const store = await loaded();
    const agent = store.agents.agents()[0];
    stubBackend(store.ctx, {
      agents: {
        setup: vi.fn().mockResolvedValue(answer(agent.name)),
        remove: vi.fn().mockResolvedValue(undefined),
      },
    });
    await store.setup.setup(agent.id);
    expect(store.setup.setupOf(agent.id)).not.toBeNull();

    store.removal.remove(agent.id);
    await vi.advanceTimersByTimeAsync(0);

    expect(store.setup.setupOf(agent.id)).toBeNull();
  });
});

