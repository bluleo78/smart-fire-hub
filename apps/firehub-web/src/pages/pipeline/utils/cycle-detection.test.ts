/**
 * cycle-detection 단위 테스트 — 파이프라인 엣지 추가 전 사이클 검사.
 */
import { describe, expect, it } from 'vitest';

import { wouldCreateCycle } from './cycle-detection';

describe('wouldCreateCycle', () => {
  it('self-loop (source === target) 은 사이클로 간주한다', () => {
    expect(wouldCreateCycle([], 'a', 'a')).toBe(true);
  });

  it('빈 그래프에서 서로 다른 노드 연결은 사이클이 아니다', () => {
    expect(wouldCreateCycle([], 'a', 'b')).toBe(false);
  });

  it('선형 체인(a->b)에 b->a 추가 시 사이클 발생', () => {
    const edges = [{ source: 'a', target: 'b' }];
    expect(wouldCreateCycle(edges, 'b', 'a')).toBe(true);
  });

  it('선형 체인(a->b->c)에 c->a 추가 시 사이클 발생', () => {
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ];
    expect(wouldCreateCycle(edges, 'c', 'a')).toBe(true);
  });

  it('선형 체인(a->b->c)에 a->c 추가는 사이클이 아니다', () => {
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ];
    expect(wouldCreateCycle(edges, 'a', 'c')).toBe(false);
  });

  it('분기 그래프에서 후손으로부터 조상으로의 간접 경로 검출', () => {
    // a->b, a->c, c->d
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'a', target: 'c' },
      { source: 'c', target: 'd' },
    ];
    // d -> a 추가 시 사이클
    expect(wouldCreateCycle(edges, 'd', 'a')).toBe(true);
    // b -> d 추가는 OK (a->b->d, a->c->d는 다이아몬드)
    expect(wouldCreateCycle(edges, 'b', 'd')).toBe(false);
  });

  it('동일 source의 여러 타겟이 있어도 처리한다', () => {
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'a', target: 'c' },
      { source: 'a', target: 'd' },
    ];
    expect(wouldCreateCycle(edges, 'b', 'a')).toBe(true);
    expect(wouldCreateCycle(edges, 'e', 'a')).toBe(false);
  });

  it('연결되지 않은 서브그래프는 서로 영향 없음', () => {
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'x', target: 'y' },
    ];
    expect(wouldCreateCycle(edges, 'b', 'x')).toBe(false);
    expect(wouldCreateCycle(edges, 'y', 'a')).toBe(false);
  });
});
