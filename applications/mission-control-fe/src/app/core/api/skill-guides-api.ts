import { AgentRef, agentTarget } from './agent-ref';
import { SkillGuideInput } from '../models';
import { ApiDeployedGuide, ApiSkillGuide } from './api-types';
import { CrudApi } from './crud-api';
import { ApiHttp, CONTAINER_WRITE_TIMEOUT_MS, seg } from './http';

/**
 * `/api/skill-guides` — guides: prose that composes several library skills with the MCP
 * servers they need, and one deploy that puts the whole set on an agent.
 */
export class SkillGuidesApi extends CrudApi<ApiSkillGuide, SkillGuideInput> {
  constructor(http: ApiHttp) {
    super(http, '/api/skill-guides');
  }

  /** Answers with a row per part, because a guide can half-land. */
  deploy(id: string, agent: AgentRef): Promise<ApiDeployedGuide> {
    return this.http.post(`/api/skill-guides/${seg(id)}/deploy`, agentTarget(agent), CONTAINER_WRITE_TIMEOUT_MS);
  }
}
