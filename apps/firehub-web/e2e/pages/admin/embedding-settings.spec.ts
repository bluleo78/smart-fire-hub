import {
  createEmbeddingConfig,
  createEmbeddingStatus,
  UNCONFIGURED_EMBEDDING,
} from '../../factories/admin.factory';
import { setupAdminAuth, setupEmbeddingMocks, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 설정 > 임베딩 탭(#713): 테넌트가 provider·모델·Base URL·키를 직접 저장한다. 저장 전 연결 테스트로 차원을
 * 재고, 영향도가 0 보다 크면 재임베딩 확인 창을 띄운다. 서버 PUT 은 다시 probe 하므로 화면의 측정값은 안내용이다.
 */
async function openTab(page: import('@playwright/test').Page) {
  await page.goto('/admin/settings');
  await page.getByRole('tab', { name: '임베딩' }).click();
  await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
}

test.describe('임베딩 설정 탭', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page);
  });

  test('미설정 테넌트가 Ollama 를 저장한다(영향 0 이면 확인 창 없이 PUT)', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    const { save, impact } = await setupEmbeddingMocks(page, {
      config: UNCONFIGURED_EMBEDDING,
      status: createEmbeddingStatus({ configured: false, model: null, dimension: null }),
      probe: { body: { dimension: 1024 } },
    });
    await openTab(page);

    // 미설정 배너: 무엇이 멈췄는지 알린다.
    await expect(page.getByText('임베딩이 설정되지 않았습니다')).toBeVisible();
    await expect(page.getByText(/문서 검색·데이터셋 탐색·행 검색이 동작하지 않습니다/)).toBeVisible();
    await expect(page.getByRole('button', { name: '전체 재임베딩 실행' })).toBeDisabled();

    await page.getByLabel('모델').fill('bge-m3');
    await page.getByLabel('Base URL').fill('http://host.docker.internal:11434');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    const imp = await impact.waitForRequest();
    expect(imp.searchParams.get('model')).toBe('bge-m3');
    expect(imp.searchParams.get('dimension')).toBe('1024');
    const req = await save.waitForRequest();
    expect(req.payload).toEqual({
      provider: 'OLLAMA',
      model: 'bge-m3',
      baseUrl: 'http://host.docker.internal:11434',
    });
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.getByText('임베딩 설정을 저장했습니다')).toBeVisible();
  });

  test('저장된 값이 폼에 채워지고 VOYAGE 선택지는 없다', async ({ authenticatedPage: page }) => {
    await setupEmbeddingMocks(page, {
      config: createEmbeddingConfig({
        provider: 'OPENAI',
        model: 'text-embedding-3-small',
        baseUrl: 'https://api.openai.com',
        dimension: 1536,
        apiKeyMasked: '****ab12',
      }),
    });
    await openTab(page);

    await expect(page.locator('#embedding-provider')).toContainText('OpenAI');
    await expect(page.getByLabel('모델')).toHaveValue('text-embedding-3-small');
    await expect(page.getByLabel('Base URL')).toHaveValue('https://api.openai.com');
    // 키는 값으로 내려오지 않는다 — 마스크는 안내문으로만.
    await expect(page.getByLabel('API 키')).toHaveValue('');
    await expect(page.getByText('저장된 키 ****ab12 — 비우면 유지됩니다')).toBeVisible();

    await page.locator('#embedding-provider').click();
    await expect(page.getByRole('option', { name: /Voyage/ })).toHaveCount(0);
  });

  test('연결 테스트는 측정 차원을, 실패는 서버 원인을 보여준다', async ({ authenticatedPage: page }) => {
    const { probe } = await setupEmbeddingMocks(page, { probe: { body: { dimension: 1024 } } });
    await openTab(page);

    await page.getByRole('button', { name: '연결 테스트' }).click();
    const req = await probe.waitForRequest();
    expect(req.payload).toEqual({
      provider: 'OLLAMA',
      model: 'bge-m3',
      baseUrl: 'http://host.docker.internal:11434',
    });
    await expect(page.getByText('연결 성공 · 1024차원')).toBeVisible();

    // 실패: 같은 경로를 400 으로 다시 모킹(나중에 등록한 route 가 우선한다).
    await mockApi(
      page,
      'POST',
      '/api/v1/settings/embedding/test',
      { message: '임베딩 연결 테스트 실패: Ollama 임베딩 호출 실패: 404 Not Found' },
      { status: 400 },
    );
    await page.getByRole('button', { name: '연결 테스트' }).click();
    await expect(page.getByText(/404 Not Found/)).toBeVisible();
  });

  test('모델·차원이 바뀌면 재임베딩 확인 창을 띄우고, 확인해야 저장한다', async ({ authenticatedPage: page }) => {
    const { save, impact } = await setupEmbeddingMocks(page, {
      probe: { body: { dimension: 1536 } },
      impact: { body: { chunks: 120, datasets: 8, rowSearchIndexes: 2 } },
      save: {
        body: createEmbeddingConfig({ provider: 'OPENAI', model: 'text-embedding-3-small', dimension: 1536 }),
      },
    });
    await openTab(page);

    await page.locator('#embedding-provider').click();
    await page.getByRole('option', { name: 'OpenAI' }).click();
    await page.getByLabel('모델').fill('text-embedding-3-small');
    await page.getByLabel('Base URL').fill('https://api.openai.com');
    await page.getByLabel('API 키').fill('sk-test-1234');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    const imp = await impact.waitForRequest();
    expect(imp.searchParams.get('model')).toBe('text-embedding-3-small');
    expect(imp.searchParams.get('dimension')).toBe('1536');

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('bge-m3 (1024) → text-embedding-3-small (1536)');
    await expect(dialog).toContainText('문서 청크 120 · 데이터셋 8 · 행 검색 색인 2');
    await expect(dialog).toContainText('외부 API 는 비용이 발생합니다');
    // 취소하면 저장하지 않는다.
    await dialog.getByRole('button', { name: '취소' }).click();
    expect(save.requests).toHaveLength(0);

    await page.getByRole('button', { name: '저장', exact: true }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장하고 재임베딩' }).click();
    const req = await save.waitForRequest();
    expect(req.payload).toEqual({
      provider: 'OPENAI',
      model: 'text-embedding-3-small',
      baseUrl: 'https://api.openai.com',
      apiKey: 'sk-test-1234',
    });
  });

  test('지원하지 않는 차원은 서버 문구로 막고 저장하지 않는다', async ({ authenticatedPage: page }) => {
    const { save } = await setupEmbeddingMocks(page, {
      probe: { status: 400, body: { message: '지원하지 않는 차원 768 (지원: 1024, 1536)' } },
    });
    await openTab(page);
    await page.getByLabel('모델').fill('nomic-embed-text');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(page.getByText('지원하지 않는 차원 768 (지원: 1024, 1536)')).toBeVisible();
    expect(save.requests).toHaveLength(0);
  });

  test('설정 탭 스트립에 유령 세로 스크롤바가 없다', async ({ authenticatedPage: page }) => {
    // 회귀: TabsList 에 overflow-x-auto 만 주면 overflow-y 가 auto 로 승격돼 유령 세로 스크롤바가 생긴다.
    await setupEmbeddingMocks(page);
    await page.goto('/admin/settings');
    const tablist = page.getByRole('tablist');
    await expect(tablist).toBeVisible();
    expect(await tablist.evaluate((el) => getComputedStyle(el).overflowY)).toBe('hidden');
  });
});

test.describe('재임베딩 카드', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page);
  });

  test('현재 모델·차원과 진행률, 잡 실패 사유를 보여준다', async ({ authenticatedPage: page }) => {
    await setupEmbeddingMocks(page, {
      status: createEmbeddingStatus({
        model: 'text-embedding-3-small',
        dimension: 1536,
        documentChunks: { total: 500, embedded: 340 },
        job: { status: 'FAILED', lastError: 'OpenAI 임베딩 호출 실패: 401 Unauthorized', updatedAt: '2026-09-28T10:00:00Z' },
      }),
    });
    await openTab(page);

    await expect(page.getByText('text-embedding-3-small · 1536차원')).toBeVisible();
    await expect(page.getByText('340 / 500')).toBeVisible();
    await expect(page.getByText('재임베딩 실패')).toBeVisible();
    await expect(page.getByText('OpenAI 임베딩 호출 실패: 401 Unauthorized')).toBeVisible();
  });

  test('전체 재임베딩 실행 시 대상 건수를 토스트로 알린다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupEmbeddingMocks(page);
    const reindex = await mockApi(
      page,
      'POST',
      '/api/v1/admin/embedding/reindex-all',
      { chunks: 10, datasets: 2, rowSearchIndexes: 0 },
      { status: 202, capture: true },
    );
    await openTab(page);

    await page.getByRole('button', { name: '전체 재임베딩 실행' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '실행' }).click();
    await reindex.waitForRequest();
    await expect(page.getByText('재임베딩을 시작했습니다 (문서 청크 10, 데이터셋 2).')).toBeVisible();
  });
});
