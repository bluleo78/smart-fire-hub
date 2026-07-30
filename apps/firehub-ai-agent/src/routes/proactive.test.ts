import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import proactiveRouter, {
  buildSectionPrompt,
  parseSections,
  detectAgentFailure,
} from './proactive.js';

const mockExecute = vi.hoisted(() => vi.fn());

vi.mock('../providers/index.js', () => ({
  ProviderFactory: {
    createChatProvider: vi.fn(() => ({
      execute: mockExecute,
    })),
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

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: rawText };
        yield { type: 'done', inputTokens: 100, outputTokens: 200 };
      })(),
    );

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

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: freeText };
        yield { type: 'done', inputTokens: 50, outputTokens: 80 };
      })(),
    );

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

  // 이슈 #350: 에이전트가 인증 실패를 일반 텍스트로 흘리고 정상 종료(done)하는 실제 시퀀스.
  // 이 경우 200으로 응답하면 백엔드가 COMPLETED로 기록하고 오류 원문을 리포트 본문 삼아
  // CHAT/EMAIL로 발송한다. 리포트 파일도 없으므로 502로 실패 처리되어야 한다.
  it('TC5: 인증 실패 텍스트가 done으로 종료되면 502로 실패 처리한다 (#350)', async () => {
    const authError =
      'Failed to authenticate. API Error: 401 {"type":"error","error":{"type":"authentication_error","message":"Invalid bearer token"},"request_id":"req_011CdYN4tsktnijbWhnP2ZCV"}';

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: authError };
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    const body = res.body as { error: string; code: string };
    expect(body.code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
    // 오류 원문·request_id가 응답에 실려 나가면 안 된다 (로그에만 남긴다)
    expect(JSON.stringify(res.body)).not.toContain('request_id');
    expect(JSON.stringify(res.body)).not.toContain('Invalid bearer token');
  });

  it('TC6: 리포트 파일도 없고 출력 텍스트도 비면 502로 실패 처리한다 (#350)', async () => {
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    expect((res.body as { code: string }).code).toBe('AGENT_EMPTY_OUTPUT');
  });

  // 회귀 방지(negative control): 장애를 *서술*하는 정상 리포트는 본문에 오류 문구가 들어 있어도
  // 성공으로 처리되어야 한다. 실패 판정을 본문 선두 200자로 한정한 이유가 이것이다.
  it('TC7: 본문 중간에 오류 문구를 인용한 정상 리포트는 200을 유지한다 (#350 회귀 방지)', async () => {
    const report =
      '## 일간 운영 리포트\n어제 수집된 데이터를 분석한 결과입니다.\n\n' +
      '## 장애 내역\n외부 API 연결에서 반복 실패가 관측되었습니다. ' +
      '수집 로그에 API Error: 401 authentication_error 가 기록되어 연결 자격 증명 갱신이 필요합니다.\n';

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: report };
        yield { type: 'done', inputTokens: 120, outputTokens: 300 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '운영 리포트', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect((res.body as { rawText: string }).rawText).toBe(report);
  });
});

describe('detectAgentFailure', () => {
  it('선두의 인증 실패 문구를 탐지한다', () => {
    expect(
      detectAgentFailure('Failed to authenticate. API Error: 401 {"type":"error"}'),
    ).toBe('failed to authenticate');
    expect(detectAgentFailure('Credit balance is too low')).toBe('credit balance is too low');
    // 라이브 검증에서 실측된 SDK 문구 (잘못된 API 키)
    expect(detectAgentFailure('Invalid API key · Fix external API key')).toBe('invalid api key');
  });

  it('선두가 아닌 위치의 오류 문구는 탐지하지 않는다 (장애를 서술하는 정상 리포트 보호)', () => {
    expect(
      detectAgentFailure('## 장애 내역\n수집 로그에 API Error: 401 이 기록되었습니다.'),
    ).toBeNull();
    expect(detectAgentFailure('요약: 인증 오류로 Failed to authenticate 로그 발생')).toBeNull();
  });

  it('빈 문자열은 null을 반환한다', () => {
    expect(detectAgentFailure('   ')).toBeNull();
  });
});

describe('buildSectionPrompt', () => {
  it('should include instruction in section prompt', () => {
    const sections = [
      { key: 'summary', label: '요약', type: 'text', instruction: '핵심 지표를 요약하세요.' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('지시: 핵심 지표를 요약하세요.');
    expect(result).toContain('## 요약');
  });

  it('should skip static sections with note', () => {
    const sections = [
      { key: 'disclaimer', label: '면책조항', type: 'text', static: true, content: '고정 텍스트' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('정적 섹션');
    expect(result).not.toContain('고정 텍스트');
  });

  it('should handle nested group sections with correct header depth', () => {
    const sections = [
      {
        key: 'ops', label: '운영 현황', type: 'group',
        instruction: '시스템 운영 상태를 분석하세요.',
        children: [
          { key: 'kpi', label: '핵심 지표', type: 'cards', instruction: 'KPI 카드를 표시하세요.' },
        ],
      },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('## 운영 현황');
    expect(result).toContain('### 핵심 지표');
    expect(result).toContain('지시: 시스템 운영 상태를 분석하세요.');
    expect(result).toContain('지시: KPI 카드를 표시하세요.');
  });

  it('should skip divider sections entirely', () => {
    const sections = [
      { key: 'div1', label: '구분선', type: 'divider' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toBe('');
  });

  it('should include type guide for non-group sections', () => {
    const sections = [
      { key: 'cards1', label: '지표', type: 'cards' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('카드 형식으로 출력합니다');
  });
});

describe('parseSections', () => {
  it('should parse flat sections from AI response', () => {
    const text = '## 요약\n내용입니다.\n\n## 상세\n상세 내용.';
    const template = {
      sections: [
        { key: 'summary', label: '요약', type: 'text' },
        { key: 'detail', label: '상세', type: 'text' },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result).toHaveLength(2);
    expect(result[0].key).toBe('summary');
    expect(result[0].content).toContain('내용입니다');
  });

  it('should skip static sections in parsing', () => {
    const text = '## 요약\n내용입니다.';
    const template = {
      sections: [
        { key: 'disclaimer', label: '면책조항', type: 'text', static: true },
        { key: 'summary', label: '요약', type: 'text' },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('summary');
  });

  it('should flatten group children in output', () => {
    const text = '## 운영 현황\n\n### 핵심 지표\nKPI 내용';
    const template = {
      sections: [
        {
          key: 'ops', label: '운영 현황', type: 'group',
          children: [
            { key: 'kpi', label: '핵심 지표', type: 'text' },
          ],
        },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('kpi');
    expect(result[0].content).toContain('KPI 내용');
  });

  it('should return single section when no template', () => {
    const result = parseSections('some text');
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('content');
  });

  it('should extract cards JSON data', () => {
    const text = '## 지표\n설명\n```json\n[{"title":"A","value":"1","description":"d"}]\n```';
    const template = {
      sections: [{ key: 'stats', label: '지표', type: 'cards' }],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result[0].data).toEqual([{ title: 'A', value: '1', description: 'd' }]);
  });
});
