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

  // WD-377: 타입 묶기 부모(접힌 메타노드)의 라벨에 노드 수(「Incident (30)」)가 붙어도 윤곽선은 타입 색으로 고르고,
  // 같은 관계를 합친 엣지(count>1)는 건수에 비례해 굵어진다.
  it('묶기 부모 윤곽선은 라벨이 아니라 data(type)으로 고르고, 합친 엣지는 count 로 굵기를 정한다', () => {
    const palette = createTypePalette(['Incident', 'Building']);
    const stylesheet = buildStylesheet(false, palette) as Array<{ selector: string; style: Record<string, unknown> }>;
    const parentRule = stylesheet.find((rule) => rule.selector === ':parent');
    const contour = parentRule?.style['border-color'] as (ele: { data(key: string): string }) => string;
    const ele = { data: (key: string) => (key === 'type' ? 'Building' : 'Building (6)') };
    expect(contour(ele)).toBe(palette.contour('Building', false));

    const mergedRule = stylesheet.find((rule) => rule.selector === 'edge[count > 1]');
    expect(mergedRule?.style.width).toBe('mapData(count, 2, 50, 2, 6)');
    // 선택 규칙(width 2)보다 뒤에 있어야 선택해도 합친 엣지의 굵기가 유지된다
    const order = (sel: string) => stylesheet.findIndex((rule) => rule.selector === sel);
    expect(order('edge[count > 1]')).toBeGreaterThan(order('edge:selected'));

    // 접힌 메타노드는 자식이 빠져 :parent 가 아니므로 node 규칙이 칠한다 — 그때도 data(type) 으로 타입 윤곽선을 고른다
    const nodeRule = stylesheet.find((rule) => rule.selector === 'node');
    const nodeContour = nodeRule?.style['border-color'] as (ele: { data(key: string): string }) => string;
    expect(nodeContour(ele)).toBe(palette.contour('Building', false));
  });
});
