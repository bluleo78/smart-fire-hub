import type { LayoutItem } from 'react-grid-layout';
import { describe, expect, it } from 'vitest';

import {
  applyKeyboardLayoutAction,
  classifyLayoutEcho,
  describeLayoutItem,
  drainKeyboardQueue,
  keyToLayoutAction,
  sameLayoutGeometry,
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

const A1: LayoutItem[] = [{ i: '1', x: 2, y: 0, w: 6, h: 4 }];
const A2: LayoutItem[] = [{ i: '1', x: 2, y: 0, w: 6, h: 5 }];
const B: LayoutItem[] = [{ i: '1', x: 0, y: 3, w: 6, h: 5 }];

describe('sameLayoutGeometry (WD-823)', () => {
  it('sameLayoutGeometry: 순서·moved 만 다르면 같다', () => {
    const a: LayoutItem[] = [
      { i: '1', x: 0, y: 0, w: 2, h: 2 },
      { i: '2', x: 2, y: 0, w: 2, h: 2 },
    ];
    const b: LayoutItem[] = [
      { i: '2', x: 2, y: 0, w: 2, h: 2, moved: true },
      { i: '1', x: 0, y: 0, w: 2, h: 2, moved: true },
    ];
    expect(sameLayoutGeometry(a, b)).toBe(true);
  });

  it('sameLayoutGeometry: h 하나 다르면 다르다', () => {
    expect(sameLayoutGeometry(A1, A2)).toBe(false);
  });

  it('sameLayoutGeometry: 같은 길이라도 위젯 id(i)가 다르면 다르다', () => {
    expect(sameLayoutGeometry(A1, [{ ...A1[0], i: '2' }])).toBe(false);
  });

  it('sameLayoutGeometry: 항목 수가 다르면 다르다', () => {
    expect(sameLayoutGeometry(A1, [...A1, { i: '2', x: 0, y: 4, w: 2, h: 2 }])).toBe(false);
  });
});

describe('classifyLayoutEcho (WD-823)', () => {

  it('보낸 배치가 없으면 external', () => {
    expect(classifyLayoutEcho(A1, [])).toBe('external');
  });

  it('앞선 키의 늦은 메아리는 stale', () => {
    expect(classifyLayoutEcho([{ ...A1[0], moved: false }], [A1, A2])).toBe('stale');
  });

  it('마지막 배치의 메아리는 ack', () => {
    expect(classifyLayoutEcho(A2, [A1, A2])).toBe('ack');
  });

  it('끝에서 안 바뀐 키(A1, A1)는 ack', () => {
    expect(classifyLayoutEcho(A1, [A1, A1])).toBe('ack');
  });

  it('ack 뒤 목록을 마지막 배치 하나로 줄이면 같은 메아리가 다시 와도 ack, 앞 배치는 external', () => {
    // WD-823: handleLayoutChange 는 ack 때 목록을 [마지막] 하나로 줄인다 — 같은 메아리가 두 번 와도 무해해야 한다
    expect(classifyLayoutEcho(A2, [A2])).toBe('ack');
    expect(classifyLayoutEcho(A1, [A2])).toBe('external');
  });

  it('어느 것과도 다른 끌기 결과는 external', () => {
    expect(classifyLayoutEcho(B, [A1, A2])).toBe('external');
  });
});

describe('drainKeyboardQueue (WD-823)', () => {
  it('마지막 키의 위젯이 배치에 없으면 배치는 그대로고 last 는 없다', () => {
    const { layout, last } = drainKeyboardQueue(base(), [{ widgetId: 99, action: { kind: 'move', dx: 1, dy: 0 } }]);
    expect(layout).toEqual(base());
    expect(last).toBeUndefined();
  });

  it('두 키를 차례로 적용한 결과가 한 키씩 적용한 것과 같다', () => {
    const layout0: LayoutItem[] = [{ i: '1', x: 0, y: 0, w: 4, h: 2, minW: 2, minH: 2 }];
    const first = { kind: 'move' as const, dx: 1, dy: 0 };
    const second = { kind: 'resize' as const, dw: 1, dh: 1 };
    const { layout, last } = drainKeyboardQueue(layout0, [
      { widgetId: 1, action: first },
      { widgetId: 1, action: second },
    ]);
    const stepwise = applyKeyboardLayoutAction(applyKeyboardLayoutAction(layout0, '1', first), '1', second);
    expect(layout).toEqual(stepwise);
    expect(last).toEqual({ widgetId: 1, item: stepwise[0] });
  });

  it('대기열이 비었으면 출발 배치 그대로, last 는 없다', () => {
    const layout0 = base();
    const { layout, last } = drainKeyboardQueue(layout0, []);
    expect(layout).toEqual(layout0);
    expect(last).toBeUndefined();
  });
});
