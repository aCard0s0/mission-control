import { ApiContainer, ApiImageTags, ApiLogLine, ApiStats } from './api-types';
import { ContainerResources, HostAccess } from '../models';
import { NO_HOST_ACCESS } from '../host-access';
import { ApiHttp, seg } from './http';

/** `/api/containers` and `/api/images` — Agent container inventory, telemetry
 *  and lifecycle on a given docker host. */
export class ContainersApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiContainer[]> {
    return this.http.get('/api/containers');
  }

  stats(hostId: string, id: string): Promise<ApiStats> {
    return this.http.get(`/api/containers/${seg(hostId)}/${seg(id)}/stats`);
  }

  /** Every named container's newest sample in one request, keyed by container id.
   *  A container the server has no live sample for is simply absent. */
  statsBatch(hostId: string, ids: string[]): Promise<Record<string, ApiStats>> {
    const query = ids.map(id => seg(id)).join(',');
    return this.http.get(`/api/containers/${seg(hostId)}/stats?ids=${query}`);
  }

  /** `since` is an epoch-ms cursor: the reply carries only lines after it, plus
   *  possibly the one it was taken from, since docker resolves it to whole seconds. */
  logs(hostId: string, id: string, tail = 100, since?: number): Promise<ApiLogLine[]> {
    const cursor = since === undefined ? '' : `&since=${since}`;
    return this.http.get(`/api/containers/${seg(hostId)}/${seg(id)}/logs?tail=${tail}${cursor}`);
  }

  /**
   * `defaultTemplateId` names a blueprint for the `default` agent the image creates; null
   * leaves it as hermes makes it. Allowed the same budget as an update: a cold host pulls the
   * image, the gateway has to pass readiness, and a blueprint is some forty hermes calls more.
   */
  deploy(
    hostId: string, name: string, version: string, profiles: string[],
    resources: ContainerResources, access: HostAccess = NO_HOST_ACCESS,
    defaultTemplateId: string | null = null,
  ): Promise<{ id: string }> {
    return this.http.post('/api/containers', {
      hostId, name, version, profiles,
      memoryMb: resources.memoryMb, cpus: resources.cpus,
      ports: access.ports, env: access.env, mounts: access.mounts,
      defaultTemplateId,
    }, 300_000);
  }

  start(hostId: string, id: string): Promise<void> {
    return this.http.post(`/api/containers/${seg(hostId)}/${seg(id)}/start`);
  }

  stop(hostId: string, id: string): Promise<void> {
    return this.http.post(`/api/containers/${seg(hostId)}/${seg(id)}/stop`);
  }

  remove(hostId: string, id: string): Promise<void> {
    return this.http.delete(`/api/containers/${seg(hostId)}/${seg(id)}`);
  }

  /**
   * Recreates the container on `version`, reusing its data volume, and resolves
   * to the replacement's id. Allowed far longer than the default budget: a cold
   * host pulls the image first, then the new container has to pass readiness.
   * `access` is laid over the ports, variables and mounts the container already has.
   */
  update(
    hostId: string, id: string, version: string, access: HostAccess = NO_HOST_ACCESS,
  ): Promise<{ id: string }> {
    return this.http.post(
      `/api/containers/${seg(hostId)}/${seg(id)}/update`,
      { version, ports: access.ports, env: access.env, mounts: access.mounts }, 300_000);
  }

  imageTags(hostId: string): Promise<ApiImageTags> {
    return this.http.get(`/api/images/tags?hostId=${seg(hostId)}`);
  }
}
