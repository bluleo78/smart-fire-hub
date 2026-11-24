import { describe, expect, it } from 'vitest';

import type { StepNodeData, StepNodeType } from '../components/StepNode';
import { applyNodeOverrides } from './node-overrides';

/** 시험용 노드 — 선택·강조 모두 꺼진 기본 상태 */
function makeNode(id: string): StepNodeType {
  return {
    id,
    type: 'step',
    position: { x: 0, y: 0 },
    selected: false,
    data: { label: id, isSelected: false } as unknown as StepNodeData,
  };
}

describe('applyNodeOverrides', () => {
  const base = [makeNode('a'), makeNode('b'), makeNode('c')];

  it('선택 노드만 새 객체', () => {
    const out = applyNodeOverrides(base, 'b', null);
    expect(out[0]).toBe(base[0]);
    expect(out[2]).toBe(base[2]);
    expect(out[1].selected).toBe(true);
    expect(out[1].data.isSelected).toBe(true);
    expect(base[1].selected).toBe(false);
  });

  it('선택 없음이면 같은 배열', () => {
    expect(applyNodeOverrides(base, null, null)).toBe(base);
  });

  it('없는 id 면 같은 배열', () => {
    expect(applyNodeOverrides(base, 'zzz', null)).toBe(base);
  });

  it('끄는 노드 위치만', () => {
    const out = applyNodeOverrides(base, null, { id: 'c', position: { x: 9, y: 9 } });
    expect(out[2].position).toEqual({ x: 9, y: 9 });
    expect(out[0]).toBe(base[0]);
    expect(out[1]).toBe(base[1]);
  });

  it('끄는 노드 = 선택 노드', () => {
    const out = applyNodeOverrides(base, 'a', { id: 'a', position: { x: 1, y: 2 } });
    expect(out[0].selected).toBe(true);
    expect(out[0].data.isSelected).toBe(true);
    expect(out[0].position).toEqual({ x: 1, y: 2 });
    expect(out[1]).toBe(base[1]);
    expect(out[2]).toBe(base[2]);
  });
});
