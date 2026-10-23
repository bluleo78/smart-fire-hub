import { useCallback } from 'react';

import { useAuth } from './useAuth';

/**
 * 분석 자원(대시보드·차트·저장 쿼리)의 작성자가 지금 로그인한 사용자인지 판정하는 함수를 돌려준다(WD-196).
 *
 * 왜: 서버는 이 자원의 수정·삭제를 작성자에게만 허용한다(소유자 확인 실패는 존재를 숨기는 404). 그런데 화면은 소유 여부를 보지 않고
 * 편집·저장·삭제 버튼을 그려, 남이 공유한 것을 열면 버튼을 누를 때마다 「찾을 수 없습니다」 토스트가 떴다. 응답에 이미 실린 작성자 id
 * (`createdBy`)를 현재 사용자 id 와 비교해 버튼을 미리 숨긴다 — 서버 규칙과 같은 기준(관리자 예외 없음)이다.
 *
 * 작성자 id 가 없거나(구버전 응답) 사용자 정보를 아직 못 읽었으면 false — 쓸 수 없는 버튼을 보이는 것보다 숨기는 쪽이 안전하다.
 */
export function useIsOwner() {
  const { user } = useAuth();
  const userId = user?.id;
  return useCallback(
    (createdBy: number | null | undefined) =>
      userId != null && createdBy != null && createdBy === userId,
    [userId],
  );
}
