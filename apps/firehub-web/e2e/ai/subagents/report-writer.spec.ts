import { test } from '@playwright/test';

import {
  assertNoError, assertResponseNotEmpty, sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('report-writer 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-RW-01: 보고서 작성해줘', async ({ page }) => {
    await sendMessage(page, '보고서 작성해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-RW-02: 분석 결과 보고서로 정리해줘', async ({ page }) => {
    await sendMessage(page, '분석 결과를 보고서로 정리해줘. 데이터: 총 5개 데이터셋, 월간 조회수 1200회');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-RW-03: 요약 보고서 만들어줘', async ({ page }) => {
    await sendMessage(page, '요약 보고서 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-RW-04: 경영진 보고서 형태로 만들어줘', async ({ page }) => {
    await sendMessage(page, '경영진 보고서 형태로 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-RW-05: 표 형태로 정리해줘', async ({ page }) => {
    await sendMessage(page, '데이터를 표 형태로 정리해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-RW-06: 차트와 설명을 포함한 보고서 만들어줘', async ({ page }) => {
    await sendMessage(page, '차트와 설명을 포함한 보고서 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-RW-07: PDF로 내보낼 수 있는 보고서 만들어줘', async ({ page }) => {
    await sendMessage(page, 'PDF로 내보낼 수 있는 보고서 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-RW-08: 보고서 내용 수정해줘', async ({ page }) => {
    await sendMessage(page, '보고서 내용 수정해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });
});
