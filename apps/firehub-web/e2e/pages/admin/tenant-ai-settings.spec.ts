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
 * 지우고 라디오 2개 + 유형별 폼으로 재작성했으므로(`AiCredentialFieldset.tsx`), 그 UI를 겨눈
 * 단언은 전부 "존재하지 않는 요소를 찾다가 타임아웃"으로만 실패해 신호가 없다 — 옛 시나리오의
 * 운명은 각 테스트 앞 주석에 남긴다(무엇을 대체했는지, 왜 그냥 지웠는지).
 *
 * <b>범위</b>: 브리프(`task-12-brief.md`)의 9개 시나리오 + 3개 보강(저장 페이로드가 손대지 않은
 * 비밀을 안 싣는다 / 미저장 입력 경고 문구 / 재정의 배지·버튼의 부재 대조). 권위는 설계서
 * (`docs/superpowers/specs/2026-09-19-typed-ai-settings-design.md`) "화면" 절이다 — 카피가
 * 그대로 고정하는 문구는 부분 매치가 아니라 전체 문자열로 단언한다(리뷰가 지적한 "문구가
 * 바뀌어도 초록"을 막기 위해).
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

/** 스크린샷 저장 경로 — CLAUDE.md 규약(`test-results/tc/<suite>/`)을 따른다. */
const screenshotPath = (name: string) =>
  path.resolve(__dirname, '..', '..', '..', '..', '..', 'test-results', 'tc', 'tenant-ai-settings', name);

/** 설계서 "테넌트 화면" 절의 "플랫폼에도 자격증명이 없으면" 안내 — `no-default` 상태의 대체
 * (옛 배지 체계에서는 이 상태를 표현할 말이 없었다). 부분 매치가 아니라 전체 문자열로 고정한다. */
const PLATFORM_EMPTY_NOTE =
  '플랫폼에 설정된 값이 없습니다 — AI 기능이 동작하지 않습니다. 직접 설정하거나 플랫폼 운영자에게 요청하세요.';

/** 저장 확인 다이얼로그가 미저장 입력이 있을 때 덧붙이는 문장(`buildSaveConfirm`). */
const UNSAVED_INPUT_SENTENCE = '입력한 값 중 아직 저장하지 않은 내용도 함께 사라집니다.';

/**
 * 플랫폼 전환(되돌리기) 저장 확인 다이얼로그의 <b>전체</b> 문구(`buildSaveConfirm` 의 `willDelete`
 * 분기, 미저장 입력 없음). 옛 [재정의 해제] 문구("...필요하면 언제든 <b>다시 재정의</b>할 수
 * 있습니다")를 그대로 재사용하지 않는다는 것이 설계 결정이었는데(`ai-credential-screen.ts` 의
 * `buildSaveConfirm` 주석: "'재정의'는 이 화면이 없애려는 바로 그 어휘다"), 첫 문장만 부분
 * 매치(`exact:false`)로 보면 그 금지된 두 번째 문장이 몰래 돌아와도 초록이 유지된다 — 그래서
 * 전체 문자열로 고정하고, 아래 테스트에서 금지 어휘의 부재도 따로 단언한다.
 */
const PLATFORM_REVERT_CONFIRM_DESC =
  '우리 조직이 설정한 에이전트 유형과 자격증명이 모두 삭제되고, 플랫폼 운영자가 정한 값이 적용됩니다. ' +
  '삭제된 값은 복구할 수 없으며, 필요하면 언제든 다시 설정할 수 있습니다.';

test.describe('테넌트별 AI 자격증명 — 유형별 화면과 라디오 전환(Task 12)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    // 동작 설정 6키(모델·최대 턴 수 등)는 자격증명과 별도 자원이지만 같은 페이지·같은 로딩
    // 게이트를 공유한다(`SettingsPage` 는 `behaviorLoading || cred.isLoading` 을 함께 본다) —
    // 모킹하지 않으면 이 화면 자체가 뜨지 않는다.
    await setupSettingsMocks(page, { ai: createAiSettings() });
  });

  /**
   * 브리프 시나리오 1(전반부) — "전환 의미: 폼 상태다"(설계서 §213)의 핵심.
   *
   * <b>뮤테이션 대상</b>: `setPlane`/라디오 `onValueChange` 가 `cred.save()`(DELETE)를 직접 부르게
   * 바꾸면 이 테스트가 빨개진다 — 라디오를 두 번(플랫폼→직접) 눌러도 `deleteCount` 가 0 이어야
   * 하는데, 즉시 호출 버전은 첫 클릭에서 이미 1이 된다.
   */
  test('라디오를 왕복하는 것만으로는 DELETE 가 나가지 않는다', async ({ authenticatedPage: page }) => {
    const calls = await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    // 전제: 테넌트가 이미 직접 설정 중이다.
    await expect(page.getByRole('radio', { name: '우리 조직이 직접 설정' })).toBeChecked();

    await page.getByRole('radio', { name: '플랫폼 설정을 사용' }).click();
    // 플랫폼 라디오로 옮긴 직후에도 실제 문서는 여전히 테넌트 것이다(저장 전) — "저장하면
    // 이렇게 된다"는 예고 문구만 보이고, 지금 적용 중인 값 정의 목록(진짜 플랫폼 값)은 아니다.
    // 이 문구가 뜬다는 것 자체가 "아직 삭제되지 않았다"의 화면 쪽 증거이기도 하다.
    await expect(credentialGroup(page).getByText('저장하면 플랫폼 운영자가 정한 값이 적용됩니다.')).toBeVisible();
    expect(calls.deleteCount).toBe(0);

    await page.getByRole('radio', { name: '우리 조직이 직접 설정' }).click();
    await expect(page.getByRole('radio', { name: '우리 조직이 직접 설정' })).toBeChecked();
    // OAuth 토큰 입력이 다시 보이는 것도 "진짜로 폼으로 돌아왔다"는 증거다.
    await expect(page.locator('#ai-cred-oauth-token')).toBeVisible();
    expect(calls.deleteCount).toBe(0);

    await page.screenshot({ path: screenshotPath('radio-toggle-no-immediate-delete.png'), fullPage: true });
  });

  /**
   * 브리프 시나리오 1(후반부) — 위 테스트의 반대편. 저장을 <b>확정</b>하면 그제서야 DELETE 가,
   * 그것도 정확히 한 번만 나가야 한다(옛 번들은 3번이었다 — `ai.credential` 은 문서 하나다).
   */
  test('저장을 확정하면 DELETE 가 정확히 한 번 나가고, 확인 다이얼로그를 거친다', async ({
    authenticatedPage: page,
  }) => {
    const calls = await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await mockAiAuthStatus(page, () => ({ valid: true, email: 'tenant@example.com' }));

    await page.goto('/admin/settings');
    await page.getByRole('radio', { name: '플랫폼 설정을 사용' }).click();
    await page.getByRole('button', { name: '저장' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('플랫폼 설정으로 되돌릴까요?')).toBeVisible();
    // 전체 문구로 고정한다 — 첫 문장만 보면 옛 [재정의 해제] 문구("...다시 재정의할 수
    // 있습니다")가 두 번째 문장 자리에 몰래 돌아와도 통과한다(fix round 1 리뷰 #1).
    await expect(dialog.getByText(PLATFORM_REVERT_CONFIRM_DESC)).toBeVisible();
    await expect(dialog.getByText('다시 재정의할 수 있습니다', { exact: false })).toHaveCount(0);
    await dialog.getByRole('button', { name: '되돌리기' }).click();

    await expect.poll(() => calls.deleteCount).toBe(1);
    expect(calls.puts).toHaveLength(0);
    await expect(page.getByText('저장했습니다.')).toBeVisible({ timeout: 8000 });
  });

  /**
   * 보강(fix round 1 리뷰 #1) — 라디오는 <b>제목</b>으로만 찾는다
   * (`getByRole('radio', {name: title})`) — 스펙이 고정한 설명문(description)은 그 어떤 단언도
   * 거치지 않고 있었다. 특히 "우리 조직이 직접 설정" 설명문은 과금 문구가 명시적으로 금지된
   * 자리다(설계서 "테넌트 화면" 절: "'사용량도 우리 계정으로 청구됩니다'는 cli(구독 OAuth)·사내
   * 엔드포인트에서 거짓이다. 'AI 호출이 우리 조직 자격증명으로 나갑니다'로 쓴다"). 설명문이
   * 금지된 문장으로 바뀌어도 title 기반 단언은 전부 그대로 통과하므로, 설명문 자체와 금지
   * 문구의 부재를 따로 본다.
   *
   * <b>뮤테이션 대상</b>: `AiCredentialFieldset.tsx` 의 "우리 조직이 직접 설정" `description`
   * 을 "AI 사용량도 우리 계정으로 청구됩니다." 로 바꾸면 이 테스트가 빨개진다.
   */
  test('라디오 설명문은 스펙이 고정한 문구 그대로이고, 금지된 과금 표현을 쓰지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    await expect(
      credentialGroup(page).getByText(
        '플랫폼 운영자가 정한 값이 그대로 적용됩니다. 운영자가 값을 바꾸면 우리 조직에도 함께 반영됩니다.',
      ),
    ).toBeVisible();
    await expect(credentialGroup(page).getByText('AI 호출이 우리 조직 자격증명으로 나갑니다.')).toBeVisible();
    // 금지된 과금 문구 — cli(구독 OAuth)·사내 엔드포인트에서 거짓이라 스펙이 명시적으로 뺐다.
    await expect(
      credentialGroup(page).getByText('사용량도 우리 계정으로 청구됩니다', { exact: false }),
    ).toHaveCount(0);
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
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
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
        tenantOwned: true,
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

    // 전제: 저장된 API 키가 있어(Ruling #37/#38 — 기본 URL 도 저장된 값과 같다) 아무것도 새로
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
        tenantOwned: true,
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
        tenantOwned: true,
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
   * 브리프 시나리오 6 — `no-default` 상태의 화면 쪽 대체(설계서 §209). 배지가 없어진 지금, 이
   * 문구가 유일하게 "플랫폼도 못 믿는다"를 말한다.
   *
   * <b>뮤테이션 대상</b>: 이 안내를 지우면(또는 조건을 뒤집으면) 이 테스트가 빨개진다 — 옛 배지
   * 체계라면 조용히 빈 목록만 보여줬을 자리다.
   */
  test('플랫폼에도 자격증명이 없으면 화면이 그 사실을 말한다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(page, createAiCredential({ agentType: 'sdk', tenantOwned: false, payload: {}, secretFieldNames: [] }));
    await page.goto('/admin/settings');

    // tenantOwned:false 이므로 라디오는 처음부터 "플랫폼 설정을 사용" 이다.
    await expect(page.getByRole('radio', { name: '플랫폼 설정을 사용' })).toBeChecked();
    await expect(credentialGroup(page).getByText(PLATFORM_EMPTY_NOTE)).toBeVisible();

    await page.screenshot({ path: screenshotPath('platform-empty-notice.png'), fullPage: true });
  });

  /**
   * 보강 — 조회 실패는 <b>플랫폼 무설정과 다른 모양</b>이어야 한다(브리프 상위 지시: "A failed
   * credential GET renders a failure notice, deliberately distinct from that platform-empty
   * state" — 이 브랜치가 최근 "네트워크 실패를 확신에 찬 사실처럼 렌더"한 결함을 낸 전례가
   * 있어 각별히 겨눈다). GET 500 은 `cred.loadFailed` 로 떨어지고, 훅은 안전한 기본값
   * (`plane:'platform', tenantOwned:false, secretFieldNames:[]`)으로 주저앉는다 — 그 기본값을
   * 그대로 평소 렌더 경로에 흘리면 "플랫폼에 설정된 값이 없습니다"를 <b>사실</b>처럼 보여주게
   * 된다(실제로는 "몰라서" 못 그리는 것뿐인데 "없다"고 단정하는 것이다).
   * `AiCredentialFieldset.tsx` 는 `loadFailed` 를 먼저 걸러 별도 실패 안내로 대체한다 — 여기서는
   * 그 대체가 실제로 일어나고, 플랫폼 무설정 문구와 섞이지 않는지를 본다.
   *
   * <b>뮤테이션 대상</b>: `AiCredentialFieldset.tsx` 의 `if (cred.loadFailed) return ...` 가드를
   * 지우면 이 테스트가 빨개진다 — 실패 안내 대신 라디오·`PLATFORM_EMPTY_NOTE` 가 렌더된다.
   */
  test('자격증명 조회가 실패하면 플랫폼 무설정과 다른 실패 안내가 뜬다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/settings/ai-credential', {}, { status: 500 });
    await page.goto('/admin/settings');

    await expect(
      credentialGroup(page).getByRole('alert').filter({ hasText: '자격증명 정보를 불러오지 못했습니다' }),
    ).toBeVisible();
    // 플랫폼 무설정 문구와 섞이면 안 된다 — 둘은 서로 다른 사실(모른다 vs 없다)을 말한다.
    await expect(credentialGroup(page).getByText(PLATFORM_EMPTY_NOTE)).toHaveCount(0);
    // 라디오 자체가 없다 — 실패 화면은 폼을 전혀 그리지 않는다.
    await expect(page.getByRole('radio')).toHaveCount(0);
  });

  /**
   * 보강 — 잠금 상태(설계서 "잠금 상태" 절: "서버가 편집 불가로 보고하면 라디오 둘 다 비활성 +
   * `PlatformLockedNote`")의 유일한 실재 경로는 GET 403(`ai:settings` 권한 없음)이다
   * (`useAiCredentialForm.ts` 의 `isLocked` 주석). `settings.spec.ts` 에서 Task 12 가 지운 옛
   * "플랫폼 전용 자격증명 3키는 그룹 Lock 배지…" 테스트가 검증하던 것의 새 UI 버전 대체다 — 그
   * 테스트는 옛 3키 번들의 `tenantEditable:false` 를 겨눴지만, 새 문서는 판정 자리 자체가 이
   * GET 403 하나뿐이라 같은 방식으로 옮겨 쓸 수 없었다.
   *
   * <b>Ruling #46(fix round 1 리뷰) — "권한이 없다"와 "값이 없다"를 섞으면 안 된다.</b> 403 은
   * `fetchAndApply` 가 `applyResponse` 를 아예 못 부르는 경로라, 이 시점의 `secretFieldNames`
   * 는 훅의 초기값(빈 배열)이다 — 그 초기값을 평소 렌더 경로(`PlatformPlaneSummary`)로 흘리면
   * `PLATFORM_EMPTY_NOTE`("플랫폼에 설정된 값이 없습니다")를 <b>사실</b>처럼 함께 보여주게
   * 된다. 진실은 "볼 권한이 없다"인데 화면이 "플랫폼에도 없다"고 단정하는 것이다 — `loadFailed`
   * 가드가 막는 것과 같은 종류의 결함이라, 브리프 6번(플랫폼 무설정) 테스트가 이미 가진
   * `toHaveCount(0)` 대구(실패 시 무설정 문구가 안 보임)를 이 테스트에도 건다.
   *
   * <b>뮤테이션 대상</b>: `AiCredentialFieldset.tsx` 에서 `editable &&` 가드를 지우면(잠긴
   * 상태에서도 평면 내용을 그리면) 이 테스트가 빨개진다.
   */
  test('자격증명 조회가 403 이면 라디오 둘 다 비활성이고 잠금 안내가 뜬다 — 플랫폼 무설정 문구와 섞이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/settings/ai-credential', {}, { status: 403 });
    await page.goto('/admin/settings');

    await expect(page.getByRole('radio', { name: '플랫폼 설정을 사용' })).toBeDisabled();
    await expect(page.getByRole('radio', { name: '우리 조직이 직접 설정' })).toBeDisabled();
    await expect(credentialGroup(page).getByText('플랫폼 운영자만 변경할 수 있는 항목입니다.')).toBeVisible();
    // 핵심: "권한 없음"이 "값 없음"으로 오해되면 안 된다.
    await expect(credentialGroup(page).getByText(PLATFORM_EMPTY_NOTE)).toHaveCount(0);
  });

  /** 브리프 시나리오 7 — 유형 전환 경고. Select 아래 정적 안내 + 저장 확인 다이얼로그 양쪽에
   * 같은 사실이 실려야 한다(설계서 "유형 전환": "저장 확인에도 포함한다"). */
  test('유형을 바꾸면 이전 유형의 저장된 비밀이 삭제된다는 경고가 뜨고, 저장 확인에도 같은 내용이 실린다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
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
    await mockAiCredential(page, createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['apiKey'] }));
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
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    // sdk — OAuth 토큰·API 키 각각 옆에 하나씩, 그룹 안에 총 둘.
    await expect(credentialGroup(page).getByRole('button', { name: '인증 확인' })).toHaveCount(2);

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();

    await expect(credentialGroup(page).getByRole('button', { name: '인증 확인' })).toHaveCount(0);
  });

  /**
   * 보강 — 저장 확인 다이얼로그의 "미저장 입력" 덧문장(설계서: "미저장 입력이 있으면 그 사실을
   * 문구에 더한다"). 브리프의 9개 목록엔 없지만, 이 문장이 조용히 빠지는 것이야말로 이 브랜치가
   * 반복해서 겪은 "사용자가 잃을 것을 몰랐던" 종류의 결함이라 request/copy 양쪽에서 고정한다.
   */
  test('저장하지 않은 입력이 있으면 저장 확인 다이얼로그가 그 사실을 덧붙인다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    // (1) 입력 없이 플랫폼으로 되돌리면 덧문장이 없다 — 늘 붙으면 잡음이 된다.
    await page.getByRole('radio', { name: '플랫폼 설정을 사용' }).click();
    await page.getByRole('button', { name: '저장' }).click();
    const cleanDialog = page.getByRole('alertdialog');
    await expect(cleanDialog.getByText('플랫폼 설정으로 되돌릴까요?')).toBeVisible();
    await expect(cleanDialog.getByText(UNSAVED_INPUT_SENTENCE, { exact: false })).toHaveCount(0);
    await cleanDialog.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    // (2) 직접 설정으로 되돌아가 API 키를 입력한 채로 같은 조작을 반복하면 덧문장이 붙는다.
    //     (라디오 자체는 원본으로 그대로 되돌아왔지만, 지금 타이핑 중인 입력은 남아 있다 —
    //     `setPlane` 은 `secretInputs` 를 비우지 않는다.)
    await page.getByRole('radio', { name: '우리 조직이 직접 설정' }).click();
    await page.locator('#ai-cred-api-key').fill('sk-typed-but-not-saved');
    await page.getByRole('radio', { name: '플랫폼 설정을 사용' }).click();
    await page.getByRole('button', { name: '저장' }).click();
    const dirtyDialog = page.getByRole('alertdialog');
    await expect(dirtyDialog.getByText(UNSAVED_INPUT_SENTENCE, { exact: false })).toBeVisible();
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
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await mockAiAuthStatus(page, () => ({ valid: true, email: 'tenant@example.com' }));

    await page.goto('/admin/settings');

    // API 키만 새로 입력한다 — OAuth 토큰 입력칸은 처음부터 빈 채로 둔다(유형·평면도 안 바꾸므로
    // 확인 다이얼로그 없이 바로 저장된다).
    await page.locator('#ai-cred-api-key').fill('sk-ant-new-key');
    await page.getByRole('button', { name: '저장' }).click();

    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0]).toEqual({ agentType: 'sdk', payload: {}, secret: { apiKey: 'sk-ant-new-key' } });
    expect(calls.puts[0].secret).not.toHaveProperty('oauthToken');
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
        tenantOwned: true,
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
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
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

    // (B) 그 사이 API 키를 바꿔 저장한다 — 유형·평면은 안 건드렸으니 확인 다이얼로그 없이 바로
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
   * 보강 — 사용자가 거부한 것(배지·[재정의 해제] 버튼)의 <b>부재</b>를 자격증명 그룹 안에서만
   * 단언하고, 같은 화면의 동작 설정 6키는 그대로 갖고 있다는 대조를 함께 고정한다("적용 범위는
   * AI 자격증명 그룹뿐이다", 설계서). 대조가 없으면 "전부 지워버리는" 과잉 리팩터도 통과한다.
   */
  test('자격증명 그룹엔 재정의 배지도 [재정의 해제] 버튼도 없다 — 동작 설정 6키는 그대로 갖고 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupSettingsMocks(page, {
      ai: createAiSettings({ 'ai.temperature': { overridden: true, value: '0.7' } }),
    });
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'sdk', tenantOwned: true, secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/admin/settings');

    await expect(credentialGroup(page).getByRole('button', { name: /재정의 해제/ })).toHaveCount(0);
    await expect(credentialGroup(page).getByText('우리 조직 값 적용 중')).toHaveCount(0);
    await expect(credentialGroup(page).getByText('플랫폼 값 사용 중')).toHaveCount(0);
    await expect(page.getByRole('radio', { name: '플랫폼 설정을 사용' })).toBeVisible();
    await expect(page.getByRole('radio', { name: '우리 조직이 직접 설정' })).toBeVisible();

    // 대조: 동작 설정의 Temperature 는 재정의 배지·버튼을 그대로 유지한다.
    await expect(fieldBox(page, 'ai-temperature').getByText('우리 조직 값 적용 중')).toBeVisible();
    await expect(fieldBox(page, 'ai-temperature').getByRole('button', { name: '재정의 해제' })).toBeVisible();

    await expect(aiPanel(page).getByRole('radio')).toHaveCount(2);
  });
});
