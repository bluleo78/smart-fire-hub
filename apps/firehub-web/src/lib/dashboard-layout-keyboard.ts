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
