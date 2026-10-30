import { useCallback, useSyncExternalStore } from 'react';

/** matchMedia 가 있는 환경인지 — 런타임에 바뀌지 않으므로 모듈에서 한 번만 본다(WD-372). */
const HAS_MATCH_MEDIA = typeof window !== 'undefined' && typeof window.matchMedia === 'function';

/**
 * CSS 미디어 쿼리 일치 여부를 구독하는 훅(WD-372).
 *
 * matchMedia + useSyncExternalStore 조합으로 창 크기 변경 시 즉시 다시 렌더한다 — useState+useEffect
 * 방식과 달리 첫 렌더부터 실제 값을 쓰므로 "데스크톱으로 그렸다가 모바일로 다시 그리는" 깜빡임이 없다.
 *
 * matchMedia가 없는 환경(jsdom 단위 테스트·SSR)에서는 `fallback`을 돌려준다 — 기존 화면(데스크톱
 * 배치)을 기준으로 작성된 테스트가 그대로 통과하도록 호출부가 기본값을 고른다.
 */
export function useMediaQuery(query: string, fallback = false): boolean {
  // 구독 함수는 query가 바뀔 때만 새로 만든다 — 매 렌더 재구독을 막기 위함.
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (!HAS_MATCH_MEDIA) return () => {};
      const mql = window.matchMedia(query);
      mql.addEventListener('change', onChange);
      return () => mql.removeEventListener('change', onChange);
    },
    [query],
  );

  const getSnapshot = () => (HAS_MATCH_MEDIA ? window.matchMedia(query).matches : fallback);

  return useSyncExternalStore(subscribe, getSnapshot, () => fallback);
}
