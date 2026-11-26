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
export interface ActiveGuardEntry {
  /** 현재 dirty 상태 여부(최신 값을 매번 조회) */
  isDirty: () => boolean;
  /** popstate로 이동하기 직전의 경로(pushState로 되돌릴 대상) */
  restorePath: () => string;
  /** 가로채기 성공 시 호출 — 다이얼로그 오픈 등 후속 처리 */
  onIntercepted: () => void;
  /**
   * 「떠나는 중」 표시를 쓰고 끈다 (WD-1160). true: 어느 가드에서 「이탈」이 확정돼 이 가드도 끈다 —
   * 다음 화면이 로딩되는 동안(WD-651 창) 남은 dirty 가드가 두 번째 확인·beforeunload 를 띄우지 않게 하기 위함이다.
   * false: 확정한 이동이 일어나지 않았을 때(WD-673) 가드를 다시 켠다.
   */
  setLeaving?: (leaving: boolean) => void;
}

// 여러 가드가 동시에 마운트될 수 있다(WD-1142) — 단일 칸이면 나중 등록이 앞 가드를 덮어쓰고, 그 해제가 앞 가드까지 지워
// dirty 폼이 있어도 떠나기 확인이 사라진다. Set 이라 등록 순서가 유지된다.
const activeGuards = new Set<ActiveGuardEntry>();
// 사용자가 다이얼로그에서 "이탈"을 확정해 실제로 뒤로가기를 재실행할 때,
// 다음 popstate 1회는 dirty 여부와 무관하게 그냥 통과시키기 위한 1회성 플래그.
let bypassNextPop = false;
let installed = false;

/**
 * 현재 페이지의 dirty 가드를 등록한다. 여럿이 동시에 있을 수 있다(WD-1142). 언마운트 시 반환된 함수로 해제해야 한다.
 */
export function registerActiveGuard(entry: ActiveGuardEntry): () => void {
  activeGuards.add(entry);
  return () => {
    activeGuards.delete(entry);
  };
}

/**
 * 이동 한 번에 확인을 물을 가드 하나를 고른다 (WD-1160).
 * 정책: dirty 가드가 여럿이어도 확인은 한 번, 가장 나중에 등록된(Set 뒤쪽) dirty 가드가 묻는다.
 * 「이탈」을 확정하면 이동하므로 다른 dirty 폼도 함께 버려진다(markAllGuardsLeaving 으로 모두 떠남 처리한다).
 * popstate 인터셉터와 훅의 click 캡처가 같은 규칙을 쓰도록 여기 한 곳에 둔다.
 *
 * 훅은 마운트당 한 번만 등록하므로 등록 순서 = 이펙트 실행 순서다.
 * - 마운트 시점이 다른 가드끼리는 마운트 순서다.
 * - React 는 이펙트를 자식부터 돌려, 같은 커밋에 부모·자식이 함께 마운트되면 부모가 나중에 등록돼 부모가 묻는다.
 *   (확정하면 모두 버려지므로 무방하고, 실제 호출처는 페이지당 하나라 중첩은 없다.)
 *
 * 고를 가드가 없으면 undefined(가로채지 않음).
 */
export function pickInterceptingGuard(): ActiveGuardEntry | undefined {
  return [...activeGuards].reverse().find((g) => g.isDirty());
}

// (WD-1160) 떠남 표시를 건 차례 번호 — 되돌림 함수가 자기보다 새 확정이 없을 때만 동작하도록 구분한다.
let leavingSeq = 0;

/**
 * 「이탈」 확정 시 등록된 모든 가드를 떠나는 중으로 돌린다 (WD-1160) — 확인 한 번이 다른 dirty 폼도 버리는 정책과
 * 코드를 맞춘다. 안 그러면 다음 화면 로딩 중(WD-651 창) 다른 dirty 가드가 살아 있어 Back 이 두 번째 확인을 띄우고,
 * 강제 새로고침에 beforeunload 가 뜨며 hasActiveLeaveGuard() 도 true 로 남는다.
 * 확정한 이동이 일어나지 않았을 때(WD-673) 부르라고 모든 가드를 다시 켜는 함수를 돌려준다.
 * 이후 재무장은 각 가드의 기존 규칙(location 정착·폼이 깨끗해짐)이 맡는다.
 */
export function markAllGuardsLeaving(): () => void {
  const marked = [...activeGuards];
  for (const g of marked) g.setLeaving?.(true);
  const seq = ++leavingSeq;
  return () => {
    // 그 뒤 새 「이탈」 확정(더 큰 번호)이 있었으면 이 되돌림은 오래된 것이다 — 언마운트된 가드의 대기 타이머가 늦게 돌아
    // 다른 가드가 방금 건 떠남 표시를 다시 끄면(두 번째 확인·beforeunload) 안 되므로 아무것도 하지 않는다. (bypassSeq 와 같은 방식)
    if (leavingSeq !== seq) return;
    for (const g of marked) g.setLeaving?.(false);
  };
}

/**
 * 떠나기를 막는 다중 진입점 집합 (WD-1122).
 * activeGuards 는 페이지 가드 집합이고 이것은 그와 따로 사는 차단 조건이다. 스트리밍·업로드 중 beforeunload 만 거는
 * 곳(useAIChat)처럼 페이지 가드와 따로 사는 차단 조건은 여기에 함수로 등록한다 — 여러 개가 동시에 있어도 된다.
 */
const leaveBlockers = new Set<() => boolean>();

/**
 * 떠나기를 막는 조건을 등록한다 (WD-1122). isBlocking 은 호출 시점의 최신 값을 돌려줘야 한다.
 * 반환 함수로 해제한다(같은 함수를 두 번 등록해도 Set 이라 하나로 취급되니, 호출마다 새 함수를 넘긴다).
 *
 * <p>규칙: `beforeunload` 리스너를 직접 거는 모든 곳은 같은 조건을 이 함수(또는 useUnsavedChangesGuard 의 registerActiveGuard)로도
 * 등록해야 한다. 안 그러면 client.ts 의 만료·사용자 바뀜 이동이 그 확인 창을 모른 채 같은 화면의 배경 요청마다 이동을 되풀이해
 * 확인 창을 계속 띄운다. 이 규칙은 `leave-blocker-rule.test.ts` 가 소스를 훑어 지킨다(WD-1122).
 */
export function registerLeaveBlocker(isBlocking: () => boolean): () => void {
  leaveBlockers.add(isBlocking);
  return () => {
    leaveBlockers.delete(isBlocking);
  };
}

/**
 * 떠나기 확인이 걸릴 수 있는 상태인지 (WD-1122) — 페이지 dirty 가드 또는 등록된 차단 조건 중 하나라도 참이면 true.
 * client.ts 의 만료 표식이 같은 화면의 배경 요청에 로그인 이동(=beforeunload 확인 창)을 되풀이할지 가르는 데 쓴다.
 */
export function hasActiveLeaveGuard(): boolean {
  // 페이지 가드가 등록됐고 dirty 인지(WD-1122)
  if ([...activeGuards].some((g) => g.isDirty())) return true;
  for (const isBlocking of leaveBlockers) {
    if (isBlocking()) return true;
  }
  return false;
}

// (WD-673) 우회 표시를 건 차례 번호 — 취소 함수가 자기가 건 표시일 때만 지우도록 구분한다.
let bypassSeq = 0;

/**
 * 다이얼로그의 "이탈" 확정 후 재시도하는 뒤로가기 1회는 가드를 우회하도록 표시한다.
 * (WD-673) 그 뒤로가기가 아무 데도 가지 않으면(되돌아갈 기록이 없는 드문 경우) popstate 가 오지 않아 표시가 남고,
 * 나중에 사용자가 dirty 상태에서 누른 진짜 뒤로가기가 확인 없이 통과된다. 그래서 표시를 거두는 취소 함수를 돌려준다 —
 * 아직 이 호출의 표시가 소비되지 않았을 때만 지우고, 이미 소비됐거나 그 뒤 새 표시가 걸렸으면 아무것도 하지 않는다.
 */
export function bypassNextPopstateGuard(): () => void {
  bypassNextPop = true;
  const seq = ++bypassSeq;
  return () => {
    if (bypassSeq === seq) bypassNextPop = false;
  };
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
    // dirty 인 가드 중 나중에 등록된 것 하나만 쓴다(WD-1142·WD-1160, pickInterceptingGuard 참고). 없으면 가로채지 않는다.
    const guard = pickInterceptingGuard();
    if (!guard) return;

    // 라우터를 포함한 이후 모든 popstate 리스너가 이 이벤트를 아예 보지 못하게 막는다.
    // → 라우터의 location이 갱신되지 않으므로 현재 페이지가 언마운트되지 않는다.
    event.stopImmediatePropagation();

    // 브라우저는 이미 실제 히스토리 이동을 완료한 상태(popstate는 취소 불가능)이므로,
    // 주소창만 원래 경로로 되돌린다. 라우터는 이 pushState를 모르지만 애초에 이 popstate를
    // 못 봤으므로 라우터 내부 location 상태와도 어긋나지 않는다.
    window.history.pushState(null, '', guard.restorePath());
    guard.onIntercepted();
  });
}
