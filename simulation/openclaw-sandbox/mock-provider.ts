import fs from 'node:fs';
import http from 'node:http';

export interface MockOpenAiProviderHandle {
  readonly baseUrl: string;
  readonly requests: unknown[];
  readonly requestLogPath?: string;
  stop(): Promise<void>;
}

export async function startMockOpenAiProvider(options: {
  assistantJson?: { speak: string; display: string; followUpHint?: string | null };
  requestLogPath?: string;
} = {}): Promise<MockOpenAiProviderHandle> {
  const requests: unknown[] = [];
  if (options.requestLogPath) {
    fs.writeFileSync(options.requestLogPath, '', 'utf8');
  }
  const payload = options.assistantJson ?? {
    speak: 'OpenClaw sandbox completed the response.',
    display: 'The mock provider returned a deterministic sandbox response.',
    followUpHint: 'mock-provider',
  };

  const server = http.createServer(async (request, response) => {
    appendRequestLog(options.requestLogPath, {
      at: new Date().toISOString(),
      phase: 'start',
      method: request.method ?? 'UNKNOWN',
      url: request.url ?? '',
      headers: request.headers,
    });

    const chunks: string[] = [];
    for await (const chunk of request) {
      chunks.push(typeof chunk === 'string' ? chunk : chunk.toString('utf8'));
    }

    const rawBody = chunks.join('');
    const parsedBody = rawBody.length > 0 ? safeJsonParse(rawBody) : null;
    appendRequestLog(options.requestLogPath, {
      at: new Date().toISOString(),
      phase: 'body-complete',
      method: request.method ?? 'UNKNOWN',
      url: request.url ?? '',
      headers: request.headers,
      body: parsedBody,
      rawBody,
    });

    if (request.method === 'GET' && (request.url === '/models' || request.url === '/v1/models')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(
        JSON.stringify({
          object: 'list',
          data: [
            {
              id: 'mock-rewrite-model',
              object: 'model',
              owned_by: 'mock-provider',
            },
          ],
        }),
      );
      return;
    }

    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(404, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'Not found' }));
      return;
    }

    if (parsedBody === null || Array.isArray(parsedBody) || typeof parsedBody !== 'object') {
      response.writeHead(400, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'Expected a JSON object body.' }));
      return;
    }

    const requestBody = parsedBody as { stream?: unknown };
    requests.push(parsedBody);

    const serializedPayload = JSON.stringify(payload);
    if (requestBody.stream === true) {
      const created = Math.floor(Date.now() / 1000);
      response.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      response.write(
        `data: ${JSON.stringify({
          id: 'chatcmpl_mock_provider',
          object: 'chat.completion.chunk',
          created,
          model: 'mock-provider/mock-rewrite-model',
          choices: [
            {
              index: 0,
              delta: {
                role: 'assistant',
                content: serializedPayload,
              },
              finish_reason: null,
            },
          ],
        })}\n\n`,
      );
      response.write(
        `data: ${JSON.stringify({
          id: 'chatcmpl_mock_provider',
          object: 'chat.completion.chunk',
          created,
          model: 'mock-provider/mock-rewrite-model',
          choices: [
            {
              index: 0,
              delta: {},
              finish_reason: 'stop',
            },
          ],
          usage: {
            prompt_tokens: 32,
            completion_tokens: 12,
            total_tokens: 44,
            prompt_tokens_details: {
              cached_tokens: 0,
            },
          },
        })}\n\n`,
      );
      response.end('data: [DONE]\n\n');
      return;
    }

    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(
      JSON.stringify({
        id: 'chatcmpl_mock_provider',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'mock-provider/mock-rewrite-model',
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: {
              role: 'assistant',
              content: serializedPayload,
            },
          },
        ],
      }),
    );
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('Mock provider address was not available.');
  }

  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    requests,
    requestLogPath: options.requestLogPath,
    stop: () => new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
  };
}

function appendRequestLog(logPath: string | undefined, entry: Record<string, unknown>): void {
  if (!logPath) {
    return;
  }

  fs.appendFileSync(logPath, `${JSON.stringify(entry)}\n`, 'utf8');
}

function safeJsonParse(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}