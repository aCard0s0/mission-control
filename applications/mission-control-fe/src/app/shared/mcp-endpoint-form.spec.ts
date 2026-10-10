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

  it('adds a header only when both its name and value are filled, and never for stdio', () => {
    expect(filled({ headerName: ' Authorization ', headerValue: ' Bearer t ' }).endpoint())
      .toEqual({ url: 'https://mcp.example.test/mcp', headers: { Authorization: 'Bearer t' } });
    expect(filled({ headerName: 'Authorization' }).valid()).toBe(false);
    expect(filled({ headerValue: 'Bearer t' }).valid()).toBe(false);
    expect(filled({ transport: 'stdio', command: 'npx', headerName: 'X', headerValue: 'y' }).endpoint())
      .toEqual({ command: 'npx', args: undefined });
  });

  it('answers null rather than a half-built endpoint', () => {
    expect(filled({ name: '' }).endpoint()).toBeNull();
    expect(filled({ transport: 'stdio', command: '' }).endpoint()).toBeNull();
  });
});

describe('McpEndpointForm load and reset', () => {
  it('loads an existing server, blanking the fields it does not carry', () => {
    const form = filled();
    form.load({ name: 'local', transport: 'stdio', command: 'npx', args: '-y @acme/server' });

    expect(form).toMatchObject({
      name: 'local', transport: 'stdio', command: 'npx', args: '-y @acme/server', url: '',
    });
  });

  it('resets to the transport the host page defaults to, not to http', () => {
    const form = new McpEndpointForm('stdio');
    form.load({ name: 'remote', transport: 'http', url: 'https://mcp.example.test/mcp' });
    form.reset();

    expect(form).toMatchObject({
      name: '', transport: 'stdio', url: '', command: '', args: '', headerName: '', headerValue: '',
    });
  });
});
