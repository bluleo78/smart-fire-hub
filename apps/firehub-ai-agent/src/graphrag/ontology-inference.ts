// 데이터셋 근거(표 컬럼 프로파일 · 문서 청크 표본)를 LLM 에 넘겨 온톨로지 draft 를 유도한다.
// mapping-inference.ts 와 같은 경계: 순수 로직만 두고 I/O 는 도구 핸들러가 맡는다.
//
// 검증 필터를 얇게 유지하는 이유: draft 생성은 백엔드에서 형식 검증만 받는다(활성화가 완전성 게이트).
// 따라서 mapping-inference 처럼 백엔드 규칙을 통째로 재구현하지 않고, 실제로 400/409 를 유발하거나
// DB 제약(UNIQUE)에 걸리는 것만 거른다.
import type { CompleteFn } from './llm-cli.js';
import type { ColumnProfile } from './column-profiler.js';
import { parseJsonBlock } from './extractor.js';

/**
 * 온톨로지 속성·타입명으로 쓸 수 없는 예약어.
 * 출처: firehub-web 의 OntologyEditDialog 가 프론트에서 검증하는 목록과 동일하다.
 * 백엔드가 강제하지 않아 앱 간 단일 출처가 없으므로 **의도적으로 중복 정의**한다.
 * 백엔드 강제가 추가되면 그때 한 곳으로 옮긴다.
 */
export const RESERVED_PROPERTY_NAMES = ['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion'] as const;

const DATA_TYPES = ['text', 'number', 'date'] as const;
const RESOLUTIONS = ['exact', 'embedding'] as const;

export interface ProposedProperty {
  name: string;
  description: string;
  dataType: (typeof DATA_TYPES)[number];
  unit: string | null;
}

export interface ProposedEntity {
  type: string;
  description: string;
  naming: string;
  resolution: (typeof RESOLUTIONS)[number];
  properties: ProposedProperty[];
}

export interface ProposedRelation {
  subject: string;
  relation: string;
  object: string;
  description: string;
}

/** 버려진 제안 기록(관측·디버깅용, 도구 결과에 노출). mapping-inference 의 DroppedSuggestion 과 같은 역할. */
export interface DroppedProposal {
  kind: 'entity' | 'property' | 'relation';
  detail: string;
}

export interface OntologyInferenceResult {
  entities: ProposedEntity[];
  relations: ProposedRelation[];
  dropped: DroppedProposal[];
}

/** 데이터셋 1건의 근거. 표는 컬럼 프로파일, 문서는 청크 본문 표본을 담는다. */
export type DatasetEvidence =
  | { datasetId: number; name: string; kind: 'table'; profiles: ColumnProfile[] }
  | { datasetId: number; name: string; kind: 'document'; chunks: string[] };

// 값이 비어있지 않은 문자열인지. 앞뒤 공백을 잘라 반환한다 —
// 자르지 않으면 "Building "과 "Building"이 서로 다른 타입/트리플로 취급되어
// 아래 중복 판정(Set 기반)이 공백 차이만으로 무력화된다.
function str(v: unknown): string | null {
  const t = typeof v === 'string' ? v.trim() : '';
  return t !== '' ? t : null;
}

/**
 * LLM 원시 제안을 백엔드 저장 가능한 형태로 거른다.
 * 규칙: (1) resolution 보정 (2) dataType 3종 (3) 엔티티 type 중복 (4) 엔티티 내 속성명 중복
 *       (5) 예약어(타입명·속성명) (6) 트리플 끝점이 살아남은 엔티티 집합 안에 있을 것
 *       (7) subject|relation|object 트리플 중복(백엔드 seenTriples 와 동일 기준)
 * 규칙 8(엔티티 0개)은 여기서 판정하지 않고 호출부가 결과 길이로 처리한다.
 */
export function filterProposal(raw: unknown): OntologyInferenceResult {
  const dropped: DroppedProposal[] = [];
  const parsed = (raw ?? {}) as { entities?: unknown; relations?: unknown };
  const rawEntities = Array.isArray(parsed.entities) ? parsed.entities : [];
  const rawRelations = Array.isArray(parsed.relations) ? parsed.relations : [];

  const entities: ProposedEntity[] = [];
  const seenTypes = new Set<string>();

  for (const re of rawEntities as Record<string, unknown>[]) {
    const type = str(re?.type);
    if (!type) {
      dropped.push({ kind: 'entity', detail: `type 이 없는 엔티티: ${JSON.stringify(re)}` });
      continue;
    }
    if ((RESERVED_PROPERTY_NAMES as readonly string[]).includes(type)) {
      dropped.push({ kind: 'entity', detail: `예약어 엔티티 타입명: ${type}` });
      continue;
    }
    if (seenTypes.has(type)) {
      dropped.push({ kind: 'entity', detail: `중복 엔티티 타입: ${type}` });
      continue;
    }
    seenTypes.add(type);

    // resolution 은 드롭 사유로 삼지 않는다 — 값 하나 때문에 엔티티 전체를 잃는 것보다
    // 보수적 기본값(embedding)으로 보정하는 편이 낫다. 사람이 편집 UI 에서 바꿀 수 있다.
    const rawResolution = str(re?.resolution);
    const resolution = (RESOLUTIONS as readonly string[]).includes(rawResolution ?? '')
      ? (rawResolution as ProposedEntity['resolution'])
      : 'embedding';

    const properties: ProposedProperty[] = [];
    const seenProps = new Set<string>();
    const rawProps = Array.isArray(re?.properties) ? (re.properties as Record<string, unknown>[]) : [];
    for (const rp of rawProps) {
      const name = str(rp?.name);
      if (!name) {
        dropped.push({ kind: 'property', detail: `name 이 없는 속성 (${type})` });
        continue;
      }
      if ((RESERVED_PROPERTY_NAMES as readonly string[]).includes(name)) {
        dropped.push({ kind: 'property', detail: `예약어 속성명: ${type}.${name}` });
        continue;
      }
      if (seenProps.has(name)) {
        dropped.push({ kind: 'property', detail: `중복 속성명: ${type}.${name}` });
        continue;
      }
      const dataType = str(rp?.dataType);
      if (!dataType || !(DATA_TYPES as readonly string[]).includes(dataType)) {
        dropped.push({ kind: 'property', detail: `허용되지 않은 dataType: ${type}.${name}=${dataType}` });
        continue;
      }
      seenProps.add(name);
      properties.push({
        name,
        description: str(rp?.description) ?? '',
        dataType: dataType as ProposedProperty['dataType'],
        unit: str(rp?.unit),
      });
    }

    entities.push({
      type,
      description: str(re?.description) ?? '',
      naming: str(re?.naming) ?? '본문에 등장한 표기를 그대로 사용한다.',
      resolution,
      properties,
    });
  }

  // 관계는 살아남은 엔티티만 끝점으로 가질 수 있다(드롭된 엔티티를 가리키면 함께 버린다).
  // 백엔드 OntologyService.validateCore 가 subject|relation|object 중복 트리플을
  // seenTriples Set 으로 무조건 거부하므로(draft 경로도 예외 없음), 여기서 먼저 걸러야
  // "필터는 통과했지만 백엔드가 스펙 전체를 400 으로 거부"하는 사고를 막을 수 있다.
  const relations: ProposedRelation[] = [];
  const seenTriples = new Set<string>();
  for (const rr of rawRelations as Record<string, unknown>[]) {
    const subject = str(rr?.subject);
    const relation = str(rr?.relation);
    const object = str(rr?.object);
    if (!subject || !relation || !object) {
      dropped.push({ kind: 'relation', detail: `형식 오류 관계: ${JSON.stringify(rr)}` });
      continue;
    }
    if (!seenTypes.has(subject) || !seenTypes.has(object)) {
      const missing = !seenTypes.has(subject) ? subject : object;
      dropped.push({ kind: 'relation', detail: `제안 엔티티 밖 끝점: ${subject}-[${relation}]->${object} (${missing})` });
      continue;
    }
    const tripleKey = `${subject}|${relation}|${object}`;
    if (seenTriples.has(tripleKey)) {
      dropped.push({ kind: 'relation', detail: `중복 트리플: ${subject}-[${relation}]->${object}` });
      continue;
    }
    seenTriples.add(tripleKey);
    relations.push({ subject, relation, object, description: str(rr?.description) ?? '' });
  }

  return { entities, relations, dropped };
}

// 표 컬럼 한 줄 요약. mapping-inference 의 프로파일 표기와 형식을 맞춘다.
function tableEvidenceLines(profiles: ColumnProfile[]): string {
  return profiles
    .filter((p) => p.ontologyDataType !== null) // GEOMETRY 등은 노드 속성이 될 수 없다
    .map(
      (p) =>
        `  - ${p.columnName} (타입=${p.ontologyDataType}, 고유값=${p.distinctCount}, ` +
        `null비율=${p.nullRatio.toFixed(2)}, PK=${p.isPrimaryKey}, 예시=[${p.sampleValues.join(', ')}])`,
    )
    .join('\n');
}

/** 도메인 · 힌트 · 표/문서 근거를 하나의 시스템 프롬프트로 조립한다. */
export function buildOntologyInferencePrompt(
  domain: string,
  hint: string | undefined,
  evidence: DatasetEvidence[],
): string {
  const blocks = evidence.map((e) =>
    e.kind === 'table'
      ? `### [표] ${e.name} (datasetId=${e.datasetId})\n${tableEvidenceLines(e.profiles) || '  (매핑 가능한 컬럼 없음)'}`
      : `### [문서] ${e.name} (datasetId=${e.datasetId})\n${e.chunks.map((c) => `  ---\n  ${c}`).join('\n')}`,
  );

  return [
    '당신은 지식그래프 온톨로지(스키마) 설계 전문가입니다.',
    `"${domain}" 도메인의 온톨로지 초안을 아래 실제 데이터 근거로부터 설계하세요.`,
    hint ? `사용자 힌트: ${hint}` : '',
    '',
    '## 설계 원칙',
    '- 컬럼을 1:1로 옮기지 마세요. 여러 데이터셋에 공통으로 나타나는 **개념**을 엔티티 타입으로 올리세요.',
    '- 엔티티 타입은 영문 PascalCase, 관계명은 영문 SCREAMING_SNAKE_CASE 로 지으세요.',
    '- 확신이 없으면 생략하세요(틀린 타입보다 누락이 낫습니다).',
    '',
    '## 제약(위반 시 해당 항목은 버려집니다)',
    `- 속성 dataType 은 ${DATA_TYPES.join(' | ')} 셋 중 하나입니다.`,
    `- 엔티티 resolution 은 ${RESOLUTIONS.join(' | ')} 중 하나입니다(표기가 흔들리면 embedding).`,
    `- 다음은 예약어라 엔티티 타입명·속성명으로 쓸 수 없습니다: ${RESERVED_PROPERTY_NAMES.join(', ')}`,
    '- 관계의 subject/object 는 당신이 제안한 엔티티 타입 안에 있어야 합니다.',
    '',
    '## 근거 데이터',
    blocks.join('\n\n') || '(근거 없음 — 도메인 지식으로 설계하세요)',
    '',
    '## 출력 형식 (JSON only, ```json 블록으로 감쌀 것)',
    '{ "entities": [ { "type": "Inspection", "description": "…", "naming": "…", "resolution": "embedding",',
    '    "properties": [ { "name": "inspectedAt", "description": "…", "dataType": "date", "unit": null } ] } ],',
    '  "relations": [ { "subject": "Inspection", "relation": "TARGETS", "object": "Building", "description": "…" } ] }',
  ]
    .filter((l) => l !== '')
    .join('\n');
}

// 실패 결과는 매번 새 객체로 만든다. 모듈 레벨 상수 하나를 공유해 돌려주면, 호출부가
// 그 결과를 제자리에서 변형(result.entities.push(...))하는 순간 이후 모든 실패 응답이
// 오염된다 — 장수 프로세스(MCP 서버)라 한 번 오염되면 재기동 전까지 남는다.
const empty = (): OntologyInferenceResult => ({ entities: [], relations: [], dropped: [] });

/**
 * LLM 을 호출해 온톨로지 제안을 얻고 검증 필터를 적용한다.
 * 실패(호출 오류·JSON 파싱 실패)는 throw 하지 않고 빈 결과로 돌려준다 —
 * 호출부가 "빈 결과면 저장하지 않는다"로 일관되게 처리할 수 있도록.
 */
export async function inferOntology(
  deps: { complete: CompleteFn },
  domain: string,
  hint: string | undefined,
  evidence: DatasetEvidence[],
): Promise<OntologyInferenceResult> {
  let content = '';
  try {
    content = await deps.complete(
      buildOntologyInferencePrompt(domain, hint, evidence),
      '위 근거로 온톨로지 JSON 을 출력하세요.',
    );
  } catch (err) {
    console.warn('[graphrag] inferOntology LLM 호출 실패, 빈 결과 반환:', err);
    return empty();
  }
  const parsed = parseJsonBlock(content);
  if (!parsed) return empty();
  return filterProposal(parsed);
}
