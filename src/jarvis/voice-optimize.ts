export function optimizeSpeak(input: string): string {
  const normalized = input.replace(/\s+/g, ' ').trim();
  const words = normalized.split(' ').filter(Boolean);

  if (words.length <= 24) {
    return normalized;
  }

  return `${words.slice(0, 24).join(' ')}.`;
}