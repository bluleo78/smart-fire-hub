import { test } from '@playwright/test';

import {
assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
  assertToolCalled, sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('dataset-manager 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-DM-01: 데이터셋 목록 보여줘', async ({ page }) => {
    await sendMessage(page, '데이터셋 목록 보여줘');
    await waitForResponse(page);
    await assertToolCalled(page, '데이터셋 목록 조회');
    await assertNoError(page);
  });

  test('TC-DM-02: 어떤 데이터셋들이 있어?', async ({ page }) => {
    await sendMessage(page, '어떤 데이터셋들이 있어?');
    await waitForResponse(page);
    await assertToolCalled(page, '데이터셋 목록 조회');
    await assertNoError(page);
  });

  test('TC-DM-03: 데이터 테이블 목록 알려줘', async ({ page }) => {
    await sendMessage(page, '데이터 테이블 목록 알려줘');
    await waitForResponse(page);
    await assertToolCalled(page, '데이터셋 목록 조회');
    await assertNoError(page);
  });

  test('TC-DM-04: 영업 관련 데이터셋 있어?', async ({ page }) => {
    await sendMessage(page, '영업 관련 데이터셋 있어?');
    await waitForResponse(page);
    await assertToolCalled(page, '데이터셋 목록 조회');
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DM-05: 데이터셋 상세 정보 알려줘', async ({ page }) => {
    await sendMessage(page, '데이터셋 상세 정보 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-DM-06: 데이터셋 컬럼 구조 알려줘', async ({ page }) => {
    await sendMessage(page, '데이터셋 컬럼 구조 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DM-07: 새 데이터셋 만들어줘', async ({ page }) => {
    await sendMessage(page, '새 데이터셋 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-DM-08: 고객 데이터 저장할 테이블 만들어줘', async ({ page }) => {
    await sendMessage(page, '고객 데이터 저장할 테이블 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DM-09: 위치 정보 포함한 데이터셋 만들어줘', async ({ page }) => {
    await sendMessage(page, '위치 정보 포함한 데이터셋 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DM-10: 데이터 저장 공간이 필요해', async ({ page }) => {
    await sendMessage(page, '데이터 저장 공간이 필요해');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DM-11: 데이터셋에 컬럼 추가해줘', async ({ page }) => {
    await sendMessage(page, '데이터셋에 컬럼 추가해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-DM-12: 컬럼 타입을 바꾸고 싶어', async ({ page }) => {
    await sendMessage(page, '컬럼 타입을 바꾸고 싶어');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-DM-13: CSV 파일 올리고 싶어', async ({ page }) => {
    await sendMessage(page, 'CSV 파일 올리고 싶어');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DM-14: 엑셀 파일로 데이터 넣고 싶어', async ({ page }) => {
    await sendMessage(page, '엑셀 파일로 데이터 넣고 싶어');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DM-15: 데이터셋 삭제해줘', async ({ page }) => {
    await sendMessage(page, '데이터셋 삭제해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });
});
