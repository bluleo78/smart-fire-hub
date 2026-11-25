/**
 * step-descendants 단위 테스트 — 스텝 입력 후보의 「뒤 스텝」 판정에 쓰는 자손 수집 (WD-1089).
 */
import { describe, expect, it } from 'vitest';

import { collectDescendantTempIds } from './step-descendants';

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
});
