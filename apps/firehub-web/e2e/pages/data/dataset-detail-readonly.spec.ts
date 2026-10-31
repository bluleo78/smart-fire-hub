import type { Page } from '@playwright/test';

import { createAdminUserDetail } from '../../factories/auth.factory';
import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
import { createDocument } from '../../factories/document.factory';
import { createSearchIndexStatus } from '../../factories/search-index.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { DATASET_WRITE_PERMISSIONS, grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { setupDocumentDatasetMocks } from '../../fixtures/document.fixture';

/**
 * 데이터셋 상세 — 권한별 쓰기 진입점 노출 (WD-139)
 *
 * 조회 권한만 있는 기본 사용자(dataset:read·data:read)가 「복제」「수정」「행 추가」 등을 누르면 다이얼로그를 다 채운 뒤
 * 403 토스트를 받았다. 진입점마다 서버 컨트롤러가 요구하는 권한(dataset:write·data:import·data:delete·pipeline:write)이
 * 있을 때만 보이는지, 조회 기능(값 표시·즐겨찾기·내보내기·선택)은 그대로인지 검증한다.
 *
 * 부재 단언이 공허해지지 않도록 ① 권한 응답을 기다린 뒤 ② 화면 값(이름·셀)이 그려진 것을 먼저 확인하고 나서 숨김을 본다.
 */

const DATASET_ID = 1;
/** 기본 USER 역할의 데이터셋 관련 권한 — 조회 전용 */
const READ_ONLY = ['dataset:read', 'data:read'];

/** 권한 응답을 기다리며 상세 페이지를 연다 — 권한이 반영되기 전의 "숨김"을 결과로 착각하지 않기 위함. */
async function openDetail(page: Page, permissions: string[], tab?: string) {
  await grantDatasetPermissions(page, permissions);
  await setupDatasetDetailMocks(page, DATASET_ID);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample']);
  const permissionsLoaded = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
  await page.goto(`/data/datasets/${DATASET_ID}${tab ? `?tab=${tab}` : ''}`);
  await permissionsLoaded;
  await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
}

/** 데이터 탭 — 모킹한 행 값이 셀에 그려질 때까지 기다린다. */
async function expectRowsRendered(page: Page) {
  await expect(page.getByRole('cell', { name: '항목 1' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '항목 2' })).toBeVisible();
}

test.describe('데이터셋 상세 — 조회 전용 사용자 (WD-139)', () => {
  test('헤더·정보 탭: 값은 보이고 복제·태그 추가/제거·수정은 없으며 즐겨찾기는 된다', async ({ authenticatedPage: page }) => {
    const favorite = await mockApi(page, 'PUT', `/api/v1/datasets/${DATASET_ID}/favorite`, { favorited: true }, { capture: true });
    await openDetail(page, READ_ONLY);

    // 조회 값은 그대로 — 테이블명·태그·기본 정보
    await expect(page.getByText('test_dataset').first()).toBeVisible();
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '테스트' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '기본 정보' })).toBeVisible();
    await expect(page.getByText('테스트용 데이터셋')).toBeVisible();

    // 쓰기 진입점(서버 dataset:write)은 없다
    await expect(page.getByRole('button', { name: '복제' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '태그 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '태그 제거' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '수정' })).toHaveCount(0);

    // 즐겨찾기는 dataset:read 로 허용 — 그대로 동작한다
    await page.getByRole('button', { name: '즐겨찾기 추가' }).click();
    const req = await favorite.waitForRequest();
    expect(req.url.pathname).toBe(`/api/v1/datasets/${DATASET_ID}/favorite`);
  });

  test('필드 탭: 필드는 보이고 기본 키 설정·필드 추가·행 작업이 없으며 설명 칸을 눌러도 편집되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, READ_ONLY, 'columns');

    await expect(page.getByRole('heading', { name: /필드 목록 \(2개\)/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: '이름', exact: true })).toBeVisible();
    // 설명 값은 글로 보인다(두 필드 모두 설명이 '기본 키'라 첫 행을 쓴다)
    const description = page.getByRole('cell', { name: '기본 키', exact: true }).first();
    await expect(description).toBeVisible();

    await expect(page.getByRole('button', { name: '기본 키 설정' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '필드 추가' })).toHaveCount(0);
    await expect(page.getByRole('columnheader', { name: '작업' })).toHaveCount(0);
    for (const name of ['컬럼 위로 이동', '컬럼 아래로 이동', '컬럼 편집', '컬럼 삭제']) {
      await expect(page.getByRole('button', { name })).toHaveCount(0);
    }

    // 설명 인라인 편집(PUT 컬럼=dataset:write)도 열리지 않는다 — 연필만 숨기고 클릭 편집이 남는 회귀를 막는다
    await description.click();
    await expect(page.getByRole('table').getByRole('textbox')).toHaveCount(0);
  });

  test('데이터 탭: 행은 보이고 SQL·행 추가·임포트·API 가져오기·행 수정·선택 삭제가 없으며 내보내기·선택은 된다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, READ_ONLY, 'data');
    await expectRowsRendered(page);

    await expect(page.getByRole('button', { name: '내보내기' })).toBeVisible();
    for (const name of ['SQL', '행 추가', '임포트', 'API 가져오기']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }

    // 더블클릭 행 수정(PUT 행=data:import)은 열리지 않는다
    await page.getByRole('cell', { name: '항목 1' }).dblclick();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // 선택은 CSV 내보내기에 쓰이므로 남지만 삭제(data:delete)는 없다
    await page.getByRole('checkbox', { name: '행 1 선택' }).check();
    await expect(page.getByText('1개 행 선택됨')).toBeVisible();
    await expect(page.getByRole('button', { name: '선택 행 CSV 내보내기' })).toBeVisible();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0);
  });

  test('검색 탭: 색인 상태·선택 필드는 보이고 체크는 잠기며 저장·다시 색인이 없다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/search-index`,
      createSearchIndexStatus({ enabled: true, fields: ['name'], status: 'IDLE', indexedRows: 2, totalRows: 2 }),
    );
    await openDetail(page, READ_ONLY, 'search');

    await expect(page.getByTestId('search-index-status')).toHaveText(/사용 가능/);
    // 고를 수 없는 사용자에게 "선택할 수 있습니다" 같은 행동 안내 대신 현재 설정을 설명한다
    await expect(page.getByText('체크된 필드가 현재 검색 대상이며')).toBeVisible();
    await expect(page.getByText('필드만 선택할 수 있습니다')).toHaveCount(0);
    // 저장된 검색 필드(name=이름)는 체크된 채 보이되 잠긴다
    const nameField = page.getByRole('checkbox', { name: '이름' });
    await expect(nameField).toBeChecked();
    await expect(nameField).toBeDisabled();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '다시 색인' })).toHaveCount(0);
  });

  test('상태 변경은 역할이 아니라 dataset:write 로 판정한다 — ADMIN 역할이어도 권한이 없으면 보이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/users/me', createAdminUserDetail());
    await openDetail(page, READ_ONLY);
    // ADMIN 으로 인식됐는지 먼저 확인 — 사이드바는 ADMIN 이면 권한 코드 없이도 관리 메뉴(user:read)를 보인다
    await expect(page.getByRole('link', { name: '사용자 관리' })).toBeVisible();
    await expect(page.getByRole('button', { name: '상태 변경' })).toHaveCount(0);
  });
});

test.describe('데이터셋 상세 — 쓰기 권한 사용자 (WD-139)', () => {
  test('모든 쓰기 진입점이 보이고 태그 추가는 입력한 이름으로 요청된다', async ({ authenticatedPage: page }) => {
    const addTag = await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/tags`, null, { capture: true });
    await openDetail(page, DATASET_WRITE_PERMISSIONS);

    await expect(page.getByRole('button', { name: '복제' })).toBeVisible();
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    await expect(page.getByRole('button', { name: '태그 제거' }).first()).toBeVisible();

    await page.getByRole('button', { name: '태그 추가' }).click();
    await page.getByPlaceholder('태그 입력...').fill('신규태그');
    await page.getByPlaceholder('태그 입력...').press('Enter');
    const req = await addTag.waitForRequest();
    expect(req.payload).toEqual({ tagName: '신규태그' });
  });

  test('필드 탭: 기본 키 설정·필드 추가·행 작업이 보이고 설명 칸을 누르면 편집 입력이 열린다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, DATASET_WRITE_PERMISSIONS, 'columns');

    await expect(page.getByRole('button', { name: '기본 키 설정' })).toBeVisible();
    await expect(page.getByRole('button', { name: '필드 추가' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '작업' })).toBeVisible();
    await expect(page.getByRole('button', { name: '컬럼 편집' })).toHaveCount(2);

    await page.getByText('기본 키', { exact: true }).first().click();
    await expect(page.getByRole('table').getByRole('textbox')).toHaveValue('기본 키');
  });

  test('데이터 탭: SQL·행 추가·임포트·API 가져오기가 보이고 더블클릭은 행 편집, 선택하면 삭제가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, DATASET_WRITE_PERMISSIONS, 'data');
    await expectRowsRendered(page);

    for (const name of ['SQL', '행 추가', '임포트', 'API 가져오기']) {
      await expect(page.getByRole('button', { name, exact: true })).toBeVisible();
    }
    await page.getByRole('checkbox', { name: '행 1 선택' }).check();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toBeVisible();

    await page.getByRole('cell', { name: '항목 2' }).dblclick();
    await expect(page.getByRole('dialog').getByText('행 편집 (ID: 2)')).toBeVisible();
  });
});

test.describe('데이터셋 상세 — 부분 권한 (WD-139)', () => {
  test('ADMIN 이 아니어도 dataset:write 가 있으면 상태 변경이 보이고 선택한 상태로 저장된다', async ({ authenticatedPage: page }) => {
    const status = await mockApi(page, 'PUT', `/api/v1/datasets/${DATASET_ID}/status`, null, { capture: true });
    await openDetail(page, [...READ_ONLY, 'dataset:write']);
    // 기본 사용자(USER 역할)라 관리 메뉴는 없다 — 역할 덕분에 보이는 것이 아님을 확인
    await expect(page.getByRole('link', { name: '사용자 관리' })).toHaveCount(0);

    await page.getByRole('button', { name: '상태 변경' }).click();
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: /사용 중단/ }).click();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await status.waitForRequest();
    expect(req.payload).toMatchObject({ status: 'DEPRECATED' });
  });

  test('dataset:write 만 있으면 정의 편집은 되고 행 적재·삭제·API 가져오기는 숨는다', async ({ authenticatedPage: page }) => {
    await openDetail(page, [...READ_ONLY, 'dataset:write'], 'columns');
    await expect(page.getByRole('button', { name: '필드 추가' })).toBeVisible();
    await expect(page.getByRole('button', { name: '복제' })).toBeVisible();

    await page.getByRole('tab', { name: '데이터' }).click();
    await expectRowsRendered(page);
    for (const name of ['SQL', '행 추가', '임포트', 'API 가져오기']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }
    await page.getByRole('checkbox', { name: '행 1 선택' }).check();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0);
  });

  test('data:import 만 있으면 행 추가·임포트·SQL·행 수정은 되고 선택 삭제·API 가져오기·정의 편집은 숨는다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, [...READ_ONLY, 'data:import'], 'data');
    await expectRowsRendered(page);

    for (const name of ['SQL', '행 추가', '임포트']) {
      await expect(page.getByRole('button', { name, exact: true })).toBeVisible();
    }
    await expect(page.getByRole('button', { name: 'API 가져오기' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '복제' })).toHaveCount(0);

    await page.getByRole('checkbox', { name: '행 1 선택' }).check();
    await expect(page.getByText('1개 행 선택됨')).toBeVisible();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0);

    await page.getByRole('cell', { name: '항목 1' }).dblclick();
    await expect(page.getByRole('dialog').getByText('행 편집 (ID: 1)')).toBeVisible();
  });

  test('data:delete 만 있으면 선택 삭제만 보이고 행 추가·임포트는 숨는다', async ({ authenticatedPage: page }) => {
    await openDetail(page, [...READ_ONLY, 'data:delete'], 'data');
    await expectRowsRendered(page);

    await expect(page.getByRole('button', { name: '행 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '임포트', exact: true })).toHaveCount(0);
    await page.getByRole('checkbox', { name: '행 1 선택' }).check();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toBeVisible();
  });

  test('pipeline:write 만 있으면 API 가져오기만 보인다', async ({ authenticatedPage: page }) => {
    await openDetail(page, [...READ_ONLY, 'pipeline:write'], 'data');
    await expectRowsRendered(page);

    await expect(page.getByRole('button', { name: 'API 가져오기' })).toBeVisible();
    await expect(page.getByRole('button', { name: '행 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '임포트', exact: true })).toHaveCount(0);
  });
});

test.describe('데이터셋 상세 — 문서·오브젝트 탭 권한 (WD-139)', () => {
  /** 문서 데이터셋 상세를 권한 응답까지 기다려 연다. */
  async function openDocuments(page: Page, permissions: string[], documents = [createDocument({ id: 7, originalName: 'manual.pdf' })]) {
    await grantDatasetPermissions(page, permissions);
    await setupDocumentDatasetMocks(page, DATASET_ID, documents);
    const permissionsLoaded = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
    await permissionsLoaded;
  }

  test('문서: data:import 만 있으면 업로드는 보이고 삭제(dataset:write)는 숨는다', async ({ authenticatedPage: page }) => {
    await openDocuments(page, [...READ_ONLY, 'data:import']);
    await expect(page.getByRole('cell', { name: 'manual.pdf', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: '문서 업로드' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'manual.pdf 삭제' })).toHaveCount(0);
  });

  test('문서: dataset:write 만 있으면 삭제는 보이고 업로드는 숨는다', async ({ authenticatedPage: page }) => {
    await openDocuments(page, [...READ_ONLY, 'dataset:write']);
    await expect(page.getByRole('cell', { name: 'manual.pdf', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'manual.pdf 삭제' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '문서 업로드' })).toHaveCount(0);
  });

  test('문서: 조회 전용이면 빈 목록에 업로드를 권하는 안내도 없다', async ({ authenticatedPage: page }) => {
    await openDocuments(page, READ_ONLY, []);
    await expect(page.getByTestId('documents-empty-state')).toContainText('업로드된 문서가 없습니다');
    await expect(page.getByText('위의 업로드 영역에서', { exact: false })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: '문서 업로드' })).toHaveCount(0);
  });

  test('오브젝트: 조회 전용이면 목록은 보이고 업로드 드롭존·폴더 선택이 없다', async ({ authenticatedPage: page }) => {
    const FILE_ID = 7;
    await grantDatasetPermissions(page, READ_ONLY);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${FILE_ID}`,
      createDatasetDetail({ id: FILE_ID, storageType: 'FILE', columns: [], rowCount: null }),
    );
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${FILE_ID}/objects`, {
      objects: [{ key: 'equip/보고서.md', name: '보고서.md', size: 2048, lastModified: '2026-07-20T00:00:00Z' }],
      nextToken: null,
      hasMore: false,
    });
    const permissionsLoaded = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
    await page.goto(`/data/datasets/${FILE_ID}?tab=objects`);
    await permissionsLoaded;

    await expect(page.getByText('보고서.md', { exact: true })).toBeVisible();
    await expect(page.getByText('파일·폴더를 드래그하거나 클릭하여 업로드')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '또는 폴더 선택' })).toHaveCount(0);
  });
});
