import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesApi } from '../hermes-api';
import { AgentRef } from './agent-ref';

/**
 * Every resource client is a one-line delegation to {@link ApiHttp}, so the only
 * thing each method can get wrong is the request it composes: the verb, the path
 * (including which segments are escaped) and the body. This table asserts that
 * one request per method; the transport itself — timeouts, error unwrapping, the
 * empty-body case — is hermes-api.spec.ts' subject.
 */
interface Recorded {
  method: string;
  url: string;
  body: unknown;
}

const REF: AgentRef = { hostId: 'dh-local', containerId: 'c-1', name: 'atlas' };
const AGENT = '/api/agents/dh-local/c-1/atlas';

interface Case {
  /** What the caller does. */
  readonly call: (api: HermesApi) => Promise<unknown>;
  readonly method: string;
  readonly url: string;
  readonly body?: unknown;
}

const cases: Record<string, Case> = {
  // ── hosts ──────────────────────────────────────────────────────────────
  'hosts.list': { call: a => a.hosts.list(), method: 'GET', url: '/api/hosts' },
  'hosts.add': {
    call: a => a.hosts.add('edge', 'tcp://10.0.0.5:2376'), method: 'POST', url: '/api/hosts',
    body: { name: 'edge', url: 'tcp://10.0.0.5:2376' },
  },
  'hosts.check': { call: a => a.hosts.check('dh-local'), method: 'POST', url: '/api/hosts/dh-local/check' },
  'hosts.remove': { call: a => a.hosts.remove('dh-local'), method: 'DELETE', url: '/api/hosts/dh-local' },

  // ── containers ─────────────────────────────────────────────────────────
  'containers.list': { call: a => a.containers.list(), method: 'GET', url: '/api/containers' },
  'containers.stats': {
    call: a => a.containers.stats('dh-local', 'c-1'), method: 'GET',
    url: '/api/containers/dh-local/c-1/stats',
  },
  'containers.logs': {
    call: a => a.containers.logs('dh-local', 'c-1', 25), method: 'GET',
    url: '/api/containers/dh-local/c-1/logs?tail=25',
  },
  'containers.deploy': {
    call: a => a.containers.deploy(
      'dh-local', 'hermes-prod', 'v1', ['ops'], { memoryMb: 4096, cpus: 4 }, {
        ports: [{ containerPort: 9119, hostPort: 9119, hostIp: '127.0.0.1' }],
        env: [{ key: 'HERMES_DASHBOARD', value: '1' }],
        mounts: [{ source: '/srv/repo', target: '/work', readOnly: false }],
      }, 'pt-1'),
    method: 'POST',
    url: '/api/containers',
    body: {
      hostId: 'dh-local', name: 'hermes-prod', version: 'v1', profiles: ['ops'],
      memoryMb: 4096, cpus: 4,
      ports: [{ containerPort: 9119, hostPort: 9119, hostIp: '127.0.0.1' }],
      env: [{ key: 'HERMES_DASHBOARD', value: '1' }],
      mounts: [{ source: '/srv/repo', target: '/work', readOnly: false }],
      defaultTemplateId: 'pt-1',
    },
  },
  'containers.start': {
    call: a => a.containers.start('dh-local', 'c-1'), method: 'POST',
    url: '/api/containers/dh-local/c-1/start',
  },
  'containers.stop': {
    call: a => a.containers.stop('dh-local', 'c-1'), method: 'POST',
    url: '/api/containers/dh-local/c-1/stop',
  },
  'containers.remove': {
    call: a => a.containers.remove('dh-local', 'c-1'), method: 'DELETE',
    url: '/api/containers/dh-local/c-1',
  },
  'containers.update': {
    call: a => a.containers.update('dh-local', 'c-1', 'v2'), method: 'POST',
    url: '/api/containers/dh-local/c-1/update', body: { version: 'v2', ports: [], env: [], mounts: [] },
  },
  'containers.imageTags': {
    call: a => a.containers.imageTags('dh-local'), method: 'GET',
    url: '/api/images/tags?hostId=dh-local',
  },

  // ── agent profiles ─────────────────────────────────────────────────────
  'agents.list': {
    call: a => a.agents.list('dh-local', 'c-1'), method: 'GET',
    url: '/api/agents?hostId=dh-local&containerId=c-1',
  },
  'agents.create': {
    call: a => a.agents.create({
      hostId: 'dh-local', containerId: 'c-1', name: 'atlas',
      provider: 'anthropic', model: 'claude-fable-5', apiKey: 'sk-x',
    }),
    method: 'POST', url: '/api/agents',
    body: {
      hostId: 'dh-local', containerId: 'c-1', name: 'atlas',
      provider: 'anthropic', model: 'claude-fable-5', apiKey: 'sk-x',
    },
  },
  'agents.remove': { call: a => a.agents.remove(REF), method: 'DELETE', url: AGENT },
  'agents.logs': { call: a => a.agents.logs(REF, 50), method: 'GET', url: `${AGENT}/logs?tail=50` },
  'agents.updateSoul': {
    call: a => a.agents.updateSoul(REF, '# SOUL'), method: 'PUT', url: `${AGENT}/soul`,
    body: { soul: '# SOUL' },
  },
  'agents.updateConfig': {
    call: a => a.agents.updateConfig(REF, 'provider: nous'), method: 'PUT', url: `${AGENT}/config`,
    body: { configYaml: 'provider: nous' },
  },
  'agents.integrations': { call: a => a.agents.integrations(REF), method: 'GET', url: `${AGENT}/integrations` },
  'agents.sessions': { call: a => a.agents.sessions(REF), method: 'GET', url: `${AGENT}/sessions` },
  'agents.sessionMessages': {
    call: a => a.agents.sessionMessages(REF, 'sess 1'), method: 'GET',
    url: `${AGENT}/sessions/sess%201`,
  },
  'agents.deleteSession': {
    call: a => a.agents.deleteSession(REF, 'sess 1'), method: 'DELETE',
    url: `${AGENT}/sessions/sess%201`,
  },
  'agents.setup': { call: a => a.agents.setup(REF), method: 'GET', url: `${AGENT}/setup` },
  'agents.setEnv': {
    call: a => a.agents.setEnv(REF, [{ key: 'K', value: null }]), method: 'PUT', url: `${AGENT}/env`,
    body: { entries: [{ key: 'K', value: null }] },
  },
  'agents.initEnv': { call: a => a.agents.initEnv(REF), method: 'POST', url: `${AGENT}/env/init` },
  'agents.authProviders': {
    call: a => a.agents.authProviders('dh-local', 'c-1'), method: 'GET',
    url: '/api/agents/dh-local/c-1/auth-providers',
  },

  // ── skills ─────────────────────────────────────────────────────────────
  'agents.skills.setEnabled': {
    call: a => a.agents.skills.setEnabled(REF, 'ops', false), method: 'PUT',
    url: `${AGENT}/skills/ops`, body: { enabled: false },
  },
  'agents.skills.install': {
    call: a => a.agents.skills.install(REF, 'ops'), method: 'POST', url: `${AGENT}/skills`,
    body: { name: 'ops' },
  },
  'agents.skills.uninstall': {
    call: a => a.agents.skills.uninstall(REF, 'ops'), method: 'DELETE', url: `${AGENT}/skills/ops`,
  },
  'agents.skills.content': {
    call: a => a.agents.skills.content(REF, 'ops'), method: 'GET', url: `${AGENT}/skills/ops/content`,
  },
  'agents.skills.updateContent': {
    call: a => a.agents.skills.updateContent(REF, 'ops', '# body'), method: 'PUT',
    url: `${AGENT}/skills/ops/content`, body: { body: '# body' },
  },

  // ── profile MCP servers ────────────────────────────────────────────────
  'agents.mcp.add': {
    call: a => a.agents.mcp.add(REF, { name: 'github', transport: 'http', url: 'http://gh' }),
    method: 'POST', url: `${AGENT}/mcp`,
    body: { name: 'github', transport: 'http', url: 'http://gh' },
  },
  'agents.mcp.update': {
    call: a => a.agents.mcp.update(REF, 'gh', { name: 'github', transport: 'http' }),
    method: 'PUT', url: `${AGENT}/mcp/gh`, body: { name: 'github', transport: 'http' },
  },
  'agents.mcp.setEnabled': {
    call: a => a.agents.mcp.setEnabled(REF, 'github', true), method: 'PUT',
    url: `${AGENT}/mcp/github/enabled`, body: { enabled: true },
  },
  'agents.mcp.connectCatalog': {
    call: a => a.agents.mcp.connectCatalog(REF, 'mcp-browser', 'browser'), method: 'POST',
    url: `${AGENT}/mcp/catalog`, body: { serverId: 'mcp-browser', alias: 'browser' },
  },
  'agents.mcp.syncCatalog': {
    call: a => a.agents.mcp.syncCatalog(REF, 'browser'), method: 'POST',
    url: `${AGENT}/mcp/browser/sync`,
  },
  'agents.mcp.unlinkCatalog': {
    call: a => a.agents.mcp.unlinkCatalog(REF, 'browser'), method: 'DELETE',
    url: `${AGENT}/mcp/browser/link`,
  },
  'agents.mcp.remove': {
    call: a => a.agents.mcp.remove(REF, 'github'), method: 'DELETE', url: `${AGENT}/mcp/github`,
  },
  'agents.mcp.test': {
    call: a => a.agents.mcp.test(REF, 'github'), method: 'POST', url: `${AGENT}/mcp/github/test`,
  },

  // ── scheduled jobs ─────────────────────────────────────────────────────
  'agents.cron.list': { call: a => a.agents.cron.list(REF), method: 'GET', url: `${AGENT}/cron` },
  'agents.cron.create': {
    call: a => a.agents.cron.create(REF, { name: 'digest', schedule: '0 9 * * *', prompt: 'go' }),
    method: 'POST', url: `${AGENT}/cron`,
    body: { name: 'digest', schedule: '0 9 * * *', prompt: 'go' },
  },
  'agents.cron.update': {
    call: a => a.agents.cron.update(REF, 'j 1', { name: 'digest', schedule: '@daily', prompt: 'go' }),
    method: 'PATCH', url: `${AGENT}/cron/j%201`,
    body: { name: 'digest', schedule: '@daily', prompt: 'go' },
  },
  'agents.cron.pause': {
    call: a => a.agents.cron.setEnabled(REF, 'j1', false), method: 'POST',
    url: `${AGENT}/cron/j1/pause`,
  },
  'agents.cron.resume': {
    call: a => a.agents.cron.setEnabled(REF, 'j1', true), method: 'POST',
    url: `${AGENT}/cron/j1/resume`,
  },
  'agents.cron.runNow': {
    call: a => a.agents.cron.runNow(REF, 'j1'), method: 'POST', url: `${AGENT}/cron/j1/run`,
  },
  'agents.cron.remove': {
    call: a => a.agents.cron.remove(REF, 'j1'), method: 'DELETE', url: `${AGENT}/cron/j1`,
  },

  // ── webhooks ───────────────────────────────────────────────────────────
  'agents.webhooks.list': {
    call: a => a.agents.webhooks.list(REF), method: 'GET', url: `${AGENT}/webhooks`,
  },
  'agents.webhooks.setPlatform': {
    call: a => a.agents.webhooks.setPlatform(REF, true, '0.0.0.0', 8644), method: 'PUT',
    url: `${AGENT}/webhooks/platform`, body: { enabled: true, host: '0.0.0.0', port: 8644 },
  },
  'agents.webhooks.subscribe': {
    call: a => a.agents.webhooks.subscribe(REF, { name: 'alerts', events: ['alert.firing'], prompt: 'p' }),
    method: 'POST', url: `${AGENT}/webhooks`,
    body: { name: 'alerts', events: ['alert.firing'], prompt: 'p' },
  },
  'agents.webhooks.secret': {
    call: a => a.agents.webhooks.secret(REF, 'alerts'), method: 'GET',
    url: `${AGENT}/webhooks/alerts/secret`,
  },
  'agents.webhooks.test': {
    call: a => a.agents.webhooks.test(REF, 'alerts'), method: 'POST',
    url: `${AGENT}/webhooks/alerts/test`,
  },
  'agents.webhooks.remove': {
    call: a => a.agents.webhooks.remove(REF, 'alerts'), method: 'DELETE',
    url: `${AGENT}/webhooks/alerts`,
  },

  // ── MCP catalog ────────────────────────────────────────────────────────
  'mcp.list': { call: a => a.mcp.list(), method: 'GET', url: '/api/mcp-servers' },
  'mcp.create': {
    call: a => a.mcp.create({ name: 'browser' } as never), method: 'POST', url: '/api/mcp-servers',
    body: { name: 'browser' },
  },
  'mcp.update': {
    call: a => a.mcp.update('mcp-1', { name: 'browser' } as never), method: 'PUT',
    url: '/api/mcp-servers/mcp-1', body: { name: 'browser' },
  },
  'mcp.remove': { call: a => a.mcp.remove('mcp-1'), method: 'DELETE', url: '/api/mcp-servers/mcp-1' },
  'mcp.start': { call: a => a.mcp.run('mcp-1', 'start'), method: 'POST', url: '/api/mcp-servers/mcp-1/start' },
  'mcp.check': { call: a => a.mcp.run('mcp-1', 'check'), method: 'POST', url: '/api/mcp-servers/mcp-1/check' },
  'mcp.logs': {
    call: a => a.mcp.logs('mcp-1', 40), method: 'GET', url: '/api/mcp-servers/mcp-1/logs?tail=40',
  },
  'mcp.retainedResources': {
    call: a => a.mcp.retainedResources(), method: 'GET', url: '/api/mcp-servers/retained-resources',
  },
  'mcp.purgeRetainedResource': {
    call: a => a.mcp.purgeRetainedResource('vol-1'), method: 'DELETE',
    url: '/api/mcp-servers/retained-resources/vol-1',
  },

  // ── providers and model catalogs ───────────────────────────────────────
  'providers.registry': { call: a => a.providers.registry(), method: 'GET', url: '/api/providers' },
  'providers.modelCatalog': {
    call: a => a.providers.modelCatalog('anthropic'), method: 'GET', url: '/api/models/anthropic',
  },
  'providers.modelCatalogLive': {
    call: a => a.providers.modelCatalogLive('anthropic', 'sk-x'), method: 'POST',
    url: '/api/models/anthropic', body: { apiKey: 'sk-x' },
  },
  'endpoints.list': { call: a => a.endpoints.list(), method: 'GET', url: '/api/inference-endpoints' },
  'endpoints.add': {
    call: a => a.endpoints.add('lab', 'http://ollama:11434'), method: 'POST',
    url: '/api/inference-endpoints', body: { name: 'lab', url: 'http://ollama:11434' },
  },
  'endpoints.remove': {
    call: a => a.endpoints.remove('mp-1'), method: 'DELETE', url: '/api/inference-endpoints/mp-1',
  },
  'endpoints.check': {
    call: a => a.endpoints.check('mp-1'), method: 'POST', url: '/api/inference-endpoints/mp-1/check',
  },
  'endpoints.models': {
    call: a => a.endpoints.models('mp-1'), method: 'GET', url: '/api/inference-endpoints/mp-1/models',
  },
  'endpoints.running': {
    call: a => a.endpoints.running('mp-1'), method: 'GET',
    url: '/api/inference-endpoints/mp-1/running',
  },
  'endpoints.loadModel': {
    call: a => a.endpoints.loadModel('mp-1', 'llama3'), method: 'POST',
    url: '/api/inference-endpoints/mp-1/models/load', body: { name: 'llama3' },
  },
  'endpoints.unloadModel': {
    call: a => a.endpoints.unloadModel('mp-1', 'llama3'), method: 'POST',
    url: '/api/inference-endpoints/mp-1/models/unload', body: { name: 'llama3' },
  },
  'endpoints.pullModel': {
    call: a => a.endpoints.pullModel('mp-1', 'llama3'), method: 'POST',
    url: '/api/inference-endpoints/mp-1/models/pull', body: { name: 'llama3' },
  },
  'endpoints.pullStatus': {
    call: a => a.endpoints.pullStatus('mp-1'), method: 'GET', url: '/api/inference-endpoints/mp-1/pulls',
  },
  'endpoints.deleteModel': {
    call: a => a.endpoints.deleteModel('mp-1', 'llama3'), method: 'POST',
    url: '/api/inference-endpoints/mp-1/models/delete', body: { name: 'llama3' },
  },

  // ── profile templates ──────────────────────────────────────────────────
  'templates.list': { call: a => a.templates.list(), method: 'GET', url: '/api/profile-templates' },
  'templates.create': {
    call: a => a.templates.create({ name: 'ops' } as never), method: 'POST',
    url: '/api/profile-templates', body: { name: 'ops' },
  },
  'templates.update': {
    call: a => a.templates.update('pt 1', { name: 'ops' } as never), method: 'PUT',
    url: '/api/profile-templates/pt%201', body: { name: 'ops' },
  },
  'templates.remove': {
    call: a => a.templates.remove('pt-1'), method: 'DELETE', url: '/api/profile-templates/pt-1',
  },
  'templates.capture': {
    call: a => a.templates.capture(REF, 'atlas-template'), method: 'POST',
    url: '/api/profile-templates/capture',
    body: { hostId: 'dh-local', containerId: 'c-1', name: 'atlas', templateName: 'atlas-template' },
  },
  'templates.deploy': {
    call: a => a.templates.deploy('pt-1', REF), method: 'POST',
    url: '/api/profile-templates/pt-1/deploy',
    body: { hostId: 'dh-local', containerId: 'c-1', name: 'atlas' },
  },

  // ── prompt library ─────────────────────────────────────────────────────
  'prompts.list': { call: a => a.prompts.list(), method: 'GET', url: '/api/prompts' },
  'prompts.create': {
    call: a => a.prompts.create({ title: 'Triage' } as never), method: 'POST',
    url: '/api/prompts', body: { title: 'Triage' },
  },
  'prompts.update': {
    call: a => a.prompts.update('p 1', { title: 'Triage' } as never), method: 'PUT',
    url: '/api/prompts/p%201', body: { title: 'Triage' },
  },
  'prompts.remove': {
    call: a => a.prompts.remove('p-1'), method: 'DELETE', url: '/api/prompts/p-1',
  },

  // ── skill library ──────────────────────────────────────────────────────
  'skills.list': { call: a => a.skills.list(), method: 'GET', url: '/api/skills' },
  'skills.create': {
    call: a => a.skills.create({ kind: 'hub', name: 'pdf' } as never), method: 'POST',
    url: '/api/skills', body: { kind: 'hub', name: 'pdf' },
  },
  'skills.update': {
    call: a => a.skills.update('s 1', { kind: 'hub', name: 'pdf' } as never), method: 'PUT',
    url: '/api/skills/s%201', body: { kind: 'hub', name: 'pdf' },
  },
  'skills.remove': {
    call: a => a.skills.remove('s-1'), method: 'DELETE', url: '/api/skills/s-1',
  },
  'skills.deploy': {
    call: a => a.skills.deploy('s 1', { hostId: 'dh-1', containerId: 'c1', name: 'ops' }),
    method: 'POST', url: '/api/skills/s%201/deploy',
    body: { hostId: 'dh-1', containerId: 'c1', profile: 'ops' },
  },
  'skills.upstream': {
    call: a => a.skills.upstream('s 1'), method: 'GET', url: '/api/skills/s%201/upstream',
  },
  'skills.importFrom': {
    call: a => a.skills.importFrom({ hostId: 'dh-1', containerId: 'c1', name: 'ops' }, 'pdf'),
    method: 'POST', url: '/api/skills/import',
    body: { hostId: 'dh-1', containerId: 'c1', profile: 'ops', skillName: 'pdf' },
  },

  // ── credentials ────────────────────────────────────────────────────────
  'credentials.list': { call: a => a.credentials.list(), method: 'GET', url: '/api/credentials' },
  'credentials.create': {
    call: a => a.credentials.create({ name: 'anthropic prod' } as never), method: 'POST',
    url: '/api/credentials', body: { name: 'anthropic prod' },
  },
  'credentials.update': {
    call: a => a.credentials.update('cr 1', { name: 'anthropic prod' } as never), method: 'PUT',
    url: '/api/credentials/cr%201', body: { name: 'anthropic prod' },
  },
  'credentials.remove': {
    call: a => a.credentials.remove('cr-1'), method: 'DELETE', url: '/api/credentials/cr-1',
  },

  // ── mcp groups ─────────────────────────────────────────────────────────
  'mcpGroups.list': { call: a => a.mcpGroups.list(), method: 'GET', url: '/api/mcp-groups' },
  'mcpGroups.create': {
    call: a => a.mcpGroups.create({ name: 'research' } as never), method: 'POST',
    url: '/api/mcp-groups', body: { name: 'research' },
  },
  'mcpGroups.update': {
    call: a => a.mcpGroups.update('mg 1', { name: 'research' } as never), method: 'PUT',
    url: '/api/mcp-groups/mg%201', body: { name: 'research' },
  },
  'mcpGroups.remove': {
    call: a => a.mcpGroups.remove('mg-1'), method: 'DELETE', url: '/api/mcp-groups/mg-1',
  },
  'mcpGroups.deploy': {
    call: a => a.mcpGroups.deploy('mg 1', REF),
    method: 'POST', url: '/api/mcp-groups/mg%201/deploy',
    body: { hostId: 'dh-local', containerId: 'c-1', profile: 'atlas' },
  },

  // ── prompt groups ──────────────────────────────────────────────────────
  'promptGroups.list': {
    call: a => a.promptGroups.list(), method: 'GET', url: '/api/prompt-groups',
  },
  'promptGroups.create': {
    call: a => a.promptGroups.create({ name: 'triage' } as never), method: 'POST',
    url: '/api/prompt-groups', body: { name: 'triage' },
  },
  'promptGroups.update': {
    call: a => a.promptGroups.update('pg 1', { name: 'triage' } as never), method: 'PUT',
    url: '/api/prompt-groups/pg%201', body: { name: 'triage' },
  },
  'promptGroups.remove': {
    call: a => a.promptGroups.remove('pg-1'), method: 'DELETE',
    url: '/api/prompt-groups/pg-1',
  },

  // ── skill groups ───────────────────────────────────────────────────────
  'skillGroups.list': {
    call: a => a.skillGroups.list(), method: 'GET', url: '/api/skill-groups',
  },
  'skillGroups.create': {
    call: a => a.skillGroups.create({ name: 'pdf' } as never), method: 'POST',
    url: '/api/skill-groups', body: { name: 'pdf' },
  },
  'skillGroups.update': {
    call: a => a.skillGroups.update('sg 1', { name: 'pdf' } as never), method: 'PUT',
    url: '/api/skill-groups/sg%201', body: { name: 'pdf' },
  },
  'skillGroups.remove': {
    call: a => a.skillGroups.remove('sg-1'), method: 'DELETE', url: '/api/skill-groups/sg-1',
  },

  // ── guides ─────────────────────────────────────────────────────────────
  'guides.list': { call: a => a.guides.list(), method: 'GET', url: '/api/skill-guides' },
  'guides.create': {
    call: a => a.guides.create({ name: 'pdf-triage' } as never), method: 'POST',
    url: '/api/skill-guides', body: { name: 'pdf-triage' },
  },
  'guides.update': {
    call: a => a.guides.update('g 1', { name: 'pdf-triage' } as never), method: 'PUT',
    url: '/api/skill-guides/g%201', body: { name: 'pdf-triage' },
  },
  'guides.remove': {
    call: a => a.guides.remove('g-1'), method: 'DELETE', url: '/api/skill-guides/g-1',
  },
  'guides.deploy': {
    call: a => a.guides.deploy('g 1', { hostId: 'dh-1', containerId: 'c1', name: 'ops' }),
    method: 'POST', url: '/api/skill-guides/g%201/deploy',
    body: { hostId: 'dh-1', containerId: 'c1', profile: 'ops' },
  },

  // ── board ──────────────────────────────────────────────────────────────
  'board.tasks': { call: a => a.board.tasks(), method: 'GET', url: '/api/board/tasks' },
  'board.moveTask': {
    call: a => a.board.moveTask('t 1', 'done'), method: 'PATCH', url: '/api/board/tasks/t%201',
    body: { column: 'done' },
  },
};

describe('HermesApi request composition', () => {
  let recorded: Recorded[];

  beforeEach(() => {
    recorded = [];
    vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
      recorded.push({
        method: init.method ?? 'GET',
        url,
        body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
      });
      return Promise.resolve(new Response('{}'));
    }));
  });

  afterEach(() => vi.unstubAllGlobals());

  for (const [name, spec] of Object.entries(cases)) {
    it(`${name} → ${spec.method} ${spec.url}`, async () => {
      await spec.call(new HermesApi(''));

      expect(recorded).toHaveLength(1);
      expect(recorded[0].method).toBe(spec.method);
      expect(recorded[0].url).toBe(spec.url);
      expect(recorded[0].body).toEqual(spec.body);
    });
  }

  it('publishes the route table for the backend to check itself against', async () => {
    // Each client method is exercised once above; this records what it composed.
    // The backend reads the same file and asserts every entry resolves to a
    // handler, so a renamed endpoint fails on whichever side moved first.
    const observed: string[] = [];
    for (const spec of Object.values(cases)) {
      recorded = [];
      await spec.call(new HermesApi(''));
      observed.push(`${recorded[0].method} ${recorded[0].url}`);
    }

    await expect(`${[...new Set(observed)].sort().join('\n')}\n`)
      .toMatchFileSnapshot('../../../../../api-contract.txt');
  });

  it('escapes every operator-supplied segment, so a name cannot address another endpoint', async () => {
    const api = new HermesApi('');
    await api.agents.setup({ hostId: 'dh/1', containerId: 'c 1', name: '../root' });

    expect(recorded[0].url).toBe('/api/agents/dh%2F1/c%201/..%2Froot/setup');
  });

  it('sends no body for a bodyless POST rather than the string "undefined"', async () => {
    await new HermesApi('').agents.initEnv(REF);

    expect(recorded[0].body).toBeUndefined();
  });
});
