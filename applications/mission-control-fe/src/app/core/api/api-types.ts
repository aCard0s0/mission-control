import { BoardColumn, TemplateMcp } from '../models';

// Wire shapes for mission-control-server. Everything the backend sends or
// accepts is declared here; the clients under this folder only compose URLs and
// the store maps these onto the domain models in ../models.
//
// `| null` means the backend can actually send null, and the mapper answers for it. It is not
// a hedge: the id and file lists on the libraries below are not null because the read side
// cannot produce one — `IdList.read`, `PromptRepository.readTags`, `SkillRepository.files`,
// `CredentialRepository.entries` and `ProfileTemplateRepository.readList` each answer an empty
// list for an absent, blank or unparseable column, so an old row cannot produce one either.
// Declaring them nullable anyway bought a `?? []` in the mapper and a spec that fed a null the
// backend has no way to send.

/** A docker daemon, as the backend describes it. Every field past the id is optional on the
 *  wire: a host that has never answered a probe has no engine, version or latency to report. */
export interface ApiDockerHost {
  id: string;
  name?: string;
  url?: string;
  kind?: 'local' | 'remote' | string;
  status?: 'connected' | 'connecting' | 'error' | 'disconnected' | string;
  engine?: string | null;
  apiVersion?: string | null;
  latencyMs?: number | null;
  note?: string | null;
}

/** A registered inference endpoint. `status` is the last probe's answer, and one that has
 *  not been probed yet reports none. */
export interface ApiInferenceEndpoint {
  id: string;
  name?: string;
  url?: string;
  /** protocol that answered the last probe; absent when none did. */
  kind?: 'ollama' | 'openai' | string | null;
  status?: 'connected' | 'error' | 'unknown' | string;
  version?: string | null;
  detail?: string | null;
  /** ollama only — the OpenAI-compatible protocol has no pull or delete. */
  canManageModels?: boolean;
}

/** One model on an endpoint, relayed from ollama's `GET {url}/api/tags`. Its optional fields
 *  are ollama's own: a model pulled from a bare digest reports no family or parameter size. */
export interface ApiEndpointModel {
  name: string;
  sizeBytes?: number;
  family?: string;
  parameterSize?: string;
  modifiedAt?: number;
}

/** One model the endpoint is holding in memory, from ollama's `GET {url}/api/ps`. A CPU-only
 *  load reports no VRAM, and an endpoint that cannot report this at all sends an empty list. */
export interface ApiRunningModel {
  name: string;
  sizeVramBytes?: number;
}

/** One turn of a recorded session, read out of the agent's own state.db. */
export interface ApiChatMessage {
  role?: string;
  content?: string;
  toolName?: string | null;
  toolCalls?: string | null;
  reasoning?: string | null;
  ts?: number;
}

export interface ApiContainer {
  id: string;
  shortId: string;
  name: string;
  hostId: string;
  status: 'running' | 'stopped' | 'unhealthy' | 'unknown';
  image: string;
  version: string;
  imageDigest: string | null;
  /** The Hermes release the image carries, as hermes reports it (`2026.8.19`); null when the
   *  container is not running or the image does not say. What a floating tag really means. */
  release: string | null;
  startedAt: number | null;
  sizeRootFsGb: number | null;
  profiles: string[];
  /** Container ports the daemon publishes on the host, one row per container port. */
  ports: ApiPublishedPort[];
}

export interface ApiPublishedPort {
  containerPort: number;
  hostIp: string;
  hostPort: number;
}

export interface ApiStats {
  cpuPercent: number;
  ramMb: number;
  ramTotalMb: number;
  rxBytes: number;
  txBytes: number;
  sampledAt: number;
}

export interface ApiLogLine {
  ts: number;
  level: 'info' | 'warn' | 'error' | 'debug';
  source: string;
  msg: string;
}

export interface ApiBoardTask {
  id: string;
  containerId: string;
  agentId: string | null;
  title: string;
  column: BoardColumn;
  priority: 'low' | 'med' | 'high';
  tags: string[];
  createdAt: number;
}

export interface ApiPrompt {
  id: string;
  title: string;
  body: string;
  category: string;
  notes: string | null;
  tags: string[];
  createdAt: number;
  updatedAt: number;
}

export interface ApiSkillFile {
  path: string;
  body: string;
}

/** A row in the skill *library*, which is dashboard-owned. `ApiSkillRef` below is the
 *  different thing: a skill already installed on one agent. */
export interface ApiSkill {
  id: string;
  kind: 'hub' | 'local' | string;
  name: string;
  description: string | null;
  category: string;
  repoUrl: string | null;
  version: string | null;
  files: ApiSkillFile[];
  createdAt: number;
  updatedAt: number;
}

/** `skipped` names files an import left behind because they are not text. */
export interface ApiImportedSkill {
  skill: ApiSkill;
  skipped: string[] | null;
}

/** Whether a skill's source repository has moved on. `latest` is null when nothing was
 *  read — an unreachable github, or a repository that publishes no releases or tags. */
export interface ApiUpstream {
  status: 'current' | 'update' | 'unknown' | 'unsupported' | 'unavailable' | string;
  latest: string | null;
  detail: string | null;
  checkedAt: number | null;
}

export interface ApiMcpGroupAgent {
  hostId: string;
  containerId: string;
  profile: string;
  linked: number;
}

export interface ApiMcpGroup {
  id: string;
  name: string;
  description: string | null;
  serverIds: string[];
  agents: ApiMcpGroupAgent[];
  createdAt: number;
  updatedAt: number;
}

/** A group deploy answers the refreshed profile and one row per server. */
export interface ApiDeployedMcpGroup {
  profile: ApiAgentProfile | null;
  parts: ApiDeployedPart[] | null;
}

/** One variable a credential fills. `value` is present only when the entry is not secret —
 *  a home channel or a base URL. A secret entry reports set/recoverable and carries no value,
 *  not even a suffix: a picker posts the credential's id back and never handles key material. */
export interface ApiCredentialEntry {
  key: string;
  value: string | null;
  secret: boolean;
  set: boolean;
  recoverable: boolean;
}

/** A credential saved once and offered wherever a key is typed. */
export interface ApiCredential {
  id: string;
  name: string;
  description: string | null;
  entries: ApiCredentialEntry[];
  createdAt: number;
  updatedAt: number;
}

export interface ApiPromptGroup {
  id: string;
  name: string;
  description: string | null;
  promptIds: string[];
  createdAt: number;
  updatedAt: number;
}

export interface ApiSkillGroup {
  id: string;
  name: string;
  description: string | null;
  skillIds: string[];
  guideId: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface ApiSkillGuide {
  id: string;
  name: string;
  description: string | null;
  body: string;
  category: string;
  skillIds: string[];
  mcpServerIds: string[];
  createdAt: number;
  updatedAt: number;
}

/** What one part of a guide deploy did. `detail` is null unless something went wrong. */
export interface ApiDeployedPart {
  kind: 'skill' | 'mcp' | 'guide' | string;
  name: string;
  status: 'deployed' | 'skipped' | 'failed' | string;
  detail: string | null;
}

export interface ApiDeployedGuide {
  /** Null when the agent could not be read back afterwards — the parts had already
   *  landed by then, so the report is answered without it rather than lost. */
  profile: ApiAgentProfile | null;
  parts: ApiDeployedPart[] | null;
}

export interface ApiImageTag {
  tag: string;
  pulled: boolean;
  remote: boolean;
  /** Registry manifest digest, when the registry reported one. Compared against a
   *  container's own image digest to tell whether a floating tag has moved on. */
  digest: string | null;
}

export interface ApiImageTags {
  repository: string;
  tags: string[];                      // every known tag, newest first
  entries?: ApiImageTag[];             // same order, with local-store presence
  newest?: string | null;              // newest pinned release, floating tags excluded
  registryStatus?: string;             // ok | cached | unavailable | unsupported | disabled
  registryDetail?: string | null;
}

export interface ApiSkillRef {
  id: string;
  name: string;
  source: 'bundled' | 'user' | 'hub' | string;
  version: string;
  description: string;
  enabled: boolean;
}

/** A skill's SKILL.md body and the files beside it. A backend that lists no
 *  files omits the key rather than sending an empty array. */
export interface ApiSkillContent {
  name: string;
  path: string;
  body: string;
  files?: string[];
}

export interface ApiMcpServer {
  id: string;
  name: string;
  transport: 'stdio' | 'http' | 'sse' | string;
  enabled?: boolean;
  origin?: 'custom' | 'catalog' | string;
  catalogServerId?: string | null;
  syncedRevision?: number | null;
  catalogRevision?: number | null;
  updateAvailable?: boolean;
  status: 'unknown' | 'connected' | 'error' | 'disabled' | string;
  tools: number;
  latencyMs: number | null;
  error?: string | null;
  checkedAt?: number | null;
  url?: string | null;
  command?: string | null;
  args?: string | null;
}

export interface ApiMcpTestResult {
  name: string;
  status: 'unknown' | 'connected' | 'error' | 'disabled' | string;
  tools: number;
  latencyMs: number | null;
  error: string | null;
  checkedAt: number;
}

/** An environment variable or HTTP header as the backend sends it. A row written
 *  before secrets were flagged carries no `secret`, and a secret's value never
 *  comes back at all. */
export interface ApiMcpConfigEntry {
  key: string;
  value?: string | null;
  secret?: boolean;
  set?: boolean;
  recoverable?: boolean;
}

export interface ApiMcpNamedVolume {
  name: string;
  target: string;
}

export interface ApiMcpHealthcheck {
  test: string[];
  interval?: string | null;
  timeout?: string | null;
  retries?: number | null;
  startPeriod?: string | null;
}

/** A service the backend renders into the managed stack alongside the server. */
export interface ApiMcpSupportService {
  name: string;
  image: string;
  platform?: string | null;
  entrypoint?: string[];
  command?: string[];
  environment?: ApiMcpConfigEntry[];
  volumes?: ApiMcpNamedVolume[];
  healthcheck?: ApiMcpHealthcheck | null;
}

/**
 * A catalog entry as the backend sends it, which is looser than the domain
 * `McpCatalogServer` in two ways worth stating in the type: a row an older
 * backend wrote can be missing any field that version did not have yet, and the
 * lifecycle states arrive in whatever case the backend spells them.
 *
 * `toMcpCatalogServer` is what closes both gaps. Declaring this as an alias of
 * the domain model — which it was — made the mapper's `?? []` defaults invisible
 * to the compiler and let unknown fields ride into the store on a spread.
 */
export interface ApiMcpCatalogServer {
  id: string;
  name?: string;
  description?: string;
  repoUrl?: string | null;
  kind?: string;
  hostId?: string | null;
  transport?: string;
  url?: string | null;
  image?: string | null;
  platform?: string | null;
  entrypoint?: string[];
  command?: string[];
  stdioCommand?: string | null;
  args?: string[];
  internalPort?: number | null;
  publishedPort?: number | null;
  path?: string | null;
  crossHostUrl?: string | null;
  connectionUrl?: string | null;
  headers?: ApiMcpConfigEntry[];
  environment?: ApiMcpConfigEntry[];
  volumes?: ApiMcpNamedVolume[];
  healthcheck?: ApiMcpHealthcheck | null;
  supportServices?: ApiMcpSupportService[];
  desiredState?: string;
  runtimeState?: string;
  operationState?: string;
  operationError?: string | null;
  checkStatus?: string;
  checkError?: string | null;
  checkedAt?: number | null;
  latencyMs?: number | null;
  /** Agent profiles carrying this entry — what a delete disables first. */
  linkedAgents?: number;
  /** When a managed service last started; every start pulls, so also when its image was last
   *  checked against the registry. Null unless running. */
  imageAsOf?: number | null;
  /** Docker Hub publishes a different digest on the image's tag than the container runs — the
   *  same rule the Hermes containers use. Null when that cannot be told. */
  imageUpdate?: boolean | null;
  revision?: number;
  appliedRevision?: number;
  pendingChanges?: boolean;
  serviceKey?: string | null;
  createdAt?: number;
  updatedAt?: number;
}

/** A named volume a delete deliberately left behind. */
export interface ApiMcpRetainedResource {
  id: string;
  serverId?: string | null;
  serverName?: string;
  hostId?: string;
  type?: string;
  name?: string;
  createdAt?: number;
}

export interface ApiIntegration {
  kind: string;
  status: 'up' | 'degraded' | 'down' | 'off' | string;
  detail: string;
}

/** What the gateway says about itself: the platforms above come out of the same file, and
 *  these are the fields beside them — how it is running, and whether `hermes pause` has it
 *  held. `activeAgents` is the count of turns in flight, which is the difference between
 *  stopping a container safely and dropping live work. */
export interface ApiGateway {
  state: string;
  desiredState: string;
  activeAgents: number;
  agentVersion: string;
  sessionStore: string;
  paused: boolean;
  pauseReason: string | null;
}

/** What stopping a container would interrupt. `unreadable` is the honest third answer:
 *  the gateway wrote no state, so nothing-in-flight is an absence of evidence. */
export interface ApiContainerActivity {
  activeAgents: number;
  busyProfiles: string[];
  /** Profiles whose create has not finished configuring — a stop now rolls them back. */
  creatingProfiles: string[];
  pausedProfiles: string[];
  unreadable: string[];
}

/** One outbound webhook target, from `hooks.outbound` in the profile's config.yaml. */
export interface ApiOutboundWebhook {
  name: string;
  url: string;
  events: string[];
  matcher: string | null;
  timeout: number | null;
  secretEnv: string | null;
  /** True when the config carries an inline `secret:`. Never its value — the dashboard will
   *  not show one and will not overwrite one. */
  literalSecret: boolean;
}

export interface ApiOutboundWebhookRequest {
  name: string;
  url: string;
  events: string[];
  matcher: string | null;
  timeout: number | null;
  secretEnv: string | null;
}

export interface ApiSession {
  id: string;
  title: string;
  platform: string;
  startedAt: number;
  messages: number;
  status: 'open' | 'closed' | string;
}

/** How a live catalog read authenticates: a typed key, or a saved credential the server
 *  resolves under the provider's own variable — one of the two. */
export interface ApiLiveModelsRequest {
  apiKey?: string;
  credentialId?: string;
}

export interface ApiModelCatalog {
  provider: string;
  models: string[];
  /** Closed on purpose: a trailing `| string` used to let `catalog` type-check without
   *  being named, which is how the union came to list two of the backend's three values. */
  source: 'catalog' | 'config' | 'live';
}

export interface ApiPullState {
  model: string;
  status: 'pulling' | 'done' | 'error';
  detail: string | null;
}

/** Optional override for the model hermes' auxiliary side tasks run on
 *  (compression, summarization, memory flush, …). Omitted entirely when the side
 *  tasks should follow the main model, which is the default. A blank `provider`
 *  means "same provider, different model" and inherits the main endpoint. */
export interface ApiAuxiliaryModel {
  provider?: string;
  model: string;
  baseUrl?: string;
  apiKey?: string;
}

export interface ApiAgentProfile {
  id: string;
  containerId: string;
  name: string;
  role: string;
  state: 'active' | 'idle' | 'dormant';
  provider: string;
  model: string;
  apiKeyMasked: string;
  cwd: string;
  soul: string;
  memoryMd: string;
  configYaml: string;
  skills: ApiSkillRef[];
  mcp: ApiMcpServer[];
  integrations: ApiIntegration[];
  gateway: ApiGateway;
  lastActive: number;
}

export interface ApiSetupApiKey {
  label: string;
  envVar: string;
  set: boolean;
  masked: string | null;
}

export interface ApiSetupAuthProvider {
  label: string;
  ok: boolean;
  status: string;
  hint: string | null;
}

export interface ApiModelProvider {
  key: string;
  label: string;
  needsKey: boolean;
  oauth: boolean;
  hasCatalog: boolean;
  envVar: string | null;          // API-key env var, or null for OAuth/keyless
}

export interface ApiSetupKeyProvider {
  label: string;
  ok: boolean;
  status: string;
}

export interface ApiSetupMessaging {
  label: string;
  ok: boolean;
  status: string;
  tokenVar: string;
  homeVar: string | null;
  homeChannel: string | null;
}

export interface ApiAgentSetup {
  envPath: string;
  envExists: boolean;
  apiKeys: ApiSetupApiKey[];
  authProviders: ApiSetupAuthProvider[];
  apiKeyProviders: ApiSetupKeyProvider[];
  messaging: ApiSetupMessaging[];
}

export interface ApiTemplateSecret {
  key: string;
  set: boolean;
  recoverable: boolean;
}

export interface ApiProfileTemplate {
  id: string;
  name: string;
  /** null on a blueprint written before the library had glyphs */
  icon: string | null;
  description: string;
  /** null on a blueprint written before the library had categories */
  category: string | null;
  provider: string;
  model: string;
  baseUrl: string;
  cwd: string;
  soul: string;
  memory: string;
  skills: string[];
  librarySkillIds: string[];
  guideIds: string[];
  mcpServers: TemplateMcp[];
  secrets: ApiTemplateSecret[];
  createdAt: number;
  updatedAt: number;
}

/**
 * One scheduled job, as hermes records it. `schedule` is hermes' own rendering —
 * only a cron schedule has an expression, while `once` and `interval` jobs are
 * stored as a timestamp and a minute count, so the display string is the one form
 * present for every kind and the one the editor hands back.
 */
export interface ApiCronJob {
  id: string;
  name: string | null;
  prompt: string | null;
  schedule: string | null;
  scheduleKind: string | null;
  deliver: string | null;
  enabled: boolean;
  state: string | null;
  repeatTimes: number | null;
  repeatDone: number;
  createdAt: number | null;
  nextRunAt: number | null;
  lastRunAt: number | null;
  lastStatus: string | null;
  lastError: string | null;
  skills: string[];
}

/** A profile's schedule, and whether the gateway that fires it is up. */
export interface ApiCronJobs {
  jobs: ApiCronJob[];
  schedulerRunning: boolean;
}

/** Create or edit a job. Every field is optional on an edit; a blank one is left alone. */
export interface ApiCronJobRequest {
  schedule?: string;
  prompt?: string;
  name?: string;
  deliver?: string;
  repeat?: number | null;
  skills?: string[];
}

/** One webhook route. The HMAC secret is never part of a listing. */
export interface ApiWebhookSubscription {
  name: string;
  description: string | null;
  url: string;
  events: string[];
  prompt: string | null;
  skills: string[];
  deliver: string | null;
  deliverOnly: boolean;
  secretMasked: string;
  createdAt: number | null;
}

/** The listener a route arrives on. */
export interface ApiWebhookPlatform {
  enabled: boolean;
  host: string | null;
  port: number | null;
  published: boolean;
}

export interface ApiWebhooks {
  subscriptions: ApiWebhookSubscription[];
  platform: ApiWebhookPlatform;
  outbound: ApiOutboundWebhook[];
}

export interface ApiSubscribeWebhookRequest {
  name: string;
  prompt?: string;
  description?: string;
  events?: string[];
  skills?: string[];
  deliver?: string;
  deliverChatId?: string;
  deliverOnly?: boolean;
}
