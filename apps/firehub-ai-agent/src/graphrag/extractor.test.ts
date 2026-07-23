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

  // D.2 회귀: 관리자가 지식 모델(DB)에 새 타입을 추가하면, 하드코딩 ENTITY_TYPES/RELATION_TYPES에
  // 없어도(Witness/TESTIFIED_BY는 CORE_ONTOLOGY 번들에 없음) 로드된 ontology 인자만 보고 통과해야 한다.
  it('DB에서 추가된(번들 상수엔 없는) 새 타입도 로드된 ontology에 있으면 폐기하지 않는다', async () => {
    const extendedOntology = {
      ...CORE_ONTOLOGY,
      entities: [
        ...CORE_ONTOLOGY.entities,
        { type: 'Witness', description: '목격자', naming: '본문 표기 보존', resolution: 'embedding' as const },
      ],
      relations: [
        ...CORE_ONTOLOGY.relations,
        { subject: 'Incident', relation: 'TESTIFIED_BY', object: 'Witness', description: '사건을 증언한 목격자' },
      ],
    };
    const jsonPayload = JSON.stringify({
      entities: [
        { type: 'Incident', name: '2026-001' },
        { type: 'Witness', name: '김철수' },
      ],
      relations: [{ subject: '2026-001', type: 'TESTIFIED_BY', object: '김철수' }],
    });
    const complete: CompleteFn = vi.fn().mockResolvedValue('```json\n' + jsonPayload + '\n```');

    const result = await extractGraph('화재 보고서 본문...', { complete, ontology: extendedOntology });

    expect(result.entities).toEqual([
      { type: 'Incident', name: '2026-001' },
      { type: 'Witness', name: '김철수' },
    ]);
    expect(result.relations).toEqual([
      { subject: '2026-001', type: 'TESTIFIED_BY', object: '김철수' },
    ]);
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
