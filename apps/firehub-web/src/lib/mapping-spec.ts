import type { MappingSpec } from '../types/mapping';

/**
 * 매핑 편집 모델.
 *
 * 백엔드 MappingSpec의 관계는 entities 배열의 **0-based 인덱스**로 끝점을 가리킨다.
 * 이 인덱스를 UI 상태로 직접 들고 있으면 엔티티를 삭제·재배치하는 순간 모든 관계가
 * 조용히 다른 엔티티를 가리키게 된다. 그래서 편집 중에는 클라이언트 전용 **안정 ID**로
 * 끝점을 참조하고, 서버로 보내기 직전(toSpec)에만 인덱스로 되돌린다.
 * ID는 절대 서버로 전송되지 않는다.
 */

export interface DraftProperty { column: string; propertyName: string; }
export interface DraftEntity { id: string; entityType: string; nameColumn: string; properties: DraftProperty[]; }
export interface DraftRelation { id: string; subjectId: string; relation: string; objectId: string; }
export interface MappingDraft { entities: DraftEntity[]; relations: DraftRelation[]; }

// 세션 내 단조 증가 카운터. 값 자체에 의미는 없고 "겹치지 않음"만 보장하면 된다.
let idCounter = 0;

/** 편집 모델 전용 고유 ID를 발급한다. prefix는 가독성을 위한 것일 뿐 의미는 없다. */
export function nextDraftId(prefix: 'e' | 'r'): string {
  idCounter += 1;
  return `${prefix}${idCounter}`;
}

/** 테스트 전용 — ID 카운터를 초기화해 테스트 간 독립성을 보장한다. */
export function resetDraftIdCounter(): void {
  idCounter = 0;
}

/** 매핑이 아직 없는 데이터셋의 초기 편집 상태. */
export function emptyDraft(): MappingDraft {
  return { entities: [], relations: [] };
}

/**
 * 서버 spec → 편집 모델.
 * 각 엔티티에 안정 ID를 부여하고, 관계의 인덱스 참조를 그 ID로 바꾼다.
 * 범위를 벗어난 참조(서버 데이터가 깨졌거나 온톨로지가 바뀐 경우)는 버린다 —
 * 편집기가 존재하지 않는 끝점을 렌더할 방법이 없기 때문이다.
 */
export function toDraft(spec: MappingSpec): MappingDraft {
  const entities: DraftEntity[] = spec.entities.map((e) => ({
    id: nextDraftId('e'),
    entityType: e.entityType,
    nameColumn: e.nameColumn,
    properties: e.properties.map((p) => ({ column: p.column, propertyName: p.propertyName })),
  }));

  const inRange = (ref: number) => Number.isInteger(ref) && ref >= 0 && ref < entities.length;

  const relations: DraftRelation[] = spec.relations
    .filter((r) => inRange(r.subjectRef) && inRange(r.objectRef))
    .map((r) => ({
      id: nextDraftId('r'),
      subjectId: entities[r.subjectRef].id,
      relation: r.relation,
      objectId: entities[r.objectRef].id,
    }));

  return { entities, relations };
}

/**
 * 편집 모델 → 서버 spec.
 * ID를 **현재 배열 위치**로 되돌린다. 엔티티가 삭제·추가됐어도 여기서 인덱스가 새로 계산되므로
 * 참조가 어긋나지 않는다. 끝점을 찾을 수 없는 관계는 버린다(정상 흐름에서는 발생하지 않는다).
 */
export function toSpec(draft: MappingDraft): MappingSpec {
  const indexById = new Map<string, number>();
  draft.entities.forEach((e, i) => indexById.set(e.id, i));

  return {
    entities: draft.entities.map((e) => ({
      entityType: e.entityType,
      nameColumn: e.nameColumn,
      properties: e.properties.map((p) => ({ column: p.column, propertyName: p.propertyName })),
    })),
    relations: draft.relations
      .filter((r) => indexById.has(r.subjectId) && indexById.has(r.objectId))
      .map((r) => ({
        subjectRef: indexById.get(r.subjectId) as number,
        relation: r.relation,
        objectRef: indexById.get(r.objectId) as number,
      })),
  };
}

/**
 * 엔티티를 사람이 읽는 한 줄 라벨로 만든다.
 * 표·선택 드롭다운·삭제 확인 문구가 모두 같은 표기를 써야 사용자가 같은 대상임을 알아보므로,
 * 문자열 조립을 이 한 곳으로 모은다. 끝점을 찾지 못했을 때의 폴백은 조회 실패를 아는 호출부가 처리한다.
 */
export function entityLabel(entity: DraftEntity): string {
  return `${entity.entityType} (${entity.nameColumn})`;
}

/** 해당 엔티티를 주어 또는 목적어로 쓰는 관계 수. 삭제 확인 문구에 쓴다. */
export function countRelationsReferencing(draft: MappingDraft, entityId: string): number {
  return draft.relations.filter((r) => r.subjectId === entityId || r.objectId === entityId).length;
}

/**
 * 엔티티를 삭제한다. 그 엔티티를 참조하는 관계도 함께 삭제한다 —
 * 남겨두면 저장 시 조용히 사라지므로, 사용자에게 미리 고지한 뒤 여기서 함께 지운다.
 * 원본 draft는 변경하지 않는다(불변 갱신).
 */
export function removeEntity(draft: MappingDraft, entityId: string): MappingDraft {
  return {
    entities: draft.entities.filter((e) => e.id !== entityId),
    relations: draft.relations.filter((r) => r.subjectId !== entityId && r.objectId !== entityId),
  };
}
