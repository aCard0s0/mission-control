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
  /** One optional connection header, e.g. `Authorization: Bearer …`. Write-only: the
   *  profile never hands a header value back, so editing starts with both blank. */
  headerName = '';
  headerValue = '';

  constructor(private readonly defaultTransport: McpTransport = 'http') {
    this.transport = defaultTransport;
  }

  get stdio(): boolean {
    return this.transport === 'stdio';
  }

  /** True once the submit button should be enabled. */
  valid(): boolean {
    if (!this.name.trim()) return false;
    if (!this.stdio && !this.headerName.trim() !== !this.headerValue.trim()) return false;
    return this.stdio ? !!this.command.trim() : !!this.url.trim();
  }

  /** The trimmed endpoint for the active transport, or null when incomplete. */
  endpoint(): McpEndpointOptions | null {
    if (!this.valid()) return null;
    return this.stdio
      ? { command: this.command.trim(), args: this.args.trim() || undefined }
      : { url: this.url.trim(), ...this.headers() };
  }

  private headers(): Pick<McpEndpointOptions, 'headers'> {
    const name = this.headerName.trim();
    const value = this.headerValue.trim();
    return name && value ? { headers: { [name]: value } } : {};
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
    this.headerName = '';
    this.headerValue = '';
  }

  reset(): void {
    this.name = '';
    this.transport = this.defaultTransport;
    this.url = '';
    this.command = '';
    this.args = '';
    this.headerName = '';
    this.headerValue = '';
  }
}
