/**
 * API 연결 추가 인증 헤더(WD-753) — 화면의 행 목록 ↔ 연결 authConfig 변환과 행 검사.
 *
 * 네이버 API HUB 처럼 인증 헤더가 둘 이상 필요한 API 를 연결 하나로 인증하도록, 서버는 authConfig 에 `extraHeader:<헤더 이름>` 키로
 * 추가 헤더를 둔다(키·값 모두 평평한 문자열이라 암호화·가림·가린 값 되돌리기가 기존 키 단위 규칙을 그대로 탄다). 값은 비밀이라 조회 응답
 * (maskedAuthConfig)에는 「****」+끝 4자로 가려 내려오고, 수정 때 가린 값을 그대로 되돌려 보내면 서버가 저장 원문을 유지한다.
 *
 * 생성 대화상자(ApiConnectionListPage)와 상세 편집(ApiConnectionDetailPage)이 같은 규칙을 쓰도록 이 파일 한 곳에 둔다.
 * 최종 검사(헤더 이름 토큰·예약 헤더·메인 인증 헤더와 겹침·개수 상한·줄바꿈)는 서버(AuthConfigValidator)가 하고 400 문구로 안내한다 —
 * 화면은 흔한 실수(이름 빠짐·값 빠짐·이름 겹침)만 먼저 막는다.
 */

import { focusFirstError, withoutKeys } from '@/lib/api-connection-form';
import { isBlankLikeServer, javaStrip } from '@/lib/api-connection-secret';

/** authConfig 의 추가 헤더 키 접두어 — 서버 AuthConfigValidator.EXTRA_HEADER_PREFIX 와 같다. 보낼 때 쓰는 정식 모양이고, 판정은 isExtraHeaderKey 로 한다. */
export const EXTRA_HEADER_PREFIX = 'extraHeader:';

/** 접두어 판정용 소문자 모양 — 접두어 글자가 모두 ASCII 라 소문자로 맞춰 비교해도 서버 regionMatches(ignoreCase) 와 같다. */
const EXTRA_HEADER_PREFIX_LOWER = EXTRA_HEADER_PREFIX.toLowerCase();

/**
 * authConfig 키가 추가 헤더 키인지 — 접두어는 대소문자를 가리지 않는다(WD-772 리뷰).
 *
 * 서버(AuthConfigValidator.isExtraHeaderKey)·Python 실행기·AI 도구가 모두 대소문자 무시로 판정한다. 화면만 가리면 AI 챗·API 로 저장된
 * 「ExtraHeader:X-Id」 가 추가 헤더 행에 안 보이고, 인증 저장 때 authConfig 가 통째로 바뀌어 그 헤더가 조용히 지워졌다. 행 꺼내기·메인 칸 거르기가
 * 이 판정 하나를 쓴다.
 */
export function isExtraHeaderKey(key: string): boolean {
  return key.slice(0, EXTRA_HEADER_PREFIX.length).toLowerCase() === EXTRA_HEADER_PREFIX_LOWER;
}

/** 연결 하나에 둘 수 있는 추가 헤더 수 — 서버 상한과 같다(넘으면 「헤더 추가」를 끈다). */
export const MAX_EXTRA_HEADERS = 10;

/**
 * 추가 헤더 편집 행.
 * - id: React key·오류 위치·입력 id 에 쓰는 화면 안 고유 값(서버로 보내지 않는다).
 * - value: 사용자가 새로 입력한 값. 저장된 행은 비운 채 시작한다(원문은 화면에 오지 않는다).
 * - storedMasked: 저장된 행의 가린 값. 값을 비워 두면 이 값을 보내 저장 원문을 유지한다.
 * - storedName: 저장된 행의 원래 이름(키에서 접두어만 뗀 값). 이름을 바꾸면 가린 값은 원래 이름의 저장값이라 쓸 수 없고,
 *   앞뒤 공백을 뗀 이름이 원래 이름과 다시 같아지면 다시 쓸 수 있다(keptStoredMasked).
 */
export interface ExtraHeaderRow {
  id: string;
  name: string;
  value: string;
  storedMasked?: string;
  storedName?: string;
}

/** 행 오류 문구 — 이름이 빠졌을 때(값만 있음) */
const NAME_MISSING = '헤더 이름을 입력하세요';
/** 행 오류 문구 — 유지할 저장값이 없는 행의 값이 비었을 때 */
const VALUE_MISSING = '값을 입력하세요';
/** 행 오류 문구 — 다른 행과 이름이 겹칠 때(HTTP 헤더 이름은 대소문자를 가리지 않는다) */
const NAME_DUPLICATE = '이름이 겹칩니다';

/** 화면 안 행 id 순번 — crypto.randomUUID 는 http(비보안 출처)에서 없을 수 있어 단순 순번을 쓴다. */
let rowSeq = 0;

/** 이름·값이 빈 새 행(「헤더 추가」). */
export function newExtraHeaderRow(): ExtraHeaderRow {
  rowSeq += 1;
  return { id: `extra-header-${rowSeq}`, name: '', value: '' };
}

/**
 * 조회한 가린 config 에서 추가 헤더 행을 만든다 — 값은 비우고 가린 값은 storedMasked 에 둔다(편집 시작·편집 취소 때 쓴다).
 * 이름은 키 그대로(접두어만 뗀다) 두어, 고치지 않고 저장하면 같은 이름으로 돌아가 서버가 가린 값을 저장 원문으로 되돌린다.
 * 접두어는 대소문자와 상관없이 알아보고(isExtraHeaderKey, WD-772 리뷰), 저장할 때는 정식 접두어로 보낸다 — 서버가 저장 키의 접두어도 정식 모양으로
 * 맞춰 가린 값을 찾으므로(normalizeExtraHeaderKey) 「ExtraHeader:X-Id」 로 저장된 값도 유지된다.
 */
export function extraHeaderRowsFromConfig(config: Record<string, string> | undefined): ExtraHeaderRow[] {
  if (!config) return [];
  return Object.entries(config)
    .filter(([key]) => isExtraHeaderKey(key))
    .map(([key, masked]) => {
      const name = key.slice(EXTRA_HEADER_PREFIX.length);
      return { ...newExtraHeaderRow(), name, storedMasked: masked, storedName: name };
    });
}

/**
 * 이 행이 지금 유지할 수 있는 저장값(가린 값) — 저장된 행이고, 앞뒤 공백을 뗀 이름이 원래 이름(storedName, 같은 정리)과 같을 때만 있다.
 * 이름을 바꾸면 다른 헤더라 원래 이름의 가린 값을 보낼 수 없고(서버가 거절), 이름을 원래대로 되돌리면 다시 「저장된 값 유지」가 된다(WD-753 리뷰).
 * 서버는 저장 키를 앞뒤 공백을 뗀 이름으로 맞추므로(AuthConfigValidator.normalizeExtraHeaderKey) 공백만 다른 이름은 같은 헤더다.
 */
export function keptStoredMasked(row: ExtraHeaderRow): string | undefined {
  if (!row.storedMasked) return undefined;
  // storedName 이 없는 행(옛 방식으로 만든 행)은 가린 값을 그대로 쓴다 — 이름 비교 기준이 없다.
  if (row.storedName === undefined) return row.storedMasked;
  return javaStrip(row.name) === javaStrip(row.storedName) ? row.storedMasked : undefined;
}

/**
 * 행 목록을 authConfig 조각(`extraHeader:<이름>` → 값)으로 바꾼다 — 메인 인증 칸 authConfig 에 합쳐 보낸다.
 * 이름은 서버 `String.strip()` 과 같은 공백 집합으로 앞뒤 공백을 떼고, 이름·값이 모두 빈 행은 버린다(추가만 하고 채우지 않은 행). 값이 비고 저장된 가린 값이 있으면
 * 그 가린 값을 보내 저장 원문을 유지한다(상세 키 칸의 storedMaskedKey 와 같은 규칙, 이름을 바꾼 행은 keptStoredMasked 가 없음). 값은 서버처럼 정리하지 않고 그대로 보낸다.
 */
export function extraHeadersToConfig(rows: ExtraHeaderRow[]): Record<string, string> {
  const config: Record<string, string> = {};
  for (const row of rows) {
    const name = javaStrip(row.name);
    const valueBlank = isBlankLikeServer(row.value);
    if (!name && valueBlank) continue;
    const kept = keptStoredMasked(row);
    config[`${EXTRA_HEADER_PREFIX}${name}`] = valueBlank && kept ? kept : row.value;
  }
  return config;
}

/**
 * 추가 헤더 키를 뺀 새 config — 가린 config 로 메인 인증 칸(위치·헤더 이름·키 등)을 채우거나 보일 때,
 * 접두어 키(대소문자 무관, isExtraHeaderKey)가 메인 인증 값으로 섞이지 않게 한다. 원본은 건드리지 않는다.
 */
export function withoutExtraHeaders(config: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(config).filter(([key]) => !isExtraHeaderKey(key)));
}

/**
 * 행 id → 오류 문구. 오류가 없는 행은 키가 없다.
 * 한 행에는 하나만 단다 — 이름 빠짐 → 이름 겹침(앞 행과 대소문자 무시로 같으면 뒤 행에) → 값 빠짐(유지할 저장값이 없을 때) 순.
 * 이름·값이 모두 빈 행은 보내지 않으므로 오류도 없다.
 */
export function extraHeaderRowErrors(rows: ExtraHeaderRow[]): Record<string, string> {
  const errors: Record<string, string> = {};
  const seen = new Set<string>();
  for (const row of rows) {
    const name = javaStrip(row.name);
    const valueBlank = isBlankLikeServer(row.value);
    if (!name) {
      if (!valueBlank) errors[row.id] = NAME_MISSING;
      continue;
    }
    const lower = name.toLowerCase();
    if (seen.has(lower)) {
      errors[row.id] = NAME_DUPLICATE;
      continue;
    }
    seen.add(lower);
    if (valueBlank && !keptStoredMasked(row)) errors[row.id] = VALUE_MISSING;
  }
  return errors;
}

/**
 * 행을 고친 뒤 남길 오류 — 이름·값이 바뀌었거나 지운 행의 오류만 지운다(다른 행 오류는 고칠 때까지 남긴다, 메인 칸 clearError 와 같은 규칙).
 * 지울 오류가 없으면 같은 참조를 돌려줘 setState 가 불필요한 렌더를 일으키지 않게 한다(공용 withoutKeys 의 성질).
 */
export function withoutChangedRowErrors(
  errors: Record<string, string>,
  prevRows: ExtraHeaderRow[],
  nextRows: ExtraHeaderRow[],
): Record<string, string> {
  const changed = Object.keys(errors).filter((id) => {
    const before = prevRows.find((row) => row.id === id);
    const after = nextRows.find((row) => row.id === id);
    return !after || before?.name !== after.name || before?.value !== after.value;
  });
  // 지우기·같은 참조 유지는 메인 칸 오류와 같은 공용 withoutKeys 를 쓴다. 행 오류 Map 은 값이 항상 문구라 Record 로 되돌려 읽어도 안전하다.
  return withoutKeys(errors, ...changed) as Record<string, string>;
}

/** 행 이름 입력 id — 첫 오류 칸 포커스와 컴포넌트가 같은 id 를 쓴다(행 id 가 화면 안에서 고유하다). */
export function extraHeaderNameInputId(rowId: string): string {
  return `${rowId}-name`;
}

/** 행 값 입력 id */
export function extraHeaderValueInputId(rowId: string): string {
  return `${rowId}-value`;
}

/** 행 오류가 값 칸의 것인지(값 빠짐) — 그 밖(이름 빠짐·겹침)은 이름 칸의 오류다. 오류 문구 연결·포커스 칸을 고를 때 쓴다. */
export function isExtraHeaderValueError(message: string | undefined): boolean {
  return message === VALUE_MISSING;
}

/**
 * 화면 순서상 첫 오류 행의 해당 입력(값 빠짐이면 값 칸, 그 밖은 이름 칸)으로 포커스를 옮긴다. 오류가 있었으면 true —
 * 호출자는 메인 칸(focusFirstError) 다음에 불러, 둘 중 하나라도 참이면 저장·호출을 멈춘다.
 * 첫 오류 찾기·포커스는 메인 칸과 같은 공용 focusFirstError 를 쓰고, 행 id → 입력 id 매핑에서 오류 종류별 칸(값/이름)을 고른다.
 */
export function focusFirstExtraHeaderError(rows: ExtraHeaderRow[], errors: Record<string, string>): boolean {
  const fieldId = Object.fromEntries(
    rows.map((row) => [
      row.id,
      isExtraHeaderValueError(errors[row.id]) ? extraHeaderValueInputId(row.id) : extraHeaderNameInputId(row.id),
    ]),
  );
  return focusFirstError(
    rows.map((row) => row.id),
    errors,
    fieldId,
  );
}
