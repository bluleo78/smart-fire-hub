/**
 * 데이터셋 매핑(슬라이스 E) 모킹 데이터 팩토리.
 * src/types/mapping.ts 타입 기반이므로, 백엔드 계약이 바뀌면 컴파일 에러로 드러난다.
 * 온톨로지 스키마는 ontology.factory.ts의 createOntologySchema()(화재조사 도메인)를 그대로 쓴다 —
 * Incident/Building/Damage 엔티티와 OCCURRED_AT/RESULTED_IN 트리플, Damage.피해액 속성이 이미 들어 있다.
 */

import type { DatasetDetailResponse } from '@/types/dataset';
import type { DatasetOntologyResponse, MappingResponse, MappingSpec } from '@/types/mapping';
import type { OntologySummary } from '@/types/ontology';

import { createDatasetDetail } from './dataset.factory';

export const MAPPING_DATASET_ID = 1;
export const MAPPING_ONTOLOGY_ID = 1;

/**
 * 매핑 편집 대상 TABLE 데이터셋. 컬럼은 온톨로지 팩토리의 엔티티/속성과 짝이 맞게 구성한다.
 * DTO 형태는 정본 팩토리(createDatasetDetail)에 위임하고 이 시나리오에 필요한 필드만 덮어쓴다 —
 * DatasetDetailResponse에 필드가 추가돼도 한 곳만 고치면 된다.
 */
export function createMappingDataset(overrides?: Partial<DatasetDetailResponse>): DatasetDetailResponse {
  return createDatasetDetail({
    id: MAPPING_DATASET_ID,
    name: '화재 사건 표',
    tableName: 'fire_incidents',
    description: null,
    category: null,
    columns: [
      { id: 1, columnName: 'incident_name', displayName: '사건명', dataType: 'TEXT', maxLength: null, isNullable: false, isIndexed: false, isPrimaryKey: true, description: null, columnOrder: 1 },
      { id: 2, columnName: 'building_name', displayName: '건물명', dataType: 'TEXT', maxLength: null, isNullable: true, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 2 },
      { id: 3, columnName: 'damage_name', displayName: '피해 내역', dataType: 'TEXT', maxLength: null, isNullable: true, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 3 },
      { id: 4, columnName: 'damage_amount', displayName: '피해액', dataType: 'DECIMAL', maxLength: null, isNullable: true, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 4 },
    ],
    rowCount: 10,
    createdAt: '2026-07-01T00:00:00',
    ...overrides,
  });
}

/** 바인딩 응답. null을 넘기면 미바인딩 상태를 만든다. */
export function createBinding(ontologyId: number | null = MAPPING_ONTOLOGY_ID): DatasetOntologyResponse {
  return { datasetId: MAPPING_DATASET_ID, ontologyId };
}

/** 바인딩 드롭다운 선택지. */
export function createOntologySummaries(): OntologySummary[] {
  return [
    { id: MAPPING_ONTOLOGY_ID, domain: '화재조사 보고서', schemaVersion: 1 },
    { id: 2, domain: '건축물 대장', schemaVersion: 3 },
  ];
}

/** 엔티티 2개 + 관계 1개(Incident -OCCURRED_AT-> Building). */
export function createMappingSpec(overrides?: Partial<MappingSpec>): MappingSpec {
  return {
    entities: [
      { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
      { entityType: 'Building', nameColumn: 'building_name', properties: [] },
    ],
    relations: [{ subjectRef: 0, relation: 'OCCURRED_AT', objectRef: 1 }],
    ...overrides,
  };
}

export function createMappingResponse(overrides?: Partial<MappingResponse>): MappingResponse {
  return {
    datasetId: MAPPING_DATASET_ID,
    ontologyId: MAPPING_ONTOLOGY_ID,
    spec: createMappingSpec(),
    status: 'draft',
    ...overrides,
  };
}
