/**
 * 탭 사이 토큰 갱신 직렬화(WD-438).
 *
 * <p>서버는 리프레시 토큰을 갱신마다 회전시키고, 같은 토큰으로 동시에 들어온 갱신 중 늦은 쪽은 401 로 거부한다
 * (RefreshTokenRotation). 탭 여러 개가 한꺼번에 열려(탭 복원 등) 같은 쿠키로 동시에 갱신하면 진 탭의 401 이
 * 로그아웃(hasSession 삭제)으로 처리되고, storage 이벤트로 이긴 탭·원래 쓰던 탭까지 모두 로그인 화면으로 간다.
 *
 * <p>Web Locks 로 같은 출처의 모든 탭이 갱신을 한 번에 하나씩 하게 한다. 앞 탭의 응답이 회전된 쿠키를 저장한 뒤에
 * 다음 탭의 요청이 나가므로, 뒤 탭은 새 쿠키로 갱신에 성공한다. 진 탭을 401 뒤에 다시 시도하게 하지 않는 이유:
 * 회전 전 쿠키로 다시 보내면 서버가 「폐기된 토큰의 재사용」으로 보고 로그인 세션 전체를 폐기한다.
 *
 * <p>Web Locks 가 없는 환경(보안 컨텍스트가 아닌 http 등)에서는 예전처럼 잠금 없이 갱신한다.
 */
const REFRESH_LOCK_NAME = 'firehub-auth-refresh';

/** fn(토큰 갱신 요청)을 탭 사이 잠금 안에서 실행한다. 잠금을 쓸 수 없으면 그대로 실행한다. */
export function withCrossTabRefreshLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  if (!locks?.request) return fn();
  // LockManager.request 는 콜백 결과를 그대로 돌려준다(콜백이 Promise 면 그 값으로 풀린다).
  return locks.request(REFRESH_LOCK_NAME, fn) as Promise<T>;
}
