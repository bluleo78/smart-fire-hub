import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { composeDetailLabel, composePageTitle, formatPageTitle, NOT_FOUND_TITLE, resolvePageLabel } from './page-title';

/**
 * 라우트 → 문서 제목 변환 단위 테스트 (WD-580, WCAG 2.4.2 Page Titled).
 * 화면마다 탭 제목이 「<화면 이름> · <브랜드명>」으로 달라지는지, 홈은 브랜드명만 쓰는지,
 * 상세·편집 화면은 섹션 이름을 쓰는지, 모르는 경로는 404 제목이 되는지 확인한다.
 */
describe('resolvePageLabel', () => {
  it('홈은 화면 이름이 없다(브랜드명만 쓴다)', () => {
    expect(resolvePageLabel('/')).toBeNull();
  });

  it.each([
    ['/data/datasets', '데이터셋'],
    ['/data/datasets/new', '데이터셋'],
    ['/data/datasets/42', '데이터셋'],
    ['/data/categories', '카테고리'],
    ['/pipelines', '파이프라인'],
    ['/pipelines/7', '파이프라인'],
    ['/pipelines/7/executions/3', '파이프라인'],
    ['/analytics/queries', '쿼리'],
    ['/analytics/queries/new', '쿼리'],
    ['/analytics/charts/5', '차트'],
    ['/analytics/dashboards/5', '대시보드'],
    ['/ai-insights/jobs/9', '스마트 작업'],
    ['/ai-insights/jobs/9/executions/2', '스마트 작업'],
    ['/ai-insights/jobs/9/executions/2/report', '리포트'],
    ['/ai-insights/reports', '리포트'],
    ['/ai-insights/templates/3', '리포트'],
    ['/knowledge-graph/explore', '그래프 탐색'],
    ['/knowledge-graph/model', '지식 모델'],
    ['/knowledge-graph/review', 'AI 검수'],
    // 'model' 이 아닌 view 값은 OntologyPage 가 탐색 뷰를 그린다
    ['/knowledge-graph/anything', '그래프 탐색'],
    ['/admin/users', '사용자 관리'],
    ['/admin/users/1', '사용자 관리'],
    ['/admin/roles/2', '역할 관리'],
    ['/admin/audit-logs', '감사 로그'],
    ['/admin/settings', '설정'],
    ['/admin/api-connections/1', 'API 연결'],
    ['/profile', '내 프로필'],
    ['/settings/channels', '알림 채널 설정'],
    ['/login', '로그인'],
    ['/signup', '회원가입'],
    ['/change-password', '비밀번호 변경'],
  ])('%s → %s', (path, label) => {
    expect(resolvePageLabel(path)).toBe(label);
  });

  it('끝 슬래시가 붙어도 같은 화면으로 본다', () => {
    expect(resolvePageLabel('/data/datasets/')).toBe('데이터셋');
  });

  it('등록되지 않은 경로는 404 제목이다', () => {
    expect(resolvePageLabel('/this-page-does-not-exist')).toBe(NOT_FOUND_TITLE);
    // 알려진 섹션 아래라도 라우트에 없는 깊이는 404 다(App.tsx catch-all 과 같은 판정)
    expect(resolvePageLabel('/data/datasets/1/unknown')).toBe(NOT_FOUND_TITLE);
  });
});

describe('formatPageTitle', () => {
  it('홈은 브랜드명만', () => {
    expect(formatPageTitle('/', 'Acme Data')).toBe('Acme Data');
  });

  it('그 외 화면은 「화면 이름 · 브랜드명」', () => {
    expect(formatPageTitle('/data/datasets', 'Acme Data')).toBe('데이터셋 · Acme Data');
    expect(formatPageTitle('/nope', 'Smart Fire Hub')).toBe('페이지를 찾을 수 없음 · Smart Fire Hub');
  });
});

describe('composePageTitle', () => {
  it('화면 이름이 null 이면 브랜드명만, 있으면 「화면 이름 · 브랜드명」', () => {
    expect(composePageTitle(null, 'Acme Data')).toBe('Acme Data');
    expect(composePageTitle('워크스페이스 선택', 'Acme Data')).toBe('워크스페이스 선택 · Acme Data');
  });
});

/**
 * 상세 화면 제목의 화면 이름 부분 조립 (WD-534) — 「리소스 이름 · 섹션 이름」, 이름이 없으면 섹션 이름만.
 */
describe('composeDetailLabel', () => {
  it('이름이 있으면 「리소스 이름 · 섹션 이름」', () => {
    expect(composeDetailLabel('화재 출동 기록', '데이터셋')).toBe('화재 출동 기록 · 데이터셋');
    expect(composePageTitle(composeDetailLabel('화재 출동 기록', '데이터셋'), 'Smart Fire Hub')).toBe(
      '화재 출동 기록 · 데이터셋 · Smart Fire Hub',
    );
  });

  it('이름 앞뒤 공백은 지운다', () => {
    expect(composeDetailLabel('  일일 집계  ', '파이프라인')).toBe('일일 집계 · 파이프라인');
  });

  it.each([[undefined], [null], [''], ['   ']])('이름이 %j 이면 섹션 이름만', (name) => {
    expect(composeDetailLabel(name, '데이터셋')).toBe('데이터셋');
  });

  it('긴 이름도 자르지 않는다(탭에서는 브라우저가 자른다)', () => {
    const longName = '가'.repeat(300);
    expect(composeDetailLabel(longName, '쿼리')).toBe(`${longName} · 쿼리`);
  });
});

/**
 * 동기화 가드 — App.tsx 에 라우트를 추가하고 제목 표(page-title.ts)에 빠뜨리면 그 화면의 탭 제목이
 * 조용히 「페이지를 찾을 수 없음」이 된다. App.tsx 원문에서 모든 <Route path="..."> 를 뽑아 404 아닌 제목으로 풀리는지 검사해
 * 누락을 CI 에서 잡는다. :param 은 아무 값으로 채워 실제 URL 처럼 만든다.
 */
describe('App.tsx 라우트 ↔ 제목 표 동기화', () => {
  const appSource = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'App.tsx'), 'utf8');
  const routePaths = [...appSource.matchAll(/<Route\s+[^>]*?path="([^"]+)"/g)]
    .map((m) => m[1])
    .filter((path) => path !== '*');

  it('App.tsx 에서 라우트 경로를 뽑아낸다(정규식이 깨지면 가드가 공허해지므로 개수를 확인)', () => {
    expect(routePaths.length).toBeGreaterThan(40);
    expect(routePaths).toContain('/data/datasets/:id');
  });

  it('모든 라우트가 404 아닌 제목으로 풀린다', () => {
    const missing = routePaths.filter(
      (path) => resolvePageLabel(path.replace(/:[A-Za-z]+/g, '1')) === NOT_FOUND_TITLE,
    );
    expect(missing).toEqual([]);
  });
});
