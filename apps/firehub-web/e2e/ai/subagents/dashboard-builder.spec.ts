import { test } from '@playwright/test';

import {
  assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('dashboard-builder 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-DB-01: 대시보드 목록 보여줘', async ({ page }) => {
    await sendMessage(page, '대시보드 목록 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DB-02: 어떤 대시보드 있어?', async ({ page }) => {
    await sendMessage(page, '어떤 대시보드 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DB-03: 대시보드 만들어줘', async ({ page }) => {
    await sendMessage(page, '대시보드 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-DB-04: 영업 현황 대시보드 만들어줘', async ({ page }) => {
    await sendMessage(page, '영업 현황 대시보드 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DB-05: 새 대시보드가 필요해', async ({ page }) => {
    await sendMessage(page, '새 대시보드가 필요해');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DB-06: 대시보드에 차트 추가해줘', async ({ page }) => {
    await sendMessage(page, '대시보드에 차트 추가해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-DB-07: 차트를 대시보드에 넣어줘', async ({ page }) => {
    await sendMessage(page, '차트를 대시보드에 넣어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DB-08: 대시보드 구성해줘', async ({ page }) => {
    await sendMessage(page, '대시보드 구성해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DB-09: 대시보드 공개로 변경해줘', async ({ page }) => {
    await sendMessage(page, '대시보드 공개로 변경해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DB-10: 대시보드 레이아웃 바꾸고 싶어', async ({ page }) => {
    await sendMessage(page, '대시보드 레이아웃 바꾸고 싶어');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });
});
