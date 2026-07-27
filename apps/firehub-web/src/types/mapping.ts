// 데이터셋 매핑 계약 타입 — firehub-api /api/v1/datasets/{id}/mapping 응답과 1:1.
// 슬라이스 1(B)에서 정의된 MappingSpec JSONB 문서 구조를 그대로 미러링한다.

/** 컬럼 → 엔티티 속성 매핑 1건. */
export interface PropertyMapping { column: string; propertyName: string; }

/** 표의 한 행에서 만들어질 엔티티 1종. nameColumn 값이 노드 이름이 된다. */
export interface EntityMapping { entityType: string; nameColumn: string; properties: PropertyMapping[]; }

/**
 * 엔티티 간 관계 1건.
 * subjectRef/objectRef는 같은 spec의 entities 배열에 대한 **0-based 인덱스**다.
 * (이름이 아니라 인덱스이므로, UI에서 엔티티를 지우면 참조가 어긋난다 — lib/mapping-spec.ts 참조)
 */
export interface RelationMapping { subjectRef: number; relation: string; objectRef: number; }

/** 데이터셋당 1개 보관되는 매핑 문서. */
export interface MappingSpec { entities: EntityMapping[]; relations: RelationMapping[]; }

/** draft = 저장만 된 초안, active = 투영에 실제 사용되는 매핑. */
export type MappingStatus = 'draft' | 'active';

export interface MappingResponse {
  datasetId: number;
  ontologyId: number;
  spec: MappingSpec;
  status: MappingStatus;
}

/** 데이터셋↔온톨로지 바인딩. ontologyId가 null이면 미바인딩(매핑 저장·활성화 불가). */
export interface DatasetOntologyResponse { datasetId: number; ontologyId: number | null; }
