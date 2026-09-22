import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import {
  AuthProvider, Credential, HermesContainer, LlmProvider, InferenceEndpoint, NewAgent,
  ProfileTemplate,
} from '../core/models';
import { AgentCreateDialog } from './agent-create-dialog';
import { TestFixture, choose, el, field, fill, settle, text } from '../testing/dom';
import { container as buildContainer, template as buildTemplate } from '../testing/models';
import { provideStores } from '../testing/store';

const llm: LlmProvider[] = [
  { key: 'nous', label: 'Nous Portal', needsKey: false, oauth: true, hasCatalog: true, envVar: null },
  { key: 'openai-codex', label: 'OpenAI Codex (ChatGPT login)', needsKey: false, oauth: true, hasCatalog: false, envVar: null },
  { key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true,
    envVar: 'ANTHROPIC_API_KEY' },
  { key: 'custom', label: 'Custom endpoint', needsKey: true, oauth: false, hasCatalog: false,
    envVar: 'CUSTOM_API_KEY' },
];

const ollama: InferenceEndpoint[] = [{
  id: 'mp-1', name: 'workstation', url: 'http://10.0.0.5:11434', kind: 'ollama',
  status: 'connected', version: null, detail: null, canManageModels: true,
}];

const container: HermesContainer = buildContainer('c-1', { name: 'hermes-prod', shortId: 'c1' });

const template = (patch: Partial<ProfileTemplate> = {}): ProfileTemplate =>
  buildTemplate('t-1', { name: 'researcher', model: 'claude-opus-5', ...patch });

/** Only what the dialog reaches for on the store, so nothing here touches a backend. */
const storeStub = (opts: {
  templates?: ProfileTemplate[];
  auth?: AuthProvider[];
  catalog?: string[];
  credentials?: Credential[];
} = {}) => {
  const templates = opts.templates ?? [];
  return {
    credentials: {
      providing: (envVar: string) =>
        (opts.credentials ?? []).filter(c => c.entries.some(e => e.key === envVar)),
    },
    providers: {
      llmProviders: signal(llm),
      modelCatalog: vi.fn().mockResolvedValue(
        { models: opts.catalog ?? ['claude-opus-5', 'claude-sonnet-5'], source: 'catalog' }),
      modelCatalogLive: vi.fn().mockResolvedValue({ models: ['live-model'], source: 'live' }),
    },
    endpoints: {
      endpoints: signal(ollama),
      models: vi.fn().mockResolvedValue([{ name: 'gemma3:4b' }]),
    },
    templates: {
      templates: signal(templates),
      byId: (id: string) => templates.find(t => t.id === id) ?? null,
    },
    agents: {
      forSelectedContainer: signal([]),
      create: vi.fn().mockResolvedValue('a-new'),
    },
    setup: { authProviders: vi.fn().mockResolvedValue(opts.auth ?? []) },
  };
};

@Component({
  imports: [AgentCreateDialog],
  template: `<mc-agent-create-dialog [container]="container"
                                     (created)="createdId = $event"
                                     (closed)="closes = closes + 1" />`,
})
class Host {
  container = container;
  createdId: string | null = null;
  closes = 0;
}

const render = async (store: ReturnType<typeof storeStub>, ctr: HermesContainer = container) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.container = ctr;
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, store, host: fixture.componentInstance };
};

const toggleAux = async (fixture: TestFixture): Promise<void> => {
  const box = el(fixture).querySelector<HTMLInputElement>('.check input[type=checkbox]')!;
  box.click();
  await settle(fixture);
};

const submit = async (fixture: TestFixture): Promise<void> => {
  el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.primary')!.click();
  await settle(fixture);
};

const submitButton = (fixture: TestFixture): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.primary')!;

/** The NewAgent the dialog assembled and handed to the store. */
const sent = (store: ReturnType<typeof storeStub>): NewAgent =>
  store.agents.create.mock.calls[0][0] as NewAgent;

describe('AgentCreateDialog opening', () => {
  it('does not ask a stopped container for its auth status — it cannot answer', async () => {
    const { store } = await render(storeStub(), { ...container, status: 'stopped' });

    expect(store.setup.authProviders).not.toHaveBeenCalled();
  });

  it('loads the default provider\'s catalog and this container\'s auth status', async () => {
    const { fixture, store } = await render(storeStub());

    expect(store.providers.modelCatalog).toHaveBeenCalledWith('nous');
    expect(store.setup.authProviders).toHaveBeenCalledWith('c-1');
    expect(el(fixture).textContent).toContain('NEW AGENT PROFILE — hermes-prod');
    expect(field(fixture, 'model').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('claude-opus-5');
  });

  it('warns when Nous Portal has not been logged in on this container', async () => {
    const { fixture } = await render(storeStub({
      auth: [{ label: 'Nous Portal', ok: false, status: 'not logged in', hint: 'hermes portal', providerKey: 'nous' }] as AuthProvider[],
    }));

    expect(el(fixture).textContent).toContain('Nous Portal not logged in');
  });

  it('confirms the login rather than asking for a key it does not need', async () => {
    const { fixture } = await render(storeStub({
      auth: [{ label: 'Nous Portal', ok: true, status: 'ok', hint: null, providerKey: 'nous' }] as AuthProvider[],
    }));

    expect(el(fixture).textContent).toContain('Nous Portal connected on this container');
    expect(() => field(fixture, 'API key')).toThrow();
  });
});

const codexLogin = (ok: boolean): AuthProvider => ({
  label: 'OpenAI Codex', ok, status: ok ? 'logged in' : 'not logged in',
  hint: ok ? null : 'hermes auth add openai-codex', providerKey: 'openai-codex',
});

const providerValues = (fixture: TestFixture): string[] =>
  Array.from(field(fixture, 'provider').querySelectorAll('option')).map(o => o.value);

describe('AgentCreateDialog provider choice', () => {
  it('offers a device-flow OAuth provider only once this container reports its login', async () => {
    // the login is a device flow the dashboard cannot drive, so a row offered on a container
    // that never ran it would build an agent that cannot answer
    const { fixture: unseen } = await render(storeStub());
    expect(providerValues(unseen)).not.toContain('openai-codex');

    const { fixture: refused } = await render(storeStub({ auth: [codexLogin(false)] }));
    expect(providerValues(refused)).not.toContain('openai-codex');

    const { fixture: logged } = await render(storeStub({ auth: [codexLogin(true)] }));
    expect(providerValues(logged)).toContain('openai-codex');
    // the default account keeps its row either way — the form opens on it and warns
    expect(providerValues(unseen)).toContain('nous');
  });

  it('points a profile at the container\'s Codex login without a key or a catalog', async () => {
    const { fixture, store } = await render(storeStub({ auth: [codexLogin(true)] }));

    await choose(fixture, 'provider', 'openai-codex');
    expect(text(fixture)).toContain('OpenAI Codex connected on this container');
    expect(() => field(fixture, 'API key')).toThrow();

    await fill(fixture, 'profile name', 'trader03');
    await fill(fixture, 'model', 'gpt-5.5');   // no catalog: the field is free text
    await submit(fixture);

    expect(sent(store)).toMatchObject({ provider: 'openai-codex', model: 'gpt-5.5', apiKey: '' });
  });

  it('asks for a key only for a provider that needs one', async () => {
    const { fixture } = await render(storeStub());
    expect(() => field(fixture, 'API key')).toThrow();

    await choose(fixture, 'provider', 'anthropic');
    expect(field(fixture, 'API key')).toBeTruthy();

    await choose(fixture, 'provider', 'ollama: workstation');
    expect(() => field(fixture, 'API key')).toThrow();
  });

  it('re-selects the model from the new provider\'s catalog on a switch', async () => {
    const { fixture, store } = await render(storeStub());

    await choose(fixture, 'provider', 'ollama: workstation');

    expect(store.endpoints.models).toHaveBeenCalledWith('mp-1');
    expect(field(fixture, 'model').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('gemma3:4b');
  });

  it('offers the whole catalog as a dropdown, not a datalist the current value filters', async () => {
    // the first suggestion is preselected, and a browser filters a datalist by what the input
    // holds — which showed one model and read as the whole catalog
    const { fixture } = await render(storeStub());

    const options = Array.from(field(fixture, 'model').querySelectorAll('option')).map(o => o.value);

    expect(options).toEqual(['claude-opus-5', 'claude-sonnet-5', '']);
    expect(field(fixture, 'model').querySelector('.input')).toBeNull();
  });

  it('offers no suggestions for a provider with no catalog, keeping the field free text', async () => {
    const { fixture, store } = await render(storeStub());

    await choose(fixture, 'provider', 'custom');

    expect(store.providers.modelCatalog).not.toHaveBeenCalledWith('custom');
    expect(field(fixture, 'model').querySelector('.select')).toBeNull();
    expect(field(fixture, 'model').querySelector('.input')).toBeTruthy();
  });

  it('fetches the live catalog once a key is typed for a catalog-backed provider', async () => {
    const { fixture, store } = await render(storeStub());
    await choose(fixture, 'provider', 'anthropic');

    await fill(fixture, 'API key', 'sk-test');
    field(fixture, 'API key').querySelector<HTMLButtonElement>('.key-refresh')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.providers.modelCatalogLive).toHaveBeenCalledWith('anthropic', { apiKey: 'sk-test' });
  });

  it('fetches the live catalog through a saved credential the moment one is picked', async () => {
    // this page never holds that key; the server reads it under the provider's own variable
    const { fixture, store } = await render(storeStub({ credentials: [{
      id: 'cred-1', name: 'anthropic prod', description: '', createdAt: 0, updatedAt: 0,
      entries: [{ key: 'ANTHROPIC_API_KEY', value: '', secret: true, set: true, recoverable: true }],
    }] }));
    await choose(fixture, 'provider', 'anthropic');

    const saved = field(fixture, 'API key').querySelector<HTMLSelectElement>('.select')!;
    saved.value = 'cred-1';
    saved.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(store.providers.modelCatalogLive)
      .toHaveBeenCalledWith('anthropic', { credentialId: 'cred-1' });
    expect(field(fixture, 'model').querySelector<HTMLSelectElement>('.select')!.value).toBe('live-model');
  });
});

describe('AgentCreateDialog template prefill', () => {
  it('prefills the provider and model the template names', async () => {
    const { fixture } = await render(storeStub({ templates: [template()] }));

    await choose(fixture, 'from profile', 't-1');

    expect(field(fixture, 'provider').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('anthropic');
    expect(field(fixture, 'model').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('claude-opus-5');
  });

  it('still asks for a key when the template only records the name of one', async () => {
    const { fixture } = await render(storeStub({
      templates: [template({ secrets: [{ key: 'ANTHROPIC_API_KEY', set: false, recoverable: false }] })],
    }));

    await choose(fixture, 'from profile', 't-1');

    expect(field(fixture, 'API key')).toBeTruthy();
  });

  it('skips the key prompt when the template carries a usable one', async () => {
    const { fixture } = await render(storeStub({
      templates: [template({ secrets: [{ key: 'ANTHROPIC_API_KEY', set: true, recoverable: true }] })],
    }));

    await choose(fixture, 'from profile', 't-1');

    expect(() => field(fixture, 'API key')).toThrow();
  });
});

describe('AgentCreateDialog auxiliary override', () => {
  it('starts the override on the main provider, and asks for no second key', async () => {
    const { fixture } = await render(storeStub());
    await choose(fixture, 'provider', 'anthropic');
    await fill(fixture, 'API key', 'sk-test');

    await toggleAux(fixture);

    expect(field(fixture, 'auxiliary provider').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('anthropic');
    expect(() => field(fixture, 'auxiliary API key')).toThrow();
  });

  it('sends a same-provider override as a model on its own', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary model', 'claude-sonnet-5');

    await submit(fixture);

    expect(sent(store).auxiliary).toEqual({ model: 'claude-sonnet-5' });
  });

  it('sends its own provider, endpoint and key when the override switches provider', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary provider', 'anthropic');
    await choose(fixture, 'auxiliary model', 'claude-sonnet-5');
    await fill(fixture, 'auxiliary API key', 'sk-aux');

    await submit(fixture);

    expect(sent(store).auxiliary).toEqual({
      provider: 'anthropic', model: 'claude-sonnet-5', baseUrl: undefined, apiKey: 'sk-aux',
    });
  });

  it('reads the override\'s provider live once its own key is typed', async () => {
    const { fixture, store } = await render(storeStub());
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary provider', 'anthropic');
    await fill(fixture, 'auxiliary API key', 'sk-aux');

    field(fixture, 'auxiliary API key').querySelector('.input')!.dispatchEvent(new Event('blur'));
    await settle(fixture);

    expect(store.providers.modelCatalogLive).toHaveBeenCalledWith('anthropic', { apiKey: 'sk-aux' });
    expect(field(fixture, 'auxiliary model').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('live-model');
  });

  it('refuses an override that names no model', async () => {
    const { fixture } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary model', '');

    expect(submitButton(fixture).disabled).toBe(true);
  });

  it('forgets the override\'s fields when it is switched back off', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary model', 'claude-sonnet-5');
    await toggleAux(fixture);

    await submit(fixture);

    expect(sent(store).auxiliary).toBeUndefined();
  });
});

describe('AgentCreateDialog create', () => {
  it('sends the slug the CLI would accept, and reports the new profile', async () => {
    const { fixture, store, host } = await render(storeStub());

    await fill(fixture, 'profile name', '  Ops Bot  ');
    await submit(fixture);

    expect(store.agents.create).toHaveBeenCalledWith({
      containerId: 'c-1', name: 'ops-bot', provider: 'nous', model: 'claude-opus-5', apiKey: '',
      cloneFrom: undefined, baseUrl: undefined, fromTemplate: undefined, auxiliary: undefined,
    });
    expect(host.createdId).toBe('a-new');
    expect(host.closes).toBe(0);
  });

  it('sends the bare provider and endpoint an ollama option resolves to', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await choose(fixture, 'provider', 'ollama: workstation');

    await submit(fixture);

    expect(sent(store)).toMatchObject({
      provider: 'ollama', model: 'gemma3:4b', apiKey: '',
      baseUrl: 'http://10.0.0.5:11434/v1',
    });
  });

  it('refuses to create with no name, and refuses a needed key that is blank', async () => {
    const { fixture } = await render(storeStub());
    expect(submitButton(fixture).disabled).toBe(true);

    await fill(fixture, 'profile name', 'ops-bot');
    expect(submitButton(fixture).disabled).toBe(false);

    await choose(fixture, 'provider', 'anthropic');
    expect(submitButton(fixture).disabled).toBe(true);
  });

  it('stays open with the form intact when the backend refuses the create', async () => {
    // it used to close, which threw away a provider key the operator had just typed
    const store = storeStub();
    store.agents.create.mockResolvedValue('');
    const { fixture, host } = await render(store);

    await fill(fixture, 'profile name', 'ops-bot');
    await submit(fixture);

    expect(host.createdId).toBeNull();
    expect(host.closes).toBe(0);
    expect(text(fixture)).toContain('nothing was created');
    expect(field(fixture, 'profile name').querySelector<HTMLInputElement>('.input')!.value)
      .toBe('ops-bot');
    expect(submitButton(fixture).disabled).toBe(false);
  });

  it('says the create runs on without the dialog, and lets it be closed mid-flight', async () => {
    const store = storeStub();
    store.agents.create.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture, host } = await render(store);

    await fill(fixture, 'profile name', 'ops-bot');
    await submit(fixture);

    expect(submitButton(fixture).textContent?.trim()).toBe('creating…');
    expect(text(fixture)).toContain('close it and keep working');

    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();
    expect(host.closes).toBe(1);
  });

  it('will not send a second create while the first is in flight', async () => {
    const store = storeStub();
    store.agents.create.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture } = await render(store);

    await fill(fixture, 'profile name', 'ops-bot');
    await submit(fixture);
    await submit(fixture);

    expect(store.agents.create).toHaveBeenCalledTimes(1);
  });

  it('reports a cancel to the page rather than closing itself', async () => {
    const { fixture, store, host } = await render(storeStub());

    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();

    expect(host.closes).toBe(1);
    expect(store.agents.create).not.toHaveBeenCalled();
  });
});

describe('AgentCreateDialog templates', () => {
  /** Picks a blueprint in the "from template" select. */
  const pickTemplate = async (fixture: TestFixture, id: string): Promise<void> => {
    const select = Array.from(el(fixture).querySelectorAll<HTMLSelectElement>('.select'))
      .find(s => Array.from(s.options).some(o => o.value === id));
    if (!select) throw new Error(`no picker offering "${id}"`);
    select.value = id;
    select.dispatchEvent(new Event('change'));
    await settle(fixture);
  };

  it('adopts the blueprint\'s provider and its model', async () => {
    const store = storeStub({
      templates: [template({ provider: 'nous', model: 'Hermes-4-405B' })],
      catalog: ['Hermes-4-70B', 'Hermes-4-405B'],
    });
    const { fixture } = await render(store);

    await pickTemplate(fixture, 't-1');
    await fill(fixture, 'profile name', 'ops-bot');
    await submit(fixture);

    expect(sent(store)).toMatchObject({ provider: 'nous', model: 'Hermes-4-405B' });
  });

  it('keeps a blueprint\'s model even when its provider is no longer registered', async () => {
    const store = storeStub({ templates: [template({ provider: 'retired-vendor' })] });
    const { fixture } = await render(store);

    await pickTemplate(fixture, 't-1');
    await fill(fixture, 'profile name', 'ops-bot');
    await submit(fixture);

    expect(sent(store)).toMatchObject({
      containerId: 'c-1', name: 'ops-bot', model: 'claude-opus-5', fromTemplate: 't-1',
    });
  });

  it('sends the blueprint id so the backend seeds the profile from it', async () => {
    const store = storeStub({ templates: [template({ provider: 'nous' })] });
    const { fixture } = await render(store);

    await pickTemplate(fixture, 't-1');
    await fill(fixture, 'profile name', 'ops-bot');
    await submit(fixture);

    expect(sent(store).fromTemplate).toBe('t-1');
  });
});

describe('AgentCreateDialog auxiliary on a self-hosted model', () => {
  const OLLAMA = 'ollama: workstation';

  it('needs no second key for an ollama instance, whatever the main provider is', async () => {
    const { fixture } = await render(storeStub());
    await choose(fixture, 'provider', 'anthropic');
    await fill(fixture, 'API key', 'sk-test');
    await toggleAux(fixture);

    await choose(fixture, 'auxiliary provider', OLLAMA);

    expect(() => field(fixture, 'auxiliary API key')).toThrow();
  });

  it('sends the instance\'s own endpoint with the override', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary provider', OLLAMA);
    await choose(fixture, 'auxiliary model', 'gemma3:4b');

    await submit(fixture);

    expect(sent(store).auxiliary).toEqual({
      provider: 'ollama', model: 'gemma3:4b',
      // the OpenAI-compatible endpoint, which is what hermes talks to
      baseUrl: 'http://10.0.0.5:11434/v1', apiKey: undefined,
    });
  });

  it('suggests the models that instance actually has installed', async () => {
    const { fixture, store } = await render(storeStub());
    await toggleAux(fixture);

    await choose(fixture, 'auxiliary provider', OLLAMA);

    expect(store.endpoints.models).toHaveBeenCalledWith('mp-1');
  });

  it('refuses to create against an instance that has since disappeared', async () => {
    const store = storeStub();
    const { fixture } = await render(store);
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary provider', OLLAMA);
    await choose(fixture, 'auxiliary model', 'gemma3:4b');

    store.endpoints.endpoints.set([]);
    await submit(fixture);

    expect(store.agents.create).not.toHaveBeenCalled();
  });
});
