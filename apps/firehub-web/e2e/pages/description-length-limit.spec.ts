import type { Locator } from '@playwright/test';

import { MAX_DESCRIPTION_LENGTH as LIMIT } from '../../src/lib/validations/description';
import { createSavedQuery } from '../factories/analytics.factory';
import { createCategories, createDatasetDetail } from '../factories/dataset.factory';
import {
  setupAdminAuth,
  setupApiConnectionDetailMocks,
  setupApiConnectionListMocks,
} from '../fixtures/admin.fixture';
import { setupChartBuilderMocks, setupNewQueryEditorMocks } from '../fixtures/analytics.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import {
  grantDatasetPermissions,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../fixtures/dataset.fixture';
import { grantPipelinePermissions } from '../fixtures/pipeline.fixture';

/**
 * 자산 설명 길이 상한 E2E (WD-520)
 *
 * 저장 쿼리·파이프라인·데이터셋의 목록 응답이 설명 전문을 싣는데 설명에 상한이 없어, 20MB 설명 하나가 모든 사용자의 목록을
 * 부풀렸다. 서버가 2,000자를 넘는 설명을 400 으로 거부하므로, 화면도 같은 길이에서 입력을 막는다(대시보드 WD-425 와 같은 방식 —
 * 입력란 maxlength). 이미 저장된 더 긴 설명은 maxlength 가 자르지 않으므로, zod 폼은 한국어 안내를 띄우고 요청을 보내지 않는다.
 */

/** 입력란이 상한(maxlength)을 갖고, 한 글자 더 긴 값을 넣어도 상한까지만 들어가는지 확인한다. */
async function expectCappedAt(input: Locator) {
  await expect(input).toHaveAttribute('maxlength', String(LIMIT));
  await input.fill('가'.repeat(LIMIT + 1));
  await expect(input).toHaveValue('가'.repeat(LIMIT));
}

test.describe('설명 길이 상한 (WD-520)', () => {
  test('쿼리 저장 다이얼로그 — 설명이 2,000자에서 잘리고, 잘린 값이 그대로 저장 요청에 실린다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewQueryEditorMocks(page);
    const cap = await mockApi(page, 'POST', '/api/v1/analytics/queries', createSavedQuery({ id: 99 }), {
      capture: true,
    });
    // SQL 이 채워진 상태여야 저장 검증을 통과한다(이슈 #195)
    await page.goto('/analytics/queries/new?sql=' + encodeURIComponent('SELECT 1'));
    await page.getByRole('button', { name: '저장' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: '쿼리 저장' })).toBeVisible();

    const description = dialog.getByLabel('설명');
    await expectCappedAt(description);

    await dialog.getByRole('button', { name: '만들기' }).click();
    const req = await cap.waitForRequest();
    expect((req.payload as { description: string }).description).toHaveLength(LIMIT);
  });

  test('파이프라인 편집기 — 설명 입력란이 2,000자에서 잘린다', async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
    await page.goto('/pipelines/new');

    const description = page.locator('#pipeline-description');
    await expectCappedAt(description);
  });

  test('데이터셋 생성 화면 — 설명 입력란이 2,000자에서 잘린다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new');

    const description = page.locator('#description');
    await expectCappedAt(description);
  });

  test('데이터셋 정보 수정 — 예전에 저장된 2,000자 넘는 설명은 안내가 뜨고 수정 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    // 상한이 생기기 전에 저장된 긴 설명 — maxlength 는 이미 있는 값을 자르지 않는다
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets/1',
      createDatasetDetail({ id: 1, description: '가'.repeat(LIMIT + 1) }),
    );
    const put = await mockApi(page, 'PUT', '/api/v1/datasets/1', {}, { capture: true });

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.getByLabel('설명')).toHaveAttribute('maxlength', String(LIMIT));
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('데이터셋 설명은 최대 2,000자까지 입력할 수 있습니다.')).toBeVisible();
    expect(put.requests).toHaveLength(0);
  });

  test('차트 저장 다이얼로그 — 설명이 2,000자에서 잘린다', async ({ authenticatedPage: page }) => {
    await setupChartBuilderMocks(page, 1);
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '차트 수정' });
    await expect(dialog).toBeVisible();

    const description = dialog.getByLabel('설명');
    await expectCappedAt(description);
  });

  test('API 연결 — 생성 다이얼로그와 상세 화면의 설명 입력란이 2,000자에서 잘린다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    // 1) 생성 다이얼로그
    await page.getByRole('button', { name: '새 연결' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('새 API 연결')).toBeVisible();
    const createDescription = dialog.getByLabel('설명 (선택)');
    await expectCappedAt(createDescription);

    // 2) 상세 화면 — 같은 상한
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();
    // 상세 화면도 선택 칸은 라벨에 「(선택)」을 붙인다(WD-666 — 생성 대화상자와 같은 표시)
    const detailDescription = page.getByLabel('설명 (선택)', { exact: true });
    await expectCappedAt(detailDescription);
  });

  test('데이터셋 복제 — 원본의 2,000자 넘는 설명은 안내가 뜨고 복제 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    // 복제 다이얼로그는 원본 설명을 기본값으로 채운다 — 상한 이전에 저장된 긴 설명이면 그대로 들어온다
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets/1',
      createDatasetDetail({ id: 1, description: '가'.repeat(LIMIT + 1) }),
    );
    const clone = await mockApi(page, 'POST', '/api/v1/datasets/1/clone', {}, { capture: true });

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('button', { name: '복제' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: '데이터셋 복제' })).toBeVisible();
    await expect(dialog.getByLabel('설명')).toHaveAttribute('maxlength', String(LIMIT));

    await dialog.getByRole('button', { name: '복제' }).click();
    await expect(dialog.getByText('데이터셋 설명은 최대 2,000자까지 입력할 수 있습니다.')).toBeVisible();
    expect(clone.requests).toHaveLength(0);

    // 설명을 상한 안으로 줄이면 복제 요청이 나간다
    await dialog.getByLabel('설명').fill('짧은 설명');
    await dialog.getByRole('button', { name: '복제' }).click();
    const req = await clone.waitForRequest();
    expect(req.payload).toMatchObject({ description: '짧은 설명' });
  });
});
