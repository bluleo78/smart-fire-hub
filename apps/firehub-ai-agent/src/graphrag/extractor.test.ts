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
        { id: 999, type: 'Witness', description: '목격자', naming: '본문 표기 보존', resolution: 'embedding' as const },
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

  it('정규화 실패 속성을 propertyReviewCandidates로 수집한다', async () => {
    // complete를 목킹해 Incident.피해액에 파싱 불가한 원문("수천만원대")을 반환하도록 한다.
    const complete: CompleteFn = vi.fn().mockResolvedValue(JSON.stringify({
      entities: [{ type: 'Incident', name: '창고 화재', properties: { 피해액: '수천만원대' } }],
      relations: [],
    }));
    const result = await extractGraph('창고 화재 원문', { complete, ontology: CORE_ONTOLOGY });
    expect(result.propertyReviewCandidates).toEqual([
      { entityType: 'Incident', entityName: '창고 화재', propertyName: '피해액', dataType: 'number', rawText: '수천만원대' },
    ]);
    // 파싱 실패값은 노드 properties에는 실리지 않는다(기존 동작 유지).
    expect(result.entities[0].properties?.피해액).toBeUndefined();
  });

  it('엔티티 confidence/reason을 통과시키고 범위 밖 confidence는 버린다', async () => {
    const complete = async () => '```json\n{"entities":[' +
      '{"type":"Cause","name":"누전","confidence":0.3,"reason":"추론"},' +
      '{"type":"Cause","name":"과부하","confidence":9}' + // 범위 밖 → 미신고
      '],"relations":[]}\n```';
    const res = await extractGraph('본문', { complete, ontology: CORE_ONTOLOGY });
    const 누전 = res.entities.find((e) => e.name === '누전')!;
    const 과부하 = res.entities.find((e) => e.name === '과부하')!;
    expect(누전.confidence).toBe(0.3);
    expect(누전.reason).toBe('추론');
    expect(과부하.confidence).toBeUndefined();
  });

  it('관계 confidence/reason을 통과시키고 범위 밖 confidence는 버린다', async () => {
    // CORE_ONTOLOGY의 허용 트리플은 Incident -CAUSED_BY-> Cause 방향만 존재하므로
    // 두 관계 모두 subject=Incident 타입, object=Cause 타입으로 구성한다.
    const complete = async () => '```json\n{"entities":[' +
      '{"type":"Incident","name":"누전"},{"type":"Incident","name":"합선"},{"type":"Cause","name":"과부하"}' +
      '],"relations":[' +
      '{"subject":"누전","type":"CAUSED_BY","object":"과부하","confidence":0.3,"reason":"추론"},' +
      '{"subject":"합선","type":"CAUSED_BY","object":"과부하","confidence":9}' +
      ']}\n```';
    const res = await extractGraph('본문', { complete, ontology: CORE_ONTOLOGY });
    const r1 = res.relations.find((r) => r.subject === '누전')!;
    const r2 = res.relations.find((r) => r.subject === '합선')!;
    expect(r1.confidence).toBe(0.3);
    expect(r1.reason).toBe('추론');
    expect(r2.confidence).toBeUndefined();
  });
});
