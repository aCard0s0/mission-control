import {
  ApiAgentProfile, ApiAgentSetup, ApiChatMessage, ApiCredential, ApiCredentialEntry,
  ApiDockerHost, ApiImageTags,
  ApiMcpCatalogServer, ApiMcpConfigEntry, ApiMcpGroup, ApiMcpHealthcheck, ApiMcpRetainedResource,
  ApiMcpSupportService, ApiModelProvider, ApiLogLine, ApiEndpointModel, ApiInferenceEndpoint,
  ApiProfileTemplate, ApiPrompt, ApiPromptGroup, ApiPullState, ApiSkill, ApiSkillGroup, ApiSkillGuide, ApiDeployedPart, ApiUpstream, ApiServerInfo, ApiSession, ApiSetupApiKey,
  ApiRunningModel, ApiSetupAuthProvider, ApiSetupKeyProvider, ApiSetupMessaging,
} from '../hermes-api';
import {
  AgentProfile, AgentSetup, AuthProvider, ChatMessage, Credential, CredentialEntry,
  DockerHost, DockerHostStatus, Gateway,
  ImageCatalog, LlmProvider, McpCatalogKind, McpCatalogServer, McpCheckStatus, McpConfigEntry,
  McpGroup, McpHealthcheck, McpRetainedResource, LogEntry, McpRuntimeState, McpSupportService,
  McpTransport, InferenceEndpoint, InferenceEndpointStatus, EndpointModel, ProfileTemplate, Prompt, PromptGroup, Skill, SkillGroup, SkillGuide, DeployedPart, Upstream,
  PullState, RunningModel, ServerInfo, SessionInfo, SetupApiKey, SetupKeyProvider,
  SetupMessaging,
} from '../models';

// Backend payload → domain model. Pure functions, deliberately tolerant of
// additive fields and of older rows written by a previous backend version: this
// is the only layer allowed to know that the wire is looser than the model.

export function toAgentProfile(api: ApiAgentProfile): AgentProfile {
  return {
    id: api.id,
    containerId: api.containerId,
    name: api.name,
    role: api.role,
    state: api.state,
    provider: api.provider,
    model: api.model,
    apiKeyMasked: api.apiKeyMasked || '',
    cwd: api.cwd,
    soul: api.soul,
    memoryMd: api.memoryMd,
    configYaml: api.configYaml,
    skills: (api.skills ?? []).map(s => ({
      id: s.id,
      name: s.name,
      source: s.source as AgentProfile['skills'][number]['source'],
      version: s.version,
      description: s.description,
      enabled: !!s.enabled,
    })),
    mcp: (api.mcp ?? []).map(m => ({
      id: m.id,
      name: m.name,
      transport: m.transport as AgentProfile['mcp'][number]['transport'],
      enabled: m.enabled !== false,
      origin: m.origin === 'catalog' ? 'catalog' : 'custom',
      catalogServerId: m.catalogServerId ?? null,
      syncedRevision: m.syncedRevision ?? null,
      catalogRevision: m.catalogRevision ?? null,
      updateAvailable: !!m.updateAvailable,
      status: m.status as AgentProfile['mcp'][number]['status'],
      tools: m.tools,
      latencyMs: m.latencyMs,
      error: m.error ?? null,
      checkedAt: m.checkedAt ?? null,
      url: m.url ?? undefined,
      command: m.command ?? undefined,
      args: m.args ?? undefined,
    })),
    integrations: (api.integrations ?? []).map(i => ({
      kind: i.kind,
      status: i.status as AgentProfile['integrations'][number]['status'],
      detail: i.detail,
    })),
    gateway: toGateway(api.gateway),
    sessions: [],
    msgsToday: 0,
    tokensToday: 0,
    errorRate: 0,
    lastActive: api.lastActive,
  };
}

/** A profile whose gateway has written nothing yet still needs a shape to render. */
function toGateway(api: ApiAgentProfile['gateway'] | null | undefined): Gateway {
  return {
    state: api?.state ?? '',
    desiredState: api?.desiredState ?? '',
    activeAgents: api?.activeAgents ?? 0,
    agentVersion: api?.agentVersion ?? '',
    sessionStore: api?.sessionStore ?? '',
    paused: !!api?.paused,
    pauseReason: api?.pauseReason ?? null,
  };
}

const KINDS: McpCatalogKind[] = ['managed', 'external', 'stdio'];
const TRANSPORTS: McpTransport[] = ['stdio', 'http', 'sse'];
// 'unavailable' is what every external and stdio record is created as and never leaves —
// leaving it out mapped all of them to 'unknown' and nothing failed to say so
const RUNTIME_STATES: McpRuntimeState[] =
  ['running', 'stopped', 'missing', 'unavailable', 'error'];
const CHECK_STATUSES: McpCheckStatus[] = ['checking', 'connected', 'error'];

/** One of `allowed`, matched case-insensitively, or `fallback`. The backend
 *  spells its enums in mixed case and can name a state this build predates. */
function oneOf<T extends string>(value: string | undefined, allowed: T[], fallback: T): T {
  const wanted = String(value ?? '').toLowerCase();
  return allowed.find(item => item === wanted) ?? fallback;
}

function toConfigEntry(entry: ApiMcpConfigEntry): McpConfigEntry {
  return {
    key: entry.key,
    value: entry.value ?? null,
    secret: !!entry.secret,
    set: entry.set,
    recoverable: entry.recoverable,
  };
}

function toHealthcheck(api: ApiMcpHealthcheck | null | undefined): McpHealthcheck | null {
  return api ? { ...api, test: [...api.test] } : null;
}

/**
 * The list fields stay absent when the row omits them rather than becoming empty
 * arrays: the editor sends this shape back on save, and an empty `entrypoint` or
 * `command` is a Compose override that clears the image's own — which is not
 * what a service that never named one meant.
 */
function toSupportService(api: ApiMcpSupportService): McpSupportService {
  return {
    name: api.name,
    image: api.image,
    platform: api.platform ?? null,
    entrypoint: api.entrypoint,
    command: api.command,
    environment: api.environment?.map(toConfigEntry),
    volumes: api.volumes?.map(volume => ({ ...volume })),
    healthcheck: toHealthcheck(api.healthcheck),
  };
}

/**
 * Keeps tolerance for older rows in one place. Every field is named: the wire
 * type is deliberately looser than the model, so a field this build does not
 * know about stays out of the store rather than riding in on a spread, and a
 * field the backend stops sending fails here instead of reaching a template as
 * undefined.
 */
export function toMcpCatalogServer(api: ApiMcpCatalogServer): McpCatalogServer {
  const kind = oneOf(api.kind, KINDS, 'external');
  return {
    id: api.id,
    name: api.name ?? '',
    description: api.description ?? '',
    repoUrl: api.repoUrl ?? '',
    kind,
    hostId: api.hostId || null,
    transport: oneOf(api.transport, TRANSPORTS, kind === 'stdio' ? 'stdio' : 'http'),
    url: api.url || null,
    image: api.image || null,
    platform: api.platform || null,
    entrypoint: api.entrypoint ?? [],
    command: api.command ?? [],
    stdioCommand: api.stdioCommand || null,
    args: api.args ?? [],
    internalPort: api.internalPort ?? null,
    publishedPort: api.publishedPort ?? null,
    path: api.path || null,
    crossHostUrl: api.crossHostUrl || null,
    connectionUrl: api.connectionUrl || null,
    headers: (api.headers ?? []).map(toConfigEntry),
    environment: (api.environment ?? []).map(toConfigEntry),
    volumes: (api.volumes ?? []).map(volume => ({ ...volume })),
    healthcheck: toHealthcheck(api.healthcheck),
    supportServices: (api.supportServices ?? []).map(toSupportService),
    desiredState: oneOf(api.desiredState, ['running', 'stopped'], 'stopped'),
    runtimeState: oneOf(api.runtimeState, RUNTIME_STATES, 'unknown'),
    operationState: String(api.operationState ?? 'idle').toLowerCase(),
    operationError: api.operationError ?? null,
    checkStatus: oneOf(api.checkStatus, CHECK_STATUSES, 'unknown'),
    checkError: api.checkError ?? null,
    checkedAt: api.checkedAt ?? null,
    latencyMs: api.latencyMs ?? null,
    linkedAgents: api.linkedAgents ?? 0,
    imageAsOf: api.imageAsOf ?? null,
    imageUpdate: api.imageUpdate ?? null,
    revision: api.revision ?? 1,
    appliedRevision: api.appliedRevision ?? 0,
    pendingChanges: !!api.pendingChanges,
    serviceKey: api.serviceKey || null,
    createdAt: api.createdAt ?? Date.now(),
    updatedAt: api.updatedAt ?? Date.now(),
  };
}

/** A retained volume is rendered straight from this, so every label it shows
 *  has to survive a row that omits it. */
export function toMcpRetainedResource(api: ApiMcpRetainedResource): McpRetainedResource {
  return {
    id: api.id,
    serverId: api.serverId ?? null,
    serverName: api.serverName ?? '',
    hostId: api.hostId ?? '',
    type: api.type ?? 'volume',
    name: api.name ?? '',
    createdAt: api.createdAt ?? 0,
  };
}

export function toImageCatalog(r: ApiImageTags): ImageCatalog {
  // a backend without `entries` only ever reported local tags, so treat them as pulled
  const pulled = new Set(r.entries?.filter(e => e.pulled).map(e => e.tag) ?? r.tags);
  // a backend without `entries` reports no digests either, so those tags compare as unknown
  const digests = new Map((r.entries ?? []).map(e => [e.tag, e.digest ?? null]));
  return {
    repository: r.repository,
    tags: (r.entries?.map(e => e.tag) ?? r.tags).map(tag =>
      ({ tag, pulled: pulled.has(tag), digest: digests.get(tag) ?? null })),
    registryStatus: r.registryStatus ?? 'unavailable',
    fetchedAt: Date.now(),
  };
}

export function toProfileTemplate(api: ApiProfileTemplate): ProfileTemplate {
  return {
    id: api.id,
    name: api.name,
    icon: api.icon ?? '',
    description: api.description ?? '',
    // a blueprint from before categories existed reads as the default rather than as
    // a blank chip of its own; saving it files it there for real
    category: api.category || 'general',
    provider: api.provider ?? '',
    model: api.model ?? '',
    baseUrl: api.baseUrl ?? '',
    cwd: api.cwd ?? '',
    soul: api.soul ?? '',
    memory: api.memory ?? '',
    skills: api.skills ?? [],
    librarySkillIds: api.librarySkillIds ?? [],
    guideIds: api.guideIds ?? [],
    mcpServers: (api.mcpServers ?? []).map(m => ({
      name: m.name, transport: m.transport, url: m.url, command: m.command, args: m.args,
      enabled: m.enabled !== false,
    })),
    secrets: (api.secrets ?? []).map(s => ({ key: s.key, set: !!s.set, recoverable: !!s.recoverable })),
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

/** A prompt's optional columns are null on the wire and '' / [] in the model, so a
 *  template never has to ask which of the two an empty note is. */
export function toPrompt(api: ApiPrompt): Prompt {
  return {
    id: api.id,
    title: api.title,
    body: api.body ?? '',
    category: api.category || 'general',
    notes: api.notes ?? '',
    tags: api.tags,
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

/** Same contract as {@link toPrompt}: the library's optional columns are null on the wire
 *  and '' / [] in the model. A hub row sends `files: null`, which is the empty list here —
 *  the kind, not the file count, is what a template branches on. */
export function toSkill(api: ApiSkill): Skill {
  return {
    id: api.id,
    kind: api.kind === 'hub' ? 'hub' : 'local',
    name: api.name,
    description: api.description ?? '',
    category: api.category || 'general',
    repoUrl: api.repoUrl ?? '',
    version: api.version ?? '',
    files: api.files.map(f => ({ path: f.path, body: f.body ?? '' })),
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

/** A status this build does not know reads as `unavailable`: claiming a skill is current
 *  on a word nothing here understands is the one wrong direction. */
export function toUpstream(api: ApiUpstream): Upstream {
  return {
    status: oneOf(api.status, ['current', 'update', 'unknown', 'unsupported'], 'unavailable'),
    latest: api.latest ?? '',
    detail: api.detail ?? '',
    checkedAt: api.checkedAt ?? null,
  };
}

export function toMcpGroup(api: ApiMcpGroup): McpGroup {
  return {
    id: api.id,
    name: api.name,
    description: api.description ?? '',
    serverIds: api.serverIds,
    agents: api.agents.map(a => ({
      hostId: a.hostId,
      containerId: a.containerId,
      profile: a.profile,
      linked: a.linked ?? 0,
    })),
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

export function toPromptGroup(api: ApiPromptGroup): PromptGroup {
  return {
    id: api.id,
    name: api.name,
    description: api.description ?? '',
    promptIds: api.promptIds,
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

/**
 * A saved credential as the pickers and the library page read it.
 *
 * A secret entry's `value` is always `''` — the backend sends null and nothing else could be
 * sent — so the form binds to a blank box and blank means "keep". An entry the backend does
 * not mark recoverable is kept in the list rather than dropped: the operator has to see the
 * loss to replace it.
 */
export function toCredential(api: ApiCredential): Credential {
  return {
    id: api.id,
    name: api.name,
    description: api.description ?? '',
    entries: api.entries.map(toCredentialEntry),
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

function toCredentialEntry(api: ApiCredentialEntry): CredentialEntry {
  const secret = !!api.secret;
  return {
    key: api.key,
    // a secret's value is never sent; a plain entry missing one reads as empty, not absent
    value: secret ? '' : api.value ?? '',
    secret,
    set: !!api.set,
    // an entry whose flag did not arrive is treated as the more demanding case, so a picker
    // warns rather than silently offering a key that will fail the write
    recoverable: api.recoverable !== false,
  };
}

export function toSkillGroup(api: ApiSkillGroup): SkillGroup {
  return {
    id: api.id,
    name: api.name,
    description: api.description ?? '',
    skillIds: api.skillIds,
    guideId: api.guideId ?? '',
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

export function toSkillGuide(api: ApiSkillGuide): SkillGuide {
  return {
    id: api.id,
    name: api.name,
    description: api.description ?? '',
    body: api.body ?? '',
    category: api.category || 'general',
    skillIds: api.skillIds,
    mcpServerIds: api.mcpServerIds,
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

/** A part whose status this build does not know reads as `failed` rather than as success —
 *  the safe direction when the operator is deciding whether the deploy actually worked. */
export function toDeployedPart(api: ApiDeployedPart): DeployedPart {
  return {
    kind: oneOf(api.kind, ['skill', 'mcp', 'guide'], 'skill'),
    name: api.name ?? '',
    status: oneOf(api.status, ['deployed', 'skipped', 'failed'], 'failed'),
    detail: api.detail ?? '',
  };
}

function toSetupApiKey(api: ApiSetupApiKey): SetupApiKey {
  return {
    label: api.label ?? '',
    envVar: api.envVar ?? '',
    set: !!api.set,
    masked: api.masked ?? null,
    problem: api.problem ?? null,
  };
}

export function toAuthProvider(api: ApiSetupAuthProvider): AuthProvider {
  return {
    label: api.label ?? '',
    ok: !!api.ok,
    status: api.status ?? '',
    hint: api.hint ?? null,
    providerKey: api.providerKey ?? null,
  };
}

function toSetupKeyProvider(api: ApiSetupKeyProvider): SetupKeyProvider {
  return { label: api.label ?? '', ok: !!api.ok, status: api.status ?? '' };
}

function toSetupMessaging(api: ApiSetupMessaging): SetupMessaging {
  return {
    label: api.label ?? '',
    ok: !!api.ok,
    status: api.status ?? '',
    tokenVar: api.tokenVar ?? '',
    homeVar: api.homeVar ?? null,
    homeChannel: api.homeChannel ?? null,
  };
}

/**
 * A profile's credentials as the Setup tab renders them.
 *
 * <p>Every list defaults to empty rather than staying absent. This is read by running
 * `hermes status` inside a container, so a version of hermes that does not know about one of
 * these sections omits it — and the tab renders a section that is missing as "nothing set up",
 * which is true, instead of failing on a template binding to undefined.
 */
export function toAgentSetup(api: ApiAgentSetup): AgentSetup {
  return {
    envPath: api.envPath ?? '',
    envExists: !!api.envExists,
    apiKeys: (api.apiKeys ?? []).map(toSetupApiKey),
    authProviders: (api.authProviders ?? []).map(toAuthProvider),
    apiKeyProviders: (api.apiKeyProviders ?? []).map(toSetupKeyProvider),
    messaging: (api.messaging ?? []).map(toSetupMessaging),
  };
}

/** One entry of the model-provider registry. A provider the backend names but describes
 *  incompletely is treated as the most demanding case — key required, no catalog — so the
 *  picker asks for a key it may not need rather than omitting one it does. */
export function toLlmProvider(api: ApiModelProvider): LlmProvider {
  return {
    key: api.key,
    label: api.label || api.key,
    needsKey: api.needsKey !== false,
    oauth: !!api.oauth,
    hasCatalog: !!api.hasCatalog,
    envVar: api.envVar ?? null,
  };
}

/** A pull in flight. An unrecognised status reads as an error: a pull this build cannot name
 *  is one it cannot promise is still running, and a stuck 'pulling' row never clears. */
export function toPullState(api: ApiPullState): PullState {
  const status = api.status === 'pulling' || api.status === 'done' ? api.status : 'error';
  return { model: api.model ?? '', status, detail: api.detail ?? null };
}

export function toServerInfo(api: ApiServerInfo): ServerInfo {
  return {
    version: api.version ?? '',
    retained: api.retained ?? 0,
    startedAt: api.startedAt ?? 0,
  };
}

/**
 * One log line, attributed.
 *
 * <p>`agentId` is the whole reason this is a mapping rather than a cast: a docker tail, a
 * managed MCP service's tail and the dashboard's own log all arrive in the same wire shape and
 * belong to nobody, while a profile's supervised gateway log carries an authoritative profile
 * identity. Only the caller knows which it fetched, so only the caller can say.
 */
export function toLogEntry(api: ApiLogLine, agentId: string | null): LogEntry {
  return {
    ts: api.ts,
    level: api.level,
    source: api.source ?? '',
    agentId,
    msg: api.msg ?? '',
  };
}

const HOST_STATUSES: DockerHostStatus[] = ['connected', 'connecting', 'error', 'disconnected'];
const ENDPOINT_STATUSES: InferenceEndpointStatus[] = ['connected', 'error', 'unknown'];

/**
 * A docker daemon.
 *
 * <p>Everything past the id defaults, because a host that has never answered a probe has no
 * engine, version or latency to report — and the sidebar chip, the containers page and the
 * deploy modal all render this. A status the backend names that this build does not know reads
 * as `disconnected` rather than as connected: the summary chip is worst-of, and a state we
 * cannot interpret is not evidence a daemon is reachable.
 */
export function toDockerHost(api: ApiDockerHost): DockerHost {
  return {
    id: api.id,
    name: api.name ?? api.id,
    url: api.url ?? '',
    kind: api.kind === 'local' ? 'local' : 'remote',
    status: oneOf(api.status, HOST_STATUSES, 'disconnected'),
    engine: api.engine ?? null,
    apiVersion: api.apiVersion ?? null,
    latencyMs: api.latencyMs ?? null,
    note: api.note ?? null,
  };
}

/** A registered inference endpoint. An unprobed one reports no status, which is `unknown` — not
 *  an error, because nothing has failed yet. */
export function toInferenceEndpoint(api: ApiInferenceEndpoint): InferenceEndpoint {
  return {
    id: api.id,
    name: api.name ?? api.id,
    url: api.url ?? '',
    kind: api.kind === 'ollama' || api.kind === 'openai' ? api.kind : null,
    status: oneOf(api.status, ENDPOINT_STATUSES, 'unknown'),
    version: api.version ?? null,
    detail: api.detail ?? null,
    // default false: an old backend that does not send the flag cannot pull either
    canManageModels: api.canManageModels === true,
  };
}

/** One model on an endpoint. The optional fields are ollama's own — /v1/models supplies none
 *  of them, and a model pulled from a bare digest reports no family or parameter size either,
 *  so the row still has to render without them. */
export function toEndpointModel(api: ApiEndpointModel): EndpointModel {
  return {
    name: api.name,
    sizeBytes: api.sizeBytes ?? 0,
    family: api.family ?? '',
    parameterSize: api.parameterSize ?? '',
    modifiedAt: api.modifiedAt ?? 0,
  };
}

/** One model held in memory. An absent VRAM figure collapses to 0, which the row renders as
 *  nothing rather than as a zero. */
export function toRunningModel(api: ApiRunningModel): RunningModel {
  return { name: api.name, sizeVramBytes: api.sizeVramBytes ?? 0 };
}

/** One turn of a recorded session. `content` defaults to empty rather than staying absent: a
 *  tool turn legitimately carries none, and the viewer renders the tool call instead. */
export function toChatMessage(api: ApiChatMessage): ChatMessage {
  return {
    role: api.role ?? '',
    content: api.content ?? '',
    toolName: api.toolName ?? null,
    toolCalls: api.toolCalls ?? null,
    reasoning: api.reasoning ?? null,
    ts: api.ts ?? 0,
  };
}

/** One recorded session. Only `open` counts as open: the backend spells this from the agent's
 *  own state, and a status this build cannot read is not evidence a session is still live. */
export function toSessionInfo(api: ApiSession): SessionInfo {
  return {
    id: api.id,
    title: api.title ?? '',
    platform: api.platform ?? '',
    startedAt: api.startedAt ?? 0,
    messages: api.messages ?? 0,
    status: api.status === 'open' ? 'open' : 'closed',
  };
}
