import type cytoscape from 'cytoscape';

import { graphChrome, type TypePalette } from '@/lib/ontology-colors';

// cytoscape 스타일시트 생성 — 노드 색은 data(color)(타입색), 라벨/엣지 색은 테마 크롬에서.
// 크롬 색은 SchemaGraph와 공유하는 단일 소스(ontology-colors.ts)에서 가져온다 (#377).
// 컴포넌트 파일(.tsx)에서 분리한 이유: react-refresh는 컴포넌트 외 export가 섞이면 fast refresh가
// 깨진다는 lint 규칙(react-refresh/only-export-components)이 있어, 유닛 테스트에서 직접 호출할 수
// 있도록 export가 필요한 순수 함수는 별도 모듈로 뺀다 (#418).
export function buildStylesheet(isDark: boolean, palette: TypePalette): cytoscape.StylesheetJson {
  const c = graphChrome(isDark);
  return [
    {
      selector: 'node',
      style: {
        'background-color': 'data(color)',
        width: 34,
        height: 34,
        label: 'data(label)',
        color: c.label,
        'font-size': 11,
        'font-weight': 500,
        'text-valign': 'bottom',
        'text-halign': 'center',
        'text-margin-y': 4,
        'text-wrap': 'ellipsis',
        'text-max-width': '120px',
        // #377: 기존 보더는 표면색 헤일로(노드 겹침 분리용)였으나, 그 결과 원형의 경계가
        // 타입색 자체뿐이라 라이트에서 Cause 2.15 / Equipment 2.54:1로 SC 1.4.11에 미달했다.
        // 타입별 AA 검증 shade를 윤곽선으로 둘러 캔버스 대비 5:1 이상을 확보하면서
        // 겹침 분리 기능도 유지한다.
        // 윤곽선은 요소 data가 아니라 **스타일시트 함수 매퍼**로 계산한다 — 노드 data는
        // 테마 전환 시 재생성되지 않지만(레이아웃 보존), 스타일시트는 테마마다 다시 만든다.
        'border-width': 2,
        'border-color': (ele: cytoscape.NodeSingular) => palette.contour(ele.data('type'), isDark),
      },
    },
    { selector: 'node:selected', style: { 'border-color': c.ring, 'border-width': 3.5 } },
    {
      selector: 'edge',
      style: {
        width: 1.5,
        'line-color': c.edge,
        'target-arrow-color': c.edge,
        'target-arrow-shape': 'triangle',
        'arrow-scale': 0.9,
        'curve-style': 'bezier',
        label: 'data(label)',
        'font-size': 9,
        color: c.muted,
        'text-background-color': c.bg,
        'text-background-opacity': 0.85,
        'text-background-padding': '2px',
        // #418: 노드 라벨과 동일하게 폭 제한 — 긴 관계명이 인접 노드를 뒤덮는 것을 방지.
        'text-wrap': 'ellipsis',
        'text-max-width': '120px',
      },
    },
    { selector: 'edge:selected', style: { 'line-color': c.ring, 'target-arrow-color': c.ring, width: 2 } },
    // hover 스포트라이트 — 이웃 아닌 요소는 흐리게(.faded), 호버 노드는 강조(.spotlight)해 hairball에서 초점을 살린다.
    { selector: '.faded', style: { opacity: 0.12, 'text-opacity': 0.12 } },
    { selector: '.spotlight', style: { 'border-color': c.ring, 'border-width': 3, 'font-weight': 700 } },
    // 타입 묶기 compound 부모 — 타입색 옅은 박스에 상단 라벨. 접힌 메타노드는 부모 스타일을 상속한다.
    {
      selector: ':parent',
      style: {
        shape: 'round-rectangle',
        'background-color': 'data(color)',
        'background-opacity': 0.08,
        // #377: 타입 묶기 박스의 테두리도 base(500)로는 라이트에서 2.15:1까지 떨어진다.
        // 부모 노드의 label이 곧 타입명이므로 같은 윤곽선 색을 쓴다.
        'border-color': (ele: cytoscape.NodeSingular) => palette.contour(ele.data('label'), isDark),
        'border-width': 1,
        label: 'data(label)',
        color: c.label,
        'font-size': 12,
        'font-weight': 700,
        'text-valign': 'top',
        'text-halign': 'center',
        padding: '12px',
      },
    },
  ];
}
