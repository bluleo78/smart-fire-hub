import { test } from '@playwright/test';

import {
  assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('api-connection-manager 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-AC-01: API 연결 목록 보여줘', async ({ page }) => {
    await sendMessage(page, 'API 연결 목록 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AC-02: 등록된 외부 API 있어?', async ({ page }) => {
    await sendMessage(page, '등록된 외부 API 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AC-03: API 연결 상세 정보 알려줘', async ({ page }) => {
    await sendMessage(page, 'API 연결 상세 정보 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AC-04: 새 API 연결 등록해줘', async ({ page }) => {
    await sendMessage(page, '새 API 연결 등록해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AC-05: API KEY로 인증하는 외부 API 연결하고 싶어', async ({ page }) => {
    await sendMessage(page, 'API KEY로 인증하는 외부 API 연결하고 싶어');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AC-06: OpenAI API 연결 추가해줘', async ({ page }) => {
    await sendMessage(page, 'OpenAI API 연결 추가해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AC-07: Bearer 토큰으로 인증하는 API 등록해줘', async ({ page }) => {
    await sendMessage(page, 'Bearer 토큰으로 인증하는 API 등록해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AC-08: OAuth 토큰 방식으로 외부 서비스 연결해줘', async ({ page }) => {
    await sendMessage(page, 'OAuth 토큰 방식으로 외부 서비스 연결해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-AC-09: API 연결 수정해줘', async ({ page }) => {
    await sendMessage(page, 'API 연결 수정해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AC-10: API 키 변경해줘', async ({ page }) => {
    await sendMessage(page, 'API 키 변경해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-AC-11: API 연결 삭제해줘', async ({ page }) => {
    await sendMessage(page, 'API 연결 삭제해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });
});
