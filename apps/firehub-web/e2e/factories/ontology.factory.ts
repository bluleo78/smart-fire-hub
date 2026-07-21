/**
 * 온톨로지 시각화 모킹 데이터 팩토리
 * src/types/ontology.ts 타입 기반으로 테스트용 스키마/그래프 객체를 생성한다.
 * 스키마는 firehub-ai-agent의 CORE_ONTOLOGY(화재조사 도메인, 6엔티티/6트리플)와 동일한 구조로 맞춘다.
 */

import type { EntityTypeDef, GraphData, GraphNode, OntologySchema, Triple } from '@/types/ontology';

/** CORE_ONTOLOGY 엔티티 정의(6타입) — Incident/Damage는 exact, 나머지는 embedding 해소 정책 */
function createEntityTypeDefs(): EntityTypeDef[] {
  return [
    { type: 'Incident', description: '사건/이벤트 (예: 발생한 화재)', naming: '문서마다 고유', resolution: 'exact' },
    { type: 'Building', description: '물리적 장소/건물', naming: '본문 표기 보존', resolution: 'embedding' },
    { type: 'Cause', description: '발화·발생 원인', naming: '본문 표기 보존', resolution: 'embedding' },
    { type: 'Damage', description: '피해 내역', naming: '본문 표기 보존', resolution: 'exact' },
    { type: 'Equipment', description: '소방 설비/장비', naming: '본문 표기 보존', resolution: 'embedding' },
    { type: 'Regulation', description: '관련 법규/기준', naming: '본문 표기 보존', resolution: 'embedding' },
  ];
}

/** CORE_ONTOLOGY 관계 정의(6트리플) */
function createTriples(): Triple[] {
  return [
    { subject: 'Incident', relation: 'OCCURRED_AT', object: 'Building', description: '사건이 발생한 장소' },
    { subject: 'Incident', relation: 'CAUSED_BY', object: 'Cause', description: '사건의 발화·발생 원인' },
    { subject: 'Incident', relation: 'RESULTED_IN', object: 'Damage', description: '사건이 초래한 피해' },
    { subject: 'Building', relation: 'HAS_EQUIPMENT', object: 'Equipment', description: '건물이 보유한 설비' },
    { subject: 'Incident', relation: 'VIOLATED', object: 'Regulation', description: '사건에서 위반된 규정' },
    { subject: 'Equipment', relation: 'GOVERNED_BY', object: 'Regulation', description: '설비를 규율하는 규정' },
  ];
}

/** 온톨로지 스키마(GET /api/v1/ontology) 응답 객체 생성 */
export function createOntologySchema(overrides?: Partial<OntologySchema>): OntologySchema {
  return {
    domain: '화재조사 보고서',
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
  const nodes: GraphNode[] = [
    { key: ONTOLOGY_GRAPH_NODE_KEYS.incident1, type: 'Incident', name: '강남구 오피스텔 화재(2026-03-02)', sourceChunkCount: 5 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.incident2, type: 'Incident', name: '송파구 아파트 화재(2026-04-11)', sourceChunkCount: 3 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.building, type: 'Building', name: '강남타워', sourceChunkCount: 4 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.cause, type: 'Cause', name: '전기적 요인(누전)', sourceChunkCount: 2 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.damage, type: 'Damage', name: '재산피해 약 3200만원', sourceChunkCount: 2 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.equipment, type: 'Equipment', name: '스프링클러설비', sourceChunkCount: 3 },
    { key: ONTOLOGY_GRAPH_NODE_KEYS.regulation, type: 'Regulation', name: '소방시설법 제9조', sourceChunkCount: 1 },
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
