import { describe, expect, it } from 'vitest';

import { buildStylesheet } from './schema-graph-stylesheet';

// #418: 관계(edge) 라벨이 길면 잘림 없이 렌더돼 인접 노드를 뒤덮던 결함의 회귀 방지 테스트.
// 캔버스 렌더 결과는 픽셀 단위라 E2E로 검증하기 어려우므로, cytoscape 스타일시트가
// node와 동일하게 text-wrap/text-max-width를 갖는지 유닛 테스트로 고정한다.
describe('SchemaGraph buildStylesheet', () => {
  it('edge 셀렉터가 node와 동일하게 라벨 폭 제한을 갖는다', () => {
    const stylesheet = buildStylesheet(false) as Array<{ selector: string; style: Record<string, unknown> }>;

    const nodeRule = stylesheet.find((rule) => rule.selector === 'node');
    const edgeRule = stylesheet.find((rule) => rule.selector === 'edge');

    expect(nodeRule?.style['text-wrap']).toBe('ellipsis');
    expect(nodeRule?.style['text-max-width']).toBeTruthy();

    expect(edgeRule?.style['text-wrap']).toBe('ellipsis');
    expect(edgeRule?.style['text-max-width']).toBeTruthy();
  });
});
