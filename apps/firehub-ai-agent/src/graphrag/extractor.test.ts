import { describe, it, expect, vi } from 'vitest';
import { extractGraph } from './extractor.js';
import { CORE_ONTOLOGY } from './ontology.js';
import type { CompleteFn } from './llm-cli.js';

describe('extractGraph', () => {
  it('온톨로지 유효 엔티티/관계만 반환하고 무효분은 폐기한다', async () => {
    const jsonPayload = JSON.stringify({
      entities: [
        { type: 'Incident', name: '2026-001' },
        { type: 'Cause', name: '전기적 요인' },
        { type: 'Person', name: '홍길동' },          // 온톨로지 밖 → 폐기
      ],
      relations: [
        { subject: '2026-001', type: 'CAUSED_BY', object: '전기적 요인' }, // 유효
        { subject: '전기적 요인', type: 'CAUSED_BY', object: '2026-001' }, // 방향 위반 → 폐기
      ],
    });
    const complete: CompleteFn = vi.fn().mockResolvedValue('```json\n' + jsonPayload + '\n```');

    const result = await extractGraph('화재 보고서 본문...', { complete, ontology: CORE_ONTOLOGY });
    expect(result.entities).toEqual([
      { type: 'Incident', name: '2026-001' },
      { type: 'Cause', name: '전기적 요인' },
    ]);
    expect(result.relations).toEqual([
      { subject: '2026-001', type: 'CAUSED_BY', object: '전기적 요인' },
    ]);
  });

  it('깨진 JSON이면 빈 결과를 반환한다(배치 중단 없이)', async () => {
    const complete: CompleteFn = vi.fn().mockResolvedValue('no json here');
    const result = await extractGraph('본문', { complete, ontology: CORE_ONTOLOGY });
    expect(result).toEqual({ entities: [], relations: [] });
  });

  it('complete가 예외를 던지면 빈 결과를 반환한다(fail-soft)', async () => {
    const complete: CompleteFn = vi.fn().mockRejectedValue(new Error('claude CLI 실패'));
    const result = await extractGraph('본문', { complete, ontology: CORE_ONTOLOGY });
    expect(result).toEqual({ entities: [], relations: [] });
  });
});

describe('extractGraph 속성 추출', () => {
  it('정의된 속성은 정규화, 미정의 키는 폐기', async () => {
    const complete: CompleteFn = async () => JSON.stringify({
      entities: [{ type: 'Incident', name: '2024 서울 창고 화재', properties: { 피해액: '약 1억 2천만원', 헛것: 'x' } }],
      relations: [],
    });
    const res = await extractGraph('...', { complete, ontology: CORE_ONTOLOGY });
    expect(res.entities[0].properties).toEqual({ 피해액: 120_000_000 });
  });

  it('정규화 실패 속성은 제외', async () => {
    const complete: CompleteFn = async () => JSON.stringify({
      entities: [{ type: 'Incident', name: 'X', properties: { 피해액: '큼' } }], relations: [],
    });
    const res = await extractGraph('...', { complete, ontology: CORE_ONTOLOGY });
    expect(res.entities[0].properties).toBeUndefined();
  });
});
