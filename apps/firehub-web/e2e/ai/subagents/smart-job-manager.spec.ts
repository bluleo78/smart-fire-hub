import { test } from '@playwright/test';

import {
  assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('smart-job-manager 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-SJ-01: 스마트 작업 목록 보여줘', async ({ page }) => {
    await sendMessage(page, '스마트 작업 목록 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-SJ-02: 어떤 자동화 작업 있어?', async ({ page }) => {
    await sendMessage(page, '어떤 자동화 작업 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-SJ-03: 정기 분석 작업 있어?', async ({ page }) => {
    await sendMessage(page, '정기 분석 작업 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-SJ-04: 스마트 작업 실행 이력 알려줘', async ({ page }) => {
    await sendMessage(page, '스마트 작업 실행 이력 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-SJ-05: 스마트 작업 만들어줘', async ({ page }) => {
    await sendMessage(page, '스마트 작업 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-SJ-06: 매일 데이터 품질 체크 자동화하고 싶어', async ({ page }) => {
    await sendMessage(page, '매일 데이터 품질 체크 자동화하고 싶어');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-SJ-07: 분석 결과를 주기적으로 보내는 작업 만들어줘', async ({ page }) => {
    await sendMessage(page, '분석 결과를 주기적으로 보내는 작업 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-SJ-08: 스마트 작업 스케줄 변경해줘', async ({ page }) => {
    await sendMessage(page, '스마트 작업 스케줄 변경해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-SJ-09: 스마트 작업 비활성화해줘', async ({ page }) => {
    await sendMessage(page, '스마트 작업 비활성화해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-SJ-10: 스마트 작업이 왜 실패했어?', async ({ page }) => {
    await sendMessage(page, '스마트 작업이 왜 실패했어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-SJ-11: 스마트 작업 지금 실행해줘', async ({ page }) => {
    await sendMessage(page, '스마트 작업 지금 실행해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });
});
