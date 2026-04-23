import { test } from '@playwright/test';

import {
  assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('template-builder 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-TB-01: 리포트 양식 목록 보여줘', async ({ page }) => {
    await sendMessage(page, '리포트 양식 목록 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TB-02: 보고서 템플릿 있어?', async ({ page }) => {
    await sendMessage(page, '보고서 템플릿 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TB-03: 어떤 양식들이 있어?', async ({ page }) => {
    await sendMessage(page, '어떤 양식들이 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TB-04: 새 리포트 양식 만들어줘', async ({ page }) => {
    await sendMessage(page, '새 리포트 양식 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-TB-05: 월간 보고서 양식 만들어줘', async ({ page }) => {
    await sendMessage(page, '월간 보고서 양식 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TB-06: HTML 보고서 템플릿 필요해', async ({ page }) => {
    await sendMessage(page, 'HTML 보고서 템플릿 필요해');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TB-07: KPI 대시보드 형태 보고서 양식 만들어줘', async ({ page }) => {
    await sendMessage(page, 'KPI 대시보드 형태 보고서 양식 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TB-08: 리포트 양식 수정해줘', async ({ page }) => {
    await sendMessage(page, '리포트 양식 수정해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-TB-09: 템플릿에 섹션 추가해줘', async ({ page }) => {
    await sendMessage(page, '템플릿에 섹션 추가해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-TB-10: 리포트 양식 삭제해줘', async ({ page }) => {
    await sendMessage(page, '리포트 양식 삭제해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });
});
