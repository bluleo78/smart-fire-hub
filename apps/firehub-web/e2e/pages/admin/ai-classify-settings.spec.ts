import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { createAiClassifyCredential, createAiCredential, createAiSettings } from '../../factories/admin.factory';
import {
  mockAiClassifyCredential,
  mockAiCredential,
  setupAdminAuth,
  setupSettingsMocks,
} from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * #707 — AI 분류(AI_CLASSIFY) 전용 공급자 탭. 미설정이면 AI 에이전트(채팅) 설정을 통째로 쓰고,
 * 설정하면 분류 묶음(인증·키·모델)만 쓴다. 고정 문구는 전체 문자열로 단언한다.
 */

const USING_CHAT = 'AI 에이전트 설정을 사용 중입니다.';
const CLEAR_LABEL = '분류 전용 설정 해제';
const CLEAR_BODY =
  '분류 전용 인증·키·모델이 삭제되고, AI 분류는 AI 에이전트 설정으로 실행됩니다. 저장된 API 키는 복구할 수 없습니다.';
/** `useAiCredentialForm` 이 "저장은 됐는데 다시 못 읽었다"에 쓰는 문구. */
const STALE_NOTICE =
  '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';

const screenshotPath = (name: string) =>
  path.resolve(__dirname, '..', '..', '..', 'test-results', 'tc', 'ai-classify-settings', name);

const classifyPanel = (page: Page) => page.getByRole('tabpanel', { name: 'AI 분류' });

async function openClassifyTab(page: Page) {
  await page.goto('/admin/settings');
  await page.getByRole('tab', { name: 'AI 분류' }).click();
}

test.describe('AI 분류 전용 공급자 탭(#707)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page, { ai: createAiSettings() });
    await mockAiCredential(page, createAiCredential({ agentType: 'cli-api', configured: true, secretFieldNames: ['apiKey'] }));
  });

  test('미설정이면 채팅 설정 사용 배너에 채팅의 에이전트 유형·모델을 보여준다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await mockAiClassifyCredential(page);
    await openClassifyTab(page);

    const panel = classifyPanel(page);
    await expect(panel.getByText('분류 모델 설정')).toBeVisible();
    await expect(panel.getByText(USING_CHAT, { exact: true })).toBeVisible();
    await expect(panel.getByText('Claude API', { exact: true })).toBeVisible(); // AGENT_TYPE_LABELS['cli-api']
    await expect(panel.getByText('claude-sonnet-5')).toBeVisible();
    await expect(panel.getByRole('button', { name: '분류 전용 설정하기' })).toBeVisible();
    await expect(panel.locator('#ai-classify-agent-type')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: CLEAR_LABEL })).toHaveCount(0);
    await page.screenshot({ path: screenshotPath('classify-unconfigured.png'), fullPage: true });
  });

  test('설정하기 → sdk + 토큰 + Haiku 저장 시 PUT 에 모델이 실리고 설정 상태로 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiClassifyCredential(page, () =>
      calls.puts.length > 0
        ? createAiClassifyCredential({ configured: true, secretFieldNames: ['oauthToken'], model: 'claude-haiku-4-5' })
        : createAiClassifyCredential(),
    );
    await openClassifyTab(page);
    const panel = classifyPanel(page);

    await panel.getByRole('button', { name: '분류 전용 설정하기' }).click();
    await expect(panel.getByText('AI 분류에 사용할 에이전트 유형')).toBeVisible();
    // 분류 탭에는 채팅 탭의 "AI 설정이 없습니다" 경고도, 인증 확인 버튼도 없다.
    await expect(panel.getByText('AI 설정이 없습니다. 설정해야 AI 기능을 쓸 수 있습니다.')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: '인증 확인' })).toHaveCount(0);

    await panel.locator('#ai-classify-oauth-token').fill('sk-ant-oat01-classify');
    await panel.locator('#ai-classify-model').click();
    await page.getByRole('option', { name: 'Claude Haiku 4.5' }).click();
    await panel.getByRole('button', { name: '저장' }).click();

    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0]).toEqual({
      agentType: 'sdk',
      payload: {},
      secret: { oauthToken: 'sk-ant-oat01-classify' },
      model: 'claude-haiku-4-5',
    });
    await expect(panel.getByText(USING_CHAT, { exact: true })).toHaveCount(0);
    await expect(panel.getByRole('button', { name: CLEAR_LABEL })).toBeVisible();
    await page.screenshot({ path: screenshotPath('classify-configured.png'), fullPage: true });
  });

  test('모델 없이 저장하면 화면이 막고 PUT 을 보내지 않는다', async ({ authenticatedPage: page }) => {
    const calls = await mockAiClassifyCredential(page);
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    await panel.getByRole('button', { name: '분류 전용 설정하기' }).click();
    await panel.locator('#ai-classify-oauth-token').fill('sk-ant-oat01-x');
    await panel.getByRole('button', { name: '저장' }).click();

    await expect(panel.getByText('분류 모델을 선택하세요', { exact: true })).toBeVisible();
    expect(calls.puts).toHaveLength(0);
  });

  test('opencode 의 모델 불러오기는 분류 probe 로만 가고 채팅 probe 는 부르지 않는다', async ({
    authenticatedPage: page,
  }) => {
    let chatProbes = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/settings/ai-credential/probe',
      (route) => {
        chatProbes += 1;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"models":[],"message":null}' });
      },
    );
    const calls = await mockAiClassifyCredential(page, createAiClassifyCredential(), {
      ok: true,
      models: ['gpt-4o-mini'],
      message: null,
    });
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    await panel.getByRole('button', { name: '분류 전용 설정하기' }).click();
    await panel.locator('#ai-classify-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();
    await panel.locator('#ai-classify-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();
    await panel.locator('#ai-classify-base-url').fill('https://gw.example/v1');
    await panel.locator('#ai-classify-opencode-api-key').fill('sk-classify');
    await panel.getByRole('button', { name: '모델 불러오기' }).click();

    await expect.poll(() => calls.probes.length).toBe(1);
    expect(calls.probes[0]).toEqual({ baseURL: 'https://gw.example/v1', apiKey: 'sk-classify' });
    expect(chatProbes).toBe(0);

    await panel.locator('#ai-classify-model').click();
    await page.getByRole('option', { name: 'gpt-4o-mini' }).click();
    await panel.getByRole('button', { name: '저장' }).click();
    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0]).toMatchObject({ agentType: 'opencode', model: 'openai/gpt-4o-mini' });
  });

  test('설정 해제는 확인창을 거쳐 DELETE 를 보내고 미설정 배너로 돌아온다', async ({ authenticatedPage: page }) => {
    const calls = await mockAiClassifyCredential(page, () =>
      calls.deleteCount > 0
        ? createAiClassifyCredential()
        : createAiClassifyCredential({ configured: true, agentType: 'cli-api', secretFieldNames: ['apiKey'], model: 'claude-haiku-4-5' }),
    );
    await openClassifyTab(page);
    const panel = classifyPanel(page);

    await panel.getByRole('button', { name: CLEAR_LABEL }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByRole('heading', { name: CLEAR_LABEL })).toBeVisible();
    await expect(dialog.getByText(CLEAR_BODY, { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: '취소' }).click();
    expect(calls.deleteCount).toBe(0);

    await panel.getByRole('button', { name: CLEAR_LABEL }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: CLEAR_LABEL }).click();
    await expect.poll(() => calls.deleteCount).toBe(1);
    await expect(panel.getByText(USING_CHAT, { exact: true })).toBeVisible();
  });

  /**
   * 저장은 성공했는데 직후 재조회가 실패한 상태(서버 `configured` 를 아직 못 읽음). 이때 "취소"를
   * 내주면 누른 순간 폼이 접히고 "AI 에이전트 설정을 사용 중입니다." 배너가 뜬다 — 분류 전용
   * 설정은 실제로 저장돼 쓰이는 중이므로 거짓말이다. 그래서 취소 자체를 내주지 않는다.
   */
  test('저장 후 재조회가 실패하면 경고 배너만 남고 취소로 미설정 화면에 빠지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiClassifyCredential(page);
    // 나중에 등록한 route 가 먼저 가로챈다 — PUT 이후의 GET 만 500 으로 떨군다.
    await page.route(
      (url) => url.pathname === '/api/v1/settings/ai-classify-credential',
      (route) =>
        route.request().method() === 'GET' && calls.puts.length > 0
          ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"boom"}' })
          : route.fallback(),
    );

    await openClassifyTab(page);
    const panel = classifyPanel(page);
    await panel.getByRole('button', { name: '분류 전용 설정하기' }).click();
    await panel.locator('#ai-classify-oauth-token').fill('sk-ant-oat01-stale');
    await panel.locator('#ai-classify-model').click();
    await page.getByRole('option', { name: 'Claude Haiku 4.5' }).click();
    await panel.getByRole('button', { name: '저장' }).click();

    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0]).toMatchObject({ agentType: 'sdk', model: 'claude-haiku-4-5' });

    // 토스트에도 같은 문구가 뜨므로 반드시 탭 패널로 좁혀서 단언한다.
    await expect(panel.getByText(STALE_NOTICE, { exact: true })).toBeVisible();
    await expect(panel.getByRole('button', { name: '취소' })).toHaveCount(0);
    await expect(panel.getByRole('button', { name: CLEAR_LABEL })).toHaveCount(0);
    // 폼은 그대로 펼쳐져 있고, 거짓 미설정 배너는 없다.
    await expect(panel.locator('#ai-classify-model')).toBeVisible();
    await expect(panel.getByText(USING_CHAT, { exact: true })).toHaveCount(0);
  });

  test('서버 400 메시지를 그대로 보여준다', async ({ authenticatedPage: page }) => {
    await mockAiClassifyCredential(page);
    const rejection = 'AI 모델(gpt)이 opencode 형식(공급자/모델)이 아니거나 선택한 공급자와 일치하지 않습니다. 관리자 설정에서 모델을 다시 선택하세요.';
    await page.route(
      (url) => url.pathname === '/api/v1/settings/ai-classify-credential',
      (route) =>
        route.request().method() !== 'PUT'
          ? route.fallback()
          : route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: rejection }) }),
    );
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    await panel.getByRole('button', { name: '분류 전용 설정하기' }).click();
    await panel.locator('#ai-classify-oauth-token').fill('sk-ant-oat01-x');
    await panel.locator('#ai-classify-model').click();
    await page.getByRole('option', { name: 'Claude Haiku 4.5' }).click();
    await panel.getByRole('button', { name: '저장' }).click();
    await expect(page.getByText(rejection)).toBeVisible();
  });
});
