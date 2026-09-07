import { createJob, createReport, createTemplate } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 목록 탭 E2E 테스트
 * - 목록 렌더, 행 클릭 시 뷰어 이동, 빈 상태 분기, 탭 전환을 검증한다.
 */
const REPORTS = [createReport()];

const JOB = createJob({ id: 3, name: '월간 화재 통계' });

test.describe('리포트 목록', () => {
  test('생성된 리포트가 목록에 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');

    await expect(page.getByRole('heading', { name: '리포트' })).toBeVisible();
    await expect(page.getByText('8월 화재 통계 리포트')).toBeVisible();
    // 제목 아래 요약 한 줄
    await expect(page.getByText('8월 화재 건수는 전월 대비 12% 감소했습니다.')).toBeVisible();
    // 잡 이름은 배지로만 — 링크가 아니어야 한다
    await expect(page.getByRole('link', { name: '월간 화재 통계' })).toHaveCount(0);
  });

  test('행을 클릭하면 리포트 뷰어로 이동한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/3', JOB);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/3/executions/11/html', '<h1>리포트 본문</h1>');

    await page.goto('/ai-insights/reports');
    await page.getByText('8월 화재 통계 리포트').click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/3\/executions\/11\/report/);
  });

  test('잡이 없으면 스마트 작업 생성을 유도하는 빈 상태를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');

    await expect(page.getByText('스마트 작업을 만들면 생성된 리포트가 여기에 쌓입니다.')).toBeVisible();
    // 문구만 바뀌고 CTA가 빠지는 반쪽 구현을 막는다
    await expect(page.getByRole('button', { name: '스마트 작업 만들기' })).toBeVisible();
  });

  test('잡은 있고 리포트만 없으면 다른 빈 상태 문구를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');

    await expect(page.getByText('아직 생성된 리포트가 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '스마트 작업 보기' })).toBeVisible();
  });

  test('행 액션으로 해당 스마트 작업 상세로 이동한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/3', JOB);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');
    // 행 클릭(뷰어)과 구분되는 별도 액션 — stopPropagation 이 동작해야 한다
    await page.getByRole('button', { name: '스마트 작업 보기' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/3$/);
  });

  test('양식 탭으로 전환하면 URL에 tab=templates가 남는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');
    await page.getByRole('tab', { name: '리포트 양식' }).click();

    await expect(page).toHaveURL(/tab=templates/);
  });

  test('더 보기를 눌러도 이미 보이던 행이 사라지지 않는다', async ({ authenticatedPage: page }) => {
    // limit이 쿼리 키에 포함되므로 "더 보기"는 새 캐시 엔트리를 만든다.
    // keepPreviousData가 없으면 그 순간 목록이 스켈레톤으로 교체돼 깜빡인다.
    const PAGE1 = Array.from({ length: 20 }, (_, i) =>
      createReport({ executionId: 100 + i, title: `리포트 ${i + 1}`, summary: null }),
    );
    const PAGE2 = [
      ...PAGE1,
      createReport({ executionId: 200, title: '리포트 21', summary: null }),
    ];

    // limit=40 요청은 의도적으로 지연시켜, 응답 전에도 기존 행이 남아 있는지 확인한다
    await page.route((url) => url.pathname === '/api/v1/proactive/reports', async (route) => {
      const limit = new URL(route.request().url()).searchParams.get('limit');
      if (limit === '40') {
        await new Promise((resolve) => setTimeout(resolve, 700));
        return route.fulfill({ status: 200, body: JSON.stringify(PAGE2) });
      }
      return route.fulfill({ status: 200, body: JSON.stringify(PAGE1) });
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');
    await expect(page.getByText('리포트 1', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '더 보기' }).click();

    // 로딩 중(응답 700ms 지연)에도 기존 행이 그대로 있어야 한다.
    // timeout을 짧게 잡아야 의미가 있다 — 기본 5초면 재시도로 응답을 기다려버려
    // 목록이 잠깐 사라지는 회귀를 놓친다.
    await expect(page.getByText('리포트 1', { exact: true })).toBeVisible({ timeout: 300 });
    // 응답이 도착하면 추가 행이 붙는다
    await expect(page.getByText('리포트 21', { exact: true })).toBeVisible();
  });

  test('양식 상세 편집 중에도 사이드바 리포트 메뉴가 활성으로 남는다', async ({
    authenticatedPage: page,
  }) => {
    // 양식 상세는 구 경로(/ai-insights/templates/:id)를 그대로 쓰므로,
    // 메뉴 경로가 /ai-insights/reports 로 바뀐 뒤에도 별칭으로 활성 표시가 유지돼야 한다
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/templates/5',
      createTemplate({ id: 5, name: '월간 양식' }),
    );
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/5');

    // 활성 항목은 aria-current="page" 로 표시된다
    await expect(page.getByRole('link', { name: '리포트', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  test('설명/스타일이 아주 길어도 헤더가 line-clamp로 제한되고 넘치지 않는다 (#513)', async ({
    authenticatedPage: page,
  }) => {
    // 회귀 방지: description/style에 오버플로우 처리(line-clamp)가 없으면
    // 헤더가 무한정 늘어나거나(설명) 줄바꿈 없이 잘려 사라진다(스타일)
    const LONG_TEXT = '설명'.repeat(200);
    const LONG_STYLE = 'A'.repeat(300);
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/templates/7',
      createTemplate({ id: 7, name: '긴 텍스트 양식', description: LONG_TEXT, style: LONG_STYLE }),
    );
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/7');

    const description = page.getByText(LONG_TEXT);
    const style = page.getByText(LONG_STYLE);
    await expect(description).toBeVisible();
    await expect(style).toBeVisible();

    // line-clamp-2가 적용되면 실제 렌더 높이가 2줄 분량으로 제한된다 (문자열 전체 높이보다 훨씬 작음)
    const descriptionBox = await description.boundingBox();
    const styleBox = await style.boundingBox();
    expect(descriptionBox?.height ?? 0).toBeLessThan(60);
    expect(styleBox?.height ?? 0).toBeLessThan(60);

    // title 속성으로 전체 텍스트를 확인할 수 있어야 한다
    await expect(description).toHaveAttribute('title', LONG_TEXT);
    await expect(style).toHaveAttribute('title', LONG_STYLE);
  });
});
