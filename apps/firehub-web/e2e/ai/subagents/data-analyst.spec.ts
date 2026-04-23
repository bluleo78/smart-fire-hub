import { test } from '@playwright/test';

import {
assertNoError,   assertResponseIsQuestion, assertResponseNotEmpty,
  assertToolCalled, sendMessage,   setupAIChat, startNewSession,
waitForResponse,
} from '../helpers/ai-chat';

test.describe('data-analyst 서브에이전트', () => {
  test.setTimeout(120_000);

  test.beforeEach(async ({ page }) => {
    await setupAIChat(page);
    await startNewSession(page);
  });

  test('TC-DA-01: 데이터 분석해줘', async ({ page }) => {
    await sendMessage(page, '데이터 분석해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseIsQuestion(page);
  });

  test('TC-DA-02: 데이터셋 통계 알려줘', async ({ page }) => {
    await sendMessage(page, '데이터셋 통계 알려줘');
    await waitForResponse(page);
    await assertToolCalled(page, '분석 쿼리 실행');
    await assertNoError(page);
  });

  test('TC-DA-03: 전체 레코드 수 알려줘', async ({ page }) => {
    await sendMessage(page, '전체 레코드 수 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-04: 카테고리별 데이터셋 수 알려줘', async ({ page }) => {
    await sendMessage(page, '카테고리별 데이터셋 수 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-05: 월별 추이 분석해줘', async ({ page }) => {
    await sendMessage(page, '월별 추이 분석해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-06: 상위 10개 알려줘', async ({ page }) => {
    await sendMessage(page, '상위 10개 알려줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-07: 두 데이터셋 비교해줘', async ({ page }) => {
    await sendMessage(page, '두 데이터셋 비교해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-08: 데이터 분석 결과를 차트로 보여줘', async ({ page }) => {
    await sendMessage(page, '데이터 분석 결과를 차트로 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-09: 막대 그래프 만들어줘', async ({ page }) => {
    await sendMessage(page, '막대 그래프 만들어줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-10: 꺾은선 차트로 추이 보여줘', async ({ page }) => {
    await sendMessage(page, '꺾은선 차트로 추이 보여줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-11: 이상치 찾아줘', async ({ page }) => {
    await sendMessage(page, '이상치 찾아줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-12: 상관관계 분석해줘', async ({ page }) => {
    await sendMessage(page, '상관관계 분석해줘');
    await waitForResponse(page);
    await assertNoError(page);
    await assertResponseNotEmpty(page);
  });

  test('TC-DA-13: SQL 쿼리 직접 실행해줘', async ({ page }) => {
    await sendMessage(page, 'SQL 쿼리 실행해줘: SELECT COUNT(*) as total FROM information_schema.tables');
    await waitForResponse(page);
    await assertToolCalled(page, '분석 쿼리 실행');
    await assertNoError(page);
  });
});
