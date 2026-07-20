import { configDefaults, defineConfig } from 'vitest/config';

// 통합 테스트(*.integration.test.ts)는 Neo4j 실행이 필요하고
// beforeAll에서 전체 노드를 삭제해 병렬 실행 시 서로 충돌한다.
// 기본 `pnpm test`에서는 제외하고, `pnpm test:integration`(VITEST_INTEGRATION=1)에서만 포함한다.
const isIntegration = process.env.VITEST_INTEGRATION === '1';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    include: ['src/**/*.test.ts'],
    exclude: isIntegration
      ? configDefaults.exclude
      : [...configDefaults.exclude, '**/*.integration.test.ts'],
    // v8 커버리지 설정 — 로컬 리포트 전용 (CI 연동 없음)
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      reportsDirectory: './coverage',
      exclude: [
        'dist/**',
        'node_modules/**',
        '**/*.test.ts',
        '**/*.d.ts',
        'vitest.config.ts',
      ],
    },
  },
});
