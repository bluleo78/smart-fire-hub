/**
 * URL 패턴을 파싱하여 현재 화면 정보를 한국어 텍스트로 반환한다.
 * AI 에이전트에게 매 메시지마다 전달되어 사용자의 작업 맥락을 제공한다.
 */

const SCREEN_CONTEXT_MAP: { pattern: RegExp; build: (match: RegExpMatchArray) => string }[] = [
  { pattern: /^\/$/, build: () => '홈 대시보드' },
  { pattern: /^\/data\/categories$/, build: () => '카테고리 목록' },
  { pattern: /^\/data\/datasets$/, build: () => '데이터셋 목록' },
  { pattern: /^\/data\/datasets\/new$/, build: () => '새 데이터셋 생성 페이지' },
  { pattern: /^\/data\/datasets\/(\d+)$/, build: (m) => `데이터셋 상세 (ID: ${m[1]})` },
  { pattern: /^\/pipelines$/, build: () => '파이프라인 목록' },
  { pattern: /^\/pipelines\/new$/, build: () => '새 파이프라인 생성' },
  { pattern: /^\/pipelines\/(\d+)\/executions\/(\d+)$/, build: (m) => `파이프라인 실행 상세 (파이프라인 ID: ${m[1]}, 실행 ID: ${m[2]})` },
  { pattern: /^\/pipelines\/(\d+)$/, build: (m) => `파이프라인 편집 (ID: ${m[1]})` },
  { pattern: /^\/analytics\/queries$/, build: () => '쿼리 목록' },
  { pattern: /^\/analytics\/queries\/new$/, build: () => '새 쿼리 작성' },
  { pattern: /^\/analytics\/queries\/(\d+)$/, build: (m) => `쿼리 편집 (ID: ${m[1]})` },
  { pattern: /^\/analytics\/charts$/, build: () => '차트 목록' },
  { pattern: /^\/analytics\/charts\/new$/, build: () => '새 차트 생성' },
  { pattern: /^\/analytics\/charts\/(\d+)$/, build: (m) => `차트 편집 (ID: ${m[1]})` },
  { pattern: /^\/analytics\/dashboards$/, build: () => '대시보드 목록' },
  { pattern: /^\/analytics\/dashboards\/(\d+)$/, build: (m) => `대시보드 편집 (ID: ${m[1]})` },
  { pattern: /^\/ai-insights\/jobs$/, build: () => 'AI 인사이트 작업 목록' },
  { pattern: /^\/ai-insights\/templates$/, build: () => 'AI 인사이트 템플릿 목록' },
  { pattern: /^\/admin\/settings$/, build: () => '시스템 설정' },
  { pattern: /^\/admin\/users\/(\d+)$/, build: (m) => `사용자 상세 (ID: ${m[1]})` },
  { pattern: /^\/admin\/roles\/(\d+)$/, build: (m) => `역할 상세 (ID: ${m[1]})` },
  { pattern: /^\/admin\//, build: () => '관리자 페이지' },
  { pattern: /^\/profile$/, build: () => '프로필 페이지' },
];

export function buildScreenContext(pathname: string): string | undefined {
  for (const { pattern, build } of SCREEN_CONTEXT_MAP) {
    const match = pathname.match(pattern);
    if (match) {
      return `[현재 화면] ${build(match)}`;
    }
  }
  return undefined;
}
