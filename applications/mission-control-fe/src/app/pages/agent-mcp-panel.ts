import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AgentMcpStore } from '../core/store/agent-mcp-store';
import { AgentStore } from '../core/store/agent-store';
import { HostStore } from '../core/store/host-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { AgentProfile, McpServer } from '../core/models';
import { McpEndpointForm } from '../shared/mcp-endpoint-form';
import { StatusDot } from '../shared/status-dot';

/**
 * A catalog entry's display name turned into the alias offered as a default. A display name is
 * prose and an alias is a key in the Agent's config, so the two alphabets differ — the name went
 * in verbatim and any entry not already named like an alias came back `invalid MCP alias` from
 * the connect, which included two of the four the catalog seeds. Mirrors
 * `AgentMcpCatalogService.aliasFor`, and is applied here as well so the box shows the alias that
 * will actually be created — the probe that follows a connect looks the new server up by it.
 */
function aliasFor(displayName: string): string {
  return displayName.trim()
    .replace(/[^A-Za-z0-9_.-]+/g, '-')
    .replace(/^[^A-Za-z0-9]+/, '')
    .replace(/[^A-Za-z0-9]+$/, '');
}

/**
 * The profile's MCP tab: connect an alias to a catalog entry, edit servers
 * configured directly on the profile, and probe what each one answers. Every
 * mutation goes through the store; what lives here is the form and the
 * confirm-once state each row needs.
 */
@Component({
  selector: 'mc-agent-mcp-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot],
  templateUrl: './agent-mcp-panel.html',
  styleUrl: './agent-mcp-panel.scss',
})
export class AgentMcpPanel {
  readonly agent = input.required<AgentProfile>();

  protected readonly agentMcp = inject(AgentMcpStore);
  protected readonly agents = inject(AgentStore);
  protected readonly catalog = inject(McpCatalogStore);
  protected readonly hosts = inject(HostStore);

  /** add / edit form — an http endpoint is the common case here */
  protected readonly form = new McpEndpointForm('http');
  /** original server name when editing an existing server, else null */
  protected readonly editingMcp = signal<string | null>(null);

  // catalog connect form
  protected catalogServerId = '';
  protected catalogAlias = '';
  protected readonly catalogConnecting = signal(false);

  // one-click-then-confirm state, keyed by server id
  protected readonly customizingMcp = signal<string | null>(null);
  protected readonly forgettingMcp = signal<string | null>(null);
  /** mcp server id with a retest in flight */
  protected readonly mcpTesting = signal<string | null>(null);
  protected readonly mcpProbeBusy = signal(false);

  private readonly agentId = computed(() => this.agent().id);

  constructor() {
    // probe on open, and again if the tab is showing a different profile
    effect(() => {
      this.agentId();
      untracked(() => void this.probeAll());
    });
  }

  protected mcpCount(status: McpServer['status'] | 'unchecked'): number {
    const servers = this.agent().mcp;
    if (status === 'unchecked') {
      return servers.filter(m => m.status === 'unknown' || m.status === 'checking').length;
    }
    return servers.filter(m => m.status === status).length;
  }

  protected selectedCatalogServer() {
    return this.catalog.byId(this.catalogServerId);
  }

  protected selectCatalogServer(id: string): void {
    this.catalogServerId = id;
    this.catalogAlias = aliasFor(this.catalog.byId(id)?.name ?? '');
  }

  protected catalogConnectLabel(): string {
    const server = this.selectedCatalogServer();
    return server?.kind === 'managed' && server.runtimeState !== 'running'
      ? 'start & connect'
      : 'connect';
  }

  protected async connectCatalogMcp(): Promise<void> {
    const serverId = this.catalogServerId;
    const alias = this.catalogAlias.trim();
    if (!serverId || !alias || this.catalogConnecting()) return;
    this.catalogConnecting.set(true);
    try {
      if (await this.agentMcp.connectCatalog(this.agentId(), serverId, alias)) {
        const connected = this.serverNamed(alias);
        if (connected?.enabled) await this.runTest(connected);
        this.catalogServerId = '';
        this.catalogAlias = '';
      }
    } finally {
      this.catalogConnecting.set(false);
    }
  }

  protected async saveMcp(): Promise<void> {
    const opts = this.form.endpoint();
    const name = this.form.trimmedName();
    if (!opts) return;

    const editing = this.editingMcp();
    const savedOk = editing
      ? await this.agentMcp.update(this.agentId(), editing, name, this.form.transport, opts)
      : await this.agentMcp.add(this.agentId(), name, this.form.transport, opts);
    if (!savedOk) return;
    this.resetMcpForm();
    const saved = this.serverNamed(name);
    if (saved && saved.status !== 'disabled') await this.runTest(saved);
  }

  protected editMcp(m: McpServer): void {
    this.editingMcp.set(m.name);
    this.form.load(m);
  }

  protected resetMcpForm(): void {
    this.editingMcp.set(null);
    this.form.reset();
  }

  protected async setMcpConnected(m: McpServer, enabled: boolean): Promise<void> {
    const saved = await this.agentMcp.setEnabled(this.agentId(), m.name, enabled);
    if (!saved || !enabled) return;
    const refreshed = this.serverNamed(m.name);
    if (refreshed) await this.runTest(refreshed);
  }

  protected async syncMcp(m: McpServer): Promise<void> {
    if (!m.catalogServerId) return;
    if (await this.agentMcp.syncCatalog(this.agentId(), m.name) && m.enabled) {
      const refreshed = this.serverNamed(m.name);
      if (refreshed) await this.runTest(refreshed);
    }
  }

  /** Detaches a catalog alias, then loads it into the edit form. */
  protected async customizeMcp(m: McpServer): Promise<void> {
    if (!(await this.agentMcp.unlinkCatalog(this.agentId(), m.name))) return;
    this.customizingMcp.set(null);
    this.editMcp(this.serverNamed(m.name) ?? m);
  }

  protected async forgetMcp(m: McpServer): Promise<void> {
    if (await this.agentMcp.remove(this.agentId(), m.id)) {
      this.forgettingMcp.set(null);
      if (this.editingMcp() === m.name) this.resetMcpForm();
    }
  }

  protected testMcp(m: McpServer): void {
    if (this.mcpTesting()) return;
    void this.runTest(m);
  }

  private async runTest(m: McpServer): Promise<void> {
    if (m.status === 'disabled') return;
    this.mcpTesting.set(m.id);
    try {
      await this.agentMcp.test(this.agentId(), m.name);
    } finally {
      if (this.mcpTesting() === m.id) this.mcpTesting.set(null);
    }
  }

  /** Probes every enabled server in turn, stopping if the profile changes. */
  private async probeAll(): Promise<void> {
    const id = this.agentId();
    if (this.mcpProbeBusy()) return;
    this.mcpProbeBusy.set(true);
    try {
      for (const server of this.agent().mcp.filter(m => m.status !== 'disabled')) {
        if (this.agentId() !== id) break;
        await this.runTest(server);
      }
    } finally {
      this.mcpProbeBusy.set(false);
    }
  }

  private serverNamed(name: string): McpServer | undefined {
    return this.agents.byId(this.agentId())?.mcp.find(server => server.name === name);
  }
}
