import type { LayoutItem } from 'react-grid-layout';
import { describe, expect, it } from 'vitest';

import {
  applyKeyboardLayoutAction,
  describeLayoutItem,
  keyToLayoutAction,
} from './dashboard-layout-keyboard';

/** 위젯 a(왼쪽 위 6×4)·b(오른쪽 위 6×4)·c(아래 왼쪽 6×4) — 대시보드 기본 배치와 같은 모양 */
function base(): LayoutItem[] {
  return [
    { i: 'a', x: 0, y: 0, w: 6, h: 4, minW: 2, minH: 2 },
    { i: 'b', x: 6, y: 0, w: 6, h: 4, minW: 2, minH: 2 },
    { i: 'c', x: 0, y: 4, w: 6, h: 4, minW: 2, minH: 2 },
  ];
}

const pick = (layout: LayoutItem[], id: string) => {
  const l = layout.find((it) => it.i === id)!;
  return { x: l.x, y: l.y, w: l.w, h: l.h };
};

describe('keyToLayoutAction (WD-324)', () => {
  it('화살표는 이동, Shift+화살표는 크기 조절, 그 밖의 키는 null', () => {
    expect(keyToLayoutAction('ArrowRight', false)).toEqual({ kind: 'move', dx: 1, dy: 0 });
    expect(keyToLayoutAction('ArrowUp', false)).toEqual({ kind: 'move', dx: 0, dy: -1 });
    expect(keyToLayoutAction('ArrowDown', true)).toEqual({ kind: 'resize', dw: 0, dh: 1 });
    expect(keyToLayoutAction('ArrowLeft', true)).toEqual({ kind: 'resize', dw: -1, dh: 0 });
    expect(keyToLayoutAction('Tab', false)).toBeNull();
    expect(keyToLayoutAction('Enter', true)).toBeNull();
  });
});

describe('applyKeyboardLayoutAction (WD-324)', () => {
  it('오른쪽으로 한 칸 옮기면 부딪힌 위젯을 밀어내고 세로로 압축한다', () => {
    const next = applyKeyboardLayoutAction(base(), 'c', { kind: 'move', dx: 1, dy: 0 });
    expect(pick(next, 'c')).toEqual({ x: 1, y: 4, w: 6, h: 4 });
    expect(pick(next, 'a')).toEqual({ x: 0, y: 0, w: 6, h: 4 });
  });

  it('가로는 그리드 안에 묶인다 — 왼쪽 끝에서 ←, 오른쪽 끝에서 → 는 그대로', () => {
    expect(pick(applyKeyboardLayoutAction(base(), 'a', { kind: 'move', dx: -1, dy: 0 }), 'a').x).toBe(0);
    expect(pick(applyKeyboardLayoutAction(base(), 'b', { kind: 'move', dx: 1, dy: 0 }), 'b').x).toBe(6);
  });

  it('아래 위젯을 위로 옮기면 위 위젯과 자리를 바꾼다(끌어 올릴 때와 같음)', () => {
    let layout = base();
    // 압축 때문에 한 칸씩은 제자리로 돌아오므로, 위 위젯 높이만큼 올리면 자리가 바뀐다
    for (let i = 0; i < 4; i++) layout = applyKeyboardLayoutAction(layout, 'c', { kind: 'move', dx: 0, dy: -1 });
    expect(pick(layout, 'c').y).toBe(0);
    expect(pick(layout, 'a').y).toBe(4);
  });

  it('너비를 키우면 겹친 위젯을 아래로 밀고, 그리드 폭을 넘지 않는다', () => {
    const next = applyKeyboardLayoutAction(base(), 'a', { kind: 'resize', dw: 1, dh: 0 });
    expect(pick(next, 'a')).toEqual({ x: 0, y: 0, w: 7, h: 4 });
    // b 는 a 와 겹치지 않는 아래로 밀려난다
    const b = pick(next, 'b');
    expect(b.y).toBeGreaterThanOrEqual(4);
    const full = applyKeyboardLayoutAction(base(), 'b', { kind: 'resize', dw: 1, dh: 0 });
    expect(pick(full, 'b').w).toBe(6);
  });

  it('크기는 minW·minH 아래로 줄지 않는다', () => {
    let layout = base();
    for (let i = 0; i < 10; i++) layout = applyKeyboardLayoutAction(layout, 'a', { kind: 'resize', dw: -1, dh: -1 });
    expect(pick(layout, 'a')).toMatchObject({ w: 2, h: 2 });
  });

  it('없는 위젯이면 레이아웃을 바꾸지 않는다', () => {
    expect(applyKeyboardLayoutAction(base(), 'zzz', { kind: 'move', dx: 1, dy: 0 })).toEqual(base());
  });
});

describe('describeLayoutItem (WD-324)', () => {
  it('1부터 세는 열·행과 칸 수로 알린다', () => {
    expect(describeLayoutItem('매출', { x: 0, y: 4, w: 6, h: 3 })).toBe(
      '「매출」 위치 1열 5행, 크기 너비 6칸 높이 3칸',
    );
  });
});
