import QRCode from 'qrcode';

export async function renderPairingQrDataUrl(value: string): Promise<string> {
  const svg = await QRCode.toString(value, {
    errorCorrectionLevel: 'M',
    margin: 1,
    type: 'svg',
    width: 320,
    color: {
      dark: '#0f172a',
      light: '#ffffff',
    },
  });

  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}