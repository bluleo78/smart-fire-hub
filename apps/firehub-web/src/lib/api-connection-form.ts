/**
 * API 연결 생성(목록 대화상자)·상세 편집 화면이 함께 쓰는 칸 검증·인라인 오류 도우미(WD-666).
 *
 * WD-561 이 두 화면에 칸별 인라인 오류를 달면서 「칸 하나의 오류 지우기」·「인증 칸 오류 모두 지우기」·「첫 오류 칸 포커스」와
 * 같은 칸 검증 문구가 화면마다 따로 복사됐다. 한쪽만 고쳐져 두 화면의 문구·규칙이 갈리지 않도록 이 파일 한 곳에 둔다.
 * 비밀 값(API 키·토큰)의 빈 값 판정은 서버와 글자 단위로 맞춘 {@link missingSecretMessage}(api-connection-secret.ts) 를 그대로 쓴다.
 */

/** 칸 key → 오류 문구. 오류가 없는 칸은 키가 없다. */
export type FieldErrors<K extends string> = Partial<Record<K, string>>;

/** 인증 편집 칸 — 인증 유형·위치를 바꾸면 이 칸들의 오류를 한꺼번에 지운다(숨겨진 칸 오류가 남지 않게). */
export const AUTH_FIELD_KEYS = ['authName', 'apiKey', 'token'] as const;

/**
 * errors 에서 주어진 key 들만 지운 새 객체를 돌려준다.
 * 지울 오류가 하나도 없으면 같은 참조를 돌려줘 setState 가 불필요한 렌더를 일으키지 않게 한다.
 */
export function withoutKeys<K extends string>(prev: FieldErrors<K>, ...keys: K[]): FieldErrors<K> {
  if (!keys.some((key) => prev[key])) return prev;
  const next = { ...prev };
  for (const key of keys) delete next[key];
  return next;
}

/**
 * 화면 순서(order)상 첫 오류 칸의 입력으로 포커스를 옮긴다. 오류가 있었으면 true 를 돌려준다 —
 * 호출자는 true 면 저장·호출을 멈춘다. 오류 문구는 FormField 가 aria-describedby 로 이어 두므로 포커스만으로 함께 읽힌다.
 */
export function focusFirstError<K extends string>(
  order: readonly K[],
  errors: FieldErrors<K>,
  fieldId: Record<K, string>,
): boolean {
  const first = order.find((key) => errors[key]);
  if (!first) return false;
  document.getElementById(fieldId[first])?.focus();
  return true;
}

/** 연결 이름 — 비었으면 오류 문구. */
export function connectionNameError(name: string): string | undefined {
  return name.trim() ? undefined : '연결 이름을 입력하세요.';
}

/**
 * Base URL — 비었으면 빈 값 안내, checkFormat 이면 http/https 로 시작하는지도 본다.
 * 상세 편집은 예전처럼 빈 값만 막고 형식은 서버 검증에 맡긴다(checkFormat=false).
 */
export function baseUrlError(baseUrl: string, checkFormat = true): string | undefined {
  const value = baseUrl.trim();
  if (!value) return 'Base URL을 입력하세요.';
  if (checkFormat && !/^https?:\/\/.+/.test(value)) return 'Base URL은 http 또는 https로 시작해야 합니다.';
  return undefined;
}

/** 헬스체크 경로 — 선택 칸이라 비어도 되지만, 입력했다면 /로 시작해야 한다. */
export function healthCheckPathError(path: string): string | undefined {
  const value = path.trim();
  return value && !value.startsWith('/') ? '헬스체크 경로는 /로 시작해야 합니다.' : undefined;
}

/** API Key 의 헤더·파라미터 이름 — 위치(placement)에 따라 라벨이 달라 문구도 맞춘다. */
export function authNameError(placement: string, value: string): string | undefined {
  if (value.trim()) return undefined;
  return placement === 'header' ? '헤더 이름을 입력하세요.' : '파라미터 이름을 입력하세요.';
}
