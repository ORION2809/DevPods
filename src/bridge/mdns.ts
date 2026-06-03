import os from 'node:os';
import { Bonjour, Service } from 'bonjour-service';

export interface BridgeMdnsOptions {
  readonly port: number;
  readonly name?: string;
  readonly pairingBaseUrl?: string;
  readonly version?: string;
}

export interface BridgeMdnsAdvertisement {
  readonly stop: () => void;
  readonly service: InstanceType<typeof Service>;
}

export function isReleaseSafeMdnsPairingBaseUrl(value: string | null | undefined): boolean {
  if (!value) return false;
  try {
    return new URL(value).protocol === 'https:';
  } catch {
    return false;
  }
}

export function startBridgeMdnsAdvertisement(options: BridgeMdnsOptions): BridgeMdnsAdvertisement {
  const bonjour = new Bonjour();
  const name = options.name ?? `DevPods Bridge on ${os.hostname()}`;
  const txt: Record<string, string> = {
    version: options.version ?? '1.0.0',
  };
  if (options.pairingBaseUrl) {
    txt.pairingBaseUrl = options.pairingBaseUrl;
  }

  const service = bonjour.publish({
    name,
    type: 'devpods',
    protocol: 'tcp',
    port: options.port,
    txt,
  });

  return {
    service,
    stop: () => {
      bonjour.unpublishAll(() => {
        bonjour.destroy();
      });
    },
  };
}
