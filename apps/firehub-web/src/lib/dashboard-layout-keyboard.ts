import type { Layout, LayoutItem } from 'react-grid-layout';
import { cloneLayout, moveElement, verticalCompactor } from 'react-grid-layout/core';

/** 대시보드 그리드 열 수 — DashboardEditorPage 의 cols.lg 와 같아야 한다 */
export const DASHBOARD_GRID_COLS = 12;

/** 키보드 배치 조작 — 이동(dx·dy) 또는 크기 조절(dw·dh), 한 번에 한 칸 */
export type KeyboardLayoutAction =
  | { kind: 'move'; dx: number; dy: number }
  | { kind: 'resize'; dw: number; dh: number };

/**
 * 화살표 키를 배치 조작으로 바꾼다(WD-324). 화살표 = 이동, Shift+화살표 = 크기 조절(→·↓ 키우기, ←·↑ 줄이기).
 * 화살표가 아니면 null — 호출부는 그 키를 그대로 흘려보낸다(Tab 등 포커스 이동을 막지 않게).
 */
export function keyToLayoutAction(key: string, shiftKey: boolean): KeyboardLayoutAction | null {
  const delta: Record<string, [number, number]> = {
    ArrowLeft: [-1, 0],
    ArrowRight: [1, 0],
    ArrowUp: [0, -1],
    ArrowDown: [0, 1],
  };
  const d = delta[key];
  if (!d) return null;
  return shiftKey ? { kind: 'resize', dw: d[0], dh: d[1] } : { kind: 'move', dx: d[0], dy: d[1] };
}

/**
 * 위젯 하나를 키보드로 한 칸 옮기거나 크기를 바꾼 새 레이아웃을 만든다(WD-324).
 *
 * 마우스 끌기와 같은 결과가 나오도록 react-grid-layout 의 moveElement(부딪힌 위젯을 밀어냄)와
 * 세로 압축(verticalCompactor — 그리드 기본값)을 그대로 쓴다. 그래서 위로 옮기면 위 위젯과 자리를 바꾸고,
 * 크기를 키워 겹치면 겹친 위젯이 아래로 밀린다. 가로는 0~(열 수-너비), 크기는 minW·minH~그리드 폭으로 묶는다.
 * 대상이 없으면 원래 레이아웃을 그대로 돌려준다.
 */
export function applyKeyboardLayoutAction(
  layout: readonly LayoutItem[],
  id: string,
  action: KeyboardLayoutAction,
  cols: number = DASHBOARD_GRID_COLS,
): LayoutItem[] {
  const next = cloneLayout(layout as Layout) as LayoutItem[];
  const item = next.find((l) => l.i === id);
  if (!item) return [...layout];

  if (action.kind === 'move') {
    const x = Math.min(Math.max(item.x + action.dx, 0), cols - item.w);
    if (action.dy >= 0) {
      return moveAndCompact(next, id, x, item.y + action.dy, cols);
    }
    // 위로: 세로 압축 때문에 바로 위 칸이 막혀 있으면 한 칸 올려도 제자리로 돌아온다. 키를 눌렀는데 아무 일이
    // 없는 것처럼 보이지 않게, 실제로 올라가는 가장 가까운 행까지 찾아 올린다(막은 위젯과 자리를 바꾸는 것과 같다).
    for (let y = item.y - 1; y >= 0; y--) {
      const moved = moveAndCompact(next, id, x, y, cols);
      if (moved.find((l) => l.i === id)!.y < item.y) return moved;
    }
    return moveAndCompact(next, id, x, item.y, cols);
  }

  const minW = item.minW ?? 1;
  const minH = item.minH ?? 1;
  item.w = Math.min(Math.max(item.w + action.dw, minW), cols - item.x);
  item.h = Math.max(item.h + action.dh, minH);
  // 키운 자리에 걸친 위젯은 끌어서 키울 때처럼 밀어낸다 — 제자리 moveElement 가 충돌을 풀어 준다
  const resolved = moveElement(next, item, item.x, item.y, true, false, 'vertical', cols) as LayoutItem[];
  return verticalCompactor.compact(resolved, cols) as LayoutItem[];
}

/**
 * 레이아웃 사본에서 위젯을 (x, y) 로 옮기고 세로 압축한 새 레이아웃 — 입력은 건드리지 않는다
 * (위로 옮길 때 여러 행을 시험해 보므로 시도마다 새 사본을 쓴다).
 */
function moveAndCompact(layout: readonly LayoutItem[], id: string, x: number, y: number, cols: number): LayoutItem[] {
  const copy = cloneLayout(layout as Layout) as LayoutItem[];
  const target = copy.find((l) => l.i === id)!;
  const moved = moveElement(copy, target, x, Math.max(y, 0), true, false, 'vertical', cols);
  return verticalCompactor.compact(moved, cols) as LayoutItem[];
}

/** 화면 읽기 프로그램에 알릴 위치·크기 문구 — 사람이 세는 1부터 시작하는 칸 번호로 쓴다 */
export function describeLayoutItem(title: string, item: Pick<LayoutItem, 'x' | 'y' | 'w' | 'h'>): string {
  return `「${title}」 위치 ${item.x + 1}열 ${item.y + 1}행, 크기 너비 ${item.w}칸 높이 ${item.h}칸`;
}

/**
 * 두 배치가 기하(i·x·y·w·h)상 같은지 비교한다. 순서와 moved·static·minW 같은 나머지 필드는 무시한다.
 * RGL 이 되돌려주는 배치는 순서·moved 플래그가 달라질 수 있어 값 전체 비교가 아닌 위치·크기만 본다(WD-823).
 * 위젯 id(i)가 배치 안에서 고유하다고 가정한다 — 같은 i 가 둘이면 Map 이 하나만 남겨 잘못 같다고 볼 수 있다.
 */
export function sameLayoutGeometry(a: readonly LayoutItem[], b: readonly LayoutItem[]): boolean {
  if (a.length !== b.length) return false;
  const byId = new Map(b.map((it) => [it.i, it] as const));
  return a.every((it) => {
    const other = byId.get(it.i);
    return !!other && other.x === it.x && other.y === it.y && other.w === it.w && other.h === it.h;
  });
}

/** 메아리 판정 결과: ack=마지막으로 보낸 배치의 메아리, stale=앞선 배치의 늦은 메아리, external=그 밖(끌기 등) */
export type LayoutEchoVerdict = 'ack' | 'stale' | 'external';

/**
 * onLayoutChange 로 들어온 배치가 우리가 부모에서 내려보낸 배치의 메아리인지 가른다.
 * RGL 이 부모 배치를 effect 뒤에 onLayoutChange 로 늦게 돌려주는 메아리를 가르기 위함이다(WD-823).
 * 키를 빠르게 누르면 키1 의 늦은 메아리가 키2 의 배치를 덮어쓰므로, 마지막 배치를 먼저 보고(ack)
 * 그 앞 배치와 같으면 stale 로 버리게 하며, 어느 쪽과도 다르면 사용자의 끌기 등 외부 변경(external)이다.
 * 키보드 결과(applyKeyboardLayoutAction)와 RGL 이 같은 verticalCompactor 로 압축한다는 데 기댄다 — 그리드의 압축 방식을
 * 바꾸면 RGL 이 다시 압축한 메아리가 보낸 배치와 달라져 모두 external 로 판정된다.
 */
export function classifyLayoutEcho(
  incoming: readonly LayoutItem[],
  pending: readonly (readonly LayoutItem[])[],
): LayoutEchoVerdict {
  if (pending.length === 0) return 'external';
  if (sameLayoutGeometry(incoming, pending[pending.length - 1])) return 'ack';
  return pending.slice(0, -1).some((p) => sameLayoutGeometry(incoming, p)) ? 'stale' : 'external';
}

/**
 * 대기열에 쌓인 키보드 조작을 출발 배치에 차례로 적용한다(WD-823). 마지막 조작의 위젯 항목(안내 문구용)도 함께 돌려준다 —
 * 마지막 키의 위젯이 결과 배치에 없으면 last 는 없다. 펌프(한 키씩)와 「완료」(남은 키 일괄)가 같은 적용·항목 찾기를 쓰게 한다.
 */
export function drainKeyboardQueue(
  base: readonly LayoutItem[],
  actions: readonly { widgetId: number; action: KeyboardLayoutAction }[],
): { layout: LayoutItem[]; last?: { widgetId: number; item: LayoutItem } } {
  let layout = [...base];
  for (const { widgetId, action } of actions) {
    layout = applyKeyboardLayoutAction(layout, String(widgetId), action);
  }
  if (actions.length === 0) return { layout };
  const { widgetId } = actions[actions.length - 1];
  const item = layout.find((l) => l.i === String(widgetId));
  return item ? { layout, last: { widgetId, item } } : { layout };
}
