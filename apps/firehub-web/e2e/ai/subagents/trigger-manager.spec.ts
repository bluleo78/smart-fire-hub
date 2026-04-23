import { test } from '@playwright/test';

import {
assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
  assertToolCalled, sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('trigger-manager 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-TM-01: 트리거 목록 보여줘', async ({ page }) => {
    await sendMessage(page, '트리거 목록 보여줘');
    await waitForResponse(page);
    await assertToolCalled(page, '트리거 목록 조회');
    await assertNoError(page);
  });

  test('TC-TM-02: 어떤 트리거가 등록돼 있어?', async ({ page }) => {
    await sendMessage(page, '어떤 트리거가 등록돼 있어?');
    await waitForResponse(page);
    await assertToolCalled(page, '트리거 목록 조회');
    await assertNoError(page);
  });

  test('TC-TM-03: 스케줄 트리거 있어?', async ({ page }) => {
    await sendMessage(page, '스케줄 트리거 있어?');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-04: 매일 새벽 2시에 파이프라인 실행 트리거 만들어줘', async ({ page }) => {
    await sendMessage(page, '매일 새벽 2시에 파이프라인 실행 트리거 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-05: 주간 배치 작업 트리거 설정해줘', async ({ page }) => {
    await sendMessage(page, '주간 배치 작업 트리거 설정해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-06: 매주 월요일 오전 9시 트리거 만들어줘', async ({ page }) => {
    await sendMessage(page, '매주 월요일 오전 9시 트리거 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-07: 웹훅 트리거 만들어줘', async ({ page }) => {
    await sendMessage(page, '웹훅 트리거 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-08: 외부 시스템에서 호출할 수 있는 트리거 필요해', async ({ page }) => {
    await sendMessage(page, '외부 시스템에서 호출할 수 있는 트리거 필요해');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-09: 파이프라인 완료 후 다음 파이프라인 실행되게 해줘', async ({ page }) => {
    await sendMessage(page, '파이프라인 완료되면 다음 파이프라인 실행되게 해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-10: 데이터셋 변경되면 파이프라인 실행해줘', async ({ page }) => {
    await sendMessage(page, '데이터셋 변경되면 파이프라인 실행해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-11: API로 트리거할 수 있게 해줘', async ({ page }) => {
    await sendMessage(page, 'API로 트리거할 수 있게 해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-TM-12: 트리거 삭제해줘', async ({ page }) => {
    await sendMessage(page, '트리거 삭제해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });
});
