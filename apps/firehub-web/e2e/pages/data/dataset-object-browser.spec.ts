/**
 * FILE 데이터셋 오브젝트 탭 — 폴더 탐색 + 이름 검색 (WD-761, A안: 폴더 이동 + 경로 표시)
 *
 * 검증 범위:
 * - 폴더 모드: 기본 목록은 현재 폴더(?path=)의 하위 폴더 + 파일. 폴더를 누르면 들어가고, 경로 표시(전체 › 제품 › …)로 올라온다.
 *   현재 폴더는 URL ?path= 에 남아 브라우저 뒤로가기·직접 진입이 된다(다른 쿼리 ?tab= 는 지우지 않는다).
 * - 검색 모드: 검색어를 넣으면 서버에 q 로 묻고 전체 상대 경로로 결과를 보인다. 결과를 누르면 그 파일이 있는 폴더로 간다.
 *   서버가 검색 상한에 닿았다고(truncated) 하면 「일부만 검색됨」 안내를 보이고 이어 보기를 띄우지 않는다.
 * - 업로드: 지금 보는 폴더로 올린다(upload-urls 본문 path). 폴더 업로드는 하위 상대 경로를 유지한다.
 * - 오류: 서버가 경로를 거부(400)하면 서버 문구를 화면에 보인다.
 *
 * 서버(firehub-api)는 모킹한다 — 실제 서버 없이 API 계약(GET /objects?path=&q=&token=, POST upload-urls {path, files})만 따른다.
 */
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import type { Page } from '@playwright/test';

import type { ObjectItem, ObjectListResponse } from '../../../src/api/objects';
import { createCategories, createFileDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

const DATASET_ID = 7;
const PREFIX = 'equip/';

/** 파일 한 건 — name 은 데이터셋 prefix 를 뺀 전체 상대 경로(모든 모드 공통 계약), key 는 prefix 를 붙인 전체 키. */
function file(name: string, size = 2048): ObjectItem {
  return { key: `${PREFIX}${name}`, name, size, lastModified: '2026-07-20T00:00:00Z' };
}

/** 폴더 모드 한 페이지 응답 — folders 의 path 는 상대 경로 + 끝 '/', name 은 마지막 세그먼트. */
function folderPage(folders: string[], objects: ObjectItem[], extra: Partial<ObjectListResponse> = {}): ObjectListResponse {
  return {
    folders: folders.map((p) => ({ path: p, name: p.replace(/\/$/, '').split('/').pop()! })),
    objects,
    nextToken: null,
    hasMore: false,
    truncated: false,
    ...extra,
  };
}

/** 모킹 폴더 트리 — 키는 서버에 넘어오는 ?path= 값(루트는 ''). */
const TREE: Record<string, ObjectListResponse> = {
  '': folderPage(['제품/', '문서/'], [file('readme.txt', 100)]),
  제품: folderPage(['제품/Tibero7/'], [file('제품/개요.pdf')]),
  '제품/Tibero7': folderPage([], [file('제품/Tibero7/설치.pdf', 1048576)]),
  문서: folderPage([], []),
};

interface ObjectsMock {
  /** 목록 GET 요청의 쿼리 파라미터를 순서대로 기록 */
  requests: URLSearchParams[];
}

/**
 * FILE 데이터셋 상세 + 오브젝트 목록을 모킹한다.
 * @param search - q 가 있을 때의 응답(검색 모드). 없으면 빈 결과.
 * @param tree - 폴더 모드 응답(path → 응답). 트리에 없는 폴더는 빈 폴더.
 */
async function setupFileDataset(
  page: Page,
  options: {
    search?: (q: string, token: string | null) => ObjectListResponse;
    tree?: Record<string, ObjectListResponse | ((token: string | null) => ObjectListResponse)>;
  } = {},
): Promise<ObjectsMock> {
  const tree = options.tree ?? TREE;
  await mockApi(
    page,
    'GET',
    `/api/v1/datasets/${DATASET_ID}`,
    createFileDatasetDetail({ id: DATASET_ID, name: '장비 학습 데이터', originType: 'SOURCE', exportAllowed: true }),
  );
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

  const requests: URLSearchParams[] = [];
  await page.route(
    (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects`,
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const params = new URL(route.request().url()).searchParams;
      requests.push(params);
      const q = params.get('q');
      const token = params.get('token');
      let body: ObjectListResponse;
      if (q) {
        body = options.search ? options.search(q, token) : folderPage([], []);
      } else {
        const entry = tree[params.get('path') ?? ''];
        body = typeof entry === 'function' ? entry(token) : (entry ?? folderPage([], []));
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    },
  );
  return { requests };
}

/** 현재 URL 의 쿼리 값 — ?path= 가 인코딩돼 있어 문자열 비교 대신 디코드한 값을 본다. */
function queryOf(page: Page, name: string): string | null {
  return new URL(page.url()).searchParams.get(name);
}

/** 목록 영역(폴더·파일 행) — 경로 표시의 같은 이름 버튼과 구분한다. */
function objectList(page: Page) {
  return page.getByTestId('object-list');
}

/** 경로 표시(전체 › 제품 › …) */
function breadcrumb(page: Page) {
  return page.getByRole('navigation', { name: '폴더 경로' });
}

/** upload-urls 요청 본문 — 올릴 폴더(path, 루트면 없음)와 파일 상대 경로들 */
type UploadBody = { path?: string; files: { filename: string }[] };

/**
 * upload-urls 와 MinIO PUT 을 모킹하고 요청 본문·PUT URL 을 모은다. 대상 키는 서버 계약대로 prefix + path/ + filename,
 * PUT URL 은 묶음 안 순서(put/<i>)다.
 * @param rejectReason - 파일 이름을 받아 서버가 그 파일만 거부할 사유를 준다(WD-776 — 그 자리는 key·uploadUrl 없이 error 만). 없으면 모두 발급.
 * @param putStatus - n 번째(0부터) MinIO PUT 의 응답 상태 — 기본 200.
 */
async function mockUploadUrls(
  page: Page,
  options: { rejectReason?: (filename: string) => string | undefined; putStatus?: (n: number) => number } = {},
) {
  const payloads: UploadBody[] = [];
  const puts: string[] = [];
  await page.route(
    (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects/upload-urls`,
    (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      const body = route.request().postDataJSON() as UploadBody;
      payloads.push(body);
      const base = body.path ? `${body.path}/` : '';
      const targets = body.files.map((f, i) => {
        const error = options.rejectReason?.(f.filename);
        return error ? { error } : { key: `${PREFIX}${base}${f.filename}`, uploadUrl: `https://minio.example.com/put/${i}` };
      });
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ targets, expiresInSeconds: 900 }),
      });
    },
  );
  await page.route('https://minio.example.com/**', (route) => {
    const status = options.putStatus?.(puts.length) ?? 200;
    puts.push(route.request().url());
    return route.fulfill({ status, body: '' });
  });
  return { payloads, puts };
}

/** 업로드 드롭존 — 누르면 파일 선택, 안에 파일 인풋이 있다. 문구 앞에 올라갈 폴더가 붙을 수 있다. */
function dropzone(page: Page) {
  return page.getByRole('button', { name: /파일·폴더를 드래그하거나 클릭하여 업로드/ });
}

/** 드롭존의 파일 인풋으로 파일을 고른다(= 업로드 시작). */
function chooseFiles(page: Page, files: Parameters<ReturnType<Page['locator']>['setInputFiles']>[0]) {
  return dropzone(page).locator('input[type="file"]').setInputFiles(files);
}

test.describe('FILE 오브젝트 탭 — 폴더 탐색', () => {
  test('폴더를 눌러 들어가고 경로 표시로 올라온다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    const mock = await setupFileDataset(page);
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);

    // 루트: 폴더 행이 먼저, 그다음 파일 행. 폴더 행의 크기·수정일 칸은 「—」.
    const list = objectList(page);
    await expect(list.getByRole('button', { name: '제품' })).toBeVisible();
    await expect(list.getByRole('button', { name: '문서' })).toBeVisible();
    await expect(list.getByRole('button', { name: 'readme.txt' })).toBeVisible();
    await expect(list.getByTestId('folder-row').first()).toContainText('—');
    const rowTexts = await list.locator('[data-testid="folder-row"], [data-testid="file-row"]').allInnerTexts();
    expect(rowTexts[0]).toContain('제품');
    expect(rowTexts[1]).toContain('문서');
    expect(rowTexts[2]).toContain('readme.txt');
    // 기본 요청은 폴더 모드 — path·q·recursive 를 싣지 않는다.
    expect(mock.requests[0].get('path')).toBeNull();
    expect(mock.requests[0].get('q')).toBeNull();
    expect(mock.requests[0].get('recursive')).toBeNull();
    // 루트에서는 경로 표시가 「전체」 하나뿐이고 그것이 현재 위치다.
    await expect(breadcrumb(page).getByRole('button', { name: '전체' })).toHaveAttribute('aria-current', 'page');

    // 폴더 들어가기 → URL ?path=제품(탭 쿼리 유지) + 서버에 path=제품 으로 묻는다.
    await list.getByRole('button', { name: '제품' }).click();
    await expect.poll(() => queryOf(page, 'path')).toBe('제품');
    expect(queryOf(page, 'tab')).toBe('objects');
    await expect.poll(() => mock.requests.some((r) => r.get('path') === '제품')).toBe(true);
    // 파일 이름은 폴더 안 이름(마지막 세그먼트)만, title 은 전체 키.
    const overview = list.getByRole('button', { name: '개요.pdf', exact: true });
    await expect(overview).toBeVisible();
    await expect(overview).toHaveAttribute('title', 'equip/제품/개요.pdf');
    await expect(list.getByText('제품/개요.pdf', { exact: true })).toHaveCount(0);
    await expect(breadcrumb(page).getByRole('button', { name: '제품' })).toHaveAttribute('aria-current', 'page');
    await expect(breadcrumb(page).getByRole('button', { name: '전체' })).not.toHaveAttribute('aria-current', 'page');

    // 한 단계 더 → 경로 표시 「전체 › 제품 › Tibero7」
    await list.getByRole('button', { name: 'Tibero7' }).click();
    await expect.poll(() => queryOf(page, 'path')).toBe('제품/Tibero7');
    await expect(list.getByRole('button', { name: '설치.pdf', exact: true })).toBeVisible();
    await expect(list.getByText('1.0 MB')).toBeVisible();
    await expect(breadcrumb(page).getByRole('button')).toHaveText(['전체', '제품', 'Tibero7']);
    await expect(breadcrumb(page).getByRole('button', { name: 'Tibero7' })).toHaveAttribute('aria-current', 'page');

    // 경로 표시의 중간 조각으로 올라온다.
    await breadcrumb(page).getByRole('button', { name: '제품' }).click();
    await expect.poll(() => queryOf(page, 'path')).toBe('제품');
    await expect(list.getByRole('button', { name: '개요.pdf', exact: true })).toBeVisible();

    // 「전체」로 루트에 돌아오면 ?path= 가 사라진다(탭 쿼리는 남는다).
    await breadcrumb(page).getByRole('button', { name: '전체' }).click();
    await expect(list.getByRole('button', { name: 'readme.txt' })).toBeVisible();
    expect(queryOf(page, 'path')).toBeNull();
    expect(queryOf(page, 'tab')).toBe('objects');
  });

  test('브라우저 뒤로가기로 이전 폴더로 돌아간다', async ({ authenticatedPage: page }) => {
    await setupFileDataset(page);
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    const list = objectList(page);

    await list.getByRole('button', { name: '제품' }).click();
    await expect(list.getByRole('button', { name: '개요.pdf', exact: true })).toBeVisible();
    await list.getByRole('button', { name: 'Tibero7' }).click();
    await expect(list.getByRole('button', { name: '설치.pdf', exact: true })).toBeVisible();

    // 폴더 이동은 기록에 쌓이므로(push) 뒤로가기가 한 단계씩 올라간다 — 오브젝트 탭에 머문다.
    await page.goBack();
    await expect.poll(() => queryOf(page, 'path')).toBe('제품');
    await expect(list.getByRole('button', { name: '개요.pdf', exact: true })).toBeVisible();
    await page.goBack();
    await expect.poll(() => queryOf(page, 'path')).toBeNull();
    await expect(list.getByRole('button', { name: 'readme.txt' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '오브젝트' })).toHaveAttribute('aria-selected', 'true');
  });

  test('?path= 로 바로 들어오면 그 폴더를 보이고, 빈 폴더는 폴더 안내를 보인다', async ({ authenticatedPage: page }) => {
    const mock = await setupFileDataset(page);
    // 앞뒤 슬래시가 붙은 값도 같은 폴더로 맞춰 서버에 넘긴다.
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('/문서/')}`);

    await expect(page.getByText('이 폴더에 파일이 없습니다.')).toBeVisible();
    await expect(page.getByText('오브젝트가 없습니다.')).toHaveCount(0);
    await expect.poll(() => mock.requests.map((r) => r.get('path'))).toContain('문서');
    await expect(breadcrumb(page).getByRole('button')).toHaveText(['전체', '문서']);
  });

  test('루트가 비었으면 기존 빈 안내(오브젝트가 없습니다.)를 보인다', async ({ authenticatedPage: page }) => {
    await setupFileDataset(page, { tree: { '': folderPage([], []) } });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);

    await expect(page.getByText('오브젝트가 없습니다.')).toBeVisible();
    await expect(page.getByText('이 폴더에 파일이 없습니다.')).toHaveCount(0);
  });

  test('더 보기는 같은 폴더 path 와 nextToken 으로 다음 페이지를 이어 받는다', async ({ authenticatedPage: page }) => {
    // 첫 페이지 경계가 폴더 prefix 에 걸린 경우 — 토큰은 그 폴더 prefix 다(서버가 다음 페이지에 다시 주지 않는다).
    const mock = await setupFileDataset(page, {
      tree: {
        '': folderPage(['제품/'], []),
        제품: (token) =>
          token === null
            ? folderPage(['제품/A/'], [file('제품/a.txt')], { hasMore: true, nextToken: '제품/a.txt' })
            : folderPage(['제품/B/'], [file('제품/b.txt')]),
      },
    });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('제품')}`);
    const list = objectList(page);
    await expect(list.getByRole('button', { name: 'a.txt', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(list.getByRole('button', { name: 'b.txt', exact: true })).toBeVisible();
    const next = mock.requests.find((r) => r.get('token') !== null)!;
    expect(next.get('token')).toBe('제품/a.txt');
    expect(next.get('path')).toBe('제품');
    // 불러온 폴더 행이 모두 파일 행보다 먼저 온다.
    const rowTexts = await list.locator('[data-testid="folder-row"], [data-testid="file-row"]').allInnerTexts();
    expect(rowTexts.map((t) => t.split('\n')[0].trim())).toEqual(['A', 'B', 'a.txt', 'b.txt']);
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
  });

  test('다음 페이지만 실패하면 이미 불러온 목록은 남기고 오류를 알리며, 더 보기로 다시 시도할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupFileDataset(page, {
      tree: {
        '': (token) =>
          token === null
            ? folderPage(['제품/'], [file('a.txt')], { hasMore: true, nextToken: 'a.txt' })
            : folderPage([], [file('b.txt')]),
      },
    });
    // 다음 페이지(token 있음)는 failNext 동안 500 — 앱의 1회 자동 재시도까지 실패하게 한다. 나중 등록 라우트가 이기고,
    // failNext 를 끄면 fallback 으로 위 모킹(정상 응답)이 받는다.
    let failNext = true;
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects` && url.searchParams.get('token') !== null,
      (route) =>
        failNext
          ? route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ status: 500 }) })
          : route.fallback(),
    );
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    const list = objectList(page);
    await expect(list.getByRole('button', { name: 'a.txt', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    // WD-776: 다음 쪽 실패는 「다음 목록」 문구 — 첫 쪽 재조회 실패 문구·「다시 불러오기」는 나오지 않는다(「더 보기」가 재시도).
    await expect(page.getByRole('alert')).toContainText('다음 목록을 불러오지 못했습니다. 다시 시도하세요.');
    await expect(page.getByRole('alert')).not.toContainText('목록을 새로 불러오지 못했습니다');
    await expect(page.getByRole('button', { name: '다시 불러오기' })).toHaveCount(0);
    // 실패해도 불러온 폴더·파일 행은 그대로다.
    await expect(list.getByRole('button', { name: '제품' })).toBeVisible();
    await expect(list.getByRole('button', { name: 'a.txt', exact: true })).toBeVisible();

    failNext = false;
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(list.getByRole('button', { name: 'b.txt', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('서버가 경로를 거부(400)하면 오류 문구를 보이고, 경로 표시로 빠져나올 수 있다', async ({ authenticatedPage: page }) => {
    await setupFileDataset(page);
    // 손으로 바꾼 ../ 경로 — 서버 정규화가 400 으로 거부한다. 나중 등록 라우트가 이기므로 이 응답만 덮는다.
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects` && url.searchParams.get('path') === '../x',
      (route) =>
        route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ status: 400, error: 'Bad Request', message: '폴더 경로가 올바르지 않습니다.' }),
        }),
    );
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('../x')}`);

    await expect(page.getByRole('alert').filter({ hasText: '폴더 경로가 올바르지 않습니다.' })).toBeVisible({
      timeout: 10_000,
    });
    // 빈 목록 안내로 오인시키지 않는다.
    await expect(page.getByText('이 폴더에 파일이 없습니다.')).toHaveCount(0);

    await breadcrumb(page).getByRole('button', { name: '전체' }).click();
    await expect(objectList(page).getByRole('button', { name: 'readme.txt' })).toBeVisible();
  });
});

test.describe('FILE 오브젝트 탭 — 이름 검색', () => {
  test('검색어를 넣으면 q 로 묻고, 결과를 누르면 그 파일이 있는 폴더로 이동하며 검색어를 지운다', async ({
    authenticatedPage: page,
  }) => {
    const mock = await setupFileDataset(page, {
      search: (q) =>
        q === '설치'
          ? { objects: [file('제품/Tibero7/설치.pdf'), file('문서/설치_가이드.md')], nextToken: null, hasMore: false, truncated: false }
          : folderPage([], []),
    });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('문서')}`);
    await expect(page.getByText('이 폴더에 파일이 없습니다.')).toBeVisible();

    const searchBox = page.getByPlaceholder('파일 이름 검색');
    await searchBox.fill('설치');

    // 결과: 폴더 행 없이, 이름 칸에 전체 상대 경로.
    const list = objectList(page);
    await expect(list.getByRole('button', { name: '제품/Tibero7/설치.pdf' })).toBeVisible();
    await expect(list.getByRole('button', { name: '문서/설치_가이드.md' })).toBeVisible();
    await expect(list.getByTestId('folder-row')).toHaveCount(0);
    // 서버에 q 로 묻는다(검색은 데이터셋 전체 — 현재 폴더 path 는 싣지 않는다).
    const searchReq = mock.requests.find((r) => r.get('q') !== null)!;
    expect(searchReq.get('q')).toBe('설치');
    expect(searchReq.get('path')).toBeNull();
    // 다운로드 아이콘은 그대로 있다.
    await expect(list.getByRole('button', { name: '다운로드' })).toHaveCount(2);

    // 결과 이름을 누르면 그 파일이 있는 폴더로 가고 검색어가 지워진다.
    await list.getByRole('button', { name: '제품/Tibero7/설치.pdf' }).click();
    await expect.poll(() => queryOf(page, 'path')).toBe('제품/Tibero7');
    await expect(searchBox).toHaveValue('');
    await expect(list.getByRole('button', { name: '설치.pdf', exact: true })).toBeVisible();
    await expect(breadcrumb(page).getByRole('button')).toHaveText(['전체', '제품', 'Tibero7']);
  });

  test('공백만 넣은 검색어는 검색이 아니다 — 폴더 목록을 그대로 둔다', async ({ authenticatedPage: page }) => {
    const mock = await setupFileDataset(page);
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    await expect(objectList(page).getByRole('button', { name: 'readme.txt' })).toBeVisible();

    await page.getByPlaceholder('파일 이름 검색').fill('   ');
    // 디바운스(300ms)가 지난 뒤에도 q 요청이 없고 폴더 행이 남는다.
    await page.waitForTimeout(600);
    expect(mock.requests.some((r) => r.get('q') !== null)).toBe(false);
    await expect(objectList(page).getByRole('button', { name: '제품' })).toBeVisible();
  });

  test('일치하는 파일이 없으면 검색어를 넣은 안내를 보인다', async ({ authenticatedPage: page }) => {
    await setupFileDataset(page, { search: () => folderPage([], []) });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    await expect(objectList(page).getByRole('button', { name: 'readme.txt' })).toBeVisible();

    await page.getByPlaceholder('파일 이름 검색').fill('없는파일');
    await expect(page.getByText('「없는파일」와 일치하는 파일이 없습니다.')).toBeVisible();
  });

  test('검색 중 브라우저 뒤로가기로 폴더가 바뀌면 검색을 끝내고 그 폴더 목록을 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupFileDataset(page, {
      search: () => folderPage([], [file('제품/Tibero7/설치.pdf')]),
    });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    const list = objectList(page);
    await list.getByRole('button', { name: '제품' }).click();
    await expect(list.getByRole('button', { name: '개요.pdf', exact: true })).toBeVisible();

    const searchBox = page.getByPlaceholder('파일 이름 검색');
    await searchBox.fill('설치');
    await expect(list.getByRole('button', { name: '제품/Tibero7/설치.pdf' })).toBeVisible();

    // 뒤로가기 = 루트 폴더. 경로 표시·업로드 대상과 목록이 어긋나지 않게 검색이 끝나야 한다.
    await page.goBack();
    await expect.poll(() => queryOf(page, 'path')).toBeNull();
    await expect(list.getByRole('button', { name: 'readme.txt' })).toBeVisible();
    await expect(searchBox).toHaveValue('');
  });

  test('검색이 상한에 닿으면(truncated) 「일부만 검색됨」 안내를 보이고 더 보기를 띄우지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupFileDataset(page, {
      search: () => ({ objects: [file('로그/app.log')], nextToken: null, hasMore: false, truncated: true }),
    });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    await expect(objectList(page).getByRole('button', { name: 'readme.txt' })).toBeVisible();

    await page.getByPlaceholder('파일 이름 검색').fill('log');
    await expect(objectList(page).getByRole('button', { name: '로그/app.log' })).toBeVisible();
    await expect(
      page
        .getByRole('status')
        .filter({ hasText: '일부만 검색됨 — 파일이 많아 검색을 중간에 멈췄습니다. 검색어를 더 구체적으로 입력하세요.' }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);

    // 검색어를 지우면 안내가 사라지고 폴더 목록으로 돌아온다.
    await page.getByRole('button', { name: '검색어 지우기' }).click();
    await expect(objectList(page).getByRole('button', { name: '제품' })).toBeVisible();
    await expect(page.getByText('일부만 검색됨', { exact: false })).toHaveCount(0);
  });
});

test.describe('FILE 오브젝트 탭 — 현재 폴더로 업로드', () => {
  // 업로드 드롭존·폴더 선택은 dataset:write 가 있을 때만 보인다(WD-139).
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('폴더 안에서 올린 파일은 upload-urls 본문에 현재 폴더 path 를 싣는다', async ({ authenticatedPage: page }) => {
    const mock = await setupFileDataset(page);
    const { payloads } = await mockUploadUrls(page);
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('제품/Tibero7')}`);
    await expect(objectList(page).getByRole('button', { name: '설치.pdf', exact: true })).toBeVisible();

    // 드롭존 문구가 올라갈 폴더를 알린다.
    await expect(dropzone(page)).toContainText('제품/Tibero7 폴더에');
    const getsBefore = mock.requests.length;

    await chooseFiles(page, {
      name: 'new.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('hello'),
    });

    await expect.poll(() => payloads.length).toBe(1);
    expect(payloads[0]).toEqual({ path: '제품/Tibero7', files: [{ filename: 'new.txt' }] });
    // 업로드 뒤 현재 폴더 목록을 다시 받는다(무효화가 폴더별 queryKey 에도 닿는다).
    await expect
      .poll(() => mock.requests.slice(getsBefore).some((r) => r.get('path') === '제품/Tibero7'))
      .toBe(true);
  });

  test('루트에서 올리면 path 를 싣지 않고 드롭존 문구도 그대로다', async ({ authenticatedPage: page }) => {
    await setupFileDataset(page);
    const { payloads } = await mockUploadUrls(page);
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    await expect(objectList(page).getByRole('button', { name: 'readme.txt' })).toBeVisible();

    await expect(dropzone(page)).toHaveText('파일·폴더를 드래그하거나 클릭하여 업로드');
    await chooseFiles(page, {
      name: 'root.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('r'),
    });

    await expect.poll(() => payloads.length).toBe(1);
    expect(payloads[0]).toEqual({ files: [{ filename: 'root.txt' }] });
  });

  test('폴더를 올리면 현재 폴더 아래로 하위 상대 경로를 유지한다', async ({ authenticatedPage: page }) => {
    await setupFileDataset(page);
    const { payloads } = await mockUploadUrls(page);

    // 실제 중첩 디렉터리를 webkitdirectory 인풋에 넘긴다 → 브라우저가 webkitRelativePath 를 채운다.
    const root = mkdtempSync(join(tmpdir(), 'fh-wd761-'));
    mkdirSync(join(root, 'site-A', '2026'), { recursive: true });
    writeFileSync(join(root, 'site-A', '2026', 'img.jpg'), 'a');
    writeFileSync(join(root, 'top.txt'), 'b');
    try {
      await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('제품')}`);
      await expect(objectList(page).getByRole('button', { name: '개요.pdf', exact: true })).toBeVisible();

      await page.locator('input[webkitdirectory]').setInputFiles(root);

      await expect.poll(() => payloads.length).toBe(1);
      expect(payloads[0].path).toBe('제품');
      const names = payloads[0].files.map((f) => f.filename);
      // 선택한 폴더 이름이 최상위 세그먼트가 되므로 접미사로 본다 — 평면화되지 않았다.
      expect(names.some((n) => n.endsWith('site-A/2026/img.jpg'))).toBe(true);
      expect(names.some((n) => n.endsWith('top.txt'))).toBe(true);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});

/**
 * WD-776 — 오브젝트 탭 후속.
 * - 목록에 보이는 폴더는 이름 그대로 들어간다: 「report\2024」(\ 를 / 로 바꾸지 않음)·공백뿐인 폴더(버리지 않음, 화면엔 「(공백)」).
 *   들어가면 그 이름 그대로 목록 API path 로 묻고, 경로 표시가 맞으며, 그 폴더에서 올리면 upload-urls 본문 path 도 그 값이다.
 * - upload-urls 가 파일별 실패(error)를 주면 그 파일만 실패로(이유와 함께) 보이고 나머지는 올라간다.
 * - 업로드 뒤 첫 쪽 재조회가 실패하면 「다음 목록」이 아니라 「목록을 새로 불러오지 못했습니다」로 알리고 다시 불러올 수 있다.
 */
test.describe('FILE 오브젝트 탭 — 폴더 이름 보존·파일별 업로드 실패·재조회 실패 (WD-776)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  const BACKSLASH_FOLDER = 'report\\2024';
  const BLANK_FOLDER = ' ';
  const TREE_776: Record<string, ObjectListResponse> = {
    '': folderPage([`${BACKSLASH_FOLDER}/`, `${BLANK_FOLDER}/`], [file('readme.txt', 100)]),
    [BACKSLASH_FOLDER]: folderPage([], [file(`${BACKSLASH_FOLDER}/q1.csv`)]),
    [BLANK_FOLDER]: folderPage([], [file(`${BLANK_FOLDER}/memo.txt`)]),
  };

  // 예전엔 \ 를 / 로 바꿔(report/2024) 또는 공백뿐인 세그먼트를 버려(루트) 다른 폴더가 열렸다. 같은 흐름이라 한 본문으로 돈다.
  for (const { title, folder, label, child } of [
    { title: '\\ 가 든 폴더', folder: BACKSLASH_FOLDER, label: BACKSLASH_FOLDER, child: 'q1.csv' },
    { title: '공백뿐인 폴더', folder: BLANK_FOLDER, label: '(공백)', child: 'memo.txt' },
  ]) {
    test(`${title}는 「${label}」로 보이고, 누르면 이름 그대로 들어가 그 폴더로 올린다`, async ({ authenticatedPage: page }) => {
      const mock = await setupFileDataset(page, { tree: TREE_776 });
      const { payloads } = await mockUploadUrls(page);
      await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
      const list = objectList(page);

      // 빈 버튼이 아니라 보이는 이름이 있다.
      await list.getByRole('button', { name: label, exact: true }).click();

      // URL·목록 요청 path 모두 이름 그대로이고, 다른 폴더(루트·report/2024)가 아니라 그 폴더의 파일이 보인다.
      await expect.poll(() => queryOf(page, 'path')).toBe(folder);
      await expect.poll(() => mock.requests.map((r) => r.get('path'))).toContain(folder);
      await expect(list.getByRole('button', { name: child, exact: true })).toBeVisible();
      await expect(list.getByRole('button', { name: 'readme.txt' })).toHaveCount(0);
      await expect(breadcrumb(page).getByRole('button')).toHaveText(['전체', label]);
      await expect(breadcrumb(page).getByRole('button', { name: label })).toHaveAttribute('aria-current', 'page');

      // 그 폴더에서 올리면 upload-urls 본문 path 도 그 이름 그대로다.
      await expect(dropzone(page)).toContainText(`${label} 폴더에`);
      await chooseFiles(page, { name: 'new.txt', mimeType: 'text/plain', buffer: Buffer.from('n') });
      await expect.poll(() => payloads.length).toBe(1);
      expect(payloads[0]).toEqual({ path: folder, files: [{ filename: 'new.txt' }] });
    });
  }

  // 서버 사유 — 폴더 안에서는 긴 이름을 자르지 않고 그 파일만 거부한다(앞부분이 같은 이름끼리 말없이 덮어쓰지 않게).
  const TOO_LONG_REASON = '폴더 경로와 합친 이름이 너무 깁니다(최대 900바이트) — 이름을 줄여 다시 올려 주세요';

  test('upload-urls 가 한 파일만 실패로 주면 그 파일만 이유와 함께 실패로 보이고 나머지는 올라간다', async ({
    authenticatedPage: page,
  }) => {
    await setupFileDataset(page);
    const longName = `${'b'.repeat(120)}.jpg`;
    const { payloads, puts } = await mockUploadUrls(page, {
      rejectReason: (name) => (name === longName ? TOO_LONG_REASON : undefined),
    });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('제품')}`);
    await expect(objectList(page).getByRole('button', { name: '개요.pdf', exact: true })).toBeVisible();

    await chooseFiles(page, [
      { name: longName, mimeType: 'image/jpeg', buffer: Buffer.from('x') },
      { name: 'ok.txt', mimeType: 'text/plain', buffer: Buffer.from('y') },
    ]);

    await expect.poll(() => payloads.length).toBe(1);
    expect(payloads[0].files.map((f) => f.filename)).toEqual([longName, 'ok.txt']);
    // 발급된 파일(ok.txt, 인덱스 1)만 MinIO 에 PUT 한다.
    await expect.poll(() => puts).toEqual(['https://minio.example.com/put/1']);

    // 묶음 요약: 2개 중 1개 실패 — 다시 올려도 같은 이유로 실패하므로 재시도 버튼은 없다(PUT 실패가 없다).
    const banner = page.getByTestId('upload-failure');
    await expect(banner).toContainText('2개 중 1개 업로드 실패');
    await expect(banner.getByRole('button', { name: '실패건 재시도' })).toHaveCount(0);
    // 거부 파일은 이유와 함께 따로 보인다.
    const rejectedBox = page.getByTestId('upload-rejected');
    await expect(rejectedBox).toContainText('올리지 못한 파일 1개');
    const rejected = rejectedBox.getByRole('list', { name: '올리지 못한 파일' }).getByRole('listitem');
    await expect(rejected).toHaveCount(1);
    await expect(rejected).toContainText(longName);
    await expect(rejected).toContainText(TOO_LONG_REASON);
    await expect(dropzone(page)).not.toContainText('업로드 실패 — 다시 시도하세요');

    // 닫으면 사라진다.
    await page.getByRole('button', { name: '올리지 못한 파일 안내 닫기' }).click();
    await expect(page.getByTestId('upload-rejected')).toHaveCount(0);
  });

  test('거부 1 + PUT 실패 1 묶음에서 실패건 재시도가 성공해도 거부 파일과 이유는 남는다', async ({
    authenticatedPage: page,
  }) => {
    await setupFileDataset(page);
    const longName = `${'c'.repeat(120)}.png`;
    // 첫 PUT(flaky.txt) 은 500, 재시도 PUT 은 200.
    const { payloads, puts } = await mockUploadUrls(page, {
      rejectReason: (name) => (name === longName ? TOO_LONG_REASON : undefined),
      putStatus: (n) => (n === 0 ? 500 : 200),
    });
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects&path=${encodeURIComponent('제품')}`);
    await expect(objectList(page).getByRole('button', { name: '개요.pdf', exact: true })).toBeVisible();

    await chooseFiles(page, [
      { name: longName, mimeType: 'image/png', buffer: Buffer.from('x') },
      { name: 'flaky.txt', mimeType: 'text/plain', buffer: Buffer.from('y') },
    ]);

    const banner = page.getByTestId('upload-failure');
    await expect(banner).toContainText('2개 중 2개 업로드 실패');
    const rejectedBox = page.getByTestId('upload-rejected');
    await expect(rejectedBox).toContainText(longName);

    // 재시도는 PUT 이 실패한 flaky.txt 만 다시 요청한다(거부 파일은 빼고).
    await banner.getByRole('button', { name: '실패건 재시도' }).click();
    await expect.poll(() => payloads.length).toBe(2);
    expect(payloads[1].files.map((f) => f.filename)).toEqual(['flaky.txt']);
    await expect.poll(() => puts.length).toBe(2);

    // 재시도 성공 → 실패 요약은 사라지지만, 거부 파일과 이유는 그대로 남는다.
    await expect(page.getByTestId('upload-failure')).toHaveCount(0);
    await expect(rejectedBox).toContainText('올리지 못한 파일 1개');
    await expect(rejectedBox).toContainText(longName);
    await expect(rejectedBox).toContainText(TOO_LONG_REASON);

    // 새 업로드를 시작하면 지난 거부 목록은 비운다.
    await chooseFiles(page, { name: 'next.txt', mimeType: 'text/plain', buffer: Buffer.from('z') });
    await expect.poll(() => payloads.length).toBe(3);
    await expect(page.getByTestId('upload-rejected')).toHaveCount(0);
  });

  test('업로드 뒤 첫 쪽 재조회가 실패하면 「목록을 새로 불러오지 못했습니다」로 알리고, 다시 불러오면 사라진다', async ({
    authenticatedPage: page,
  }) => {
    await setupFileDataset(page);
    await mockUploadUrls(page);
    // failList 동안 목록 GET 은 500 — 앱의 자동 재시도까지 실패하게 한다. 나중 등록 라우트가 이기고, 끄면 fallback 으로 정상 모킹이 받는다.
    let failList = false;
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects`,
      (route) =>
        failList && route.request().method() === 'GET'
          ? route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ status: 500 }) })
          : route.fallback(),
    );
    await page.goto(`/data/datasets/${DATASET_ID}?tab=objects`);
    const list = objectList(page);
    await expect(list.getByRole('button', { name: 'readme.txt' })).toBeVisible();

    // 업로드 성공 → 목록 무효화 → 첫 쪽 재조회가 실패한다.
    failList = true;
    await chooseFiles(page, { name: 'new.txt', mimeType: 'text/plain', buffer: Buffer.from('n') });

    const alert = page.getByRole('alert');
    await expect(alert).toContainText('목록을 새로 불러오지 못했습니다. 다시 시도하세요.', { timeout: 10_000 });
    await expect(alert).not.toContainText('다음 목록');
    // 이미 불러온 목록은 그대로 둔다.
    await expect(list.getByRole('button', { name: 'readme.txt' })).toBeVisible();

    failList = false;
    await page.getByRole('button', { name: '다시 불러오기' }).click();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(list.getByRole('button', { name: 'readme.txt' })).toBeVisible();
  });
});
