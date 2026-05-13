export interface RelayPairingPayload {
  bridgeBaseUrl: string;
  pairingCode?: string;
  workspace?: string;
}

export interface ResolveRelayPairingBaseUrlInput {
  host: string;
  port: number;
  pairingBaseUrl?: string;
}

const DEFAULT_WORKSPACE = 'current_repo';

export function buildRelayPairingUri(payload: RelayPairingPayload): string {
  const url = new URL('devpods://pair');
  url.searchParams.set('bridgeBaseUrl', normalizeRelayBaseUrl(payload.bridgeBaseUrl));

  const pairingCode = payload.pairingCode?.trim();
  if (pairingCode) {
    url.searchParams.set('pairingCode', pairingCode);
  }

  url.searchParams.set('workspace', payload.workspace?.trim() || DEFAULT_WORKSPACE);
  return url.toString();
}

export function buildRelayPairingPageUrl(baseUrl: string): string {
  const url = new URL(normalizeRelayBaseUrl(baseUrl));
  const normalizedPathname = url.pathname.replace(/\/+$/, '');
  url.pathname = `${normalizedPathname}/pairing` || '/pairing';
  url.search = '';
  url.hash = '';
  return url.toString();
}

export function resolveRelayPairingBaseUrl(input: ResolveRelayPairingBaseUrlInput): string | null {
  const explicitBaseUrl = input.pairingBaseUrl?.trim();
  if (explicitBaseUrl) {
    return normalizeRelayBaseUrl(explicitBaseUrl);
  }

  const normalizedHost = input.host.trim().toLowerCase();
  if (!normalizedHost || normalizedHost === '0.0.0.0' || normalizedHost === '::' || isLoopbackHost(normalizedHost)) {
    return null;
  }

  return `http://${input.host.trim()}:${input.port}`;
}

function normalizeRelayBaseUrl(value: string): string {
  const trimmedValue = value.trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(trimmedValue)) {
    throw new Error(`Expected pairing base URL to start with http:// or https:// but received "${value}".`);
  }

  return trimmedValue;
}

function isLoopbackHost(host: string): boolean {
  return host === '127.0.0.1' || host === 'localhost' || host === '::1';
}