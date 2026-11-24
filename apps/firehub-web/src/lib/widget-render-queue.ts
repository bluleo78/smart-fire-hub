import { startTransition, useEffect, useState } from 'react';

/**
 * 대시보드 위젯 그리기 차례 큐 (WD-1072).
 *
 * 위젯 12개가 한 커밋에서 recharts 를 모두 그리면 메인 스레드가 2.6초 막힌다.
 * 위젯마다 차례를 받아 하나씩 그리게 해 긴 작업을 쪼갠다.
 * 모듈 단위 큐라 같은 화면의 모든 위젯이 한 줄을 공유한다.
 */
type Grant = () => void;

/** 줄 선 항목 — 차례를 주는 함수 자체가 항목이다(indexOf 로 같은 항목을 찾는다). */
const queue: Grant[] = [];
let current: Grant | null = null;
/** 반납 뒤 다음 차례를 주기 전 걸어 둔 rAF id — null 이 아니면 이중 rAF 대기 중이다(바깥 콜백은 안쪽 id 로 바로 바꿔 넣는다). */
let waitId: number | null = null;

/** 대기 중인 rAF 를 취소한다. */
function cancelWait() {
  if (waitId !== null) cancelAnimationFrame(waitId);
  waitId = null;
}

/** 대기열 맨 앞에게 차례를 준다. 이미 쥔 쪽이 있으면 하지 않는다. */
function grantNext() {
  cancelWait();
  if (current || queue.length === 0) return;
  current = queue.shift()!;
  current();
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
  if (current === grant) {
    current = null;
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

  useEffect(() => {
    // 차례를 이미 받았으면 줄을 다시 서지 않는다. turn 이 true 로 바뀌며 이전 effect 의 cleanup 이 반납한다.
    if (turn || !ready) return;
    // 차례 부여는 낮은 우선순위 전환으로 — 입력이 끼어들 수 있게 한다.
    const grant: Grant = () => startTransition(() => setTurn(true));
    queue.push(grant);
    // 반납 직후 대기 중이면 기다린다(grantNext 가 대기를 취소하므로 여기서 거른다). 쥔 쪽이 있는지는 grantNext 가 본다.
    if (waitId === null) grantNext();
    return () => leave(grant);
  }, [ready, turn]);

  return turn;
}

/** 시험용: 모듈 상태 초기화. */
export function __resetWidgetRenderQueueForTest(): void {
  queue.length = 0;
  current = null;
  cancelWait();
}
