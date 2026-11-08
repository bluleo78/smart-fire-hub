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
 * axios 에러에서 서버 메시지를 뽑는다. 서버가 프런트가 재현할 수 없는 판정을 하는 경우(예: "존재하지 않는
 * 사용자입니다", "운영자 계정은 비활성화할 수 없습니다") 그 문구를 그대로 실어야 한다.
 * 기본은 400 만 — 기존 호출(TenantCreatePage 등)의 의미를 바꾸지 않는다. 409 같은 상태 충돌도 서버 문구를
 * 보여 줘야 하는 화면(계정 비활성화, #784)은 statuses 로 넓힌다. 대상 상태가 아니거나 메시지가 없으면 `undefined`.
 */
export function serverMessage(error: unknown, statuses: number[] = [400]): string | undefined {
  if (!axios.isAxiosError(error)) return undefined;
  const status = error.response?.status;
  if (status === undefined || !statuses.includes(status)) return undefined;
  const message = (error.response?.data as ErrorResponse | undefined)?.message;
  // 빈 문자열도 "메시지 없음"으로 본다(호출부가 fallback 으로 넘어가도록).
  return message ? message : undefined;
}

/** Bean Validation 400 의 기계용 코드(WD-640) — api GlobalExceptionHandler.VALIDATION_FAILED_CODE 와 같은 값. */
export const VALIDATION_FAILED_CODE = 'VALIDATION_FAILED';

/**
 * axios 400 에러에서 Bean Validation 필드 오류(`errors`: 필드 → 한국어 문구)를 뽑는다(WD-311).
 * `message` 는 필드명이 없는 일반 문구(「입력값이 올바르지 않습니다.」, 구버전 서버는 영어 「Validation failed」)라 무엇을 고칠지 알 수 없다 —
 * 화면은 이 맵으로 해당 칸에 문구를 단다. 필드 오류가 없으면 `undefined`.
 * code 가 없거나 검증 실패 코드일 때만 사람용 문구다 — 다른 코드의 errors 는 화면 분기용 기계 값(details)이라 칸에 달지 않는다(WD-640).
 */
export function serverFieldErrors(error: unknown): Record<string, string> | undefined {
  if (!axios.isAxiosError(error) || error.response?.status !== 400) return undefined;
  const data = error.response?.data as ErrorResponse | undefined;
  if (data?.code != null && data.code !== VALIDATION_FAILED_CODE) return undefined;
  const errors = data?.errors;
  return errors && Object.keys(errors).length > 0 ? errors : undefined;
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
