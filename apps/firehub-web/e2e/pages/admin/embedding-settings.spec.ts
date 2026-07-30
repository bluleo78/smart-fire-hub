import type { Page } from '@playwright/test';

import type { SettingResponse } from '../../../src/types/settings';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 임베딩 설정 탭 E2E 테스트
 * - 문서 RAG 임베딩 provider 설정의 로드/편집/저장 동작을 검증한다.
 * - GET /settings 는 prefix(ai|embedding)로 분기하며, PUT /settings 는 별도로 캡처한다.
 * - AdminRoute 통과를 위해 setupAdminAuth로 ADMIN 역할을 오버라이드한다.
 */

// AI 탭(기본 탭)이 마운트 시 호출하는 GET /settings?prefix=ai 응답.
const AI_SETTINGS: SettingResponse[] = [
  { key: 'ai.agent_type', value: 'sdk', description: '에이전트 유형', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.model', value: 'claude-sonnet-4-6', description: '모델', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.max_turns', value: '10', description: '최대 턴 수', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.system_prompt', value: '프롬프트', description: '시스템 프롬프트', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.temperature', value: '1.0', description: 'Temperature', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.max_tokens', value: '16384', description: '최대 응답 토큰', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.session_max_tokens', value: '50000', description: '세션 최대 토큰', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.api_key', value: '****masked****', description: 'API 키', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'ai.cli_oauth_token', value: '', description: 'OAuth 토큰', updatedAt: '2024-01-01T00:00:00Z' },
];

// 임베딩 탭 진입 시 호출하는 GET /settings?prefix=embedding 응답.
// api_key는 백엔드가 마스킹하여 내려보낸다.
const EMBEDDING_SETTINGS: SettingResponse[] = [
  { key: 'embedding.provider', value: 'OLLAMA', description: 'provider', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'embedding.model', value: 'bge-m3', description: '모델', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'embedding.base_url', value: 'http://host.docker.internal:11434', description: 'base url', updatedAt: '2024-01-01T00:00:00Z' },
  { key: 'embedding.api_key', value: '****masked****', description: 'API 키', updatedAt: '2024-01-01T00:00:00Z' },
];

/**
 * GET /settings 를 prefix 쿼리 파라미터로 분기해 모킹한다.
 * - prefix=embedding → 임베딩 설정, 그 외(ai) → AI 설정.
 * - PUT 등 다른 메서드는 route.fallback()으로 다음 핸들러(PUT 캡처)에 위임한다.
 */
async function setupGetSettingsMock(page: Page, embedding: SettingResponse[] = EMBEDDING_SETTINGS) {
  await page.route(
    (url) => url.pathname === '/api/v1/settings',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const prefix = new URL(route.request().url()).searchParams.get('prefix');
      const body = prefix === 'embedding' ? embedding : AI_SETTINGS;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
      });
    },
  );
}

test.describe('임베딩 설정 탭', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupGetSettingsMock(page);
  });

  test('임베딩 탭이 렌더링되고 서버 값이 폼에 채워진다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/admin/settings');

    // 임베딩 탭 클릭 → 탭 마운트 시 GET /settings?prefix=embedding 호출
    await page.getByRole('tab', { name: '임베딩' }).click();

    // 카드 제목 + 차원 고정 안내 노출 확인
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
    await expect(
      page.getByText(/임베딩 차원은 1024로 고정됩니다/),
    ).toBeVisible();

    // 서버 값이 폼에 반영되었는지 확인
    await expect(page.locator('#embedding-provider')).toContainText('Ollama');
    await expect(page.locator('#embedding-model')).toHaveValue('bge-m3');
    await expect(page.locator('#embedding-base-url')).toHaveValue(
      'http://host.docker.internal:11434',
    );
    // 마스킹된 api_key가 그대로 표시된다
    await expect(page.locator('#embedding-api-key')).toHaveValue('****masked****');
  });

  // 회귀: 설정 탭 스트립(TabsList)에 overflow-x-auto만 주면 CSS 사양상 overflow-y가
  // visible→auto로 승격되어 탭 콘텐츠가 고정 높이를 미세 초과할 때 유령 세로 스크롤바가 생긴다.
  // overflow-y를 hidden으로 고정해 세로 오버플로가 없어야 한다.
  test('설정 탭 스트립에 유령 세로 스크롤바가 없다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/settings');
    const tablist = page.getByRole('tablist');
    await expect(tablist).toBeVisible();

    // overflow-y가 auto로 승격되지 않고 hidden으로 고정되어야 한다(승격 시 유령 세로 스크롤바 발생).
    // 콘텐츠가 고정 높이를 1px 미세 초과하더라도 hidden이면 스크롤바가 절대 생기지 않는다.
    const overflowY = await tablist.evaluate((el) => getComputedStyle(el).overflowY);
    expect(overflowY).toBe('hidden');
  });

  test('OLLAMA/OPENAI는 선택 가능하고 VOYAGE만 비활성화된다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();

    // Select 열기
    await page.locator('#embedding-provider').click();

    // OLLAMA/OPENAI는 활성, VOYAGE(미구현)만 비활성(aria-disabled="true")
    await expect(page.getByRole('option', { name: 'Ollama' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await expect(page.getByRole('option', { name: 'OpenAI', exact: true })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await expect(page.getByRole('option', { name: 'Voyage (준비 중)' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  test('OpenAI 선택 시 모델/base_url이 OpenAI 기본값으로 자동 교체되고 저장 payload에 담긴다', async ({
    authenticatedPage: page,
  }) => {
    const putCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
    await expect(page.locator('#embedding-model')).toHaveValue('bge-m3');

    // provider를 OpenAI로 전환 → 이전 provider(OLLAMA) 기본값이던 model/base_url이 OpenAI 기본값으로 스왑
    await page.locator('#embedding-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();

    await expect(page.locator('#embedding-provider')).toContainText('OpenAI');
    await expect(page.locator('#embedding-model')).toHaveValue('text-embedding-3-small');
    await expect(page.locator('#embedding-base-url')).toHaveValue('https://api.openai.com');

    // OpenAI는 api_key가 필요하므로 입력(마스킹 상태가 아니라 실제 값 → payload에 포함되어야 함)
    await page.locator('#embedding-api-key').fill('sk-test-key');

    const saveBtn = page.getByRole('button', { name: '저장' }).first();
    await expect(saveBtn).toBeEnabled({ timeout: 3000 });
    await saveBtn.click();

    const req = await putCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      settings: {
        'embedding.provider': 'OPENAI',
        'embedding.model': 'text-embedding-3-small',
        'embedding.base_url': 'https://api.openai.com',
        'embedding.api_key': 'sk-test-key',
      },
    });

    await expect(page.getByText('임베딩 설정이 저장되었습니다.')).toBeVisible({ timeout: 5000 });
  });

  test('모델 수정 후 저장 시 변경된 키가 PUT payload에 담겨 전송된다', async ({
    authenticatedPage: page,
  }) => {
    // PUT 캡처 — goto 이전에 등록 (GET 라우트보다 나중 등록되어 PUT을 먼저 처리)
    const putCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
    await expect(page.locator('#embedding-model')).toHaveValue('bge-m3');

    // 모델 변경 → dirty 감지로 저장 버튼 활성화
    await page.locator('#embedding-model').fill('bge-m3-v2');

    const saveBtn = page.getByRole('button', { name: '저장' }).first();
    await expect(saveBtn).toBeEnabled({ timeout: 3000 });
    await saveBtn.click();

    // PUT payload 검증 — settings 맵에 변경된 model + 기타 비밀이 아닌 키가 포함되어야 한다
    const req = await putCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/settings');
    expect(req.payload).toMatchObject({
      settings: {
        'embedding.provider': 'OLLAMA',
        'embedding.model': 'bge-m3-v2',
        'embedding.base_url': 'http://host.docker.internal:11434',
      },
    });

    // 저장 성공 toast 확인
    await expect(page.getByText('임베딩 설정이 저장되었습니다.')).toBeVisible({ timeout: 5000 });
  });

  test('수정하지 않은 마스킹된 api_key는 PUT payload에서 제외된다', async ({
    authenticatedPage: page,
  }) => {
    const putCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();

    // api_key는 마스킹 상태 그대로 두고, base_url만 변경하여 dirty 상태 생성
    await page.locator('#embedding-base-url').fill('http://localhost:11434');

    const saveBtn = page.getByRole('button', { name: '저장' }).first();
    await expect(saveBtn).toBeEnabled({ timeout: 3000 });
    await saveBtn.click();

    const req = await putCapture.waitForRequest();
    const settings = (req.payload as { settings: Record<string, string> }).settings;
    // 변경한 base_url은 포함, 마스킹된 api_key는 제외되어야 한다
    expect(settings['embedding.base_url']).toBe('http://localhost:11434');
    expect(settings).not.toHaveProperty('embedding.api_key');
  });
});

/**
 * 임베딩 설정 검증 회귀 테스트 (#322 provider/base_url 불일치, #323 API 키 누락)
 * - #322: base_url이 하드코딩된 기본값과 "문자열이 다르기만 하면" 커스텀으로 오판되어 보존되던 문제.
 *   실제 배포에서 흔한 http://localhost:11434(기본값은 host.docker.internal)를 전제로 검증한다.
 * - #323: OpenAI인데 API 키가 비어도 저장이 가능하던 문제 — 인라인 오류 + 저장 비활성 검증.
 */
test.describe('임베딩 설정 검증', () => {
  // 기본값(host.docker.internal)과 호스트만 다른 동종 Ollama 주소 — #322의 실제 재현 전제
  const LOCALHOST_EMBEDDING: SettingResponse[] = [
    { key: 'embedding.provider', value: 'OLLAMA', description: 'provider', updatedAt: '2024-01-01T00:00:00Z' },
    { key: 'embedding.model', value: 'bge-m3', description: '모델', updatedAt: '2024-01-01T00:00:00Z' },
    { key: 'embedding.base_url', value: 'http://localhost:11434', description: 'base url', updatedAt: '2024-01-01T00:00:00Z' },
    { key: 'embedding.api_key', value: '', description: 'API 키', updatedAt: '2024-01-01T00:00:00Z' },
  ];

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  // 임베딩 탭까지 이동하는 공통 단계
  async function openEmbeddingTab(page: Page) {
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
  }

  test('#322 기본값과 다른 Ollama 주소도 OpenAI 전환 시 OpenAI 기본값으로 교체된다', async ({
    authenticatedPage: page,
  }) => {
    await setupGetSettingsMock(page, LOCALHOST_EMBEDDING);
    await openEmbeddingTab(page);

    // 전제: 저장된 base_url이 하드코딩 기본값과 다르다 (예전 코드는 여기서 보존해버렸다)
    await expect(page.locator('#embedding-base-url')).toHaveValue('http://localhost:11434');

    await page.locator('#embedding-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();

    // base_url·모델 모두 OpenAI 기본값으로 교체되어야 한다 (Ollama 주소 잔존 = 버그)
    await expect(page.locator('#embedding-base-url')).toHaveValue('https://api.openai.com');
    await expect(page.locator('#embedding-model')).toHaveValue('text-embedding-3-small');
    // 값이 임의로 사라진 것처럼 보이지 않도록 교체 사실을 안내한다
    await expect(page.getByText(/새 provider 기본값으로 교체되었습니다/)).toBeVisible();
  });

  test('#322 OpenAI provider에 http Base URL을 직접 넣으면 저장이 막히고 오류가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupGetSettingsMock(page, LOCALHOST_EMBEDDING);
    await openEmbeddingTab(page);

    await page.locator('#embedding-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();
    await page.locator('#embedding-api-key').fill('sk-test-key');

    // 사용자가 수동으로 Ollama 주소를 되돌려 넣는 경우도 막아야 한다
    await page.locator('#embedding-base-url').fill('http://localhost:11434');

    await expect(page.getByText('OpenAI provider의 Base URL은 https 주소여야 합니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장' }).first()).toBeDisabled();
  });

  test('#323 OpenAI provider에서 API 키가 비면 저장이 비활성되고 인라인 오류가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 저장된 키가 없는 상태(api_key 빈 값)에서 OpenAI로 전환하는 시나리오
    await setupGetSettingsMock(page, LOCALHOST_EMBEDDING);
    await openEmbeddingTab(page);

    await page.locator('#embedding-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();

    // provider가 바뀌어 dirty지만, API 키가 없으므로 저장은 막혀야 한다
    await expect(page.getByText('OpenAI provider에는 API 키가 필요합니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장' }).first()).toBeDisabled();

    // 키를 채우면 오류가 사라지고 저장이 가능해진다
    await page.locator('#embedding-api-key').fill('sk-test-key');
    await expect(page.getByText('OpenAI provider에는 API 키가 필요합니다.')).toBeHidden();
    await expect(page.getByRole('button', { name: '저장' }).first()).toBeEnabled();
  });

  test('#323 마스킹된 기존 키는 유효한 키로 인정되어 OpenAI 저장을 막지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 서버가 마스킹해 내려준 키(****masked****) = 저장된 키가 있다는 뜻이므로 저장 가능해야 한다
    await setupGetSettingsMock(page);
    await openEmbeddingTab(page);

    await page.locator('#embedding-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();

    await expect(page.getByText('OpenAI provider에는 API 키가 필요합니다.')).toBeHidden();
    await expect(page.getByRole('button', { name: '저장' }).first()).toBeEnabled();
  });

  test('모델이나 Base URL을 비우면 저장이 막힌다', async ({ authenticatedPage: page }) => {
    await setupGetSettingsMock(page);
    await openEmbeddingTab(page);

    await page.locator('#embedding-model').fill('');
    await expect(page.getByText('모델을 입력하세요.')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장' }).first()).toBeDisabled();

    await page.locator('#embedding-model').fill('bge-m3');
    await page.locator('#embedding-base-url').fill('localhost:11434');
    await expect(
      page.getByText('Base URL은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다.'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '저장' }).first()).toBeDisabled();
  });

  test('저장 실패 시 서버가 준 사유가 토스트에 표시된다', async ({ authenticatedPage: page }) => {
    // 서버 검증(400)의 사유가 고정 문구에 덮이지 않고 사용자에게 도달하는지 검증 (#323 항목4).
    // 클라이언트 검증을 통과하는 payload로 저장하되 서버가 400을 주는 상황을 모킹한다.
    await mockApi(
      page,
      'PUT',
      '/api/v1/settings',
      { status: 400, error: 'Bad Request', message: 'OpenAI 임베딩 provider 에는 API 키가 필요합니다' },
      { status: 400 },
    );
    await setupGetSettingsMock(page);
    await openEmbeddingTab(page);

    await page.locator('#embedding-model').fill('bge-m3-v2');
    const saveBtn = page.getByRole('button', { name: '저장' }).first();
    await expect(saveBtn).toBeEnabled();
    await saveBtn.click();

    // 서버 메시지가 그대로 노출되어야 한다 (고정 fallback 문구가 아니라)
    await expect(page.getByText('OpenAI 임베딩 provider 에는 API 키가 필요합니다')).toBeVisible({
      timeout: 5000,
    });
    await expect(page.getByText('임베딩 설정 저장에 실패했습니다.')).toBeHidden();
  });
});

/**
 * 재임베딩 카드 E2E 테스트
 * - provider 설정 카드 아래의 "재임베딩" 카드(현황 + 전체 재임베딩 실행)를 검증한다.
 * - GET /admin/embedding/status 로 현황을, POST /admin/embedding/reindex-all 로 트리거 결과를 모킹한다.
 * - AlertDialog 확인 후 시작 toast가 노출되는지 확인한다.
 */
test.describe('재임베딩 카드', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    // 임베딩 탭 진입 시 provider 폼이 호출하는 GET /settings 모킹 (기존 헬퍼 재사용)
    await setupGetSettingsMock(page);
    // 재임베딩 현황 — 데이터셋은 완료(28/28), 문서 청크는 진행 중(340/500)
    await mockApi(page, 'GET', '/api/v1/admin/embedding/status', {
      model: 'bge-m3',
      datasets: { total: 28, embedded: 28 },
      documentChunks: { total: 500, embedded: 340 },
    });
  });

  test(
    '재임베딩 카드가 현황을 표시하고 전체 재임베딩 실행 시 시작 toast가 노출된다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      // 전체 재임베딩 트리거 — 202 Accepted + 대상 카운트(데이터셋 28, 문서셋 4)
      await mockApi(
        page,
        'POST',
        '/api/v1/admin/embedding/reindex-all',
        { datasets: 28, documentDatasets: 4 },
        { status: 202 },
      );

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '임베딩' }).click();

      // 재임베딩 카드 노출 + 현재 모델 + 문서 청크 진행 카운트 확인
      await expect(page.getByText('재임베딩', { exact: true })).toBeVisible();
      await expect(page.getByText('현재 모델:')).toBeVisible();
      await expect(page.getByText('340 / 500')).toBeVisible();

      // 트리거 버튼 클릭 → AlertDialog 노출 → 다이얼로그 내 "실행" 클릭
      // (트리거는 "전체 재임베딩 실행", 확인 액션은 "실행"이므로 alertdialog로 스코프를 좁힌다)
      await page.getByRole('button', { name: '전체 재임베딩 실행' }).click();
      const dialog = page.getByRole('alertdialog');
      await expect(dialog).toBeVisible();
      await dialog.getByRole('button', { name: '실행' }).click();

      // 시작 toast 확인 (재임베딩을 시작했습니다 (데이터셋 28, 문서셋 4).)
      await expect(page.getByText(/재임베딩을 시작했습니다/)).toBeVisible({ timeout: 5000 });
    },
  );
});
