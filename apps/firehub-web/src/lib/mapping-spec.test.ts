import { beforeEach, describe, expect, it } from 'vitest';

import type { MappingSpec } from '../types/mapping';
import {
  countRelationsReferencing,
  emptyDraft,
  nextDraftId,
  removeEntity,
  resetDraftIdCounter,
  toDraft,
  toSpec,
} from './mapping-spec';

/** 엔티티 3개 + 관계 2개. 관계는 인덱스로 엔티티를 가리킨다. */
function sampleSpec(): MappingSpec {
  return {
    entities: [
      { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
      { entityType: 'Building', nameColumn: 'building_name', properties: [] },
      { entityType: 'Damage', nameColumn: 'damage_name', properties: [{ column: 'damage_amount', propertyName: '피해액' }] },
    ],
    relations: [
      { subjectRef: 0, relation: 'OCCURRED_AT', objectRef: 1 },
      { subjectRef: 0, relation: 'RESULTED_IN', objectRef: 2 },
    ],
  };
}

describe('mapping-spec', () => {
  beforeEach(() => {
    resetDraftIdCounter();
  });

  it('emptyDraft는 비어 있는 draft를 만든다', () => {
    expect(emptyDraft()).toEqual({ entities: [], relations: [] });
  });

  it('nextDraftId는 접두사별로 고유한 ID를 발급한다', () => {
    const ids = [nextDraftId('e'), nextDraftId('e'), nextDraftId('r')];
    expect(new Set(ids).size).toBe(3);
    expect(ids[0]).toMatch(/^e/);
    expect(ids[2]).toMatch(/^r/);
  });

  it('toDraft는 엔티티에 고유 ID를 부여하고 관계의 인덱스 참조를 ID 참조로 바꾼다', () => {
    const draft = toDraft(sampleSpec());

    expect(draft.entities).toHaveLength(3);
    expect(new Set(draft.entities.map((e) => e.id)).size).toBe(3);
    expect(draft.relations).toHaveLength(2);
    expect(draft.relations[0].subjectId).toBe(draft.entities[0].id);
    expect(draft.relations[0].objectId).toBe(draft.entities[1].id);
    expect(draft.relations[1].objectId).toBe(draft.entities[2].id);
    // 속성은 그대로 보존된다
    expect(draft.entities[2].properties).toEqual([{ column: 'damage_amount', propertyName: '피해액' }]);
  });

  it('toSpec(toDraft(spec))는 원본 spec과 동일하다 (왕복 변환)', () => {
    const spec = sampleSpec();
    expect(toSpec(toDraft(spec))).toEqual(spec);
  });

  it('toDraft는 범위를 벗어난 참조를 가진 관계를 버린다', () => {
    const broken: MappingSpec = {
      entities: [{ entityType: 'Incident', nameColumn: 'incident_name', properties: [] }],
      relations: [
        { subjectRef: 0, relation: 'OCCURRED_AT', objectRef: 5 },
        { subjectRef: -1, relation: 'CAUSED_BY', objectRef: 0 },
      ],
    };
    expect(toDraft(broken).relations).toEqual([]);
  });

  it('countRelationsReferencing은 해당 엔티티를 주어 또는 목적어로 쓰는 관계 수를 센다', () => {
    const draft = toDraft(sampleSpec());
    expect(countRelationsReferencing(draft, draft.entities[0].id)).toBe(2);
    expect(countRelationsReferencing(draft, draft.entities[1].id)).toBe(1);
  });

  it('removeEntity는 엔티티와 이를 참조하는 관계를 함께 제거하고, 남은 관계는 재계산된 인덱스로 직렬화된다', () => {
    const draft = toDraft(sampleSpec());
    // 인덱스 0(Incident)이 아니라 인덱스 1(Building)을 지운다 → 남은 관계는 Incident→Damage 하나.
    const next = removeEntity(draft, draft.entities[1].id);

    expect(next.entities.map((e) => e.entityType)).toEqual(['Incident', 'Damage']);
    expect(next.relations).toHaveLength(1);

    // Damage는 이제 인덱스 2가 아니라 1이다.
    expect(toSpec(next)).toEqual({
      entities: [
        { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
        { entityType: 'Damage', nameColumn: 'damage_name', properties: [{ column: 'damage_amount', propertyName: '피해액' }] },
      ],
      relations: [{ subjectRef: 0, relation: 'RESULTED_IN', objectRef: 1 }],
    });
  });

  it('removeEntity는 원본 draft를 변경하지 않는다', () => {
    const draft = toDraft(sampleSpec());
    const before = JSON.stringify(draft);
    removeEntity(draft, draft.entities[0].id);
    expect(JSON.stringify(draft)).toBe(before);
  });

  it('toSpec은 존재하지 않는 엔티티를 가리키는 관계를 버린다', () => {
    const draft = toDraft(sampleSpec());
    draft.relations.push({ id: 'r-ghost', subjectId: draft.entities[0].id, relation: 'CAUSED_BY', objectId: 'e-ghost' });
    expect(toSpec(draft).relations).toHaveLength(2);
  });
});
