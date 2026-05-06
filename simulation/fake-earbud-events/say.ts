import { sendEvent, loadFixtureEvent } from '../../src/simulator/client';

async function main(): Promise<void> {
  const utterance = process.argv.slice(2).join(' ').trim();
  if (!utterance) {
    throw new Error('An utterance is required.');
  }

  const event = loadFixtureEvent('triple_tap_right', { utterance });
  const result = await sendEvent(event);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

void main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});