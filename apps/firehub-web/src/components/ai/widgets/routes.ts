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
    aiDescription: '시스템 전체 현황 대시보드. 건강 상태, 주의 필요 항목, 최근 활동을 한눈에 확인.',
    aiExamples: ['홈으로 가줘', '메인 페이지 보여줘', '현황판 열어줘'],
  },
  {
    type: 'dataset',
    label: '데이터셋',
    icon: '📦',
    listPath: '/data/datasets',
    detailPath: (id) => `/data/datasets/${id}`,
    aiDescription: '데이터셋 관리. 데이터 조회, 컬럼 확인, 데이터 편집, 내보내기.',
    aiExamples: ['데이터셋 목록 보여줘', '소방장비 데이터셋 열어줘', '데이터 관리 페이지로 가줘'],
  },
  {
    type: 'dataset_new',
    label: '데이터셋 생성',
    icon: '📦',
    listPath: '/data/datasets/new',
    aiDescription: '새 데이터셋 생성 페이지. 테이블명, 컬럼 정의, 카테고리 설정.',
    aiExamples: ['새 데이터셋 만들 페이지 열어줘'],
  },
  {
    type: 'category',
    label: '카테고리',
    icon: '📂',
    listPath: '/data/categories',
    aiDescription: '데이터셋 카테고리 관리. 분류 체계 생성/수정.',
    aiExamples: ['카테고리 관리 페이지 열어줘'],
  },
  {
    type: 'pipeline',
    label: '파이프라인',
    icon: '⚙️',
    listPath: '/pipelines',
    detailPath: (id) => `/pipelines/${id}`,
    aiDescription: 'ETL 파이프라인 관리. SQL/Python/API 스텝으로 데이터 수집·변환·적재. 실행 이력 확인.',
    aiExamples: ['파이프라인 목록 보여줘', '일일 수집 파이프라인 열어줘', '파이프라인 상태 확인하러 가줘'],
  },
  {
    type: 'pipeline_new',
    label: '파이프라인 생성',
    icon: '⚙️',
    listPath: '/pipelines/new',
    aiDescription: '새 파이프라인 생성 페이지. 스텝 구성, DAG 의존성 설정.',
    aiExamples: ['새 파이프라인 만들 페이지 열어줘'],
  },
  {
    type: 'query',
    label: '쿼리 에디터',
    icon: '🔍',
    listPath: '/analytics/queries',
    detailPath: (id) => `/analytics/queries/${id}`,
    aiDescription: 'SQL 쿼리 작성/실행/저장. 데이터 분석의 시작점.',
    aiExamples: ['쿼리 에디터 열어줘', 'SQL 작성하러 가줘', '저장된 쿼리 보여줘'],
  },
  {
    type: 'query_new',
    label: '쿼리 생성',
    icon: '🔍',
    listPath: '/analytics/queries/new',
    aiDescription: '새 SQL 쿼리 작성 페이지.',
    aiExamples: ['새 쿼리 작성하러 가줘'],
  },
  {
    type: 'chart',
    label: '차트',
    icon: '📈',
    listPath: '/analytics/charts',
    detailPath: (id) => `/analytics/charts/${id}`,
    aiDescription: '차트 관리. 저장된 쿼리 기반 시각화 생성/편집.',
    aiExamples: ['차트 목록 보여줘', '차트 편집하러 가줘'],
  },
  {
    type: 'chart_new',
    label: '차트 생성',
    icon: '📈',
    listPath: '/analytics/charts/new',
    aiDescription: '새 차트 생성 페이지.',
    aiExamples: ['새 차트 만들러 가줘'],
  },
  {
    type: 'dashboard',
    label: '분석 대시보드',
    icon: '📊',
    listPath: '/analytics/dashboards',
    detailPath: (id) => `/analytics/dashboards/${id}`,
    aiDescription: '분석 대시보드 관리. 여러 차트를 배치한 대시보드 보기/편집.',
    aiExamples: ['대시보드 목록 보여줘', '운영 대시보드 열어줘'],
  },
  {
    type: 'settings',
    label: '시스템 설정',
    icon: '⚙️',
    listPath: '/admin/settings',
    aiDescription: 'AI 모델, 인증 설정 등 시스템 관리.',
    aiExamples: ['설정 페이지 열어줘', 'AI 설정 바꾸러 가줘'],
  },
  {
    type: 'users',
    label: '사용자 관리',
    icon: '👥',
    listPath: '/admin/users',
    detailPath: (id) => `/admin/users/${id}`,
    aiDescription: '사용자 계정 관리. 역할 할당, 프로필 수정. (관리자 전용)',
    aiExamples: ['사용자 관리 페이지 열어줘'],
  },
  {
    type: 'roles',
    label: '역할 관리',
    icon: '🔐',
    listPath: '/admin/roles',
    detailPath: (id) => `/admin/roles/${id}`,
    aiDescription: '역할 및 권한 관리. (관리자 전용)',
    aiExamples: ['역할 관리 열어줘'],
  },
  {
    type: 'audit_logs',
    label: '감사 로그',
    icon: '📋',
    listPath: '/admin/audit-logs',
    aiDescription: '사용자 행위 감사 로그 조회. (관리자 전용)',
    aiExamples: ['감사 로그 보여줘', '로그 확인하러 가줘'],
  },
  {
    type: 'api_connections',
    label: 'API 연결',
    icon: '🔌',
    listPath: '/admin/api-connections',
    detailPath: (id) => `/admin/api-connections/${id}`,
    aiDescription: '외부 API 인증 정보 관리. API 키, Bearer 토큰 등. (관리자 전용)',
    aiExamples: ['API 연결 관리 열어줘'],
  },
  {
    type: 'profile',
    label: '프로필',
    icon: '👤',
    listPath: '/profile',
    aiDescription: '내 프로필 정보 수정.',
    aiExamples: ['내 프로필 열어줘'],
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
