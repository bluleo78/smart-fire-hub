import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { createAiCredential, createAiSettings } from '../../factories/admin.factory';
import {
  AI_AUTH_STATUS_PATH,
  aiAuthStatusResponse,
  mockAiAuthStatus,
  mockAiCredential,
  setupAdminAuth,
  setupSettingsMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

// ESM 환경에서는 `__dirname`이 없으므로 `import.meta.url`로 현재 파일 디렉토리 경로를 계산한다
// (`e2e/pages/ai-chat/dataset-manager.spec.ts`와 같은 관용구).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Task 12 — AI 자격증명(`ai.credential`) 전용 화면 E2E.
 *
 * <b>이 파일이 대체하는 것</b>: 옛 스펙(853줄, 14 테스트)은 삭제된 3키 번들 UI(그룹 배지 ·
 * [재정의 해제] · [저장된 OAuth 토큰 삭제] · 부분 해제 실패)를 검증했다. Task 11 이 그 UI 자체를
 * 지우고 유형별 폼으로 재작성했으므로(`AiCredentialFieldset.tsx`), 그 UI를 겨눈
 * 단언은 전부 "존재하지 않는 요소를 찾다가 타임아웃"으로만 실패해 신호가 없다 — 옛 시나리오의
 * 운명은 각 테스트 앞 주석에 남긴다(무엇을 대체했는지, 왜 그냥 지웠는지).
 *
 * <b>범위</b>: 브리프(`task-12-brief.md`)의 9개 시나리오 + 보강(저장 페이로드가 손대지 않은
 * 비밀을 안 싣는다 / 미저장 입력 경고 문구) + AI 동작 설정 6키가 테넌트 전용 평면 설정으로
 * 그려지는지(플랫폼·재정의 개념 부재, 기본값 힌트, 저장 페이로드). 권위는 설계서
 * (`docs/superpowers/specs/2026-09-19-typed-ai-settings-design.md`) "화면" 절이다 — 카피가
 * 그대로 고정하는 문구는 부분 매치가 아니라 전체 문자열로 단언한다(리뷰가 지적한 "문구가
 * 바뀌어도 초록"을 막기 위해).
 *
 * <b>#706 — 자격증명과 AI 동작 설정은 테넌트 전용이다.</b> 유형 선택 라디오도 삭제(DELETE) 경로도
 * 없다 — "미설정이면 안내 + 폼, 저장은 PUT 하나, DELETE 없음"을 고정한다.
 */

/** 필드 한 개를 감싸는 컨테이너 — `settings.spec.ts` 의 `fieldBox` 와 같은 스코프 규칙. 자격증명
 * 필드(`ai-cred-*`)도 같은 `<div className="space-y-2">` 구조를 쓰므로 그대로 재사용된다. */
const fieldBox = (page: Page, inputId: string) =>
  page.locator('div.space-y-2', { has: page.locator(`#${inputId}`) });

/**
 * 자격증명 `fieldset` 전체. `<fieldset>` 의 기본 접근성 role 은 `group` 이고, 안의 `<legend>`
 * ("자격증명")이 그 group 의 접근 가능한 이름이 된다 — `AiCredentialFieldset.tsx` 가 정상 렌더
 * 경로·조회 실패(`loadFailed`) 경로 둘 다에서 이 legend 를 쓰므로 두 경우 모두 잡힌다.
 *
 * 옛 스펙의 `credentialGroup`(`fieldset:has(#ai-agent-type)`)과 같은 스코프 <b>역할</b>이다 —
 * "재정의 배지·버튼이 이 안에는 없다"를 단언하려면 AI 탭 전체가 아니라 이 안으로 좁혀야 한다.
 * 동작 설정 6키(모델·최대 턴 수 등)는 여전히 개별 배지·버튼을 갖고(설계서 "적용 범위는 AI
 * 자격증명 그룹뿐이다"), 그 요소들과 섞이면 "전부 지웠는데도 통과"하는 공허한 부재 단언이 된다.
 */
const credentialGroup = (page: Page) => page.getByRole('group', { name: '자격증명' });

/** AI 탭 전체(`TabsContent`). 자격증명 그룹 <b>안</b>인지 <b>밖</b>인지를 갈라야 하는 단언에 쓴다
 * (예: 동작 설정 저장 실패 안내가 자격증명 그룹 밖에 있어야 한다는 식의 자리 고정). */
const aiPanel = (page: Page) => page.getByRole('tabpanel', { name: 'AI 에이전트' });

/** 스크린샷 저장 경로 — `apps/firehub-web/test-results/tc/<suite>/` 규약을 따른다. */
const screenshotPath = (name: string) =>
  path.resolve(__dirname, '..', '..', '..', 'test-results', 'tc', 'tenant-ai-settings', name);

/** 테넌트에 자격증명이 없을 때(`configured:false`)의 안내 — 테넌트 전용이라 미설정은 곧 AI
 * 중단이다(#706). 부분 매치가 아니라 전체 문자열로 고정한다. */
const NOT_CONFIGURED_NOTE = 'AI 설정이 없습니다. 설정해야 AI 기능을 쓸 수 있습니다.';

test.describe('테넌트별 AI 자격증명 — 유형별 화면과 미설정 안내(Task 12, #706)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    // 동작 설정 6키(모델·최대 턴 수 등)는 자격증명과 별도 자원이지만 같은 페이지·같은 로딩
    // 게이트를 공유한다(`SettingsPage` 는 `behaviorLoading || cred.isLoading` 을 함께 본다) —
    // 모킹하지 않으면 이 화면 자체가 뜨지 않는다.
    await setupSettingsMocks(page, { ai: createAiSettings() });
  });

  /**
   * #706 핵심 — 미설정 테넌트는 안내 + 직접 설정 폼을 보고, 첫 저장은 PUT 한 번으로 끝난다.
   *
   * <b>뮤테이션 대상</b>:
   *  1. `AiCredentialFieldset.tsx` 의 `!cred.configured` 안내를 지우면 첫 단언이 빨개진다.
   *  2. 옛 라디오를 되살리면 `radio` 부재 단언이 빨개진다.
   *  3. `save()` 가 다시 DELETE 를 보내면 `deleteCount` 단언이 빨개진다.
   *  4. 저장 후 재조회를 지우면 안내가 사라지지 않아 마지막 단언이 빨개진다.
   */
  test('미설정(configured:false)이면 안내와 직접 설정 폼이 보이고, 저장은 PUT 한 번뿐 — DELETE 는 없다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiCredential(page, () =>
      calls.puts.length > 0
        ? createAiCredential({ agentType: 'sdk', configured: true, payload: {}, secretFieldNames: ['oauthToken'] })
        : createAiCredential({ agentType: 'sdk', configured: false, payload: {}, secretFieldNames: [] }),
    );
    await mockAiAuthStatus(page, () => ({ valid: true, email: 'tenant@example.com' }));
    await page.goto('/admin/settings');

    await expect(credentialGroup(page).getByText(NOT_CONFIGURED_NOTE)).toBeVisible();
    // 라디오(플랫폼/직접) 선택지는 없다 — 폼이 곧바로 보인다.
    await expect(aiPanel(page).getByRole('radio')).toHaveCount(0);
    await expect(credentialGroup(page).getByText('플랫폼 설정을 사용')).toHaveCount(0);
    await expect(page.locator('#ai-cred-agent-type')).toBeVisible();
    await expect(page.locator('#ai-cred-oauth-token')).toBeEnabled();
    await expect(fieldBox(page, 'ai-cred-oauth-token').getByText('설정된 값이 없습니다.')).toBeVisible();

    await page.screenshot({ path: screenshotPath('ai-settings-not-configured.png'), fullPage: true });

    await page.locator('#ai-cred-oauth-token').fill('sk-ant-oat01-first');
    // 미설정 상태의 첫 저장은 잃을 비밀이 없으므로 확인 다이얼로그 없이 바로 나간다.
    await page.getByRole('button', { name: '저장' }).click();

    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0]).toEqual({ agentType: 'sdk', payload: {}, secret: { oauthToken: 'sk-ant-oat01-first' } });
    expect(calls.deleteCount).toBe(0);
    await expect(page.getByText('저장했습니다.')).toBeVisible({ timeout: 8000 });
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    // 재조회가 configured:true 를 반영하면 안내가 사라지고 힌트가 "설정됨"으로 바뀐다.
    await expect(credentialGroup(page).getByText(NOT_CONFIGURED_NOTE)).toHaveCount(0);
    await expect(
      fieldBox(page, 'ai-cred-oauth-token').getByText(
        '현재 값이 설정되어 있습니다. 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.',
      ),
    ).toBeVisible();
    expect(calls.deleteCount).toBe(0);
  });

  /** 설정된 테넌트(`configured:true`)에는 미설정 안내도, 옛 라디오도 없다. */
  test('설정됨(configured:true)이면 미설정 안내가 없고 저장된 비밀이 힌트로 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    await expect(page.locator('#ai-cred-oauth-token')).toBeVisible();
    await expect(credentialGroup(page).getByText(NOT_CONFIGURED_NOTE)).toHaveCount(0);
    await expect(aiPanel(page).getByRole('radio')).toHaveCount(0);
    await expect(
      fieldBox(page, 'ai-cred-api-key').getByText(
        '현재 값이 설정되어 있습니다. 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.',
      ),
    ).toBeVisible();

    await page.screenshot({ path: screenshotPath('ai-settings-configured.png'), fullPage: true });
  });

  /**
   * 서버의 비밀 필수 규칙(#706: 평면 무관하게 sdk/cli/cli-api 는 비밀이 최소 하나 있어야 한다)이
   * 400 으로 거부하면 그 한국어 메시지가 그대로 토스트로 보여야 한다 — 화면이 "저장했습니다"로
   * 뭉개거나 일반 문구로 바꾸면 사용자는 무엇을 넣어야 하는지 모른다.
   */
  test('비밀 없이 저장해 서버가 400 으로 거부하면 서버 메시지를 그대로 보여준다', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: false, payload: {}, secretFieldNames: [] }),
    );
    const rejection = 'cli 자격증명은 oauthToken 중 최소 하나가 있어야 한다 — 값을 입력하세요.';
    // mockAiCredential 보다 나중에 등록한 라우트가 먼저 잡는다 — PUT 만 400 으로 가로채고 나머지는 넘긴다.
    let rejectedPuts = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/settings/ai-credential',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        rejectedPuts += 1;
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ status: 400, error: 'Bad Request', message: rejection }),
        });
      },
    );
    await page.goto('/admin/settings');

    // 유형만 cli 로 바꾸고(미설정이라 유형 전환 경고·확인 다이얼로그가 없다) 비밀 없이 저장한다.
    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'Claude Code CLI', exact: true }).click();
    await expect(page.getByText('유형을 바꾸면 이전 유형', { exact: false })).toHaveCount(0);
    await page.getByRole('button', { name: '저장' }).click();

    await expect.poll(() => rejectedPuts).toBe(1);
    await expect(page.getByText(rejection)).toBeVisible();
    await expect(page.getByText('저장했습니다.')).toHaveCount(0);
    expect(calls.deleteCount).toBe(0);
    // 실패했으므로 미설정 안내는 그대로다.
    await expect(credentialGroup(page).getByText(NOT_CONFIGURED_NOTE)).toBeVisible();
  });

  /**
   * 브리프 시나리오 2 — "유형(맨 앞) → 유형별 필드"(설계서 §180 공통 레이아웃). opencode 는
   * Anthropic 인증 개념이 없으므로 OAuth 토큰이 사라지는 것까지 같은 화면에서 고정한다.
   */
  test('유형을 opencode 로 바꾸면 공급자·기본 URL·API 키·추론 강도가 나타나고 OAuth 토큰은 사라진다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    // 전제: sdk 화면엔 OAuth 토큰·API 키가 둘 다 있다.
    await expect(page.locator('#ai-cred-oauth-token')).toBeVisible();
    await expect(page.locator('#ai-cred-api-key')).toBeVisible();

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();

    // 유형별 필드 — 공급자·기본 URL·API 키·추론 강도(설계서 §180 표의 opencode 행).
    await expect(page.locator('#ai-cred-provider')).toBeVisible();
    await expect(page.locator('#ai-cred-base-url')).toBeVisible();
    await expect(page.locator('#ai-cred-opencode-api-key')).toBeVisible();
    await expect(page.locator('#ai-cred-reasoning-effort')).toBeVisible();
    // sdk 전용 입력은 사라진다 — id 가 달라 opencode API 키와 혼동될 여지가 없다.
    await expect(page.locator('#ai-cred-oauth-token')).toHaveCount(0);
    await expect(page.locator('#ai-cred-api-key')).toHaveCount(0);

    // 모델 칸도 opencode 4상태로 바뀌고, [모델 불러오기] 가 그 옆에 있다("모델 칸 옆" 배치는
    // 설계서 "화면" 절의 명시 요구다).
    await expect(page.locator('#ai-model')).toBeVisible();
    await expect(page.getByRole('button', { name: '모델 불러오기' })).toBeVisible();

    // 추론 강도는 opencode 전용, `기본값` + low/medium/high 네 옵션이다(설계서 "화면" 절).
    await page.locator('#ai-cred-reasoning-effort').click();
    await expect(page.getByRole('option', { name: '기본값', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'low', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'medium', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'high', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');

    await page.screenshot({ path: screenshotPath('opencode-fields.png'), fullPage: true });
  });

  /** 브리프 시나리오 3 — [모델 불러오기] 가 Select 를 채운다. */
  test('[모델 불러오기] 를 누르면 모델 칸이 Select 로 바뀌고 목록이 채워진다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(
      page,
      createAiCredential({
        agentType: 'opencode',
        configured: true,
        payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
        secretFieldNames: ['apiKey'],
      }),
    );
    await mockApi(page, 'POST', '/api/v1/settings/ai-credential/probe', {
      ok: true,
      models: ['gpt-4o', 'gpt-4o-mini'],
      message: null,
    });

    await page.goto('/admin/settings');

    // 전제: 저장된 API 키가 있어(유형·기본 URL 도 저장된 값과 같다) 아무것도 새로
    // 입력하지 않아도 버튼이 이미 활성이다.
    const loadButton = page.getByRole('button', { name: '모델 불러오기' });
    await expect(loadButton).toBeEnabled();
    await loadButton.click();

    const modelField = page.locator('#ai-model');
    await expect(page.getByText('✓ 모델 2개')).toBeVisible();
    await modelField.click();
    await expect(page.getByRole('option', { name: 'gpt-4o', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'gpt-4o-mini', exact: true })).toBeVisible();
    await page.getByRole('option', { name: 'gpt-4o', exact: true }).click();
    await expect(modelField).toContainText('gpt-4o');
  });

  /** 브리프 시나리오 4 — 기본 URL 을 고치면 이미 불러온 목록이 무효화되고 미로드로 돌아간다
   * (미로드는 "먼저 모델을 불러오세요" placeholder 로, 실패와 다른 모양이어야 한다). */
  test('기본 URL 을 고치면 모델 칸이 미로드 상태로 되돌아간다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(
      page,
      createAiCredential({
        agentType: 'opencode',
        configured: true,
        payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
        secretFieldNames: ['apiKey'],
      }),
    );
    await mockApi(page, 'POST', '/api/v1/settings/ai-credential/probe', { ok: true, models: ['gpt-4o'], message: null });

    await page.goto('/admin/settings');
    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect(page.getByText('✓ 모델 1개')).toBeVisible();

    await page.locator('#ai-cred-base-url').fill('https://api.openai.com/v2');

    const unloaded = page.getByPlaceholder('먼저 모델을 불러오세요');
    await expect(unloaded).toBeVisible();
    await expect(unloaded).toBeDisabled();
    await expect(page.getByText('✓ 모델 1개')).toHaveCount(0);
  });

  /** 브리프 시나리오 5 — 프로브 실패는 미로드와 다른 모양이어야 하고("실패했을 때 자유 입력으로
   * 갈 길을 준다", 설계서), 전환 버튼을 눌러야만 자유 입력이 열린다(자동으로 열리면 안 된다 —
   * 그러면 "실패"와 "직접 입력을 선택했다"가 구별되지 않는다). */
  test('프로브 실패는 오류 문구 + "직접 입력으로 전환" 을 보여주고, 눌러야만 자유 입력이 열린다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(
      page,
      createAiCredential({
        agentType: 'opencode',
        configured: true,
        payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
        secretFieldNames: ['apiKey'],
      }),
    );
    await mockApi(page, 'POST', '/api/v1/settings/ai-credential/probe', {
      ok: false,
      models: [],
      message: '인증에 실패했습니다.',
    });

    await page.goto('/admin/settings');
    await page.getByRole('button', { name: '모델 불러오기' }).click();

    await expect(page.getByText('인증에 실패했습니다.')).toBeVisible();
    const switchButton = page.getByRole('button', { name: '직접 입력으로 전환' });
    await expect(switchButton).toBeVisible();
    // 실패 상태는 미로드와 같은 placeholder 를 쓰지만(값 없음), disabled 인 채로 오류 문구와
    // 함께 있다 — 전환 버튼을 누르기 전까지는 아직 자유 입력이 아니다.
    await expect(page.getByPlaceholder('먼저 모델을 불러오세요')).toBeVisible();
    await expect(page.getByPlaceholder('예: gpt-4o')).toHaveCount(0);

    await switchButton.click();
    await expect(page.getByPlaceholder('예: gpt-4o')).toBeEnabled();
    await expect(page.getByText('직접 입력한 모델을 사용합니다.')).toBeVisible();

    await page.screenshot({ path: screenshotPath('probe-failure-manual-switch.png'), fullPage: true });
  });

  /**
   * 보강 — 조회 실패는 <b>미설정과 다른 모양</b>이어야 한다(이 브랜치가 "네트워크 실패를 확신에
   * 찬 사실처럼 렌더"한 결함을 낸 전례가 있어 각별히 겨눈다). GET 500 은 `cred.loadFailed` 로
   * 떨어지고, 훅은 안전한 초기값(`configured:false, secretFieldNames:[]`)으로 주저앉는다 — 그
   * 초기값을 그대로 평소 렌더 경로에 흘리면 "AI 설정이 없습니다"를 <b>사실</b>처럼 보여주게 된다
   * (실제로는 "몰라서" 못 그리는 것뿐인데 "없다"고 단정하는 것이다).
   *
   * <b>뮤테이션 대상</b>: `AiCredentialFieldset.tsx` 의 `if (cred.loadFailed) return ...` 가드를
   * 지우면 이 테스트가 빨개진다 — 실패 안내 대신 미설정 안내·폼이 렌더된다.
   */
  test('자격증명 조회가 실패하면 미설정 안내와 다른 실패 안내가 뜬다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/settings/ai-credential', {}, { status: 500 });
    await page.goto('/admin/settings');

    await expect(
      credentialGroup(page).getByRole('alert').filter({ hasText: '자격증명 정보를 불러오지 못했습니다' }),
    ).toBeVisible();
    // 미설정 문구와 섞이면 안 된다 — 둘은 서로 다른 사실(모른다 vs 없다)을 말한다.
    await expect(credentialGroup(page).getByText(NOT_CONFIGURED_NOTE)).toHaveCount(0);
    // 실패 화면은 폼을 전혀 그리지 않는다.
    await expect(page.locator('#ai-cred-agent-type')).toHaveCount(0);
  });

  /**
   * 보강 — 잠금 상태의 유일한 실재 경로는 GET 403(`ai:settings` 권한 없음)이다
   * (`useAiCredentialForm.ts` 의 `isLocked` 주석). 403 은 `applyResponse` 를 아예 못 부르는 경로라
   * 이 시점의 `configured` 는 훅의 초기값(false)이다 — 그 초기값으로 미설정 안내를 그리면 "볼
   * 권한이 없다"를 "설정이 없다"로 단정하게 된다. `loadFailed` 가드가 막는 것과 같은 종류의 결함.
   *
   * <b>뮤테이션 대상</b>: `AiCredentialFieldset.tsx` 의 `if (cred.isLocked) return ...` 가드를
   * 지우면 이 테스트가 빨개진다.
   */
  test('자격증명 조회가 403 이면 권한 안내만 뜨고 폼·미설정 안내는 그리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/settings/ai-credential', {}, { status: 403 });
    await page.goto('/admin/settings');

    await expect(credentialGroup(page).getByText('AI 자격증명을 조회·변경할 권한이 없습니다.')).toBeVisible();
    // 핵심: "권한 없음"이 "설정 없음"으로 오해되면 안 된다.
    await expect(credentialGroup(page).getByText(NOT_CONFIGURED_NOTE)).toHaveCount(0);
    await expect(page.locator('#ai-cred-agent-type')).toHaveCount(0);
  });

  /** 브리프 시나리오 7 — 유형 전환 경고. Select 아래 정적 안내 + 저장 확인 다이얼로그 양쪽에
   * 같은 사실이 실려야 한다(설계서 "유형 전환": "저장 확인에도 포함한다"). */
  test('유형을 바꾸면 이전 유형의 저장된 비밀이 삭제된다는 경고가 뜨고, 저장 확인에도 같은 내용이 실린다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    // 아직 안 바꿨으면 경고가 없다 — 늘 붙어 있으면 배경 소음이 된다.
    await expect(page.getByText('유형을 바꾸면 이전 유형', { exact: false })).toHaveCount(0);

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'Claude API', exact: true }).click();

    await expect(
      page.getByText('유형을 바꾸면 이전 유형(Claude Agent SDK)의 저장된 비밀이 삭제됩니다. 복구할 수 없습니다.'),
    ).toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('자격증명 유형을 바꿀까요?')).toBeVisible();
    await expect(
      dialog.getByText(
        '유형을 Claude Agent SDK에서 Claude API(으)로 바꾸면 이전 유형의 저장된 비밀이 삭제됩니다. 복구할 수 없습니다.',
      ),
    ).toBeVisible();
  });

  /**
   * 브리프 시나리오 8 — 비밀 필드는 <b>절대</b> 서버 마스크를 시드하지 않는다(설계서 "비밀
   * 필드": "마스크 문자열은 어떤 경로로도 입력칸에 들어가지 않는다"). 설정 여부는 힌트 문구로만
   * 전달된다 — 값 단언(`toHaveValue`)은 두 상태 모두 `''` 라 공허하므로 힌트를 함께 본다.
   */
  test('비밀 입력은 항상 비어 있고, 설정 여부는 힌트 문구로만 전달된다', async ({ authenticatedPage: page }) => {
    // OAuth 토큰은 미설정, API 키는 설정됨 — 두 힌트를 한 화면에서 대조한다.
    await mockAiCredential(page, createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['apiKey'] }));
    await page.goto('/admin/settings');

    await expect(page.locator('#ai-cred-oauth-token')).toHaveValue('');
    await expect(page.locator('#ai-cred-api-key')).toHaveValue('');

    await expect(fieldBox(page, 'ai-cred-oauth-token').getByText('설정된 값이 없습니다.')).toBeVisible();
    await expect(
      fieldBox(page, 'ai-cred-api-key').getByText(
        '현재 값이 설정되어 있습니다. 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.',
      ),
    ).toBeVisible();
  });

  /** 브리프 시나리오 9 — "인증 확인" 은 sdk/cli/cli-api 세 유형의 검증 수단이고, opencode 는
   * Anthropic 인증 개념이 없어 이 버튼 자체가 없다(설계서 §184-189 표, 검증 수단은 [모델
   * 불러오기]로 갈린다). */
  test('opencode 에는 "인증 확인" 버튼이 없고, sdk 에는 있다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    // sdk — OAuth 토큰·API 키 각각 옆에 하나씩, 그룹 안에 총 둘.
    await expect(credentialGroup(page).getByRole('button', { name: '인증 확인' })).toHaveCount(2);

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();

    await expect(credentialGroup(page).getByRole('button', { name: '인증 확인' })).toHaveCount(0);
  });

  /**
   * 보강 — 요청 쪽 핵심 불변식(브리프 상위 지시: "저장하는 것이 untouched secret 에 대해 빈
   * 문자열을 보내지 않는다"). 빈 문자열을 보내면 서버 계약상 "삭제"라(`AiCredentialUpsertPayload`
   * 주석), 손대지 않은 OAuth 토큰이 API 키 하나 바꾼 저장에 딸려 사라진다 — 화면 단언만으로는
   * 잡히지 않고 PUT 바디를 직접 봐야 한다.
   */
  test('저장 페이로드는 손대지 않은 비밀을 싣지 않는다 — 생략이지 빈 문자열이 아니다', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await mockAiAuthStatus(page, () => ({ valid: true, email: 'tenant@example.com' }));

    await page.goto('/admin/settings');

    // API 키만 새로 입력한다 — OAuth 토큰 입력칸은 처음부터 빈 채로 둔다(유형도 안 바꾸므로
    // 확인 다이얼로그 없이 바로 저장된다).
    await page.locator('#ai-cred-api-key').fill('sk-ant-new-key');
    await page.getByRole('button', { name: '저장' }).click();

    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0]).toEqual({ agentType: 'sdk', payload: {}, secret: { apiKey: 'sk-ant-new-key' } });
    expect(calls.puts[0].secret).not.toHaveProperty('oauthToken');
    expect(calls.deleteCount).toBe(0);
  });

  /**
   * 보강(fix round 1 리뷰 #2) — 위 테스트는 쓰기(PUT) 횟수·페이로드만 봤을 뿐, 저장 성공 뒤
   * `cred.save()` 가 스스로 거는 재조회(`fetchAndApply`)와 `SettingsPage.tsx:275` 의
   * `verifyAuth()` 호출은 전혀 관찰하지 않았다 — 둘 다 지워도 "저장 페이로드" 단언은 그대로
   * 통과한다. `mockAiCredential` 의 자기 참조 함수형 `get`(fixture 주석 참고: `const calls =
   * await mockAiCredential(page, () => ... calls.puts.length ...)`)으로 "저장 전/후 다른 GET
   * 응답"을 실제로 구성해야만 재조회 여부를 <b>화면에서</b> 구별할 수 있다.
   *
   * <b>뮤테이션 대상 둘</b>:
   *  1. `useAiCredentialForm.ts` 의 `save()` 에서 성공 후 `fetchAndApply()` 호출을 지우면 — API
   *     키 힌트가 저장 전 상태("설정된 값이 없습니다")에 그대로 머물러 이 테스트가 빨개진다.
   *  2. `SettingsPage.tsx` 의 `performSave` 에서 비-opencode 분기의 `verifyAuth()` 호출을
   *     지우면 — 한 번도 "인증 확인"을 누르지 않았는데 배지가 나타나야 하는 자리가 끝내
   *     비어 있어 역시 빨개진다.
   */
  test('저장이 성공하면 서버 값으로 실제로 재조회되고, 인증 배지도 자동으로 다시 읽힌다', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiCredential(page, () =>
      createAiCredential({
        agentType: 'sdk',
        configured: true,
        // 저장(PUT) 전엔 API 키가 없다가, 저장 후에만 서버가 "있다"고 답한다 — 재조회가 실제로
        // 일어나야만 화면이 이 전환을 따라간다.
        secretFieldNames: calls.puts.length > 0 ? ['oauthToken', 'apiKey'] : ['oauthToken'],
      }),
    );
    await mockAiAuthStatus(page, () => ({ valid: true, email: 'tenant@example.com' }));

    await page.goto('/admin/settings');

    // 전제: 저장 전엔 API 키 힌트가 "없음"이고, "인증 확인"을 한 번도 안 눌렀으니 배지도 없다.
    await expect(fieldBox(page, 'ai-cred-api-key').getByText('설정된 값이 없습니다.')).toBeVisible();
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false })).toHaveCount(0);

    await page.locator('#ai-cred-api-key').fill('sk-ant-new-key');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('저장했습니다.')).toBeVisible({ timeout: 8000 });

    // 핵심 1: 재조회가 실제로 일어났다 — 힌트가 서버의 새 응답을 따라 "있음"으로 바뀐다.
    await expect(
      fieldBox(page, 'ai-cred-api-key').getByText(
        '현재 값이 설정되어 있습니다. 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.',
      ),
    ).toBeVisible();
    // 핵심 2: "인증 확인"을 누른 적이 없는데도 배지가 나타난다 — `verifyAuth()` 가 저장 성공
    // 뒤에 자동으로 불렸다는 증거다.
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false }).first()).toBeVisible();
  });

  /**
   * 보강(fix round 1 리뷰 #3) — 늦게 도착한 낡은 인증 확인이 새 결과를 덮어쓰면 안 된다
   * (`SettingsPage.tsx` 의 `verifySeqRef`, latest-request-wins 가드 — §132 주석). 옛 스펙의
   * 경쟁 조건 테스트(옛 13번)는 "저장된 OAuth 토큰 삭제" 버튼으로 두 번째 `verifyAuth` 호출을
   * 만들었는데 그 버튼이 새 UI 에 없어 그대로 옮길 수 없었다 — 대신 이 화면에 남아 있는 <b>두
   * 번째</b> 호출부, 즉 바로 위 테스트가 증명한 <b>저장 성공 후 자동 `verifyAuth()`</b> 로 같은
   * 경쟁을 재현한다: "인증 확인"을 눌러 첫 번째 호출을 보류시킨 채 자격증명을 바꿔 저장해 두
   * 번째 호출을 먼저 응답받고, 그 다음에야 첫 번째(낡은) 응답을 풀어준다.
   *
   * <b>인과적 배리어를 쓴다</b>(고정 대기 아님, 옛 13번과 같은 이유) — 고정 시간은 느린
   * 러너에서 가드가 없어도 통과하는 <b>거짓 통과</b>를 만든다.
   */
  test('인증 확인이 진행 중일 때 저장하면, 늦게 도착한 낡은 응답이 새 결과를 덮어쓰지 못한다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    let authCalls = 0;
    let releaseStaleAuth = false;
    let staleAuthFulfilled = false;
    await page.route(
      (url) => url.pathname === AI_AUTH_STATUS_PATH,
      async (route) => {
        authCalls += 1;
        if (authCalls === 1) {
          // A — 저장 전에 누른 "인증 확인". `valid:true` 를 들고 <b>가장 늦게</b> 도착한다.
          // 라우트 핸들러 안에서는 `page.waitForTimeout` 을 쓸 수 없다(옛 13번과 같은 이유).
          const deadline = Date.now() + 15_000;
          while (!releaseStaleAuth && Date.now() < deadline) {
            await new Promise((resolve) => setTimeout(resolve, 50));
          }
          await route.fulfill(aiAuthStatusResponse({ valid: true, email: 'stale@example.com' }));
          staleAuthFulfilled = true;
          return;
        }
        // B — 저장 성공 뒤 자동으로 걸리는 두 번째 확인. 즉시 응답하고, 결과는 실패다.
        return route.fulfill(aiAuthStatusResponse({ valid: false }));
      },
    );

    await page.goto('/admin/settings');

    // (A) "인증 확인"을 눌러 첫 번째 호출을 시작하고 응답을 붙잡아 둔다.
    await page.getByRole('button', { name: '인증 확인' }).first().click();
    await expect.poll(() => authCalls).toBe(1);

    // (B) 그 사이 API 키를 바꿔 저장한다 — 유형은 안 건드렸으니 확인 다이얼로그 없이 바로
    // 저장되고, 성공하면 `performSave` 가 두 번째 `verifyAuth()` 를 자동으로 건다.
    await page.locator('#ai-cred-api-key').fill('sk-ant-second-call');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('저장했습니다.')).toBeVisible({ timeout: 8000 });
    await expect.poll(() => authCalls).toBe(2);
    // B 의 답(무효)이 먼저 화면에 도달한다.
    await expect(aiPanel(page).getByText('✗ 유효하지 않음', { exact: false }).first()).toBeVisible({
      timeout: 8000,
    });

    // 이제 A 를 풀어 준다 — `valid:true` 가 <b>마지막에</b> 도착한다.
    const staleResponse = page.waitForResponse((res) => res.url().includes(AI_AUTH_STATUS_PATH));
    releaseStaleAuth = true;
    await expect.poll(() => staleAuthFulfilled).toBe(true);
    await staleResponse;
    await page.evaluate(
      () =>
        new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );

    // 핵심: 이미 낡은 A 의 `valid:true` 가 B(더 최신)의 결과를 되돌리면 안 된다.
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false })).toHaveCount(0);
    await expect(aiPanel(page).getByText('✗ 유효하지 않음', { exact: false }).first()).toBeVisible();
  });

  /**
   * 보강 — 자격증명 그룹에 옛 배지·[재정의 해제] 버튼·라디오가 없다. 폼 자체가 그려져 있음을 함께
   * 단언해 "그룹이 비어서" 공허하게 통과하지 않게 한다.
   */
  /**
   * 인증 배지는 서버가 **실제로 검증한 칸**에만 붙는다(`AiController.getAuthStatus` 와 같은 규칙).
   * 예전엔 결과 하나를 두 칸에 모두 붙여, 비어 있는 OAuth 칸까지 "✗ 유효하지 않음"으로 보였다
   * (2026-09-27 라이브 검증).
   *
   * <b>뮤테이션 대상</b>: `verifiedSecretField` 조건을 지우면(두 칸 모두 배지) 각 경우의 "다른
   * 칸엔 배지 없음" 단언이 빨개진다.
   */
  for (const c of [
    { name: 'sdk + API 키만 저장 → API 키 칸', agentType: 'sdk', saved: ['apiKey'], on: 'ai-cred-api-key', off: 'ai-cred-oauth-token' },
    { name: 'sdk + 둘 다 저장 → OAuth 칸(OAuth 우선)', agentType: 'sdk', saved: ['oauthToken', 'apiKey'], on: 'ai-cred-oauth-token', off: 'ai-cred-api-key' },
    { name: 'cli-api → API 키 칸', agentType: 'cli-api', saved: ['apiKey'], on: 'ai-cred-api-key', off: null },
  ] as const) {
    test(`인증 배지는 검증된 칸에만 붙는다 — ${c.name}`, async ({ authenticatedPage: page }) => {
      await mockAiCredential(page, createAiCredential({ agentType: c.agentType, secretFieldNames: [...c.saved] }));
      await mockAiAuthStatus(page, () => ({ valid: false }));

      await page.goto('/admin/settings');
      await fieldBox(page, c.on).getByRole('button', { name: '인증 확인' }).click();

      await expect(fieldBox(page, c.on).getByText('✗ 유효하지 않음')).toBeVisible();
      await expect(aiPanel(page).getByText('✗ 유효하지 않음')).toHaveCount(1);
      if (c.off) await expect(fieldBox(page, c.off).getByText('✗ 유효하지 않음')).toHaveCount(0);
    });
  }

  test('자격증명 그룹엔 상태 배지도 [재정의 해제] 버튼도 라디오도 없다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    await expect(credentialGroup(page).locator('#ai-cred-oauth-token')).toBeVisible();
    await expect(credentialGroup(page).getByRole('button', { name: /재정의 해제/ })).toHaveCount(0);
    await expect(credentialGroup(page).getByText(/재정의|플랫폼|오버라이드|상속|우리 조직 값/)).toHaveCount(0);
    await expect(aiPanel(page).getByRole('radio')).toHaveCount(0);
  });
});

/** 기본값 힌트 — 라벨 옆의 작은 배지. 옵션 이름 등과 섞이지 않게 전체 문자열로 찾는다. */
const defaultHint = (scope: ReturnType<Page['locator']>) => scope.getByText('기본값', { exact: true });

/** 시스템 프롬프트는 라벨 대신 카드 제목 줄에 힌트가 붙어 카드 단위로 스코프를 잡는다. */
const systemPromptCard = (page: Page) => page.locator('div.card-hover', { has: page.locator('#ai-system-prompt') });

/**
 * AI 동작 설정 6키(모델·최대 턴 수·Temperature·최대 응답 토큰·세션 최대 토큰·시스템 프롬프트)는
 * <b>테넌트 전용 평면 설정</b>이다. 서버는 6키를 항상 내려주고, 저장 안 한 키는 코드 기본값을
 * `overridden:false` 로 준다. 화면은 플랫폼 상속·재정의·잠금 개념 없이 평범한 폼으로 그리고,
 * 저장 안 한 필드에만 "기본값" 힌트를 붙인다.
 */
test.describe('AI 동작 설정 — 테넌트 전용 평면 설정', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockAiCredential(page, createAiCredential());
  });

  /**
   * <b>뮤테이션 대상</b>:
   *  1. 옛 상태 배지(`SettingStateBadge`)나 [재정의 해제] 버튼을 되살리면 문구 부재 단언이 빨개진다.
   *  2. 힌트를 `overridden` 과 무관하게 항상 그리면 저장된 Temperature 의 힌트 부재 단언이 빨개진다.
   *  3. 힌트를 지우면 최대 턴 수·시스템 프롬프트의 힌트 존재 단언이 빨개진다.
   *  4. `tenantEditable` 기반 disabled 를 되살려도 서버가 true 를 주므로 통과한다 — 그래서 입력
   *     활성 단언은 "잠금이 끼어들지 않았다"는 확인일 뿐이다.
   */
  test('플랫폼·재정의 문구가 없고, 저장 안 한 필드엔 기본값 힌트가, 저장된 필드엔 값만 보인다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupSettingsMocks(page, {
      ai: createAiSettings({ 'ai.temperature': { overridden: true, value: '0.7' } }),
    });
    await page.goto('/admin/settings');

    const panel = aiPanel(page);
    // 저장 안 한 필드: 서버가 내려준 코드 기본값 + "기본값" 힌트
    await expect(page.locator('#ai-max-turns')).toHaveValue('10');
    await expect(defaultHint(fieldBox(page, 'ai-max-turns'))).toBeVisible();
    await expect(page.locator('#ai-session-max-tokens')).toHaveValue('50000');
    await expect(defaultHint(fieldBox(page, 'ai-session-max-tokens'))).toBeVisible();
    await expect(defaultHint(systemPromptCard(page))).toBeVisible();
    // 저장된 필드: 값만 보이고 힌트는 없다
    await expect(page.locator('#ai-temperature')).toHaveValue('0.7');
    await expect(defaultHint(fieldBox(page, 'ai-temperature'))).toHaveCount(0);
    // 저장 안 한 5키만 힌트를 단다(모델·최대 턴 수·최대 응답 토큰·세션 최대 토큰·시스템 프롬프트)
    await expect(defaultHint(panel)).toHaveCount(5);

    // 플랫폼 상속·재정의 개념은 AI 탭 어디에도 없다
    await expect(panel.getByText(/재정의|플랫폼|오버라이드|상속/)).toHaveCount(0);
    await expect(panel.getByRole('button', { name: /재정의 해제/ })).toHaveCount(0);
    for (const id of ['ai-model', 'ai-max-turns', 'ai-temperature', 'ai-max-tokens', 'ai-session-max-tokens', 'ai-system-prompt']) {
      await expect(page.locator(`#${id}`)).toBeEnabled();
    }

    // 앱 레이아웃은 내부 컨테이너가 스크롤해 fullPage 로도 아래쪽 필드가 잘린다 — 뷰포트를 늘려
    // AI 탭 전체(동작 설정 6키 포함)가 한 장에 담기게 한다.
    await page.setViewportSize({ width: 1280, height: 2400 });
    await page.screenshot({ path: screenshotPath('ai-tab.png'), fullPage: true });
  });

  test('저장 안 한 필드를 편집해 저장하면 그 키만 PUT 되고, 저장 후에는 기본값 힌트가 사라진다', async ({
    authenticatedPage: page,
  }) => {
    // PUT 캡처를 먼저 등록한다 — setupSettingsMocks 는 GET 이 아닌 요청을 fallback 으로 넘긴다.
    const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
    // 저장 뒤 재조회는 "그 키가 저장된" 응답을 준다(실제 서버와 같은 흐름).
    await setupSettingsMocks(page, {
      ai: () =>
        saveCapture.lastRequest()
          ? createAiSettings({ 'ai.max_turns': { overridden: true, value: '15' } })
          : createAiSettings(),
    });
    await page.goto('/admin/settings');

    await expect(defaultHint(fieldBox(page, 'ai-max-turns'))).toBeVisible();
    await page.locator('#ai-max-turns').fill('15');
    await page.getByRole('button', { name: '저장' }).click();

    const req = await saveCapture.waitForRequest();
    const settings = (req.payload as { settings: Record<string, string> }).settings;
    // 손대지 않은 키(서버가 기본값으로 내려준 값)는 보내지 않는다 — 보내면 기본값이 저장값으로 굳는다.
    expect(settings).toEqual({ 'ai.max_turns': '15' });

    await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
    await expect(page.locator('#ai-max-turns')).toHaveValue('15');
    await expect(defaultHint(fieldBox(page, 'ai-max-turns'))).toHaveCount(0);
    // 다른 필드는 여전히 기본값이다
    await expect(defaultHint(fieldBox(page, 'ai-temperature'))).toBeVisible();
  });
});

/**
 * 이슈 #720 — 모델(`ai.model`, `PUT /settings`)과 자격증명(`PUT /settings/ai-credential`)은 별도
 * 요청으로 순서대로 저장된다. 앞의 것이 성공하고 뒤의 것이 거부되면 "새 유형 형식의 모델 + 옛 유형
 * 자격증명"이 남아 AI 채팅이 깨졌다. 화면은 자격증명이 거부되면 방금 저장한 모델을 저장 전 값으로
 * 되돌리는 보상 PUT 을 보내야 한다.
 */
test.describe('AI 설정 저장 — 자격증명이 거부되면 모델도 되돌린다(#720)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /**
   * 이슈의 재현 경로 그대로다: Claude → OpenCode 전환 + 프로브 실패 → 직접 입력 → 저장 → 자격증명 502.
   *
   * <b>서버 상태를 흉내 낸다</b> — `PUT /settings` 가 `serverModel` 을 실제로 바꾸고 GET 이 그 값을
   * 돌려준다. 단언의 중심은 토스트가 아니라 <b>끝났을 때 서버에 남은 `ai.model`</b> 과 두 번째 PUT 의
   * 본문이다(토스트만 보면 보상이 빠져도 초록이다).
   *
   * <b>뮤테이션 대상</b>: `SettingsPage.performSave` 의 `rollbackModel` 호출을 지우면 `settingsPuts`
   * 길이·`serverModel` 단언이 빨개진다(수정 전 동작: 서버에 `openai/gpt-4o` 가 남는다).
   */
  test('자격증명 PUT 이 502 로 거부되면 ai.model 을 저장 전 값으로 되돌리는 PUT 을 보내고 편집은 남긴다', async ({
    authenticatedPage: page,
  }) => {
    // 서버가 들고 있는 ai.model — 저장한 적 없는(기본값) 상태에서 시작한다.
    let serverModel = { value: 'claude-sonnet-5', overridden: false };
    const settingsPuts: Record<string, string>[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        const { settings } = route.request().postDataJSON() as { settings: Record<string, string> };
        settingsPuts.push(settings);
        if (settings['ai.model'] !== undefined) serverModel = { value: settings['ai.model'], overridden: true };
        return route.fulfill({ status: 204, body: '' });
      },
    );
    await setupSettingsMocks(page, { ai: () => createAiSettings({ 'ai.model': serverModel }) });

    const calls = await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', configured: true, payload: {}, secretFieldNames: ['oauthToken'] }),
    );
    // 나중에 등록한 라우트가 먼저 잡는다 — 자격증명 PUT 만 502 로 거부한다(서버의 저장 시 재프로브 실패).
    const rejection = '호스트를 확인할 수 없습니다';
    let rejectedCredPuts = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/settings/ai-credential',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        rejectedCredPuts += 1;
        return route.fulfill({
          status: 502,
          contentType: 'application/json',
          body: JSON.stringify({ status: 502, error: 'Bad Gateway', message: rejection }),
        });
      },
    );
    await mockApi(page, 'POST', '/api/v1/settings/ai-credential/probe', {
      ok: false,
      models: [],
      message: rejection,
    });

    await page.goto('/admin/settings');

    // Claude → OpenCode 전환, 공급자·URL·키 입력, 프로브 실패 → 직접 입력으로 모델 지정
    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();
    await credentialGroup(page).getByRole('combobox', { name: '공급자' }).click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();
    await credentialGroup(page).getByRole('textbox', { name: '기본 URL' }).fill('https://gateway-b.example.com/v1');
    await credentialGroup(page).getByRole('textbox', { name: 'API 키' }).fill('sk-test-720');
    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await page.getByRole('button', { name: '직접 입력으로 전환' }).click();
    await page.getByPlaceholder('예: gpt-4o').fill('gpt-4o');

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    // 1) 모델 저장 → 2) 자격증명 거부 → 3) 모델 보상(저장 전 값, 모델 한 키만)
    await expect.poll(() => settingsPuts.length).toBe(2);
    expect(rejectedCredPuts).toBe(1);
    expect(calls.puts).toHaveLength(0);
    expect(settingsPuts[0]).toEqual({ 'ai.model': 'openai/gpt-4o' });
    expect(settingsPuts[1]).toEqual({ 'ai.model': 'claude-sonnet-5' });
    // 서버에 남은 모델은 옛 유형(Claude) 자격증명과 맞는 값이다 — 이것이 결함의 본체다.
    expect(serverModel.value).toBe('claude-sonnet-5');

    // 거부 사유와 "모델도 저장하지 않았다"는 안내가 뜨고, 모순되는 성공 토스트는 없다.
    await expect(page.getByText(rejection).first()).toBeVisible();
    await expect(page.getByText('자격증명 저장이 거부되어 모델 변경도 저장하지 않았습니다.', { exact: false })).toBeVisible();
    await expect(page.getByText('설정이 저장되었습니다.')).toHaveCount(0);

    // 편집은 그대로 남아 다시 저장할 수 있다(모델 입력 유지, 저장 버튼 활성).
    await expect(page.getByPlaceholder('예: gpt-4o')).toHaveValue('gpt-4o');
    await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

    await page.screenshot({ path: screenshotPath('credential-rejected-model-rolled-back.png'), fullPage: true });

    // 되돌리기 → 저장된 상태(Claude SDK + claude-sonnet-5)로 온전히 돌아온다. 모델 칸은 빈 칸이 아니다.
    await page.getByRole('button', { name: '되돌리기' }).click();
    await expect(page.locator('#ai-cred-agent-type')).toContainText('Claude Agent SDK');
    await expect(page.locator('#ai-model')).toContainText('Claude Sonnet 5');
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  /**
   * 방어선 — 이미 어긋난 상태(Claude 유형 자격증명 + `공급자/모델` 형식 모델)로 들어온 화면은 모델
   * Select 를 빈 칸으로 그리지 않고 저장값을 그대로 보여 주며, 다시 고르라고 안내한다.
   *
   * <b>뮤테이션 대상</b>: Select 옵션의 `withPreservedValue` 를 빼면 `#ai-model` 단언이 빨개진다.
   */
  test('Claude 유형인데 저장된 모델이 목록 밖이면 그 값을 보여 주고 다시 선택하라고 안내한다', async ({
    authenticatedPage: page,
  }) => {
    await setupSettingsMocks(page, {
      ai: createAiSettings({ 'ai.model': { value: 'openai/gpt-4o', overridden: true } }),
    });
    await mockAiCredential(page, createAiCredential({ agentType: 'sdk', configured: true }));
    await page.goto('/admin/settings');

    await expect(page.locator('#ai-model')).toContainText('openai/gpt-4o');
    await expect(aiPanel(page).getByText('Claude 모델 목록에 없는 값입니다.', { exact: false })).toBeVisible();

    // 목록의 모델을 다시 고르면 안내가 사라진다.
    await page.locator('#ai-model').click();
    await page.getByRole('option', { name: 'Claude Sonnet 5', exact: true }).click();
    await expect(aiPanel(page).getByText('Claude 모델 목록에 없는 값입니다.', { exact: false })).toHaveCount(0);
  });
});

/**
 * #721 — OpenCode `모델 불러오기` 가 도는 동안 기본 URL·유형을 바꾸면, 늦게 도착한 <b>옛 요청의 응답</b>
 * (성공·실패 모두)을 지금 폼의 결과로 쓰지 않는다. 진행 중에는 버튼을 잠가 프로브가 두 번 나가지 않는다.
 *
 * `setPayloadField`/`setAgentType` 의 `setModels(null)` 은 이미 도착한 결과만 지울 수 있어, 수정 전에는
 * 옛 URL 의 목록이 새 URL 옆에 "✓ 모델 N개"로 되살아났다(#716 임베딩 탭과 같은 패턴).
 */
test.describe('OpenCode 모델 불러오기 — 요청 중 입력이 바뀌면 늦은 옛 응답을 버린다(#721)', () => {
  const PROBE_PATH = '/api/v1/settings/ai-credential/probe';
  const OLD_URL = 'https://gateway-a.example.com/v1';
  const NEW_URL = 'https://gateway-b.example.com/v1';
  const OLD_FAILURE = '게이트웨이 A 가 자격증명을 거부했습니다.';

  /**
   * 프로브 응답을 `release()` 전까지 붙잡아 두는 라우트 — "입력을 바꾼 뒤 응답 도착" 순서를 결정적으로 만든다.
   * `hits` 는 실제로 나간 프로브 수(중복 전송 단언용)다.
   */
  async function gateProbe(page: Page, response: { status: number; body: unknown }) {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let hits = 0;
    await page.route(
      (url) => url.pathname === PROBE_PATH,
      async (route) => {
        hits += 1;
        await gate;
        return route.fulfill({
          status: response.status,
          contentType: 'application/json',
          body: JSON.stringify(response.body),
        });
      },
    );
    return { release, hits: () => hits };
  }

  /**
   * 붙잡아 둔 응답을 풀고, 화면이 그 응답을 처리할 시간을 준다. 기대 결과가 "아무것도 나타나지 않음"이라
   * 기다릴 양성 신호가 없다 — 응답 도착 뒤 잠깐 기다려야 부재 단언이 렌더 전에 공허하게 통과하지 않는다.
   */
  async function releaseAndSettle(page: Page, probe: { release: () => void }) {
    const responded = page.waitForResponse((r) => new URL(r.url()).pathname === PROBE_PATH);
    probe.release();
    await responded;
    await page.waitForTimeout(300);
  }

  /** 저장된 opencode 자격증명(옛 URL)으로 화면을 열고 새 API 키를 입력해 둔다 — URL 을 바꿔도 버튼이 활성이게. */
  async function openWithOpencode(page: Page) {
    await mockAiCredential(
      page,
      createAiCredential({
        agentType: 'opencode',
        configured: true,
        payload: { providerId: 'openai', baseURL: OLD_URL },
        secretFieldNames: ['apiKey'],
      }),
    );
    await page.goto('/admin/settings');
    await page.locator('#ai-cred-opencode-api-key').fill('sk-typed');
  }

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page, { ai: createAiSettings() });
  });

  test('요청 중 기본 URL 을 바꾸면 늦게 도착한 옛 URL 의 모델 목록을 채우지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const probe = await gateProbe(page, {
      status: 200,
      body: { ok: true, models: ['a-model-1', 'a-model-2', 'a-model-3'], message: null },
    });
    await openWithOpencode(page);

    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect.poll(probe.hits).toBe(1);

    // 요청이 도는 동안 기본 URL 을 바꾼다 — 옛 요청은 버려지므로 버튼은 곧바로 새 URL 용으로 되돌아온다.
    await page.locator('#ai-cred-base-url').fill(NEW_URL);
    await expect(page.getByRole('button', { name: '모델 불러오기' })).toBeEnabled();

    await releaseAndSettle(page, probe);

    await expect(page.getByText(/✓ 모델 \d+개/)).toHaveCount(0);
    await expect(page.getByPlaceholder('먼저 모델을 불러오세요')).toBeVisible();
    await expect(page.locator('#ai-cred-base-url')).toHaveValue(NEW_URL);
    await expect(page.getByRole('button', { name: '모델 불러오기' })).toBeEnabled();
  });

  test('요청 중 기본 URL 을 바꾸면 늦게 도착한 옛 URL 의 실패(ok:false)를 붙이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const probe = await gateProbe(page, { status: 200, body: { ok: false, models: [], message: OLD_FAILURE } });
    await openWithOpencode(page);

    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect.poll(probe.hits).toBe(1);
    await page.locator('#ai-cred-base-url').fill(NEW_URL);

    await releaseAndSettle(page, probe);

    await expect(page.getByText(OLD_FAILURE)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '직접 입력으로 전환' })).toHaveCount(0);
    await expect(page.getByPlaceholder('먼저 모델을 불러오세요')).toBeVisible();
  });

  test('요청 중 기본 URL 을 바꾸면 늦게 도착한 옛 URL 의 HTTP 400 도 붙이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const probe = await gateProbe(page, { status: 400, body: { message: OLD_FAILURE } });
    await openWithOpencode(page);

    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect.poll(probe.hits).toBe(1);
    await page.locator('#ai-cred-base-url').fill(NEW_URL);

    await releaseAndSettle(page, probe);

    await expect(page.getByText(OLD_FAILURE)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '직접 입력으로 전환' })).toHaveCount(0);
  });

  test('요청 중 유형을 바꿨다 돌아와도 늦게 도착한 옛 목록을 채우지 않는다', async ({ authenticatedPage: page }) => {
    const probe = await gateProbe(page, {
      status: 200,
      body: { ok: true, models: ['a-model-1', 'a-model-2', 'a-model-3'], message: null },
    });
    await openWithOpencode(page);

    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect.poll(probe.hits).toBe(1);

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'Claude Agent SDK', exact: true }).click();
    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();
    // 유형 전환은 payload 를 비운다 — 기본 URL 이 빈 새 폼이다.
    await expect(page.locator('#ai-cred-base-url')).toHaveValue('');

    await releaseAndSettle(page, probe);

    await expect(page.getByText(/✓ 모델 \d+개/)).toHaveCount(0);
    await expect(page.getByPlaceholder('먼저 모델을 불러오세요')).toBeVisible();
  });

  test('불러오는 동안 버튼이 "불러오는 중..."으로 잠겨 프로브가 한 번만 나가고, 응답이 오면 목록을 채운다', async ({
    authenticatedPage: page,
  }) => {
    const probe = await gateProbe(page, {
      status: 200,
      body: { ok: true, models: ['a-model-1', 'a-model-2', 'a-model-3'], message: null },
    });
    await openWithOpencode(page);

    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect.poll(probe.hits).toBe(1);

    const busy = page.getByRole('button', { name: '불러오는 중...' });
    await expect(busy).toBeVisible();
    await expect(busy).toBeDisabled();
    await expect(page.getByRole('button', { name: '모델 불러오기' })).toHaveCount(0);

    await releaseAndSettle(page, probe);

    // 입력을 바꾸지 않았으니 이 응답은 지금 폼의 것이다 — 정상 경로가 막히지 않았음을 함께 고정한다.
    await expect(page.getByText('✓ 모델 3개')).toBeVisible();
    await expect(page.getByRole('button', { name: '모델 불러오기' })).toBeEnabled();
    expect(probe.hits()).toBe(1);
  });
});
