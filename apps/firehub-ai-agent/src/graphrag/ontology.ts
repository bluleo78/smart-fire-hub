// 코어 온톨로지 — 화재조사 도메인의 엔티티/관계 타입과 허용 트리플을 고정 정의한다.
// walking skeleton 범위에서는 이 파일이 유일한 온톨로지 소스(추후 DB/관리 UI로 승격 예정).

export const ENTITY_TYPES = [
  'Incident', 'Building', 'Cause', 'Damage', 'Equipment', 'Regulation',
] as const;
export type EntityType = (typeof ENTITY_TYPES)[number];

export const RELATION_TYPES = [
  'OCCURRED_AT', 'CAUSED_BY', 'RESULTED_IN', 'HAS_EQUIPMENT', 'VIOLATED', 'GOVERNED_BY',
] as const;
export type RelationType = (typeof RELATION_TYPES)[number];

export interface ExtractedEntity { type: EntityType; name: string; }
export interface ExtractedRelation { subject: string; type: RelationType; object: string; }
export interface ExtractionResult { entities: ExtractedEntity[]; relations: ExtractedRelation[]; }

// 허용 트리플 (주어타입, 관계, 목적어타입) — 이 조합만 그래프에 적재한다.
export const ONTOLOGY_TRIPLES: ReadonlyArray<readonly [EntityType, RelationType, EntityType]> = [
  ['Incident', 'OCCURRED_AT', 'Building'],
  ['Incident', 'CAUSED_BY', 'Cause'],
  ['Incident', 'RESULTED_IN', 'Damage'],
  ['Building', 'HAS_EQUIPMENT', 'Equipment'],
  ['Incident', 'VIOLATED', 'Regulation'],
  ['Equipment', 'GOVERNED_BY', 'Regulation'],
];

export function isEntityType(x: string): x is EntityType {
  return (ENTITY_TYPES as readonly string[]).includes(x);
}
export function isRelationType(x: string): x is RelationType {
  return (RELATION_TYPES as readonly string[]).includes(x);
}
// 주어·관계·목적어 조합이 온톨로지 허용 트리플에 존재하는지 검사한다.
export function isAllowedTriple(subjectType: EntityType, rel: RelationType, objectType: EntityType): boolean {
  return ONTOLOGY_TRIPLES.some(([s, r, o]) => s === subjectType && r === rel && o === objectType);
}
