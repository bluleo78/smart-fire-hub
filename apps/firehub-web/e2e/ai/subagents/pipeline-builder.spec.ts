import { test } from '@playwright/test';

import {
assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
  assertToolCalled, sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('pipeline-builder 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-PB-01: 파이프라인 목록 보여줘', async ({ page }) => {
    await sendMessage(page, '파이프라인 목록 보여줘');
    await waitForResponse(page);
    await assertToolCalled(page, '파이프라인 목록 조회');
    await assertNoError(page);
  });

  test('TC-PB-02: 어떤 파이프라인들이 있어?', async ({ page }) => {
    await sendMessage(page, '어떤 파이프라인들이 있어?');
    await waitForResponse(page);
    await assertToolCalled(page, '파이프라인 목록 조회');
    await assertNoError(page);
  });

  test('TC-PB-03: ETL 파이프라인 있어?', async ({ page }) => {
    await sendMessage(page, 'ETL 파이프라인 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-PB-04: 파이프라인 실행 상태 알려줘', async ({ page }) => {
    await sendMessage(page, '파이프라인 실행 상태 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-PB-05: 새 파이프라인 만들어줘', async ({ page }) => {
    await sendMessage(page, '새 파이프라인 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-PB-06: 데이터를 변환하는 파이프라인 필요해', async ({ page }) => {
    await sendMessage(page, '데이터를 변환하는 파이프라인 필요해');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-PB-07: API에서 데이터 가져오는 파이프라인 만들어줘', async ({ page }) => {
    await sendMessage(page, 'API에서 데이터 가져오는 파이프라인 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-PB-08: 파이프라인에 SQL 스텝 추가해줘', async ({ page }) => {
    await sendMessage(page, '파이프라인에 SQL 스텝 추가해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-PB-09: 파이프라인 실행해줘', async ({ page }) => {
    await sendMessage(page, '파이프라인 실행해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-PB-10: 파이프라인 돌려줘', async ({ page }) => {
    await sendMessage(page, '파이프라인 돌려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-PB-11: 파이프라인 실행 결과 알려줘', async ({ page }) => {
    await sendMessage(page, '파이프라인 실행 결과 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-PB-12: 왜 파이프라인이 실패했어?', async ({ page }) => {
    await sendMessage(page, '왜 파이프라인이 실패했어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-PB-13: 파이프라인 삭제해줘', async ({ page }) => {
    await sendMessage(page, '파이프라인 삭제해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });
});
