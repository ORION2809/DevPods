import { parseArgs } from 'node:util';
import { loadFixtureEvent, sendEvent } from '../../src/simulator/client';

async function main(): Promise<void> {
  const [fixture, ...rest] = process.argv.slice(2);
  if (!fixture) {
    throw new Error('A fixture name or alias is required.');
  }

  const { values } = parseArgs({
    args: rest,
    options: {
      port: { type: 'string', default: '4545' },
      utterance: { type: 'string' },
      'session-id': { type: 'string' },
      workspace: { type: 'string' },
    },
  });

  const event = loadFixtureEvent(fixture, {
    utterance: values.utterance,
    sessionId: values['session-id'],
    workspace: values.workspace,
  });

  const result = await sendEvent(event, Number(values.port));
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

void main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});