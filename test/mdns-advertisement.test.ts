import { describe, it, expect, vi } from 'vitest';
import { isReleaseSafeMdnsPairingBaseUrl, startBridgeMdnsAdvertisement } from '../src/bridge/mdns';

describe('Bridge mDNS advertisement', () => {
  it('creates an advertisement with the correct service type', () => {
    const ad = startBridgeMdnsAdvertisement({
      port: 4545,
      pairingBaseUrl: 'https://192.168.1.100:4545',
      version: '1.0.0',
    });

    expect(ad).toHaveProperty('service');
    expect(ad).toHaveProperty('stop');
    expect(typeof ad.stop).toBe('function');

    ad.stop();
  });

  it('uses custom name when provided', () => {
    const ad = startBridgeMdnsAdvertisement({
      port: 4545,
      name: 'Custom Bridge Name',
    });

    expect(ad.service.name).toBe('Custom Bridge Name');
    ad.stop();
  });

  it('includes pairingBaseUrl in TXT record when provided', () => {
    const ad = startBridgeMdnsAdvertisement({
      port: 4545,
      pairingBaseUrl: 'https://192.168.1.100:4545',
    });

    expect(ad.service.txt).toHaveProperty('pairingBaseUrl', 'https://192.168.1.100:4545');
    ad.stop();
  });

  it('marks only HTTPS pairing URLs as release-safe for discovery', () => {
    expect(isReleaseSafeMdnsPairingBaseUrl('https://bridge.example.test/relay')).toBe(true);
    expect(isReleaseSafeMdnsPairingBaseUrl('http://192.168.1.100:4545')).toBe(false);
    expect(isReleaseSafeMdnsPairingBaseUrl(null)).toBe(false);
    expect(isReleaseSafeMdnsPairingBaseUrl('not a url')).toBe(false);
  });
});
