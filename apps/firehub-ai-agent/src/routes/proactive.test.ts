import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import proactiveRouter from './proactive.js';

const mockAxiosPost = vi.hoisted(() => vi.fn());
const mockAxiosInstance = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
  interceptors: {
    request: { use: vi.fn() },
    response: { use: vi.fn() },
  },
}));

vi.mock('axios', () => ({
  default: {
    post: mockAxiosPost,
    create: vi.fn(() => mockAxiosInstance),
  },
}));

function createApp() {
  const app = express();
  app.use(express.json());
  app.use('/agent', proactiveRouter);
  return app;
}

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

describe('Proactive routes — integration tests', () => {
  const VALID_TOKEN = 'test-internal-token';

  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    process.env.ANTHROPIC_API_KEY = 'test-api-key';
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
    delete process.env.ANTHROPIC_API_KEY;
  });

  it('TC1: POST /agent/proactive without auth should return 401', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'POST', '/agent/proactive', {
      prompt: 'Analyze this',
      context: { data: 'test' },
    });

    expect(res.status).toBe(401);
  });

  it('TC2: POST /agent/proactive without prompt should return 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { context: { data: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  it('TC3: POST /agent/proactive with template returns structured 3-section response with cards', async () => {
    const cardsData = [
      { title: '카드1', value: '100', description: '설명1' },
      { title: '카드2', value: '200', description: '설명2' },
    ];
    const rawText =
      '## 요약\n요약 내용입니다.\n\n## 통계\n통계 내용입니다.\n\n## 주요 지표\n카드 설명입니다.\n```json\n' +
      JSON.stringify(cardsData) +
      '\n```\n';

    mockAxiosPost.mockResolvedValue({
      data: {
        content: [{ type: 'text', text: rawText }],
        usage: { input_tokens: 100, output_tokens: 200 },
      },
    });

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      {
        prompt: '데이터를 분석해주세요',
        context: { metric: 42 },
        template: {
          sections: [
            { key: 'summary', label: '요약', required: true },
            { key: 'stats', label: '통계', required: true },
            { key: 'cards', label: '주요 지표', required: true, type: 'cards' },
          ],
          output_format: 'structured',
        },
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    const body = res.body as {
      sections: Array<{ key: string; label: string; content: string; data?: unknown }>;
      rawText: string;
      usage: { inputTokens: number; outputTokens: number };
    };
    expect(body.sections).toHaveLength(3);
    expect(body.sections[0].key).toBe('summary');
    expect(body.sections[1].key).toBe('stats');
    expect(body.sections[2].key).toBe('cards');
    expect(body.sections[2].data).toEqual(cardsData);
    expect(body.rawText).toBe(rawText);
    expect(body.usage.inputTokens).toBe(100);
    expect(body.usage.outputTokens).toBe(200);
  });

  it('TC4: POST /agent/proactive without template returns free-form response', async () => {
    const freeText = '자유 형식의 분석 결과입니다. 데이터를 바탕으로 인사이트를 제공합니다.';

    mockAxiosPost.mockResolvedValue({
      data: {
        content: [{ type: 'text', text: freeText }],
        usage: { input_tokens: 50, output_tokens: 80 },
      },
    });

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      {
        prompt: '간단히 분석해주세요',
        context: { value: 'test' },
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    const body = res.body as {
      sections: Array<{ key: string; label: string; content: string }>;
      rawText: string;
      usage: { inputTokens: number; outputTokens: number };
    };
    expect(body.sections).toHaveLength(1);
    expect(body.sections[0].key).toBe('content');
    expect(body.sections[0].content).toBe(freeText);
    expect(body.rawText).toBe(freeText);
    expect(body.usage.inputTokens).toBe(50);
    expect(body.usage.outputTokens).toBe(80);
  });
});
