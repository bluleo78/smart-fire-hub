/**
 * 지식 모델(온톨로지) 편집 검증 규칙 — 요소 단위 편집 폼(EntityInspector/RelationInspector, Task 4~6)의
 * 단일 소유자. 전체 문서를 왕복시키던 모달(OntologyEditDialog)은 Task 6에서 제거됐다 — 그 모달이
 * 쓰던 문구가 여전히 이 파일의 정본이다(byte-identical 유지, ontology-validation.test.ts 참고).
 *
 * 문구는 백엔드 `OntologyRules.java`가 아니라 **이 파일이 정한 문구**를 정본으로 삼는다 — 서버 쪽
 * OntologyRules와 공유해야 하는 것은 문자열이 아니라 **규칙**이다: 예약어 집합, dataType/resolution
 * 허용값, 그리고 진단 순서(blank → 예약어 → 중복, #302). 이 순서가 어긋나면 이름이 빈 속성 2개가
 * 서로 같은 키로 충돌해 "중복된 속성명"으로 오진단된다 — 실제 원인은 미입력인데.
 *
 * 로컬 검증은 서버 검증(OntologyRules)을 항상 완전히 포함해야 한다 — 정상 사용 시 서버 문구가
 * 사용자에게 노출되는 일은 없어야 하고, 서버 검증은 동시 편집 레이스(#301류)에 대한 최종 방어선일
 * 뿐이어야 한다.
 */
import type { Triple } from '@/types/ontology';

// Neo4j 노드 예약 필드(loader.ts 모델 (:Entity{key,type,name,sourceChunkIds,schemaVersion}))와 겹치는
// 속성명은 적재 시 SET n += props 가 노드 정체성 필드를 덮어쓰므로 편집 시점에 차단한다.
// 백엔드 OntologyRules.RESERVED_PROPERTY_NAMES와 동일한 집합(서비스 경계상 공유 불가, 수동 동기화).
export const RESERVED_PROPERTY_NAMES = new Set(['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion']);

// 속성 dataType 허용값 — 백엔드 OntologyRules.DATA_TYPES(DB CHECK(text|number|date))와 동일.
export const DATA_TYPES: Array<'text' | 'number' | 'date'> = ['text', 'number', 'date'];

// 엔티티 타입 resolution 허용값 — 백엔드 OntologyRules.RESOLUTIONS와 동일.
export const RESOLUTIONS: Array<'embedding' | 'exact'> = ['embedding', 'exact'];

/**
 * 속성명 검증 — blank 차단 후 예약어 차단(#302), 마지막으로 같은 스코프 내 중복 차단.
 * 무엇이 "같은 스코프"인지(같은 엔티티 타입 안)는 호출부가 `existing`으로 넘긴다.
 *
 * @param existing 중복 판정 대상 목록(호출부의 스코프). 자기 자신을 리네임하는 경우 `excludeName`으로
 *   그 이름을 목록에서 제외해, "model → model"처럼 값이 그대로인 편집이 중복으로 오진단되지 않게 한다.
 *
 * blank 분기는 EntityInspector의 단일 필드 폼(행 개념 없음)이 그대로 쓴다 — "속성명을 입력하세요"
 * 처럼 행 인덱스가 없는 문구다. 예전 모달(OntologyEditDialog, Task 6에서 제거)은 행 인덱스를 담은
 * 별도 문구(`이름이 비어 있는 속성이 있습니다(…, N번째 행)`)를 직접 냈었다 — 그 문구는 이제 어디서도
 * 쓰이지 않는다.
 */
export function validatePropertyName(
  name: string,
  typeName: string,
  existing: string[],
  excludeName?: string,
): string | null {
  // blank 검사를 예약어/중복보다 먼저 둔다 — 순서를 지키지 않으면 이름이 빈 속성 2개가 ''끼리
  // 충돌해 "중복된 속성명"으로 오진단된다(#302, 실제 원인은 미입력).
  // trim은 blank 판정에만 쓴다 — 예약어/중복은 백엔드 OntologyRules.validatePropertyName과 동일하게
  // raw 문자열로 비교하며, 호출부가 trim한 값을 넘길 책임을 진다.
  if (!name.trim()) {
    return '속성명을 입력하세요';
  }
  if (RESERVED_PROPERTY_NAMES.has(name)) {
    return `예약어는 속성명으로 쓸 수 없습니다(${typeName}): ${name}`;
  }
  const candidates = excludeName === undefined ? existing : existing.filter((n) => n !== excludeName);
  if (candidates.includes(name)) {
    return `중복된 속성명(${typeName}): ${name}`;
  }
  return null;
}

/**
 * 엔티티 타입명 검증 — blank 차단 후 중복 차단. `excludeName`은 리네임 시 자기 자신의 원래
 * 이름을 목록에서 빼, 이름을 바꾸지 않는 편집이 중복으로 오진단되지 않게 한다.
 */
export function validateEntityTypeName(type: string, existing: string[], excludeName?: string): string | null {
  const name = type.trim();
  if (!name) {
    return '타입 이름을 입력하세요.';
  }
  const candidates = excludeName === undefined ? existing : existing.filter((n) => n !== excludeName);
  if (candidates.includes(name)) {
    return `이미 존재하는 타입입니다: ${name}`;
  }
  return null;
}

/**
 * 온톨로지 도메인명 blank 차단(I-1, Task 6 리뷰) — OntologyCreateDialog가 생성 시점에 쓰던 것과
 * 같은 규칙·문구를 편집 시점(ModelOutline)에도 재사용한다. 도메인명 중복은 이 함수가 판정하지
 * 않는다 — 다른 온톨로지 목록(도메인·상태)이 이 함수의 호출부(ModelOutline)에 넘어오지 않기
 * 때문이다(NEW-5, Task 6 리뷰 라운드2 — "판정할 목록이 없다"는 이전 서술은 부정확했다: OntologyPage
 * 는 그 목록을 이미 들고 있고, 넘기지 않기로 한 것은 선택이지 불가능이 아니다). 정상 사용 시
 * 중복 입력은 드물다고 보고(OntologyCreateDialog가 이미 그 409를 보여준다), 서버(409)가 최종
 * 방어선으로 남는다.
 */
export function validateDomain(domain: string): string | null {
  if (!domain.trim()) {
    return '도메인명을 입력하세요.';
  }
  return null;
}

/**
 * 관계명 blank 차단. 트리플(subject|relation|object) 중복은 여러 관계를 동시에 봐야 하는
 * 문서 단위 불변식이라 별도 함수(validateTripleUniqueness)로 분리돼 있다.
 */
export function validateRelationName(relation: string, subjectName: string, objectName: string): string | null {
  if (!relation.trim()) {
    return `관계명을 입력하세요(${subjectName} → ${objectName})`;
  }
  return null;
}

/**
 * 트리플(subject|relation|object) 중복 검사 — 문서 전체 관계 목록을 봐야 하는 불변식이라
 * validateRelationName과 분리한다. RelationInspector(Task 5, 요소 단위 생성/편집)의 단일 소유자다 —
 * 전에는 전체 문서 모달(OntologyEditDialog, Task 6에서 제거) 안에 인라인 Set 검사로만 있었는데,
 * 요소 단위 편집기가 생기면서 두 곳에 같은 규칙이 따로 자라는 걸 막기 위해 여기로 옮겼다.
 *
 * @param relations 중복 판정 대상 목록. 호출부마다 무엇을 넘기는지가 다르다:
 *   - RelationInspector의 생성 폼은 schema.relations 전체를 넘긴다(새 관계는 아직 그 안에 없다).
 *   - RelationInspector의 편집 폼(관계명 리네임)도 schema.relations 전체를 넘기되, 편집 대상 자신은
 *     이미 그 목록 안에 있으므로 excludeId로 제외해야 한다.
 * @param excludeId 리네임 시 자기 자신의 id를 제외한다 — 없으면 이름을 바꾸지 않은 편집(원래 이름을
 *   그대로 두거나 되돌리는 경우)이 스스로와 충돌해 중복으로 오진단된다. undefined면 아무것도
 *   제외하지 않는다(id 없는 관계끼리도 정상 비교된다 — `r.id !== excludeId`로만 비교하면 둘 다
 *   undefined일 때 `undefined !== undefined`가 false가 되어 그 관계가 조용히 비교 대상에서 빠지는
 *   함정이 있어, excludeId가 실제로 주어졌을 때만 그 필드를 본다).
 *
 * 문구는 이 파일이 정한 정본(`중복된 관계: S -R-> O`)을 그대로 쓴다 — 서버(OntologyRules)의
 * 파이프 구분 문구(`S|R|O`)와 다르다. 이 파일 헤더의 전역 제약("정상 사용 시 서버 문구가 노출되면
 * 안 된다")을 지키려면 로컬 검사가 서버 검사를 완전히 포함해야 한다.
 */
export function validateTripleUniqueness(
  relations: Triple[],
  subject: string,
  relation: string,
  object: string,
  excludeId?: number,
): string | null {
  const duplicate = relations.some(
    (r) =>
      (excludeId === undefined || r.id !== excludeId) &&
      r.subject === subject &&
      r.relation === relation &&
      r.object === object,
  );
  if (duplicate) {
    return `중복된 관계: ${subject} -${relation}-> ${object}`;
  }
  return null;
}
