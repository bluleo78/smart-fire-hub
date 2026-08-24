// SEEDED_18 은 e2e/factories/platform.factory.ts 로 옮겼다(리뷰 L2) — 스펙 파일에서 export 하면
// 다른 스펙이 import 하는 순간 Playwright 가 이 파일도 테스트 파일로 등록해 settings.spec.ts 의
// 테스트가 함께 중복 실행된다.
import { SEEDED_18 } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

test.describe('플랫폼 설정 — 카탈로그와 상태', () => {
  test('탭 3개와 안내 배너가 나온다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', SEEDED_18);
    await page.goto('/settings');

    await expect(page.getByRole('heading', { name: '플랫폼 설정' })).toBeVisible();
    await expect(
      page.getByText(
        '여기서 저장한 값은 전 테넌트의 기본값입니다. 일부 항목은 각 워크스페이스가 자기 값으로 재정의할 수 있습니다.',
      ),
    ).toBeVisible();
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '이메일(SMTP)' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '임베딩' })).toBeVisible();
    await expect(page.getByRole('tab')).toHaveCount(3);
  });

  test('배지 두 종류가 항상 붙는다 — 19필드 전부(개수로 단언, 리뷰 L1)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/platform/settings', SEEDED_18);
    await page.goto('/settings');

    // .first() 두 번은 "존재한다"만 증명하고 "전부에 붙는다"는 증명하지 못한다 — 어느 필드가
    // 배지를 빼먹어도 초록이다. 탭마다 (테넌트 재정의 가능 배지 수 + 전역 고정 배지 수)가
    // 그 탭의 필드 수와 정확히 같은지 개수로 단언한다. AI 9 / SMTP 6 / 임베딩 4 = 19.
    const tabFieldCounts: Record<string, number> = { 'AI 에이전트': 9, '이메일(SMTP)': 6, '임베딩': 4 };
    let totalBadges = 0;
    for (const [tabName, fieldCount] of Object.entries(tabFieldCounts)) {
      await page.getByRole('tab', { name: tabName }).click();
      const overridable = await page.getByText('테넌트 재정의 가능').count();
      const fixed = await page.getByText('전역 고정').count();
      expect(overridable + fixed).toBe(fieldCount);
      totalBadges += overridable + fixed;
    }
    expect(totalBadges).toBe(19);

    // 안내 문구도 여전히 확인한다 — 두 종류가 각기 다른 문구를 낸다는 대조군.
    await page.getByRole('tab', { name: 'AI 에이전트' }).click();
    await expect(
      page
        .getByText(
          '워크스페이스가 자기 값으로 재정의할 수 있습니다. 재정의하지 않은 워크스페이스에만 이 값이 적용됩니다.',
        )
        .first(),
    ).toBeVisible();
    await expect(page.getByText('모든 워크스페이스에 이 값이 적용됩니다.').first()).toBeVisible();
  });

  test('19번째 키는 내장 기본값 배지와 값 50000 으로 그린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', SEEDED_18);
    await page.goto('/settings');

    await expect(page.getByLabel('세션 최대 토큰')).toHaveValue('50000');
    await expect(page.getByText('내장 기본값')).toBeVisible();
    await expect(
      page.getByText(
        '아직 저장된 값이 없어 코드 기본값이 적용되고 있습니다. 저장하면 이 값이 플랫폼 기본값이 됩니다.',
      ),
    ).toBeVisible();
  });

  test('필드 설명은 서버 description 을 그대로 쓴다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', SEEDED_18);
    await page.goto('/settings');
    await expect(page.getByText('AI 에이전트 사용 모델')).toBeVisible();
  });

  test('STARTTLS 는 Switch 로 그리고 값이 true 면 켜져 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', SEEDED_18);
    await page.goto('/settings');

    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();
    await expect(page.getByRole('switch')).toBeChecked();
  });

  test('쓰기 권한이 없으면 전 필드가 disabled 이고 안내 배너가 뜬다', async ({ page }) => {
    await loginAs(page, ['platform:settings:read', 'platform:tenant:read']);
    await mockApi(page, 'GET', '/api/platform/settings', SEEDED_18);
    await page.goto('/settings');

    await expect(
      page.getByText('조회 권한만 있습니다. 값을 변경하려면 platform:settings:write 권한이 필요합니다.'),
    ).toBeVisible();
    await expect(page.getByLabel('최대 턴 수')).toBeDisabled();
  });

  test('조회 실패는 다시 시도 버튼을 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', {}, { status: 500 });
    await page.goto('/settings');

    await expect(page.getByText('설정을 불러오는데 실패했습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
  });

  test('403 이면 권한 배너로 본문을 교체한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', {}, { status: 403 });
    await page.goto('/settings');
    // role 까지 고정한다 — InlineBanner 는 role="status" + aria-live="polite" 다.
    // 문구만 잡으면 나중에 누가 평범한 <p> 로 바꿔도 테스트가 통과해 버린다.
    await expect(
      page.getByRole('status').filter({ hasText: '이 작업을 수행할 권한이 없습니다.' }),
    ).toBeVisible();
  });

  test('마지막 변경 시각을 탭 하단에 한 줄로만 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', SEEDED_18);
    await page.goto('/settings');
    await expect(page.getByText('마지막 변경: 2026-08-19 14:02')).toBeVisible();
  });
});
