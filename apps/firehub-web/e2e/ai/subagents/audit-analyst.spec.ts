import { test } from '@playwright/test';

import {
  assertNoError, assertResponseNotEmpty, sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('audit-analyst 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-AA-01: 감사 로그 조회해줘', async ({ page }) => {
    await sendMessage(page, '감사 로그 조회해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-02: 최근 시스템 활동 이력 보여줘', async ({ page }) => {
    await sendMessage(page, '최근 시스템 활동 이력 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-03: 로그 기록 알려줘', async ({ page }) => {
    await sendMessage(page, '로그 기록 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-04: 특정 사용자 활동 이력 알려줘', async ({ page }) => {
    await sendMessage(page, '특정 사용자 활동 이력 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-05: 오늘 로그인한 사람 알려줘', async ({ page }) => {
    await sendMessage(page, '오늘 로그인한 사람 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-06: 데이터셋 삭제 이벤트만 보여줘', async ({ page }) => {
    await sendMessage(page, '데이터셋 삭제 이벤트만 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-07: 지난주 변경 이력 알려줘', async ({ page }) => {
    await sendMessage(page, '지난주 변경 이력 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-08: 실패한 이벤트 패턴 분석해줘', async ({ page }) => {
    await sendMessage(page, '실패한 이벤트 패턴 분석해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-09: 누가 가장 많이 접근했어?', async ({ page }) => {
    await sendMessage(page, '누가 가장 많이 접근했어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AA-10: 이상한 접근 시도 있어?', async ({ page }) => {
    await sendMessage(page, '이상한 접근 시도 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });
});
