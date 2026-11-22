/**
 * 무엇: 페이지 제목을 손으로 쓴 h1 로 만드는 것을 막는 게이트 (WD-910).
 * 왜: 표준 클래스 문자열을 찾는 것만으로는 처음부터 다른 크기로 쓴 제목(WD-895 의 20px)을 놓친다.
 *     그래서 h1 을 그리는 자리 자체(RAW_H1)를 세어, 일부러 표준과 다르게 둔 자리(RAW_H1_ALLOWED)와 다르면 실패시킨다.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** h1 을 직접 써도 되는 자리(파일 → 개수) — 표준과 일부러 다른 제목만 둔다 */
const RAW_H1_ALLOWED: Record<string, number> = {
  'components/PageHeading.tsx': 2, // 태그·props 타입
  'components/TruncatedTitle.tsx': 1, // as prop 타입
  // 빈 상태·404 안내
  'components/ResourceNotFound.tsx': 1,
  'pages/NotFoundPage.tsx': 1,
  // 인증 화면(24px)
  'pages/LoginPage.tsx': 1,
  'pages/ChangePasswordPage.tsx': 1,
  'pages/SignupPage.tsx': 2,
  'pages/SelectTenantPage.tsx': 1,
  // 편집기·실행 상세·리포트 뷰어 도구 막대, sr-only
  'pages/analytics/ChartBuilderPage.tsx': 2,
  'pages/analytics/DashboardEditorPage.tsx': 1,
  'pages/analytics/QueryEditorPage.tsx': 2, // 저장된 쿼리(TruncatedTitle as="h1")·새 쿼리
  'pages/ai-insights/ExecutionDetailPage.tsx': 1,
  'pages/ai-insights/ReportViewerPage.tsx': 1,
  'pages/pipeline/PipelineEditorPage.tsx': 1,
};

/**
 * h1 이 될 수 있는 자리 — `<h1` 태그, 따옴표로 쓴 태그 이름 h1, aria-level 1.
 * 태그 이름은 문법(as prop·기본값·createElement·타입)을 가리지 않고 센다 — 문법마다 식을 두면 새 꼴이 빠져나간다.
 */
const RAW_H1 = /<h1[\s/>]|(["'`])h1\1|aria-level=(?:\{\s*1\s*\}|["']1["'])/g;

const countH1 = (source: string) => source.match(RAW_H1)?.length ?? 0;

describe('PageHeading 게이트', () => {
  it.each([
    ['<h1>제목</h1>', 1],
    ['<h1\n  className="text-lg">', 1],
    ['<TruncatedTitle as="h1" text={name} />', 1],
    ["<TruncatedTitle as={'h1'} text={name} />", 1],
    ['<h1/>', 1],
    ["<TruncatedTitle as={isTop({ depth }) ? 'h1' : 'span'} text={name} />", 1],
    ["function Title({ as: Tag = 'h1' }) {}", 1],
    ['<CardTitle role="heading" aria-level={1}>', 1],
    ['<CardTitle role="heading" aria-level="1">', 1],
    ['<CardTitle role="heading" aria-level={2}>', 0],
    ['<TruncatedTitle as="span" text={name} />', 0],
    ['<p>h1 제목 안내</p>', 0],
  ])('h1 자리 세기: %j → %i', (source, count) => {
    expect(countH1(source)).toBe(count);
  });

  it('표준과 일부러 다른 자리 말고는 h1 을 직접 쓰지 않는다', () => {
    const found: Record<string, number> = {};
    for (const entry of readdirSync(SRC_DIR, { recursive: true, encoding: 'utf8' })) {
      if (!/\.tsx?$/.test(entry) || /\.test\.tsx?$/.test(entry)) continue;
      const count = countH1(readFileSync(join(SRC_DIR, entry), 'utf8'));
      if (count) found[entry.replace(/\\/g, '/')] = count;
    }
    expect(found, '새 페이지 제목은 <h1>·as="h1" 대신 PageHeading 을 쓴다').toEqual(RAW_H1_ALLOWED);
  });
});
