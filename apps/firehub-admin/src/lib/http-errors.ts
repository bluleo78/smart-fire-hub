/**
 * HTTP 에러 판정 헬퍼. 컴포넌트가 아니라 순수 함수이므로 `.ts` lib 모듈에 둔다
 * (`.tsx` 컴포넌트 파일에 두면 react-refresh/only-export-components 위반).
 */

/** axios 에러가 403 인지 판정한다. 화면마다 같은 조건을 다시 쓰지 않게 한곳에 둔다. */
export function isForbidden(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'response' in error &&
    (error as { response?: { status?: number } }).response?.status === 403
  );
}
