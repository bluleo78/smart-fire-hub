import { describe, expect, it } from 'vitest';

import { createTypePalette } from '@/lib/ontology-colors';

import { buildStylesheet } from './instance-graph-stylesheet';

// #418: 관계(edge) 라벨이 길면 잘림 없이 렌더돼 인접 노드를 뒤덮던 결함의 회귀 방지 테스트.
// 캔버스 렌더 결과는 픽셀 단위라 E2E로 검증하기 어려우므로, cytoscape 스타일시트가
// node와 동일하게 text-wrap/text-max-width를 갖는지 유닛 테스트로 고정한다.
describe('InstanceGraph buildStylesheet', () => {
  it('edge 셀렉터가 node와 동일하게 라벨 폭 제한을 갖는다', () => {
    // (#396) 스타일시트가 타입 팔레트를 인자로 받게 되어(윤곽선 색 매퍼) 최소 팔레트를 넘긴다.
    const stylesheet = buildStylesheet(false, createTypePalette(['Incident'])) as Array<{ selector: string; style: Record<string, unknown> }>;

    const nodeRule = stylesheet.find((rule) => rule.selector === 'node');
    const edgeRule = stylesheet.find((rule) => rule.selector === 'edge');

    expect(nodeRule?.style['text-wrap']).toBe('ellipsis');
    expect(nodeRule?.style['text-max-width']).toBeTruthy();

    expect(edgeRule?.style['text-wrap']).toBe('ellipsis');
    expect(edgeRule?.style['text-max-width']).toBeTruthy();
  });

  // #507: 엣지 라벨 배경(text-background-color)이 리터럴 고정값이 아니라 실제 페이지 배경
  // (--background 계산값)을 따라가는지 확인 — 테마 컬러(indigo/ocean/sunset)마다 달라지는 값이다.
  it('background 인자를 넘기면 엣지 라벨 배경이 그 값을 따른다', () => {
    const stylesheet = buildStylesheet(true, createTypePalette(['Incident']), 'oklch(0.12 0.015 210)') as Array<{
      selector: string;
      style: Record<string, unknown>;
    }>;
    const edgeRule = stylesheet.find((rule) => rule.selector === 'edge');
    expect(edgeRule?.style['text-background-color']).toBe('oklch(0.12 0.015 210)');
  });
});
