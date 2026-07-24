// 코어 온톨로지 — 번들 폴백(api 무응답 시)용 엔티티/관계 타입, 허용 트리플, 도메인 메타데이터.
// 추출 프롬프트는 (api에서 로드했거나 이 번들로 폴백한) 온톨로지 정의에서 생성된다.
// B-2b(지식 모델 편집)부터 온톨로지의 정본은 api(DB)이며, 여기 ENTITY_TYPES/RELATION_TYPES는
// CORE_ONTOLOGY 번들 정의에만 쓰이는 상수다 — 엔티티/관계 타입 검증(isEntityType 등)은 이제
// 이 고정 배열이 아니라 "실제 로드된 Ontology 인스턴스"를 기준으로 하므로, DB에서 새 타입을
// 추가·수정해도(예: 관리자가 지식 모델을 편집) 여기 재배포 없이 그대로 통과한다.

export const ENTITY_TYPES = [
  'Incident', 'Building', 'Cause', 'Damage', 'Equipment', 'Regulation',
] as const;
export type EntityType = string;

export const RELATION_TYPES = [
  'OCCURRED_AT', 'CAUSED_BY', 'RESULTED_IN', 'HAS_EQUIPMENT', 'VIOLATED', 'GOVERNED_BY',
] as const;
export type RelationType = string;

// 엔티티 데이터 프로퍼티 정의 — 속성명·설명·데이터타입·단위. 추출/정규화/질의가 이 메타로 동작한다.
export interface PropertyDef { name: string; description: string; dataType: 'text' | 'number' | 'date'; unit?: string; }

export interface ExtractedEntity {
  type: EntityType;
  name: string;
  // 정규화된 속성값 맵(예: {"피해액": 120000000}) — Task 4(추출/정규화)가 채운다. 이 태스크는 타입만 추가.
  properties?: Record<string, number | string>;
}
export interface ExtractedRelation { subject: string; type: RelationType; object: string; }
export interface ExtractionResult {
  entities: ExtractedEntity[];
  relations: ExtractedRelation[];
  // 정규화 실패 속성 검수 후보(선택) — 비어 있으면 생략.
  propertyReviewCandidates?: PropertyReviewCandidate[];
}

// 정규화 실패 속성 검수 후보 — extractor가 채우고 ingest가 canonical key 바인딩 후 검수 큐에 등록한다.
export interface PropertyReviewCandidate {
  entityType: EntityType;
  entityName: string;       // 추출 원본 이름(canonical 재매핑 전) — ingest가 최종 key로 변환.
  propertyName: string;
  dataType: 'text' | 'number' | 'date';
  rawText: string;          // 정규화하지 못한 원문 값.
}

// 엔티티 타입 정의 — 설명 + 명명 규칙 + 해소(resolution) 정책 + (선택) 데이터 속성 정의.
// resolution: 'embedding'(표기 변형을 임베딩 유사도로 병합) | 'exact'(정확 키 일치만 병합 — 수치·고유성 보존 목적).
// id(5-6): api(ontology_entity_type.id) 서로게이트 PK — 타입명이 리네임돼도 리포지토리가 UPDATE로
// 보존하는 안정적 식별자. Neo4j 노드 key(entityKey)가 이 id를 기반으로 구성되어, 리네임 시에도
// key가 바뀌지 않는다(타입 문자열은 더 이상 key의 일부가 아님).
export interface EntityTypeDef {
  type: EntityType;
  description: string;
  naming: string;
  resolution: 'embedding' | 'exact';
  properties?: readonly PropertyDef[];
  id: number;
}
// 관계 정의 — 허용 (주어타입, 관계, 목적어타입) + 의미 설명.
export interface RelationDef { subject: EntityType; relation: RelationType; object: EntityType; description: string; }
// 온톨로지 = 도메인 설명 + 엔티티 정의 + 관계 정의. 추출 엔진은 이 구조만 알면 도메인 무관하게 동작한다.
// schemaVersion: api(DB) 소유 온톨로지 스키마 버전 — 적재 이력에 "당시 스키마"로 기록해 추후 스키마 변경 추적에 쓴다.
export interface Ontology { domain: string; schemaVersion: number; entities: readonly EntityTypeDef[]; relations: readonly RelationDef[]; }

// identity/event 성격의 엔티티에 공통으로 적용되는 고유 명명 규칙(도메인 무관 원칙).
const UNIQUE_NAMING =
  '문서마다 고유해야 한다. 핵심 식별 속성(장소·일자 등)을 포함해 구성하고, '
  + '일반명이나 문서 번호를 이름으로 쓰지 마라. 한 문서에서 정확히 1개만 추출한다.';
// 그 외 엔티티는 본문 표기를 보존한다.
const VERBATIM_NAMING = '본문에 등장한 표기를 그대로 사용한다.';

// 코어 온톨로지 인스턴스 — 화재조사 도메인. 도메인 특화는 이 객체에만 존재한다.
// id: 번들 폴백 전용 고정값(1~6) — api DB의 실제 entity_type_id와는 무관하다. 섞이지 않는 이유는
// "폴백이라서"가 아니라 파이프라인 순서다: loadOntology()가 실패해 이 폴백으로 넘어갈 정도로 api가
// 응답하지 않으면, 같은 api를 호출하는 ingestDataset의 deps.listChunks()도 반드시 실패해 entityKey가
// 구성되기 전에 파이프라인 자체가 중단된다(graphrag-tools.ts 참고). 이 폴백 id가 실제 저장에 쓰이는
// 경로는 존재하지 않는다 — listChunks가 api 이외의 경로(캐시 등)로 바뀌면 이 불변식이 깨진다.
export const CORE_ONTOLOGY: Ontology = {
  domain: '화재조사 보고서',
  schemaVersion: 1,
  entities: [
    // Incident/Damage는 사건별로 고유하거나 구체 수치를 담으므로 임베딩 유사도 병합 시 서로 다른
    // 사건/피해가 뭉개질 수 있다 → 'exact'(정확 키 일치만 병합)로 고정.
    { id: 1, type: 'Incident', description: '사건/이벤트 (예: 발생한 화재)', naming: UNIQUE_NAMING, resolution: 'exact',
      properties: [{ name: '피해액',
        description: '화재로 인한 재산 피해 총액을 원 단위 정수로 추출. \'억\'=1e8, \'만\'=1e4 로 환산하고 콤마·통화기호 제거. 범위·근사값은 대표값 1개만.',
        dataType: 'number', unit: '원' }] },
    { id: 2, type: 'Building', description: '물리적 장소/건물', naming: VERBATIM_NAMING, resolution: 'embedding' },
    { id: 3, type: 'Cause', description: '발화·발생 원인', naming: VERBATIM_NAMING, resolution: 'embedding' },
    { id: 4, type: 'Damage', description: '피해 내역', naming: VERBATIM_NAMING, resolution: 'exact' },
    { id: 5, type: 'Equipment', description: '소방 설비/장비', naming: VERBATIM_NAMING, resolution: 'embedding' },
    { id: 6, type: 'Regulation', description: '관련 법규/기준', naming: VERBATIM_NAMING, resolution: 'embedding' },
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

// 전달된 ontology(로드된 실제 스키마)에 정의된 엔티티/관계 타입인지 검사한다.
// DB 편집으로 추가/변경된 타입도 그대로 통과해야 하므로, 고정 상수가 아니라 ontology 인자를 기준으로 삼는다.
export function isEntityType(ontology: Ontology, x: string): boolean {
  return ontology.entities.some((e) => e.type === x);
}
export function isRelationType(ontology: Ontology, x: string): boolean {
  return ontology.relations.some((r) => r.relation === x);
}
// 엔티티 타입의 해소 정책 조회 — 전달된 ontology 기준. 없는 타입은 안전하게 'embedding' 기본값.
export function entityResolutionPolicy(ontology: Ontology, type: EntityType): 'embedding' | 'exact' {
  return ontology.entities.find((e) => e.type === type)?.resolution ?? 'embedding';
}
// 엔티티 타입명 → id(5-6, Neo4j key 구성용). extractor.ts가 isEntityType으로 이미 ontology에 실존하는
// 타입만 통과시키므로, 이 함수를 호출하는 시점엔 항상 매칭에 성공해야 한다 — 실패하면 파이프라인
// 불변식이 깨진 것이므로 조용히 폴백하지 않고 예외를 던진다.
export function entityTypeId(ontology: Ontology, type: EntityType): number {
  const def = ontology.entities.find((e) => e.type === type);
  if (!def) throw new Error(`온톨로지에 정의되지 않은 엔티티 타입: ${type}`);
  return def.id;
}
// 주어·관계·목적어 조합이 전달된 ontology 의 허용 트리플에 존재하는지 검사한다.
export function isAllowedTriple(
  ontology: Ontology, subjectType: EntityType, rel: RelationType, objectType: EntityType,
): boolean {
  return ontology.relations.some((r) => r.subject === subjectType && r.relation === rel && r.object === objectType);
}

// ── 시각화용 직렬화 (읽기 전용) ──
// CORE_ONTOLOGY를 프론트 계약 형태로 평문 직렬화한다. 도메인 로직/추출에는 영향 없음.
export interface SerializedEntityType { type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; properties?: PropertyDef[]; id?: number; }
export interface SerializedTriple { subject: string; relation: string; object: string; description: string; }
export interface SerializedOntology { domain: string; schemaVersion: number; entities: SerializedEntityType[]; relations: SerializedTriple[]; }

export function serializeOntology(ontology: Ontology = CORE_ONTOLOGY): SerializedOntology {
  return {
    domain: ontology.domain,
    schemaVersion: ontology.schemaVersion,
    entities: ontology.entities.map((e) => ({
      type: e.type, description: e.description, naming: e.naming, resolution: e.resolution, id: e.id,
      properties: e.properties ? [...e.properties] : undefined,
    })),
    relations: ontology.relations.map((r) => ({ subject: r.subject, relation: r.relation, object: r.object, description: r.description })),
  };
}

// wire 형태(SerializedOntology)를 내부 Ontology 로 역직렬화한다.
// DB(api)가 온톨로지 정본이므로 고정 상수(ENTITY_TYPES/RELATION_TYPES)로는 필터링하지 않는다 —
// 대신 wire 응답 자체의 일관성만 방어한다: relation은 응답에 실제로 존재하는 엔티티 타입만 참조해야 한다
// (예: subject/object 오타나 삭제된 타입을 가리키는 정합성 깨진 응답 방어).
export function deserializeOntology(s: SerializedOntology): Ontology {
  const knownTypes = new Set(s.entities.map((e) => e.type));
  return {
    domain: s.domain,
    // 방어 기본값: 구버전 api/캐시된 wire 응답 등 schemaVersion 이 없을 수 있으므로 1로 폴백.
    schemaVersion: s.schemaVersion ?? 1,
    entities: s.entities.map((e) => {
      // id(5-6)는 Neo4j key 구성에 쓰이는 정체성 필드라 누락을 조용히 넘기면 안 된다 —
      // undefined인 채로 진행하면 모든 무id 타입이 "undefined:이름"으로 뭉개져 서로 다른
      // 타입의 엔티티가 같은 key 네임스페이스로 충돌할 수 있다. 명시적으로 실패시킨다.
      if (e.id == null) {
        throw new Error(`온톨로지 wire 응답에 entity_type id가 없습니다: ${e.type}`);
      }
      return {
        type: e.type, description: e.description, naming: e.naming, resolution: e.resolution, id: e.id,
        // 허용된 dataType(text|number|date)만 통과시킨다 — 정의되지 않은 값 방어.
        properties: (e.properties ?? [])
          .filter((p) => ['text', 'number', 'date'].includes(p.dataType))
          // api wire 의 unit 은 nullable → JSON null 이 올 수 있으므로 undefined 로 정규화(타입 string|undefined 보존).
          .map((p) => ({ name: p.name, description: p.description, dataType: p.dataType, unit: p.unit ?? undefined })),
      };
    }),
    relations: s.relations
      .filter((r) => knownTypes.has(r.subject) && knownTypes.has(r.object))
      .map((r) => ({ subject: r.subject, relation: r.relation, object: r.object, description: r.description })),
  };
}

// 온톨로지 정의로부터 추출용 시스템 프롬프트를 생성한다(도메인 무관 — 도메인 특화는 ontology 인자에만 존재).
export function buildExtractionPrompt(ontology: Ontology = CORE_ONTOLOGY): string {
  const entityLines = ontology.entities
    .map((e) => {
      let line = `- ${e.type}: ${e.description}\n  · 명명: ${e.naming}`;
      // 속성 정의가 있는 타입만 속성 렌더 — 없는 타입은 기존 줄과 바이트 동일(회귀 보존).
      if (e.properties && e.properties.length > 0) {
        const props = e.properties
          .map((p) => `    - ${p.name}(${p.dataType}${p.unit ? `, ${p.unit}` : ''}): ${p.description}`)
          .join('\n');
        line += `\n  · 속성(있으면 추출):\n${props}`;
      }
      return line;
    })
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
{"entities":[{"type":"Incident","name":"...","properties":{"피해액":"약 1억 2천만원"}}],"relations":[{"subject":"엔티티명","type":"CAUSED_BY","object":"엔티티명"}]}
\`\`\``;
}
