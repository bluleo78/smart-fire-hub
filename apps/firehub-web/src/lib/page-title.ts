import { matchPath } from 'react-router-dom';

/**
 * 라우트 → 문서 제목(document.title) 매핑 (WD-580, WCAG 2.4.2 Page Titled).
 *
 * SPA 라 index.html 의 <title> 하나뿐이어서 모든 화면의 탭 제목이 브랜드명으로 같았다 —
 * 스크린리더 사용자·탭 여러 개를 띄운 사용자가 지금 어느 화면인지 구분할 수 없었다.
 * 경로별 화면 이름을 이 한 곳에 모아 두고, PageTitleSync 가 경로가 바뀔 때마다 제목을 갱신한다.
 *
 * - 화면 이름은 사이드바 메뉴 라벨(AppLayout navItems)·페이지 제목(h1)과 같은 문구를 쓴다.
 * - 상세·생성·편집 화면은 개별 리소스 이름 대신 섹션 이름을 쓴다(리소스 이름은 비동기 로딩이라 범위 밖).
 * - 패턴은 App.tsx 의 <Route path> 와 1:1 로 맞춘다 — 여기에 없는 경로는 App.tsx 의 catch-all(404)과 같이 404 제목이 된다.
 *   App.tsx 에 라우트를 추가하면 이 표에도 추가해야 한다 — 빠뜨리면 page-title.test.ts 의 동기화 가드
 *   (App.tsx 의 모든 <Route path> 가 404 아닌 제목으로 풀리는지 검사)가 실패한다.
 * - URL 은 그대로 두고 다른 화면을 그리는 경우(워크스페이스 선택·권한 안내)는 이 표가 아니라 PageTitleOverride 가 맡는다.
 */

/** 404 화면 제목 — NotFoundPage 의 안내 문구(「페이지를 찾을 수 없습니다」)를 제목형으로 줄인 것 */
export const NOT_FOUND_TITLE = '페이지를 찾을 수 없음';

/** 테넌트 미선택 시 ProtectedRoute 가 그 자리에서 그리는 SelectTenantPage 의 h1 문구 */
export const SELECT_TENANT_TITLE = '워크스페이스 선택';

/** 화면 권한이 없을 때 AdminOrPermissionRoute 가 그리는 안내(「이 화면을 볼 권한이 없습니다」)를 제목형으로 줄인 것 */
export const FORBIDDEN_TITLE = '접근 권한 없음';

/** 제목 구분자 — 「화면 이름 · 브랜드명」 */
const TITLE_SEPARATOR = ' · ';

/**
 * [경로 패턴, 화면 이름] 목록. matchPath 는 기본 end:true 라 깊이가 다른 경로끼리 섞이지 않는다.
 * 화면 이름이 null 이면 홈 — 브랜드명만 쓴다.
 * 리다이렉트 전용 경로(/knowledge-graph 등)도 넣어 두어, 이동 직전 한 틱 동안 404 제목이 스치지 않게 한다.
 */
const ROUTE_TITLES: ReadonlyArray<readonly [string, string | null]> = [
  ['/', null],
  // 인증 화면 — AppLayout 밖이라 레이아웃이 아닌 라우터 최상단에서 제목을 맞춰야 한다
  ['/login', '로그인'],
  ['/signup', '회원가입'],
  ['/change-password', '비밀번호 변경'],
  ['/profile', '내 프로필'],
  ['/settings/channels', '알림 채널 설정'],
  // 데이터
  ['/data/categories', '카테고리'],
  ['/data/datasets', '데이터셋'],
  ['/data/datasets/new', '데이터셋'],
  ['/data/datasets/:id', '데이터셋'],
  // 자동화
  ['/pipelines', '파이프라인'],
  ['/pipelines/new', '파이프라인'],
  ['/pipelines/:id', '파이프라인'],
  ['/pipelines/:id/executions/:execId', '파이프라인'],
  ['/admin/api-connections', 'API 연결'],
  ['/admin/api-connections/:id', 'API 연결'],
  // 분석
  ['/analytics/queries', '쿼리'],
  ['/analytics/queries/new', '쿼리'],
  ['/analytics/queries/:id', '쿼리'],
  ['/analytics/charts', '차트'],
  ['/analytics/charts/new', '차트'],
  ['/analytics/charts/:id', '차트'],
  ['/analytics/dashboards', '대시보드'],
  ['/analytics/dashboards/:id', '대시보드'],
  // AI 인사이트 — 실행 결과 리포트 뷰어는 「리포트」, 나머지 작업 하위 화면은 「스마트 작업」
  ['/ai-insights/jobs', '스마트 작업'],
  ['/ai-insights/jobs/new', '스마트 작업'],
  ['/ai-insights/jobs/:id', '스마트 작업'],
  ['/ai-insights/jobs/:jobId/executions/:executionId', '스마트 작업'],
  ['/ai-insights/jobs/:jobId/executions/:executionId/report', '리포트'],
  ['/ai-insights/reports', '리포트'],
  ['/ai-insights/templates', '리포트'],
  ['/ai-insights/templates/new', '리포트'],
  ['/ai-insights/templates/:id', '리포트'],
  // 지식그래프 — 고정 경로를 :view 보다 먼저 둔다(먼저 맞는 항목이 이긴다)
  ['/knowledge-graph', '그래프 탐색'],
  ['/knowledge-graph/explore', '그래프 탐색'],
  ['/knowledge-graph/model', '지식 모델'],
  ['/knowledge-graph/review', 'AI 검수'],
  ['/knowledge-graph/synonym-review', 'AI 검수'],
  // 그 밖의 view 값은 OntologyPage 가 'model' 이 아니면 탐색 뷰를 그리므로 탐색과 같은 이름을 쓴다
  ['/knowledge-graph/:view', '그래프 탐색'],
  ['/admin/ontology', '그래프 탐색'],
  // 관리
  ['/admin/settings', '설정'],
  ['/admin/ai-settings', '설정'],
  ['/admin/users', '사용자 관리'],
  ['/admin/users/:id', '사용자 관리'],
  ['/admin/roles', '역할 관리'],
  ['/admin/roles/:id', '역할 관리'],
  ['/admin/audit-logs', '감사 로그'],
];

/**
 * 경로의 화면 이름을 구한다. 홈은 null, 등록되지 않은 경로는 404 제목.
 */
export function resolvePageLabel(pathname: string): string | null {
  for (const [pattern, label] of ROUTE_TITLES) {
    if (matchPath(pattern, pathname)) return label;
  }
  return NOT_FOUND_TITLE;
}

/**
 * 탭 제목 문자열을 만든다 — 홈은 브랜드명만, 그 외는 「화면 이름 · 브랜드명」.
 * 브랜드명을 뒤에 두는 이유: 탭 폭이 좁아 잘릴 때 화면을 구분하는 앞부분이 남게 하려는 것.
 */
export function formatPageTitle(pathname: string, brandName: string): string {
  return composePageTitle(resolvePageLabel(pathname), brandName);
}

/** 화면 이름(null 이면 홈)과 브랜드명으로 제목을 조립한다 — 경로 기반·덮어쓰기 제목이 같은 형식을 쓰게 한 곳에 둔다. */
export function composePageTitle(label: string | null, brandName: string): string {
  return label ? `${label}${TITLE_SEPARATOR}${brandName}` : brandName;
}
