/**
 * 온톨로지 시각화 모킹 데이터 팩토리
 * src/types/ontology.ts 타입 기반으로 테스트용 스키마/그래프 객체를 생성한다.
 * 스키마는 firehub-ai-agent의 CORE_ONTOLOGY(화재조사 도메인, 6엔티티/6트리플)와 동일한 구조로 맞춘다.
 */

import type {
  EntityTypeDef,
  EntityTypeDeletion,
  EntityTypeMutation,
  GraphData,
  GraphNode,
  OntologySchema,
  Property,
  PropertyMutation,
  RelationMutation,
  Triple,
  VersionOnly,
} from '@/types/ontology';

/** CORE_ONTOLOGY 엔티티 정의(6타입) — Incident/Damage는 exact, 나머지는 embedding 해소 정책.
 * Damage에 데이터 프로퍼티(피해액) 1개를 부여해 편집 라운드트립 시 미편집 properties 보존을 검증할 수 있게 한다.
 * id(S2): 실서버 GET 응답은 ontology_entity_type.id를 항상 채워 보낸다 — 요소 단위 편집 화면(아웃라인·
 * 인스펙터)이 이 id로 대상을 지목하므로 모킹도 실서버와 같은 모양을 유지해야 한다. */
function createEntityTypeDefs(): EntityTypeDef[] {
  return [
    { id: 1, type: 'Incident', description: '사건/이벤트 (예: 발생한 화재)', naming: '문서마다 고유', resolution: 'exact', properties: [] },
    { id: 2, type: 'Building', description: '물리적 장소/건물', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
    { id: 3, type: 'Cause', description: '발화·발생 원인', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
    {
      id: 4,
      type: 'Damage',
      description: '피해 내역',
      naming: '본문 표기 보존',
      resolution: 'exact',
      properties: [{ id: 1, name: '피해액', description: '추정 재산 피해액(원)', dataType: 'number', unit: '원' }],
    },
    { id: 5, type: 'Equipment', description: '소방 설비/장비', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
    { id: 6, type: 'Regulation', description: '관련 법규/기준', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
  ];
}

/** CORE_ONTOLOGY 관계 정의(6트리플). id/subjectTypeId/objectTypeId(S2): 위 엔티티 id와 짝지어 둔다. */
function createTriples(): Triple[] {
  return [
    { id: 1, subject: 'Incident', relation: 'OCCURRED_AT', object: 'Building', description: '사건이 발생한 장소', subjectTypeId: 1, objectTypeId: 2 },
    { id: 2, subject: 'Incident', relation: 'CAUSED_BY', object: 'Cause', description: '사건의 발화·발생 원인', subjectTypeId: 1, objectTypeId: 3 },
    { id: 3, subject: 'Incident', relation: 'RESULTED_IN', object: 'Damage', description: '사건이 초래한 피해', subjectTypeId: 1, objectTypeId: 4 },
    { id: 4, subject: 'Building', relation: 'HAS_EQUIPMENT', object: 'Equipment', description: '건물이 보유한 설비', subjectTypeId: 2, objectTypeId: 5 },
    { id: 5, subject: 'Incident', relation: 'VIOLATED', object: 'Regulation', description: '사건에서 위반된 규정', subjectTypeId: 1, objectTypeId: 6 },
    { id: 6, subject: 'Equipment', relation: 'GOVERNED_BY', object: 'Regulation', description: '설비를 규율하는 규정', subjectTypeId: 5, objectTypeId: 6 },
  ];
}

/** 온톨로지 스키마(GET /api/v1/ontology) 응답 객체 생성 */
export function createOntologySchema(overrides?: Partial<OntologySchema>): OntologySchema {
  return {
    domain: '화재조사 보고서',
    schemaVersion: 1,
    entities: createEntityTypeDefs(),
    relations: createTriples(),
    ...overrides,
  };
}

/** 인스턴스 그래프 노드 키 상수 — 스펙에서 특정 노드를 재참조할 때 사용 */
export const ONTOLOGY_GRAPH_NODE_KEYS = {
  incident1: 'incident-1',
  incident2: 'incident-2',
  building: 'building-1',
  cause: 'cause-1',
  damage: 'damage-1',
  equipment: 'equipment-1',
  regulation: 'regulation-1',
} as const;

/** 인스턴스 그래프(GET /api/v1/ontology/graph) 응답 객체 생성 — Incident 2개 + 나머지 타입 각 1개, 총 7노드 */
export function createOntologyGraph(overrides?: Partial<GraphData>): GraphData {
  // schemaVersion: incident1=1(현재 스키마와 동일 — 정상 표시), incident2=null(스탬프 도입 이전
  // 레거시 노드 — 표시 안 함), 나머지는 1(구버전 표시 테스트는 schema override로 별도 구성).
  const nodes: GraphNode[] = [
    { key: ONTOLOGY_GRAPH_NODE_KEYS.incident1, type: 'Incident', name: '강남구 오피스텔 화재(2026-03-02)', sourceChunkCount: 5, schemaVersion: 1 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.incident2, type: 'Incident', name: '송파구 아파트 화재(2026-04-11)', sourceChunkCount: 3, schemaVersion: null },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.building, type: 'Building', name: '강남타워', sourceChunkCount: 4, schemaVersion: 1 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.cause, type: 'Cause', name: '전기적 요인(누전)', sourceChunkCount: 2, schemaVersion: 1 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.damage, type: 'Damage', name: '재산피해 약 3200만원', sourceChunkCount: 2, schemaVersion: 1 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.equipment, type: 'Equipment', name: '스프링클러설비', sourceChunkCount: 3, schemaVersion: 1 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.regulation, type: 'Regulation', name: '소방시설법 제9조', sourceChunkCount: 1, schemaVersion: 1 },
  ];
  const edges = [
    { subjectKey: ONTOLOGY_GRAPH_NODE_KEYS.incident1, type: 'OCCURRED_AT', objectKey: ONTOLOGY_GRAPH_NODE_KEYS.building },
    { subjectKey: ONTOLOGY_GRAPH_NODE_KEYS.incident1, type: 'CAUSED_BY', objectKey: ONTOLOGY_GRAPH_NODE_KEYS.cause },
    { subjectKey: ONTOLOGY_GRAPH_NODE_KEYS.incident1, type: 'RESULTED_IN', objectKey: ONTOLOGY_GRAPH_NODE_KEYS.damage },
    { subjectKey: ONTOLOGY_GRAPH_NODE_KEYS.building, type: 'HAS_EQUIPMENT', objectKey: ONTOLOGY_GRAPH_NODE_KEYS.equipment },
    { subjectKey: ONTOLOGY_GRAPH_NODE_KEYS.incident1, type: 'VIOLATED', objectKey: ONTOLOGY_GRAPH_NODE_KEYS.regulation },
    { subjectKey: ONTOLOGY_GRAPH_NODE_KEYS.incident2, type: 'OCCURRED_AT', objectKey: ONTOLOGY_GRAPH_NODE_KEYS.building },
  ];
  return { nodes, edges, ...overrides };
}

// 요소 단위 편집(S2) 뮤테이션 응답 모킹 — EntityInspector(Task 4)의 PATCH/POST/DELETE 엔드포인트가
// 돌려주는 형태(src/types/ontology.ts 참고). schemaVersion 기본값 2는 createOntologySchema()의
// 초기 스키마 버전(1)에서 한 번 증가한 값 — useOntologyElementMutations의 낙관적 갱신 로직이
// "내가 유발한 증가분(prev+1)"과 일치해야 재조회 없이 캐시가 직접 갱신된다.
export function createEntityTypeMutation(entityType: EntityTypeDef, schemaVersion = 2): EntityTypeMutation {
  return { schemaVersion, entityType };
}

export function createPropertyMutation(property: Property, schemaVersion = 2): PropertyMutation {
  return { schemaVersion, property };
}

export function createEntityTypeDeletion(deletedRelationIds: number[] = [], schemaVersion = 2): EntityTypeDeletion {
  return { schemaVersion, deletedRelationIds };
}

// RelationInspector(Task 5)의 POST/PATCH /relations 엔드포인트가 돌려주는 형태.
export function createRelationMutation(relation: Triple, schemaVersion = 2): RelationMutation {
  return { schemaVersion, relation };
}

export function createVersionOnly(schemaVersion = 2): VersionOnly {
  return { schemaVersion };
}
