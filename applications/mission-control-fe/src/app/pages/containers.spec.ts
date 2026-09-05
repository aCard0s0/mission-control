import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DockerHost, HermesContainer, ImageCatalog, ImageTag, ProfileTemplate,
} from '../core/models';
import { HERMES_BASELINE } from '../core/container-resources';
import { NO_HOST_ACCESS } from '../core/host-access';
import { ContainersPage, normalizeSeedProfiles } from './containers';
import {
  TestFixture, button, buttonWith, choose, el, field, fill, press, settle, stubConfirm, text, type,
} from '../testing/dom';
import { container, dockerHost, template as buildTemplate } from '../testing/models';
import { ApiContainerActivity } from '../core/hermes-api';
import { provideStores } from '../testing/store';

describe('normalizeSeedProfiles', () => {
  it('normalizes, deduplicates, and omits the implicit default profile', () => {
    expect(normalizeSeedProfiles(' Default, Ops, research team, ops '))
      .toEqual(['ops', 'research-team']);
  });
});

// The tag-comparison rules these tests used to cover live in core/image-policy.spec.ts now.
// What is left below is the page: which modal opens, what it renders, and what it calls.
const HERMES = 'nousresearch/hermes-agent';

type TagSpec = string | (Partial<ImageTag> & { tag: string });

const HOSTS: DockerHost[] = [
  dockerHost('dh-local', {
    name: 'localhost', url: 'unix:///var/run/docker.sock', kind: 'local',
    engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 4,
  }),
  dockerHost('dh-edge', {
    name: 'edge', url: 'tcp://10.0.0.5:2376', status: 'error', note: 'unreachable',
  }),
];

/** Only what the page and its three modals reach for on the store. */
const storeStub = (containers: HermesContainer[], catalogs: Record<string, ImageCatalog> = {}) => {
  const dockerHosts = signal(HOSTS);
  return {
    containers: {
      containers: signal(containers),
      selectedContainerId: signal(containers[0]?.id ?? ''),
      select: vi.fn(),
    },
    hosts: {
      hosts: dockerHosts,
      byId: (id: string) => dockerHosts().find(h => h.id === id) ?? null,
      add: vi.fn(),
      remove: vi.fn(),
      check: vi.fn(),
    },
    agents: { agents: signal([{ id: 'a-1', containerId: 'hermes-prod' }]) },
    ctx: { backendStatus: signal('connected') },
    images: {
      catalog: signal(catalogs),
      refreshAll: vi.fn().mockResolvedValue(undefined),
      tags: vi.fn().mockResolvedValue({ repository: HERMES, tags: ['latest', 'v2026.8.3'] }),
    },
    lifecycle: {
      setStatus: vi.fn(),
      deploy: vi.fn().mockResolvedValue('c-new'),
      update: vi.fn().mockResolvedValue('c-updated'),
      remove: vi.fn().mockResolvedValue(true),
    },
    terminal: { open: vi.fn() },
    templates: { templates: signal<ProfileTemplate[]>([]) },
  };
};

const render = (store: ReturnType<typeof storeStub>) => {
  const router = { navigate: vi.fn() };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: Router, useValue: router },
      ...provideStores(store),
    ],
  });
  const fixture = TestBed.createComponent(ContainersPage);
  fixture.detectChanges();
  return { fixture, store, router };
};

/** Opens the deploy modal and lets the image-tag read land. */
const openDeploy = async (fixture: TestFixture): Promise<void> => {
  press(fixture, '+ deploy container');
  await settle(fixture);
};

describe('ContainersPage fleet', () => {

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the image catalogs on arrival, so update badges are not a poll behind', () => {
    const { store } = render(storeStub([container('hermes-prod')]));

    expect(store.images.refreshAll).toHaveBeenCalled();
  });

  it('names the host a container runs on, and falls back when that host is gone', () => {
    const { fixture } = render(storeStub([
      container('hermes-prod'), container('hermes-orphan', { hostId: 'dh-deleted' }),
    ]));

    expect(text(fixture)).toContain('on localhost');
    expect(text(fixture)).toContain('on ?');
  });

  it('counts the profiles inside each container', () => {
    const { fixture } = render(storeStub([container('hermes-prod'), container('hermes-lab')]));

    const counts = Array.from(el(fixture).querySelectorAll('.stats > div'))
      .filter(d => (d.textContent ?? '').includes('profiles'))
      .map(d => (d.textContent ?? '').replace('profiles', '').trim());
    expect(counts).toEqual(['1', '0']);
  });

  it('blames the backend for an empty fleet while it is unreachable', () => {
    const waiting = storeStub([]);
    waiting.ctx.backendStatus.set('unreachable');

    const { fixture } = render(waiting);

    expect(text(fixture)).toContain('waiting for the Mission Control backend');
  });

  it('says the connected hosts simply have none, once the backend answers', () => {
    const { fixture } = render(storeStub([]));

    expect(text(fixture)).toContain('No Hermes containers detected');
  });

  it('opens a container from the keyboard, on Enter and on Space', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));
    const card = el(fixture).querySelector<HTMLElement>('.card')!;

    card.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(store.containers.select).toHaveBeenCalledWith('hermes-prod');

    store.containers.select.mockClear();
    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    card.dispatchEvent(space);

    // a real button fires on Space too, and does not scroll the page doing it
    expect(store.containers.select).toHaveBeenCalledWith('hermes-prod');
    expect(space.defaultPrevented).toBe(true);
  });

  it('selects a container from a click on the card and leaves the page for its overview', () => {
    const { fixture, store, router } = render(storeStub([container('hermes-prod')]));
    const card = el(fixture).querySelector<HTMLElement>('.card')!;

    card.click();
    fixture.detectChanges();

    expect(store.containers.select).toHaveBeenCalledWith('hermes-prod');
    expect(router.navigate).toHaveBeenCalledWith(['/overview']);
    // the card is the control; a separate select button would only duplicate it
    expect(Array.from(card.querySelectorAll('button'))
      .map(b => (b.textContent ?? '').trim())).not.toContain('select');
  });

  it('offers start for a stopped container, and shows no telemetry for it', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod', { status: 'stopped' })]));

    expect(text(fixture)).toContain('no telemetry — stopped');
    press(fixture, 'start', '.card');

    expect(store.lifecycle.setStatus).toHaveBeenCalledWith('hermes-prod', 'running');
  });

  it('offers stop for a running one', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));

    press(fixture, 'stop', '.card');

    expect(store.lifecycle.setStatus).toHaveBeenCalledWith('hermes-prod', 'stopped');
  });

  it('opens a terminal on the container without running anything in it', () => {
    const { fixture, store, router } = render(storeStub([container('hermes-prod')]));
    const term = el(fixture).querySelector<HTMLButtonElement>('.card .term')!;

    // an icon-only control still has to say what it does
    expect(term.getAttribute('aria-label')).toBe('open a terminal in hermes-prod');
    term.click();
    fixture.detectChanges();

    expect(store.terminal.open).toHaveBeenCalledWith({
      hostId: 'dh-local', containerId: 'hermes-prod', label: 'hermes-prod',
    });
    // the operator asked for a prompt, not for something to be run at it
    expect(store.terminal.open.mock.calls[0][0]).not.toHaveProperty('command');
    // the shell is not a reason to leave the page
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('says why a stopped container has no shell to open', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod', { status: 'stopped' })]));
    const term = el(fixture).querySelector<HTMLButtonElement>('.card .term')!;

    expect(term.disabled).toBe(true);
    expect(term.title).toContain('start it to open a shell');

    term.click();
    fixture.detectChanges();

    expect(store.terminal.open).not.toHaveBeenCalled();
  });
});

describe('ContainersPage docker hosts', () => {

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('lists every daemon with its engine and why it is unreachable', () => {
    const { fixture } = render(storeStub([]));

    expect(text(fixture)).toContain('localhost');
    expect(text(fixture)).toContain('Docker 27.3 · api 1.47');
    expect(text(fixture)).toContain('— unreachable');
  });

  it('re-checks a daemon on demand, and removes only a remote one', () => {
    const { fixture, store } = render(storeStub([]));

    press(fixture, 'check', '.host-row');
    expect(store.hosts.check).toHaveBeenCalledWith('dh-local');

    const rows = el(fixture).querySelectorAll('.host-row');
    expect(rows[0].textContent).not.toContain('delete');   // the local socket is not removable
    press(fixture, 'delete', rows[1]);
    expect(store.hosts.remove).toHaveBeenCalledWith('dh-edge');
  });

  it('only accepts a tcp URL carrying an explicit port', async () => {
    const { fixture, store } = render(storeStub([]));
    press(fixture, '+ remote host');

    const [name, url] = Array.from(el(fixture).querySelectorAll<HTMLInputElement>('.host-add .input'));
    const fillInput = async (input: HTMLInputElement, value: string) => {
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await settle(fixture);
    };

    await fillInput(name, 'edge-vm');
    await fillInput(url, 'http://10.0.0.5:2376');
    expect(button(fixture, 'connect').disabled).toBe(true);

    await fillInput(url, 'tcp://10.0.0.5');
    expect(button(fixture, 'connect').disabled).toBe(true);

    await fillInput(url, 'tcp://10.0.0.5:2376');
    press(fixture, 'connect');
    expect(store.hosts.add).toHaveBeenCalledWith('edge-vm', 'tcp://10.0.0.5:2376');
  });

  it('closes the form and resets it once the host is added', async () => {
    const { fixture } = render(storeStub([]));
    press(fixture, '+ remote host');
    await type(fixture, '.host-add .input', 'edge-vm');
    const url = el(fixture).querySelectorAll<HTMLInputElement>('.host-add .input')[1];
    url.value = 'tcp://10.0.0.5:2376';
    url.dispatchEvent(new Event('input'));
    await settle(fixture);

    press(fixture, 'connect');

    expect(el(fixture).querySelector('.host-add')).toBeNull();
  });
});

describe('ContainersPage deploy', () => {

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('cannot be opened without a connected daemon to deploy onto', () => {
    const offline = storeStub([]);
    offline.hosts.hosts.set([{ ...HOSTS[1] }]);
    const { fixture } = render(offline);

    expect(button(fixture, '+ deploy container').disabled).toBe(true);
  });

  it('offers only connected hosts and loads that host\'s tags', async () => {
    const { fixture, store } = render(storeStub([]));

    await openDeploy(fixture);

    expect(store.images.tags).toHaveBeenCalledWith('dh-local');
    const hosts = field(fixture, 'docker host').querySelectorAll('option');
    expect(Array.from(hosts).map(o => o.textContent?.trim()))
      .toEqual(['localhost — unix:///var/run/docker.sock']);
  });

  it('prefers latest when the host has it, so a deploy is not pinned by accident', async () => {
    const { fixture } = render(storeStub([]));

    await openDeploy(fixture);

    expect(field(fixture, 'image version')
      .querySelector<HTMLSelectElement>('.select')!.value).toBe('latest');
  });

  it('falls back to the newest tag on a host with no latest', async () => {
    const store = storeStub([]);
    store.images.tags.mockResolvedValue({ repository: HERMES, tags: ['v2026.8.3', 'v2026.7.20'] });
    const { fixture } = render(store);

    await openDeploy(fixture);

    expect(field(fixture, 'image version')
      .querySelector<HTMLSelectElement>('.select')!.value).toBe('v2026.8.3');
  });

  it('says why the version list is empty rather than showing a bare select', async () => {
    const store = storeStub([]);
    store.images.tags.mockRejectedValue(new Error('registry unreachable'));
    const { fixture } = render(store);

    await openDeploy(fixture);

    expect(text(fixture)).toContain('image tags unavailable — registry unreachable');
    expect(button(fixture, 'deploy').disabled).toBe(true);
  });

  it('deploys with normalized seed profiles and moves to the new container', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);
    await fill(fixture, 'container name', ' hermes-staging ');
    await fill(fixture, 'seed profiles', 'Ops, research team, ops');

    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy).toHaveBeenCalledWith(
      'hermes-staging', 'latest', ['ops', 'research-team'], 'dh-local', HERMES_BASELINE, NO_HOST_ACCESS, null);
    expect(store.containers.select).toHaveBeenCalledWith('c-new');
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('offers the blueprints for the default agent and sends the chosen one', async () => {
    const store = storeStub([]);
    store.templates.templates.set([
      buildTemplate('pt-coach', { name: 'coach', provider: 'openai-api', model: 'gpt-5.2' }),
    ]);
    const { fixture } = render(store);
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    const options = Array.from(field(fixture, 'default agent').querySelectorAll('option')).map(o => o.textContent?.trim());
    expect(options[0]).toContain('hermes defaults');
    expect(options[1]).toBe('coach · openai-api / gpt-5.2');

    await choose(fixture, 'default agent', 'pt-coach');
    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy).toHaveBeenCalledWith(
      'hermes-staging', 'latest', [], 'dh-local', HERMES_BASELINE, NO_HOST_ACCESS, 'pt-coach');
  });

  it('forgets the last blueprint when the modal is opened again', async () => {
    const store = storeStub([]);
    store.templates.templates.set([buildTemplate('pt-coach', { name: 'coach' })]);
    const { fixture } = render(store);
    await openDeploy(fixture);
    await choose(fixture, 'default agent', 'pt-coach');
    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();
    fixture.detectChanges();

    await openDeploy(fixture);

    expect(field(fixture, 'default agent').querySelector<HTMLSelectElement>('select')!.value).toBe('');
  });

  it('keeps the modal open, with the name intact, when the deploy fails', async () => {
    const store = storeStub([]);
    store.lifecycle.deploy.mockResolvedValue('');
    const { fixture } = render(store);
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    press(fixture, 'deploy');
    await settle(fixture);

    expect(el(fixture).querySelector('.modal')).not.toBeNull();
    expect(field(fixture, 'container name')
      .querySelector<HTMLInputElement>('.input')!.value).toBe('hermes-staging');
    expect(text(fixture)).toContain('no container was created');
  });

  it('can be closed while the deploy runs, which is minutes when the image is pulled', async () => {
    const store = storeStub([]);
    store.lifecycle.deploy.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture } = render(store);
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    press(fixture, 'deploy');
    await settle(fixture);

    expect(text(fixture)).toContain('close it and keep working');
    press(fixture, 'close');

    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('does not haul a closed-modal operator onto Overview when the deploy lands', async () => {
    const store = storeStub([]);
    let land = (_: string): void => { /* replaced below */ };
    store.lifecycle.deploy.mockReturnValue(new Promise(resolve => { land = resolve; }));
    const { fixture, router } = render(store);
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    press(fixture, 'deploy');
    await settle(fixture);
    press(fixture, 'close');

    land('c-new');
    await settle(fixture);

    // the container is still adopted as the active one — it is the routing that is not forced
    expect(store.containers.select).toHaveBeenCalledWith('c-new');
    expect(router.navigate).not.toHaveBeenCalledWith(['/overview']);
  });

  it('will not deploy without a name', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);

    expect(button(fixture, 'deploy').disabled).toBe(true);
    await fill(fixture, 'container name', '   ');
    expect(button(fixture, 'deploy').disabled).toBe(true);
    expect(store.lifecycle.deploy).not.toHaveBeenCalled();
  });

  it('will not deploy a name Docker would refuse, and says the rule instead of a 400 later', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);

    await fill(fixture, 'container name', 'Bad Name!');
    expect(button(fixture, 'deploy').disabled).toBe(true);
    expect(text(fixture)).toContain('starts with a letter or digit');

    await fill(fixture, 'container name', 'hermes-2.stage_b');
    expect(button(fixture, 'deploy').disabled).toBe(false);
    expect(text(fixture)).not.toContain('starts with a letter or digit');
    expect(store.lifecycle.deploy).not.toHaveBeenCalled();
  });

  it('ignores the tags of a host the operator has already switched away from', async () => {
    const store = storeStub([]);
    let landEdge!: (value: unknown) => void;
    store.images.tags.mockImplementation((hostId: string) => hostId === 'dh-edge'
      ? new Promise(resolve => { landEdge = resolve; })
      : Promise.resolve({ repository: HERMES, tags: ['latest'] }));
    store.hosts.hosts.set([HOSTS[0], { ...HOSTS[1], status: 'connected' }]);
    const { fixture } = render(store);
    await openDeploy(fixture);

    await choose(fixture, 'docker host', 'dh-edge');
    await choose(fixture, 'docker host', 'dh-local');
    landEdge({ repository: HERMES, tags: ['edge-only'] });
    await settle(fixture);

    const options = Array.from(field(fixture, 'image version').querySelectorAll('option'));
    expect(options.map(o => o.textContent?.trim())).toEqual(['latest']);
  });

  it('leaves the version unset on a host with no images at all', async () => {
    const store = storeStub([]);
    store.images.tags.mockResolvedValue({ repository: HERMES, tags: [] });
    const { fixture } = render(store);

    await openDeploy(fixture);

    expect(text(fixture)).toContain('No local Hermes images found on this host.');
    expect(button(fixture, 'deploy').disabled).toBe(true);
  });

  it('will not deploy onto a host that is no longer connected', async () => {
    const store = storeStub([]);
    const { fixture } = render(store);
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    store.hosts.hosts.set([{ ...HOSTS[0], status: 'error' }]);
    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy).not.toHaveBeenCalled();
  });

  it('empties the version list when the modal has no host to read tags from', async () => {
    const store = storeStub([]);
    store.hosts.hosts.set([]);
    const { fixture } = render(store);
    const page = fixture.componentInstance as unknown as { openDeploy(): void };

    page.openDeploy();
    await settle(fixture);

    expect(store.images.tags).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('No local Hermes images found on this host.');
  });
});

describe('ContainersPage image update', () => {
  const catalog = (tags: TagSpec[]): Record<string, ImageCatalog> => ({
    'dh-local': {
      repository: HERMES,
      tags: tags.map(t => typeof t === 'string'
        ? { tag: t, pulled: true, digest: null }
        : { pulled: true, digest: null, ...t }),
      registryStatus: 'ok',
      fetchedAt: 0,
    },
  });

  /** The card's update button — its label now carries the target tag, so match by prefix. */
  const pressUpdate = (fixture: TestFixture): void => {
    buttonWith(fixture, 'update', '.card').click();
    fixture.detectChanges();
  };

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('offers the update on a floating tag once the registry has moved it', () => {
    // the case the tag rules alone can never see: `latest` is always the newest tag,
    // so only the digests say the running image is two months old
    const stale = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([stale],
      catalog([{ tag: 'latest', digest: 'sha256:bbb' }])));

    // no release tag shares either digest here, so `latest` stays the only honest name
    const button = el(fixture).querySelector('.btn.upd')!;
    expect(button.textContent).toContain('update latest');
    expect(button.getAttribute('title'))
      .toContain('the registry published a new image on latest');
  });

  it('names both ends as releases, so a move on latest is not "latest → latest"', () => {
    // what the operator was actually asking about: the card said `:latest` and the
    // button said `update latest`, which reads as a no-op even though it is not
    const stale = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([stale], catalog([
      { tag: 'latest', digest: 'sha256:bbb' },
      { tag: 'v2026.8.3', digest: 'sha256:bbb' },
      { tag: 'v2026.7.20', digest: 'sha256:aaa' },
    ])));

    // the version it runs, not the pointer it followed there
    expect(text(fixture)).toContain('v2026.7.20');
    const button = el(fixture).querySelector('.btn.upd')!;
    expect(button.textContent).toContain('update v2026.8.3');
    expect(button.getAttribute('title')).toContain('v2026.7.20 → v2026.8.3');
    // and it still says which moving tag carries it there
    expect(button.getAttribute('title')).toContain('on latest');
  });

  it('still says which tag a resolved container follows', () => {
    const c = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([c],
      catalog([{ tag: 'v2026.7.20', digest: 'sha256:aaa' }])));

    // a container pinned to v2026.7.20 and one on latest that resolves to it behave
    // differently the next time they are recreated, so the pointer stays visible
    const meta = el(fixture).querySelector('.panel-b .meta')!;
    expect(meta.textContent).toContain('v2026.7.20');
    expect(meta.querySelector('.tracks')!.textContent!.trim()).toBe('latest');
  });

  it('offers nothing on a floating tag it already matches', () => {
    const current = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([current],
      catalog([{ tag: 'latest', digest: 'sha256:aaa' }])));

    expect(el(fixture).querySelector('.btn.upd')).toBeNull();
  });

  it('names the move on the update button, which is the only badge now', () => {
    const { fixture } = render(storeStub([container('hermes-prod')], catalog(['v2026.8.3'])));

    const button = el(fixture).querySelector('.btn.upd')!;
    expect(button.textContent).toContain('update v2026.8.3');
    expect(button.getAttribute('title')).toBe('v2026.7.20 → v2026.8.3');
  });

  it('warns on the button when the target is not on the host yet', () => {
    const { fixture } = render(storeStub(
      [container('hermes-prod')], catalog([{ tag: 'v2026.8.3', pulled: false }])));

    expect(el(fixture).querySelector('.btn.upd')!.getAttribute('title'))
      .toBe('v2026.7.20 → v2026.8.3 · not pulled on this host yet');
  });

  it('offers no update button for a container already on the newest tag', () => {
    const { fixture } = render(storeStub([container('hermes-prod', { version: 'v2026.8.3' })],
      catalog(['v2026.8.3'])));

    expect(el(fixture).querySelector('.btn.upd')).toBeNull();
  });

  it('opens on the newest release and lists every step in between', () => {
    const { fixture } = render(storeStub([container('hermes-prod')],
      catalog(['v2026.8.3', 'v2026.7.30'])));

    pressUpdate(fixture);

    const options = Array.from(field(fixture, 'target version').querySelectorAll('option'));
    // the tag it runs is always among them: a recreate on the same image is how host access is
    // added to an existing Agent, and it needs a way to be chosen
    expect(options.map(o => o.textContent?.trim()))
      .toEqual(['v2026.8.3', 'v2026.7.30', 'v2026.7.20 — current']);
    expect(text(fixture)).toContain('update to v2026.8.3');
  });

  it('warns that an unpulled target is fetched first', () => {
    const { fixture } = render(storeStub([container('hermes-prod')],
      catalog([{ tag: 'v2026.8.3', pulled: false }])));

    pressUpdate(fixture);

    expect(text(fixture)).toContain('the image is pulled first');
  });

  it('says a stopped container stays stopped', () => {
    const { fixture } = render(storeStub([container('hermes-prod', { status: 'stopped' })],
      catalog(['v2026.8.3'])));

    pressUpdate(fixture);

    expect(text(fixture)).toContain('this container is stopped — it stays stopped after the update');
  });

  it('recreates the container on the chosen tag and closes', async () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')],
      catalog(['v2026.8.3', 'v2026.7.30'])));
    pressUpdate(fixture);
    await choose(fixture, 'target version', 'v2026.7.30');

    press(fixture, 'update to v2026.7.30');
    await settle(fixture);

    expect(store.lifecycle.update).toHaveBeenCalledWith('hermes-prod', 'v2026.7.30', NO_HOST_ACCESS);
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('keeps the modal open when the recreate failed, so the reason stays on screen', async () => {
    const store = storeStub([container('hermes-prod')], catalog(['v2026.8.3']));
    store.lifecycle.update.mockResolvedValue('');
    const { fixture } = render(store);
    pressUpdate(fixture);

    press(fixture, 'update to v2026.8.3');
    await settle(fixture);

    expect(el(fixture).querySelector('.modal')).not.toBeNull();
  });

  it('locks the card and the modal while the recreate is in flight', async () => {
    const store = storeStub([container('hermes-prod')], catalog(['v2026.8.3']));
    let land!: (value: string) => void;
    store.lifecycle.update.mockReturnValue(new Promise<string>(r => { land = r; }));
    const { fixture } = render(store);
    pressUpdate(fixture);

    press(fixture, 'update to v2026.8.3');
    fixture.detectChanges();

    // a recreate takes long enough to look stalled, so it says what it is doing and spins
    expect(text(fixture)).toContain('recreating the container');
    expect(el(fixture).querySelectorAll('.modal .spin').length).toBeGreaterThan(0);
    press(fixture, 'cancel');                       // a cancel mid-flight must not close it
    expect(el(fixture).querySelector('.modal')).not.toBeNull();

    land('c-updated');
    await settle(fixture);
  });

  it('cancels back out without touching the container', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')], catalog(['v2026.8.3'])));
    pressUpdate(fixture);

    press(fixture, 'cancel');

    expect(el(fixture).querySelector('.modal')).toBeNull();
    expect(store.lifecycle.update).not.toHaveBeenCalled();
  });
});

describe('ContainersPage host access', () => {
  const published = (hostIp: string, hostPort = 9119) => [{ containerPort: 9119, hostIp, hostPort }];

  it('recreates a container on the image it runs, once host access is asked for', async () => {
    // no newer tag, so no update badge — and still the only way to publish a port on an Agent
    // deployed without one, which used to mean a hand-typed docker run
    const { fixture, store } = render(storeStub([container('hermes-prod')]));
    expect(el(fixture).querySelector('.btn.upd')).toBeNull();

    press(fixture, 'host access');

    const options = Array.from(field(fixture, 'target version').querySelectorAll('option'));
    expect(options.map(o => o.textContent?.trim())).toEqual(['v2026.7.20 — current']);
    // nothing asked yet: recreating for no reason would drop every Agent's session
    expect(button(fixture, 'recreate with host access').disabled).toBe(true);

    const chip = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.access .chip'))
      .find(c => (c.textContent ?? '').trim() === '+ webhook listener');
    chip!.click();
    fixture.detectChanges();
    press(fixture, 'recreate with host access');
    await settle(fixture);

    expect(store.lifecycle.update).toHaveBeenCalledWith('hermes-prod', 'v2026.7.20', {
      ports: [{ containerPort: 8644, hostPort: 8644, hostIp: '127.0.0.1' }], env: [], mounts: [],
    });
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('links to hermes\u2019 own dashboard once 9119 is published, where a browser can reach it', () => {
    const { fixture } = render(storeStub([
      container('hermes-prod', { published: published('0.0.0.0') }),
      container('hermes-edge', { hostId: 'dh-edge', published: published('0.0.0.0', 19119) }),
      container('hermes-loop', { published: published('127.0.0.1') }),
      container('hermes-plain', { published: [{ containerPort: 8644, hostIp: '127.0.0.1', hostPort: 8644 }] }),
      container('hermes-off', { status: 'stopped', published: published('0.0.0.0') }),
    ]));

    const links = Array.from(el(fixture).querySelectorAll<HTMLAnchorElement>('a.dash'))
      .map(a => a.getAttribute('href'));
    // the local socket is this machine, so the page's own host; a remote daemon is named by its
    // url; a loopback bind stays as bound, since only the docker host itself can reach it
    expect(links).toEqual([
      `http://${location.hostname}:9119`, 'http://10.0.0.5:19119', 'http://127.0.0.1:9119']);
  });
});

describe('ContainersPage stop', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  /** The store stub plus the two agent endpoints the stop gate reaches for. */
  const withActivity = (
    activity: Partial<ApiContainerActivity> | Error,
    containers = [container('hermes-prod')],
  ) => {
    const store = storeStub(containers);
    const idle = { activeAgents: 0, busyProfiles: [], creatingProfiles: [], pausedProfiles: [], unreadable: [] };
    const agents = {
      activity: activity instanceof Error
        ? vi.fn().mockRejectedValue(activity)
        : vi.fn().mockResolvedValue({ ...idle, ...activity }),
      pause: vi.fn().mockResolvedValue(undefined),
    };
    Object.assign(store.ctx, {
      api: { agents },
      toast: vi.fn(),
      toastFailure: vi.fn(),
    });
    Object.assign(store.agents, { refresh: vi.fn().mockResolvedValue(undefined) });
    return { store, agents };
  };

  it('stops an idle container on the click, with nothing in the way', async () => {
    const { store } = withActivity({});
    const { fixture } = render(store);

    press(fixture, 'stop', '.card');
    await settle(fixture);

    expect(store.lifecycle.setStatus).toHaveBeenCalledWith('hermes-prod', 'stopped');
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('holds a stop that would kill turns, and names what is running', async () => {
    const { store } = withActivity({ activeAgents: 2, busyProfiles: ['atlas'] });
    const { fixture } = render(store);

    press(fixture, 'stop', '.card');
    await settle(fixture);

    expect(store.lifecycle.setStatus).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('2');
    expect(text(fixture)).toContain('atlas');

    press(fixture, 'stop anyway');
    await settle(fixture);
    expect(store.lifecycle.setStatus).toHaveBeenCalledWith('hermes-prod', 'stopped');
  });

  it('offers hermes\u2019 own pause, which leaves the container up', async () => {
    const { store, agents } = withActivity({ activeAgents: 1, busyProfiles: ['atlas'] });
    const { fixture } = render(store);

    press(fixture, 'stop', '.card');
    await settle(fixture);
    // buttonWith, not press: the label carries the "— let them finish" suffix
    buttonWith(fixture, 'pause instead').click();
    fixture.detectChanges();
    await settle(fixture);

    expect(agents.pause).toHaveBeenCalledWith(
      { hostId: 'dh-local', containerId: 'hermes-prod', name: 'atlas' },
      expect.any(String));
    expect(store.lifecycle.setStatus).not.toHaveBeenCalled();
  });

  it('asks before stopping when the gateway wrote no state at all', async () => {
    // an unreadable profile is not a quiet yes — nothing-running is unproven either way
    const { store } = withActivity({ unreadable: ['atlas'] });
    const { fixture } = render(store);

    press(fixture, 'stop', '.card');
    await settle(fixture);

    expect(store.lifecycle.setStatus).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('absence of evidence');
  });

  it('stops anyway when the check itself fails', async () => {
    // refusing to stop a container because we could not read it is the worse failure
    const { store } = withActivity(new Error('container is not running'));
    const { fixture } = render(store);

    press(fixture, 'stop', '.card');
    await settle(fixture);

    expect(store.lifecycle.setStatus).toHaveBeenCalledWith('hermes-prod', 'stopped');
  });
});

describe('ContainersPage removal', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('names what the delete takes with it, and asks for the name typed back', async () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));
    const confirmed = stubConfirm(true);

    press(fixture, 'delete', '.card');
    await settle(fixture);

    expect(confirmed).toHaveBeenCalledWith(expect.objectContaining({
      typed: 'hermes-prod', action: 'delete permanently' }));
    expect(confirmed.mock.calls[0][0].message).toContain('1 profile(s) inside it');
    expect(store.lifecycle.remove).toHaveBeenCalledWith('hermes-prod');
  });

  it('deletes nothing when the operator backs out', async () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));
    stubConfirm(false);

    press(fixture, 'delete', '.card');
    await settle(fixture);

    expect(store.lifecycle.remove).not.toHaveBeenCalled();
  });
});

describe('ContainersPage deploy resources', () => {
  const accessChip = (fixture: TestFixture, label: string): HTMLButtonElement => {
    const match = Array.from(
      el(fixture).querySelectorAll<HTMLButtonElement>('.access .chip'))
      .find(c => (c.textContent ?? '').trim() === label);
    if (!match) throw new Error(`no host-access preset labelled "${label}"`);
    return match;
  };

  const resourceChip = (fixture: TestFixture, label: string): HTMLButtonElement => {
    const match = Array.from(
      el(fixture).querySelectorAll<HTMLButtonElement>('.resources .chip'))
      .find(c => (c.textContent ?? '').trim() === label);
    if (!match) throw new Error(`no resource preset labelled "${label}"`);
    return match;
  };

  it('opens on what Hermes recommends, and says so', async () => {
    const { fixture } = render(storeStub([]));
    await openDeploy(fixture);

    expect(resourceChip(fixture, '2 GB').classList.contains('on')).toBe(true);
    expect(resourceChip(fixture, '2 cores').classList.contains('on')).toBe(true);
    expect(text(fixture)).toContain('Hermes recommends 2–4 GB and 2 cores');
  });

  it('opens nothing to the host until a preset or a row asks for it', async () => {
    const { fixture } = render(storeStub([]));
    await openDeploy(fixture);

    expect(text(fixture)).toContain('nothing is opened unless you ask');
    expect(el(fixture).querySelector('.access .kv-row')).toBeNull();
  });

  it('a preset adds the rows its Hermes feature needs, and they go out with the deploy', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    accessChip(fixture, '+ hermes dashboard').click();
    accessChip(fixture, '+ hermes dashboard').click();     // a second press changes nothing
    fixture.detectChanges();
    await settle(fixture);

    const rows = el(fixture).querySelectorAll('.access .kv-row');
    expect(rows.length).toBe(4);                             // one port, three variables
    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy).toHaveBeenCalledWith(
      'hermes-staging', 'latest', [], 'dh-local', HERMES_BASELINE, expect.objectContaining({
        ports: [{ containerPort: 9119, hostPort: 9119, hostIp: '127.0.0.1' }],
        env: expect.arrayContaining([
          { key: 'HERMES_DASHBOARD', value: '1' },
          { key: 'HERMES_DASHBOARD_BASIC_AUTH_USERNAME', value: 'operator' },
          { key: 'HERMES_DASHBOARD_BASIC_AUTH_PASSWORD', value: expect.stringMatching(/^[0-9a-f]{32}$/) },
        ]),
        mounts: [],
      }), null);
  });

  it('a half-filled row is dropped rather than sent', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    press(fixture, '+ mount');
    await settle(fixture);
    expect(el(fixture).querySelectorAll('.access .kv-row.mounts').length).toBe(1);
    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy).toHaveBeenCalledWith(
      'hermes-staging', 'latest', [], 'dh-local', HERMES_BASELINE, NO_HOST_ACCESS, null);
  });

  it('deploys the raised ceiling rather than the baseline', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    resourceChip(fixture, '8 GB').click();
    resourceChip(fixture, '4 cores').click();
    fixture.detectChanges();
    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy).toHaveBeenCalledWith(
      'hermes-staging', 'latest', [], 'dh-local', { memoryMb: 8192, cpus: 4 }, NO_HOST_ACCESS, null);
  });

  it('warns at the floor that browser automation will not fit', async () => {
    const { fixture } = render(storeStub([]));
    await openDeploy(fixture);

    resourceChip(fixture, '1 GB').click();
    fixture.detectChanges();

    expect(text(fixture)).toContain('browser automation');
  });

  it('says the data volume is not capped here rather than implying it is', async () => {
    // a local docker volume has no size of its own; claiming a control we do not have
    // would be worse than saying nothing
    const { fixture } = render(storeStub([]));
    await openDeploy(fixture);

    expect(text(fixture)).toContain('data volume is not capped here');
  });

  it('starts the next deploy from the recommendation, not the last one raised', async () => {
    const { fixture } = render(storeStub([]));
    await openDeploy(fixture);
    resourceChip(fixture, '16 GB').click();
    fixture.detectChanges();
    press(fixture, 'cancel');
    fixture.detectChanges();

    await openDeploy(fixture);

    expect(resourceChip(fixture, '2 GB').classList.contains('on')).toBe(true);
  });
});
