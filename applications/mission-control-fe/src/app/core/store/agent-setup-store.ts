import { inject, Injectable, signal } from '@angular/core';
import { AgentSetup, AuthProvider, ChatMessage, SessionInfo } from '../models';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';
import { toAgentSetup, toAuthProvider, toChatMessage, toSessionInfo } from './wire-mappers';

/**
 * A profile's credentials (its `.env`) and its recorded chat sessions — the two
 * read-mostly surfaces the detail page's Setup and Sessions tabs live on. Both
 * answer `null` when the profile or its container is unknown, which the page
 * renders as "nothing loaded" rather than as an error.
 *
 * Setup is cached per profile: reading it runs `hermes status` inside the
 * container, which takes seconds, so re-entering the tab must not pay for it
 * again. Every write answers with the refreshed setup and replaces the entry, and
 * the Setup tab's refresh button forces a re-read.
 */
@Injectable({ providedIn: 'root' })
export class AgentSetupStore {
  /** Last known setup per agent id — the Setup tab renders straight off this. */
  readonly setups = signal<Record<string, AgentSetup>>({});

  /** Agent ids with a setup read in flight, so two views cannot both fetch. */
  readonly setupLoading = signal<ReadonlySet<string>>(new Set());

  private readonly ctx = inject(StoreContext);
  private readonly containers = inject(ContainerStore);
  private readonly agents = inject(AgentStore);

  /** The cached setup for a profile, or null if it has never been read. */
  setupOf(agentId: string): AgentSetup | null {
    return this.setups()[agentId] ?? null;
  }

  isSetupLoading(agentId: string): boolean {
    return this.setupLoading().has(agentId);
  }

  /**
   * Reads a profile's setup, answering the cached copy unless `force`. Returns
   * null when the read failed or the profile is unknown; the cached copy, if any,
   * is left in place so a failed refresh does not blank the tab.
   */
  async setup(agentId: string, force = false): Promise<AgentSetup | null> {
    const agent = this.agents.byId(agentId);
    if (!agent) return null;
    const cached = this.setupOf(agentId);
    if (cached && !force) return cached;
    if (this.isSetupLoading(agentId)) return cached;

    this.markLoading(agentId, true);
    try {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return null;
      try {
        return this.remember(agentId, toAgentSetup(await this.ctx.api.agents.setup(resolved.ref)));
      } catch (e) {
        this.ctx.toastFailure('setup load', e);
        return null;
      }
    } finally {
      this.markLoading(agentId, false);
    }
  }

  /** Empty/null entry value removes that key from the .env file. */
  setEnv(
    agentId: string,
    entries: Array<{ key: string; value: string | null; credentialId?: string }>,
  ): Promise<AgentSetup | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return Promise.resolve(null);
    }
    return this.ctx.api.agents.setEnv(resolved.ref, entries)
      .then(setup => this.remember(agentId, toAgentSetup(setup)))
      .catch(e => {
        this.ctx.toastFailure('env save', e);
        return null;
      });
  }

  /** Writes the commented-out .env template only when the file is missing. */
  initEnv(agentId: string): Promise<AgentSetup | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return Promise.resolve(null);
    }
    return this.ctx.api.agents.initEnv(resolved.ref)
      .then(setup => this.remember(agentId, toAgentSetup(setup)))
      .catch(e => {
        this.ctx.toastFailure('env init', e);
        return null;
      });
  }

  /** Container-level auth-provider status (Nous Portal OAuth etc.) for the create
   *  modal — readable before an agent exists. Failures degrade to an empty list
   *  so the modal still works without the status badge. */
  authProviders(containerId: string): Promise<AuthProvider[]> {
    const container = this.containers.byId(containerId);
    if (!container) return Promise.resolve([]);
    return this.ctx.api.agents.authProviders(container.hostId, containerId)
      .then(list => list.map(toAuthProvider))
      .catch(() => []);
  }

  /** Lists this agent's recorded sessions. */
  sessions(agentId: string): Promise<SessionInfo[] | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) return Promise.resolve(null);
    return this.ctx.api.agents.sessions(resolved.ref)
      .then(list => list.map(toSessionInfo))
      .catch(e => { this.ctx.toastFailure('sessions load', e); return null; });
  }

  /** Chat history (messages) for a single session. */
  sessionMessages(agentId: string, sessionId: string): Promise<ChatMessage[] | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return Promise.resolve(null);
    }
    return this.ctx.api.agents.sessionMessages(resolved.ref, sessionId)
      .then(list => list.map(toChatMessage))
      .catch(e => { this.ctx.toastFailure('session load', e); return null; });
  }

  /** Deletes a session file. */
  deleteSession(agentId: string, sessionId: string): Promise<void> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return Promise.resolve();
    }
    return this.ctx.api.agents.deleteSession(resolved.ref, sessionId);
  }

  /** Drops a profile's cached setup — its credentials are gone with it. */
  forget(agentId: string): void {
    this.setups.update(all => {
      if (!(agentId in all)) return all;
      const next = { ...all };
      delete next[agentId];
      return next;
    });
  }

  private remember(agentId: string, setup: AgentSetup): AgentSetup {
    this.setups.update(all => ({ ...all, [agentId]: setup }));
    return setup;
  }

  private markLoading(agentId: string, loading: boolean): void {
    this.setupLoading.update(ids => {
      const next = new Set(ids);
      if (loading) next.add(agentId);
      else next.delete(agentId);
      return next;
    });
  }
}
