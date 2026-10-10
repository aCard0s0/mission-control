import { inject, Injectable } from '@angular/core';
import { errorMessage } from '../errors';
import { McpServer } from '../models';
import { McpEndpointOptions } from '../../shared/mcp-endpoint-form';
import { AgentStore } from './agent-store';
import { McpCatalogStore } from './mcp-catalog-store';
import { StoreContext } from './store-context';

/**
 * The MCP servers one profile connects to. Two kinds live side by side: servers
 * edited directly on the profile, and aliases linked to a catalog entry — which
 * is why connect/sync/unlink exist alongside plain add/update.
 */
@Injectable({ providedIn: 'root' })
export class AgentMcpStore {
  private readonly ctx = inject(StoreContext);
  private readonly agents = inject(AgentStore);
  private readonly catalog = inject(McpCatalogStore);

  async add(
    agentId: string, name: string, transport: McpServer['transport'], opts?: McpEndpointOptions,
  ): Promise<boolean> {
    if (!this.agents.byId(agentId)) return this.ctx.gone('profile');
    return this.agents.mutate(agentId, 'mcp add', ref => this.ctx.api.agents.mcp.add(ref, {
      name, transport, url: opts?.url, command: opts?.command, args: opts?.args,
      headers: opts?.headers,
    }));
  }

  /** Atomic direct-server edit/rename. Catalog-linked servers must be unlinked
   *  by the caller first, which keeps registry synchronization explicit. */
  async update(
    agentId: string, oldName: string, name: string, transport: McpServer['transport'],
    opts?: McpEndpointOptions,
  ): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    const existing = agent?.mcp.find(server => server.name === oldName);
    if (!agent || !existing) return this.ctx.gone('MCP server');
    if (oldName !== name && agent.mcp.some(server => server.name === name)) {
      this.ctx.toast(`MCP alias already exists: ${name}`);
      return false;
    }
    return this.agents.mutate(agentId, 'MCP update',
      ref => this.ctx.api.agents.mcp.update(ref, oldName, {
        name, transport, url: opts?.url, command: opts?.command, args: opts?.args,
        enabled: existing.enabled, headers: opts?.headers,
      }));
  }

  async setEnabled(agentId: string, serverName: string, enabled: boolean): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    if (!agent?.mcp.some(server => server.name === serverName)) {
      return this.ctx.gone('MCP server');
    }
    return this.agents.mutate(agentId, `MCP ${enabled ? 'connect' : 'disconnect'}`,
      ref => this.ctx.api.agents.mcp.setEnabled(ref, serverName, enabled));
  }

  /** Starts a stopped managed catalog server and waits for the real runtime
   *  state before writing any Agent configuration. */
  async connectCatalog(agentId: string, serverId: string, alias: string): Promise<boolean> {
    if (!alias.trim()) return false;   // the form's own guard, not a failure
    const resolved = this.agents.resolve(agentId);
    const catalog = this.catalog.byId(serverId);
    if (!resolved) return this.ctx.gone('profile');
    if (!catalog) return this.ctx.gone('catalog entry');
    const { agent, ref } = resolved;
    if (agent.mcp.some(server => server.name === alias)) {
      this.ctx.toast(`MCP alias already exists: ${alias}`);
      return false;
    }
    if (catalog.kind === 'managed' && catalog.hostId !== ref.hostId && !catalog.crossHostUrl) {
      this.ctx.toast(`MCP server ${catalog.name} needs an explicit cross-host URL for this Agent`);
      return false;
    }
    if (catalog.kind === 'managed' && catalog.runtimeState !== 'running') {
      if (!(await this.catalog.start(serverId))) return false;
      if (!(await this.catalog.waitUntilRunning(serverId))) return false;
    }

    return this.agents.mutate(agentId, 'MCP catalog connect',
      target => this.ctx.api.agents.mcp.connectCatalog(target, serverId, alias.trim()));
  }

  /** Re-applies the catalog definition onto an alias already linked to it. */
  async syncCatalog(agentId: string, alias: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    const linked = agent?.mcp.find(server => server.name === alias && server.catalogServerId);
    if (!agent || !linked?.catalogServerId) return this.ctx.gone('catalog link');
    return this.agents.mutate(agentId, 'MCP sync',
      ref => this.ctx.api.agents.mcp.syncCatalog(ref, alias));
  }

  /** Detaches the alias from the catalog so it can be edited directly. */
  async unlinkCatalog(agentId: string, alias: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    if (!agent?.mcp.some(server => server.name === alias)) return this.ctx.gone('MCP server');
    return this.agents.mutate(agentId, 'MCP customize',
      ref => this.ctx.api.agents.mcp.unlinkCatalog(ref, alias));
  }

  async remove(agentId: string, mcpId: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    const server = agent?.mcp.find(m => m.id === mcpId);
    if (!agent || !server) return this.ctx.gone('MCP server');
    return this.agents.mutate(agentId, 'mcp remove',
      ref => this.ctx.api.agents.mcp.remove(ref, server.name));
  }

  /** Retest a single MCP server's reachability. */
  async test(agentId: string, serverName: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    if (!agent) return this.ctx.gone('profile');
    this.patchServer(agentId, serverName, server => server.status === 'disabled'
      ? server
      : { ...server, status: 'checking' as const, error: null });

    const resolved = this.agents.resolve(agentId);
    if (!resolved) return this.ctx.gone('profile');
    try {
      const r = await this.ctx.api.agents.mcp.test(resolved.ref, serverName);
      this.patchServer(agentId, serverName, server => ({
        ...server, status: r.status as McpServer['status'], tools: r.tools,
        latencyMs: r.latencyMs, error: r.error, checkedAt: r.checkedAt,
      }));
      if (r.error) this.ctx.toast(`mcp ${serverName}: ${r.error}`);
      return r.status === 'connected';
    } catch (e) {
      const message = errorMessage(e);
      this.ctx.toast(`mcp test failed: ${message}`);
      this.patchServer(agentId, serverName, server => ({
        ...server, status: 'error' as const, latencyMs: null,
        error: message, checkedAt: Date.now(),
      }));
      return false;
    }
  }

  private patchServer(
    agentId: string, serverName: string, change: (server: McpServer) => McpServer,
  ): void {
    this.agents.update(agentId, x => ({
      ...x, mcp: x.mcp.map(m => m.name === serverName ? change(m) : m),
    }));
  }
}
