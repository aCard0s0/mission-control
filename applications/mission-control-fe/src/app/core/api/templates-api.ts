import { ProfileTemplateInput } from '../models';
import { ApiAgentProfile, ApiProfileTemplate } from './api-types';
import { AgentRef } from './agent-ref';
import { CrudApi } from './crud-api';
import { ApiHttp, CONTAINER_WRITE_TIMEOUT_MS, seg } from './http';

/** `/api/profile-templates` — reusable agent blueprints, plus the capture and
 *  deploy calls that move configuration between a template and a live profile.
 *
 *  Both of those name the profile `name`, not `profile` as the library deploys do,
 *  so neither takes `agentTarget`. */
export class TemplatesApi extends CrudApi<ApiProfileTemplate, ProfileTemplateInput> {
  constructor(http: ApiHttp) {
    super(http, '/api/profile-templates');
  }

  /** Snapshots a running profile into a new template. */
  capture(ref: AgentRef, templateName?: string): Promise<ApiProfileTemplate> {
    return this.http.post('/api/profile-templates/capture', {
      hostId: ref.hostId, containerId: ref.containerId, name: ref.name, templateName,
    }, CONTAINER_WRITE_TIMEOUT_MS);
  }

  /** Materializes a template into `ref`'s container as the profile `ref.name`. */
  deploy(id: string, ref: AgentRef): Promise<ApiAgentProfile> {
    return this.http.post(`/api/profile-templates/${seg(id)}/deploy`, {
      hostId: ref.hostId, containerId: ref.containerId, name: ref.name,
    }, CONTAINER_WRITE_TIMEOUT_MS);
  }
}
