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

    // 미설정 배너: 무엇이 멈췄는지 알린다. 검색이 멈춘 상태라 가장 강한 주의(caution) 변형이다(스펙 §6.1).
    await expect(page.getByText('임베딩이 설정되지 않았습니다')).toBeVisible();
    await expect(
      page.locator('[data-variant="caution"]', { hasText: '임베딩이 설정되지 않았습니다' }),
    ).toBeVisible();
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
    // 저장 흐름의 probe 실패는 연결 테스트 결과 줄에도, 저장 실패 토스트에도 같은 서버 문구로 뜬다.
    await expect(page.locator('p[role="status"]')).toHaveText('지원하지 않는 차원 768 (지원: 1024, 1536)');
    await expect(
      page.locator('[data-sonner-toast]', { hasText: '지원하지 않는 차원 768 (지원: 1024, 1536)' }),
    ).toBeVisible();
    expect(save.requests).toHaveLength(0);
  });

  test('입력을 바꾸면 직전 연결 테스트 결과를 지운다', async ({ authenticatedPage: page }) => {
    // 남겨 두면 "연결 성공 · 1024차원" 이 바뀐 모델·주소에 대한 결과처럼 보인다.
    await setupEmbeddingMocks(page, { probe: { body: { dimension: 1024 } } });
    await openTab(page);
    await page.getByRole('button', { name: '연결 테스트' }).click();
    await expect(page.getByText('연결 성공 · 1024차원')).toBeVisible();

    await page.getByLabel('모델').fill('bge-m3-v2');
    await expect(page.locator('p[role="status"]')).toHaveCount(0);
  });

  test('저장 PUT 이 400 이면 서버 문구를 토스트로 보여준다', async ({ authenticatedPage: page }) => {
    // probe·영향도는 통과(영향 0 → 확인 창 없이 PUT)했는데 서버가 저장 시 다시 probe 해 거부한 경우.
    const { save } = await setupEmbeddingMocks(page, {
      save: { status: 400, body: { message: '임베딩 연결 테스트 실패: Ollama 임베딩 호출 실패: 503' } },
    });
    await openTab(page);
    await page.getByLabel('모델').fill('bge-m3-v2');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    const req = await save.waitForRequest();
    expect(req.payload).toMatchObject({ model: 'bge-m3-v2' });
    await expect(
      page.locator('[data-sonner-toast]', { hasText: '임베딩 연결 테스트 실패: Ollama 임베딩 호출 실패: 503' }),
    ).toBeVisible();
    await expect(page.getByText('임베딩 설정을 저장했습니다')).toHaveCount(0);
  });

  test('Base URL 을 바꾸면 키 안내가 바뀌고, 키 없이 저장하면 서버 400 문구를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    // 저장된 OpenAI 키는 저장된 Base URL 에만 쓸 수 있다(서버가 주소만 바꾼 저장의 키 재사용을 거부한다).
    const { probe, save } = await setupEmbeddingMocks(page, {
      config: createEmbeddingConfig({
        provider: 'OPENAI',
        model: 'text-embedding-3-small',
        baseUrl: 'https://api.openai.com',
        dimension: 1536,
        apiKeyMasked: '****ab12',
      }),
      probe: { status: 400, body: { message: 'Base URL 을 바꾸면 API 키를 다시 입력해야 합니다' } },
    });
    await openTab(page);
    await expect(page.getByText('저장된 키 ****ab12 — 비우면 유지됩니다')).toBeVisible();

    // 끝 슬래시만 다른 주소는 서버 정규화상 같은 주소다 — 안내를 바꾸지 않는다.
    await page.getByLabel('Base URL').fill('https://api.openai.com/');
    await expect(page.getByText('저장된 키 ****ab12 — 비우면 유지됩니다')).toBeVisible();

    await page.getByLabel('Base URL').fill('https://proxy.example.com');
    await expect(page.getByText('Base URL 을 바꾸면 API 키를 다시 입력해야 합니다')).toBeVisible();
    await expect(page.getByText('저장된 키 ****ab12 — 비우면 유지됩니다')).toHaveCount(0);

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await probe.waitForRequest();
    // 키 칸이 비었으므로 apiKey 는 보내지 않는다(서버가 저장된 키 재사용 여부를 판정한다).
    expect(req.payload).toEqual({
      provider: 'OPENAI',
      model: 'text-embedding-3-small',
      baseUrl: 'https://proxy.example.com',
    });
    await expect(page.locator('p[role="status"]')).toHaveText('Base URL 을 바꾸면 API 키를 다시 입력해야 합니다');
    await expect(
      page.locator('[data-sonner-toast]', { hasText: 'Base URL 을 바꾸면 API 키를 다시 입력해야 합니다' }),
    ).toBeVisible();
    expect(save.requests).toHaveLength(0);
  });

  test('확인 뒤 저장 PUT 이 도는 동안에는 연결 테스트도 누를 수 없다', async ({ authenticatedPage: page }) => {
    await setupEmbeddingMocks(page, {
      probe: { body: { dimension: 1536 } },
      impact: { body: { chunks: 3, datasets: 0, rowSearchIndexes: 0 } },
    });
    // PUT 을 붙잡아 두는 라우트(나중 등록이 우선). release() 전까지 응답하지 않는다.
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route(
      (url) => url.pathname === '/api/v1/settings/embedding',
      async (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        await gate;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createEmbeddingConfig({ model: 'm2', dimension: 1536 })),
        });
      },
    );
    await openTab(page);
    await page.getByLabel('모델').fill('m2');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장하고 재임베딩' }).click();

    await expect(page.getByRole('button', { name: '연결 테스트' })).toBeDisabled();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeDisabled();
    release();
    await expect(page.getByText('임베딩 설정을 저장했습니다')).toBeVisible();
    await expect(page.getByRole('button', { name: '연결 테스트' })).toBeEnabled();
  });

  test('설정 탭 스트립에 유령 세로 스크롤바가 없다', async ({ authenticatedPage: page }) => {
    // 회귀: TabsList 에 overflow-x-auto 만 주면 overflow-y 가 auto 로 승격돼 유령 세로 스크롤바가 생긴다.
    await setupEmbeddingMocks(page);
    await page.goto('/admin/settings');
    const tablist = page.getByRole('tablist');
    await expect(tablist).toBeVisible();
    expect(await tablist.evaluate((el) => getComputedStyle(el).overflowY)).toBe('hidden');
  });

  test('설정 조회가 실패하면 편집 가능한 빈 폼 대신 재시도 화면이 뜬다', async ({ authenticatedPage: page }) => {
    // 빈 폼을 그리면 "미설정"과 구별되지 않고, 거기서 저장·연결 테스트를 누르면 기존 설정(키 포함)을
    // 덮어쓸 수 있다(리뷰 fix round 1, Important 1). SmtpSettingsTab 의 재시도 화면과 같은 패턴.
    await setupEmbeddingMocks(page);
    let failing = true;
    // setupEmbeddingMocks 가 먼저 등록한 GET 라우트보다 나중에 등록해 우선 적용하고, failing 이
    // 풀리면 route.fallback() 으로 원래(200) 핸들러에 넘긴다.
    await page.route(
      (url) => url.pathname === '/api/v1/settings/embedding',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        if (failing) {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ message: '임베딩 설정 조회 실패' }),
          });
        }
        return route.fallback();
      },
    );

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();

    await expect(page.getByText('임베딩 설정을 불러오지 못했습니다')).toBeVisible();
    // 저장 폼(저장·연결 테스트) 자체가 없어야 한다 — 비활성이 아니라 부재.
    await expect(page.getByLabel('모델')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '연결 테스트' })).toHaveCount(0);

    failing = false;
    await page.getByRole('button', { name: '다시 시도' }).click();
    await expect(page.getByLabel('모델')).toHaveValue('bge-m3');
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
  });

  test('임베딩 탭의 미저장 편집은 다른 탭으로 옮긴 뒤에도 이탈 가드에 잡힌다', async ({
    authenticatedPage: page,
  }) => {
    // 리뷰 fix round 1, Important 2 — 폼 상태가 탭 자신 소유였을 때는 탭을 바꾸는 순간 입력이
    // 경고 없이 사라졌다. 이제 SettingsPage 가 상태를 갖고 이탈 가드에 dirty 를 보고한다
    // (이메일 탭의 동일 회귀 테스트, settings.spec.ts:810 부근 과 같은 패턴).
    await setupEmbeddingMocks(page);
    await openTab(page);
    await page.getByLabel('모델').fill('bge-m3-v2');

    await page.getByRole('tab', { name: 'AI 에이전트' }).click();
    await expect(page.locator('#ai-max-turns')).toBeVisible();
    // 임베딩 탭으로 돌아오면 입력이 살아 있다 — 탭 언마운트로 유실되지 않았다는 직접 증거.
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByLabel('모델')).toHaveValue('bge-m3-v2');

    await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(
      page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
    ).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/admin/settings');
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

  test('잡 실패 사유가 비어 있으면 대체 안내 문구를 보여준다', async ({ authenticatedPage: page }) => {
    await setupEmbeddingMocks(page, {
      status: createEmbeddingStatus({
        job: { status: 'FAILED', lastError: null, updatedAt: '2026-09-28T10:00:00Z' },
      }),
    });
    await openTab(page);

    await expect(page.getByText('재임베딩 실패')).toBeVisible();
    await expect(page.getByText('재임베딩이 실패했습니다. 다시 시도하세요.')).toBeVisible();
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
