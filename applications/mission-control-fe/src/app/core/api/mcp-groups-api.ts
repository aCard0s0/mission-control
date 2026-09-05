import { AgentRef, agentTarget } from './agent-ref';
import { McpGroupInput } from '../models';
import { ApiDeployedMcpGroup, ApiMcpGroup } from './api-types';
import { CrudApi } from './crud-api';
import { ApiHttp, CONTAINER_WRITE_TIMEOUT_MS, seg } from './http';

/**
 * `/api/mcp-groups` — a named set of catalog entries, and one call that connects the whole set
 * to one agent.
 *
 * The only group client with a deploy. Nothing here records which agents a group reaches: the
 * list answers that, read back off the agent links each time.
 */
export class McpGroupsApi extends CrudApi<ApiMcpGroup, McpGroupInput> {
  constructor(http: ApiHttp) {
    super(http, '/api/mcp-groups');
  }

  /** Answers a row per server, because a group can half-connect. */
  deploy(id: string, agent: AgentRef): Promise<ApiDeployedMcpGroup> {
    return this.http.post(`/api/mcp-groups/${seg(id)}/deploy`, agentTarget(agent), CONTAINER_WRITE_TIMEOUT_MS);
  }
}
