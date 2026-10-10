import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AgentProfile, McpCatalogServer, McpServer } from '../core/models';
import { AgentMcpPanel } from './agent-mcp-panel';
import { buttonWith, el, type } from '../testing/dom';
import {
  agent, catalogServer as sharedCatalogServer, mcpServer as server,
} from '../testing/models';
import { provideStores } from '../testing/store';

/** The managed catalog entry these tests connect to. */
const catalogServer = (patch: Partial<McpCatalogServer> = {}): McpCatalogServer =>
  sharedCatalogServer('mcp-browser', {
    name: 'browser', serviceKey: 'browser', image: 'playwright:latest',
    connectionUrl: 'http://browser:1100/mcp', ...patch,
  });

/** One profile, carrying the MCP servers under test. */
const profile = (mcp: McpServer[]): AgentProfile => agent('a-1', { name: 'ops-bot', mcp });

/** Only what the panel reaches for on the store, so nothing here touches a backend. */
const storeStub = (agent: AgentProfile, catalog: McpCatalogServer[] = []) => ({
  agentMcp: {
    add: vi.fn().mockResolvedValue(true),
    update: vi.fn().mockResolvedValue(true),
    setEnabled: vi.fn().mockResolvedValue(true),
    connectCatalog: vi.fn().mockResolvedValue(true),
    syncCatalog: vi.fn().mockResolvedValue(true),
    unlinkCatalog: vi.fn().mockResolvedValue(true),
    remove: vi.fn().mockResolvedValue(true),
    test: vi.fn().mockResolvedValue(true),
  },
  agents: {
    byId: () => agent,
  },
  catalog: {
    servers: signal(catalog),
    byId: (id: string) => catalog.find(s => s.id === id) ?? null,
  },
  hosts: {
    hosts: signal([{ id: 'dh-local', name: 'localhost' }]),
    byId: (id: string) => ({ id, name: 'localhost' }),
  },
});

@Component({
  imports: [AgentMcpPanel],
  template: `<mc-agent-mcp-panel [agent]="agent()" />`,
})
class Host {
  readonly agent = signal(profile([]));
}

const render = (store: ReturnType<typeof storeStub>, agent: AgentProfile) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    // the panel links to /mcp-servers, so RouterLink needs a router present
    providers: [provideRouter([]), ...provideStores(store)],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.agent.set(agent);
  fixture.detectChanges();
  return fixture;
};

/** Types into an `[(ngModel)]` field and lets the two-way write settle. */
/** The add form's submit button — the catalog panel above it also says "connect". */
const submitAdd = (fixture: { nativeElement: unknown }): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.mcp-add .btn.primary')!;

describe('AgentMcpPanel roster', () => {
  it('summarizes each status, counting unknown and checking as unchecked', () => {
    const agent = profile([
      server('github'),
      server('files', { status: 'disabled', enabled: false }),
      server('search', { status: 'error', error: 'unreachable' }),
      server('notes', { status: 'unknown' }),
      server('mail', { status: 'checking' }),
    ]);
    const fixture = render(storeStub(agent), agent);

    expect(el(fixture).textContent)
      .toContain('1 connected · 1 disabled · 1 failing · 2 unchecked');
    expect(el(fixture).querySelectorAll('.mcp-row')).toHaveLength(5);
  });

  it('offers reconnect for a disconnected server and disconnect for a live one', () => {
    const agent = profile([server('github'), server('files', { status: 'disabled', enabled: false })]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    buttonWith(fixture, 'disconnect').click();
    expect(store.agentMcp.setEnabled).toHaveBeenCalledWith('a-1', 'github', false);

    buttonWith(fixture, 'reconnect').click();
    expect(store.agentMcp.setEnabled).toHaveBeenCalledWith('a-1', 'files', true);
  });

  it('probes every enabled server when the tab opens, and skips disabled ones', async () => {
    const agent = profile([server('github'), server('files', { status: 'disabled', enabled: false })]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();

    expect(store.agentMcp.test.mock.calls.map(call => call[1])).toEqual(['github']);
  });

  it('asks twice before forgetting a server', () => {
    const agent = profile([server('github')]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    buttonWith(fixture, 'forget').click();
    fixture.detectChanges();
    expect(store.agentMcp.remove).not.toHaveBeenCalled();

    buttonWith(fixture, 'confirm forget').click();
    expect(store.agentMcp.remove).toHaveBeenCalledWith('a-1', 'm-github');
  });

  it('shows sync only while the catalog is ahead of the linked alias', () => {
    const linked = server('browser', {
      origin: 'catalog', catalogServerId: 'mcp-browser', syncedRevision: 1,
      catalogRevision: 2, updateAvailable: true,
    });
    const agent = profile([linked]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    buttonWith(fixture, 'sync').click();
    expect(store.agentMcp.syncCatalog).toHaveBeenCalledWith('a-1', 'browser');
    expect(el(fixture).textContent).toContain('update available');
  });
});

describe('AgentMcpPanel add form', () => {
  it('requires a url for http and a command for stdio', async () => {
    const agent = profile([]);
    const fixture = render(storeStub(agent), agent);

    expect(submitAdd(fixture).disabled).toBe(true);
    await type(fixture, '.name-in', 'github');
    expect(submitAdd(fixture).disabled).toBe(true);

    await type(fixture, '.url-in', 'https://mcp.example.test/mcp');
    expect(submitAdd(fixture).disabled).toBe(false);

    const transport = el(fixture).querySelector<HTMLSelectElement>('.transport-in')!;
    transport.value = 'stdio';
    transport.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(submitAdd(fixture).disabled).toBe(true);   // the url does not count for stdio

    await type(fixture, '.cmd-in', 'npx');
    expect(submitAdd(fixture).disabled).toBe(false);
  });

  it('saves a trimmed http server, then probes what it configured', async () => {
    const agent = profile([server('github', { status: 'unknown' })]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();
    store.agentMcp.test.mockClear();

    await type(fixture, '.name-in', ' github ');
    await type(fixture, '.url-in', ' https://mcp.example.test/mcp ');
    submitAdd(fixture).click();
    await fixture.whenStable();

    expect(store.agentMcp.add).toHaveBeenCalledWith(
      'a-1', 'github', 'http', { url: 'https://mcp.example.test/mcp' });
    expect(store.agentMcp.test).toHaveBeenCalledWith('a-1', 'github');
  });

  it('loads an existing server for editing and updates it under its old name', async () => {
    const agent = profile([server('github')]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();

    buttonWith(fixture, 'edit').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('EDIT CUSTOM MCP SERVER');
    expect(el(fixture).querySelector<HTMLInputElement>('.name-in')!.value).toBe('github');

    await type(fixture, '.name-in', 'github-enterprise');
    submitAdd(fixture).click();
    await fixture.whenStable();

    expect(store.agentMcp.update).toHaveBeenCalledWith(
      'a-1', 'github', 'github-enterprise', 'http',
      { url: 'https://github.example.test/mcp' });
  });

  it('sends one connection header, and only once both halves are filled', async () => {
    const agent = profile([]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    await type(fixture, '.name-in', 'todos');
    await type(fixture, '.url-in', 'http://host.docker.internal:3000/mcp');
    await type(fixture, '.header-name-in', 'Authorization');
    expect(submitAdd(fixture).disabled).toBe(true);   // a name without a value is not a header

    await type(fixture, '.header-value-in', ' Bearer abc ');
    submitAdd(fixture).click();
    await fixture.whenStable();

    expect(store.agentMcp.add).toHaveBeenCalledWith('a-1', 'todos', 'http', {
      url: 'http://host.docker.internal:3000/mcp', headers: { Authorization: 'Bearer abc' },
    });
  });
});

describe('AgentMcpPanel catalog connect', () => {
  it('warns that a stopped managed server has to be started first', () => {
    const agent = profile([]);
    const store = storeStub(agent, [catalogServer()]);
    const fixture = render(store, agent);

    const select = el(fixture).querySelector<HTMLSelectElement>('.select')!;
    select.value = 'mcp-browser';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('start & connect');
    expect(el(fixture).textContent).toContain('stopped on localhost');
  });

  it('says plain connect once the server is already running', () => {
    const agent = profile([]);
    const store = storeStub(agent, [catalogServer({ runtimeState: 'running' })]);
    const fixture = render(store, agent);

    const select = el(fixture).querySelector<HTMLSelectElement>('.select')!;
    select.value = 'mcp-browser';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(el(fixture).textContent).not.toContain('start & connect');
    expect(el(fixture).querySelector<HTMLButtonElement>('.catalog-connect .btn')!.disabled).toBe(false);
  });
});

/** Picks a catalog entry in the connect form. */
const pickCatalog = (fixture: { nativeElement: unknown; detectChanges(): void }, id: string): void => {
  const select = el(fixture).querySelector<HTMLSelectElement>('.catalog-connect .select')!;
  select.value = id;
  select.dispatchEvent(new Event('change'));
  fixture.detectChanges();
};

describe('AgentMcpPanel catalog links', () => {
  it('proposes the catalog name as the alias, and connects under it', async () => {
    const agent = profile([]);
    const store = storeStub(agent, [catalogServer({ runtimeState: 'running' })]);
    const fixture = render(store, agent);
    pickCatalog(fixture, 'mcp-browser');
    await fixture.whenStable();

    expect(el(fixture).querySelector<HTMLInputElement>('.catalog-connect .input')!.value)
      .toBe('browser');

    el(fixture).querySelector<HTMLButtonElement>('.catalog-connect .btn')!.click();
    await fixture.whenStable();

    expect(store.agentMcp.connectCatalog).toHaveBeenCalledWith('a-1', 'mcp-browser', 'browser');
  });

  it('probes the alias it just linked, so the row is not left unchecked', async () => {
    const linked = server('browser', { origin: 'catalog', catalogServerId: 'mcp-browser' });
    const agent = profile([linked]);
    const store = storeStub(agent, [catalogServer({ runtimeState: 'running' })]);
    const fixture = render(store, agent);
    await fixture.whenStable();
    store.agentMcp.test.mockClear();
    pickCatalog(fixture, 'mcp-browser');
    await fixture.whenStable();

    el(fixture).querySelector<HTMLButtonElement>('.catalog-connect .btn')!.click();
    await fixture.whenStable();

    expect(store.agentMcp.test).toHaveBeenCalledWith('a-1', 'browser');
  });

  it('will not connect without an alias, or twice while one is in flight', async () => {
    const agent = profile([]);
    const store = storeStub(agent, [catalogServer({ runtimeState: 'running' })]);
    store.agentMcp.connectCatalog.mockReturnValue(new Promise(() => { /* never settles */ }));
    const fixture = render(store, agent);
    const connect = () => el(fixture).querySelector<HTMLButtonElement>('.catalog-connect .btn')!;

    expect(connect().disabled).toBe(true);          // nothing picked yet

    pickCatalog(fixture, 'mcp-browser');
    await fixture.whenStable();
    connect().click();
    connect().click();
    await fixture.whenStable();

    expect(store.agentMcp.connectCatalog).toHaveBeenCalledTimes(1);
  });

  it('keeps the form filled in when the connect was refused', async () => {
    const agent = profile([]);
    const store = storeStub(agent, [catalogServer({ runtimeState: 'running' })]);
    store.agentMcp.connectCatalog.mockResolvedValue(false);
    const fixture = render(store, agent);
    pickCatalog(fixture, 'mcp-browser');
    await fixture.whenStable();

    el(fixture).querySelector<HTMLButtonElement>('.catalog-connect .btn')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).querySelector<HTMLInputElement>('.catalog-connect .input')!.value)
      .toBe('browser');
  });

  it('asks twice before detaching an alias, then opens it for editing', async () => {
    const linked = server('browser', { origin: 'catalog', catalogServerId: 'mcp-browser' });
    const agent = profile([linked]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    buttonWith(fixture, 'customize').click();
    fixture.detectChanges();
    expect(store.agentMcp.unlinkCatalog).not.toHaveBeenCalled();

    buttonWith(fixture, 'confirm customize').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.agentMcp.unlinkCatalog).toHaveBeenCalledWith('a-1', 'browser');
    expect(el(fixture).textContent).toContain('EDIT CUSTOM MCP SERVER');
    expect(el(fixture).querySelector<HTMLInputElement>('.name-in')!.value).toBe('browser');
  });

  it('leaves the alias linked when the detach was refused', async () => {
    const linked = server('browser', { origin: 'catalog', catalogServerId: 'mcp-browser' });
    const agent = profile([linked]);
    const store = storeStub(agent);
    store.agentMcp.unlinkCatalog.mockResolvedValue(false);
    const fixture = render(store, agent);

    buttonWith(fixture, 'customize').click();
    fixture.detectChanges();
    buttonWith(fixture, 'confirm customize').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).textContent).not.toContain('EDIT CUSTOM MCP SERVER');
  });

  it('re-probes an enabled alias after a sync, and leaves a disabled one alone', async () => {
    const ahead = { origin: 'catalog' as const, catalogServerId: 'mcp-browser', updateAvailable: true };
    const agent = profile([
      server('browser', ahead),
      server('files', { ...ahead, enabled: false, status: 'disabled' }),
    ]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();
    store.agentMcp.test.mockClear();

    buttonWith(fixture, 'sync').click();
    await fixture.whenStable();

    expect(store.agentMcp.test).toHaveBeenCalledTimes(1);
    expect(store.agentMcp.test).toHaveBeenCalledWith('a-1', 'browser');
  });
});

describe('AgentMcpPanel probing', () => {
  it('re-probes a single server on demand, and refuses a second while one runs', async () => {
    const agent = profile([server('github'), server('files')]);
    const store = storeStub(agent);
    store.agentMcp.test.mockReturnValue(new Promise(() => { /* never settles */ }));
    const fixture = render(store, agent);
    await fixture.whenStable();

    // github's probe is still open and holds the lock, so files cannot start one
    buttonWith(fixture, 'retest').click();
    await fixture.whenStable();

    expect(store.agentMcp.test).toHaveBeenCalledTimes(1);
    expect(store.agentMcp.test).toHaveBeenCalledWith('a-1', 'github');
  });

  it('does not probe a server it just disabled', async () => {
    const agent = profile([server('github')]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();
    store.agentMcp.test.mockClear();

    buttonWith(fixture, 'disconnect').click();
    await fixture.whenStable();

    expect(store.agentMcp.test).not.toHaveBeenCalled();
  });

  it('clears the edit form when the server being edited is forgotten', async () => {
    const agent = profile([server('github')]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    buttonWith(fixture, 'edit').click();
    await fixture.whenStable();
    fixture.detectChanges();

    buttonWith(fixture, 'forget').click();
    fixture.detectChanges();
    buttonWith(fixture, 'confirm forget').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).textContent).not.toContain('EDIT CUSTOM MCP SERVER');
  });

  it('keeps the confirmation open when the delete was refused', async () => {
    const agent = profile([server('github')]);
    const store = storeStub(agent);
    store.agentMcp.remove.mockResolvedValue(false);
    const fixture = render(store, agent);

    buttonWith(fixture, 'forget').click();
    fixture.detectChanges();
    buttonWith(fixture, 'confirm forget').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('confirm forget');
  });
});
