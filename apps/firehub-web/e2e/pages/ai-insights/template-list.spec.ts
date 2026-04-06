import { createTemplate } from '../../factories/ai-insight.factory';
import { setupTemplateListMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 양식(Template) 목록 페이지 E2E 테스트
 * - 기본/커스텀 템플릿 구분, 빈 상태, 새 템플릿 버튼을 검증한다.
 */
test.describe('리포트 양식 목록 페이지', () => {
  test('템플릿 목록이 기본/커스텀으로 구분되어 렌더링된다', async ({ authenticatedPage: page }) => {
    // 기본(builtin: true) + 커스텀(builtin: false) 템플릿 각 1개씩 모킹
    // createTemplates() → [{id:1, name:'일일 현황 리포트', builtin:true}, {id:2, name:'주간 통계 리포트', builtin:false}]
    await setupTemplateListMocks(page);

    await page.goto('/ai-insights/templates');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '리포트 양식' })).toBeVisible();

    // 기본 템플릿 섹션 확인
    await expect(page.getByText('기본 템플릿')).toBeVisible();

    // 커스텀 템플릿 섹션 확인
    await expect(page.getByText('커스텀 템플릿')).toBeVisible();

    // 기본 템플릿에 "기본" 뱃지 확인 (createTemplates()의 첫 번째 항목: builtin: true)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '기본' })).toBeVisible();

    // createTemplates() 팩토리 실제 이름 확인 — 기본 템플릿 이름
    await expect(page.getByText('일일 현황 리포트')).toBeVisible();

    // createTemplates() 팩토리 실제 이름 확인 — 커스텀 템플릿 이름
    await expect(page.getByText('주간 통계 리포트')).toBeVisible();
  });

  test('커스텀 템플릿이 없을 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 기본 템플릿만 있고 커스텀 없는 상태로 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [
      createTemplate({ id: 1, name: '일일 현황 리포트', builtin: true }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates');

    // 빈 상태 메시지 확인
    await expect(page.getByText('커스텀 템플릿 없음')).toBeVisible();

    // "첫 템플릿 만들기" 버튼 확인
    await expect(page.getByRole('button', { name: '첫 템플릿 만들기' })).toBeVisible();
  });

  test('템플릿 추가 버튼 클릭 시 새 템플릿 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupTemplateListMocks(page);
    // 새 템플릿 페이지에서 필요한 API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/templates');

    // "템플릿 추가" 버튼 클릭
    await page.getByRole('button', { name: '템플릿 추가' }).click();

    // 새 템플릿 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/templates/new');
  });

  test('커스텀 템플릿 카드 클릭 시 템플릿 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    // 커스텀 템플릿 포함 목록 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [
      createTemplate({ id: 2, name: '주간 통계 리포트', builtin: false }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    // 상세 페이지 API 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/templates/2',
      createTemplate({ id: 2, name: '주간 통계 리포트', builtin: false }),
    );

    await page.goto('/ai-insights/templates');

    // 커스텀 템플릿 카드 클릭
    await page.getByText('주간 통계 리포트').click();

    // 상세 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/templates/2');
  });
});
