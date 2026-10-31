import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import templateGenerateRouter, {
  parseSectionsFromText,
  TEMPLATE_GENERATE_TIMEOUT_MS,
} from './template-generate.js';

// 단발 completion provider 를 목으로 바꿔 LLM 없이 라우트의 입력 검증·응답 파싱·오류 매핑만 검증한다(WD-455).
const mockComplete = vi.fn();
const mockCreateCompletionProvider = vi.fn(() => ({ name: 'mock-completion', complete: mockComplete }));

vi.mock('../providers/index.js', () => ({
  ProviderFactory: {
    createCompletionProvider: (...args: unknown[]) => mockCreateCompletionProvider(...(args as [])),
  },
}));

const VALID_TOKEN = 'test-internal-token-12345';
const PATH = '/agent/template-sections/generate';

function createApp() {
  const app = express();
  app.use('/agent', templateGenerateRouter);
  return app;
}

/** 임시 포트로 띄워 한 번 호출하고 상태·본문을 돌려준다(classify.test 와 같은 방식). */
async function post(body: unknown, auth = true): Promise<{ status: number; body: Record<string, unknown> }> {
  const app = createApp();
  return new Promise((resolve, reject) => {
    const server = app.listen(0, () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error('no address'));
        return;
      }
      fetch(`http://localhost:${addr.port}${PATH}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(auth ? { Authorization: `Internal ${VALID_TOKEN}` } : {}),
        },
        body: JSON.stringify(body),
      })
        .then(async (res) => {
          const text = await res.text();
          server.close();
          resolve({ status: res.status, body: text ? JSON.parse(text) : {} });
        })
        .catch((err) => {
          server.close();
          reject(err);
        });
    });
  });
}

const validBody = { question: '이번 달 매출 하락 원인', agentType: 'sdk', apiKey: 'sk-test', model: 'claude-x' };
const sections = [
  { key: 'summary', type: 'text', label: '요약', instruction: '요약하세요', required: true },
  { key: 'sales_trend', type: 'chart', label: '매출 추이', instruction: '추이를 설명하세요' },
];

describe('POST /agent/template-sections/generate', () => {
  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    vi.clearAllMocks();
  });
  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  it('Internal 토큰이 없으면 401', async () => {
    const res = await post(validBody, false);
    expect(res.status).toBe(401);
  });

  it('질문이 비면 400', async () => {
    const res = await post({ ...validBody, question: '   ' });
    expect(res.status).toBe(400);
    expect(mockComplete).not.toHaveBeenCalled();
  });

  it('agentType 이 없으면 400 — 조용히 sdk 로 취급하지 않는다(6b1c6383)', async () => {
    const { agentType: _a, ...rest } = validBody;
    const res = await post(rest);
    expect(res.status).toBe(400);
  });

  it('LLM 이 JSON 배열을 주면 sections 로 돌려주고, 자격증명·타임아웃을 provider 에 넘긴다', async () => {
    mockComplete.mockResolvedValue({ text: JSON.stringify(sections) });
    const res = await post(validBody);
    expect(res.status).toBe(200);
    expect(res.body.sections).toEqual(sections);
    expect(mockCreateCompletionProvider).toHaveBeenCalledWith(
      expect.objectContaining({ agentType: 'sdk', apiKey: 'sk-test', model: 'claude-x' }),
    );
    const [, userText, options] = mockComplete.mock.calls[0];
    expect(userText).toContain('이번 달 매출 하락 원인');
    expect(options).toMatchObject({ timeoutMs: TEMPLATE_GENERATE_TIMEOUT_MS, systemPromptMode: 'replace' });
  });

  it('코드 블록으로 감싼 JSON 도 읽는다', async () => {
    mockComplete.mockResolvedValue({ text: '구성입니다.\n```json\n' + JSON.stringify(sections) + '\n```' });
    const res = await post(validBody);
    expect(res.status).toBe(200);
    expect(res.body.sections).toEqual(sections);
  });

  it('JSON 으로 읽을 수 없는 응답은 502 TEMPLATE_OUTPUT_INVALID', async () => {
    mockComplete.mockResolvedValue({ text: '죄송합니다. 만들 수 없습니다.' });
    const res = await post(validBody);
    expect(res.status).toBe(502);
    expect(res.body.code).toBe('TEMPLATE_OUTPUT_INVALID');
  });

  it('provider 가 실패하면 502 TEMPLATE_GENERATION_FAILED — 원문은 응답에 싣지 않는다', async () => {
    mockComplete.mockRejectedValue(new Error('secret upstream detail'));
    const res = await post(validBody);
    expect(res.status).toBe(502);
    expect(res.body.code).toBe('TEMPLATE_GENERATION_FAILED');
    expect(JSON.stringify(res.body)).not.toContain('secret upstream detail');
  });

  it('인증/쿼터 실패 코드는 그대로 전달한다', async () => {
    mockComplete.mockRejectedValue(Object.assign(new Error('auth'), { code: 'AGENT_AUTH_OR_QUOTA_FAILURE' }));
    const res = await post(validBody);
    expect(res.status).toBe(502);
    expect(res.body.code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
  });
});

describe('parseSectionsFromText', () => {
  it('{"sections":[...]} 형태도 받는다', () => {
    expect(parseSectionsFromText(JSON.stringify({ sections }))).toEqual(sections);
  });

  it('빈 배열·객체가 아닌 원소는 null', () => {
    expect(parseSectionsFromText('[]')).toBeNull();
    expect(parseSectionsFromText('["a","b"]')).toBeNull();
    expect(parseSectionsFromText('not json')).toBeNull();
  });
});
