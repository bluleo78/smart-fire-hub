import { useCallback, useEffect, useRef } from 'react';

/**
 * 대화상자 「닫힘 세대」 공용 훅 (WD-819).
 *
 * 무엇을: 대화상자가 닫힐 때마다(open 이 true→false 로 바뀔 때 — X·ESC·취소뿐 아니라 부모가 open 을 꺼서 닫는 경우도 포함)
 *   올라가는 세대 카운터와, 지금 열려 있는지를 ref 로 들고 있다. capture() 는 제출 시작 때 세대를 잡아
 *   - isStale(): 그 뒤 닫힌 적이 있으면 true
 *   - isReopened(): isStale() 이면서 지금 열려 있으면 true (닫혔다가 다시 열림)
 *   를 돌려준다.
 * 왜: 대화상자 컴포넌트는 닫혀도 마운트된 채라, 늦게 도착한 응답이 상태(입력·열림·오류)를 건드리면 다시 연 대화상자가
 *   오염된다. 늦은 실패는 칸 대신 토스트로, 늦은 성공은 다시 열려 있을 때만 대화상자를 건드리지 않게 가르는 판정을 모았다.
 * 구현: ref 는 렌더 중에 쓰면 react-hooks/refs 규칙에 걸리므로 effect 에서 갱신한다(응답 시점에 읽으므로 충분하다).
 */
export function useCloseGeneration(open: boolean): {
  capture: () => { isStale: () => boolean; isReopened: () => boolean };
} {
  const genRef = useRef(0);
  const openRef = useRef(open);

  useEffect(() => {
    // 열림→닫힘 전환에서만 세대를 올린다(처음 마운트·닫힘→열림은 올리지 않는다)
    if (openRef.current && !open) genRef.current += 1;
    openRef.current = open;
  }, [open]);

  const capture = useCallback(() => {
    const gen = genRef.current;
    const isStale = () => genRef.current !== gen;
    return { isStale, isReopened: () => isStale() && openRef.current };
  }, []);

  return { capture };
}
