import { inject, Injectable } from '@angular/core';
import { HERMES_BASELINE } from '../container-resources';
import { isFloatingTag } from '../image-policy';
import { ContainerResources, ContainerStatus, HostAccess } from '../models';
import { NO_HOST_ACCESS, hasAccess } from '../host-access';
import { ActivityStore } from './activity-store';
import { ContainerStore } from './container-store';
import { ImageCatalogStore } from './image-catalog-store';
import { StoreContext } from './store-context';

/**
 * Deploying, starting, updating and removing a container. Each call is the
 * backend's to make; what lands here afterwards is a re-read of the inventory,
 * because the daemon decides what actually exists.
 */
@Injectable({ providedIn: 'root' })
export class ContainerLifecycle {
  private readonly ctx = inject(StoreContext);
  private readonly containers = inject(ContainerStore);
  private readonly images = inject(ImageCatalogStore);
  private readonly activity = inject(ActivityStore);

  /** Deploys a container and resolves only after refreshed inventory contains it. */
  async deploy(
    name: string, version: string, profileNames: string[], hostId = 'dh-local',
    resources: ContainerResources = HERMES_BASELINE,
    access: HostAccess = NO_HOST_ACCESS,
    defaultTemplateId: string | null = null,
  ): Promise<string> {
    return this.activity.run(`deploying ${name}`, async () => {
      try {
        const r = await this.ctx.api.containers.deploy(
          hostId, name, version, profileNames, resources, access, defaultTemplateId);
        await new Promise(resolve => setTimeout(resolve, 600));
        await this.containers.refresh();
        this.containers.select(r.id);
        this.ctx.notify(`container ${name} deployed`);
        return r.id;
      } catch (e) {
        this.ctx.toastFailure('deploy', e);
        return '';
      }
    });
  }

  setStatus(id: string, status: ContainerStatus): void {
    const container = this.containers.byId(id);
    if (!container) {
      this.ctx.gone('container');
      return;
    }
    const words = status === 'running'
      ? { verb: 'start', doing: 'starting', done: 'started' }
      : { verb: 'stop', doing: 'stopping', done: 'stopped' };
    const call = status === 'running'
      ? this.ctx.api.containers.start(container.hostId, id)
      : this.ctx.api.containers.stop(container.hostId, id);
    // Tracked from here rather than from the button, because the daemon keeps working on it
    // after the page that asked is gone — and the refresh, not the call, is what says it landed.
    // The refresh cannot reject (it keeps its last inventory), so the catch stays about the call.
    const running = this.activity.begin(`${words.doing} ${container.name}`);
    call
      .then(() => new Promise<void>(resolve => setTimeout(resolve, 700)))
      .then(() => this.containers.refresh())
      .then(() => this.ctx.notify(`${container.name} ${words.done}`))
      .catch(e => this.ctx.toastFailure(words.verb, e))
      .finally(() => this.activity.end(running));
  }

  /**
   * Recreates `id` on `version`. The backend pulls the tag if needed, then
   * replaces the container against the same data volume, so profiles, souls,
   * skills and credentials survive. **The container id changes** — callers
   * holding an id must re-read it. Resolves to the new id, or '' on failure.
   * `access` is added to what the container already has, and is what makes the
   * tag it already runs a legitimate target: the recreate is then the point.
   */
  async update(id: string, version: string, access: HostAccess = NO_HOST_ACCESS): Promise<string> {
    const container = this.containers.byId(id);
    if (!container) {
      this.ctx.gone('container');
      return '';
    }
    if (!version) return '';
    // A floating tag matching what the container already runs is the whole point of asking:
    // `latest` moved, and the local copy is what is stale. Dropping it as a no-op here is why
    // 'update to latest' did nothing — the backend re-pulls floating tags precisely for this.
    if (version === container.version && !isFloatingTag(version) && !hasAccess(access)) return '';
    const wasSelected = this.containers.selectedContainerId() === id;
    try {
      const r = await this.ctx.api.containers.update(container.hostId, id, version, access);
      await this.containers.refresh();
      if (wasSelected) this.containers.select(r.id);
      void this.images.refresh(container.hostId, true);   // the tag is pulled now
      return r.id;
    } catch (e) {
      this.ctx.toastFailure('update', e);
      await this.containers.refresh();   // the recreate may have half-landed
      return '';
    }
  }

  async remove(id: string): Promise<boolean> {
    const container = this.containers.byId(id);
    if (!container) return this.ctx.gone('container');
    try {
      await this.ctx.api.containers.remove(container.hostId, id);
      if (this.containers.selectedContainerId() === id) this.containers.select('');
      await this.containers.refresh();
      return true;
    } catch (e) {
      this.ctx.toastFailure('remove', e);
      await this.containers.refresh(); // removal may have succeeded before volume cleanup failed
      return false;
    }
  }
}
