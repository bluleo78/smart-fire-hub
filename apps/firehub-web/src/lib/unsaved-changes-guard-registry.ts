/**
 * unsaved-changes-guard-registry
 *
 * 이슈 #562: `useUnsavedChangesGuard`의 popstate(브라우저 뒤로/앞으로) 가드는
 * 페이지 컴포넌트의 `useEffect` 안에서 `window.addEventListener('popstate', ...)`로
 * 구현돼 있었다. 하지만 `BrowserRouter`(react-router-dom)도 앱 부팅 시점(레이아웃
 * 이펙트)에 자기 자신의 popstate 리스너를 이미 등록해두고, 이 리스너는 라우트가
 * 바뀌어도 절대 해제되지 않는다. 반면 페이지 훅의 리스너는 항상 그보다 한참 뒤(해당
 * 페이지가 마운트되는 시점)에 등록되므로, DOM 이벤트 디스패치 순서상 **항상** 라우터가
 * 먼저 반응해 페이지를 언마운트해버리고, 그 정리(cleanup)가 같은 디스패치 도중
 * 동기적으로 실행되어 훅의 리스너가 아예 호출되지 못한 채 스킵된다(DOM 스펙상 디스패치
 * 도중 제거된 리스너는 호출되지 않는다). 즉, "페이지 훅 안에서 popstate를 가로챈다"는
 * 접근 자체가 등록 순서상 구조적으로 라우터를 이길 수 없다.
 *
 * 해결: 라우터보다 먼저 등록되고 앱 생명주기 내내 유지되는 **단일 전역 리스너**를
 * `main.tsx`에서 `installPopstateGuardInterceptor()`로 1회 설치한다(React 트리
 * 구성보다 먼저 실행되므로 항상 라우터의 리스너보다 먼저 등록된다). 현재 마운트된
 * 페이지의 dirty 가드는 이 레지스트리에 자신을 등록해두고, 전역 리스너가 dirty를
 * 감지하면 `event.stopImmediatePropagation()`으로 라우터(및 이후 모든 리스너)가
 * 이 popstate를 아예 보지 못하게 막은 뒤, 브라우저가 이미 옮겨버린 주소를
 * `pushState`로 원복하고 다이얼로그를 띄운다. 라우터가 이벤트를 못 봤으므로 페이지는
 * 언마운트되지 않고, URL과 렌더 화면이 어긋나는 일도 없다.
 */

/** 현재 마운트된 페이지가 등록하는 가드 진입점 */
interface ActiveGuardEntry {
  /** 현재 dirty 상태 여부(최신 값을 매번 조회) */
  isDirty: () => boolean;
  /** popstate로 이동하기 직전의 경로(pushState로 되돌릴 대상) */
  restorePath: () => string;
  /** 가로채기 성공 시 호출 — 다이얼로그 오픈 등 후속 처리 */
  onIntercepted: () => void;
}

let activeGuard: ActiveGuardEntry | null = null;
// 사용자가 다이얼로그에서 "이탈"을 확정해 실제로 뒤로가기를 재실행할 때,
// 다음 popstate 1회는 dirty 여부와 무관하게 그냥 통과시키기 위한 1회성 플래그.
let bypassNextPop = false;
let installed = false;

/**
 * 현재 페이지의 dirty 가드를 등록한다. 동시에 하나만 활성화될 수 있다(페이지 단위 훅이므로
 * 실제로 한 번에 하나만 마운트된다). 언마운트 시 반환된 함수로 해제해야 한다.
 */
export function registerActiveGuard(entry: ActiveGuardEntry): () => void {
  activeGuard = entry;
  return () => {
    if (activeGuard === entry) activeGuard = null;
  };
}

/** 다이얼로그의 "이탈" 확정 후 재시도하는 뒤로가기 1회는 가드를 우회하도록 표시한다. */
export function bypassNextPopstateGuard(): void {
  bypassNextPop = true;
}

/**
 * 전역 popstate 인터셉터를 설치한다. 앱 부팅 시(React 렌더 이전) 1회만 호출해야
 * `BrowserRouter`가 자신의 리스너를 등록하기 전에 우리 리스너가 먼저 등록되도록
 * 보장할 수 있다. 여러 번 호출해도 안전하다(idempotent).
 */
export function installPopstateGuardInterceptor(): void {
  if (installed) return;
  installed = true;

  window.addEventListener('popstate', (event) => {
    if (bypassNextPop) {
      bypassNextPop = false;
      return;
    }
    if (!activeGuard || !activeGuard.isDirty()) return;

    // 라우터를 포함한 이후 모든 popstate 리스너가 이 이벤트를 아예 보지 못하게 막는다.
    // → 라우터의 location이 갱신되지 않으므로 현재 페이지가 언마운트되지 않는다.
    event.stopImmediatePropagation();

    // 브라우저는 이미 실제 히스토리 이동을 완료한 상태(popstate는 취소 불가능)이므로,
    // 주소창만 원래 경로로 되돌린다. 라우터는 이 pushState를 모르지만 애초에 이 popstate를
    // 못 봤으므로 라우터 내부 location 상태와도 어긋나지 않는다.
    window.history.pushState(null, '', activeGuard.restorePath());
    activeGuard.onIntercepted();
  });
}
