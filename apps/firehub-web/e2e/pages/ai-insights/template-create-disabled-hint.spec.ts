import { createTemplate } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * WD-812 — 리포트 양식 편집기: 이름 필수 표시(*)와, 새 양식에서 「만들기」가 꺼진 이유 안내.
 * - 이름이 비어 있으면 칸 아래에 안내가 보이고 aria-describedby 로 칸에 이어진다.
 * - 이름을 넣었다 지우면 스키마 오류 문구만 보이고 안내는 겹쳐 그려지지 않는다.
 */
const HINT = '이름을 입력하면 만들 수 있습니다.';

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

async function openNew(page: import('@playwright/test').Page) {
  await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await page.goto('/ai-insights/templates/new');
  await expect(page.locator('#tpl-name')).toBeVisible();
}

test.describe('리포트 양식 이름 안내 (WD-812)', () => {
  test('새 양식: 이름 라벨에 * 가 있고, 비어 있으면 안내가 보이며 이름 칸에 이어진다', async ({
    authenticatedPage: page,
  }) => {
    await openNew(page);
    await expect(page.locator('label[for="tpl-name"]')).toContainText('*');
    await expect(page.getByText(HINT)).toBeVisible();
    await expect(page.locator('#tpl-name')).toHaveAttribute('aria-describedby', /tpl-name-hint/);
    await expect(page.getByRole('button', { name: '만들기' })).toBeDisabled();
  });

  test('새 양식: 이름을 넣으면 안내가 사라지고 만들기가 켜진다', async ({ authenticatedPage: page }) => {
    await openNew(page);
    // 생성 요청(POST /proactive/templates)을 붙잡아 본문을 확인한다
    const posts: { name?: string }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/templates',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        posts.push(route.request().postDataJSON() as { name?: string });
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createTemplate({ id: 99, name: '월간 리포트', builtin: false })),
        });
      },
    );
    await page.locator('#tpl-name').fill('월간 리포트');
    await expect(page.getByText(HINT)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '만들기' })).toBeEnabled();

    // 켜진 「만들기」를 누르면 입력한 이름으로 요청이 정확히 한 번 나간다
    await page.getByRole('button', { name: '만들기' }).click();
    await expect.poll(() => posts.length).toBe(1);
    expect(posts[0].name).toBe('월간 리포트');
  });

  test('새 양식: 이름을 넣었다가 지우면 안내 대신 오류 문구만 보인다', async ({ authenticatedPage: page }) => {
    await openNew(page);
    await page.locator('#tpl-name').fill('월간 리포트');
    await page.locator('#tpl-name').fill('');
    await expect(page.getByText('이름을 입력해주세요.')).toBeVisible();
    await expect(page.getByText(HINT)).toHaveCount(0);
  });

  test('기존 양식 편집 모드: 이름 라벨에 * 는 있고 안내는 없다', async ({ authenticatedPage: page }) => {
    // 빌트인이 아닌 템플릿만 편집할 수 있다
    const template = createTemplate({ id: 1, name: '기본 리포트 템플릿', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/1', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await page.goto('/ai-insights/templates/1');
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('#tpl-name')).toBeVisible();
    await expect(page.locator('label[for="tpl-name"]')).toContainText('*');
    await expect(page.getByText(HINT)).toHaveCount(0);
  });
});
