/**
 * 스마트 작업 폼 — 필수 칸을 보조기술에 알린다 E2E (WD-928)
 *
 * 결함: 이름·프롬프트 칸에 별표(*)만 있고 스크린리더는 "필수"를 읽지 않았다.
 * 이제 칸에 aria-required="true", 라벨의 별표는 aria-hidden 이라 접근 가능한 이름은 별표 없이 읽힌다.
 */

import type { Page } from '@playwright/test';

import { mockProactivePermissions, setupJobDetailMocks, setupNewJobMocks } from '../../fixtures/ai-insight.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/** 필수 칸 공통 검증 — aria-required(native required 아님), 별표 없는 이름, 보이는 라벨 모양, 라벨 클릭 포커스 */
async function expectRequiredField(page: Page, id: string, name: string) {
  const field = page.locator(`#${id}`);
  await expect(field).toHaveAttribute('aria-required', 'true');
  await expect(field).not.toHaveAttribute('required');
  await expect(field).toHaveAccessibleName(name);
  const label = page.locator(`label[for="${id}"]`);
  // 별표는 같은 줄·보통 공백 그대로 보이고 보조기술에서만 숨는다
  // 문자열 비교는 공백을 정규화해 flex 간격 회귀(innerText 의 \n)를 놓치므로 정규식을 쓴다
  await expect(label).toHaveText(new RegExp(`^${name} \\*$`), { useInnerText: true });
  await expect(label.locator('[aria-hidden="true"]')).toHaveText('*');
  await label.click();
  await expect(field).toBeFocused();
}

test.describe('스마트 작업 폼 — 필수 칸 알림 (WD-928)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('새 작업: 작업 이름·분석 프롬프트', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');
    await expect(page.locator('#job-name')).toHaveAttribute('aria-describedby', /job-name-hint/);
    await expect(page.locator('#job-prompt')).toHaveAttribute('aria-describedby', /job-prompt-hint/);
    await expectRequiredField(page, 'job-name', '작업 이름');
    await expectRequiredField(page, 'job-prompt', '분석 프롬프트');
  });

  test('새 작업 목표 기반: 작업 이름', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');
    await page.getByRole('radio', { name: '목표 기반' }).click();
    // 모드가 실제로 바뀐 뒤에 본다 — 안 바뀌어도 기본 화면의 칸으로 통과하지 않게
    await expect(page.locator('#business-question')).toBeVisible();
    await expectRequiredField(page, 'job-name', '작업 이름');
  });

  test('새 작업 오류: 작업 이름', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');
    await page.locator('#job-name').fill('   ');
    await expect(page.getByText('작업 이름을 입력하세요')).toBeVisible();
    await expectRequiredField(page, 'job-name', '작업 이름');
  });

  test('모드 전환: 분석 프롬프트', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');
    await page.getByRole('radio', { name: '목표 기반' }).click();
    // 칸이 사라졌다가 다시 그려지는 경우를 본다
    await expect(page.locator('#job-prompt')).toHaveCount(0);
    await page.getByRole('radio', { name: '직접 설정' }).click();
    await expectRequiredField(page, 'job-prompt', '분석 프롬프트');
  });

  test('편집: 작업 이름·분석 프롬프트', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('#job-name')).toHaveValue('매일 현황 리포트');
    await expectRequiredField(page, 'job-name', '작업 이름');
    await expectRequiredField(page, 'job-prompt', '분석 프롬프트');
  });
});
