import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import chatRouter from './chat.js';

// We need supertest for HTTP-level integration tests
// Since supertest might not be installed, we'll use raw Node http approach
// Actually, let's test with a simpler approach using the express app directly

// Mock external dependencies to avoid actual Claude API calls
vi.mock('../agent/agent-sdk.js', () => ({
  executeAgent: vi.fn(),
}));

vi.mock('../agent/compaction.js', () => ({
  shouldCompact: vi.fn().mockResolvedValue(false),
  generateSummary: vi.fn().mockResolvedValue(''),
}));

vi.mock('../agent/transcript-reader.js', () => ({
  readSessionTranscript: vi.fn().mockResolvedValue([]),
}));

function createApp() {
  const app = express();
  app.use(express.json());
  app.use('/agent', chatRouter);
  return app;
}

// Simple HTTP test helper using Node's built-in http
async function makeRequest(
  app: express.Express,
  method: 'GET' | 'POST',
  path: string,
  body?: Record<string, unknown>,
  headers?: Record<string, string>,
): Promise<{ status: number; body: unknown }> {
  return new Promise((resolve, reject) => {
    const server = app.listen(0, () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error('Failed to get server address'));
        return;
      }
      const port = addr.port;
      const url = `http://localhost:${port}${path}`;
      const options: RequestInit = {
        method,
        headers: {
          'Content-Type': 'application/json',
          ...headers,
        },
      };
      if (body) {
        options.body = JSON.stringify(body);
      }
      fetch(url, options)
        .then(async (res) => {
          const text = await res.text();
          let parsed: unknown;
          try {
            parsed = JSON.parse(text);
          } catch {
            parsed = text;
          }
          server.close();
          resolve({ status: res.status, body: parsed });
        })
        .catch((err) => {
          server.close();
          reject(err);
        });
    });
  });
}

describe('Chat routes — integration tests', () => {
  const VALID_TOKEN = 'test-internal-token';

  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  it('GET /agent/health should return 200 with status ok', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'GET', '/agent/health');

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('status', 'ok');
  });

  it('POST /agent/chat without auth should return 401', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'POST', '/agent/chat', {
      message: 'Hello',
      userId: 1,
    });

    expect(res.status).toBe(401);
  });

  it('POST /agent/chat with auth but missing message should return 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { userId: 1 },
      {
        Authorization: `Internal ${VALID_TOKEN}`,
      },
    );

    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  it('POST /agent/chat with auth but missing userId should return 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { message: 'Hello' },
      {
        Authorization: `Internal ${VALID_TOKEN}`,
      },
    );

    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });
});
