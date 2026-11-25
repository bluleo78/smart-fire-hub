import { startTransition, useEffect, useState } from 'react';

/*
 * 대시보드 위젯 그리기 차례 큐 (WD-1072, WD-1113).
 *
 * 위젯 여러 개가 한 커밋에서 recharts 를 한꺼번에 그리면 메인 스레드가 길게 막혀, 위젯마다 차례를 받아 하나씩 그린다.
 * 끌기 중에는 커밋이 밀려 반납이 늦어지고 뒤의 위젯이 모두 서므로, 차례를 쥔 지 500ms 가 지나면 강제로 넘긴다.
 * 단, 커밋 안 된 강제 차례는 최대 하나만 둔다 — 아니면 긴 끌기 뒤 한꺼번에 그려져 수 초짜리 긴 작업이 되살아난다.
 * 두 단계 상한(WD-1146): 차례를 받은 위젯이 렌더를 시작했다면(훅이 turn===true 로 렌더) 느린 렌더 도중에 강제로 넘기면
 * 렌더가 쪼개져 오히려 비싸지므로, 그때부터는 상한을 RENDERING_RELEASE_CAP_MS 로 늘려 다시 건다.
 * 500ms 상한은 렌더가 시작조차 못 한(커밋이 밀린) 차례에만 적용된다.
 */

/** 차례를 받았지만 아직 렌더를 시작하지 않은 쪽의 상한 — 이 시간 안에 렌더가 시작되지 않으면 강제로 반납시킨다(ms). */
export const RENDER_TURN_RELEASE_MS = 500;

/** 렌더를 시작한 차례의 상한(ms) — 느린 렌더를 중간에 쪼개지 않도록 넉넉하되, 멈춘 위젯이 줄을 영원히 세우지는 않게 한다. */
export const RENDERING_RELEASE_CAP_MS = 8000;

type Grant = () => void;

/** 줄 선 항목 — 차례를 주는 함수 자체가 항목이다(indexOf 로 같은 항목을 찾는다). */
const queue: Grant[] = [];
let current: Grant | null = null;
/** 반납 뒤 다음 차례를 주기 전 걸어 둔 rAF id — null 이 아니면 이중 rAF 대기 중이다(바깥 콜백은 안쪽 id 로 바로 바꿔 넣는다). */
let waitId: number | null = null;
/** 현재 차례를 쥔 쪽의 상한 타이머 — 반납되면 취소한다. */
let releaseTimer: ReturnType<typeof setTimeout> | null = null;
/** 상한으로 강제 반납됐지만 아직 제 반납(커밋·언마운트)이 오지 않은 차례 — 최대 하나만 둔다. */
let forcedOutstanding: Grant | null = null;
/** 지금 쥔 쪽의 상한이 지났지만 forcedOutstanding 때문에 넘기지 못한 상태 — forcedOutstanding 이 반납되는 순간 leave() 가 즉시 넘긴다. */
let capOverdue = false;
/** 지금 쥔 차례(current)의 렌더가 시작됐는가 — true 면 상한이 RENDERING_RELEASE_CAP_MS 로 걸려 있다. 새 차례를 줄 때 지운다. */
let rendering = false;

/** 상한 타이머를 취소한다. */
function cancelReleaseTimer() {
  if (releaseTimer !== null) clearTimeout(releaseTimer);
  releaseTimer = null;
}

/** 대기 중인 rAF 를 취소한다. */
function cancelWait() {
  if (waitId !== null) cancelAnimationFrame(waitId);
  waitId = null;
}

/** 대기열 맨 앞에게 차례를 준다. 이미 쥔 쪽이 있으면 하지 않는다. */
function grantNext() {
  cancelWait();
  if (current || queue.length === 0) return;
  const grant = queue.shift()!;
  current = grant;
  rendering = false;
  capOverdue = false;
  armReleaseTimer(grant, RENDER_TURN_RELEASE_MS);
  grant();
}

/** 쥔 쪽의 상한 타이머를 ms 로 건다. 늦게 온 타이머는 current 가 바뀌었으면 아무것도 하지 않는다. */
function armReleaseTimer(grant: Grant, ms: number) {
  cancelReleaseTimer();
  releaseTimer = setTimeout(() => {
    releaseTimer = null;
    if (current !== grant) return;
    if (forcedOutstanding !== null) {
      // 이미 커밋 안 된 강제 차례가 있다 — 또 넘기면 끌기 뒤 한꺼번에 그리게 되므로 표시만 한다.
      // 타이머는 다시 걸지 않는다: 밀린 차례가 반납되면 leave() 가 곧바로 넘기고, 쥔 쪽이 먼저 반납하면 release() 가 표시를 지운다.
      capOverdue = true;
      return;
    }
    forceRelease(grant);
  }, ms);
}

/**
 * 차례를 쥔 grant 가 렌더를 시작했음을 알린다 — 상한을 RENDERING_RELEASE_CAP_MS 로 다시 건다.
 * 멱등이다: 이미 표시됐거나 쥔 차례가 아니면(강제 반납된 뒤의 늦은 렌더 등) 아무것도 하지 않는다.
 * 이미 지나 있던 상한 표시(capOverdue)도 지운다 — 렌더가 시작됐으니 더는 밀린 차례가 아니다.
 */
function markRendering(grant: Grant): void {
  if (current !== grant || rendering) return;
  rendering = true;
  capOverdue = false;
  armReleaseTimer(grant, RENDERING_RELEASE_CAP_MS);
}

/** 커밋을 기다리지 않고 차례를 넘긴다. 이 grant 는 제 반납이 올 때까지 forcedOutstanding 으로 남는다. */
function forceRelease(grant: Grant) {
  forcedOutstanding = grant;
  release(grant);
}

/**
 * 줄에서 빠진다. 쥔 차례가 있으면 반납하고 다음 차례를 준다.
 *
 * - 화면이 보이면 requestAnimationFrame 두 번 뒤에 준다(이중 rAF). fillParent 의 recharts ResponsiveContainer 는
 *   첫 커밋에서 크기를 재고(ResizeObserver) 다음 렌더에서 SVG 를 그리는데, 그 무거운 작업이 한 프레임 안에 끝난 뒤에
 *   다음 위젯이 시작하게 하려는 것이다. setTimeout 0 한 번은 그 렌더보다 먼저 올 수 있어 타이밍 가정에 기대게 된다.
 * - 숨은 탭(document.visibilityState 가 visible 이 아님)에서는 rAF 가 멈추고 setTimeout 도 1초 이상으로 묶이며
 *   그릴 때 반응할 사용자도 없으니 기다리지 않고 바로 준다. 이중 rAF 를 기다리는 중 탭이 숨으면
 *   visibilitychange 리스너가 기다림을 취소하고 바로 준다.
 * 운영 빌드 측정(위젯 12개·500행, WD-1072): 1x 최대 장기 작업 2835→878ms, 4x 9207→4077ms.
 */
function leave(grant: Grant) {
  const i = queue.indexOf(grant);
  if (i >= 0) queue.splice(i, 1);
  if (forcedOutstanding === grant) {
    // 밀렸던 강제 차례가 이제 커밋(또는 언마운트)됐다 — 다음 강제 반납을 다시 허용한다
    forcedOutstanding = null;
    if (capOverdue && current !== null) {
      // 상한이 이미 지난 채 막혀 있던 쪽은 500ms 를 더 기다리지 않고 곧바로 넘긴다
      forceRelease(current);
      return;
    }
  }
  release(grant);
}

/** grant 가 쥔 차례면 반납하고 다음 차례를 예약한다(아니면 아무것도 하지 않는다). */
function release(grant: Grant) {
  if (current === grant) {
    current = null;
    capOverdue = false;
    cancelReleaseTimer();
    if (document.visibilityState !== 'visible') {
      grantNext();
      return;
    }
    waitId = requestAnimationFrame(() => {
      waitId = requestAnimationFrame(grantNext);
    });
  }
}

// 모듈 수준 리스너 하나 — 이중 rAF 를 기다리는 중에만 동작한다(숨은 탭에서 rAF 가 멈춰 큐가 서는 것을 막는다).
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (waitId !== null && document.visibilityState !== 'visible') grantNext();
  });
}

/**
 * ready 가 true 가 되면 줄을 서고, 차례를 받으면 true 를 돌려준다.
 * 한 번 true 가 되면 살아 있는 동안 유지한다(새로고침 중 ready 가 false 가 돼도 깜빡이지 않게).
 * 차례를 받은 렌더가 커밋된 뒤(effect cleanup) 반납한다.
 * 참고: StrictMode(dev·E2E)에서는 첫 마운트 effect 가 차례를 받고 strict cleanup 이 곧바로 반납해
 * 프로덕션과 차례 동작이 다를 수 있다(WD-1072).
 */
export function useWidgetRenderTurn(ready: boolean): boolean {
  const [turn, setTurn] = useState(false);
  // 안정된 grant 하나 — 렌더에서 읽어 markRendering 에 넘기고, effect 는 같은 함수를 줄 세운다(ref 를 렌더에서 읽지 않으려는 것).
  // 차례 부여는 낮은 우선순위 전환으로 — 입력이 끼어들 수 있게 한다.
  const [grant] = useState<Grant>(() => () => startTransition(() => setTurn(true)));

  // 렌더 단계 부작용이지만 멱등이고 current === grant 가드가 있어 StrictMode 이중 렌더·중단된 transition 에도 안전하다.
  // 렌더 단계에서 하는 이유: 커밋은 렌더가 끝난 뒤라, 느린 렌더 도중 500ms 강제 반납을 막으려면 렌더 시작 시점에 알아야 한다.
  if (turn) markRendering(grant);

  useEffect(() => {
    // 차례를 이미 받았으면 줄을 다시 서지 않는다. turn 이 true 로 바뀌며 이전 effect 의 cleanup 이 반납한다.
    if (turn || !ready) return;
    queue.push(grant);
    // 반납 직후 대기 중이면 기다린다(grantNext 가 대기를 취소하므로 여기서 거른다). 쥔 쪽이 있는지는 grantNext 가 본다.
    if (waitId === null) grantNext();
    return () => leave(grant);
  }, [ready, turn, grant]);

  return turn;
}

/** 시험용: 모듈 상태 초기화. */
function resetForTest(): void {
  queue.length = 0;
  current = null;
  rendering = false;
  forcedOutstanding = null;
  capOverdue = false;
  cancelWait();
  cancelReleaseTimer();
}

/** 시험용: 반납하지 않는 원시 grant 를 줄 세우고 반납 함수를 돌려준다(커밋이 밀리는 위젯 흉내). */
function enqueueRawGrantForTest(grant: () => void): () => void {
  queue.push(grant);
  if (waitId === null) grantNext();
  return () => leave(grant);
}

/**
 * 시험 전용 API 묶음. Vite 가 import.meta.env.MODE 를 빌드 시 상수로 바꿔 운영 번들에서는 undefined 로 굳고
 * 위 두 함수도 죽은 코드로 제거된다.
 */
export const __widgetRenderQueueTestApi =
  import.meta.env.MODE === 'test' ? { reset: resetForTest, enqueueRawGrant: enqueueRawGrantForTest } : undefined;
