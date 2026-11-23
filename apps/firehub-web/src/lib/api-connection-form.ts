/**
 * API 연결 생성(목록 대화상자)·상세 편집 화면이 함께 쓰는 칸 검증·인라인 오류 도우미(WD-666).
 *
 * WD-561 이 두 화면에 칸별 인라인 오류를 달면서 「칸 하나의 오류 지우기」·「인증 칸 오류 모두 지우기」·「첫 오류 칸 포커스」와
 * 같은 칸 검증 문구가 화면마다 따로 복사됐다. 한쪽만 고쳐져 두 화면의 문구·규칙이 갈리지 않도록 이 파일 한 곳에 둔다.
 * 비밀 값(API 키·토큰·Basic 비밀번호)의 빈 값 판정은 서버와 글자 단위로 맞춘 {@link missingSecretMessage}(api-connection-secret.ts) 를 그대로 쓴다.
 */

import { isBlankLikeServer } from '@/lib/api-connection-secret';
import { httpUrlError, stripUrlUserInfo } from '@/lib/http-url';
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

/** 연결 이름 최대 길이 — 서버 Create/UpdateApiConnectionRequest 의 @Size(max = 100)와 같다(WD-1007). */
const MAX_CONNECTION_NAME_LENGTH = 100;

/** Base URL 최대 길이 — 서버 Create/UpdateApiConnectionRequest 의 @Size(max = 500)와 같다(WD-1007). */
const MAX_BASE_URL_LENGTH = 500;

/**
 * 연결 이름 — 비었으면 오류 문구, 100자를 넘으면 길이 문구.
 * 길이는 서버가 받는 값(앞뒤 공백을 뗀 값)으로 센다. maxLength 속성은 붙여넣기를 말없이 잘라 버리므로 쓰지 않고
 * 문구로 알려 서버 400 을 미리 막는다(WD-1007).
 */
export function connectionNameError(name: string): string | undefined {
  const value = name.trim();
  if (!value) return '연결 이름을 입력하세요.';
  if (value.length > MAX_CONNECTION_NAME_LENGTH) {
    return `연결 이름은 ${MAX_CONNECTION_NAME_LENGTH}자 이하로 입력하세요.`;
  }
  return undefined;
}

/**
 * API URL 입력칸 플레이스홀더 (WD-887).
 * "예:" 접두어로 바꿔 넣을 예시임을 드러내고, 호스트는 RFC 6761 예약 TLD `.example` 이라
 * 그대로 옮겨 적어도 공개 DNS 에서 풀리지 않아 남의 서버로 연결되지 않는다.
 */
export const API_BASE_URL_PLACEHOLDER = '예: https://api.your-service.example';
export const API_FULL_URL_PLACEHOLDER = `${API_BASE_URL_PLACEHOLDER}/v1/data`;

/**
 * Base URL — 비었으면 입력 안내, 그 밖에는 공용 http(s) 형식 검사({@link httpUrlError})를 따른다.
 * 공용 검사는 대문자 스킴(HTTPS://)을 받지만 서버 DTO 는 소문자 http(s):// 만 받으므로(대소문자를 가림),
 * API 연결은 소문자 스킴을 먼저 확인한다. 형식 실수만 미리 알리고 내부 주소·DNS·인증 방식에 따른 거절은 서버 안내를 따른다.
 * 생성·상세 편집이 같은 규칙을 쓴다(WD-909).
 */
export function baseUrlError(baseUrl: string): string | undefined {
  const value = baseUrl.trim();
  if (!value) return 'Base URL을 입력하세요.';
  // 서버 @Size(max = 500) 초과는 형식 검사보다 먼저 알린다(WD-1007).
  if (value.length > MAX_BASE_URL_LENGTH) {
    return `Base URL은 ${MAX_BASE_URL_LENGTH}자 이하로 입력하세요.`;
  }
  if (!/^https?:\/\//.test(value)) return 'Base URL은 http 또는 https로 시작해야 합니다.';
  // httpUrlError 는 호스트 밖 공백을 통과시키지만(실행기가 %20 으로 보냄), Base URL 은 서버가
  // URI.create 로 저장해 공백을 거절하므로 여기서는 막는다. 다만 사용자 정보는 서버가(BASIC) 그 전에 떼어
  // 인증 설정으로 옮기므로 그 안의 공백(비밀번호 등)은 막지 않는다.
  if (/\s/.test(stripUrlUserInfo(value))) return 'Base URL 형식이 올바르지 않습니다.';
  return httpUrlError(value, 'Base URL');
}

/** 헬스체크 경로 최대 길이 — 서버 DTO(@Size(max = 500))와 같다(WD-937 리뷰 후속). */
export const MAX_HEALTH_CHECK_PATH_LENGTH = 500;

/** 헬스체크 경로 칸 도움말 — 만들기 대화상자·상세 정보 카드가 같은 문구를 쓰도록 한 곳에 둔다(WD-937 리뷰 후속). */
export const HEALTH_CHECK_PATH_HELP = '10분마다 자동 상태 점검. 비워두면 점검 안 함.';

/**
 * 헬스체크 경로 — 선택 칸이라 비어도 되지만, 입력했다면 /로 시작하고 500자 이하여야 한다.
 * 길이는 서버가 받는 값(앞뒤 공백을 뗀 값)으로 센다. maxLength 속성은 붙여넣기를 말없이 잘라 버리므로 쓰지 않고
 * 문구로 알린다(WD-937 리뷰 후속). 생성 대화상자·상세 편집이 같은 규칙을 쓴다.
 */
export function healthCheckPathError(path: string): string | undefined {
  const value = path.trim();
  if (!value) return undefined;
  if (!value.startsWith('/')) return '헬스체크 경로는 /로 시작해야 합니다.';
  if (value.length > MAX_HEALTH_CHECK_PATH_LENGTH) {
    return `헬스체크 경로는 ${MAX_HEALTH_CHECK_PATH_LENGTH}자 이하로 입력하세요.`;
  }
  return undefined;
}

/** API Key 의 헤더·파라미터 이름 — 위치(placement)에 따라 라벨이 달라 문구도 맞춘다. */
export function authNameError(placement: string, value: string): string | undefined {
  if (value.trim()) return undefined;
  return placement === 'header' ? '헤더 이름을 입력하세요.' : '파라미터 이름을 입력하세요.';
}
