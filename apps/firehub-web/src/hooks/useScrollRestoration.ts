import { useEffect, useLayoutEffect } from 'react';
import { useLocation, useNavigationType } from 'react-router-dom';

/**
 * 앱의 스크롤 컨테이너(`<main>`)에 대한 스크롤 위치 관리.
 *
 * 무엇: 새 화면으로 이동(PUSH/REPLACE)하면 맨 위로 리셋하고, 뒤로/앞으로(POP)면 이전 위치를 복원한다.
 * 왜:   실제 스크롤 컨테이너가 `window`가 아니라 `<main>`(`AppLayout`)이라
 *       브라우저의 기본 스크롤 복원이 전혀 동작하지 않는다. 그 결과 목록을 아래까지 스크롤한 뒤
 *       상세로 이동하면 `main.scrollTop`이 그대로 이월돼 **새 페이지가 중간/맨 아래부터 보였다.**
 *       (실측: 홈에서 400 → /data/datasets 도착 시 240 = maxScroll로 클램프된 최하단)
 *
 * `react-router`의 `ScrollRestoration`을 쓰지 않는 이유: 그것은 data router(`createBrowserRouter`)
 * 전용이고 window 스크롤만 다룬다. 이 앱은 `BrowserRouter` + 커스텀 스크롤 컨테이너다.
 */

/** 위치별 스크롤 오프셋 저장소. 탭 세션 동안만 유효하면 되므로 메모리에 둔다. */
const positions = new Map<string, number>();

/**
 * 복원 재시도 시간 예산.
 *
 * 뒤로가기 대상 페이지는 lazy 청크 로딩 + TanStack Query 페치가 끝나야 원래 높이가 된다.
 * 몇 프레임(≈80ms)만 시도하면 스켈레톤 높이에 클램프된 채 끝난다(실측: 120 목표에 82에서 멈춤).
 * 반대로 무한 재시도는 사용자가 직접 스크롤하는 것과 싸우므로 시간 상한을 둔다.
 */
const RESTORE_BUDGET_MS = 1200;

export function useScrollRestoration(ref: React.RefObject<HTMLElement | null>) {
  const location = useLocation();
  const navigationType = useNavigationType();

  // 스크롤할 때마다 현재 위치의 오프셋을 기록해 둔다 — 뒤로가기 시 복원 재료.
  // 위치가 바뀔 때마다 리스너를 다시 건다(핸들러가 자기 location.key를 클로저로 갖게 하기 위함).
  // 리스너 하나뿐이라 재등록 비용은 무시할 수준이고, ref를 렌더 중에 쓰는 것보다 안전하다.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const key = location.key;
    const onScroll = () => positions.set(key, el.scrollTop);
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [ref, location.key]);

  // 페인트 전에 적용해야 이전 위치가 한 프레임 노출되는 깜빡임이 없다.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;

    if (navigationType !== 'POP') {
      // 새 화면 — 항상 맨 위. 저장된 값이 있어도 무시한다(같은 URL 재방문도 새 시작으로 본다).
      el.scrollTop = 0;
      return;
    }

    const saved = positions.get(location.key) ?? 0;
    if (saved === 0) {
      el.scrollTop = 0;
      return;
    }

    // 뒤로가기 대상 페이지는 lazy 로딩·데이터 페치 때문에 아직 짧다. 콘텐츠가 자라 목표 위치에
    // 도달할 수 있을 때까지 프레임마다 다시 시도하되, 시간 예산을 넘기면 포기한다.
    // 사용자가 그 사이 직접 스크롤하면 즉시 중단한다 — 입력과 싸우지 않기 위함.
    let raf = 0;
    let userScrolled = false;
    const deadline = performance.now() + RESTORE_BUDGET_MS;
    const onUserScroll = () => {
      userScrolled = true;
    };

    const attempt = () => {
      el.scrollTop = saved;
      if (el.scrollTop >= saved || userScrolled || performance.now() > deadline) {
        el.removeEventListener('wheel', onUserScroll);
        el.removeEventListener('touchstart', onUserScroll);
        return;
      }
      raf = requestAnimationFrame(attempt);
    };

    el.addEventListener('wheel', onUserScroll, { passive: true });
    el.addEventListener('touchstart', onUserScroll, { passive: true });
    attempt();

    return () => {
      cancelAnimationFrame(raf);
      el.removeEventListener('wheel', onUserScroll);
      el.removeEventListener('touchstart', onUserScroll);
    };
  }, [ref, location.key, navigationType]);
}

/** 테스트 전용 — 위치 저장소를 비운다. */
export function __resetScrollPositions() {
  positions.clear();
}
