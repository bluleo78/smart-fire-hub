/**
 * AI 네비게이션 라우트 정의.
 * NavigateToWidget의 경로 매핑 + AI 세션 시작 시 컨텍스트로 전달.
 *
 * 라우트 추가 시 이 파일만 수정하면 프론트엔드 + AI 양쪽에 자동 반영.
 */

export interface NavigableRoute {
  /** navigate_to 도구의 type 값 */
  type: string;
  /** 한글 라벨 */
  label: string;
  /** 아이콘 */
  icon: string;
  /** 목록 페이지 경로 */
  listPath: string;
  /** 상세 페이지 경로 패턴 (id 치환) */
  detailPath?: (id: number) => string;
  /** AI가 이 페이지를 이해하기 위한 설명 */
  aiDescription: string;
  /** AI가 이 페이지로 이동해야 하는 사용자 발화 예시 */
  aiExamples?: string[];
}

export const NAVIGABLE_ROUTES: NavigableRoute[] = [
  {
    type: 'home',
    label: '홈',
    icon: '🏠',
    listPath: '/',
    aiDescription: '전체 시스템 현황을 한눈에 파악하고 싶을 때. 건강 상태, 주의 필요 항목, 최근 활동 요약을 제공.',
    aiExamples: ['홈으로 가줘', '전체 현황 보고 싶어', '메인 페이지'],
  },
  {
    type: 'dataset',
    label: '데이터셋',
    icon: '📦',
    listPath: '/data/datasets',
    detailPath: (id) => `/data/datasets/${id}`,
    aiDescription: '데이터를 직접 탐색하거나 편집/임포트/내보내기 등 UI에서만 가능한 작업을 하려 할 때. 목록: 전체 데이터셋 검색/필터링, 상세(id): 특정 데이터셋의 데이터 조회/편집/컬럼 관리.',
    aiExamples: ['데이터셋 목록 보여줘', '소방장비 데이터셋 열어줘', '데이터 직접 수정하고 싶어'],
  },
  {
    type: 'dataset_new',
    label: '데이터셋 생성',
    icon: '📦',
    listPath: '/data/datasets/new',
    aiDescription: '사용자가 UI에서 직접 테이블 구조를 설계하며 데이터셋을 만들고 싶을 때. 컬럼 타입/이름을 시각적으로 설정 가능.',
    aiExamples: ['새 데이터셋 만들러 가줘', '테이블 직접 설계하고 싶어'],
  },
  {
    type: 'category',
    label: '카테고리',
    icon: '📂',
    listPath: '/data/categories',
    aiDescription: '데이터셋 분류 체계를 정리하거나 카테고리를 추가/수정하려 할 때.',
    aiExamples: ['카테고리 관리 열어줘', '분류 체계 정리하고 싶어'],
  },
  {
    type: 'pipeline',
    label: '파이프라인',
    icon: '⚙️',
    listPath: '/pipelines',
    detailPath: (id) => `/pipelines/${id}`,
    aiDescription: '파이프라인을 직접 편집하거나 실행 이력을 상세히 확인하려 할 때. 목록: 전체 파이프라인 현황, 상세(id): 스텝 편집/실행/로그 확인/트리거 관리 등 채팅보다 UI가 효과적인 작업.',
    aiExamples: ['파이프라인 목록 보여줘', '일일 수집 파이프라인 편집하러 가줘', '실행 로그 직접 확인하고 싶어'],
  },
  {
    type: 'pipeline_new',
    label: '파이프라인 생성',
    icon: '⚙️',
    listPath: '/pipelines/new',
    aiDescription: 'DAG 에디터에서 시각적으로 파이프라인을 구성하고 싶을 때. 스텝 간 의존성을 드래그로 설정 가능.',
    aiExamples: ['새 파이프라인 만들러 가줘', '파이프라인 직접 설계하고 싶어'],
  },
  {
    type: 'query',
    label: '쿼리 에디터',
    icon: '🔍',
    listPath: '/analytics/queries',
    detailPath: (id) => `/analytics/queries/${id}`,
    aiDescription: 'SQL을 직접 작성/수정하거나 복잡한 쿼리를 반복 실행하며 결과를 확인하려 할 때. 스키마 탐색기, 자동완성, 실행 결과 테이블 등 채팅보다 에디터 UI가 효과적.',
    aiExamples: ['쿼리 에디터 열어줘', 'SQL 직접 작성하고 싶어', '저장된 쿼리 수정하러 가줘'],
  },
  {
    type: 'query_new',
    label: '쿼리 생성',
    icon: '🔍',
    listPath: '/analytics/queries/new',
    aiDescription: '빈 쿼리 에디터에서 새 SQL을 작성하고 싶을 때.',
    aiExamples: ['새 쿼리 작성하러 가줘'],
  },
  {
    type: 'chart',
    label: '차트',
    icon: '📈',
    listPath: '/analytics/charts',
    detailPath: (id) => `/analytics/charts/${id}`,
    aiDescription: '차트 설정을 직접 조정하거나(축, 색상, 유형 변경 등) 차트를 대시보드에 배치하려 할 때. 목록: 전체 차트 검색, 상세(id): 차트 편집/미리보기.',
    aiExamples: ['차트 목록 보여줘', '이 차트 설정 바꾸고 싶어', '차트 편집하러 가줘'],
  },
  {
    type: 'chart_new',
    label: '차트 생성',
    icon: '📈',
    listPath: '/analytics/charts/new',
    aiDescription: '차트 빌더에서 시각적으로 차트를 구성하고 싶을 때.',
    aiExamples: ['새 차트 만들러 가줘'],
  },
  {
    type: 'dashboard',
    label: '분석 대시보드',
    icon: '📊',
    listPath: '/analytics/dashboards',
    detailPath: (id) => `/analytics/dashboards/${id}`,
    aiDescription: '여러 차트를 한 화면에서 비교하거나, 대시보드 레이아웃을 편집하려 할 때. 목록: 전체 대시보드, 상세(id): 차트 배치/크기 조정/자동 갱신 설정.',
    aiExamples: ['대시보드 목록 보여줘', '운영 대시보드 열어줘', '대시보드 편집하러 가줘'],
  },
  {
    type: 'settings',
    label: '시스템 설정',
    icon: '⚙️',
    listPath: '/admin/settings',
    aiDescription: 'AI 모델/온도/토큰 설정, API 키 관리, 에이전트 타입 변경 등 시스템 설정을 조정하려 할 때.',
    aiExamples: ['설정 페이지 열어줘', 'AI 모델 바꾸고 싶어', 'API 키 설정하러 가줘'],
  },
  {
    type: 'users',
    label: '사용자 관리',
    icon: '👥',
    listPath: '/admin/users',
    detailPath: (id) => `/admin/users/${id}`,
    aiDescription: '사용자 계정을 추가/수정하거나 역할을 할당하려 할 때. (관리자 전용)',
    aiExamples: ['사용자 관리 열어줘', '사용자 역할 바꾸고 싶어'],
  },
  {
    type: 'roles',
    label: '역할 관리',
    icon: '🔐',
    listPath: '/admin/roles',
    detailPath: (id) => `/admin/roles/${id}`,
    aiDescription: '역할별 권한을 설정하거나 새 역할을 만들려 할 때. (관리자 전용)',
    aiExamples: ['역할 관리 열어줘', '권한 설정하러 가줘'],
  },
  {
    type: 'audit_logs',
    label: '감사 로그',
    icon: '📋',
    listPath: '/admin/audit-logs',
    aiDescription: '누가 언제 무엇을 했는지 사용자 행위를 추적/감사하려 할 때. (관리자 전용)',
    aiExamples: ['감사 로그 보여줘', '누가 데이터 삭제했는지 확인하고 싶어'],
  },
  {
    type: 'api_connections',
    label: 'API 연결',
    icon: '🔌',
    listPath: '/admin/api-connections',
    detailPath: (id) => `/admin/api-connections/${id}`,
    aiDescription: '외부 API 인증 정보(API 키, Bearer 토큰)를 등록/수정하려 할 때. 파이프라인의 API_CALL 스텝에서 사용. (관리자 전용)',
    aiExamples: ['API 연결 관리 열어줘', 'API 키 등록하러 가줘'],
  },
  {
    type: 'ai_insights_jobs',
    label: '스마트 작업',
    icon: '⚡',
    listPath: '/ai-insights/jobs',
    aiDescription: 'AI가 자동으로 실행하는 스마트 작업을 관리하려 할 때.',
    aiExamples: ['스마트 작업 보여줘', 'AI 자동 작업 설정', '예약 분석 관리'],
  },
  {
    type: 'ai_insights_templates',
    label: '리포트 양식',
    icon: '📄',
    listPath: '/ai-insights/templates',
    aiDescription: 'AI 리포트의 출력 구조(템플릿)를 정의하거나 수정하려 할 때.',
    aiExamples: ['리포트 양식 보여줘', '리포트 템플릿 관리하고 싶어', 'AI 리포트 형식 설정'],
  },
  {
    type: 'profile',
    label: '프로필',
    icon: '👤',
    listPath: '/profile',
    aiDescription: '내 이름, 이메일, 비밀번호 등 개인 정보를 수정하려 할 때.',
    aiExamples: ['내 프로필 열어줘', '비밀번호 바꾸고 싶어'],
  },
];

/** navigate_to 위젯에서 사용: type → 경로 변환 */
export function resolveNavigationPath(type: string, id?: number): string | null {
  const route = NAVIGABLE_ROUTES.find(r => r.type === type);
  if (!route) return null;
  return id && route.detailPath ? route.detailPath(id) : route.listPath;
}

/** AI 세션 시작 시 전달할 네비게이션 컨텍스트 생성 */
export function buildNavigationContext(): string {
  const lines = NAVIGABLE_ROUTES.map(r => {
    const parts = [`- ${r.type}: ${r.label} — ${r.aiDescription}`];
    if (r.detailPath) parts[0] += ' (목록/상세)';
    if (r.aiExamples?.length) {
      parts.push(`  예: "${r.aiExamples.join('", "')}"`);
    }
    return parts.join('\n');
  });
  return `[네비게이션 가능 페이지]\nnavigate_to 도구의 type에 아래 값을 사용하세요. id를 생략하면 목록, id를 포함하면 상세 페이지로 이동합니다.\n\n${lines.join('\n')}`;
}
