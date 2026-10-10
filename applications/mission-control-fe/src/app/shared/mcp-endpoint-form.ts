import { McpTransport } from '../core/models';

/** Transport-specific endpoint fields, as the store and the template editor
 *  both want them: a url for http/sse, a command plus args for stdio. */
export interface McpEndpointOptions {
  url?: string;
  command?: string;
  args?: string;
  /** http/sse only; absent means "leave the profile's headers as they are". */
  headers?: Record<string, string>;
}

/** One connection header being typed. `secret` only masks the value on screen. */
export interface McpHeaderRow {
  name: string;
  value: string;
  secret: boolean;
}

/**
 * The "add an MCP server" form, which the agent detail page and the profile
 * template editor both put on screen with their own layout. What they must agree
 * on is the rule — a name, plus a command for stdio or a url otherwise — so the
 * fields and that rule live here rather than in each page.
 *
 * Plain mutable fields, because both templates bind them with `[(ngModel)]`.
 */
export class McpEndpointForm {
  name = '';
  transport: McpTransport;
  url = '';
  command = '';
  args = '';
  /** Connection headers, e.g. `Authorization: Bearer …`. Write-only: the profile never hands
   *  a value back, so editing starts with no rows. A row left fully blank is ignored. */
  headers: McpHeaderRow[] = [];

  constructor(private readonly defaultTransport: McpTransport = 'http') {
    this.transport = defaultTransport;
  }

  get stdio(): boolean {
    return this.transport === 'stdio';
  }

  /** True once the submit button should be enabled. */
  valid(): boolean {
    if (!this.name.trim()) return false;
    if (!this.stdio && this.headers.some(h => !h.name.trim() !== !h.value.trim())) return false;
    return this.stdio ? !!this.command.trim() : !!this.url.trim();
  }

  /** The trimmed endpoint for the active transport, or null when incomplete. */
  endpoint(): McpEndpointOptions | null {
    if (!this.valid()) return null;
    return this.stdio
      ? { command: this.command.trim(), args: this.args.trim() || undefined }
      : { url: this.url.trim(), ...this.headerMap() };
  }

  addHeader(): void {
    this.headers.push({ name: '', value: '', secret: false });
  }

  removeHeader(index: number): void {
    this.headers.splice(index, 1);
  }

  private headerMap(): Pick<McpEndpointOptions, 'headers'> {
    const filled = this.headers.filter(h => h.name.trim() && h.value.trim());
    return filled.length
      ? { headers: Object.fromEntries(filled.map(h => [h.name.trim(), h.value.trim()])) }
      : {};
  }

  trimmedName(): string {
    return this.name.trim();
  }

  /** Loads an existing server in for editing. */
  load(server: { name: string; transport: McpTransport; url?: string; command?: string; args?: string }): void {
    this.name = server.name;
    this.transport = server.transport;
    this.url = server.url ?? '';
    this.command = server.command ?? '';
    this.args = server.args ?? '';
    this.headers = [];
  }

  reset(): void {
    this.name = '';
    this.transport = this.defaultTransport;
    this.url = '';
    this.command = '';
    this.args = '';
    this.headers = [];
  }
}
