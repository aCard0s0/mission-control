import { describe, expect, it } from 'vitest';
import {
  ApiAgentProfile, ApiAgentSetup, ApiImageTags, ApiMcpCatalogServer,
  ApiModelProvider, ApiInferenceEndpoint, ApiProfileTemplate, ApiSession,
} from '../hermes-api';
import {
  toAgentProfile, toAgentSetup, toChatMessage, toDockerHost, toImageCatalog, toLlmProvider,
  toLogEntry, toMcpCatalogServer, toMcpRetainedResource, toInferenceEndpoint, toEndpointModel,
  toProfileTemplate, toPullState, toServerInfo, toSessionInfo,
} from './wire-mappers';

/**
 * These mappers exist because the wire is looser than the model: a field the
 * current backend always sends may be missing from a row an older one wrote, and
 * both have to render. So each case here is a payload that is legal on the wire
 * and would otherwise reach a template as undefined.
 */
describe('toAgentProfile', () => {
  const minimal = {
    id: 'a-1', containerId: 'c-1', name: 'atlas', role: 'Ops', state: 'idle',
    provider: 'anthropic', model: 'm', cwd: '/opt/data', soul: '', memoryMd: '',
    configYaml: '', lastActive: 20,
  } as ApiAgentProfile;

  it('fills in the lists a sparse row leaves out', () => {
    const profile = toAgentProfile(minimal);

    expect(profile).toMatchObject({
      apiKeyMasked: '', skills: [], mcp: [], integrations: [], sessions: [],
      msgsToday: 0, tokensToday: 0, errorRate: 0,
    });
  });

  it('treats an MCP server as enabled unless it says otherwise', () => {
    const enabled = toAgentProfile({ ...minimal, mcp: [server({})] }).mcp[0];
    const disabled = toAgentProfile({ ...minimal, mcp: [server({ enabled: false })] }).mcp[0];

    expect(enabled.enabled).toBe(true);
    expect(disabled.enabled).toBe(false);
  });

  it('treats anything but an explicit catalog origin as a directly-edited server', () => {
    expect(toAgentProfile({ ...minimal, mcp: [server({})] }).mcp[0].origin).toBe('custom');
    expect(toAgentProfile({ ...minimal, mcp: [server({ origin: 'catalog' })] }).mcp[0].origin)
      .toBe('catalog');
  });

  it('nulls the catalog bookkeeping a pre-catalog row has none of', () => {
    expect(toAgentProfile({ ...minimal, mcp: [server({})] }).mcp[0]).toMatchObject({
      catalogServerId: null, syncedRevision: null, catalogRevision: null,
      updateAvailable: false, error: null, checkedAt: null,
      url: undefined, command: undefined, args: undefined,
    });
  });

  it('keeps the endpoint fields a server does carry', () => {
    const mcp = toAgentProfile({
      ...minimal,
      mcp: [server({ url: 'http://gh', command: 'npx', args: '-y pkg', catalogServerId: 'mcp-1' })],
    }).mcp[0];

    expect(mcp).toMatchObject({
      url: 'http://gh', command: 'npx', args: '-y pkg', catalogServerId: 'mcp-1',
    });
  });

  it('reads a skill\'s enabled flag as a boolean, whatever the row holds', () => {
    const skills = toAgentProfile({
      ...minimal,
      skills: [
        { id: 's1', name: 'ops', source: 'bundled', version: '1', description: '', enabled: true },
        { id: 's2', name: 'research', source: 'user', version: '1', description: '' },
      ] as never,
    }).skills;

    expect(skills.map(s => s.enabled)).toEqual([true, false]);
  });

  function server(patch: Record<string, unknown>) {
    return {
      id: 'm-1', name: 'github', transport: 'http', status: 'connected', tools: 2,
      latencyMs: 30, ...patch,
    } as never;
  }
});

describe('toMcpCatalogServer', () => {
  const minimal: ApiMcpCatalogServer = { id: 'mcp-1', desiredState: 'stopped' };

  it('fills in every list and label a sparse row leaves out', () => {
    expect(toMcpCatalogServer(minimal)).toMatchObject({
      name: '', description: '', kind: 'external', hostId: null, transport: 'http',
      url: null, image: null, entrypoint: [], command: [], stdioCommand: null, args: [],
      internalPort: null, publishedPort: null, path: null, crossHostUrl: null,
      connectionUrl: null, headers: [], environment: [], volumes: [], healthcheck: null,
      supportServices: [], operationState: 'idle', operationError: null, checkError: null,
      checkedAt: null, latencyMs: null, linkedAgents: 0, imageAsOf: null, imageUpdate: null, revision: 1, appliedRevision: 0,
      pendingChanges: false, serviceKey: null,
    });
  });

  it('defaults a stdio entry to the stdio transport', () => {
    expect(toMcpCatalogServer({ ...minimal, kind: 'stdio' }).transport).toBe('stdio');
  });

  it('normalizes the states the backend spells in mixed case', () => {
    const server = toMcpCatalogServer({
      ...minimal, desiredState: 'RUNNING', runtimeState: 'Running',
      checkStatus: 'CONNECTED', operationState: 'Starting',
    } as never);

    expect(server).toMatchObject({
      desiredState: 'running', runtimeState: 'running',
      checkStatus: 'connected', operationState: 'starting',
    });
  });

  it('falls back to unknown for a state it does not recognise', () => {
    const server = toMcpCatalogServer({
      ...minimal, runtimeState: 'reticulating', checkStatus: 'perhaps',
    } as never);

    expect(server).toMatchObject({ runtimeState: 'unknown', checkStatus: 'unknown' });
  });

  it('reads a config entry\'s secret flag as a boolean', () => {
    const server = toMcpCatalogServer({
      ...minimal,
      headers: [{ key: 'Authorization', value: 't' }],
      environment: [{ key: 'TOKEN', value: 't', secret: true }],
    } as never);

    expect(server.headers[0].secret).toBe(false);
    expect(server.environment[0].secret).toBe(true);
  });

  it('reads a support service\'s own entries the same way, not just the top-level ones', () => {
    const service = toMcpCatalogServer({
      ...minimal,
      supportServices: [{
        name: 'database', image: 'postgres:16',
        environment: [{ key: 'POSTGRES_PASSWORD', value: null }],
      }],
    }).supportServices[0];

    expect(service.environment?.[0]).toMatchObject({ secret: false, value: null });
    // absent lists stay absent — the editor sends this shape back, and an empty
    // entrypoint would clear the image's own instead of leaving it alone
    expect(service).toMatchObject({ platform: null });
    expect(service.entrypoint).toBeUndefined();
    expect(service.command).toBeUndefined();
    expect(service.volumes).toBeUndefined();
  });

  it('falls back to an external HTTP entry for a kind this build does not know', () => {
    const server = toMcpCatalogServer({ ...minimal, kind: 'quantum' } as never);

    expect(server).toMatchObject({ kind: 'external', transport: 'http' });
  });

  it('falls back to the kind\'s own transport when the row names an unknown one', () => {
    expect(toMcpCatalogServer({ ...minimal, kind: 'stdio', transport: 'carrier-pigeon' } as never)
      .transport).toBe('stdio');
  });

  it('leaves a field this build knows nothing about out of the store', () => {
    const server = toMcpCatalogServer({ ...minimal, quantumEntanglement: true } as never);

    expect(server).not.toHaveProperty('quantumEntanglement');
  });

  it('copies nested structures, so the store cannot alias the response body', () => {
    const payload = {
      ...minimal,
      volumes: [{ name: 'data', target: '/data' }],
      healthcheck: { test: ['CMD', 'true'] },
    };
    const server = toMcpCatalogServer(payload as never);
    server.volumes[0].name = 'edited';
    server.healthcheck!.test.push('extra');

    expect(payload.volumes[0].name).toBe('data');
    expect(payload.healthcheck.test).toEqual(['CMD', 'true']);
  });
});

describe('toMcpRetainedResource', () => {
  it('gives every label the row is rendered by something to show', () => {
    expect(toMcpRetainedResource({ id: 'vol-1' })).toEqual({
      id: 'vol-1', serverId: null, serverName: '', hostId: '', type: 'volume', name: '',
      createdAt: 0,
    });
  });

  it('keeps what the row does carry', () => {
    expect(toMcpRetainedResource({
      id: 'vol-1', serverId: 'mcp-1', serverName: 'browser', hostId: 'dh-local',
      type: 'volume', name: 'browser-data', createdAt: 7,
    })).toMatchObject({ serverId: 'mcp-1', serverName: 'browser', name: 'browser-data' });
  });
});

describe('toImageCatalog', () => {
  it('treats every tag as pulled on a backend that reports no local presence', () => {
    const catalog = toImageCatalog({
      repository: 'nousresearch/hermes-agent', tags: ['v1', 'v2'],
    } as ApiImageTags);

    expect(catalog.tags).toEqual([
      { tag: 'v1', pulled: true, digest: null }, { tag: 'v2', pulled: true, digest: null }]);
    expect(catalog.registryStatus).toBe('unavailable');
  });

  it('takes the entries over the tag list once the backend sends them', () => {
    const catalog = toImageCatalog({
      repository: 'r', tags: ['ignored'], registryStatus: 'ok',
      entries: [{ tag: 'v2', pulled: true, remote: true }, { tag: 'v3', pulled: false, remote: true }],
    } as ApiImageTags);

    expect(catalog.tags).toEqual([
      { tag: 'v2', pulled: true, digest: null }, { tag: 'v3', pulled: false, digest: null }]);
    expect(catalog.registryStatus).toBe('ok');
  });
});

describe('toProfileTemplate', () => {
  const minimal = { id: 'pt-1', name: 'ops', createdAt: 1, updatedAt: 2 } as ApiProfileTemplate;

  it('fills in every field a template row can omit', () => {
    expect(toProfileTemplate(minimal)).toEqual({
      id: 'pt-1', name: 'ops', icon: '', description: '', category: 'general', provider: '', model: '',
      baseUrl: '', cwd: '', soul: '', memory: '', skills: [], librarySkillIds: [], guideIds: [],
      mcpServers: [], secrets: [], createdAt: 1, updatedAt: 2,
    });
  });

  it('treats a template MCP server as enabled unless it says otherwise', () => {
    const servers = toProfileTemplate({
      ...minimal,
      mcpServers: [
        { name: 'files', transport: 'stdio', command: 'npx' },
        { name: 'gh', transport: 'http', url: 'http://gh', enabled: false },
      ] as never,
    }).mcpServers;

    expect(servers.map(m => m.enabled)).toEqual([true, false]);
  });

  it('reads each key\'s flags as booleans, so an unset key never reads as set', () => {
    const secrets = toProfileTemplate({
      ...minimal,
      secrets: [{ key: 'A' }, { key: 'B', set: true, recoverable: true }] as never,
    }).secrets;

    expect(secrets).toEqual([
      { key: 'A', set: false, recoverable: false },
      { key: 'B', set: true, recoverable: true },
    ]);
  });
});

describe('toAgentSetup', () => {
  // hermes reports these sections; a version that does not know one omits it, and the Setup
  // tab has to render "nothing set up" rather than fail on a binding to undefined
  it('fills in every section a sparse payload leaves out', () => {
    const setup = toAgentSetup({ envPath: '/opt/data/.env', envExists: true } as ApiAgentSetup);

    expect(setup.apiKeys).toEqual([]);
    expect(setup.authProviders).toEqual([]);
    expect(setup.apiKeyProviders).toEqual([]);
    expect(setup.messaging).toEqual([]);
  });

  it('treats a missing flag as not-set rather than as undefined', () => {
    const setup = toAgentSetup({
      apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY' }],
      authProviders: [{ label: 'Nous Portal', status: 'not logged in' }],
      messaging: [{ label: 'Discord', status: 'off', tokenVar: 'DISCORD_TOKEN' }],
    } as unknown as ApiAgentSetup);

    expect(setup.envExists).toBe(false);
    expect(setup.apiKeys[0]).toEqual({
      label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: false, masked: null, problem: null,
    });
    expect(setup.authProviders[0].providerKey).toBeNull();
    expect(setup.authProviders[0].ok).toBe(false);
    expect(setup.messaging[0]).toMatchObject({ ok: false, homeVar: null, homeChannel: null });
  });

  it('keeps a field it does not know about out of the model', () => {
    const setup = toAgentSetup(
      { envPath: '/e', envExists: true, quotaGb: 12 } as unknown as ApiAgentSetup);

    expect('quotaGb' in setup).toBe(false);
  });
});

describe('toLlmProvider', () => {
  // the picker asks for a key it may not need rather than omitting one it does
  it('treats an incompletely described provider as the most demanding case', () => {
    expect(toLlmProvider({ key: 'newvendor' } as ApiModelProvider)).toEqual({
      key: 'newvendor', label: 'newvendor', needsKey: true,
      oauth: false, hasCatalog: false, envVar: null,
    });
  });

  it('keeps a keyless OAuth provider keyless', () => {
    expect(toLlmProvider({
      key: 'nous', label: 'Nous (account)', needsKey: false,
      oauth: true, hasCatalog: true, envVar: null,
    })).toMatchObject({ needsKey: false, oauth: true });
  });
});

describe('toPullState', () => {
  it('carries a status it knows', () => {
    expect(toPullState({ model: 'gemma3:4b', status: 'pulling', detail: null }))
      .toEqual({ model: 'gemma3:4b', status: 'pulling', detail: null });
  });

  // a pull this build cannot name is one it cannot promise is still running, and a row stuck
  // on 'pulling' never clears on its own
  it('reads an unrecognised status as an error rather than as in-flight', () => {
    expect(toPullState({ model: 'm', status: 'cancelled', detail: null } as never).status)
      .toBe('error');
  });
});

describe('toServerInfo', () => {
  it('answers something renderable for a payload that names nothing', () => {
    expect(toServerInfo({} as never)).toEqual({ version: '', retained: 0, startedAt: 0 });
  });
});

describe('toLogEntry', () => {
  // the same wire shape arrives from four tails; only the caller knows whose it is
  it('attributes a line to the caller\'s subject, not to the payload', () => {
    const line = { ts: 5, level: 'warn' as const, source: 'gateway', msg: 'slow' };

    expect(toLogEntry(line, 'a-atlas').agentId).toBe('a-atlas');
    expect(toLogEntry(line, null).agentId).toBeNull();
  });
});

describe('toDockerHost', () => {
  // a host that has never answered a probe reports none of this, and the sidebar chip, the
  // containers page and the deploy modal all render it
  it('fills in everything a host that has not answered omits', () => {
    expect(toDockerHost({ id: 'dh-edge' })).toEqual({
      id: 'dh-edge', name: 'dh-edge', url: '', kind: 'remote', status: 'disconnected',
      engine: null, apiVersion: null, latencyMs: null, note: null,
    });
  });

  // the sidebar summary is worst-of, so a state we cannot interpret must not read as reachable
  it('reads a status it does not know as disconnected, never as connected', () => {
    expect(toDockerHost({ id: 'dh-1', status: 'reconciling' }).status).toBe('disconnected');
  });

  it('matches the backend\'s casing, which is not always ours', () => {
    expect(toDockerHost({ id: 'dh-1', status: 'CONNECTED' }).status).toBe('connected');
  });

  it('treats anything not named local as remote', () => {
    expect(toDockerHost({ id: 'dh-1', kind: 'local' }).kind).toBe('local');
    expect(toDockerHost({ id: 'dh-1' }).kind).toBe('remote');
  });
});

describe('toInferenceEndpoint', () => {
  // nothing has failed yet, so an unprobed endpoint is unknown rather than an error
  it('reads an unprobed endpoint as unknown', () => {
    expect(toInferenceEndpoint({ id: 'mp-1', name: 'workstation' })).toEqual({
      id: 'mp-1', name: 'workstation', url: '',
      // a backend too old to send the flag cannot pull either, so false is the safe read
      // no protocol answered yet, so there is none to report — not a guess at ollama
      status: 'unknown', version: null, detail: null, kind: null, canManageModels: false,
    });
  });

  it('reports only a protocol it recognises, and null for anything else', () => {
    expect(toInferenceEndpoint({ id: 'mp-1', kind: 'openai' } as ApiInferenceEndpoint).kind)
      .toBe('openai');
    // a kind this build has no client for is not something the page can render controls for
    expect(toInferenceEndpoint({ id: 'mp-1', kind: 'vllm' } as ApiInferenceEndpoint).kind).toBeNull();
  });
});

describe('toEndpointModel', () => {
  // ollama's own fields — a model pulled from a bare digest reports no family or size
  it('renders a model that reports only its name', () => {
    expect(toEndpointModel({ name: 'gemma3:4b' })).toEqual({
      name: 'gemma3:4b', sizeBytes: 0, family: '', parameterSize: '', modifiedAt: 0,
    });
  });
});

describe('toChatMessage', () => {
  it('names the fields a plain turn carries none of', () => {
    expect(toChatMessage({ role: 'user', content: 'status?', ts: 1 })).toEqual({
      role: 'user', content: 'status?', ts: 1,
      toolName: null, toolCalls: null, reasoning: null,
    });
  });

  // a tool turn legitimately carries no content; the viewer renders the call instead
  it('keeps a tool turn with no content of its own', () => {
    expect(toChatMessage({ role: 'tool', toolName: 'grep', ts: 2 }))
      .toMatchObject({ content: '', toolName: 'grep' });
  });
});

describe('toSessionInfo', () => {
  // a status this build cannot read is not evidence a session is still live
  it('counts only an explicit open as open', () => {
    expect(toSessionInfo({ status: 'open' } as ApiSession).status).toBe('open');
    expect(toSessionInfo({ status: 'suspended' } as ApiSession).status).toBe('closed');
    expect(toSessionInfo({} as ApiSession).status).toBe('closed');
  });
});
