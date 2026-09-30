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

  /**
   * #724 — 설정된 탭에서 유형을 "구경만" 하고 저장된 유형으로 돌아오면 저장된 모델·payload 가
   * 그대로 돌아와야 한다. 예전에는 돌아올 때도 모델을 비워서, 아무것도 바꾸지 않았는데 폼이 dirty
   * 로 남고(저장 활성) 원래 모델을 기억해 다시 골라야 했다.
   */
  test('설정된 상태에서 유형을 바꿨다 저장된 유형으로 돌아오면 저장된 모델이 복원되고 dirty 가 풀린다(#724)', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiClassifyCredential(
      page,
      createAiClassifyCredential({ configured: true, secretFieldNames: ['oauthToken'], model: 'claude-haiku-4-5' }),
    );
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    const model = panel.locator('#ai-classify-model');
    const save = panel.getByRole('button', { name: '저장' });
    await expect(model).toHaveText('Claude Haiku 4.5');
    await expect(save).toBeDisabled();

    // 다른 유형으로 — 모델 형식이 달라질 수 있으므로 비우는 것은 의도된 동작이다.
    await panel.locator('#ai-classify-agent-type').click();
    await page.getByRole('option', { name: 'Claude API', exact: true }).click();
    await expect(model).toHaveText('모델을 선택하세요');
    await expect(save).toBeEnabled();

    // 저장된 유형으로 복귀 — 저장값과 같은 화면이어야 한다.
    await panel.locator('#ai-classify-agent-type').click();
    await page.getByRole('option', { name: 'Claude Agent SDK', exact: true }).click();
    await expect(model).toHaveText('Claude Haiku 4.5');
    await expect(save).toBeDisabled();
    await expect(panel.getByRole('button', { name: '되돌리기' })).toBeDisabled();
    expect(calls.puts).toHaveLength(0);
  });

  /**
   * #724 — 설정된 탭에도 편집을 버릴 수단(되돌리기)이 있어야 한다. 되돌리기는 네트워크 없이 유형·
   * 비밀 입력·모델을 저장값으로 돌리고, 돌린 뒤에는 저장·되돌리기 모두 비활성이다.
   */
  test('설정된 상태의 되돌리기는 유형·비밀 입력·모델 편집을 저장값으로 돌린다(#724)', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiClassifyCredential(
      page,
      createAiClassifyCredential({ configured: true, secretFieldNames: ['oauthToken'], model: 'claude-haiku-4-5' }),
    );
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    const model = panel.locator('#ai-classify-model');
    const save = panel.getByRole('button', { name: '저장' });
    const revert = panel.getByRole('button', { name: '되돌리기' });
    // 편집이 없으면 되돌릴 것도 없다.
    await expect(revert).toBeDisabled();

    // 유형 전환 + 새 유형의 비밀 입력 — 저장값과 전혀 다른 화면을 만든다.
    await panel.locator('#ai-classify-agent-type').click();
    await page.getByRole('option', { name: 'Claude API', exact: true }).click();
    await panel.locator('#ai-classify-api-key').fill('sk-ant-api03-discard');
    await expect(revert).toBeEnabled();
    await revert.click();

    await expect(panel.locator('#ai-classify-agent-type')).toHaveText('Claude Agent SDK');
    // sdk 유형에도 API 키 칸이 있다 — 되돌린 뒤에는 방금 친 값이 남아 있으면 안 된다.
    await expect(panel.locator('#ai-classify-api-key')).toHaveValue('');
    await expect(model).toHaveText('Claude Haiku 4.5');
    await expect(save).toBeDisabled();
    await expect(revert).toBeDisabled();

    // 모델만 바꾼 편집도 되돌린다.
    await model.click();
    await page.getByRole('option', { name: 'Claude Sonnet 5' }).click();
    await expect(save).toBeEnabled();
    await revert.click();
    await expect(model).toHaveText('Claude Haiku 4.5');
    await expect(save).toBeDisabled();
    // 해제 버튼은 그대로 좌측에 남아 있고, 서버로는 아무것도 가지 않았다.
    await expect(panel.getByRole('button', { name: CLEAR_LABEL })).toBeVisible();
    expect(calls.puts).toHaveLength(0);
    expect(calls.deleteCount).toBe(0);
    await page.screenshot({ path: screenshotPath('classify-configured-revert.png'), fullPage: true });
  });

  /**
   * #730 — 분류 모델 Select 는 폼의 모델이 빈 값이 되면 반드시 placeholder 로 돌아가야 한다.
   * 예전에는 빈 값일 때 `value={undefined}` 를 넘겨 Radix Select 가 비제어 모드로 바뀌었고, 그
   * 사이에 고른 모델이 Radix 내부 상태에 남아 이후 모델이 비워질 때마다 옛 이름이 다시 나타났다
   * (화면은 "Claude Sonnet 5", 저장하면 "분류 모델을 선택하세요").
   */
  test('빈 상태에서 골랐던 모델이, 유형을 오간 뒤 모델이 비워졌을 때 다시 나타나지 않는다(#730)', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiClassifyCredential(
      page,
      createAiClassifyCredential({ configured: true, secretFieldNames: ['oauthToken'], model: 'claude-haiku-4-5' }),
    );
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    const model = panel.locator('#ai-classify-model');
    const agentType = panel.locator('#ai-classify-agent-type');
    await expect(model).toHaveText('Claude Haiku 4.5');

    // 다른 유형으로 가서(모델 비워짐) 모델을 하나 고른다 — 이때 고른 값이 남는 것이 결함이었다.
    await agentType.click();
    await page.getByRole('option', { name: 'Claude API', exact: true }).click();
    await expect(model).toHaveText('모델을 선택하세요');
    await model.click();
    await page.getByRole('option', { name: 'Claude Sonnet 5' }).click();
    await expect(model).toHaveText('Claude Sonnet 5');

    // 저장된 유형으로 복귀(저장값 복원) 후 다시 다른 유형으로 — 모델은 비워져 있어야 한다.
    await agentType.click();
    await page.getByRole('option', { name: 'Claude Agent SDK', exact: true }).click();
    await expect(model).toHaveText('Claude Haiku 4.5');
    await agentType.click();
    await page.getByRole('option', { name: 'Claude API', exact: true }).click();
    await expect(model).toHaveText('모델을 선택하세요');

    // 화면과 폼 상태가 같다 — 저장은 모델 필수 오류로 막히고, 그때도 칸은 placeholder 다.
    await panel.getByRole('button', { name: '저장' }).click();
    await expect(panel.getByText('분류 모델을 선택하세요', { exact: true })).toBeVisible();
    await expect(model).toHaveText('모델을 선택하세요');
    expect(calls.puts).toHaveLength(0);
    await page.screenshot({ path: screenshotPath('classify-model-cleared-placeholder.png'), fullPage: true });
  });

  /**
   * #730 변형 — 미설정 편집에서도 같다. 다른 유형에서 고른 모델이, 처음 유형으로 돌아와 모델이
   * 비워진 뒤에도 보이면 "모델이 골라져 있는데 저장이 비활성"인 모순된 화면이 된다.
   */
  test('미설정 편집에서 다른 유형에서 고른 모델이 유형 복귀 뒤 남아 보이지 않는다(#730)', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiClassifyCredential(page);
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    await panel.getByRole('button', { name: '분류 전용 설정하기' }).click();
    const model = panel.locator('#ai-classify-model');
    const agentType = panel.locator('#ai-classify-agent-type');
    await expect(model).toHaveText('모델을 선택하세요');

    await agentType.click();
    await page.getByRole('option', { name: 'Claude API', exact: true }).click();
    await model.click();
    await page.getByRole('option', { name: 'Claude Sonnet 5' }).click();
    await expect(model).toHaveText('Claude Sonnet 5');

    await agentType.click();
    await page.getByRole('option', { name: 'Claude Agent SDK', exact: true }).click();
    await expect(model).toHaveText('모델을 선택하세요');
    expect(calls.puts).toHaveLength(0);
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

  /**
   * #721 — 분류 탭도 같은 훅(`useAiCredentialForm`)·같은 모델 칸을 쓴다. 분류 probe 응답을 `release()` 전까지
   * 붙잡아 "입력을 바꾼 뒤 응답 도착" 순서를 결정적으로 만든다(나중에 등록한 route 가 fixture 의 probe 를 덮는다).
   */
  async function gateClassifyProbe(page: Page, body: unknown) {
    const probePath = '/api/v1/settings/ai-classify-credential/probe';
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let hits = 0;
    await page.route(
      (url) => url.pathname === probePath,
      async (route) => {
        hits += 1;
        await gate;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );
    return {
      hits: () => hits,
      /** 응답을 풀고 화면이 처리할 시간을 준다 — 기대가 "아무것도 안 나타남"이라 기다릴 양성 신호가 없다. */
      releaseAndSettle: async () => {
        const responded = page.waitForResponse((r) => new URL(r.url()).pathname === probePath);
        release();
        await responded;
        await page.waitForTimeout(300);
      },
    };
  }

  /** 분류 전용 설정을 opencode + 옛 URL + 키로 채우고 `모델 불러오기` 를 누른 상태까지 간다. */
  async function startClassifyProbe(page: Page) {
    await openClassifyTab(page);
    const panel = classifyPanel(page);
    await panel.getByRole('button', { name: '분류 전용 설정하기' }).click();
    await panel.locator('#ai-classify-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();
    await panel.locator('#ai-classify-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();
    await panel.locator('#ai-classify-base-url').fill('https://gateway-a.example.com/v1');
    await panel.locator('#ai-classify-opencode-api-key').fill('sk-classify');
    await panel.getByRole('button', { name: '모델 불러오기' }).click();
    return panel;
  }

  test('모델 불러오기 중 기본 URL 을 바꾸면 늦게 도착한 옛 URL 의 목록을 채우지 않고, 진행 중엔 버튼이 잠긴다(#721)', async ({
    authenticatedPage: page,
  }) => {
    await mockAiClassifyCredential(page, createAiClassifyCredential());
    const probe = await gateClassifyProbe(page, { ok: true, models: ['a-model-1', 'a-model-2'], message: null });
    const panel = await startClassifyProbe(page);
    await expect.poll(probe.hits).toBe(1);
    await expect(panel.getByRole('button', { name: '불러오는 중...' })).toBeDisabled();

    await panel.locator('#ai-classify-base-url').fill('https://gateway-b.example.com/v1');
    // 옛 요청은 버려졌다 — 버튼은 곧바로 새 URL 용으로 되돌아온다.
    await expect(panel.getByRole('button', { name: '모델 불러오기' })).toBeEnabled();
    await probe.releaseAndSettle();

    await expect(panel.getByText(/✓ 모델 \d+개/)).toHaveCount(0);
    await expect(panel.getByPlaceholder('먼저 모델을 불러오세요')).toBeVisible();
    expect(probe.hits()).toBe(1);
  });

  test('모델 불러오기 중 기본 URL 을 바꾸면 늦게 도착한 옛 URL 의 실패를 붙이지 않는다(#721)', async ({
    authenticatedPage: page,
  }) => {
    const failure = '게이트웨이 A 가 자격증명을 거부했습니다.';
    await mockAiClassifyCredential(page, createAiClassifyCredential());
    const probe = await gateClassifyProbe(page, { ok: false, models: [], message: failure });
    const panel = await startClassifyProbe(page);
    await expect.poll(probe.hits).toBe(1);

    await panel.locator('#ai-classify-base-url').fill('https://gateway-b.example.com/v1');
    await probe.releaseAndSettle();

    await expect(panel.getByText(failure)).toHaveCount(0);
    await expect(panel.getByRole('button', { name: '직접 입력으로 전환' })).toHaveCount(0);
    await expect(panel.getByPlaceholder('먼저 모델을 불러오세요')).toBeVisible();
  });
});
