/**
 * 스마트 작업 폼 — 오류·안내 문구가 칸에 aria 로 이어진다 E2E (WD-963)
 *
 * 결함: 작업 이름·프롬프트·실행 주기의 빨간 오류 문구와 회색 안내가 칸과 aria 로 이어지지 않아 스크린리더가
 * 칸에 초점이 가도 이유를 읽지 못했고, 실행 주기 「직접 입력」 칸은 이름도 필수 표시도 없었다.
 */

import type { Page } from '@playwright/test';

import type { ProactiveJob } from '@/api/proactive';

import { createJob } from '../../factories/ai-insight.factory';
import {
  captureJobWrites,
  mockProactivePermissions,
  setupJobDetailMocks,
  setupNewJobMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { JOB_CRON_PREVIEW_PATH, mockCronPreview } from '../../fixtures/cron-preview.fixture';

const CUSTOM_NAME = '실행 주기 직접 입력 (Cron 표현식)';
const customInput = (page: Page) => page.getByRole('textbox', { name: CUSTOM_NAME });
const presetSelect = (page: Page) => page.locator('#job-cron-preset');

/** 실행 주기에서 「직접 입력」을 고른다. */
async function chooseCustom(page: Page) {
  await presetSelect(page).click();
  await page.getByRole('option', { name: '직접 입력' }).click();
}

/** 기존 작업 상세를 지정한 cron 으로 띄운다(나중 등록 라우트가 이긴다). */
async function openJob(page: Page, cronExpression: string, overrides: Partial<ProactiveJob> = {}) {
  await setupJobDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, cronExpression, ...overrides }));
  await page.goto('/ai-insights/jobs/1');
  await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
}

/** 새 작업 폼을 열고 실행 주기에서 「직접 입력」을 고른다(WD-963 리뷰). 쓰기 캡처 등 라우트는 이보다 먼저 건다. */
async function openNewJobWithCustomCron(page: Page) {
  await setupNewJobMocks(page);
  await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
  await page.goto('/ai-insights/jobs/new');
  await chooseCustom(page);
}

/** 프리셋을 고른 순간 낡은 cron 오류가 그려졌는지 기록하는 창 상태 */
type StaleCronWindow = Window & { __staleCronError?: boolean; __staleCronObserver?: MutationObserver };

test.describe('스마트 작업 폼 — 오류·안내 aria 연결 (WD-963)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('이름에 공백만 치면 칸이 잘못됨과 오류 문구를 읽는다 (WD-963)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await page.goto('/ai-insights/jobs/new');

    const name = page.locator('#job-name');
    // 손대기 전 빈 새 작업 폼은 안내(hint)만 읽는다
    await expect(name).toHaveAttribute('aria-describedby', 'job-name-hint');
    await expect(name).not.toHaveAttribute('aria-invalid', /.*/);
    await name.fill('   ');
    await expect(name).toHaveAttribute('aria-invalid', 'true');
    await expect(name).toHaveAttribute('aria-describedby', 'job-name-error');
    const err = page.locator('#job-name-error');
    await expect(err).toHaveAttribute('role', 'alert');
    await expect(err).toHaveText('작업 이름을 입력하세요');

    await name.fill('주간');
    await expect(name).not.toHaveAttribute('aria-invalid', /.*/);
    // 고친 뒤에는 이름이 비지 않아 안내(job-name-hint)도 보이지 않는다 — 오류 id 가 남지 않고 describedby 자체가 없어야 한다
    await expect(page.locator('#job-name-hint')).toHaveCount(0);
    await expect(page.locator('#job-name-error')).toHaveCount(0);
    await expect(name).not.toHaveAttribute('aria-describedby', /.*/);
  });

  test('프롬프트를 비우면 칸이 잘못됨과 오류 문구를 읽는다 (WD-963)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await page.goto('/ai-insights/jobs/new');

    const prompt = page.locator('#job-prompt');
    await prompt.fill('   ');
    await expect(prompt).toHaveAttribute('aria-invalid', 'true');
    await expect(prompt).toHaveAttribute('aria-describedby', 'job-prompt-error');
    const err = page.locator('#job-prompt-error');
    await expect(err).toHaveAttribute('role', 'alert');
    await expect(err).not.toHaveText('');

    await prompt.fill('어제 결과를 요약하세요');
    await expect(prompt).not.toHaveAttribute('aria-invalid', /.*/);
  });

  test('직접 입력 칸에 이름·필수가 있고 빈 칸 안내를 읽는다 (WD-963)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await page.goto('/ai-insights/jobs/new');
    await chooseCustom(page);

    const input = customInput(page);
    await expect(input).toBeVisible();
    await expect(input).toHaveAttribute('aria-required', 'true');
    await expect(input).toHaveAttribute('id', 'job-cron-custom');
    await expect(input).toHaveAttribute('aria-describedby', 'job-cron-custom-desc job-cron-empty-hint');
    await expect(input).not.toHaveAttribute('aria-invalid', /.*/);
  });

  test('직접 입력이 너무 잦으면 칸이 잘못됨과 이유를 읽는다 (WD-963)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await page.goto('/ai-insights/jobs/new');
    await chooseCustom(page);

    const input = customInput(page);
    await input.fill('* * * * * *');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    await expect(input).toHaveAttribute('aria-describedby', 'job-cron-error job-cron-custom-desc');
    const err = page.locator('#job-cron-error');
    // 글자마다 바뀌는 cron 오류는 끼어들지 않게 polite 로 알린다(/code-review)
    await expect(err).toHaveAttribute('aria-live', 'polite');
    await expect(err).not.toHaveAttribute('role', 'alert');
    await expect(err).toContainText('실행 주기는 1분보다 짧게');
  });

  test('직접 입력 칸을 막 열면 비어 있는 cron 오류 live 영역이 미리 있고, 너무 잦으면 같은 영역에 글이 생긴다 (WD-963 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    await openNewJobWithCustomCron(page);

    // 문구와 함께 처음 그려진 live 영역은 읽히지 않을 수 있다 — 칸이 보이는 동안 빈 영역이 미리 있어야 한다
    const err = page.locator('#job-cron-error');
    await expect(err).toHaveCount(1);
    await expect(err).toHaveAttribute('aria-live', 'polite');
    await expect(err).toHaveText('');
    // 처음 있던 영역에 표식을 달아, 글이 생긴 뒤에도 같은 요소인지 본다
    await err.evaluate((el) => {
      (el as HTMLElement).dataset.firstRender = '1';
    });
    // 글이 없으면 describedby 에 잇지 않는다
    await expect(customInput(page)).toHaveAttribute('aria-describedby', 'job-cron-custom-desc job-cron-empty-hint');

    await customInput(page).fill('* * * * * *');
    // 새로 그린 요소가 아니라 처음 있던 같은 영역의 글이 바뀌어야 바뀜으로 읽힌다
    await expect(page.locator('#job-cron-error[data-first-render="1"]')).toContainText('실행 주기는 1분보다 짧게');
  });

  test('너무 잦은 직접 입력 뒤 프리셋을 고르면 cron 오류가 남지 않는다 (WD-963 리뷰)', async ({ authenticatedPage: page }) => {
    await openNewJobWithCustomCron(page);
    await customInput(page).fill('* * * * * *');
    await expect(page.locator('#job-cron-error')).toContainText('실행 주기는 1분보다 짧게');

    // 프리셋을 고른 직후 검증이 끝나기 전 한순간이라도, 이을 칸 없이 낡은 오류가 그려지는지 DOM 바뀜마다 기록한다
    // (기다리며 다시 보는 단언은 그 틈을 지나쳐 버린다)
    await page.evaluate(() => {
      const w = window as StaleCronWindow;
      w.__staleCronError = false;
      w.__staleCronObserver = new MutationObserver(() => {
        const e = document.getElementById('job-cron-error');
        if (e?.textContent && !document.getElementById('job-cron-custom')) w.__staleCronError = true;
      });
      w.__staleCronObserver.observe(document.body, { subtree: true, childList: true, characterData: true });
    });
    await presetSelect(page).click();
    await page.getByRole('option', { name: '매일 오전 9시' }).click();
    await expect(presetSelect(page)).toHaveText('매일 오전 9시');
    const stale = await page.evaluate(() => {
      const w = window as StaleCronWindow;
      w.__staleCronObserver?.disconnect();
      return w.__staleCronError;
    });
    expect(stale).toBe(false);
    await expect(customInput(page)).toHaveCount(0);
    await expect(page.locator('#job-cron-error')).toHaveCount(0);
    await expect(page.getByText(/실행 주기는 1분보다 짧게/)).toHaveCount(0);
  });

  test('너무 잦은 직접 입력을 고친 뒤 만들면 고친 cron 이 서버로 간다 (WD-963 리뷰)', async ({ authenticatedPage: page }) => {
    const writes = await captureJobWrites(page);
    await openNewJobWithCustomCron(page);
    await page.locator('#job-name').fill('주간 시험');
    await page.locator('#job-prompt').fill('어제 결과를 요약하세요');

    const input = customInput(page);
    await input.fill('* * * * * *');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeDisabled();

    await input.fill('0 9 * * 1,3');
    await expect(input).not.toHaveAttribute('aria-invalid', /.*/);
    await expect(page.locator('#job-cron-error')).toHaveText('');
    await page.getByRole('button', { name: '만들기', exact: true }).click();
    await expect.poll(() => writes.posts.length).toBe(1);
    expect(writes.posts[0]).toMatchObject({ name: '주간 시험', prompt: '어제 결과를 요약하세요', cronExpression: '0 9 * * 1,3' });
  });

  test('주기가 없는 옛 작업은 직접 입력 칸이 오류를 읽는다 (WD-963)', async ({ authenticatedPage: page }) => {
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    // 안 건드린 주기 없는 수정은 필수가 아니다(WD-995) — 이상 탐지 작업을 스케줄로 바꿔야 주기가 필요해진다
    await openJob(page, '', { triggerType: 'ANOMALY' });
    await page.getByRole('button', { name: '편집' }).click();
    await page.locator('#job-trigger-type').click();
    await page.getByRole('option', { name: '스케줄 (정기 실행)' }).click();

    // 저장된 주기가 비면 cronPreset 이 「직접 입력」이라 직접 입력 칸이 그려지고, 그 칸이 오류를 읽어야 한다
    const input = customInput(page);
    await expect(input).toBeVisible();
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    await expect(input).toHaveAttribute('aria-describedby', /job-cron-error/);
    const err = page.locator('#job-cron-error');
    // 글자마다 바뀌는 cron 오류는 끼어들지 않게 polite 로 알린다(/code-review)
    await expect(err).toHaveAttribute('aria-live', 'polite');
    await expect(err).not.toHaveAttribute('role', 'alert');
    await expect(err).toHaveText('실행 주기를 설정하세요');
  });

  test('편집 진입 때 이미 너무 잦은 주기면 직접 입력 칸이 잘못됨과 이유를 읽는다 (WD-963)', async ({
    authenticatedPage: page,
  }) => {
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await openJob(page, '* * * * * *');
    await page.getByRole('button', { name: '편집' }).click();

    const input = customInput(page);
    await expect(input).toBeVisible();
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    await expect(input).toHaveAttribute('aria-describedby', /^job-cron-error/);
    await expect(page.locator('#job-cron-error')).toContainText('실행 주기는 1분보다 짧게');
  });

  test('모드를 목표 기반 → 직접 설정으로 바꿔도 프롬프트 오류가 바로 읽히지 않고 안내만 읽는다 (WD-963)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await page.goto('/ai-insights/jobs/new');
    await page.locator('#job-name').fill('모드 시험');
    await page.locator('#job-prompt').fill('어제 결과를 요약하세요');

    await page.locator('#mode-goal').click();
    await page.locator('#mode-manual').click();

    const prompt = page.locator('#job-prompt');
    await expect(prompt).toBeVisible();
    await expect(page.locator('#job-prompt-error')).toHaveCount(0);
    await expect(prompt).not.toHaveAttribute('aria-invalid', /.*/);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeDisabled();
    await expect(prompt).toHaveAttribute('aria-describedby', 'job-prompt-hint');
  });
});
