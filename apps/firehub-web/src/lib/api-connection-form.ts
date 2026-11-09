/**
 * API 연결 생성(목록 대화상자)·상세 편집 화면이 함께 쓰는 칸 검증·인라인 오류 도우미(WD-666).
 *
 * WD-561 이 두 화면에 칸별 인라인 오류를 달면서 「칸 하나의 오류 지우기」·「인증 칸 오류 모두 지우기」·「첫 오류 칸 포커스」와
 * 같은 칸 검증 문구가 화면마다 따로 복사됐다. 한쪽만 고쳐져 두 화면의 문구·규칙이 갈리지 않도록 이 파일 한 곳에 둔다.
 * 비밀 값(API 키·토큰·Basic 비밀번호)의 빈 값 판정은 서버와 글자 단위로 맞춘 {@link missingSecretMessage}(api-connection-secret.ts) 를 그대로 쓴다.
 */

import { isBlankLikeServer } from '@/lib/api-connection-secret';
import type { ApiConnectionAuthType, StoredApiConnectionAuthType } from '@/types/api-connection';

/** 칸 key → 오류 문구. 오류가 없는 칸은 키가 없다. */
export type FieldErrors<K extends string> = Partial<Record<K, string>>;

/**
 * 인증 편집 칸 — 인증 유형·위치를 바꾸면 이 칸들의 오류를 한꺼번에 지운다(숨겨진 칸 오류가 남지 않게).
 * 선언 순서가 화면 순서(첫 오류 포커스 기준)다 — Basic(WD-681)은 아이디 → 비밀번호 순이다.
 */
export const AUTH_FIELD_KEYS = ['authName', 'apiKey', 'token', 'username', 'password'] as const;

/** 만들기·상세 화면의 인증 방식 선택지(WD-681) — 서버가 받는 값과 같은 순서로 둔다. */
export const AUTH_TYPE_OPTIONS = ['API_KEY', 'BEARER', 'BASIC'] as const satisfies readonly ApiConnectionAuthType[];

/**
 * 인증 방식 표시 이름 — badge 는 목록·상세 머리의 짧은 배지, full 은 선택지·읽기 전용 보기의 이름(WD-681).
 * 예전에는 화면마다 `authType === 'BEARER' ? 'Bearer' : 'API Key'` 로 따로 써서 새 방식(BASIC)이 「API Key」로 보일 뻔했다.
 */
export const AUTH_TYPE_LABEL: Record<ApiConnectionAuthType, { badge: string; full: string }> = {
  API_KEY: { badge: 'API Key', full: 'API Key' },
  BEARER: { badge: 'Bearer', full: 'Bearer Token' },
  BASIC: { badge: 'Basic', full: 'Basic Auth' },
};

/**
 * 조회한 인증 방식의 표시 이름 — 받는 방식이면 {@link AUTH_TYPE_LABEL} 의 이름, 옛 OAUTH2 처럼 표에 없는 값은 서버 값 그대로.
 * 응답 타입이 OAUTH2 를 포함하므로 표를 바로 인덱싱하지 않고 여기서 한 번 가른다(목록 배지·상세 배지·상세 읽기 보기 공용).
 */
export function authTypeLabel(authType: StoredApiConnectionAuthType, kind: 'badge' | 'full'): string {
  return isApiConnectionAuthType(authType) ? AUTH_TYPE_LABEL[authType][kind] : authType;
}

/** 서버가 받는 인증 방식인지 — 선택지({@link AUTH_TYPE_OPTIONS})에 있는 값만 참이다. */
export function isApiConnectionAuthType(value: string): value is ApiConnectionAuthType {
  return (AUTH_TYPE_OPTIONS as readonly string[]).includes(value);
}

/** 인증 방식 배지 색 — Bearer 는 기본, 그 밖(API Key·Basic·옛 OAUTH2)은 보조 색(예전 화면 그대로). */
export function authTypeBadgeVariant(authType: StoredApiConnectionAuthType): 'default' | 'secondary' {
  return authType === 'BEARER' ? 'default' : 'secondary';
}

/**
 * Basic 인증 아이디(WD-681) — 서버 AuthConfigValidator.basicUsernameProblem 과 같은 규칙.
 * 비었는지는 서버 isBlank 와 같게 보고(NBSP 는 값), 콜론은 Basic 인증이 「아이디:비밀번호」 를 첫 콜론에서 나누므로 막는다.
 * 아이디는 앞뒤 공백을 떼지 않고 그대로 보낸다(서버도 그대로 쓴다). 줄바꿈 등 나머지는 서버 400 안내에 맡긴다.
 */
export function basicUsernameError(username: string): string | undefined {
  if (isBlankLikeServer(username)) return '아이디를 입력하세요.';
  if (username.includes(':')) return '아이디에 콜론(:)을 넣을 수 없습니다.';
  return undefined;
}

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
