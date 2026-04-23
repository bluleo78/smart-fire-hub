import { test } from '@playwright/test';

import {
  assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('admin-manager 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-AM-01: 사용자 목록 보여줘', async ({ page }) => {
    await sendMessage(page, '사용자 목록 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AM-02: 가입한 계정 목록 알려줘', async ({ page }) => {
    await sendMessage(page, '가입한 계정 목록 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AM-03: 어드민 권한 있는 사용자 알려줘', async ({ page }) => {
    await sendMessage(page, '어드민 권한 있는 사용자 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AM-04: 특정 사용자 정보 알려줘', async ({ page }) => {
    await sendMessage(page, '특정 사용자 정보 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AM-05: 사용자 역할 변경해줘', async ({ page }) => {
    await sendMessage(page, '사용자 역할 변경해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AM-06: 어드민 권한 부여해줘', async ({ page }) => {
    await sendMessage(page, '어드민 권한 부여해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AM-07: 권한 회수해줘', async ({ page }) => {
    await sendMessage(page, '권한 회수해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AM-08: 사용자 계정 비활성화해줘', async ({ page }) => {
    await sendMessage(page, '사용자 계정 비활성화해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AM-09: 계정 다시 활성화해줘', async ({ page }) => {
    await sendMessage(page, '계정 다시 활성화해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AM-10: 탈퇴 처리해줘', async ({ page }) => {
    await sendMessage(page, '탈퇴 처리해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });
});
