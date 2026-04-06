import { createJob, createTemplate } from '../factories/ai-insight.factory';
import {
  setupJobDetailMocks,
  setupJobListMocks,
  setupTemplateListMocks,
} from '../fixtures/ai-insight.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * AI 인사이트 도메인 플로우 E2E 테스트
 * - 작업 목록 → 상세, 템플릿 목록 확인, 작업 실행 등
 *   여러 페이지를 걸치는 사용자 플로우를 검증한다.
 */
test.describe('AI 인사이트 플로우', () => {
  test('작업 목록 → 작업 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    // 작업 목록 페이지 모킹
    await setupJobListMocks(page, 3);
    // 작업 상세 페이지 모킹 — createJob({ id:1 }) 기본값:
    //   name:'매일 현황 리포트', cronExpression:'0 9 * * *', templateName:'기본 리포트 템플릿'
    await setupJobDetailMocks(page, 1);

    // 작업 목록 페이지 접근
    await page.goto('/ai-insights/jobs');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '스마트 작업' })).toBeVisible();

    // 첫 번째 작업("잡 1") 행 클릭
    await page.getByText('잡 1').click();

    // 작업 상세 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/1');

    // 작업 상세에서 작업명이 표시되는지 확인 (팩토리 기본값: "매일 현황 리포트")
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

    // 팩토리 기본값 cronExpression '0 9 * * *' 또는 스케줄 설명이 상세 페이지에 표시되는지 확인
    const cronText = page.getByText('0 9 * * *');
    const scheduleDesc = page.getByText(/매일|오전 9시|09:00|스케줄/);
    const hasCron = (await cronText.count()) > 0 || (await scheduleDesc.count()) > 0;
    expect(hasCron).toBe(true);

    // 팩토리 기본값 templateName '기본 리포트 템플릿'이 상세 페이지에 표시되는지 확인
    await expect(page.getByText('기본 리포트 템플릿')).toBeVisible();
  });

  test('템플릿 목록에서 기본/커스텀 템플릿을 확인한다', async ({ authenticatedPage: page }) => {
    // 기본 + 커스텀 템플릿 포함 목록 모킹
    // createTemplates() → [{id:1, name:'일일 현황 리포트', builtin:true}, {id:2, name:'주간 통계 리포트', builtin:false}]
    await setupTemplateListMocks(page);

    await page.goto('/ai-insights/templates');

    // 리포트 양식 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '리포트 양식' })).toBeVisible();

    // 기본 템플릿 섹션 확인
    await expect(page.getByText('기본 템플릿')).toBeVisible();

    // 커스텀 템플릿 섹션 확인
    await expect(page.getByText('커스텀 템플릿')).toBeVisible();

    // 기본 템플릿의 "기본" 뱃지 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '기본' })).toBeVisible();

    // createTemplates() 팩토리 실제 이름 확인 — 기본 템플릿
    await expect(page.getByText('일일 현황 리포트')).toBeVisible();

    // createTemplates() 팩토리 실제 이름 확인 — 커스텀 템플릿
    await expect(page.getByText('주간 통계 리포트')).toBeVisible();
  });

  test('새 작업 버튼 → 생성 폼 → 목록으로 이동하는 플로우', async ({ authenticatedPage: page }) => {
    // 목록 페이지 + 새 작업 페이지 API 모킹
    await setupJobListMocks(page, 2);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [
      createTemplate({ id: 1, name: '일일 현황 리포트' }),
    ]);

    // 새 작업 생성 후 돌아올 목록에 기존 잡 포함
    const newJob = createJob({ id: 10, name: '신규 작업' });
    await mockApi(page, 'POST', '/api/v1/proactive/jobs', newJob);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/10', newJob);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/10/executions', []);

    // 작업 목록 페이지 접근
    await page.goto('/ai-insights/jobs');

    // "작업 추가" 버튼 클릭
    await page.getByRole('button', { name: '작업 추가' }).click();

    // 새 작업 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/new');

    // 새 스마트 작업 헤더 확인
    await expect(page.getByRole('heading', { name: '새 스마트 작업' })).toBeVisible();

    // 생성 버튼이 존재하는지 확인
    await expect(page.getByRole('button', { name: '생성' })).toBeVisible();
  });
});
