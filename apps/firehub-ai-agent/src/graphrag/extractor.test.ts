import { describe, it, expect, afterEach } from 'vitest';
import nock from 'nock';
import { extractGraph } from './extractor.js';

const OPTS = { model: 'claude-haiku-4-5', apiKey: 'test-key', anthropicBaseUrl: 'https://api.anthropic.com' };

function mockAnthropic(jsonPayload: string) {
  return nock('https://api.anthropic.com')
    .post('/v1/messages')
    .reply(200, { content: [{ type: 'text', text: '```json\n' + jsonPayload + '\n```' }] });
}

afterEach(() => nock.cleanAll());

describe('extractGraph', () => {
  it('온톨로지 유효 엔티티/관계만 반환하고 무효분은 폐기한다', async () => {
    mockAnthropic(JSON.stringify({
      entities: [
        { type: 'Incident', name: '2026-001' },
        { type: 'Cause', name: '전기적 요인' },
        { type: 'Person', name: '홍길동' },          // 온톨로지 밖 → 폐기
      ],
      relations: [
        { subject: '2026-001', type: 'CAUSED_BY', object: '전기적 요인' }, // 유효
        { subject: '전기적 요인', type: 'CAUSED_BY', object: '2026-001' }, // 방향 위반 → 폐기
      ],
    }));
    const result = await extractGraph('화재 보고서 본문...', OPTS);
    expect(result.entities).toEqual([
      { type: 'Incident', name: '2026-001' },
      { type: 'Cause', name: '전기적 요인' },
    ]);
    expect(result.relations).toEqual([
      { subject: '2026-001', type: 'CAUSED_BY', object: '전기적 요인' },
    ]);
  });

  it('깨진 JSON이면 빈 결과를 반환한다(배치 중단 없이)', async () => {
    nock('https://api.anthropic.com').post('/v1/messages')
      .reply(200, { content: [{ type: 'text', text: 'no json here' }] });
    const result = await extractGraph('본문', OPTS);
    expect(result).toEqual({ entities: [], relations: [] });
  });
});
