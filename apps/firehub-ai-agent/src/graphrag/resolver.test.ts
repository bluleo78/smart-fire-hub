import { describe, it, expect } from 'vitest';
import { normalizeName, entityKey, resolveExtraction } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

// 5-6: entityKey는 이제 typeId(entity_type_id) 기반이다. 테스트는 CORE_ONTOLOGY의 고정 id를
// entityTypeId(ontology, type)로 조회해 사용 — 매직넘버 대신 실제 온톨로지 정의와 동기화된다.
const buildingId = entityTypeId(CORE_ONTOLOGY, 'Building');
const incidentId = entityTypeId(CORE_ONTOLOGY, 'Incident');

describe('resolver', () => {
  it('정규화는 공백/대소문자 변형을 하나로 만든다', () => {
    expect(normalizeName('  중앙로   상가 ')).toBe('중앙로 상가');
    expect(entityKey(buildingId, '중앙로  상가')).toBe(entityKey(buildingId, '중앙로 상가'));
  });
  it('정규화 변형 엔티티를 하나로 병합하고 관계를 키로 재작성한다', () => {
    const g = resolveExtraction({
      entities: [
        { type: 'Building', name: '중앙로 상가' },
        { type: 'Building', name: '중앙로  상가' },   // 공백 변형 → 병합
        { type: 'Incident', name: '2026-001' },
      ],
      relations: [
        { subject: '2026-001', type: 'OCCURRED_AT', object: '중앙로  상가' },
      ],
    }, CORE_ONTOLOGY);
    expect(g.entities).toHaveLength(2); // Building 1 + Incident 1
    expect(g.relations).toEqual([
      { subjectKey: entityKey(incidentId, '2026-001'), type: 'OCCURRED_AT', objectKey: entityKey(buildingId, '중앙로 상가') },
    ]);
  });

  it('속성값을 ResolvedEntity 로 전달', () => {
    const g = resolveExtraction({
      entities: [{ type: 'Incident', name: 'X', properties: { 피해액: 120_000_000 } }], relations: [],
    }, CORE_ONTOLOGY);
    expect(g.entities[0].properties).toEqual({ 피해액: 120_000_000 });
  });
});
