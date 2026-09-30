/**
 * 파이프라인 상세 — 편집 취소의 복원 기준(baseline) E2E (#742)
 *
 * 과거에는 취소 시 "페이지 최초 로드 응답"으로 되돌아가, 저장 → 수정 → 취소 → 다른 필드 변경 → 저장 하면
 * 앞서 저장한 값이 PUT 페이로드에 옛 값으로 실려 서버에서 조용히 되돌려졌다.
 * 이 스펙은 세 조건을 검증한다:
 *  (a) 저장 성공 직후 취소 기준 = 저장된 내용 (재조회가 늦어도)
 *  (b) 미저장 변경이 있는 동안 서버 재조회가 편집 상태를 덮지 않는다(취소 기준만 최신화)
 *  (c) 보기 모드에서는 재조회 결과가 화면과 취소 기준에 반영된다
 */

import type { Page, Route } from '@playwright/test';

import type { PipelineDetailResponse, UpdatePipelineRequest } from '@/types/pipeline';

import { createExecution, createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** sql1, py1 두 스텝을 가진 파이프라인 상세 — 설명은 모두 비어 있다 */
function initialDetail(sqlDesc: string | null = null): PipelineDetailResponse {
  return createPipelineDetail({
    id: 1,
    steps: [
      createStep({ id: 11, name: 'sql1', description: sqlDesc, scriptType: 'SQL', scriptContent: 'SELECT 1' }),
      createStep({ id: 12, name: 'py1', description: null, scriptType: 'PYTHON', scriptContent: 'print(0)' }),
    ],
  });
}

/** PUT 페이로드를 서버처럼 상세 응답에 반영한다 — 스텝 설명·스크립트만 다룬다 */
function applyPut(detail: PipelineDetailResponse, body: UpdatePipelineRequest): PipelineDetailResponse {
  return {
    ...detail,
    steps: detail.steps.map((s) => {
      const req = body.steps.find((r) => r.name === s.name);
      return req
        ? { ...s, description: req.description ?? null, scriptContent: req.scriptContent ?? s.scriptContent }
        : s;
    }),
  };
}

async function mockCommon(page: Page) {
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
  });
}

const node = (page: Page, name: string) => page.locator('.react-flow__node', { hasText: name });
const stepDesc = (page: Page) => page.locator('#step-description');

test.describe('파이프라인 상세 — 편집 취소 복원 기준 (#742)', () => {
  /**
   * (a) 저장 → 수정 → 취소 → 다른 필드 변경 → 저장. 서버는 PUT 을 반영하는 상태 기반 모킹.
   * 두 번째 PUT 에 첫 저장 값이 그대로 실려야 한다(조용한 되돌림 방지).
   */
  for (const refetch of ['즉시 반영', '저장 후 재조회가 늦게 도착'] as const) {
    test(`저장 후 수정→취소해도 방금 저장한 값이 유지되고 이어진 저장이 덮어쓰지 않는다 (${refetch})`, async ({
      authenticatedPage: page,
    }) => {
      await mockCommon(page);
      await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);

      let server = initialDetail();
      const puts: UpdatePipelineRequest[] = [];
      // 재조회 지연 모드: 첫 PUT 이후의 상세 GET 은 테스트 끝까지 응답을 보류한다
      let releaseGet: () => void = () => {};
      const getGate = new Promise<void>((resolve) => (releaseGet = resolve));

      await page.route(
        (url) => url.pathname === '/api/v1/pipelines/1',
        async (route: Route) => {
          const method = route.request().method();
          if (method === 'PUT') {
            const body = route.request().postDataJSON() as UpdatePipelineRequest;
            puts.push(body);
            server = applyPut(server, body);
            return route.fulfill({ status: 204, body: '' });
          }
          if (method !== 'GET') return route.fallback();
          const snapshot = server;
          if (refetch === '저장 후 재조회가 늦게 도착' && puts.length > 0) await getGate;
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(snapshot) });
        },
      );

      await page.goto('/pipelines/1');
      const saveButton = page.getByRole('button', { name: '저장', exact: true });

      // 1) 수정 → sql1 설명 입력 → 저장
      await page.getByRole('button', { name: '수정' }).click();
      await node(page, 'sql1').click();
      await stepDesc(page).fill('saved-desc');
      await saveButton.click();
      await expect.poll(() => puts.length).toBe(1);
      await expect(page.getByRole('button', { name: '수정' })).toBeVisible();

      // 2) 수정 → 아무것도 안 바꾸고 취소 → 보기 모드에서 저장한 값이 보여야 한다
      await page.getByRole('button', { name: '수정' }).click();
      await page.getByRole('button', { name: '취소', exact: true }).click();
      await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
      await node(page, 'sql1').click();
      await expect(stepDesc(page)).toHaveValue('saved-desc');

      // 3) 수정 → py1 설명 변경 → 저장: 앞서 저장한 sql1 설명이 페이로드에 유지되어야 한다
      await page.getByRole('button', { name: '수정' }).click();
      await node(page, 'py1').click();
      await stepDesc(page).fill('py-desc');
      await saveButton.click();
      await expect.poll(() => puts.length).toBe(2);
      const second = puts[1];
      expect(second.steps.find((s) => s.name === 'sql1')?.description).toBe('saved-desc');
      expect(second.steps.find((s) => s.name === 'py1')?.description).toBe('py-desc');

      releaseGet();
    });
  }

  /**
   * 실행 목록 폴링이 RUNNING → COMPLETED 를 보면 상세가 재조회된다(#733). 재조회 응답에는
   * "다른 탭에서 바뀐" sql1 설명이 담겨 있다. 첫 번째 상세 GET 만 옛 값, 이후는 새 값.
   */
  async function mockRefetchAfterRun(page: Page) {
    await mockCommon(page);
    let listCalls = 0;
    let detailCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1' || url.pathname === '/api/v1/pipelines/1/executions',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const isList = new URL(route.request().url()).pathname.endsWith('/executions');
        if (isList) {
          listCalls += 1;
          const status = listCalls === 1 ? 'RUNNING' : 'COMPLETED';
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify([createExecution({ id: 475, status })]),
          });
        }
        detailCalls += 1;
        const body = detailCalls === 1 ? initialDetail('old-desc') : initialDetail('other-tab');
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );
    return { detailCalls: () => detailCalls };
  }

  test('편집 중(미저장 변경 있음)에는 재조회가 입력을 덮지 않고, 취소하면 최신 서버 값으로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    const mock = await mockRefetchAfterRun(page);
    await page.goto('/pipelines/1');

    await page.getByRole('button', { name: '수정' }).click();
    await node(page, 'sql1').click();
    await expect(stepDesc(page)).toHaveValue('old-desc');
    await stepDesc(page).fill('my-edit');

    // 폴링(5초) 뒤 상세가 다시 조회될 때까지 기다린다 — 편집 중 입력은 그대로여야 한다
    await expect.poll(() => mock.detailCalls(), { timeout: 15_000 }).toBeGreaterThanOrEqual(2);
    await page.waitForTimeout(300);
    await expect(stepDesc(page)).toHaveValue('my-edit');
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeEnabled();

    // 취소(확인) → 최초 로드 값이 아니라 재조회된 최신 값으로 돌아가야 한다
    await page.getByRole('button', { name: '취소', exact: true }).click();
    await page.getByRole('button', { name: '변경사항 취소' }).click();
    await node(page, 'sql1').click();
    await expect(stepDesc(page)).toHaveValue('other-tab');
  });

  test('보기 모드에서는 재조회 결과가 열린 스텝 패널과 취소 기준에 반영된다', async ({ authenticatedPage: page }) => {
    await mockRefetchAfterRun(page);
    await page.goto('/pipelines/1');

    await node(page, 'sql1').click();
    await expect(stepDesc(page)).toHaveValue('old-desc');

    // 폴링 뒤 재조회 — 패널이 닫히지 않고 새 값이 보여야 한다
    await expect(stepDesc(page)).toHaveValue('other-tab', { timeout: 15_000 });

    // 수정 → 변경 → 취소(확인) 해도 최신 서버 값으로 돌아가야 한다
    await page.getByRole('button', { name: '수정' }).click();
    await stepDesc(page).fill('temp');
    await page.getByRole('button', { name: '취소', exact: true }).click();
    await page.getByRole('button', { name: '변경사항 취소' }).click();
    await node(page, 'sql1').click();
    await expect(stepDesc(page)).toHaveValue('other-tab');
  });
});
