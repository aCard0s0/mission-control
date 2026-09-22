import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AgentSetupPanel } from './agent-setup-panel';
import { buttonWith, el } from '../testing/dom';
import { agent } from '../testing/models';
import { Credential } from '../core/models';
import { provideStores } from '../testing/store';

const setupFor = (name: string, patch: object = {}) => ({
  envPath: `/opt/data/profiles/${name}/.env`,
  envExists: true,
  apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: false, masked: null, problem: null }],
  authProviders: [{ label: 'Nous Portal', ok: false, status: 'not logged in', hint: 'hermes portal', providerKey: 'nous' }],
  apiKeyProviders: [],
  messaging: [{
    label: 'Telegram', ok: false, status: 'not configured',
    tokenVar: 'TELEGRAM_BOT_TOKEN', homeVar: 'TELEGRAM_HOME_CHANNEL', homeChannel: null,
  }],
  ...patch,
});

/** A store stub with the same caching contract as AgentSetupStore. */
const storeStub = () => {
  const cache = signal<Record<string, ReturnType<typeof setupFor>>>({});
  const loading = signal<ReadonlySet<string>>(new Set());
  const setup = {
    reads: [] as Array<{ id: string; force: boolean }>,
    setupOf: (id: string) => cache()[id] ?? null,
    isSetupLoading: (id: string) => loading().has(id),
    setup: vi.fn((id: string, force = false) => {
      setup.reads.push({ id, force });
      if (cache()[id] && !force) return Promise.resolve(cache()[id]);
      cache.update(all => ({ ...all, [id]: setupFor(id) }));
      return Promise.resolve(cache()[id]);
    }),
    setEnv: vi.fn((id: string, entries: Array<{ key: string; value: string | null }>) => {
      cache.update(all => ({
        ...all,
        [id]: setupFor(id, {
          apiKeys: [{
            label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY',
            set: entries[0].value !== null, masked: entries[0].value ? '…here' : null, problem: null,
          }],
        }),
      }));
      return Promise.resolve(cache()[id]);
    }),
    initEnv: vi.fn((id: string) => {
      cache.update(all => ({ ...all, [id]: setupFor(id, { envExists: true }) }));
      return Promise.resolve(cache()[id]);
    }),
  };
  const saved = signal<Credential[]>([]);
  const credentials = {
    credentials: saved,
    providing: (envVar: string) =>
      saved().filter(c => c.entries.some(e => e.key === envVar)),
  };
  return { setup, terminal: { open: vi.fn() }, credentials, cache, loading, saved };
};

/** A saved credential holding one secret per key given. */
const credential = (id: string, name: string, keys: string[]): Credential => ({
  id, name, description: '', createdAt: 1, updatedAt: 1,
  entries: keys.map(key => ({ key, value: '', secret: true, set: true, recoverable: true })),
});



@Component({
  imports: [AgentSetupPanel],
  template: `<mc-agent-setup-panel [agent]="agent()" />`,
})
class Host {
  readonly agent = signal(profile());
}

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return fixture;
};

const profile = (id = 'a-atlas', name = 'atlas') => agent(id, { name });

/** The picker beside a row. These selects carry an aria-label rather than sitting in a
 *  labelled `.field`, so the shared `choose` helper cannot address them. */
const picker = (fixture: ComponentFixture<Host>, envVar: string) =>
  el(fixture).querySelector<HTMLSelectElement>(
    `select[aria-label="saved credential for ${envVar}"]`);

const pick = (fixture: ComponentFixture<Host>, envVar: string, value: string) => {
  const select = picker(fixture, envVar);
  if (!select) throw new Error(`no picker for ${envVar}`);
  select.value = value;
  select.dispatchEvent(new Event('change'));
  fixture.detectChanges();
};

describe('AgentSetupPanel', () => {
  it('reads the setup when it opens, and renders what came back', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.setup.reads).toEqual([{ id: 'a-atlas', force: false }]);
    expect(el(fixture).textContent).toContain('/opt/data/profiles/a-atlas/.env');
    expect(el(fixture).textContent).toContain('ANTHROPIC_API_KEY');
    expect(el(fixture).textContent).toContain('Nous Portal');
  });

  it('asks for a forced re-read only when refresh is pressed', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();

    buttonWith(fixture, 'refresh').click();
    await fixture.whenStable();

    expect(store.setup.reads).toEqual([
      { id: 'a-atlas', force: false },
      { id: 'a-atlas', force: true },
    ]);
  });

  it('reads a different profile when the tab switches to one', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();

    fixture.componentInstance.agent.set(profile('a-scribe', 'scribe'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.setup.reads.map(r => r.id)).toEqual(['a-atlas', 'a-scribe']);
    expect(el(fixture).textContent).toContain('/opt/data/profiles/a-scribe/.env');
  });

  it('sends a typed key, then clears the field and shows what the store answered', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    const input = el(fixture).querySelector<HTMLInputElement>('.key-in')!;
    input.value = ' sk-ant-typed ';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    buttonWith(fixture, 'set').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.setup.setEnv).toHaveBeenCalledWith(
      'a-atlas', [{ key: 'ANTHROPIC_API_KEY', value: 'sk-ant-typed' }]);
    expect(el(fixture).querySelector<HTMLInputElement>('.key-in')!.value).toBe('');
    expect(el(fixture).textContent).toContain('…here');
  });

  it('refuses to send an empty value as a set', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(buttonWith(fixture, 'set').disabled).toBe(true);
    buttonWith(fixture, 'set').click();
    expect(store.setup.setEnv).not.toHaveBeenCalled();
  });

  it('clears a key that is already set, without typing anything', async () => {
    const store = storeStub();
    store.cache.set({ 'a-atlas': setupFor('a-atlas', {
      apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: true, masked: '…9f2c', problem: null }],
    }) });
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    buttonWith(fixture, 'clear').click();

    expect(store.setup.setEnv).toHaveBeenCalledWith(
      'a-atlas', [{ key: 'ANTHROPIC_API_KEY', value: null }]);
  });

  it('flags a key whose pooled credential hermes remembers as failing, even once it is unset', async () => {
    // the pool outlives the .env line: this key is not set anywhere the operator can see, and
    // is still what every turn dies on
    const store = storeStub();
    store.cache.set({ 'a-atlas': setupFor('a-atlas', {
      apiKeys: [{
        label: 'OpenAI', envVar: 'OPENAI_API_KEY', set: false, masked: null,
        problem: 'auth failed invalid_api_key (401) (re-auth may be required)',
      }],
    }) });
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('auth failed invalid_api_key (401)');
    expect(el(fixture).querySelector('mc-status .dot.warn')).toBeTruthy();
  });

  it('offers to create a missing .env, and nothing to configure until it exists', async () => {
    const store = storeStub();
    store.cache.set({ 'a-atlas': setupFor('a-atlas', { envExists: false }) });
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('.env missing');
    buttonWith(fixture, 'create .env template').click();
    await fixture.whenStable();

    expect(store.setup.initEnv).toHaveBeenCalledWith('a-atlas');
  });

  it('expands one messaging platform at a time', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).querySelector('.msg-config')).toBeNull();
    buttonWith(fixture, 'configure').click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('TELEGRAM_HOME_CHANNEL');

    buttonWith(fixture, 'close').click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('.msg-config')).toBeNull();
  });

  // ── the credential picker ────────────────────────────────────────────────

  it('offers no picker at all while the library holds nothing for that variable', async () => {
    // the feature stays invisible until it has something to offer
    const store = storeStub();
    store.saved.set([credential('cr-1', 'openai', ['OPENAI_API_KEY'])]);
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(picker(fixture, 'ANTHROPIC_API_KEY')).toBeNull();
  });

  it('offers the credentials holding that row\'s variable', async () => {
    const store = storeStub();
    store.saved.set([
      credential('cr-1', 'anthropic prod', ['ANTHROPIC_API_KEY']),
      credential('cr-2', 'openai', ['OPENAI_API_KEY']),
    ]);
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    const options = Array.from(picker(fixture, 'ANTHROPIC_API_KEY')!.options)
      .map(o => o.textContent?.trim());
    expect(options).toEqual(['saved…', 'anthropic prod']);
  });

  it('posts the credential id and no value, so the browser never holds the key', async () => {
    const store = storeStub();
    store.saved.set([credential('cr-1', 'anthropic prod', ['ANTHROPIC_API_KEY'])]);
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    pick(fixture, 'ANTHROPIC_API_KEY', 'cr-1');
    buttonWith(fixture, 'use').click();
    await fixture.whenStable();

    expect(store.setup.setEnv).toHaveBeenCalledWith('a-atlas', [
      { key: 'ANTHROPIC_API_KEY', value: null, credentialId: 'cr-1' },
    ]);
  });

  it('replaces the value box while a credential is picked, so only one can be sent', async () => {
    const store = storeStub();
    store.saved.set([credential('cr-1', 'anthropic prod', ['ANTHROPIC_API_KEY'])]);
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).querySelector('.key-in')).not.toBeNull();

    pick(fixture, 'ANTHROPIC_API_KEY', 'cr-1');

    expect(el(fixture).querySelector('.key-in')).toBeNull();
    expect(buttonWith(fixture, 'use')).not.toBeNull();
  });

  it('fills every variable the same credential covers, because it is a bundle', async () => {
    // picking "Telegram ops" on the token row is also the answer for the home channel
    const store = storeStub();
    store.saved.set([
      credential('cr-1', 'telegram ops', ['TELEGRAM_BOT_TOKEN', 'TELEGRAM_HOME_CHANNEL']),
    ]);
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();
    buttonWith(fixture, 'configure').click();
    fixture.detectChanges();

    pick(fixture, 'TELEGRAM_BOT_TOKEN', 'cr-1');

    expect(picker(fixture, 'TELEGRAM_HOME_CHANNEL')!.value).toBe('cr-1');
  });

  it('lets the picker be unset again, and types a value instead', async () => {
    const store = storeStub();
    store.saved.set([credential('cr-1', 'anthropic prod', ['ANTHROPIC_API_KEY'])]);
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    pick(fixture, 'ANTHROPIC_API_KEY', 'cr-1');
    pick(fixture, 'ANTHROPIC_API_KEY', '');

    expect(el(fixture).querySelector('.key-in')).not.toBeNull();
  });

  it('forgets the picks when the tab switches profile', async () => {
    // a pick belongs to the profile it was made on; carrying it over would apply a key to
    // an agent nobody chose it for
    const store = storeStub();
    store.saved.set([credential('cr-1', 'anthropic prod', ['ANTHROPIC_API_KEY'])]);
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();
    pick(fixture, 'ANTHROPIC_API_KEY', 'cr-1');

    fixture.componentInstance.agent.set(profile('a-scribe', 'scribe'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(picker(fixture, 'ANTHROPIC_API_KEY')!.value).toBe('');
  });

  it('says the read is running rather than claiming the setup is empty', () => {
    const store = storeStub();
    store.loading.set(new Set(['a-atlas']));
    store.setup.setup = vi.fn(() => new Promise(() => {}));
    const fixture = render(store);

    expect(el(fixture).textContent).toContain('running hermes status…');
    expect(buttonWith(fixture, 'refresh').disabled).toBe(true);
  });
});
