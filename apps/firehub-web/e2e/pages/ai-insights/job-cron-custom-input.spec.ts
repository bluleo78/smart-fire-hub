/**
 * 스마트 작업 — 실행 주기 「직접 입력」 칸이 입력 중 사라지지 않는다 E2E (WD-962)
 *
 * 결함: 칸을 보일지를 입력값이 프리셋과 같은지로 매 렌더 다시 정해, `0 9 * * 1,3` 을 치다 `0 9 * * 1`(프리셋)이 되는 순간
 * 칸이 사라지고 초점이 풀렸다. 이제 「직접 입력」을 고른 사실을 기억해 값이 프리셋과 같아져도 칸을 유지한다.
 * 칸을 닫지 않는 것을 확인하는 시험은 글자마다 치는 pressSequentially·press 만 쓴다 — fill 은 중간 값을 건너뛰어 결함을 못 잡는다.
 * 값이 정해진 뒤 결과만 보는 자리(고쳐 쓰기 등)는 fill 도 쓴다.
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

const cronInput = (page: Page) => page.getByPlaceholder('0 9 * * *');
const presetSelect = (page: Page) => page.locator('#job-cron-preset');
/** 실행 주기 칸 묶음 — 프리셋 선택 칸과 직접 입력 칸·오류 문구가 함께 든 div. 직접 입력 칸이 닫혀도 남는다. */
const cronGroup = (page: Page) => presetSelect(page).locator('..');
const TOO_FREQUENT = /실행 주기는 1분보다 짧게 할 수 없습니다/;
const createButton = (page: Page) => page.getByRole('button', { name: '만들기', exact: true });
const saveButton = (page: Page) => page.getByRole('button', { name: '저장', exact: true });
const UNSET = '실행 주기를 설정하세요';
/** 「직접 입력」 칸이 빈 동안 보이는 회색 안내(WD-962 리뷰 후속) — 새 작업·편집 */
const EMPTY_CREATE = 'Cron 표현식이 비어 있어 만들 수 없습니다.';
const EMPTY_SAVE = 'Cron 표현식이 비어 있어 저장할 수 없습니다.';

/** 실행 주기에서 「직접 입력」을 고른다. */
async function chooseCustom(page: Page) {
  await presetSelect(page).click();
  await page.getByRole('option', { name: '직접 입력' }).click();
}

/** 트리거 유형을 고른다. */
async function chooseTrigger(page: Page, label: string) {
  await page.locator('#job-trigger-type').click();
  await page.getByRole('option', { name: label }).click();
}

/** 기존 작업 상세를 지정한 cron 으로 띄운다(나중 등록 라우트가 이긴다). */
async function openJob(page: Page, cronExpression: string, overrides: Partial<ProactiveJob> = {}) {
  await setupJobDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, cronExpression, ...overrides }));
  await page.goto('/ai-insights/jobs/1');
  await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
}

test.describe('스마트 작업 — 「직접 입력」 칸 유지 (WD-962)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('새 작업: 치는 중 프리셋과 같아져도 칸·초점이 남아 0 9 * * 1,3 으로 만들어진다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    const preview = await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    const writes = await captureJobWrites(page);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('주기 시험');
    await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
    await chooseCustom(page);

    await cronInput(page).pressSequentially('0 9 * * 1');
    // 여기서 값이 프리셋(매주 월요일 오전 9시)과 같아진다 — 그래도 칸이 남아 초점을 쥐고 있어야 한다
    await expect(cronInput(page)).toBeFocused();
    await expect(presetSelect(page)).toHaveText('직접 입력');
    await page.keyboard.type(',3');

    await expect(cronInput(page)).toHaveValue('0 9 * * 1,3');
    await expect.poll(() => preview.lastRequest()?.searchParams.get('cron')).toBe('0 9 * * 1,3');
    await createButton(page).click();
    await expect.poll(() => writes.posts.length).toBe(1);
    expect(writes.posts[0].cronExpression).toBe('0 9 * * 1,3');
  });

  test('편집: 사용자 정의 값을 고치다 프리셋과 같아져도 칸·초점이 남는다', async ({ authenticatedPage: page }) => {
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    const writes = await captureJobWrites(page, 1);
    await openJob(page, '0 9 * * 1,3');

    await page.getByRole('button', { name: '편집' }).click();
    await expect(cronInput(page)).toHaveValue('0 9 * * 1,3');
    await cronInput(page).focus();
    await page.keyboard.press('End');
    await page.keyboard.press('Backspace');
    await page.keyboard.press('Backspace');

    await expect(cronInput(page)).toBeFocused();
    await expect(cronInput(page)).toHaveValue('0 9 * * 1');
    await page.keyboard.type(',5');
    await expect(cronInput(page)).toHaveValue('0 9 * * 1,5');

    await saveButton(page).click();
    await expect.poll(() => writes.puts.length).toBe(1);
    expect(writes.puts[0].cronExpression).toBe('0 9 * * 1,5');
  });

  test('편집을 취소하고 다시 열면 저장된 주기로 보인다', async ({ authenticatedPage: page }) => {
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await openJob(page, '0 9 * * *');

    await page.getByRole('button', { name: '편집' }).click();
    await chooseCustom(page);
    await cronInput(page).pressSequentially('0 8 * * *');

    await page.getByRole('button', { name: '취소' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();

    // 다시 편집하면 「직접 입력」 기억이 남지 않아 저장된 프리셋으로 보인다
    await page.getByRole('button', { name: '편집' }).click();
    await expect(presetSelect(page)).toHaveText('매일 오전 9시');
    await expect(cronInput(page)).toHaveCount(0);
  });

  test('치는 중 다른 탭을 다녀와도 직접 입력 칸과 값이 남아 이어 칠 수 있다', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await page.goto('/ai-insights/jobs/new');

    await chooseCustom(page);
    await cronInput(page).pressSequentially('0 9 * * 1');
    // 개요 탭은 다른 탭으로 가면 언마운트된다 — 선택한 사실이 그때 사라지면 안 된다
    await page.getByRole('tab', { name: '모니터링' }).click();
    await expect(cronInput(page)).toHaveCount(0);
    await page.getByRole('tab', { name: '개요' }).click();

    await expect(presetSelect(page)).toHaveText('직접 입력');
    await cronInput(page).focus();
    await page.keyboard.press('End');
    await page.keyboard.type(',3');
    await expect(cronInput(page)).toHaveValue('0 9 * * 1,3');
  });

  test('직접 입력 중 프리셋을 고르면 칸이 닫힌다', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
    await page.goto('/ai-insights/jobs/new');

    await chooseCustom(page);
    await cronInput(page).pressSequentially('0 9 * * 1');

    await presetSelect(page).click();
    await page.getByRole('option', { name: '매시간' }).click();
    await expect(cronInput(page)).toHaveCount(0);
    await expect(presetSelect(page)).toHaveText('매시간');
  });

  test.describe('cron 때문에 꺼진 이유 안내', () => {
    /** 이름·프롬프트를 채운 새 작업 폼 — 만들기를 막을 수 있는 건 cron 뿐이게 한다. */
    async function openFilledNewJob(page: Page) {
      await setupNewJobMocks(page);
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page);
      await page.goto('/ai-insights/jobs/new');
      await page.locator('#job-name').fill('주기 시험');
      await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
      return writes;
    }

    test('새 작업: 너무 잦은 cron 이면 이유가 보이고, 고치면 사라진다', async ({ authenticatedPage: page }) => {
      const writes = await openFilledNewJob(page);
      await chooseCustom(page);
      await cronInput(page).pressSequentially('* * * * * *');
      await page.keyboard.press('Tab');

      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toBeVisible();
      await expect(createButton(page)).toBeDisabled();
      expect(writes.posts).toHaveLength(0);

      await cronInput(page).fill('0 * * * * *');
      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toHaveCount(0);
      await expect(createButton(page)).toBeEnabled();
      await createButton(page).click();
      await expect.poll(() => writes.posts.length).toBe(1);
      expect(writes.posts[0].cronExpression).toBe('0 * * * * *');
    });

    test('너무 잦은 채 프리셋을 고르면 문구가 없어지고 만들기가 켜진다', async ({ authenticatedPage: page }) => {
      const writes = await openFilledNewJob(page);
      await chooseCustom(page);
      await cronInput(page).pressSequentially('* * * * * *');
      await expect(createButton(page)).toBeDisabled();

      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toBeVisible();

      await presetSelect(page).click();
      await page.getByRole('option', { name: '매시간' }).click();
      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toHaveCount(0);
      await expect(createButton(page)).toBeEnabled();
      await createButton(page).click();
      await expect.poll(() => writes.posts.length).toBe(1);
      expect(writes.posts[0].cronExpression).toBe('0 * * * *');
    });

    test('이상 탐지로 바꾸면 cron 이 막지 않고, 되돌리면 이유가 다시 보인다', async ({ authenticatedPage: page }) => {
      await openFilledNewJob(page);
      await chooseCustom(page);
      await cronInput(page).pressSequentially('* * * * * *');
      await expect(createButton(page)).toBeDisabled();

      await chooseTrigger(page, '이상 탐지 (이벤트 기반)');
      await expect(page.getByText(TOO_FREQUENT)).toHaveCount(0);
      await expect(createButton(page)).toBeEnabled();

      await chooseTrigger(page, '스케줄 (정기 실행)');
      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toBeVisible();
      await expect(createButton(page)).toBeDisabled();
    });

    test('편집: 이미 너무 잦은 cron 이면 손대기 전부터 이유가 보인다', async ({ authenticatedPage: page }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      await openJob(page, '* * * * * *');
      await page.getByRole('button', { name: '편집' }).click();

      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toBeVisible();
      await expect(saveButton(page)).toBeDisabled();
    });

    test('직접 입력을 막 고르면 빨간 문구가 없고, 썼다 지우면 설정하라는 문구가 보인다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewJobMocks(page);
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      await page.goto('/ai-insights/jobs/new');
      await chooseCustom(page);

      const unset = cronGroup(page).getByText(UNSET);
      await expect(unset).toHaveCount(0);
      await cronInput(page).pressSequentially('0');
      await page.keyboard.press('Backspace');
      await expect(unset).toBeVisible();
    });

    // WD-962 리뷰 후속 ① — 막 연 빈 칸은 빨간 오류 대신 회색 안내로 「만들기」가 꺼진 이유를 보인다
    test('새 작업: 직접 입력 칸이 빈 동안 만들기가 꺼진 이유를 회색 안내로 보이고, 치면 사라진다', async ({
      authenticatedPage: page,
    }) => {
      const writes = await openFilledNewJob(page);
      await chooseCustom(page);

      const hint = cronGroup(page).getByText(EMPTY_CREATE);
      await expect(hint).toBeVisible();
      await expect(hint).toHaveClass(/text-muted-foreground/);
      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
      await expect(createButton(page)).toBeDisabled();

      await cronInput(page).pressSequentially('0 8 * * *');
      await expect(hint).toHaveCount(0);
      await expect(createButton(page)).toBeEnabled();
      await createButton(page).click();
      await expect.poll(() => writes.posts.length).toBe(1);
      expect(writes.posts[0].cronExpression).toBe('0 8 * * *');
    });

    test('편집: 직접 입력 칸을 막 열면 저장이 꺼진 이유를 회색 안내로 보인다', async ({ authenticatedPage: page }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      await openJob(page, '0 9 * * *');
      await page.getByRole('button', { name: '편집' }).click();
      await chooseCustom(page);

      await expect(cronGroup(page).getByText(EMPTY_SAVE)).toBeVisible();
      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
      await expect(saveButton(page)).toBeDisabled();
    });

    // WD-962 리뷰 후속 ② — 트리거를 바꾸면 cron 오류를 맞춘다. 빈 직접 입력 칸은 검사하지 않고 오류만 지워 회색 안내로 이유를 보인다.
    test('새 작업: 칸을 비운 채 이상 탐지를 다녀오면 낡은 빨간 문구 대신 회색 안내가 보이고 만들기가 꺼진다', async ({
      authenticatedPage: page,
    }) => {
      await openFilledNewJob(page);
      await chooseCustom(page);
      await cronInput(page).pressSequentially('0');
      await page.keyboard.press('Backspace');
      await expect(cronGroup(page).getByText(UNSET)).toBeVisible();

      await chooseTrigger(page, '이상 탐지 (이벤트 기반)');
      await expect(createButton(page)).toBeEnabled();

      await chooseTrigger(page, '스케줄 + 이상 탐지');
      await expect(cronGroup(page).getByText(EMPTY_CREATE)).toBeVisible();
      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
      await expect(createButton(page)).toBeDisabled();
    });

    test('새 작업: 직접 입력을 막 고른 채 트리거를 바꿔도 빈 칸에 빨간 문구가 뜨지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await openFilledNewJob(page);
      await chooseCustom(page);
      await chooseTrigger(page, '스케줄 + 이상 탐지');

      await expect(cronGroup(page).getByText(EMPTY_CREATE)).toBeVisible();
      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
      await expect(createButton(page)).toBeDisabled();
    });

    // 트리거 변경 때의 form.trigger 분기는 화면에서 관찰되지 않아(문구는 값으로, isValid 는 setValue 때 다시 계산) 눈에 보이는 동작을 지킨다.
    // 빈 cron 의 clearErrors 분기는 「칸을 비운 채 이상 탐지를 다녀오면…」 시험이 지킨다.
    test('편집: 스케줄 → 이상 탐지 → 스케줄로 다녀와도 보이는 이유와 저장 버튼이 지금 값에 맞는다', async ({
      authenticatedPage: page,
    }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page, 1);
      await openJob(page, '0 9 * * *');
      await page.getByRole('button', { name: '편집' }).click();
      await chooseCustom(page);
      await cronInput(page).pressSequentially('* * * * * *');
      await page.keyboard.press('Tab');

      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toBeVisible();
      await expect(saveButton(page)).toBeDisabled();

      // 이상 탐지는 cron 이 필수도 아니고 너무 잦음 검사도 없다 — cron 칸이 숨겨지고 저장이 켜져야 한다
      await chooseTrigger(page, '이상 탐지 (이벤트 기반)');
      await expect(presetSelect(page)).toBeHidden();
      await expect(saveButton(page)).toBeEnabled();

      await chooseTrigger(page, '스케줄 (정기 실행)');
      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toBeVisible();
      await expect(saveButton(page)).toBeDisabled();

      await cronInput(page).fill('0 8 * * *');
      await expect(cronGroup(page).getByText(TOO_FREQUENT)).toHaveCount(0);
      await expect(saveButton(page)).toBeEnabled();

      // 올바른 값으로 「스케줄 + 이상 탐지」로 바꿔도 칸이 남고 낡은 「실행 주기를 설정하세요」 없이 저장이 켜진다(편집 + BOTH)
      await chooseTrigger(page, '스케줄 + 이상 탐지');
      await expect(cronInput(page)).toBeVisible();
      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
      await expect(saveButton(page)).toBeEnabled();

      // 다녀온 뒤 저장되는 값이 지금 화면 값과 같아야 한다 — 낡은 주기·트리거가 실리지 않는다
      await saveButton(page).click();
      await expect.poll(() => writes.puts.length).toBe(1);
      expect(writes.puts[0].cronExpression).toBe('0 8 * * *');
      expect(writes.puts[0].triggerType).toBe('BOTH');
    });

    // 서버는 주기·트리거를 건드리지 않는 수정을 받는다(WD-607). 예전에 공백으로 저장된 작업의 이름 수정을 화면이 막으면 안 된다.
    test('편집: 주기가 공백으로 저장된 옛 작업도 이름만 고쳐 저장된다', async ({ authenticatedPage: page }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page, 1);
      await openJob(page, ' ');
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('이름만 고침');

      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
      await expect(saveButton(page)).toBeEnabled();
      await saveButton(page).click();
      await expect.poll(() => writes.puts.length).toBe(1);
      // 공백 주기는 잘린 빈 값으로 정리해 보낸다 — 서버는 공백·빈 값을 같은 「없음」으로 보아 변경·400 이 아니다(WD-995)
      expect(writes.puts[0].cronExpression).toBe('');
    });

    // 서버는 트리거·주기를 안 건드리는 수정은 주기가 없어도 받는다(WD-995). 화면도 이름만 고치는 저장을 막지 않는다.
    test('편집: 주기 없는 작업도 트리거·주기를 안 건드리면 이름만 고쳐 저장된다 (WD-995)', async ({
      authenticatedPage: page,
    }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page, 1);
      await openJob(page, '', { triggerType: 'BOTH' });
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('이름만 고침');

      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
      await expect(saveButton(page)).toBeEnabled();
      await saveButton(page).click();
      await expect.poll(() => writes.puts.length).toBe(1);
      expect(writes.puts[0]).not.toHaveProperty('cronExpression');
    });

    test('편집: 안 바꾼 주기는 수정 요청에 보내지 않는다 (WD-995)', async ({ authenticatedPage: page }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page, 1);
      await openJob(page, '0 9 * * *');
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('이름만 고침');
      await saveButton(page).click();
      await expect.poll(() => writes.puts.length).toBe(1);
      expect(writes.puts[0]).not.toHaveProperty('cronExpression');
    });

    // 끝 공백으로 저장된 옛 주기는 이름만 고쳐도 잘린 값을 보내 정리한다 — 생략하면 서버가 손대지 않아 공백이 영영 남는다(WD-995 리뷰).
    // 서버는 공백만 다른 값을 변경으로 보지 않으므로 감사에는 남지 않는다.
    test('편집: 끝 공백으로 저장된 주기는 이름만 고쳐도 잘린 값으로 정리된다 (WD-995)', async ({ authenticatedPage: page }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page, 1);
      await openJob(page, '0 9 * * * ');
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('이름만 고침');
      await saveButton(page).click();
      await expect.poll(() => writes.puts.length).toBe(1);
      expect(writes.puts[0].cronExpression).toBe('0 9 * * *');
    });

    test('편집: 끝 공백 주기는 잘라 보낸다 (WD-995)', async ({ authenticatedPage: page }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page, 1);
      await openJob(page, '0 9 * * *');
      await page.getByRole('button', { name: '편집' }).click();
      await chooseCustom(page);
      await cronInput(page).fill('0 10 * * * ');
      await saveButton(page).click();
      await expect.poll(() => writes.puts.length).toBe(1);
      expect(writes.puts[0].cronExpression).toBe('0 10 * * *');
    });

    test('새 작업: 공백만 넣은 주기로는 만들 수 없다 (WD-995)', async ({ authenticatedPage: page }) => {
      await setupNewJobMocks(page);
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      await page.goto('/ai-insights/jobs/new');
      await page.locator('#job-name').fill('공백 주기');
      await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
      await chooseCustom(page);
      await cronInput(page).fill('   ');

      await expect(createButton(page)).toBeDisabled();
      await expect(cronGroup(page).getByText(UNSET)).toBeVisible();
    });

    test('편집: 주기 없는 이상 탐지 작업을 스케줄로 바꾸면 이유가 보이고 저장이 꺼진다', async ({
      authenticatedPage: page,
    }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      await openJob(page, '', { triggerType: 'ANOMALY' });
      await page.getByRole('button', { name: '편집' }).click();
      await chooseTrigger(page, '스케줄 (정기 실행)');

      await expect(cronGroup(page).getByText(UNSET)).toBeVisible();
      await expect(saveButton(page)).toBeDisabled();
    });

    test('편집: 주기 없는 작업도 프리셋을 골랐다 직접 입력을 고르면 빈 칸에 빨간 문구가 없다', async ({
      authenticatedPage: page,
    }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      await openJob(page, '', { triggerType: 'BOTH' });
      await page.getByRole('button', { name: '편집' }).click();
      await presetSelect(page).click();
      await page.getByRole('option', { name: '매시간' }).click();
      await chooseCustom(page);

      await expect(cronInput(page)).toHaveValue('');
      await expect(cronGroup(page).getByText(UNSET)).toHaveCount(0);
    });

    test('편집: 주기가 null 인 이상 탐지 작업도 저장되고 cronExpression 은 보내지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      const writes = await captureJobWrites(page, 1);
      await openJob(page, '', { triggerType: 'ANOMALY', cronExpression: null as unknown as string });
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('이름만 고침');

      await expect(saveButton(page)).toBeEnabled();
      await saveButton(page).click();
      await expect.poll(() => writes.puts.length).toBe(1);
      expect(writes.puts[0]).not.toHaveProperty('cronExpression');
    });
  });

  for (const { title, latest, label } of [
    { title: '다른 값', latest: '*/30 * * * *', label: '매 30분' },
    { title: '내가 친 것과 같은 값', latest: '0 * * * *', label: '매시간' },
  ]) {
    test(`충돌 후 「다시 불러오기」(${title})면 직접 입력 칸이 닫히고 저장된 프리셋이 보인다`, async ({
      authenticatedPage: page,
    }) => {
      await mockCronPreview(page, JOB_CRON_PREVIEW_PATH);
      await openJob(page, '0 9 * * *');
      await page.route(
        (url) => url.pathname === '/api/v1/proactive/jobs/1',
        (route) =>
          route.request().method() === 'PUT'
            ? route.fulfill({
                status: 409,
                contentType: 'application/json',
                body: JSON.stringify({ status: 409, code: 'EDIT_CONFLICT', message: '다른 곳에서 변경되었습니다.' }),
              })
            : route.fallback(),
      );

      await page.getByRole('button', { name: '편집' }).click();
      await chooseCustom(page);
      await cronInput(page).pressSequentially('0 * * * *');
      await saveButton(page).click();
      const dialog = page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });
      await expect(dialog).toBeVisible();

      // 다른 탭이 먼저 저장했다 — 재조회 결과가 이 값을 돌려준다
      await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, cronExpression: latest }));
      await dialog.getByRole('button', { name: '다시 불러오기' }).click();

      await expect(presetSelect(page)).toHaveText(label);
      await expect(cronInput(page)).toHaveCount(0);
    });
  }
});
