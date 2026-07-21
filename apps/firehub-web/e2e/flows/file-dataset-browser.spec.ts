import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { createCategories, createDatasetDetail } from '../factories/dataset.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

const DATASET_ID = 7;

test.describe('FILE 데이터셋 오브젝트 브라우저', () => {
  test('오브젝트 목록을 S3 스타일(이름/크기)로 표시한다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    // FILE 타입 상세 응답 — DatasetDetailPage 헤더가 항상 호출하는 카테고리/태그 API도 함께 모킹한다.
    const detail = createDatasetDetail({
      id: DATASET_ID,
      name: '장비 학습 데이터',
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    // 오브젝트 목록 mock — 키는 "<prefix>...<파일명>". 표시명은 마지막 경로 세그먼트(S3 방식).
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [
        { key: 'equip/보고서.md', name: '보고서.md', size: 2048, lastModified: '2026-07-20T00:00:00Z' },
        { key: 'equip/2026/photo.jpg', name: '2026/photo.jpg', size: 1048576, lastModified: null },
      ],
      nextToken: null,
      hasMore: false,
    });

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();

    // 표시명 = prefix를 제외한 상대경로. 폴더 구조(2026/)는 보이되 데이터셋 prefix(equip/)는 숨긴다.
    await expect(page.getByText('보고서.md', { exact: true })).toBeVisible();
    await expect(page.getByText('2026/photo.jpg', { exact: true })).toBeVisible();
    await expect(page.getByText('equip/2026/photo.jpg')).toHaveCount(0);
    // 크기가 사람이 읽는 단위로 표기된다.
    await expect(page.getByText('2.0 KB')).toBeVisible();
    await expect(page.getByText('1.0 MB')).toBeVisible();
  });

  test('행을 클릭하면 presigned GET URL로 원본 파일명 다운로드를 연다', async ({
    authenticatedPage: page,
  }) => {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [{ key: 'equip/report.md', name: 'report.md', size: 2048, lastModified: null }],
      nextToken: null,
      hasMore: false,
    });

    // presigned GET URL 발급 mock — 어떤 key로 요청됐는지 캡처하고 다운로드용 URL을 반환한다.
    let requestedKey: string | null = null;
    await page.route(
      (u) => u.pathname === `/api/v1/datasets/${DATASET_ID}/objects/url`,
      (route) => {
        requestedKey = new URL(route.request().url()).searchParams.get('key');
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            url: 'https://example.com/download/report.md',
            expiresInSeconds: 300,
          }),
        });
      },
    );
    // 새 탭이 이동할 presigned(외부 MinIO 대체) URL — 컨텍스트 레벨 라우트로 팝업까지 커버한다.
    await page.context().route('https://example.com/**', (route) =>
      route.fulfill({ status: 200, contentType: 'text/plain', body: 'file-bytes' }),
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();

    // 행(버튼) 클릭 → 새 탭(popup)이 열리고 presigned URL로 이동한다.
    const popupPromise = page.waitForEvent('popup');
    await page.getByRole('button', { name: /report\.md/ }).click();
    const popup = await popupPromise;
    await expect(popup).toHaveURL('https://example.com/download/report.md');

    // 발급 요청이 해당 오브젝트의 전체 키로 전달됐는지 검증.
    expect(requestedKey).toBe('equip/report.md');
  });

  test('FILE 데이터셋은 필드/데이터 탭을 숨긴다', async ({ authenticatedPage: page }) => {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [],
      nextToken: null,
      hasMore: false,
    });

    await page.goto(`/data/datasets/${DATASET_ID}`);

    await expect(page.getByRole('tab', { name: '오브젝트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '필드' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveCount(0);
  });

  test('파일을 드롭하면 원본 파일명으로 presigned PUT 업로드하고 목록을 갱신한다', async ({
    authenticatedPage: page,
  }) => {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    // 목록 응답을 상태(state)로 관리한다 — 초기 로드에서는 빈 목록을 반환하고,
    // upload-urls 발급(POST) 요청을 관측한 "이후"에만 photo.jpg를 포함한 목록을 반환한다.
    // 이렇게 해야 이 assert가 invalidateQueries에 의한 재조회 없이는 통과할 수 없다.
    let uploaded = false;
    await page.route((url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects`, (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const body = uploaded
        ? {
            objects: [{ key: 'equip/photo.jpg', name: 'photo.jpg', size: 1024, lastModified: null }],
            nextToken: null,
            hasMore: false,
          }
        : { objects: [], nextToken: null, hasMore: false };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    });

    // upload-urls 발급 mock — 요청 페이로드를 캡처하고, 응답 직전에 uploaded 플래그를 올려
    // "POST 수신 → PUT 발생 → invalidateQueries → 재조회" 순서를 보장한다.
    let uploadUrlsPayload: unknown = null;
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects/upload-urls`,
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        uploadUrlsPayload = route.request().postDataJSON();
        uploaded = true;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            targets: [{ key: 'equip/photo.jpg', uploadUrl: 'https://minio.example.com/put/photo.jpg' }],
            expiresInSeconds: 900,
          }),
        });
      },
    );

    // presigned PUT(외부 MinIO) mock — 앱을 경유하지 않는 직접 PUT. 실제로 호출됐는지 플래그로 기록한다.
    let putHit = false;
    await page.route('https://minio.example.com/**', (route) => {
      putHit = true;
      return route.fulfill({ status: 200, body: '' });
    });

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();

    // 초기 로드 시점에는 목록이 비어있어야 한다 (업로드 전 상태 확인)
    await expect(page.getByText('photo.jpg', { exact: true })).toHaveCount(0);

    // 숨겨진 파일 입력에 파일 주입 → 업로드 트리거
    // 다른 탭(문서)의 업로드 인풋도 DOM에 함께 마운트되어 있으므로 드롭존 버튼으로 범위를 좁힌다.
    await page
      .getByRole('button', { name: '파일·폴더를 드래그하거나 클릭하여 업로드' })
      .locator('input[type="file"]')
      .setInputFiles({
        name: 'photo.jpg',
        mimeType: 'image/jpeg',
        buffer: Buffer.from('fake-bytes'),
      });

    // upload-urls 요청이 원본 파일명(filename)으로 전송됐는지 확인(robotId/ext 없음 — S3 방식).
    await expect
      .poll(() => uploadUrlsPayload)
      .toMatchObject({ files: [{ filename: 'photo.jpg' }] });

    // presigned PUT이 실제로 MinIO 엔드포인트에 도달했는지 확인
    await expect.poll(() => putHit).toBe(true);

    // 업로드 후 목록 재조회(invalidateQueries)로 새 오브젝트가 이름으로 노출된다.
    await expect(page.getByText('photo.jpg', { exact: true })).toBeVisible();
  });

  test('일부 PUT이 실패하면 실패 배너를 띄우고 성공분은 목록에 반영, 재시도로 실패건만 재업로드한다', async ({
    authenticatedPage: page,
  }) => {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    // 목록 GET 호출 횟수를 센다 — 부분 실패에도 onSettled invalidate로 재조회가 일어나는지 검증.
    let objectsGetCount = 0;
    await page.route((url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects`, (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      objectsGetCount += 1;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ objects: [], nextToken: null, hasMore: false }),
      });
    });

    // upload-urls: 요청 files 수만큼 target을 발급(위치별 URL). 각 호출 페이로드를 순서대로 기록한다.
    const uploadUrlsCalls: { files: { filename: string }[] }[] = [];
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects/upload-urls`,
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        const payload = route.request().postDataJSON() as { files: { filename: string }[] };
        uploadUrlsCalls.push(payload);
        const targets = payload.files.map((_, i) => ({
          key: `equip/f${i}.jpg`,
          uploadUrl: `https://minio.example.com/put/f${i}`,
        }));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ targets, expiresInSeconds: 900 }),
        });
      },
    );

    // PUT mock — f1은 첫 시도에서만 500(부분 실패 유도), 재시도(이후)에는 200. f0은 항상 200.
    let f1Attempts = 0;
    await page.route('https://minio.example.com/**', (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/f1')) {
        f1Attempts += 1;
        if (f1Attempts === 1) return route.fulfill({ status: 500, body: '' });
      }
      return route.fulfill({ status: 200, body: '' });
    });

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();
    await expect.poll(() => objectsGetCount).toBeGreaterThanOrEqual(1);
    const getCountBeforeUpload = objectsGetCount;

    // 파일 2개 주입 → 배치 업로드(하나는 실패하도록 설계됨)
    await page
      .getByRole('button', { name: '파일·폴더를 드래그하거나 클릭하여 업로드' })
      .locator('input[type="file"]')
      .setInputFiles([
        { name: 'a.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('a') },
        { name: 'b.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('b') },
      ]);

    // 첫 배치는 2건 요청, 각각 원본 파일명이 실린다.
    await expect.poll(() => uploadUrlsCalls.length).toBe(1);
    expect(uploadUrlsCalls[0].files).toEqual([{ filename: 'a.jpg' }, { filename: 'b.jpg' }]);

    // 부분 실패 배너: 2개 중 1개 실패
    await expect(page.getByText('2개 중 1개 업로드 실패')).toBeVisible();

    // 부분 실패여도 목록이 재조회된다(onSettled invalidate) — GET 호출이 늘어난다.
    await expect.poll(() => objectsGetCount).toBeGreaterThan(getCountBeforeUpload);

    // 재시도: 실패건(1개)만 재업로드 → upload-urls가 1건으로 재호출되고 배너가 사라진다.
    await page.getByRole('button', { name: '실패건 재시도' }).click();
    await expect.poll(() => uploadUrlsCalls.length).toBe(2);
    expect(uploadUrlsCalls[1].files).toHaveLength(1);
    await expect(page.getByText('2개 중 1개 업로드 실패')).toHaveCount(0);
  });

  test('폴더를 선택하면 하위 상대경로를 filename으로 전송해 구조를 보존한다', async ({
    authenticatedPage: page,
  }) => {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [],
      nextToken: null,
      hasMore: false,
    });

    // upload-urls 발급 mock — 폴더 업로드가 하위 파일의 "상대경로"를 filename으로 싣는지 검증하려 페이로드를 캡처한다.
    let payload: { files: { filename: string }[] } | null = null;
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects/upload-urls`,
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        payload = route.request().postDataJSON() as { files: { filename: string }[] };
        const targets = payload.files.map((f, i) => ({
          key: `equip/${f.filename}`,
          uploadUrl: `https://minio.example.com/put/${i}`,
        }));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ targets, expiresInSeconds: 900 }),
        });
      },
    );
    await page.route('https://minio.example.com/**', (route) => route.fulfill({ status: 200, body: '' }));

    // 실제 중첩 디렉터리를 만들어 webkitdirectory 인풋에 넘긴다 → 브라우저가 webkitRelativePath를 채운다.
    const root = mkdtempSync(join(tmpdir(), 'fh-folder-'));
    mkdirSync(join(root, 'site-A', '2026'), { recursive: true });
    writeFileSync(join(root, 'site-A', '2026', 'img.jpg'), 'a');
    writeFileSync(join(root, 'root.txt'), 'b');

    try {
      await page.goto(`/data/datasets/${DATASET_ID}`);
      await page.getByRole('tab', { name: '오브젝트' }).click();

      // webkitdirectory 인풋(폴더 선택)에 디렉터리 자체를 주입.
      await page.locator('input[webkitdirectory]').setInputFiles(root);

      // 전송된 filename들이 하위 상대경로를 보존하는지 확인(선택 폴더명이 최상위 세그먼트가 되므로 접미사로 검증).
      await expect.poll(() => payload).not.toBeNull();
      const names = payload!.files.map((f) => f.filename);
      expect(names.some((n) => n.endsWith('site-A/2026/img.jpg'))).toBe(true);
      expect(names.some((n) => n.endsWith('root.txt'))).toBe(true);
      // 평면화되지 않았는지(구조 보존) — 하위 경로가 실제로 들어있다.
      expect(names.some((n) => n.includes('site-A/2026/'))).toBe(true);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});
