import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import classifyRouter from './classify.js';

vi.mock('../services/classification-service.js', () => ({
  classifyBatch: vi.fn(),
}));

import { classifyBatch } from '../services/classification-service.js';

const VALID_TOKEN = 'test-internal-token-12345';

function createApp() {
  const app = express();
  app.use(express.json());
  app.use('/agent', classifyRouter);
  return app;
}

async function makeRequest(
  app: express.Express,
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
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...headers,
        },
        body: body ? JSON.stringify(body) : undefined,
      };
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

const validBody = {
  rows: [
    { rowId: '1', text: 'This product is amazing!' },
    { rowId: '2', text: 'Terrible experience.' },
  ],
  labels: ['positive', 'neutral', 'negative'],
  promptTemplate:
    'Classify the following text into one of the allowed labels: {labels}. Text: {text}',
  promptVersion: 'v1',
};

const mockResponse = {
  results: [
    { rowId: '1', label: 'positive', confidence: 0.94, reason: 'customer expresses satisfaction' },
    {
      rowId: '2',
      label: 'negative',
      confidence: 0.91,
      reason: 'terrible indicates negative sentiment',
    },
  ],
  cached: 0,
  processed: 2,
  model: 'claude-haiku-4-5-20251001',
  usage: { promptTokens: 150, completionTokens: 80 },
};

describe('POST /agent/classify', () => {
  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    process.env.API_BASE_URL = 'http://localhost:8080/api/v1';
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
    delete process.env.API_BASE_URL;
  });

  it('should return 401 when Authorization header is missing', async () => {
    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody);
    expect(res.status).toBe(401);
  });

  it('should return 401 for invalid Internal token', async () => {
    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody, {
      Authorization: 'Internal wrong-token',
    });
    expect(res.status).toBe(401);
  });

  it('should return 400 when rows is empty', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      '/agent/classify',
      { ...validBody, rows: [] },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  it('should return 400 when labels has fewer than 2 items', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      '/agent/classify',
      { ...validBody, labels: ['positive'] },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  it('should return 400 when labels is missing', async () => {
    const app = createApp();
    const { labels: _labels, ...bodyWithoutLabels } = validBody;
    const res = await makeRequest(app, '/agent/classify', bodyWithoutLabels as Record<string, unknown>, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });
    expect(res.status).toBe(400);
  });

  it('should return 400 when promptTemplate is missing', async () => {
    const app = createApp();
    const { promptTemplate: _pt, ...bodyWithoutTemplate } = validBody;
    const res = await makeRequest(app, '/agent/classify', bodyWithoutTemplate as Record<string, unknown>, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });
    expect(res.status).toBe(400);
  });

  it('should return 400 when promptVersion is missing', async () => {
    const app = createApp();
    const { promptVersion: _pv, ...bodyWithoutVersion } = validBody;
    const res = await makeRequest(app, '/agent/classify', bodyWithoutVersion as Record<string, unknown>, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });
    expect(res.status).toBe(400);
  });

  it('should classify successfully with valid request', async () => {
    vi.mocked(classifyBatch).mockResolvedValue(mockResponse);

    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });

    expect(res.status).toBe(200);
    expect(res.body).toEqual(mockResponse);
    expect(classifyBatch).toHaveBeenCalledOnce();
    expect(classifyBatch).toHaveBeenCalledWith(
      {
        rows: validBody.rows,
        labels: validBody.labels,
        promptTemplate: validBody.promptTemplate,
        promptVersion: validBody.promptVersion,
      },
      'http://localhost:8080/api/v1',
      VALID_TOKEN,
    );
  });

  it('should return 500 when classification service throws', async () => {
    vi.mocked(classifyBatch).mockRejectedValue(new Error('AI API key is not configured'));

    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });

    expect(res.status).toBe(500);
    expect(res.body).toHaveProperty('error');
  });

  it('should accept rows with empty text', async () => {
    vi.mocked(classifyBatch).mockResolvedValue({
      ...mockResponse,
      results: [{ rowId: '1', label: 'neutral', confidence: 0.5 }],
      processed: 1,
    });

    const app = createApp();
    const res = await makeRequest(
      app,
      '/agent/classify',
      { ...validBody, rows: [{ rowId: '1', text: '' }] },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
  });
});
