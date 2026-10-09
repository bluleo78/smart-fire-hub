import type { Page } from '@playwright/test';

import {
  createCategories,
  createDataset,
  createDatasetDetail,
  createFileDatasetDetail,
} from '../../factories/dataset.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 데이터셋 내보내기 정책 UI(S4, 스펙 §5-4) — exportAllowed 로 다운로드 UI 를 막는다(UI 수준 차단, 서버가 다시 판정).
 * - 주 버튼(데이터 탭 「내보내기」): 비활성 + 사유 툴팁
 * - 보조 다운로드(목록 행 아이콘·선택 행 CSV·오브젝트 다운로드 아이콘): 숨김
 * - exportAllowed 가 없거나 null 이면(구버전 응답) 막는다(fail-closed) — `=== true` 일 때만 허용
 */
const BLOCKED = '보안 등급 정책상 이 데이터는 내보낼 수 없습니다.';

/** 데이터 탭이 있는 TABLE 데이터셋 상세 — exportAllowed 만 바꿔 끼운다(나중 등록 mock 이 우선). */
async function detail(page: Page, exportAllowed: boolean | null | undefined) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets/1',
    createDatasetDetail({ id: 1, name: '인사_평가_2026', securityLevel: createLevelSummary('기밀'), exportAllowed }),
  );
  await setupSecurityLevelMocks(page, {});
}

/** 상세 → 데이터 탭 진입 */
async function openDataTab(page: Page) {
  await page.goto('/data/datasets/1?tab=data');
  await expect(page.getByRole('heading', { name: '인사_평가_2026' })).toBeVisible();
  await page.getByRole('tab', { name: '데이터' }).click();
}

test.describe('데이터셋 내보내기 정책 UI', () => {
  test('exportAllowed=true 면 내보내기 다이얼로그가 열린다(양성 대조)', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await detail(page, true);
    const estimate = await mockApi(
      page,
      'GET',
      '/api/v1/datasets/1/export/estimate',
      { rowCount: 2, async: false, hasGeometryColumn: false, columns: [] },
      { capture: true },
    );
    await openDataTab(page);
    const btn = page.getByRole('button', { name: '내보내기' });
    await expect(btn).toBeEnabled();
    await btn.click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '데이터 내보내기' })).toBeVisible();
    // 다이얼로그가 실제로 추정 API 를 불렀다(열린 척이 아니라 내보내기 흐름에 진입)
    await estimate.waitForRequest();
  });

  test('exportAllowed=false 면 데이터 탭 내보내기는 비활성이고 사유 툴팁이 뜬다', async ({
    authenticatedPage: page,
  }) => {
    await detail(page, false);
    await openDataTab(page);
    const btn = page.getByRole('button', { name: '내보내기' });
    await expect(btn).toBeDisabled();
    // 비활성 버튼은 포인터 이벤트가 없어 래퍼(span role=group)에 호버한다 — 래퍼 이름은 '동작 — 사유'(스크린리더)
    const wrapper = page.getByRole('group', { name: `내보내기 — ${BLOCKED}`, exact: true });
    await wrapper.hover();
    await expect(page.getByRole('tooltip')).toHaveText(BLOCKED);
  });

  test('exportAllowed 가 없으면(구버전 응답) 비활성 — fail-closed', async ({ authenticatedPage: page }) => {
    await detail(page, undefined);
    await openDataTab(page);
    await expect(page.getByRole('button', { name: '내보내기' })).toBeDisabled();
  });

  test('exportAllowed=null 도 비활성 — fail-closed', async ({ authenticatedPage: page }) => {
    await detail(page, null);
    await openDataTab(page);
    await expect(page.getByRole('button', { name: '내보내기' })).toBeDisabled();
  });

  test('선택 행 CSV 내보내기(보조)는 차단이면 숨기고, 허용이면 보인다', async ({ authenticatedPage: page }) => {
    await detail(page, false);
    await openDataTab(page);
    await page.getByRole('checkbox', { name: '전체 선택' }).click();
    // 선택 액션 바 자체는 뜬다(선택 해제는 남음) — 내보내기만 빠진다
    await expect(page.getByText(/개 행 선택됨/)).toBeVisible();
    await expect(page.getByRole('button', { name: '선택 행 CSV 내보내기' })).toHaveCount(0);

    // 양성 대조 — 같은 화면을 허용으로 다시 연다
    await detail(page, true);
    await openDataTab(page);
    await page.getByRole('checkbox', { name: '전체 선택' }).click();
    await expect(page.getByRole('button', { name: '선택 행 CSV 내보내기' })).toBeVisible();
  });

  test('목록 행 다운로드 아이콘은 exportAllowed=false·없음 행에서 숨긴다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await setupSecurityLevelMocks(page, {});
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets',
      createPageResponse([
        createDataset({ id: 1, name: '허용_데이터셋', exportAllowed: true }),
        createDataset({ id: 2, name: '차단_데이터셋', exportAllowed: false }),
        createDataset({ id: 3, name: '구버전_데이터셋', exportAllowed: undefined }),
      ]),
    );
    await page.goto('/data/datasets');
    await expect(page.getByRole('row', { name: /허용_데이터셋/ })).toBeVisible();
    await expect(page.getByRole('row', { name: /허용_데이터셋/ }).getByRole('button', { name: '내보내기' })).toHaveCount(1);
    await expect(page.getByRole('row', { name: /차단_데이터셋/ }).getByRole('button', { name: '내보내기' })).toHaveCount(0);
    await expect(page.getByRole('row', { name: /구버전_데이터셋/ }).getByRole('button', { name: '내보내기' })).toHaveCount(0);
    // 다른 행 액션(프로파일)은 그대로 — 내보내기만 빠진다
    await expect(page.getByRole('row', { name: /차단_데이터셋/ }).getByRole('button', { name: '프로파일' })).toHaveCount(1);
  });
});

/**
 * FILE 데이터셋 오브젝트 열기/다운로드는 window.open 으로 빈 탭을 먼저 연 뒤 presign URL 로 이동시킨다.
 * 실제 팝업을 띄우면 병렬 부하에서 팝업 생성·close 대기가 30초를 넘겨 흔들렸다 — 이 절은 window.open 을
 * 가짜 창(앱 코드가 쓰는 location.href·close 만 가짐)으로 바꾸고, presign 요청의 disposition 과 가짜 창의
 * 이동/닫힘만 단언한다(팝업 자체의 동작은 브라우저 소관).
 */
test.describe('FILE 데이터셋 오브젝트 — inline/attachment 분리', () => {
  const DATASET_ID = 7;

  /** 가짜 창 기록 — 페이지 컨텍스트의 window.__openedWindows 에 쌓인다. */
  type FakeWindow = { href: string; closed: boolean };

  /** 페이지가 연 가짜 창들의 이동 주소·닫힘 여부를 읽는다. */
  async function openedWindows(page: Page): Promise<FakeWindow[]> {
    return page.evaluate(() =>
      ((window as unknown as { __openedWindows?: { location: { href: string }; closed: boolean }[] })
        .__openedWindows ?? []).map((w) => ({ href: w.location.href, closed: w.closed })),
    );
  }

  /** FILE 상세 + 오브젝트 1건 + presign 요청 캡처. window.open 은 가짜 창으로 바꾼다. */
  async function setup(page: Page, exportAllowed: boolean) {
    await page.addInitScript(() => {
      const opened: { location: { href: string }; closed: boolean; close: () => void }[] = [];
      (window as unknown as { __openedWindows: typeof opened }).__openedWindows = opened;
      // openObjectInNewTab 은 반환 창의 location.href 대입과 close() 만 쓴다.
      window.open = (() => {
        const fake = {
          location: { href: '' },
          closed: false,
          close() {
            fake.closed = true;
          },
        };
        opened.push(fake);
        return fake;
      }) as unknown as typeof window.open;
    });
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}`,
      createFileDatasetDetail({ id: DATASET_ID, name: '장비 학습 데이터', originType: 'SOURCE', exportAllowed }),
    );
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [{ key: 'equip/report.md', name: 'report.md', size: 2048, lastModified: null }],
      nextToken: null,
      hasMore: false,
    });
    const presign = await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/objects/url`,
      { url: 'https://example.com/download/report.md', expiresInSeconds: 300 },
      { capture: true },
    );
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();
    await expect(page.getByRole('button', { name: 'report.md' })).toBeVisible();
    return presign;
  }

  test('다운로드 아이콘은 disposition=attachment, 이름 클릭은 inline 으로 presign 을 요청한다', async ({
    authenticatedPage: page,
  }) => {
    const presign = await setup(page, true);

    await page.getByRole('button', { name: '다운로드' }).click();
    await expect.poll(() => presign.requests.length).toBe(1);
    expect(presign.requests[0].searchParams.get('disposition')).toBe('attachment');
    expect(presign.requests[0].searchParams.get('key')).toBe('equip/report.md');

    await page.getByRole('button', { name: 'report.md' }).click();
    await expect.poll(() => presign.requests.length).toBe(2);
    expect(presign.requests[1].searchParams.get('disposition')).toBe('inline');

    // 두 번 모두 미리 연 창을 발급 URL 로 이동시킨다
    await expect
      .poll(async () => (await openedWindows(page)).map((w) => w.href))
      .toEqual(['https://example.com/download/report.md', 'https://example.com/download/report.md']);
  });

  test('exportAllowed=false 면 다운로드 아이콘이 없고 이름 클릭(열기)은 inline 으로 남는다', async ({
    authenticatedPage: page,
  }) => {
    const presign = await setup(page, false);
    await expect(page.getByRole('button', { name: '다운로드' })).toHaveCount(0);

    await page.getByRole('button', { name: 'report.md' }).click();
    await expect.poll(() => presign.requests.length).toBe(1);
    expect(presign.requests[0].searchParams.get('disposition')).toBe('inline');
  });

  test('attachment 발급이 정책 거부(403)면 서버 사유 문구를 토스트로 보인다', async ({ authenticatedPage: page }) => {
    await setup(page, true);
    const message = "'기밀' 등급 데이터는 내보낼 수 없습니다.";
    // 나중 등록 route 가 우선 — 같은 경로를 403 으로 덮는다
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/objects/url`,
      { status: 403, code: 'POLICY_BLOCKED', message, errors: { action: 'EXPORT', policyKey: 'export_policy' } },
      { status: 403 },
    );
    await page.getByRole('button', { name: '다운로드' }).click();
    await expect(page.getByText(message)).toBeVisible();
    // 미리 연 창은 이동 없이 닫힌다
    await expect.poll(() => openedWindows(page)).toEqual([{ href: '', closed: true }]);
  });
});
