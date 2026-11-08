import type { Page } from '@playwright/test';

import { createMessage } from '../factories/ai-insight.factory';
import { createCategories, createColumn, createDatasetDetail } from '../factories/dataset.factory';
import { createEntityReviewItem } from '../factories/reviewItem.factory';
import { setupJobListMocks, setupNotificationPanelMocks } from '../fixtures/ai-insight.fixture';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../fixtures/dataset.fixture';
import { expectDialogContained, expectSingleLine, expectWithin, expectWrapped } from '../fixtures/long-name-layout';

/**
 * 일반 대화상자·알림 상세의 긴 이름 넘침 E2E (WD-648, WD-632 후속).
 *
 * - 일반 Dialog(DialogContent)도 AlertDialogContent 처럼 칸 폭을 grid-cols-[minmax(0,1fr)] 로 묶어야 한다 —
 *   암시적 auto 칸은 자식의 최소 내용 폭(공백 없는 긴 이름)까지 늘어나 창을 넓히고 하단 버튼을 화면 밖으로 민다.
 * - 줄바꿈 상속은 overflow-wrap: break-word 여야 한다 — anywhere 는 모든 자식의 최소 내용 폭을 줄여,
 *   flex 행의 짧은 식별자(컬럼명·데이터 타입 등)까지 글자 중간에서 끊는다.
 * - AlertDialogHeader 의 제목·설명 밖 자식(검수 확인 창의 대상 요약 등)도 헤더 폭 안에서 줄바꿈된다.
 * - 알림 상세의 제목은 말줄임(truncate) 없이 줄바꿈해 전부 읽힌다(상세 화면).
 */

/** 데이터셋 이름 상한(100자)을 꽉 채운 공백 없는 이름 */
const LONG_DATASET_NAME = 'D'.repeat(100);
/** 알림 제목 — 앞부분은 목록에서 찾을 표지, 뒷부분은 공백 없는 긴 낱말 */
const LONG_TITLE_PREFIX = '긴제목리포트';
const LONG_MESSAGE_TITLE = `${LONG_TITLE_PREFIX}${'R'.repeat(120)}`;
/** 검수 대상 엔티티의 공백 없는 긴 이름(추출 결과라 길이 상한이 없다) */
const LONG_ENTITY_NAME = 'N'.repeat(150);

test.use({ viewport: { width: 1280, height: 800 } });

/** 데이터셋 화면이 공통으로 부르는 카테고리·태그 목록 모킹 */
async function mockDatasetLookups(page: Page) {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
}

test.describe('일반 대화상자·알림 상세 긴 이름 넘침 (WD-648)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await mockDatasetLookups(page);
  });

  test('데이터셋 복제 대화상자 — 100자 원본 이름이 줄바꿈되고 복제 버튼이 화면 안에 남는다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetDetailMocks(page, 1);
    // 상세 조회만 긴 이름으로 덮어쓴다(나중에 등록한 route 가 우선)
    await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1, name: LONG_DATASET_NAME }));

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: LONG_DATASET_NAME })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '복제' }).click();

    const dialog = page.getByRole('dialog', { name: '데이터셋 복제' });
    await expect(dialog).toBeVisible();
    // 원본 이름은 잘리지 않고 원본 안내 상자에 전부 들어 있으며, 상자 안에서 줄바꿈된다
    const source = dialog.getByText('원본:', { exact: false });
    await expect(source).toContainText(LONG_DATASET_NAME);
    await expectWrapped(source);
    await expectDialogContained(dialog, dialog.getByRole('button', { name: '복제', exact: true }));
  });

  test('데이터셋 미리보기 대화상자 — 제목의 100자 이름이 줄바꿈되고 창이 가로로 넘치지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([createDatasetDetail({ id: 1, name: LONG_DATASET_NAME })]));
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: [],
      rows: [],
      page: 0,
      size: 5,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/data/datasets');
    // 미리보기 버튼은 행에 마우스를 올렸을 때 나타난다(hover-opacity) — 사용자처럼 행에 올린 뒤 누른다
    const row = page.getByRole('row').filter({ hasText: LONG_DATASET_NAME });
    await row.hover();
    await row.getByRole('button', { name: '미리보기' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    const title = dialog.getByRole('heading', { name: `미리보기: ${LONG_DATASET_NAME}` });
    await expect(title).toBeVisible();
    await expectWrapped(title);
    // 제목이 창 안에 머물고, 창은 넘치지 않으며 닫기 버튼이 화면 안에 보인다
    await expectWithin(title, dialog);
    await expectDialogContained(dialog, dialog.getByRole('button', { name: '닫기' }));
  });

  test('기본 키 설정 대화상자 — flex 행의 짧은 컬럼명·데이터 타입이 글자 중간에서 끊기지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 긴 표시 이름이 같은 flex 행을 꽉 채워도, 짧은 식별자(컬럼명·타입)는 한 줄 그대로여야 한다.
    // 대화상자 줄바꿈 상속이 overflow-wrap:anywhere 면 flex 항목의 최소 폭이 줄어 「customer_e / mail」처럼 끊긴다.
    await setupDatasetDetailMocks(page, 5);
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets/5',
      createDatasetDetail({
        id: 5,
        rowCount: 0,
        columns: [
          createColumn({
            id: 1,
            columnName: 'customer_email',
            displayName: '고객이 회원 가입할 때 입력한 연락용 전자우편 주소이며 알림 발송과 본인 확인에 함께 쓰는 값',
            dataType: 'TIMESTAMP',
            isPrimaryKey: false,
            isNullable: true,
            columnOrder: 0,
          }),
        ],
      }),
    );

    await page.goto('/data/datasets/5');
    await page.getByRole('tab', { name: '필드' }).click();
    await page.getByRole('button', { name: /기본 키 설정/ }).click();

    const dialog = page.getByRole('dialog', { name: /기본 키 일괄 설정/ });
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await expectSingleLine(dialog.getByText('customer_email', { exact: true }));
    await expectSingleLine(dialog.getByText('TIMESTAMP', { exact: true }));
    await expectWrapped(dialog);
  });

  test('검수 거부 확인 창 — 헤더의 대상 요약 속 긴 엔티티 이름이 줄바꿈되고 거부 버튼이 화면 안에 남는다', async ({
    authenticatedPage: page,
  }) => {
    // 대상 요약은 AlertDialogHeader 의 제목·설명이 아닌 별도 자식(div)이다 — 헤더 자식 전체에 폭 제한이 걸리는지 본다.
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [
      createEntityReviewItem({
        payload: { entityType: 'Cause', name: LONG_ENTITY_NAME, sourceChunkIds: [9], relations: [] },
      }),
    ]);

    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '거부' }).click();

    const dialog = page.getByTestId('review-decide-confirm');
    await expect(dialog).toBeVisible();
    const summary = dialog.getByText(`대상: “${LONG_ENTITY_NAME}” (Cause)`, { exact: true });
    await expect(summary).toBeVisible();
    await expectWrapped(summary);
    await expectWithin(summary, dialog);
    await expectDialogContained(dialog, page.getByTestId('review-decide-confirm-action'));
  });

  test('알림 패널 상세 — 공백 없는 긴 제목이 말줄임 없이 패널 폭 안에서 줄바꿈된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNotificationPanelMocks(page, [createMessage({ title: LONG_MESSAGE_TITLE, read: false })]);
    await setupJobListMocks(page);

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
    const panel = page.getByRole('dialog', { name: 'AI 인사이트 알림' });
    await expect(panel).toBeVisible();
    await panel.getByRole('button', { name: new RegExp(LONG_TITLE_PREFIX) }).click();
    await expect(panel.getByRole('button', { name: '목록으로 돌아가기' })).toBeVisible();

    // 상세 머리글의 제목 — 말줄임이면 scrollWidth > clientWidth 라 expectWrapped 가 잡는다
    const title = panel.getByText(LONG_MESSAGE_TITLE, { exact: true });
    await expectWrapped(title);
    await expectWithin(title, panel);
  });
});
