import { describe, expect, it } from 'vitest';
import { McpEndpointForm } from './mcp-endpoint-form';

const filled = (patch: Partial<McpEndpointForm> = {}): McpEndpointForm =>
  Object.assign(new McpEndpointForm(), { name: 'github', url: 'https://mcp.example.test/mcp' }, patch);

describe('McpEndpointForm validity', () => {
  it('needs a name whatever the transport', () => {
    expect(filled({ name: '  ' }).valid()).toBe(false);
  });

  it('needs a url for an http or sse endpoint', () => {
    expect(filled({ url: '' }).valid()).toBe(false);
    expect(filled({ transport: 'sse' }).valid()).toBe(true);
  });

  it('needs a command for stdio, and does not accept a url in its place', () => {
    expect(filled({ transport: 'stdio' }).valid()).toBe(false);
    expect(filled({ transport: 'stdio', command: 'npx' }).valid()).toBe(true);
  });
});

describe('McpEndpointForm endpoint', () => {
  it('trims the url and sends nothing else for http', () => {
    expect(filled({ url: '  https://mcp.example.test/mcp  ' }).endpoint())
      .toEqual({ url: 'https://mcp.example.test/mcp' });
  });

  it('sends the command with optional args for stdio', () => {
    expect(filled({ transport: 'stdio', command: ' npx ', args: ' -y @acme/server ' }).endpoint())
      .toEqual({ command: 'npx', args: '-y @acme/server' });
    expect(filled({ transport: 'stdio', command: 'npx', args: '   ' }).endpoint())
      .toEqual({ command: 'npx', args: undefined });
  });

  it('sends every header whose name and value are both filled, trimmed', () => {
    const headers = [
      { name: ' Authorization ', value: ' Bearer t ', secret: false },
      { name: 'X-Tenant', value: 'acme', secret: true },
      { name: '  ', value: '', secret: false },        // an untouched row is just ignored
    ];
    expect(filled({ headers }).endpoint()).toEqual({
      url: 'https://mcp.example.test/mcp',
      headers: { Authorization: 'Bearer t', 'X-Tenant': 'acme' },
    });
  });

  it('rejects a header row with only one half filled, and drops headers for stdio', () => {
    expect(filled({ headers: [{ name: 'Authorization', value: '', secret: false }] }).valid()).toBe(false);
    expect(filled({ headers: [{ name: '', value: 'Bearer t', secret: false }] }).valid()).toBe(false);
    expect(filled({
      transport: 'stdio', command: 'npx', headers: [{ name: 'X', value: 'y', secret: false }],
    }).endpoint()).toEqual({ command: 'npx', args: undefined });
  });

  it('adds and removes header rows, a new one showing its value', () => {
    const form = filled();
    form.addHeader();
    form.addHeader();
    form.headers[0].name = 'A';
    form.removeHeader(1);
    expect(form.headers).toEqual([{ name: 'A', value: '', secret: false }]);
  });

  it('answers null rather than a half-built endpoint', () => {
    expect(filled({ name: '' }).endpoint()).toBeNull();
    expect(filled({ transport: 'stdio', command: '' }).endpoint()).toBeNull();
  });
});

describe('McpEndpointForm load and reset', () => {
  it('loads an existing server, blanking the fields it does not carry', () => {
    const form = filled({ headers: [{ name: 'Authorization', value: 'Bearer t', secret: false }] });
    form.load({ name: 'local', transport: 'stdio', command: 'npx', args: '-y @acme/server' });

    expect(form).toMatchObject({
      name: 'local', transport: 'stdio', command: 'npx', args: '-y @acme/server', url: '', headers: [],
    });
  });

  it('resets to the transport the host page defaults to, not to http', () => {
    const form = new McpEndpointForm('stdio');
    form.load({ name: 'remote', transport: 'http', url: 'https://mcp.example.test/mcp' });
    form.reset();

    expect(form).toMatchObject({
      name: '', transport: 'stdio', url: '', command: '', args: '', headers: [],
    });
  });
});
