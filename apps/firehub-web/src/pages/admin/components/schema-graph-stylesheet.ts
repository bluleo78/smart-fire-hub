import type cytoscape from 'cytoscape';

import { graphChrome } from '@/lib/ontology-colors';

// 스키마 그래프 스타일시트 — 타입 노드는 data(bg/border/text)(테마별 tint·윤곽선·대비 텍스트), 엣지는 크롬 색.
// 크롬 색은 InstanceGraph와 공유하는 단일 소스(ontology-colors.ts)에서 가져온다 (#377).
// 컴포넌트 파일(.tsx)에서 분리한 이유: react-refresh는 컴포넌트 외 export가 섞이면 fast refresh가
// 깨진다는 lint 규칙(react-refresh/only-export-components)이 있어, 유닛 테스트에서 직접 호출할 수
// 있도록 export가 필요한 순수 함수는 별도 모듈로 뺀다 (#418).
export function buildStylesheet(isDark: boolean): cytoscape.StylesheetJson {
  const c = graphChrome(isDark);
  return [
    {
      selector: 'node',
      style: {
        shape: 'round-rectangle',
        'background-color': 'data(bg)',
        'border-color': 'data(border)',
        'border-width': 1.5,
        label: 'data(label)',
        color: 'data(text)',
        'font-size': 12,
        'font-weight': 600,
        'text-valign': 'center',
        'text-halign': 'center',
        width: 'label',
        height: 'label',
        padding: '10px',
        'text-wrap': 'ellipsis',
        'text-max-width': '150px',
      },
    },
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
        'font-size': 11,
        color: c.muted,
        'text-background-color': c.bg,
        'text-background-opacity': 0.8,
        'text-background-padding': '2px',
        // #418: 노드 라벨과 동일하게 폭 제한 — 긴 관계명이 인접 노드를 뒤덮는 것을 방지.
        // 잘린 전체 이름은 상세 패널/툴팁에서 확인 가능하므로 사용성 손실 적음.
        'text-wrap': 'ellipsis',
        'text-max-width': '120px',
      },
    },
    // 편집 모드 선택 강조 — cytoscape 내장 :selected 상태를 그대로 쓴다(InstanceGraph와 동일 패턴).
    // read 모드는 selected를 절대 넘기지 않으므로(.select()가 호출되지 않으므로) 이 규칙은 조용히 무관하다.
    { selector: 'node:selected', style: { 'border-color': c.ring, 'border-width': 3 } },
    { selector: 'edge:selected', style: { 'line-color': c.ring, 'target-arrow-color': c.ring, width: 2.5 } },
  ];
}
