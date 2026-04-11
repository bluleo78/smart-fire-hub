/**
 * monocart-coverage-reports 공용 설정
 * - global setup / 각 테스트 fixture / global teardown 에서 동일한 설정으로 MCR 인스턴스를 생성한다.
 * - 캐시 디렉토리(outputDir/.cache)를 통해 프로세스 간 커버리지 데이터가 merge 된다.
 */
export const coverageOptions = {
  name: 'Firehub Web E2E Coverage',
  outputDir: './coverage/e2e',
  // 생성할 리포트들 — v8 네이티브 HTML + Istanbul HTML + json-summary + 콘솔 요약
  reports: [
    ['v8', { metrics: ['lines', 'statements', 'branches', 'functions'] }],
    ['html', {}],
    ['json-summary', {}],
    ['console-summary', {}],
  ] as [string, Record<string, unknown>][],
  // V8 raw entry URL 기준 필터 — /src/ 경로만 포함하고 node_modules/vendor 제외
  // V8 raw entry URL 기준 필터 — /src/ 경로만 포함하고 node_modules/vite-internal은 제외
  entryFilter: (entry: { url: string }) =>
    entry.url.includes('/src/') &&
    !entry.url.includes('node_modules') &&
    !entry.url.includes('/@vite/') &&
    !entry.url.includes('/@react-refresh'),
  // 소스맵 복원 후 소스 파일 필터
  // Vite dev 서버가 생성하는 source map 은 sourcesRoot 를 생략해 sourcePath 가
  // 파일명(예: 'main.tsx') 또는 상대 경로('components/ui/button.tsx') 만 담고 있다.
  // 따라서 .tsx/.ts 확장자 허용을 기본으로 하고 shadcn 자동 생성물과 테스트 파일만 제외한다.
  sourceFilter: (sourcePath: string) => {
    // JS/TS 소스만 대상 — CSS/폰트/vendor 제외
    if (!/\.(ts|tsx|js|jsx|mjs)$/.test(sourcePath)) return false;
    // shadcn 자동 생성 UI primitive 는 제외 (Vitest 설정과 동일)
    if (sourcePath.includes('components/ui/')) return false;
    // 테스트 파일 및 타입 선언 제외
    if (sourcePath.includes('.test.')) return false;
    if (sourcePath.endsWith('.d.ts')) return false;
    // node_modules 에서 온 것 제외
    if (sourcePath.includes('node_modules')) return false;
    return true;
  },
};
