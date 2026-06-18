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
async function setupGetSettingsMock(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/settings',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const prefix = new URL(route.request().url()).searchParams.get('prefix');
      const body = prefix === 'embedding' ? EMBEDDING_SETTINGS : AI_SETTINGS;
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

  test('VOYAGE / OPENAI provider 옵션은 비활성화되어 선택할 수 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();

    // Select 열기
    await page.locator('#embedding-provider').click();

    // OLLAMA는 활성, 나머지는 비활성(aria-disabled="true")
    await expect(page.getByRole('option', { name: 'Ollama' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await expect(page.getByRole('option', { name: 'Voyage (준비 중)' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await expect(page.getByRole('option', { name: 'OpenAI (준비 중)' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
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
