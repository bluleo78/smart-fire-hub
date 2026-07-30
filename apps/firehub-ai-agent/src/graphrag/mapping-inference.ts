// 표 컬럼 프로파일 + 온톨로지를 LLM에 넘겨 매핑(MappingSpec)을 추론한다.
// 백엔드 MappingService.validate()가 스펙 전체를 400으로 거부하므로, 여기서 규칙 2~6을
// TS로 재구현해 부적합 제안을 버리고 통과분만 낸다(그래야 draft 저장이 항상 성공).
import type { CompleteFn } from './llm-cli.js';
import { Ontology, isEntityType, isAllowedTriple } from './ontology.js';
import type { ColumnProfile } from './column-profiler.js';
import type { MappingSpec } from './table-projection.js';
import { parseJsonBlock } from './extractor.js';

export interface InferMappingDeps {
  complete: CompleteFn;
}

// 버려진 제안 기록(관측·디버깅용, 툴 결과에 노출).
export interface DroppedSuggestion {
  kind: 'entity' | 'property' | 'relation';
  detail: string;
}

export interface InferenceResult {
  spec: MappingSpec;
  dropped: DroppedSuggestion[];
  confidences: { target: string; confidence: number }[];
}

// LLM 원시 출력의 느슨한 형태(검증 전).
interface RawEntity {
  entityType?: string;
  nameColumn?: string;
  properties?: { column?: string; propertyName?: string }[];
  confidence?: number;
}
interface RawRelation {
  subjectRef?: number;
  relation?: string;
  objectRef?: number;
  confidence?: number;
}

const EMPTY: MappingSpec = { entities: [], relations: [] };

// 온톨로지 엔티티타입/속성/허용트리플 + 컬럼 프로파일로 시스템 프롬프트를 만든다.
// GEOMETRY 등 ontologyDataType=null 컬럼은 후보에서 제외한다.
function buildInferencePrompt(ontology: Ontology, profiles: ColumnProfile[]): string {
  const entityLines = ontology.entities
    .map((e) => {
      const props = (e.properties ?? []).map((p) => `${p.name}(${p.dataType})`).join(', ') || '(속성 없음)';
      return `- ${e.type}: 속성=[${props}]`;
    })
    .join('\n');
  const tripleLines = ontology.relations.map((r) => `- ${r.subject} -[${r.relation}]-> ${r.object}`).join('\n');
  const colLines = profiles
    .filter((p) => p.ontologyDataType !== null)
    .map(
      (p) =>
        `- ${p.columnName} (타입=${p.ontologyDataType}, 고유값=${p.distinctCount}, null비율=${p.nullRatio.toFixed(2)}, PK=${p.isPrimaryKey}, 예시=[${p.sampleValues.join(', ')}])`,
    )
    .join('\n');
  return [
    '당신은 표 데이터셋의 컬럼을 지식그래프 온톨로지에 매핑하는 전문가입니다.',
    '각 컬럼을 엔티티의 이름(nameColumn) 또는 속성(property)으로 매핑하세요.',
    '한 행이 여러 엔티티를 담고 그 사이에 아래 허용관계가 있으면 relations로 표현하세요.',
    '확신이 없으면 생략하세요(틀린 매핑보다 누락이 낫습니다).',
    '',
    '## 엔티티타입(속성)',
    entityLines,
    '',
    '## 허용 관계(트리플)',
    tripleLines || '(없음)',
    '',
    '## 컬럼 프로파일',
    colLines || '(매핑 가능한 컬럼 없음)',
    '',
    '## 출력 형식 (JSON only, ```json 블록으로 감쌀 것)',
    '{ "entities": [ { "entityType": "타입", "nameColumn": "컬럼", "properties": [ { "column": "컬럼", "propertyName": "속성" } ], "confidence": 0.0 } ],',
    '  "relations": [ { "subjectRef": 0, "relation": "관계", "objectRef": 1, "confidence": 0.0 } ] }',
    'subjectRef/objectRef는 entities 배열의 0-based 인덱스입니다.',
  ].join('\n');
}

// 신뢰도가 유효 범위 [0,1]의 숫자면 confidences에 기록.
function pushConfidence(list: { target: string; confidence: number }[], target: string, c: unknown): void {
  if (typeof c === 'number' && c >= 0 && c <= 1) list.push({ target, confidence: c });
}

export async function inferMapping(
  deps: InferMappingDeps,
  ontology: Ontology,
  profiles: ColumnProfile[],
): Promise<InferenceResult> {
  const dropped: DroppedSuggestion[] = [];
  const confidences: { target: string; confidence: number }[] = [];

  // 1) LLM 호출(실패 시 빈 결과 — 조용히 계속하지 않고 호출부가 빈 결과를 인지).
  let content = '';
  try {
    content = await deps.complete(buildInferencePrompt(ontology, profiles), '위 근거로 매핑 JSON을 출력하세요.');
  } catch (err) {
    console.warn('[graphrag] inferMapping LLM 호출 실패, 빈 결과 반환:', err);
    return { spec: EMPTY, dropped, confidences };
  }

  const parsed = parseJsonBlock(content) as { entities?: RawEntity[]; relations?: RawRelation[] } | null;
  if (!parsed) return { spec: EMPTY, dropped, confidences };

  const columnNames = new Set(profiles.map((p) => p.columnName));
  const profileByName = new Map(profiles.map((p) => [p.columnName, p]));
  const rawEntities = Array.isArray(parsed.entities) ? parsed.entities : [];
  const rawRelations = Array.isArray(parsed.relations) ? parsed.relations : [];

  // 2) 엔티티 필터(규칙 2·3·4). 원본 인덱스를 함께 보관해 관계 ref 재계산에 사용.
  const kept: { entity: MappingSpec['entities'][number]; origIndex: number }[] = [];
  rawEntities.forEach((re, origIndex) => {
    if (typeof re?.entityType !== 'string' || !isEntityType(ontology, re.entityType)) {
      dropped.push({ kind: 'entity', detail: `알 수 없는 엔티티타입: ${re?.entityType}` });
      return;
    }
    if (typeof re?.nameColumn !== 'string' || !columnNames.has(re.nameColumn)) {
      dropped.push({ kind: 'entity', detail: `없는 nameColumn: ${re?.nameColumn} (${re.entityType})` });
      return;
    }
    const definedProps = new Map(
      (ontology.entities.find((e) => e.type === re.entityType)?.properties ?? []).map((p) => [p.name, p]),
    );
    const properties: MappingSpec['entities'][number]['properties'] = [];
    for (const p of Array.isArray(re.properties) ? re.properties : []) {
      const defined = typeof p?.propertyName === 'string' ? definedProps.get(p.propertyName) : undefined;
      if (!defined) {
        dropped.push({ kind: 'property', detail: `미정의 속성: ${p?.propertyName} (${re.entityType})` });
        continue;
      }
      if (typeof p?.column !== 'string' || !columnNames.has(p.column)) {
        dropped.push({ kind: 'property', detail: `없는 속성 컬럼: ${p?.column} (${re.entityType}.${p.propertyName})` });
        continue;
      }
      // 규칙 6(#324): 속성 dataType과 컬럼 타입 축이 다르면 백엔드가 스펙 전체를 400으로 거부하므로
      // 여기서 해당 속성만 버린다. text 속성·dataType 미지정 속성은 백엔드와 동일하게 통과.
      // number/date 속성은 축이 정확히 같을 때만 남긴다 — 여기가 백엔드보다 약간 엄격하지만
      // (미지 컬럼타입을 profiler가 text로 접음) 초과 드롭은 400이 아니라 제안 누락이라 안전한 방향이다.
      const columnAxis = profileByName.get(p.column)?.ontologyDataType ?? null;
      if (defined.dataType && defined.dataType !== 'text' && columnAxis !== defined.dataType) {
        dropped.push({
          kind: 'property',
          detail: `타입 불일치 속성: ${re.entityType}.${p.propertyName}(${defined.dataType}) ← ${p.column}(${columnAxis})`,
        });
        continue;
      }
      // propertyName은 온톨로지 정의에서 찾은 이름(defined.name)을 쓴다 — 값은 같지만 타입이 확정된다.
      properties.push({ column: p.column, propertyName: defined.name });
    }
    pushConfidence(confidences, `entity:${re.entityType}:${re.nameColumn}`, re.confidence);
    kept.push({ entity: { entityType: re.entityType, nameColumn: re.nameColumn, properties }, origIndex });
  });

  const entities = kept.map((k) => k.entity);
  const origToNew = new Map<number, number>();
  kept.forEach((k, newIndex) => origToNew.set(k.origIndex, newIndex));

  // 3) 관계 필터(규칙 5a·5b). ref는 유지된 엔티티의 새 인덱스로 재계산.
  const relations: MappingSpec['relations'] = [];
  for (const rr of rawRelations) {
    if (typeof rr?.relation !== 'string' || typeof rr?.subjectRef !== 'number' || typeof rr?.objectRef !== 'number') {
      dropped.push({ kind: 'relation', detail: `형식 오류 관계: ${JSON.stringify(rr)}` });
      continue;
    }
    const subNew = origToNew.get(rr.subjectRef);
    const objNew = origToNew.get(rr.objectRef);
    if (subNew === undefined || objNew === undefined) {
      dropped.push({ kind: 'relation', detail: `드롭된 엔티티 참조: ${rr.subjectRef}-[${rr.relation}]->${rr.objectRef}` });
      continue;
    }
    const subjectType = entities[subNew].entityType;
    const objectType = entities[objNew].entityType;
    if (!isAllowedTriple(ontology, subjectType, rr.relation, objectType)) {
      dropped.push({ kind: 'relation', detail: `허용되지 않은 트리플: ${subjectType}-[${rr.relation}]->${objectType}` });
      continue;
    }
    pushConfidence(confidences, `relation:${subjectType}:${rr.relation}:${objectType}`, rr.confidence);
    relations.push({ subjectRef: subNew, relation: rr.relation, objectRef: objNew });
  }

  return { spec: { entities, relations }, dropped, confidences };
}
