import { cn } from '@/lib/utils';

// 타입 필터 패널과 목록 로딩 스켈레톤이 함께 쓰는 루트 클래스(WD-1041) — 목록 응답을 기다리는 동안엔
// OntologyPage의 TypeFilterPanelSkeleton이, 응답이 온 뒤엔 TypeFilterPanel이 같은 자리에 들어오므로
// 폭·보더·트랜지션이 어긋나는 순간 레이아웃이 밀린다. 두 파일에 같은 문자열을 복사해 두면 고친 쪽
// 하나가 달라지는 CLS 회귀가 다시 생기므로 한 원본에서 뽑아 쓴다.
// 컴포넌트 파일(.tsx)이 아니라 별도 모듈인 이유: react-refresh/only-export-components 는 컴포넌트가
// 아닌 export 가 섞이면 fast refresh 가 깨진다고 경고한다(#418 — schema-graph-stylesheet.ts 와 같은 방식).
//
// (#414) sm(640px) 미만에서는 collapsed 상태와 무관하게 항상 숨긴다 — w-64(256px) + 캔버스
// 최소폭(280px, OntologyPage.tsx의 min-w-[280px])이 536px로, 640px부터는 이 조합이 항상
// 들어가지만 그 미만(모바일 375px 등)에서는 캔버스가 뷰포트 밖으로 밀리거나 슬리버로
// 압착된다. (ModelOutline/인스펙터도 예전엔 창 폭 xl 미만에서 숨었지만(#403), 지금은 본문
// 컨테이너 폭 기준으로 접기 막대·선택 시에만 붙는 패널로 바뀌었다 — WD-130.)
export function typeFilterPanelRootClass(collapsed: boolean) {
  return cn(
    'hidden shrink-0 flex-col overflow-hidden border-r transition-[width] duration-200 sm:flex',
    collapsed ? 'sm:w-0 sm:border-r-0' : 'sm:w-64',
  );
}