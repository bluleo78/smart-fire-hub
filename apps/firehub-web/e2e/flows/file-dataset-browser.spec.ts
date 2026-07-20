import { createCategories, createDatasetDetail } from '../factories/dataset.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

const DATASET_ID = 7;

test.describe('FILE 데이터셋 오브젝트 브라우저', () => {
  test('오브젝트 목록과 썸네일을 보여준다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

    // 오브젝트 목록 mock
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [{ key: 'equip/robot-01/a.jpg', size: 2048, lastModified: null }],
      nextToken: null,
      hasMore: false,
    });
    // presigned URL mock
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects/url`, {
      url: 'https://example.com/a.jpg',
      expiresInSeconds: 300,
    });

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();

    await expect(page.getByText('a.jpg · 2KB')).toBeVisible();
    await expect(page.locator('img[alt="a.jpg"]')).toHaveAttribute('src', 'https://example.com/a.jpg');
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

  test('파일을 드롭하면 presigned PUT으로 업로드하고 목록을 갱신한다', async ({
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
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects/url`, {
      url: 'https://example.com/u1.jpg',
      expiresInSeconds: 300,
    });

    // 목록 응답을 상태(state)로 관리한다 — 초기 로드에서는 빈 목록을 반환하고,
    // upload-urls 발급(POST) 요청을 관측한 "이후"에만 u1.jpg를 포함한 목록을 반환한다.
    // 이렇게 해야 이 assert가 invalidateQueries에 의한 재조회 없이는 통과할 수 없다.
    let uploaded = false;
    await page.route((url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/objects`, (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const body = uploaded
        ? {
            objects: [{ key: 'equip/web/2026-07-20/u1.jpg', size: 1024, lastModified: null }],
            nextToken: null,
            hasMore: false,
          }
        : { objects: [], nextToken: null, hasMore: false };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    });

    // upload-urls 발급 mock — 요청 페이로드를 직접 캡처하고, 응답 직전에 uploaded 플래그를 올려
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
            targets: [
              { key: 'equip/web/2026-07-20/u1.jpg', uploadUrl: 'https://minio.example.com/put/u1.jpg' },
            ],
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
    await expect(page.locator('img[alt="u1.jpg"]')).toHaveCount(0);

    // 숨겨진 파일 입력에 파일 주입 → 업로드 트리거
    // 다른 탭(문서)의 업로드 인풋도 DOM에 함께 마운트되어 있으므로 드롭존 버튼으로 범위를 좁힌다.
    await page
      .getByRole('button', { name: '파일을 드래그하거나 클릭하여 업로드' })
      .locator('input[type="file"]')
      .setInputFiles({
        name: 'photo.jpg',
        mimeType: 'image/jpeg',
        buffer: Buffer.from('fake-bytes'),
      });

    // upload-urls 요청이 robotId=web, files=[{ext:'jpg'}] 로 전송됐는지 확인
    await expect.poll(() => uploadUrlsPayload).toMatchObject({ robotId: 'web', files: [{ ext: 'jpg' }] });

    // presigned PUT이 실제로 MinIO 엔드포인트에 도달했는지 확인
    await expect.poll(() => putHit).toBe(true);

    // 업로드 후 목록 재조회(invalidateQueries)로 새 오브젝트 썸네일이 노출된다.
    // uploaded 플래그가 올라가기 전에는 위 mock이 빈 목록을 반환하므로,
    // invalidateQueries가 제거되면 이 assert는 실패한다.
    await expect(page.locator('img[alt="u1.jpg"]')).toHaveAttribute(
      'src',
      'https://example.com/u1.jpg',
    );
  });
});
