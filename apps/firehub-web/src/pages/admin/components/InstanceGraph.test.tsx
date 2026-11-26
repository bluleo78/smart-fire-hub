import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { createTypePalette } from '@/lib/ontology-colors';
import type { GraphData } from '@/types/ontology';

// 캔버스(Cytoscape)는 jsdom 에서 그려지지 않으므로 어떤 호출에도 자기 자신을 돌려주는 가짜로 대체한다.
// 이 테스트는 0건 오버레이의 분기·문구·콜백만 본다(WD-1127).
vi.mock('cytoscape', () => {
  const fake: unknown = new Proxy(function () {}, {
    get: (_t, prop) => (prop === 'length' ? 0 : prop === 'then' ? undefined : fake),
    apply: () => fake,
  });
  const cytoscape = Object.assign(() => fake, { use: () => {} });
  return { default: cytoscape };
});
vi.mock('cytoscape-euler', () => ({ default: {} }));
vi.mock('cytoscape-expand-collapse', () => ({ default: {} }));
vi.mock('cytoscape-fcose', () => ({ default: {} }));
vi.mock('next-themes', () => ({ useTheme: () => ({ resolvedTheme: 'light' }) }));
vi.mock('@/hooks/useCanvasBackground', () => ({ useCanvasBackground: () => '#ffffff' }));

// jsdom 에는 ResizeObserver 가 없다 — 크기 감시는 이 테스트와 무관하므로 빈 구현을 둔다.
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
);

import InstanceGraph from './InstanceGraph';

const graph: GraphData = {
  nodes: [{ key: 'a-1', type: 'A', name: '노드 하나', sourceChunkCount: 1, schemaVersion: 1, ontologyId: 1 }],
  edges: [],
};

/** 타입 A 노드만 있는 그래프에 주어진 필터·검색을 걸어 렌더한다. */
function renderGraph(props: { activeTypes: Set<string>; search: string; onResetTypes?: () => void }) {
  return render(
    <InstanceGraph
      graph={graph}
      palette={createTypePalette(['A', 'B'])}
      onNodeSelect={() => {}}
      {...props}
    />,
  );
}

describe('InstanceGraph 0건 오버레이 (WD-1127)', () => {
  it('검색어 없이 타입 필터만으로 0건이면 타입 안내와 「타입 필터 초기화」를 보이고 클릭 시 onResetTypes 를 부른다', () => {
    const onResetTypes = vi.fn();
    renderGraph({ activeTypes: new Set(['B']), search: '', onResetTypes });

    const overlay = screen.getByTestId('instance-graph-no-results');
    expect(overlay).toHaveTextContent('선택한 타입에 맞는 노드가 없습니다.');
    fireEvent.click(screen.getByRole('button', { name: '타입 필터 초기화' }));
    expect(onResetTypes).toHaveBeenCalledTimes(1);
  });

  it('검색어가 있으면 타입 필터가 걸려 있어도 검색 쪽 안내(검색어 인용, 타입 필터 초기화 없음)', () => {
    renderGraph({ activeTypes: new Set(['B']), search: '  zzz  ', onResetTypes: () => {} });

    const overlay = screen.getByTestId('instance-graph-no-results');
    expect(overlay).toHaveTextContent("'zzz'");
    expect(screen.queryByRole('button', { name: '타입 필터 초기화' })).not.toBeInTheDocument();
  });

  it('검색어가 다른 타입 노드에는 맞는데 타입 필터가 숨겨 0건이면 타입 안내(검색 초기화 없음)', () => {
    renderGraph({ activeTypes: new Set(['B']), search: '하나', onResetTypes: () => {} });

    const overlay = screen.getByTestId('instance-graph-no-results');
    expect(overlay).toHaveTextContent('선택한 타입에 맞는 노드가 없습니다.');
    expect(screen.queryByRole('button', { name: '검색 초기화' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '타입 필터 초기화' })).toBeInTheDocument();
  });
});
