/**
 * step-graph 단위 테스트 — 스텝 입력 후보의 「뒤 스텝」 판정에 쓰는 자손 수집 (WD-1089),
 * 그리고 선행(조상) 판정에 쓰는 collectAncestorTempIds (WD-1139, WD-1164).
 */
import { describe, expect, it } from 'vitest';

import { collectAncestorTempIds, collectDescendantTempIds } from './step-graph';

const step = (tempId: string, dependsOnTempIds: string[] = []) => ({ tempId, dependsOnTempIds });

describe('collectDescendantTempIds', () => {
  it('직접·간접 후행 스텝을 모두 모으고 자신은 넣지 않는다', () => {
    const steps = [step('a'), step('b', ['a']), step('c', ['b']), step('d')];
    expect(collectDescendantTempIds(steps, 'a')).toEqual(new Set(['b', 'c']));
  });

  it('목록 위치와 상관없이 의존 관계로만 정한다 — 끝에 붙은 선행은 자손이 아니다', () => {
    // 삽입으로 목록 끝에 붙은 x 가 b 의 선행인 경우
    const steps = [step('a'), step('b', ['a', 'x']), step('x')];
    expect(collectDescendantTempIds(steps, 'x')).toEqual(new Set(['b']));
    expect(collectDescendantTempIds(steps, 'b')).toEqual(new Set());
  });

  it('여러 갈래로 합쳐지는 자손은 한 번만 넣는다', () => {
    const steps = [step('a'), step('b', ['a']), step('c', ['a']), step('d', ['b', 'c'])];
    expect(collectDescendantTempIds(steps, 'a')).toEqual(new Set(['b', 'c', 'd']));
  });

  it('사이클이 있어도 끝나고 시작 스텝은 넣지 않는다', () => {
    const steps = [step('a', ['b']), step('b', ['a'])];
    expect(collectDescendantTempIds(steps, 'a')).toEqual(new Set(['b']));
  });

  it('시작 스텝이 목록에 없어도 그것을 의존하는 스텝은 자손으로 모은다', () => {
    const steps = [step('b', ['ghost']), step('c', ['b'])];
    expect(collectDescendantTempIds(steps, 'ghost')).toEqual(new Set(['b', 'c']));
  });
});

describe('collectAncestorTempIds', () => {
  it('직접·간접 선행을 모두 모으고 자신은 넣지 않는다', () => {
    const steps = [step('a'), step('b', ['a']), step('c', ['b'])];
    expect(collectAncestorTempIds(steps, 'c')).toEqual(new Set(['a', 'b']));
    expect(collectAncestorTempIds(steps, 'c').has('c')).toBe(false);
  });

  it('사이클을 따라가도 끝나고 자기 자신은 뺀다', () => {
    const steps = [step('x', ['y']), step('y', ['x'])];
    expect(collectAncestorTempIds(steps, 'x')).toEqual(new Set(['y']));
  });

  it('목록에 없는 선행(고스트)은 결과에 넣지 않는다', () => {
    const steps = [step('x', ['ghost', 'y']), step('y', ['ghost2'])];
    expect(collectAncestorTempIds(steps, 'x')).toEqual(new Set(['y']));
  });

  it('다이아몬드 그래프의 공통 선행은 한 번만 넣는다', () => {
    const steps = [step('a'), step('b', ['a']), step('c', ['a']), step('d', ['b', 'c'])];
    expect(collectAncestorTempIds(steps, 'd')).toEqual(new Set(['a', 'b', 'c']));
    expect(collectAncestorTempIds(steps, 'b')).toEqual(new Set(['a']));
  });

  it('시작 스텝이 목록에 없으면 선행도 없다', () => {
    const steps = [step('a'), step('b', ['a'])];
    expect(collectAncestorTempIds(steps, 'missing')).toEqual(new Set());
  });
});

describe('collectDescendantTempIds — 다이아몬드', () => {
  it('다이아몬드 끝 스텝에는 자손이 없다', () => {
    const steps = [step('a'), step('b', ['a']), step('c', ['a']), step('d', ['b', 'c'])];
    expect(collectDescendantTempIds(steps, 'd')).toEqual(new Set());
    expect(collectDescendantTempIds(steps, 'b')).toEqual(new Set(['d']));
  });
});
