/**
 * API 연결 추가 인증 헤더(WD-753) — 화면의 행 목록 ↔ 연결 authConfig 변환과 행 검사.
 *
 * 네이버 API HUB 처럼 인증 헤더가 둘 이상 필요한 API 를 연결 하나로 인증하도록, 서버는 authConfig 에 `extraHeader:<헤더 이름>` 키로
 * 추가 헤더를 둔다(키·값 모두 평평한 문자열이라 암호화·가림·가린 값 되돌리기가 기존 키 단위 규칙을 그대로 탄다). 값은 비밀이라 조회 응답
 * (maskedAuthConfig)에는 「****」+끝 4자로 가려 내려오고, 수정 때 가린 값을 그대로 되돌려 보내면 서버가 저장 원문을 유지한다.
 *
 * 생성 대화상자(ApiConnectionListPage)와 상세 편집(ApiConnectionDetailPage)이 같은 규칙을 쓰도록 이 파일 한 곳에 둔다.
 * 최종 검사(헤더 이름 토큰·예약 헤더·메인 인증 헤더와 겹침·줄바꿈)는 서버(AuthConfigValidator)가 하고 400 문구로 안내한다 —
 * 화면은 흔한 실수(이름 빠짐·값 빠짐·이름 겹침·ASCII 밖 값·개수 상한)만 먼저 막는다. 개수 상한은 재조회가 다른 곳의 헤더를 더해 행이
 * 버튼으로는 못 넘는 수(11개)가 될 수 있어 저장 전에 보이게 한다(WD-804).
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
/** 행 오류 문구 — 보낼 행이 상한(MAX_EXTRA_HEADERS)을 넘을 때(서버 AuthConfigValidator 문구와 같다, WD-804) */
const TOO_MANY_HEADERS = `추가 헤더는 최대 ${MAX_EXTRA_HEADERS}개까지 넣을 수 있습니다`;
/** 행 오류 문구 — 값에 HTTP 헤더로 보낼 수 없는 글자(한글·특수 공백·제어 문자)가 있을 때(WD-780) */
const VALUE_NON_ASCII = '영문·숫자·기호(ASCII)만 쓸 수 있습니다';

/**
 * 추가 헤더 값에 HTTP 헤더로 그대로 보낼 수 없는 글자가 있는지(WD-780) — 서버 AuthConfigValidator 의 값 검사(줄바꿈·ASCII 밖 글자)를
 * 한 문구로 먼저 막는다.
 *
 * 허용은 출력 가능한 ASCII(0x20~0x7E)와 탭(0x09)뿐이다. 서버는 앞뒤 공백을 Java strip 으로 뗀 값을 보므로 여기서도 javaStrip 뒤에 본다 —
 * NBSP(U+00A0)는 Java 공백이 아니라 앞뒤에 있어도 남아 거절된다(서버와 같다). 줄바꿈(CR·LF)은 서버가 「줄바꿈」 문구로 따로 알리지만, 값 칸은 한 줄
 * 입력이라 붙여 넣기로만 들어오므로 화면은 같은 ASCII 문구로 묶는다. 예전에는 화면이 이 규칙을 몰라, 저장하면 서버 400 이 토스트로만 보이고 어느
 * 행인지 표시·포커스가 없었다.
 *
 * 값 칸에는 사용자가 새로 입력한 값만 있다 — 저장된 행의 가린 값은 storedMasked 에 따로 두고 값 칸은 비운 채 시작하므로, 「****」로 시작하는 값도
 * 사용자가 친 값이라 그대로 검사한다(접두어로 건너뛰면 「****한글」이 화면 검사를 지나 서버 가린 값 오류로 바뀐다).
 */
function hasNonAsciiHeaderValue(value: string): boolean {
  return /[^\t\x20-\x7e]/.test(javaStrip(value));
}

/**
 * 이 행을 서버로 보내는지 — 이름이나 값 중 하나라도 있으면 보낸다(extraHeadersToConfig 가 이름·값이 모두 빈 행만 버린다).
 * 개수 상한 검사(extraHeaderRowErrors)·상한 오류 정리(withoutChangedRowErrors)가 같은 기준으로 세도록 한곳에 둔다(WD-804 리뷰).
 */
function isSendableExtraHeaderRow(row: ExtraHeaderRow): boolean {
  return javaStrip(row.name) !== '' || !isBlankLikeServer(row.value);
}

/** 보낼 행 id 를 순서대로 — 개수 초과 행 고르기(extraHeaderRowErrors)와 보낼 행 모음이 바뀌었는지 보기(withoutChangedRowErrors)가 함께 쓴다(WD-804). */
function sendableExtraHeaderRowIds(rows: ExtraHeaderRow[]): string[] {
  return rows.filter(isSendableExtraHeaderRow).map((row) => row.id);
}

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
    if (!isSendableExtraHeaderRow(row)) continue;
    const name = javaStrip(row.name);
    const valueBlank = isBlankLikeServer(row.value);
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
 * 한 행에는 하나만 단다 — 개수 초과(WD-804, 보낼 행 기준 11번째부터, 서버도 개수를 먼저 본다) → 이름 빠짐 → 이름 겹침(앞 행과 대소문자 무시로 같으면 뒤 행에) → 값 빠짐(유지할 저장값이 없을 때)
 * → 값의 ASCII 밖 글자(hasNonAsciiHeaderValue, 서버 규칙과 같다 — WD-780) 순.
 * 이름·값이 모두 빈 행은 보내지 않으므로 오류도 없다.
 */
export function extraHeaderRowErrors(rows: ExtraHeaderRow[]): Record<string, string> {
  const errors: Record<string, string> = {};
  const seen = new Set<string>();
  // 보낼 행 중 상한을 넘는 행(11번째부터) — extraHeadersToConfig 가 버리는 행(이름·값이 모두 빈 행)은 세지 않는다(WD-804)
  const overflow = new Set(sendableExtraHeaderRowIds(rows).slice(MAX_EXTRA_HEADERS));
  for (const row of rows) {
    if (overflow.has(row.id)) {
      // 이름 칸 오류(isExtraHeaderValueError 아님)라 포커스가 넘친 첫 행의 이름 칸으로 간다
      errors[row.id] = TOO_MANY_HEADERS;
      continue;
    }
    const name = javaStrip(row.name);
    const valueBlank = isBlankLikeServer(row.value);
    if (!name) {
      if (!valueBlank) errors[row.id] = NAME_MISSING;
      continue;
    }
    const lower = normalizedExtraHeaderName(row.name);
    if (seen.has(lower)) {
      errors[row.id] = NAME_DUPLICATE;
      continue;
    }
    seen.add(lower);
    // 값을 비운 저장 행은 가린 값을 보내 저장 원문을 유지하므로 ASCII 검사는 새로 입력한 값에만 한다.
    if (valueBlank) {
      if (!keptStoredMasked(row)) errors[row.id] = VALUE_MISSING;
    } else if (hasNonAsciiHeaderValue(row.value)) {
      errors[row.id] = VALUE_NON_ASCII;
    }
  }
  return errors;
}

/**
 * 행을 고친 뒤 남길 오류 — 이름·값이 바뀌었거나 지운 행의 오류만 지운다(다른 행 오류는 고칠 때까지 남긴다, 메인 칸 clearError 와 같은 규칙).
 * 다만 개수 초과(TOO_MANY_HEADERS)는 그 행 하나가 아니라 보낼 행 전체에 걸린 오류라, 보낼 행의 모음·순서가 바뀌면(다른 행을 지우거나 비우거나
 * 채우거나, 재조회 병합으로 행이 바뀌면) 모두 지운다 — 그대로 두면 다른 행을 지워 10개로 맞춰도 넘쳤던 행의 오류가 남는다(WD-804 리뷰).
 * 아직 넘치면 다음 저장 검사(extraHeaderRowErrors)가 다시 단다.
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
  // 보낼 행 id 순서가 달라졌으면 개수 초과 오류는 낡은 것이라 함께 지운다(빈 행만 더하거나 지운 경우는 그대로 넘치므로 남긴다)
  const before = sendableExtraHeaderRowIds(prevRows);
  const after = sendableExtraHeaderRowIds(nextRows);
  const sameSendable = before.length === after.length && before.every((id, i) => id === after[i]);
  if (!sameSendable) {
    changed.push(...Object.keys(errors).filter((id) => errors[id] === TOO_MANY_HEADERS));
  }
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

/** 행 오류가 값 칸의 것인지(값 빠짐·ASCII 밖 값) — 그 밖(이름 빠짐·겹침)은 이름 칸의 오류다. 오류 문구 연결·포커스 칸을 고를 때 쓴다. */
export function isExtraHeaderValueError(message: string | undefined): boolean {
  return message === VALUE_MISSING || message === VALUE_NON_ASCII;
}

/**
 * 화면 순서상 첫 오류 행의 해당 입력(값 빠짐·ASCII 밖 값이면 값 칸, 그 밖은 이름 칸)으로 포커스를 옮긴다. 오류가 있었으면 true —
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

/**
 * 행 이름의 비교용 정규형 — 서버와 같은 javaStrip 으로 앞뒤 공백을 떼고 소문자로 만든다.
 * 서버는 헤더 이름을 대소문자 무시로 같은 헤더로 보므로, 중복 검사와 「같은 이름의 행이 있는가」 판정이 이 값으로 비교해야 어긋나지 않는다.
 */
export function normalizedExtraHeaderName(name: string): string {
  return javaStrip(name).toLowerCase();
}

/** 사용자가 지운 저장 헤더가 다른 곳에서 대소문자만 바뀐 이름으로 온 쌍 — from = 지운 옛 이름, to = 새 이름 */
export type RenamedPair = { from: string; to: string };

/**
 * 「지운 헤더가 다른 곳에서 이름이 바뀌었다」 토스트 문구를 만든다(WD-804).
 * 한 쌍이면 `옛 이(가) 다른 곳에서 새(으)로`, 여럿이면 `옛→새` 쌍을 이어 보인다. 이름 뒤 조사는 받침 유무를 알 수 없어 「이(가)」·「(으)로」 로 쓴다.
 * 문구를 페이지 밖 순수 함수로 두어 단위 시험으로 지킨다.
 */
export function renamedDeletedNotice(pairs: RenamedPair[]): string {
  // 두 문구가 같이 쓰는 꼬리 — 한 곳에 두어 문구가 어긋나지 않게 한다
  const tail = '. 지운 대로 두며, 저장하면 서버에서도 지워집니다.';
  if (pairs.length === 1) {
    return `지운 헤더 ${pairs[0].from} 이(가) 다른 곳에서 ${pairs[0].to}(으)로 바뀌었습니다${tail}`;
  }
  const joined = pairs.map((r) => `${r.from}→${r.to}`).join(', ');
  return `지운 헤더 ${joined} 이(가) 다른 곳에서 이름이 바뀌었습니다${tail}`;
}
