import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Confirm } from '../shared/confirm';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AgentStore } from '../core/store/agent-store';
import { ContainerLifecycle } from '../core/store/container-lifecycle';
import { ContainerStore } from '../core/store/container-store';
import { HostStore } from '../core/store/host-store';
import { ImageCatalogStore } from '../core/store/image-catalog-store';
import { StoreContext } from '../core/store/store-context';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { TemplateStore } from '../core/store/template-store';
import { StatusDot } from '../shared/status-dot';
import { Sparkline } from '../shared/sparkline';
import { Reveal } from '../shared/reveal';
import { TerminalIcon } from '../shared/terminal-icon';
import {
  CPU_PRESETS, HERMES_BASELINE, MEMORY_PRESETS_MB, formatMemory, memoryNote,
} from '../core/container-resources';
import { compactAccess, dashboardUrl, emptyAccess, hasAccess } from '../core/host-access';
import { HostAccess } from '../core/models';
import { HostAccessEditor } from '../shared/host-access-editor';
import { errorMessage } from '../core/errors';
import { pct, uptime } from '../core/format';
import {
  containerUpdate, displayVersion, isFloatingTag, targetVersion, updateTargets,
} from '../core/image-policy';
import { HermesContainer, ImageTag } from '../core/models';
import { ApiContainerActivity } from '../core/hermes-api';
import { Scrim } from '../shared/scrim';

export function normalizeSeedProfiles(value: string): string[] {
  return Array.from(new Set(value.split(',')
    .map(p => p.trim().toLowerCase().replace(/\s+/g, '-'))
    .filter(p => !!p && p !== 'default')));
}

@Component({
  selector: 'mc-containers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Sparkline, Reveal, TerminalIcon, Scrim, HostAccessEditor],
  templateUrl: './containers.html',
  styleUrl: './containers.scss',
})
export class ContainersPage {
  protected readonly agents = inject(AgentStore);
  private readonly confirm = inject(Confirm);
  protected readonly containers = inject(ContainerStore);
  protected readonly ctx = inject(StoreContext);
  protected readonly hosts = inject(HostStore);
  protected readonly images = inject(ImageCatalogStore);
  protected readonly lifecycle = inject(ContainerLifecycle);
  protected readonly terminal = inject(TerminalRequestStore);
  protected readonly templates = inject(TemplateStore);
  private readonly router = inject(Router);

  protected readonly uptime = uptime;
  protected readonly pct = pct;

  protected readonly deployOpen = signal(false);
  protected deployName = '';
  protected deployVersion = '';
  protected deployProfiles = '';
  /** A blueprint for the default agent the image creates, or '' for hermes' own defaults. */
  protected deployTemplateId = '';
  protected deployHost = 'dh-local';
  protected readonly deployTags = signal<string[]>([]);
  protected readonly tagsLoading = signal(false);
  protected readonly tagsError = signal<string | null>(null);
  protected readonly deployBusy = signal(false);

  // The ceiling the new container runs under, starting at the vendor's recommendation.
  // Signals rather than plain fields: the hint under them reads off the chosen memory.
  protected readonly deployMemoryMb = signal(HERMES_BASELINE.memoryMb);
  protected readonly deployCpus = signal(HERMES_BASELINE.cpus);

  /** Ports, variables and mounts the deploy opens to the host — nothing until asked. Plain
   *  rows edited in place by the editor, the way the MCP editor keeps its environment. */
  protected deployAccess: HostAccess = emptyAccess();
  protected readonly memoryPresets = MEMORY_PRESETS_MB;
  protected readonly cpuPresets = CPU_PRESETS;
  protected readonly formatMemory = formatMemory;
  protected readonly memoryNote = memoryNote;

  /** Set when a deploy came back empty, so the modal stays and says why. */
  protected readonly deployFailed = signal(false);

  protected readonly addingHost = signal(false);
  protected hostName = '';
  protected hostUrl = 'tcp://';

  /** The container a stop is waiting on, with what that stop would interrupt. Only set when
   *  there is something to interrupt — an idle container stops on the click, as before. */
  protected readonly stopping = signal<{ container: HermesContainer; activity: ApiContainerActivity } | null>(null);
  protected readonly stopChecking = signal<string | null>(null);


  protected readonly updating = signal<HermesContainer | null>(null);
  protected readonly updatingBusy = signal(false);
  protected readonly updateTargets = signal<ImageTag[]>([]);
  protected updateVersion = '';
  /** What the update adds to the container's host access. The recreate an update already is
   *  happens to be the one moment Docker lets a port, variable or mount be added to an Agent. */
  protected updateAccess: HostAccess = emptyAccess();

  protected readonly connectedHosts = computed(() =>
    this.hosts.hosts().filter(h => h.status === 'connected'));

  /** containerId → the newest release it could move to. */
  protected readonly updates = computed(() => {
    const catalogs = this.images.catalog();
    const map = new Map<string, ImageTag>();
    for (const c of this.containers.containers()) {
      const target = containerUpdate(c, catalogs[c.hostId]);
      if (target) map.set(c.id, target);
    }
    return map;
  });

  constructor() {
    // fresh on navigate; the store's TTL collapses this with its own poll
    void this.images.refreshAll();
  }

  protected isUpdating(id: string): boolean {
    return this.updatingBusy() && this.updating()?.id === id;
  }

  /**
   * Open the bottom terminal panel on a shell in this container. No command: the
   * operator asked for a prompt, not for something to be run in it. A repeat
   * click focuses the tab this container already has rather than stacking one.
   */
  protected openTerminal(c: HermesContainer): void {
    this.terminal.open({ hostId: c.hostId, containerId: c.id, label: c.name });
  }

  /** The version to show for this container — the release it runs, not the pointer. */
  protected version(c: HermesContainer): string {
    return displayVersion(c, this.images.catalog()[c.hostId]);
  }

  /** True when the container tracks a moving tag, so the card can still say which. */
  protected tracks(c: HermesContainer): string | null {
    return isFloatingTag(c.version) && this.version(c) !== c.version ? c.version : null;
  }

  /** What the update moves it to, named as the release rather than the tag. */
  protected targetLabel(c: HermesContainer, target: ImageTag): string {
    return targetVersion(target, this.images.catalog()[c.hostId]);
  }

  protected updateHint(c: HermesContainer, target: ImageTag): string {
    const from = this.version(c);
    const to = this.targetLabel(c, target);
    // both ends resolved, so a move along a floating tag reads as the version change it is
    const move = from === to || isFloatingTag(to)
      ? `${from} · the registry published a new image on ${target.tag}`
      : `${from} → ${to}`;
    const via = isFloatingTag(target.tag) && to !== target.tag ? ` · on ${target.tag}` : '';
    return `${move}${via}${target.pulled ? '' : ' · not pulled on this host yet'}`;
  }

  /** A target option, naming the release a moving tag currently points at. */
  protected optionLabel(c: HermesContainer, t: ImageTag): string {
    const release = targetVersion(t, this.images.catalog()[c.hostId]);
    const name = release === t.tag ? t.tag : `${t.tag} — ${release}`;
    if (t.tag === c.version && !isFloatingTag(t.tag)) return `${name} — current`;
    return `${name}${t.pulled ? '' : ' — not pulled'}`;
  }

  /** True when the chosen target is the image the container already runs, so the recreate is
   *  for the host access alone. A floating tag re-pulled is a real move and never this. */
  protected sameImage(c: HermesContainer): boolean {
    return this.updateVersion === c.version && !isFloatingTag(this.updateVersion);
  }

  /** Recreating for no reason would drop every Agent's session, so the same image needs a row. */
  protected canUpdate(c: HermesContainer): boolean {
    return !!this.updateVersion && !this.updatingBusy()
      && (!this.sameImage(c) || hasAccess(compactAccess(this.updateAccess)));
  }

  protected updateLabel(c: HermesContainer): string {
    return this.sameImage(c) ? 'recreate with host access' : `update to ${this.updateVersion}`;
  }

  /** Where hermes' own web UI answers for this container, or null while nothing publishes it. */
  protected dashboardUrl(c: HermesContainer): string | null {
    return dashboardUrl(c, this.hosts.byId(c.hostId)?.url ?? '', location.hostname);
  }

  /** Everything this container could move to: newer releases, or the same tag re-pulled. */
  private targetsFor(c: HermesContainer): ImageTag[] {
    return updateTargets(c, this.images.catalog()[c.hostId]);
  }

  /**
   * What the backend is doing right now, in the operator's words.
   *
   * <p>An update on a cold host pulls an image before it recreates anything, which is minutes
   * of a spinner with nothing to read. Naming the slow half is the difference between "it is
   * working" and "it is stuck".
   */
  protected updateStage(): string {
    return this.targetPulled() ? 'recreating the container' : 'pulling the image, then recreating';
  }

  protected targetPulled(): boolean {
    return this.updateTargets().find(t => t.tag === this.updateVersion)?.pulled ?? true;
  }

  /**
   * Opens the update dialog — on the newest release, or with `sameImage` on the tag the
   * container already runs, which is how host access is added to an existing Agent. The
   * current tag is always among the options for the same reason.
   */
  protected beginUpdate(c: HermesContainer, sameImage = false): void {
    if (this.updatingBusy()) return;
    const targets = this.targetsFor(c);
    const current: ImageTag = { tag: c.version, pulled: true, digest: c.imageDigest };
    this.updateTargets.set(targets.some(t => t.tag === c.version) ? targets : [...targets, current]);
    this.updateVersion = sameImage ? c.version : targets[0]?.tag ?? c.version;
    this.updateAccess = emptyAccess();
    this.updating.set(c);
  }

  protected cancelUpdate(): void {
    if (this.updatingBusy()) return;
    this.updating.set(null);
    this.updateTargets.set([]);
    this.updateVersion = '';
  }

  protected async confirmUpdate(): Promise<void> {
    const c = this.updating();
    if (!c || !this.canUpdate(c)) return;
    this.updatingBusy.set(true);
    try {
      if (await this.lifecycle.update(c.id, this.updateVersion, compactAccess(this.updateAccess))) {
        this.updating.set(null);
        this.updateTargets.set([]);
        this.updateVersion = '';
      }
    } finally {
      this.updatingBusy.set(false);
    }
  }

  private static readonly TCP_URL = /^tcp:\/\/.+:\d+$/;

  protected hostUrlValid(): boolean {
    return ContainersPage.TCP_URL.test(this.hostUrl.trim());
  }

  protected openDeploy(): void {
    // never carry a stale host id into the modal — snap to a connected host
    this.deployFailed.set(false);
    // and never carry the last deploy's ceiling into the next one: the recommendation is
    // the starting point every time, not whatever was typed once
    this.deployMemoryMb.set(HERMES_BASELINE.memoryMb);
    this.deployCpus.set(HERMES_BASELINE.cpus);
    this.deployAccess = emptyAccess();
    this.deployTemplateId = '';
    this.deployHost = this.connectedHosts()[0]?.id ?? '';
    this.deployTags.set([]);
    this.tagsError.set(null);
    this.tagsLoading.set(false);
    this.deployOpen.set(true);
    void this.loadTags(this.deployHost);
  }

  protected profileCount(id: string): number {
    return this.agents.agents().filter(a => a.containerId === id).length;
  }

  protected open(id: string): void {
    this.containers.select(id);
    this.router.navigate(['/overview']);
  }

  /** Docker's own rule for a container name — `[a-zA-Z0-9][a-zA-Z0-9_.-]*` — checked here so
   *  the operator learns it from a hint, not from a 400 after the pull. */
  protected nameValid(): boolean {
    return /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(this.deployName.trim());
  }

  protected async deploy(): Promise<void> {
    const name = this.deployName.trim();
    const host = this.hosts.byId(this.deployHost);
    if (!this.nameValid() || !host || host.status !== 'connected' || !this.deployVersion || this.deployBusy()) return;
    const profiles = normalizeSeedProfiles(this.deployProfiles);
    this.deployBusy.set(true);
    this.deployFailed.set(false);
    const id = await this.lifecycle.deploy(name, this.deployVersion, profiles, this.deployHost,
      { memoryMb: this.deployMemoryMb(), cpus: this.deployCpus() }, compactAccess(this.deployAccess),
      this.deployTemplateId || null);
    this.deployBusy.set(false);
    if (!id) {
      this.deployFailed.set(true);
      return;
    }
    // Following the new container onto Overview is right for an operator who waited on the
    // modal, and wrong for one who closed it and walked to another page — a pull can take
    // minutes, and yanking them off whatever they went to do is not a reward for waiting.
    const waiting = this.deployOpen();
    this.deployOpen.set(false);
    this.deployName = '';
    this.deployProfiles = '';
    this.deployTags.set([]);
    this.containers.select(id);
    if (waiting) this.router.navigate(['/overview']);
  }

  /**
   * Stop, but ask first when the container has turns in flight.
   *
   * <p>`docker stop` gives the gateway ten seconds and then kills it, so a running turn is
   * lost work — and hermes has a `pause` that holds new work while letting those finish. The
   * check costs one request, and only on the click: an idle container still stops immediately.
   * A check that itself fails falls through to stopping rather than blocking the operator,
   * because refusing to stop a container because we could not read it is the worse failure.
   */
  protected async requestStop(c: HermesContainer): Promise<void> {
    if (this.stopChecking()) return;
    this.stopChecking.set(c.id);
    try {
      const activity = await this.ctx.api.agents.activity(c.hostId, c.id);
      if (activity.activeAgents > 0 || activity.unreadable.length > 0) {
        this.stopping.set({ container: c, activity });
        return;
      }
    } catch {
      // fall through: an unreadable container is still one the operator asked to stop
    } finally {
      this.stopChecking.set(null);
    }
    this.lifecycle.setStatus(c.id, 'stopped');
  }

  protected confirmStop(): void {
    const pending = this.stopping();
    if (!pending) return;
    this.stopping.set(null);
    this.lifecycle.setStatus(pending.container.id, 'stopped');
  }

  /** Holds every busy profile with hermes' own emergency stop, and leaves the container up.
   *  In-flight turns finish; nothing new starts. */
  protected async pauseInstead(): Promise<void> {
    const pending = this.stopping();
    if (!pending) return;
    const { container, activity } = pending;
    this.stopping.set(null);
    // straight at the API rather than through AgentStore: the Containers page does not
    // require that container's profiles to be loaded, and an id lookup would miss them
    try {
      for (const name of activity.busyProfiles) {
        await this.ctx.api.agents.pause(
          { hostId: container.hostId, containerId: container.id, name },
          'paused from the Containers page');
      }
      this.ctx.toast(`paused ${activity.busyProfiles.join(', ')} — in-flight turns will finish`);
      await this.agents.refresh();
    } catch (e) {
      this.ctx.toastFailure('pause', e);
    }
  }

  protected async remove(c: HermesContainer): Promise<void> {
    if (!await this.confirm.ask({
      title: 'delete container',
      message: `This deletes ${c.name}, its Mission Control-managed data volume, and all `
        + `${this.profileCount(c.id)} profile(s) inside it. External or unowned mounts are never `
        + 'deleted. It cannot be undone.',
      typed: c.name,
      action: 'delete permanently',
    })) return;
    // a refusal is toasted by the store; the card stays until the fleet poll says otherwise
    await this.lifecycle.remove(c.id);
  }

  protected addHost(): void {
    const name = this.hostName.trim();
    const url = this.hostUrl.trim();
    if (!name || !ContainersPage.TCP_URL.test(url)) return;
    this.hosts.add(name, url);
    this.addingHost.set(false);
    this.hostName = '';
    this.hostUrl = 'tcp://';
  }

  protected async loadTags(hostId: string): Promise<void> {
    if (!hostId) {
      this.deployTags.set([]);
      this.deployVersion = '';
      return;
    }
    this.tagsLoading.set(true);
    this.tagsError.set(null);
    try {
      const { tags } = await this.images.tags(hostId);
      if (hostId !== this.deployHost) return;   // host changed mid-flight — stale response
      this.deployTags.set(tags);
      if (!tags.includes(this.deployVersion)) {
        this.deployVersion = tags.includes('latest') ? 'latest' : (tags[0] ?? '');
      }
    } catch (error) {
      if (hostId !== this.deployHost) return;
      this.tagsError.set(errorMessage(error, 'failed to load image tags'));
      this.deployTags.set([]);
      this.deployVersion = '';
    } finally {
      this.tagsLoading.set(false);
    }
  }

  /** The docker host's display name, or '?' when it is no longer in the list. */
  protected hostLabel(id: string): string {
    return this.hosts.byId(id)?.name ?? '?';
  }
}
