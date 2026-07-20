// 코어 온톨로지 — 엔티티/관계 타입, 허용 트리플, 도메인 메타데이터를 고정 정의한다.
// 추출 프롬프트는 이 온톨로지 정의에서 생성된다(도메인 하드코딩을 여기 한 곳으로 모음).
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

// 엔티티 타입 정의 — 설명 + 명명 규칙. 프롬프트가 이 메타데이터로 조립된다.
export interface EntityTypeDef { type: EntityType; description: string; naming: string; }
// 관계 정의 — 허용 (주어타입, 관계, 목적어타입) + 의미 설명.
export interface RelationDef { subject: EntityType; relation: RelationType; object: EntityType; description: string; }
// 온톨로지 = 도메인 설명 + 엔티티 정의 + 관계 정의. 추출 엔진은 이 구조만 알면 도메인 무관하게 동작한다.
export interface Ontology { domain: string; entities: readonly EntityTypeDef[]; relations: readonly RelationDef[]; }

// identity/event 성격의 엔티티에 공통으로 적용되는 고유 명명 규칙(도메인 무관 원칙).
const UNIQUE_NAMING =
  '문서마다 고유해야 한다. 핵심 식별 속성(장소·일자 등)을 포함해 구성하고, '
  + '일반명이나 문서 번호를 이름으로 쓰지 마라. 한 문서에서 정확히 1개만 추출한다.';
// 그 외 엔티티는 본문 표기를 보존한다.
const VERBATIM_NAMING = '본문에 등장한 표기를 그대로 사용한다.';

// 코어 온톨로지 인스턴스 — 화재조사 도메인. 도메인 특화는 이 객체에만 존재한다.
export const CORE_ONTOLOGY: Ontology = {
  domain: '화재조사 보고서',
  entities: [
    { type: 'Incident', description: '사건/이벤트 (예: 발생한 화재)', naming: UNIQUE_NAMING },
    { type: 'Building', description: '물리적 장소/건물', naming: VERBATIM_NAMING },
    { type: 'Cause', description: '발화·발생 원인', naming: VERBATIM_NAMING },
    { type: 'Damage', description: '피해 내역', naming: VERBATIM_NAMING },
    { type: 'Equipment', description: '소방 설비/장비', naming: VERBATIM_NAMING },
    { type: 'Regulation', description: '관련 법규/기준', naming: VERBATIM_NAMING },
  ],
  relations: [
    { subject: 'Incident', relation: 'OCCURRED_AT', object: 'Building', description: '사건이 발생한 장소' },
    { subject: 'Incident', relation: 'CAUSED_BY', object: 'Cause', description: '사건의 발화·발생 원인' },
    { subject: 'Incident', relation: 'RESULTED_IN', object: 'Damage', description: '사건이 초래한 피해' },
    { subject: 'Building', relation: 'HAS_EQUIPMENT', object: 'Equipment', description: '건물이 보유한 설비' },
    { subject: 'Incident', relation: 'VIOLATED', object: 'Regulation', description: '사건에서 위반된 규정' },
    { subject: 'Equipment', relation: 'GOVERNED_BY', object: 'Regulation', description: '설비를 규율하는 규정' },
  ],
};

// 허용 트리플 (주어타입, 관계, 목적어타입) — CORE_ONTOLOGY에서 파생. 이 조합만 그래프에 적재한다.
export const ONTOLOGY_TRIPLES: ReadonlyArray<readonly [EntityType, RelationType, EntityType]> =
  CORE_ONTOLOGY.relations.map((r) => [r.subject, r.relation, r.object] as const);

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

// 온톨로지 정의로부터 추출용 시스템 프롬프트를 생성한다(도메인 무관 — 도메인 특화는 ontology 인자에만 존재).
export function buildExtractionPrompt(ontology: Ontology = CORE_ONTOLOGY): string {
  const entityLines = ontology.entities
    .map((e) => `- ${e.type}: ${e.description}\n  · 명명: ${e.naming}`)
    .join('\n');
  const relationLines = ontology.relations
    .map((r) => `- ${r.subject} -${r.relation}-> ${r.object}: ${r.description}`)
    .join('\n');
  return `너는 ${ontology.domain}에서 지식 그래프를 추출하는 도구다.
아래 온톨로지에 **정확히 일치하는** 엔티티와 관계만 추출한다.

[엔티티 타입]
${entityLines}

[관계 타입 — 아래 허용 방향만]
${relationLines}

반드시 다음 형식의 JSON 코드블록만 출력한다(설명 금지):
\`\`\`json
{"entities":[{"type":"Incident","name":"..."}],"relations":[{"subject":"엔티티명","type":"CAUSED_BY","object":"엔티티명"}]}
\`\`\``;
}
