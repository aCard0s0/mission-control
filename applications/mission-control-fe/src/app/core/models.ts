// Domain models mirroring real Hermes Agent structures.
// A "container" is a Docker deployment of Hermes; an "agent" is a Hermes
// profile (~/.hermes/profiles/<name>/) living inside one container.

export type ContainerStatus = 'running' | 'stopped' | 'unhealthy' | 'unknown';
export type AgentState = 'active' | 'idle' | 'dormant';
export type LogLevel = 'info' | 'warn' | 'error' | 'debug';

export type DockerHostStatus = 'connected' | 'connecting' | 'error' | 'disconnected';

/** A Docker daemon Mission Control can deploy Hermes containers to. */
export interface DockerHost {
  id: string;
  name: string;
  /** unix:///var/run/docker.sock for local, tcp://host:port for remote */
  url: string;
  kind: 'local' | 'remote';
  status: DockerHostStatus;
  engine: string | null;       // e.g. "Docker 27.3"
  apiVersion: string | null;   // e.g. "1.47"
  latencyMs: number | null;
  /** human-readable reason when the host is not connected */
  note: string | null;
}

export type InferenceEndpointStatus = 'connected' | 'error' | 'unknown';

/**
 * A self-hosted model server Mission Control administers.
 *
 * <p>Agents reach it over its OpenAI-compatible `/v1` surface, so anything serving that —
 * ollama, LM Studio, MLX, vLLM, llama.cpp — can back one, local or remote. Only the
 * list/pull/delete management on this page is protocol-specific, which is what `kind` marks.
 */
export interface InferenceEndpoint {
  id: string;
  name: string;
  /** base url of the server, e.g. http://host.docker.internal:11434 */
  url: string;
  /**
   * Protocol that answered the last probe — `ollama` speaks /api/*, `openai` is the
   * OpenAI-compatible /v1 surface everything else serves (LM Studio, MLX, vLLM, llama.cpp).
   *
   * <p>null when nothing answered. It is probed rather than stored, so an endpoint that is
   * switched off simply has no known protocol until it comes back.
   */
  kind: 'ollama' | 'openai' | null;
  status: InferenceEndpointStatus;
  version: string | null;      // e.g. "0.6.4"
  /** human-readable reason when the provider is not connected */
  detail: string | null;
  /** whether models can be pulled and removed from here — ollama only. */
  canManageModels: boolean;
}

/**
 * One entry in the registry of who can serve a model to an Agent.
 *
 * <p>Distinct from {@link InferenceEndpoint}, which is a self-hosted server Mission
 * Control administers. This is a capability description: what to call it in a picker, whether
 * it wants an API key or an OAuth login, and which env var hermes reads that key from.
 */
export interface LlmProvider {
  key: string;
  label: string;
  needsKey: boolean;
  oauth: boolean;
  hasCatalog: boolean;
  /** API-key env var, or null for an OAuth or keyless provider. */
  envVar: string | null;
}

/**
 * Where a model list came from — the backend decides, and sends one of these.
 *
 * <p>It is on the wire so a page can say which list an operator is looking at. Worth
 * showing rather than hiding: a shipped list and a list read from the provider look
 * identical in a dropdown, and picking a model the provider no longer serves fails much
 * later, at the agent's first turn.
 */
export type ModelSource = 'catalog' | 'config' | 'live';

/**
 * The models one provider can serve, and where that list came from.
 *
 * <p>`source` is null when there is no provenance to report — an endpoint's own installed
 * models, or the empty list a free-text provider gets. The backend always sends one.
 */
export interface ModelCatalog {
  models: string[];
  source: ModelSource | null;
}

/** A model pull in progress on an endpoint, as its last poll reported it. */
export interface PullState {
  model: string;
  status: 'pulling' | 'done' | 'error';
  detail: string | null;
}

/** A model available on an endpoint (from ollama's GET {url}/api/tags). */
export interface EndpointModel {
  name: string;                // e.g. "gemma3:4b"
  sizeBytes: number;
  family: string;              // e.g. "gemma3"
  parameterSize: string;       // e.g. "4.3B"
  modifiedAt: number;          // epoch ms
}

/**
 * A model an endpoint is holding in memory right now (ollama's `GET {url}/api/ps`).
 *
 * <p>The other half of "what is in use": {@link EndpointModel} is what costs disk,
 * this is what costs the VRAM the next model needs.
 */
export interface RunningModel {
  name: string;
  sizeVramBytes: number;       // 0 when the load is CPU-only, or unreported
}

/** One tag of the Hermes image as seen from a specific docker host. */
export interface ImageTag {
  tag: string;
  pulled: boolean;             // already in that host's image store
  digest: string | null;       // registry manifest digest, when the registry reported one
}

/** Registry and local tags for one docker host, as the backend merged them. */
export interface ImageCatalog {
  repository: string;
  tags: ImageTag[];            // newest first
  registryStatus: string;      // ok | cached | unavailable | unsupported | disabled
  fetchedAt: number;
}

export interface HermesContainer {
  id: string;
  name: string;
  shortId: string;
  hostId: string;              // DockerHost this container runs on
  status: ContainerStatus;
  image: string;
  version: string;
  imageDigest: string | null;     // registry digest of the image it runs, null if never pulled
  release: string | null;         // the Hermes release hermes itself reports, null when unknown
  startedAt: number | null;       // epoch ms, null when stopped
  cpu: number;                    // percent 0–100
  ram: number;                    // MB used
  ramTotal: number;               // MB
  disk: number;                   // GB used
  diskTotal: number;              // GB
  netIn: number;                  // KB/s
  netOut: number;                 // KB/s
  cpuHist: number[];
  ramHist: number[];
  netHist: number[];
  /** Container ports published on the host, as the daemon lists them — the only record there is
   *  of what a deploy asked for or an operator mapped by hand. */
  published: PortMapping[];
}

/** A skill installed **on** an agent, read through from that container's disk.
 *  The library's own row is `Skill` below — a different thing with a different lifetime. */
export interface SkillRef {
  id: string;
  name: string;
  source: 'bundled' | 'user' | 'hub';
  version: string;
  description: string;
  enabled: boolean;
}

// ── skill library (dashboard-owned) ────────────────────────────────────────

/** `hub` rows are a pointer the Skills Hub resolves; `local` rows carry their own files,
 *  because hermes has no way to create a skill from an id it has never seen. */
export type SkillKind = 'hub' | 'local';

export interface SkillFile {
  path: string;
  body: string;
}

/** One row in the skill library — deployable onto any agent, and not necessarily on one
 *  yet. Distinct from `SkillRef`, which is what an agent already has. */
export interface Skill {
  id: string;
  kind: SkillKind;
  name: string;
  description: string;
  category: string;
  repoUrl: string;
  version: string;
  files: SkillFile[];
  createdAt: number;
  updatedAt: number;
}

/**
 * One agent an MCP group reaches, and how completely.
 *
 * `linked` counts how many of the group's servers that agent is connected to — a group of four
 * showing 2 is an agent someone half-disconnected. Derived from the agent links on every read,
 * never stored, so it can only ever say what the links say.
 */
export interface McpGroupAgent {
  hostId: string;
  containerId: string;
  profile: string;
  linked: number;
}

/**
 * An MCP group: a named set of catalog entries, deployable onto an agent in one action.
 *
 * The only group in this app that *does* something — a skill group and a prompt group file a
 * library, this one also has a deploy. It records no agents: connecting a group writes the same
 * `mcp_agent_links` rows the agent's own MCP tab writes, and {@link agents} is read back off
 * them. Many-to-many in both directions falls out of that with nothing storing it — one group
 * onto as many agents as you like, and an agent's links from several groups plus servers
 * connected individually.
 */
export interface McpGroup {
  id: string;
  name: string;
  description: string;
  serverIds: string[];
  agents: McpGroupAgent[];
  createdAt: number;
  updatedAt: number;
}

export interface McpGroupInput {
  name: string;
  description: string;
  serverIds: string[];
}

/**
 * A prompt group: how the prompt library is filed. Organization only — no behaviour, and
 * deleting one leaves every prompt it named in the library.
 *
 * A different axis from a prompt's `category` or its `tags`, which are a word and a loose
 * label on one row. `promptIds` is not a foreign key: a prompt can be deleted after a group
 * named it, so the page resolves the ids on read and drops what is gone.
 */
export interface PromptGroup {
  id: string;
  name: string;
  description: string;
  promptIds: string[];
  createdAt: number;
  updatedAt: number;
}

export interface PromptGroupInput {
  name: string;
  description: string;
  promptIds: string[];
}

/**
 * One variable a credential fills.
 *
 * `value` is `''` for a secret — the server never returns key material, so `set` and
 * `recoverable` are all a form has to go on. A plain entry (a messaging home channel, a base
 * URL) carries its value, because a picker that could not show it would be useless for the
 * pair it belongs to.
 */
export interface CredentialEntry {
  key: string;
  value: string;
  secret: boolean;
  set: boolean;                   // a value is stored
  recoverable: boolean;           // the stored value still decrypts
}

/**
 * A credential saved once and offered as a dropdown wherever a key is typed: an agent's
 * `.env`, the create-agent dialog, a blueprint's keys tab.
 *
 * A bundle of entries, not a single key, because that is the shape of the real things — a
 * messaging platform is a bot token plus a home channel, and a self-hosted provider takes a
 * base URL alongside its key.
 *
 * Autofill only. Picking one copies its value into the target then and there and the link
 * ends: deleting a credential breaks nothing already written, and rotating it changes nothing
 * already written either.
 */
export interface Credential {
  id: string;
  name: string;
  description: string;
  entries: CredentialEntry[];
  createdAt: number;
  updatedAt: number;
}

/** A credential on the way out. A blank `value` on a secret entry keeps the stored one —
 *  the editor never receives ciphertext, so blank is how it says "unchanged". */
export interface CredentialInput {
  name: string;
  description: string;
  entries: Array<{ key: string; value: string; secret: boolean }>;
}

/**
 * A skill group: how the library is filed, and optionally which guide explains the set.
 *
 * Organization only — a group has no deploy, and deleting one leaves every skill it named in
 * the library. A different axis from a skill's `category`, which is one word on one skill: a
 * group is a record, so it can be renamed, described, pointed at a guide, and hold skills
 * that disagree about their category.
 *
 * `guideId` is `''` when the group is filing and nothing more. Neither it nor `skillIds` is a
 * foreign key — the rows behind them can go at any time, so the page resolves them on read
 * and marks what is missing.
 */
export interface SkillGroup {
  id: string;
  name: string;
  description: string;
  skillIds: string[];
  guideId: string;
  createdAt: number;
  updatedAt: number;
}

export interface SkillGroupInput {
  name: string;
  description: string;
  skillIds: string[];
  guideId: string;
}

/**
 * A guide: prose teaching how to use several library skills together, with the MCP servers
 * they need. Deploying one puts every part on the agent and writes the prose itself there
 * as an umbrella skill, so the agent reads it too.
 *
 * `name` is a directory name as well as a label — the umbrella skill is written into it.
 */
export interface SkillGuide {
  id: string;
  name: string;
  description: string;
  body: string;
  category: string;
  skillIds: string[];
  mcpServerIds: string[];
  createdAt: number;
  updatedAt: number;
}

export interface SkillGuideInput {
  name: string;
  description: string;
  body: string;
  category: string;
  skillIds: string[];
  mcpServerIds: string[];
}

/** One line of a guide deploy's report. A guide can half-land, so each part says so. */
export interface DeployedPart {
  kind: 'skill' | 'mcp' | 'guide';
  name: string;
  status: 'deployed' | 'skipped' | 'failed';
  detail: string;
}

export type UpstreamStatus =
  'current' | 'update' | 'unknown' | 'unsupported' | 'unavailable' | 'checking';

/** What a repository check found. `checking` is the frontend's own state while in flight. */
export interface Upstream {
  status: UpstreamStatus;
  latest: string;
  detail: string;
  checkedAt: number | null;
}

/** Editor payload sent on save — a create has no id, an edit is a PUT. */
export interface SkillInput {
  kind: SkillKind;
  name: string;
  description: string;
  category: string;
  repoUrl: string;
  version: string;
  files: SkillFile[];
}

/** Full SKILL.md body + file listing for inspecting/editing a skill. */
export interface SkillContent {
  name: string;
  path: string;
  body: string;
  files: string[];
}

export type McpStatus = 'unknown' | 'checking' | 'connected' | 'error' | 'disabled';

export interface McpServer {
  id: string;
  name: string;
  transport: 'stdio' | 'http' | 'sse';
  enabled: boolean;
  origin: 'custom' | 'catalog';
  catalogServerId: string | null;
  syncedRevision: number | null;
  catalogRevision: number | null;
  updateAvailable: boolean;
  status: McpStatus;
  tools: number;
  latencyMs: number | null;
  error?: string | null;
  checkedAt?: number | null;
  url?: string;                   // http/sse endpoint (for the edit form)
  command?: string;               // stdio command
  args?: string;                  // stdio args, space-joined
}

// ── global MCP server catalog ──────────────────────────────────────────────

export type McpCatalogKind = 'managed' | 'external' | 'stdio';
export type McpTransport = 'stdio' | 'http' | 'sse';
export type McpDesiredState = 'running' | 'stopped';
/**
 * Every value `operationState` can hold. The backend's McpOperationState enum is the single
 * source of it; this is the same eight names, and what the field is checked against.
 *
 * The field itself stays a plain `string` rather than this union, deliberately: an
 * unrecognised state has to count as an operation still running, and narrowing it in the
 * mapper would collapse one to a fallback that reads as settled and unfreeze the controls.
 */
export type McpOperationState =
  | 'provisioning' | 'reconciling' | 'starting' | 'stopping' | 'applying' | 'deleting'
  | 'idle' | 'error';

export type McpRuntimeState =
  'running' | 'stopped' | 'missing' | 'unavailable' | 'unknown' | 'error';
export type McpCheckStatus = 'unknown' | 'checking' | 'connected' | 'error';

/** A redacted environment variable or HTTP header. The backend never returns
 *  `value` for a secret; blank values in update requests retain a stored value. */
export interface McpConfigEntry {
  key: string;
  value?: string | null;
  secret: boolean;
  set?: boolean;
  recoverable?: boolean;
  clear?: boolean;
}

/** Named volumes only. Host paths and bind mounts are intentionally not
 *  representable by the UI or API model. */
export interface McpNamedVolume {
  name: string;
  target: string;
}

export interface McpHealthcheck {
  test: string[];
  interval?: string | null;
  timeout?: string | null;
  retries?: number | null;
  startPeriod?: string | null;
}

/** Support services are rendered by the backend into the managed stack. The
 *  first UI version preserves this structured data when editing a server. */
export interface McpSupportService {
  name: string;
  image: string;
  platform?: string | null;
  entrypoint?: string[];
  command?: string[];
  environment?: McpConfigEntry[];
  volumes?: McpNamedVolume[];
  healthcheck?: McpHealthcheck | null;
}

export interface McpCatalogServer {
  id: string;
  name: string;
  description: string;
  /** Where this entry comes from, or '' — documentation, never fetched by anything. */
  repoUrl: string;
  kind: McpCatalogKind;
  hostId: string | null;
  transport: McpTransport;
  url: string | null;
  image: string | null;
  platform: string | null;
  entrypoint: string[];
  /** List-form command override for managed Compose services. */
  command: string[];
  stdioCommand: string | null;
  args: string[];
  internalPort: number | null;
  publishedPort: number | null;
  path: string | null;
  crossHostUrl: string | null;
  /** Computed usable endpoint returned by the backend. */
  connectionUrl: string | null;
  headers: McpConfigEntry[];
  environment: McpConfigEntry[];
  volumes: McpNamedVolume[];
  healthcheck: McpHealthcheck | null;
  supportServices: McpSupportService[];
  desiredState: McpDesiredState;
  runtimeState: McpRuntimeState;
  operationState: string;
  operationError: string | null;
  checkStatus: McpCheckStatus;
  checkError: string | null;
  checkedAt: number | null;
  latencyMs: number | null;
  linkedAgents: number;           // agent profiles carrying this entry
  imageAsOf: number | null;       // last start of a managed service = last image pull/verify
  imageUpdate: boolean | null;    // the registry moved the tag past what runs; null = cannot tell
  revision: number;
  appliedRevision: number;
  pendingChanges: boolean;
  serviceKey: string | null;
  createdAt: number;
  updatedAt: number;
}

/** Editable catalog fields accepted by POST and PUT. Host assignment is
 *  immutable after create; the backend enforces that invariant. */
export interface McpCatalogServerInput {
  name: string;
  repoUrl: string;
  description: string;
  kind: McpCatalogKind;
  hostId: string | null;
  transport: McpTransport;
  url: string | null;
  image: string | null;
  platform: string | null;
  entrypoint: string[];
  command: string[];
  stdioCommand: string | null;
  args: string[];
  internalPort: number | null;
  publishedPort: number | null;
  path: string | null;
  crossHostUrl: string | null;
  headers: McpConfigEntry[];
  environment: McpConfigEntry[];
  volumes: McpNamedVolume[];
  healthcheck: McpHealthcheck | null;
  supportServices: McpSupportService[];
}

export interface McpRetainedResource {
  id: string;
  serverId: string | null;
  serverName: string;
  hostId: string;
  type: 'volume' | string;
  name: string;
  createdAt: number;
}

/** Whatever the gateway calls it. This used to be a closed union of the ten kinds the
 *  dashboard had a card for, which the backend filtered against — so a profile talking over
 *  anything newer rendered as "no integrations" while the gateway was connected. The names
 *  below are the ones worth styling; the type stays open because hermes adds channels
 *  faster than a union can be edited. */
export type IntegrationKind =
  | 'slack' | 'whatsapp' | 'discord' | 'telegram' | 'signal' | 'email'
  | 'github' | 'filesystem' | 'browser' | 'database'
  | (string & {});

export type IntegrationStatus = 'up' | 'degraded' | 'down' | 'off';

export interface Integration {
  kind: IntegrationKind;
  status: IntegrationStatus;
  detail: string;                 // e.g. "gateway up 14d · @ops-bot"
}

/** How a profile's gateway is running, from the file it keeps for saying so.
 *  `activeAgents` is the count of turns in flight — the difference between stopping a
 *  container safely and dropping live work — and `paused` is hermes' own emergency stop. */
export interface Gateway {
  state: string;
  desiredState: string;
  activeAgents: number;
  agentVersion: string;
  sessionStore: string;
  paused: boolean;
  pauseReason: string | null;
}

export interface SessionInfo {
  id: string;
  title: string;
  platform: string;
  startedAt: number;
  messages: number;
  status: 'open' | 'closed';
}

/** One turn in a session's chat history (from the agent's state.db messages table). */
export interface ChatMessage {
  role: string;                   // user | assistant | tool | system
  content: string;
  toolName?: string | null;       // tool name for tool turns / tool results
  toolCalls?: string | null;      // raw JSON string of requested tool calls
  reasoning?: string | null;      // model reasoning content, when present
  ts: number;                     // epoch ms
}

export interface AgentProfile {
  id: string;
  containerId: string;
  name: string;                   // profile name → ~/.hermes/profiles/<name>/
  role: string;                   // human description
  state: AgentState;
  provider: string;
  model: string;
  apiKeyMasked: string;
  cwd: string;                    // terminal.cwd from config.yaml
  soul: string;                   // SOUL.md content (the one safe-editable file)
  memoryMd: string;               // MEMORY.md (read-only view)
  configYaml: string;             // config.yaml (read-only view)
  skills: SkillRef[];
  mcp: McpServer[];
  integrations: Integration[];
  gateway: Gateway;
  sessions: SessionInfo[];
  msgsToday: number;
  tokensToday: number;            // thousands
  errorRate: number;              // percent
  lastActive: number;             // epoch ms
}

/** An API key hermes reads from a profile's `.env`, and whether it is there. Never the
 *  value: the backend returns a mask, and only the tail of one. */
export interface SetupApiKey {
  label: string;
  envVar: string;
  set: boolean;
  masked: string | null;
  /** What hermes' credential pool remembers going wrong with this key — `auth failed
   *  invalid_api_key (401) …` — or null. The pool outlives the `.env` line, so a key can be
   *  unset here and still be what every turn dies on. */
  problem: string | null;
}

/** An OAuth login the container holds (Nous Portal and the like). `hint` is what to tell an
 *  operator when it is not connected — usually the command that connects it. */
export interface AuthProvider {
  label: string;
  ok: boolean;
  status: string;
  hint: string | null;
  /** The provider-registry key this login serves (`nous`, `openai-codex`), or null for a login
   *  the picker cannot point a profile at. */
  providerKey: string | null;
}

/** A provider whose key is present in the profile's `.env`, as hermes reports it. */
export interface SetupKeyProvider {
  label: string;
  ok: boolean;
  status: string;
}

/** A messaging platform wired to a profile — Discord, Telegram and friends. `homeChannel` is
 *  where it posts unprompted, which is the setting an operator most often has to check. */
export interface SetupMessaging {
  label: string;
  ok: boolean;
  status: string;
  tokenVar: string;
  homeVar: string | null;
  homeChannel: string | null;
}

/**
 * What a profile's credentials look like from outside the container: which `.env` file
 * holds them, whether it exists at all, and the state of everything hermes reads out of it.
 *
 * <p>Read by running `hermes status` inside the container, which takes seconds — so this is
 * cached per profile rather than polled.
 */
export interface AgentSetup {
  envPath: string;
  envExists: boolean;
  apiKeys: SetupApiKey[];
  authProviders: AuthProvider[];
  apiKeyProviders: SetupKeyProvider[];
  messaging: SetupMessaging[];
}

/**
 * The model hermes' side tasks run on — compression, summarization, memory flush — when they
 * should not follow the main model.
 *
 * <p>Absent means "follow the main model", which is the default and the common case. A blank
 * `provider` means "same provider, different model" and inherits the main endpoint.
 */
export interface AuxiliaryModel {
  provider?: string;
  model: string;
  baseUrl?: string;
  apiKey?: string;
}

/**
 * Everything needed to create a profile, as the create dialog assembles it.
 *
 * <p>A record rather than a positional argument list: this is nine fields, five of them
 * optional, and the two orderings that matter — provider before model, cloneFrom before
 * fromTemplateId — are not ones a reader can recover from a call site. The store resolves
 * `containerId` to a docker host before this reaches the wire.
 */
export interface NewAgent {
  containerId: string;
  name: string;
  provider: string;
  model: string;
  apiKey: string;
  /** id of a saved credential to take the key from instead of `apiKey`. */
  apiKeyCredentialId?: string;
  /** id of a profile to copy files from. */
  cloneFrom?: string;
  baseUrl?: string;
  /** id of a blueprint to seed files from. */
  fromTemplate?: string;
  auxiliary?: AuxiliaryModel;
}

/** An MCP server defined in a profile template (no live status — that exists only
 *  once deployed onto an agent). */
export interface TemplateMcp {
  name: string;
  transport: 'stdio' | 'http' | 'sse';
  url?: string;
  command?: string;
  args?: string;
  enabled: boolean;
}

/** A template secret as returned from the backend — only flags, never any value.
 *  `recoverable` is false when a stored value can no longer be decrypted (the
 *  MC_SECRET_KEY changed) and must be re-entered. */
export interface TemplateSecret {
  key: string;
  set: boolean;                   // a value is stored
  recoverable: boolean;           // the stored value still decrypts
}

/**
 * A reusable agent blueprint (soul, memory, skills, MCP servers, encrypted keys).
 * Dashboard-owned and container-independent; applied when deploying an agent.
 * Distinct from {@link AgentProfile}, which is a live agent instance.
 */
/**
 * The ceiling a deployed Hermes container runs under.
 *
 * <p>Defaults live in {@link HERMES_BASELINE}, which is the vendor's own
 * recommendation rather than Docker's default of no limit at all.
 */
export interface ContainerResources {
  memoryMb: number;
  cpus: number;
}

/** A container port published on the host; a blank `hostIp` binds loopback. */
export interface PortMapping {
  containerPort: number;
  hostPort: number;
  hostIp: string;
}

export interface EnvVar {
  key: string;
  value: string;
}

/** A host directory bound into the container. */
export interface Mount {
  source: string;
  target: string;
  readOnly: boolean;
}

/**
 * What a form opens between the container and its host. All create-time — Docker cannot add
 * any of it to a running container — so it is asked on the deploy, or on the update that
 * recreates the container anyway.
 */
export interface HostAccess {
  ports: PortMapping[];
  env: EnvVar[];
  mounts: Mount[];
}

export interface ProfileTemplate {
  id: string;
  name: string;
  icon: string;                   // key into the built-in glyph set; '' for the default
  description: string;
  category: string;               // lower-cased by the backend, so filters cannot split
  provider: string;
  model: string;
  baseUrl: string;
  cwd: string;
  soul: string;
  memory: string;
  skills: string[];               // Skills Hub ids, installed by name
  librarySkillIds: string[];      // rows of the skill library, resolved when deployed
  guideIds: string[];             // guides — each brings its skills, MCP servers and umbrella SKILL.md
  mcpServers: TemplateMcp[];
  secrets: TemplateSecret[];
  createdAt: number;
  updatedAt: number;
}

/** Editor payload sent to the backend on save. Blank secret values keep the
 *  stored secret; non-blank values replace it. */
export interface ProfileTemplateInput {
  name: string;
  icon: string;
  description: string;
  category: string;
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
  secrets: Array<{ key: string; value: string; credentialId?: string }>;
}

export interface CronJob {
  id: string;
  containerId: string;
  agentId: string;
  name: string;
  schedule: string;               // 5-field cron / "every monday 9am" / "30m"
  prompt: string;
  deliverTo: string;              // platform the result is delivered to
  enabled: boolean;
  lastRun: number | null;
  lastStatus: 'ok' | 'fail' | null;
  nextRun: number;
}

export interface LogEntry {
  ts: number;
  level: LogLevel;
  source: string;                 // gateway / scheduler / agent name / mcp
  agentId: string | null;
  msg: string;
}

/** What Mission Control's own process reports about itself, for the header of its log page. */
export interface ServerInfo {
  version: string;
  /** how many lines the server's in-memory ring holds before the oldest fall out */
  retained: number;
  startedAt: number;
}

export type BoardColumn = 'queued' | 'running' | 'review' | 'done';

export interface BoardTask {
  id: string;
  containerId: string;
  agentId: string;
  title: string;
  column: BoardColumn;
  priority: 'low' | 'med' | 'high';
  tags: string[];
  createdAt: number;
}

/**
 * One entry in the prompt library — like a board task, dashboard-owned state with no
 * Hermes home: nothing inside a container reads it. It is text an operator keeps so it
 * can be found again and pasted where it is needed (a session, a cron job, a webhook).
 */
export interface Prompt {
  id: string;
  title: string;
  body: string;
  category: string;               // lower-cased by the backend, so filters cannot split
  notes: string;
  tags: string[];
  createdAt: number;
  updatedAt: number;
}

/** Editor payload sent to the backend on save — a create has no id, an edit is a PUT. */
export interface PromptInput {
  title: string;
  body: string;
  category: string;
  notes: string;
  tags: string[];
}

/**
 * One inbound webhook route on a profile, as hermes holds it.
 *
 * `secretMasked` is all a listing carries: hermes stores the HMAC secret in plaintext and
 * the sending provider needs it, so revealing it in full is a separate request.
 */
export interface WebhookRoute {
  name: string;
  description: string;
  url: string;
  events: string[];               // empty means every event
  prompt: string;
  skills: string[];
  deliver: string;
  deliverOnly: boolean;
  secretMasked: string;
  createdAt: number | null;
  /** the profile that owns the route, and the container it runs in */
  agentId: string;
  containerId: string;
}

/**
 * One outbound webhook target: where the agent POSTs signed lifecycle events. The mirror of
 * {@link WebhookRoute} — a route wakes the agent, a target tells the world.
 *
 * <p>`index` is its position in `hooks.outbound`, which is the only handle hermes gives one:
 * `name` is optional and not unique, so every edit addresses it by position.
 */
export interface OutboundWebhook {
  index: number;
  name: string;
  url: string;
  events: string[];
  matcher: string | null;
  timeout: number | null;
  secretEnv: string | null;
  /** the config carries an inline `secret:`; never its value */
  literalSecret: boolean;
  agentId: string;
  containerId: string;
}

/** A profile's webhook listener, which must be on before any route can fire. */
export interface WebhookListener {
  agentId: string;
  enabled: boolean;
  host: string | null;
  port: number | null;
  /** false whenever nothing outside the docker network can reach the listener */
  published: boolean;
}
