/**
 * 이동 가드 판정에 쓰는 위치 값 — **경로(pathname) 만** 담는다.
 *
 * 쿼리·해시는 여기에 넣지 않는다: `?tab=executions` 같은 탭 전환은 폼을 들고 있는 스마트 작업 화면 안에서 일어나
 * 떠남이 아니기 때문이다(WD-1109 반려 1). 값을 넣어 두면 저장 중 탭을 바꾼 순간 성공한 저장이 「이탈」으로
 * 읽혀 편집 모드·미저장 표시가 남고, 다음 저장이 자기 저장과 409 로 부딪힌다.
 * 단위 테스트(nav-location.test.ts)가 이 함수를 직접 검증한다. 컴포넌트 파일 밖에 둔 것은 react-refresh 규칙 때문이다 (WD-1153).
 */
export function navLocationKey(): string {
  return window.location.pathname;
}
