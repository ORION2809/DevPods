import { describe, expect, it } from 'vitest';
import { buildRelayPairingPageUrl, buildRelayPairingUri, resolveRelayPairingBaseUrl } from '../src/pairing/uri';

describe('pairing uri helpers', () => {
  it('builds a devpods pairing uri from the bridge config using a pairing code', () => {
    expect(
      buildRelayPairingUri({
        bridgeBaseUrl: 'http://192.168.1.10:4545/',
        pairingCode: 'ABC123',
        workspace: 'current_repo',
      }),
    ).toBe(
      'devpods://pair?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545&pairingCode=ABC123&workspace=current_repo',
    );
  });

  it('omits the token when the pairing payload does not need one', () => {
    expect(
      buildRelayPairingUri({
        bridgeBaseUrl: 'https://bridge.example.test/base/',
        workspace: 'current_repo',
      }),
    ).toBe(
      'devpods://pair?bridgeBaseUrl=https%3A%2F%2Fbridge.example.test%2Fbase&workspace=current_repo',
    );
  });

  it('builds a pairing page url from the externally reachable bridge base url', () => {
    expect(buildRelayPairingPageUrl('http://192.168.1.10:4545/')).toBe('http://192.168.1.10:4545/pairing');
    expect(buildRelayPairingPageUrl('https://bridge.example.test/relay')).toBe('https://bridge.example.test/relay/pairing');
  });

  it('resolves a pairing base url from either an explicit value or a routable host binding', () => {
    expect(
      resolveRelayPairingBaseUrl({
        host: '192.168.1.12',
        port: 4545,
      }),
    ).toBe('http://192.168.1.12:4545');

    expect(
      resolveRelayPairingBaseUrl({
        host: '0.0.0.0',
        port: 4545,
        pairingBaseUrl: 'https://bridge.example.test/relay/',
      }),
    ).toBe('https://bridge.example.test/relay');
  });

  it('refuses to infer a pairing base url from loopback or unspecified bindings', () => {
    expect(
      resolveRelayPairingBaseUrl({
        host: '127.0.0.1',
        port: 4545,
      }),
    ).toBeNull();

    expect(
      resolveRelayPairingBaseUrl({
        host: '0.0.0.0',
        port: 4545,
      }),
    ).toBeNull();
  });
});