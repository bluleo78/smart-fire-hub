/**
 * NodeDetailDrawer 단위 테스트 (#678)
 *
 * "구버전" 배지는 더 이상 단일 currentSchemaVersion과 비교하지 않고, 노드가 실제로 적재된
 * 온톨로지(node.ontologyId)를 schemaVersionByOntologyId 맵에서 찾아 비교한다. 다른 온톨로지의
 * 버전이 더 낮아도 이 노드에는 영향이 없어야 하고, ontologyId가 없는 레거시 노드는 판정 자체를
 * 하지 않아야 한다.
 */
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createTypePalette } from '@/lib/ontology-colors';

import NodeDetailDrawer from './NodeDetailDrawer';

// (WD-372) jsdom 에는 matchMedia 가 없어 기본은 도킹 패널이다. 좁은 화면(모달) 분기를 보려고 import 전에 matchMedia 를 심고,
// 테스트마다 넓은 화면 여부를 바꾼다. useMediaQuery 는 matchMedia 존재 여부를 모듈 로드 때 한 번 보므로 hoisted 로 먼저 심는다.
const media = vi.hoisted(() => {
  const state = { wide: true };
  window.matchMedia = ((query: string) => ({
    matches: state.wide,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia;
  return state;
});

describe('NodeDetailDrawer', () => {
  const baseNode = {
    key: 'k1',
    type: 'Incident',
    name: '화재 A',
    sourceChunkCount: 1,
  };
  const palette = createTypePalette(['Incident']);

  it('노드의 ontologyId로 찾은 현재 버전보다 낮으면 구버전 배지를 표시한다', () => {
    render(
      <NodeDetailDrawer
        node={{ ...baseNode, schemaVersion: 1, ontologyId: 5 }}
        edges={[]}
        nodesByKey={new Map()}
        onClose={() => {}}
        schemaVersionByOntologyId={new Map([[5, 3]])}
        palette={palette}
      />,
    );

    expect(screen.getByTestId('node-schema-version')).toHaveTextContent('(구버전)');
  });

  it('다른 온톨로지(id=9)의 버전이 낮아도 이 노드(ontologyId=5)에는 영향이 없다', () => {
    render(
      <NodeDetailDrawer
        node={{ ...baseNode, schemaVersion: 3, ontologyId: 5 }}
        edges={[]}
        nodesByKey={new Map()}
        onClose={() => {}}
        schemaVersionByOntologyId={new Map([[5, 3], [9, 1]])}
        palette={palette}
      />,
    );

    expect(screen.getByTestId('node-schema-version')).not.toHaveTextContent('(구버전)');
  });

  it('ontologyId가 없는 레거시 노드는 구버전 판정을 하지 않는다', () => {
    render(
      <NodeDetailDrawer
        node={{ ...baseNode, schemaVersion: 1, ontologyId: null }}
        edges={[]}
        nodesByKey={new Map()}
        onClose={() => {}}
        schemaVersionByOntologyId={new Map([[5, 3]])}
        palette={palette}
      />,
    );

    expect(screen.getByTestId('node-schema-version')).not.toHaveTextContent('(구버전)');
  });

  describe('좁은 화면(WD-372)', () => {
    afterEach(() => {
      media.wide = true;
    });

    it('대화상자로 띄워 노드 타입을 대화상자 이름으로 쓰고, 크기 조절 손잡이는 없다', () => {
      media.wide = false;
      render(
        <NodeDetailDrawer
          node={{ ...baseNode, schemaVersion: 1, ontologyId: 5 }}
          edges={[]}
          nodesByKey={new Map()}
          onClose={() => {}}
          schemaVersionByOntologyId={new Map([[5, 1]])}
          palette={palette}
        />,
      );

      expect(screen.getByRole('dialog', { name: /Incident/ })).toBeInTheDocument();
      expect(screen.queryByRole('separator')).not.toBeInTheDocument();
    });

    it('Esc 로 닫으면 onClose 가 불린다', () => {
      media.wide = false;
      const onClose = vi.fn();
      render(
        <NodeDetailDrawer
          node={{ ...baseNode, schemaVersion: 1, ontologyId: 5 }}
          edges={[]}
          nodesByKey={new Map()}
          onClose={onClose}
          schemaVersionByOntologyId={new Map([[5, 1]])}
          palette={palette}
        />,
      );

      fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
      expect(onClose).toHaveBeenCalled();
    });
  });
});

