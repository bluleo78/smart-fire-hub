/**
 * 이 문서(탭)에서 지금 위치 뒤로 앱 안 기록이 몇 칸인지 센다 (WD-667, useGoBack 의 「돌아갈 기록」 판정).
 *
 * 주 판정은 공개 API — location.key 와 이동 종류(useNavigationType)다.
 * - PUSH: 이전 위치 깊이 + 1
 * - REPLACE: 이전 위치 깊이 그대로 — 로그인 리다이렉트(/login 으로 replace, 로그인 뒤 원래 주소로 replace)는 기록이 아니다
 * - POP: 이 문서에서 본 위치면 기록해 둔 깊이(뒤로 가서 첫 화면에 돌아오면 다시 0)
 * - 처음 보는 위치(첫 진입·새로고침·POP)는 React Router 가 남긴 history.state.idx 를 참고값으로만 쓴다 —
 *   앱 안 이동 뒤 새로고침해도 뒤로 갈 기록이 있음을 놓치지 않게. idx 가 없으면 0 이다.
 *
 * 한계: 미저장 가드의 pushState(null, …) 처럼 상태 없는 기록 칸으로 POP 하면 idx 가 없어 「없음」(대체 경로)이 된다.
 * 그 칸 위에 머무는 동안은 라우터 위치(key)가 그대로라 판정이 유지된다.
 *
 * 렌더 중에 기록되고(NavigationHistoryTracker) 같은 key 는 다시 기록하지 않아 StrictMode 이중 렌더에도 깊이가 두 번 늘지 않는다.
 */

export type NavigationKind = 'PUSH' | 'REPLACE' | 'POP';

/** location.key → 앱 안 기록 깊이 */
const depthByKey = new Map<string, number>();
/** 마지막으로 기록한 위치의 key — 다음 이동의 「이전 위치」 */
let currentKey: string | null = null;

/** 처음 보는 위치의 깊이 — 라우터가 남긴 idx 가 있으면 참고하고, 없으면 0 */
function seededDepth(): number {
  const idx = (window.history.state as { idx?: unknown } | null)?.idx;
  return typeof idx === 'number' && idx > 0 ? idx : 0;
}

/** 라우터 위치가 바뀌었음을 기록한다. 같은 위치를 다시 넘기면 무시한다. */
export function recordNavigation(key: string, kind: NavigationKind): void {
  if (key === currentKey) return;
  const prevDepth = currentKey == null ? 0 : (depthByKey.get(currentKey) ?? 0);
  if (kind === 'PUSH') depthByKey.set(key, prevDepth + 1);
  else if (kind === 'REPLACE') depthByKey.set(key, prevDepth);
  else if (!depthByKey.has(key)) depthByKey.set(key, seededDepth());
  currentKey = key;
}

/** 이 위치에서 뒤로 가면 앱 안의 이전 화면이 나오는가 */
export function hasInAppHistory(key: string): boolean {
  return (depthByKey.get(key) ?? 0) > 0;
}
