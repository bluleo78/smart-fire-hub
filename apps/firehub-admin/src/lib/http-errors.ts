/**
 * HTTP 에러 판정 헬퍼. 컴포넌트가 아니라 순수 함수이므로 `.ts` lib 모듈에 둔다
 * (`.tsx` 컴포넌트 파일에 두면 react-refresh/only-export-components 위반).
 */
import axios from 'axios';

import type { ErrorResponse } from '@/types/platform';

/** axios 에러가 403 인지 판정한다. 화면마다 같은 조건을 다시 쓰지 않게 한곳에 둔다. */
export function isForbidden(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'response' in error &&
    (error as { response?: { status?: number } }).response?.status === 403
  );
}

/**
 * axios 에러가 404 인지 판정한다. "존재하지 않음"과 "불러오지 못함(500/네트워크)"을 구별하는
 * 화면(예: 테넌트 상세)에서 쓴다 — 둘을 뭉뚱그리면 일시적 장애를 영구적인 "없음"으로 보여준다.
 */
export function isNotFound(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'response' in error &&
    (error as { response?: { status?: number } }).response?.status === 404
  );
}

/**
 * axios 400 에러에서 서버 메시지를 뽑는다. 서버가 프런트가 재현할 수 없는 교차검증을
 * 하는 경우(예: "존재하지 않는 사용자입니다", "OpenAI 임베딩 provider 에는 API 키가
 * 필요합니다") 그 문구를 그대로 실어야 하는데, 이 추출 로직이 `SettingsPage`/
 * `TenantCreatePage` 두 화면에 축어적으로 복제돼 있었다 — `isForbidden`/`isNotFound` 와
 * 같은 층으로 옮긴다. 400 이 아니거나 메시지가 없으면 `undefined`.
 */
export function serverMessage(error: unknown): string | undefined {
  if (!axios.isAxiosError(error) || error.response?.status !== 400) return undefined;
  return (error.response.data as ErrorResponse | undefined)?.message;
}

/** axios 에러가 4xx 인지 판정한다. 재시도 정책(main.tsx)이 상태코드별로 결정을 내리는 데 쓴다. */
export function isClientError(error: unknown): boolean {
  const status =
    typeof error === 'object' &&
    error !== null &&
    'response' in error &&
    (error as { response?: { status?: number } }).response?.status;
  return typeof status === 'number' && status >= 400 && status < 500;
}
