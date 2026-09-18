import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { createAiSettings } from '../../factories/admin.factory';
import {
  AI_AUTH_STATUS_PATH,
  aiAuthStatusResponse,
  captureOverrideDeletes,
  mockAiAuthStatus,
  SETTINGS_FETCH_ERROR,
  setupAdminAuth,
  setupSettingsMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

// ESM 환경에서는 `__dirname`이 없으므로 `import.meta.url`로 현재 파일 디렉토리 경로를 계산한다
// (`e2e/pages/ai-chat/dataset-manager.spec.ts`와 같은 관용구).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Task 7 — 테넌트가 자기 AI 를 고른다 (E2E)
 *
 * `settings.spec.ts` 는 P7-b/P7-c1 을 검증하지만, `ai.agent_type` / `ai.api_key` /
 * `ai.cli_oauth_token` 은 그 파일 전체에서 `tenantEditable: false`(플랫폼 전용)로만 고정돼
 * 있었다 — `settings.spec.ts:268-274` 의 저장 페이로드 단언조차 "잠긴 3키는 절대 안 보낸다"만
 * 지킬 뿐, 3키가 **열렸을 때** 무엇이 일어나는지는 이 리포에 테스트가 하나도 없었다.
 *
 * 이 스펙은 그 반대편 — 3키가 `tenantEditable: true` 로 내려오는 분기 — 를 덮는다:
 *  1. 테넌트가 자기 API 키를 저장하면 바뀐 키만 전송되고, 저장 직후(새로고침 없이) 서버의
 *     번들 해석 결과(마스킹 값 + 손대지 않은 형제 키의 초기화)로 폼이 다시 시드되는가
 *     (`SettingsPage.tsx` `handleSave` 의 `resyncFromServer` 경로 — Task 6 가 새로 연 경로다).
 *  2. `ai.agent_type` 자체가 테넌트 편집 가능으로 렌더되는가(`isEditable`/`disabled` 분기).
 *  3. 번들 규칙의 "다른 값을 바꾸면 나머지가 사라진다"는 부작용이 화면에서 실제로 보이는가 —
 *     자격증명 대신 에이전트 유형만 바꿔 반대 방향을 확인한다.
 *
 * 백엔드 근거(`SettingsService.applyAiCredentialBundle`, `BUNDLE_FILL_VALUES`): 3키 중
 * 하나라도 테넌트 행이 있으면 3키 전부가 테넌트 평면에서 해석된다. 행이 없는 키는 빈 문자열로
 * 채워지되 `ai.agent_type` 만 예외로 `"sdk"` 로 채워진다(실행 형태 선택자라 빈 값이 안전한
 * 방향이 아니기 때문 — 빈 값은 `AiAgentProxyService` 에서 `cli-api` 분기로 떨어진다).
 */

/** 필드 한 개를 감싸는 컨테이너 — `settings.spec.ts` 의 `fieldBox` 와 같은 스코프 규칙. */
const fieldBox = (page: Page, inputId: string) =>
  page.locator('div.space-y-2', { has: page.locator(`#${inputId}`) });

/** 자격증명 번들 3키를 감싸는 `fieldset` — 그룹 배지·그룹 해제 버튼이 하나뿐임을 세려면 필요하다. */
const credentialGroup = (page: Page) =>
  page.locator('fieldset', { has: page.locator('#ai-agent-type') });

/**
 * AI 탭 전체(`TabsContent`). 낡음 안내의 <b>자리</b>를 고정하는 데 쓴다 — 탭 안에 있고 자격증명
 * `fieldset` 안에는 없어야 한다. `page.getByText` 로는 이 구별이 불가능하고, 게다가 토스트까지
 * 함께 잡혀 안내가 화면에 남는지를 전혀 증명하지 못한다.
 */
const aiPanel = (page: Page) => page.getByRole('tabpanel', { name: 'AI 에이전트' });

const LOCKED_NOTE = '플랫폼 운영자만 변경할 수 있는 항목입니다.';

/**
 * 비밀 입력이 <b>항상 빈 채로</b> 렌더되므로, "서버에 값이 있다/없다"는 오직 이 두 문구로만
 * 화면에 전달된다 — 그래서 값 단언(`toHaveValue`)이 아니라 이 문구를 단언한다.
 *
 * <b>값 단언으로 대체할 수 없다.</b> 마스크를 시드하지 않는 지금 두 입력은 어떤 상태에서도
 * `''` 이라, `toHaveValue('')` 는 재시드가 일어났든 아니든 통과하는 <b>공허한 단언</b>이 된다.
 */
const SECRET_SET_HINT = '현재 값이 설정되어 있습니다. 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.';
const SECRET_UNSET_HINT = '설정된 값이 없습니다.';
const EMPTY_IN_BUNDLE_NOTE = '이 항목은 비어 있습니다 — 플랫폼 값이 사용되지 않습니다.';

/** 스크린샷 저장 경로 — CLAUDE.md 규약(`test-results/tc/<suite>/`)을 따른다. */
const screenshotPath = (name: string) =>
  path.resolve(__dirname, '..', '..', '..', '..', '..', 'test-results', 'tc', 'tenant-ai-settings', name);

/**
 * 3키가 아직 어느 쪽도 테넌트 행이 없는(=플랫폼 상속 중) 초기 상태.
 * `ai.api_key` / `ai.cli_oauth_token` 값은 "손대지 않아도 화면에 보이는 무언가"를 만들기
 * 위한 플랫폼 마스크다 — 저장 후 재시드로 그 값이 사라지는 것을 눈으로 확인할 수 있어야
 * 이 테스트가 "재시드 안 해도 통과"하는 공허한 단언이 되지 않는다.
 */
const inheritedAi = createAiSettings({
  'ai.agent_type': { tenantEditable: true, value: 'sdk', overridden: false },
  'ai.api_key': { tenantEditable: true, value: '****plat', overridden: false },
  'ai.cli_oauth_token': { tenantEditable: true, value: '****oldt', overridden: false },
});

/**
 * 테넌트가 `ai.api_key` 만 저장한 뒤 서버가 돌려줄 해석 결과.
 * 번들이 원자적으로 3키를 테넌트 평면으로 옮기므로: `ai.api_key` 는 방금 저장한 값이 마스킹돼
 * 돌아오고(`****-key` = `EncryptionService.maskValue` 형태, **** + 마지막 4글자),
 * `ai.agent_type` 은 손대지 않았지만 테넌트 행이 없어 채움 값 `"sdk"` 로, `ai.cli_oauth_token`
 * 도 손대지 않았지만 테넌트 행이 없어 빈 문자열로 해석된다 — 이것이 화면 안내문이 말하는
 * "자격증명만 바꾸면 나머지 자격증명은 비워진다"의 실측이다.
 */
const afterApiKeySaveAi = createAiSettings({
  'ai.agent_type': { tenantEditable: true, value: 'sdk', overridden: true },
  'ai.api_key': { tenantEditable: true, value: '****-key', overridden: true },
  'ai.cli_oauth_token': { tenantEditable: true, value: '', overridden: true },
});

/**
 * 테넌트가 `ai.agent_type` 만 `cli-api` 로 저장한 뒤 서버가 돌려줄 해석 결과.
 * `ai.api_key` / `ai.cli_oauth_token` 은 손대지 않았지만 테넌트 행이 없어 둘 다 빈 문자열로
 * 채워진다(`ai.agent_type` 만 예외로 `"sdk"` 채움을 받는 쪽이라, 반대로 이 키가 바뀐 경우엔
 * 그 예외가 자격증명 2키에는 적용되지 않는다).
 */
const afterAgentTypeSaveAi = createAiSettings({
  'ai.agent_type': { tenantEditable: true, value: 'cli-api', overridden: true },
  'ai.api_key': { tenantEditable: true, value: '', overridden: true },
  'ai.cli_oauth_token': { tenantEditable: true, value: '', overridden: true },
});

/**
 * 번들이 <b>이미 테넌트 재정의 상태</b>인 픽스처. 그룹 해제와 "저장된 OAuth 토큰 삭제"는 둘 다
 * 이 상태에서만 화면에 존재하므로(전자는 지울 오버라이드가 있어야 하고, 후자는 상속 중에 쏘면
 * 번들이 새로 발동해 상속 중이던 API 키까지 `""` 로 떨어뜨린다) 별도 픽스처가 필요하다.
 */
const overriddenAi = createAiSettings({
  'ai.agent_type': { tenantEditable: true, value: 'sdk', overridden: true },
  'ai.api_key': { tenantEditable: true, value: '****akey', overridden: true },
  'ai.cli_oauth_token': { tenantEditable: true, value: '****oat1', overridden: true },
});

/** 3키 전체 해제(DELETE ×3) 후 서버가 돌려줄 상태 — 번들이 통째로 플랫폼 상속으로 돌아간다. */
const afterBundleClearAi = createAiSettings({
  'ai.agent_type': { tenantEditable: true, value: 'sdk', overridden: false },
  'ai.api_key': { tenantEditable: true, value: '****plat', overridden: false },
  'ai.cli_oauth_token': { tenantEditable: true, value: '****oldt', overridden: false },
});

/**
 * 3키 중 <b>둘만</b> 지워진 뒤 서버가 돌려줄 상태 — `ai.cli_oauth_token` DELETE 가 실패한 경우다.
 *
 * <b>3키가 전부 `overridden: true` 로 남는 것이 핵심</b>이다. 행이 하나라도 남으면
 * `applyAiCredentialBundle` 이 계속 발동해 3키 전부가 테넌트 평면에서 해석되고, 지워진 두 키는
 * 플랫폼 값이 아니라 번들 채움(`""` / `"sdk"`)이 된다. 즉 사용자가 보기엔 "해제했는데 아직
 * 재정의 배지"이고, 남은 토큰은 여전히 우리 조직 값으로 <b>적용 중</b>이다.
 */
const afterPartialBundleClearAi = createAiSettings({
  'ai.agent_type': { tenantEditable: true, value: 'sdk', overridden: true },
  'ai.api_key': { tenantEditable: true, value: '', overridden: true },
  'ai.cli_oauth_token': { tenantEditable: true, value: '****oat1', overridden: true },
});

/** OAuth 토큰만 빈 값으로 저장한 뒤의 상태 — 번들은 재정의인 채로 남고 토큰만 비워진다. */
const afterTokenDeleteAi = createAiSettings({
  'ai.agent_type': { tenantEditable: true, value: 'sdk', overridden: true },
  'ai.api_key': { tenantEditable: true, value: '****akey', overridden: true },
  'ai.cli_oauth_token': { tenantEditable: true, value: '', overridden: true },
});

test.describe('테넌트별 AI 설정 — 편집 가능 분기(Task 7)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('테넌트가 API 키만 저장해도 바뀐 키만 전송되고, 저장 직후 마스킹 값 + 번들 부작용(미편집 OAuth 토큰 초기화)으로 재시드된다', async ({
    authenticatedPage: page,
  }) => {
    // 저장 전후로 다른 응답을 주기 위한 상태 플래그 — SMTP 재시드 스펙(`settings.spec.ts`
    // "저장이 성공하면 그룹 배지가..." 테스트)과 같은 관용구다.
    let saved = false;
    let putPayload: Record<string, string> | null = null;

    await setupSettingsMocks(page, { ai: () => (saved ? afterApiKeySaveAi : inheritedAi) });
    // PUT 은 setupSettingsMocks 의 GET 핸들러보다 나중에 등록해야 같은 pathname 요청에서
    // 이 핸들러가 먼저 매칭된다(Playwright는 나중에 등록된 라우트를 먼저 시도한다).
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putPayload = (route.request().postDataJSON() as { settings: Record<string, string> }).settings;
        saved = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );
    // 저장 성공 후 화면이 호출하는 인증 상태 조회 — 모킹하지 않으면 실네트워크로 빠진다.
    await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

    await page.goto('/admin/settings');

    // 전제: 세 키 모두 편집 가능하고, OAuth 토큰은 아직 플랫폼 값을 상속 중이다.
    await expect(page.locator('#ai-agent-type')).toBeEnabled();
    await expect(page.locator('#ai-api-key')).toBeEnabled();
    await expect(page.locator('#ai-cli-oauth-token')).toBeEnabled();
    // 비밀 입력은 서버 마스크를 <b>시드하지 않는다</b>. 마스크가 들어 있으면 사용자가 그 뒤에
    // 실제 키를 덧붙여(`****oldtsk-ant-…`) 서버 센티널(길이 4/8만 드롭)을 빠져나간 문자열을
    // 자기 자격증명으로 저장하게 되고, 저장 후 새 마스크가 보여 알아챌 표면이 없다.
    await expect(page.locator('#ai-cli-oauth-token')).toHaveValue('');
    await expect(page.locator('#ai-api-key')).toHaveValue('');
    // 빈 입력창이 "아직 안 채운 칸"과 구별되는 유일한 경로가 이 힌트다.
    await expect(fieldBox(page, 'ai-cli-oauth-token').getByText(SECRET_SET_HINT)).toBeVisible();
    await expect(fieldBox(page, 'ai-api-key').getByText(SECRET_SET_HINT)).toBeVisible();

    // API 키만 편집한다 — 에이전트 유형·OAuth 토큰은 건드리지 않는다.
    await page.locator('#ai-api-key').fill('sk-tenant-own-key');
    await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
    await page.getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });

    // 경계: 손대지 않은 두 키(agent_type/cli_oauth_token)는 페이로드에 없다 — 상속을 끊지 않는다.
    expect(putPayload).toEqual({ 'ai.api_key': 'sk-tenant-own-key' });

    // 핵심: 새로고침 없이(=handleSave 내부의 refreshMeta + resyncFromServer 경로) 번들 해석
    // 결과가 화면에 반영돼야 한다.
    //
    // <b>입력 값이 아니라 힌트를 본다.</b> 비밀 2키의 입력은 어느 상태에서도 빈 문자열이므로
    // `toHaveValue('')` 는 재시드 여부와 무관하게 통과하는 공허한 단언이다. 서버 해석이 화면에
    // 도달했는지는 힌트 문구만이 증명한다.
    // - API 키에는 방금 저장한 값이 마스킹돼 존재한다(****-key).
    await expect(fieldBox(page, 'ai-api-key').getByText(SECRET_SET_HINT)).toBeVisible();
    // - 손대지 않은 OAuth 토큰은 번들 채움으로 빈 값이 됐다. 여기서 "현재 값이 설정되어
    //   있습니다"가 계속 보이면 사용자는 "왜 갑자기 토큰이 안 먹히지"를 겪게 된다.
    await expect(
      fieldBox(page, 'ai-cli-oauth-token').getByText(EMPTY_IN_BUNDLE_NOTE),
    ).toBeVisible();
    await expect(fieldBox(page, 'ai-cli-oauth-token').getByText(SECRET_SET_HINT)).toHaveCount(0);
    // - agent_type 은 채움 값 "sdk" 로 해석되며(원래도 sdk 였으므로 표시는 그대로) 여전히
    //   Select 라벨로 읽힌다.
    await expect(page.locator('#ai-agent-type')).toContainText('Claude Agent SDK');
    // - 재시드가 dirty 를 만들면 안 된다(original 도 함께 갱신되므로).
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();

    await page.screenshot({ path: screenshotPath('api-key-save-resync.png'), fullPage: true });
  });

  test('에이전트 유형이 테넌트 편집 가능으로 보인다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page, {
      ai: createAiSettings({
        'ai.agent_type': { tenantEditable: true },
        'ai.api_key': { tenantEditable: true },
        'ai.cli_oauth_token': { tenantEditable: true },
      }),
    });
    await page.goto('/admin/settings');

    // htmlFor="ai-agent-type" → "에이전트 유형". 플랫폼 잠금이던 시절에는
    // disabled={!isEditable('ai.agent_type')} 로 항상 비활성이었다(settings.spec.ts 의
    // "agent_type=sdk 면..." 테스트가 그 기본 픽스처로 여전히 지킨다). 여기서는 서버가
    // tenantEditable: true 를 내리는 반대 분기를 고정한다.
    await expect(page.locator('#ai-agent-type')).toBeEnabled();
    // 편집 가능한데 잠금 안내문이 보이면 거짓 안내다.
    await expect(fieldBox(page, 'ai-agent-type').getByText(LOCKED_NOTE)).toHaveCount(0);
    // 배지는 그룹 머리에 <b>하나뿐</b>이다(원자 해석을 "각각 독립"으로 오해시키지 않기 위해).
    await expect(credentialGroup(page).getByText('기본값 사용 중')).toHaveCount(1);

    // 이 픽스처는 힌트 세 갈래 중 <b>두 갈래</b>를 한 화면에 담는다(`createAiSettings` 기본값:
    // api_key 는 마스크가 있고 cli_oauth_token 은 빈 값, 둘 다 상속 중). 빈 입력창 자체는 두
    // 경우에 똑같이 보이므로, 이 두 문구가 갈리지 않으면 "값이 있는데 없다고 읽는"(또는 그 반대)
    // 화면이 된다 — 전자는 사용자가 멀쩡한 키를 다시 붙여넣게 만들고, 후자는 없는 키를 믿게 만든다.
    await expect(fieldBox(page, 'ai-api-key').getByText(SECRET_SET_HINT)).toBeVisible();
    await expect(fieldBox(page, 'ai-cli-oauth-token').getByText(SECRET_UNSET_HINT)).toBeVisible();
    // 상속 중이므로 "번들 안에서 비어 있음" 노트가 아니다 — 그 문구는 플랫폼 값이 안 쓰인다고
    // 말하는데, 여기서는 플랫폼 값이 그대로 쓰이고 있다.
    await expect(
      fieldBox(page, 'ai-cli-oauth-token').getByText(EMPTY_IN_BUNDLE_NOTE),
    ).toHaveCount(0);

    await page.screenshot({ path: screenshotPath('agent-type-editable.png'), fullPage: true });
  });

  test('에이전트 유형만 바꾸면 자격증명은 플랫폼 값을 상속하지 않고 빈 값으로 재시드된다(번들 규칙 UX)', async ({
    authenticatedPage: page,
  }) => {
    let saved = false;
    let putPayload: Record<string, string> | null = null;

    await setupSettingsMocks(page, { ai: () => (saved ? afterAgentTypeSaveAi : inheritedAi) });
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putPayload = (route.request().postDataJSON() as { settings: Record<string, string> }).settings;
        saved = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );
    await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

    await page.goto('/admin/settings');
    await expect(fieldBox(page, 'ai-api-key').getByText(SECRET_SET_HINT)).toBeVisible();

    // 에이전트 유형만 바꾼다 — API 키는 건드리지 않는다.
    await page.locator('#ai-agent-type').click();
    await page.getByRole('option', { name: 'Claude API' }).click();
    await expect(page.locator('#ai-agent-type')).toContainText('Claude API');

    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });

    // 경계: 손대지 않은 API 키는 페이로드에 없다.
    expect(putPayload).toEqual({ 'ai.agent_type': 'cli-api' });

    // cli-api 로 바뀌었으니 OAuth 토큰 필드는 화면에서 사라진다(sdk/cli 전용 분기).
    await expect(page.locator('#ai-cli-oauth-token')).toHaveCount(0);
    // 핵심(브리프가 요구하는 "화면에서 확인"): 손대지 않은 API 키가 여전히 "현재 값이 설정되어
    // 있습니다"로 보이면 이 화면의 그룹 안내문("셋 중 하나만 바꿔도 나머지는 ... 비워지고")이
    // 거짓말이 된다. 입력 값은 어느 쪽이든 빈 문자열이라 단언 대상이 될 수 없다.
    await expect(fieldBox(page, 'ai-api-key').getByText(EMPTY_IN_BUNDLE_NOTE)).toBeVisible();
    await expect(fieldBox(page, 'ai-api-key').getByText(SECRET_SET_HINT)).toHaveCount(0);

    await page.screenshot({ path: screenshotPath('agent-type-only-save-clears-credentials.png'), fullPage: true });
  });

  /**
   * Finding 1 — 플랫폼 상속으로 돌아가는 길.
   *
   * 키 단위 해제로는 표현할 수 없는 조작이다: `clearOverride` 가 행 하나를 지워도
   * `applyAiCredentialBundle` 이 남은 두 행을 보고 계속 발동해, "해제한" 키가 플랫폼 값이 아니라
   * 번들 채움("" / "sdk")으로 해석된다. 3키를 모두 지워야만 상속으로 돌아간다.
   */
  test('자격증명 그룹 해제는 3키를 모두 DELETE 하고 그룹을 플랫폼 상속으로 되돌린다', async ({
    authenticatedPage: page,
  }) => {
    const { deletedPaths } = await captureOverrideDeletes(page);
    await setupSettingsMocks(page, {
      ai: () => (deletedPaths.length > 0 ? afterBundleClearAi : overriddenAi),
    });

    await page.goto('/admin/settings');

    const group = credentialGroup(page);
    // 전제: 번들이 재정의 상태이고, 배지·해제 버튼이 그룹 머리에 하나씩만 있다.
    await expect(group.getByText('테넌트 재정의 적용됨')).toHaveCount(1);
    await expect(group.getByRole('button', { name: /재정의 해제/ })).toHaveCount(1);

    await group.getByRole('button', { name: '자격증명 전체 재정의 해제' }).click();
    const dialog = page.getByRole('alertdialog');
    // 다이얼로그가 영향 범위를 <b>이름으로</b> 나열해야 한다 — "이 그룹"이라고만 쓰면 사용자가
    // 경계를 스크롤 밖에서 추정해야 한다.
    await expect(dialog.getByText('에이전트 유형, OAuth 토큰, API 키', { exact: false })).toBeVisible();
    await dialog.getByRole('button', { name: '되돌리기' }).click();

    // 핵심: 3키 전부가 지워져야 한다. 둘만 지우면 서버 번들이 계속 발동해 나머지가 플랫폼 값이
    // 아니라 빈 값으로 해석되고, 화면은 "상속으로 돌아갔다"고 거짓말한다.
    await expect
      .poll(() => [...deletedPaths].sort())
      .toEqual([
        '/api/v1/settings/overrides/ai.agent_type',
        '/api/v1/settings/overrides/ai.api_key',
        '/api/v1/settings/overrides/ai.cli_oauth_token',
      ]);

    // 재조회 결과가 화면에 반영된다 — 그룹 배지가 상속으로 돌아가고 해제 버튼이 사라진다.
    await expect(group.getByText('기본값 사용 중')).toHaveCount(1);
    await expect(group.getByRole('button', { name: /재정의 해제/ })).toHaveCount(0);
    await expect(page.getByText('플랫폼 기본값으로 되돌렸습니다.')).toBeVisible({ timeout: 5000 });
    // 재시드가 dirty 를 만들면 안 된다.
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();

    await page.screenshot({ path: screenshotPath('credential-bundle-clear.png'), fullPage: true });
  });

  /**
   * Finding 2 의 핵심 성질 — <b>손대지 않은 비밀은 페이로드에 없다</b>.
   *
   * 입력이 비어 있는 것만으로는 부족하다: `form` 만 비우고 `original` 에 마스크를 남기면 손대지
   * 않은 비밀이 "빈 값으로 바뀌었다"로 판정되어 저장이 통째로 거부되거나(거부 목록) 빈 문자열이
   * 실려 자격증명이 날아간다. 그래서 <b>실제 PUT 페이로드</b>를 본다.
   */
  test('편집 가능한 비밀 2키를 손대지 않으면 저장 페이로드에 담기지 않는다', async ({
    authenticatedPage: page,
  }) => {
    let putPayload: Record<string, string> | null = null;
    await setupSettingsMocks(page, { ai: inheritedAi });
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putPayload = (route.request().postDataJSON() as { settings: Record<string, string> }).settings;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );
    await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

    await page.goto('/admin/settings');
    // 전제: 서버는 두 비밀에 대해 <b>값이 있다</b>고 내려준다(마스크). 값이 없는 픽스처를 쓰면
    // "원래 보낼 것이 없었다"로도 통과해 이 테스트가 공허해진다.
    await expect(fieldBox(page, 'ai-api-key').getByText(SECRET_SET_HINT)).toBeVisible();
    await expect(fieldBox(page, 'ai-cli-oauth-token').getByText(SECRET_SET_HINT)).toBeVisible();

    // 비밀이 아닌 필드 하나만 편집한다.
    await page.locator('#ai-max-turns').fill('15');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });

    // 부분 단언(toMatchObject)으로 두면 비밀 2키가 섞여 들어와도 통과한다 — 전체 일치여야 한다.
    expect(putPayload).toEqual({ 'ai.max_turns': '15' });
  });

  /**
   * Finding 1(c) — `sdk` 테넌트가 자기 OAuth 토큰을 내려 자기 API 키로 떨어질 길.
   *
   * 입력창을 비우는 제스처로는 표현할 수 없다(폼이 항상 비어 있어 "비웠다"는 변경이 성립하지
   * 않는다). 그래서 명시적 버튼이 빈 값 PUT 을 쏜다. 서버는 `ai.cli_oauth_token` 의 빈 값을
   * 명시적으로 허용한다(`SettingsService.validateValues` 의 no-op 분기).
   */
  test('저장된 OAuth 토큰 삭제는 그 키만 빈 값으로 PUT 하고 API 키는 건드리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    let deleted = false;
    let putPayload: Record<string, string> | null = null;

    await setupSettingsMocks(page, { ai: () => (deleted ? afterTokenDeleteAi : overriddenAi) });
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putPayload = (route.request().postDataJSON() as { settings: Record<string, string> }).settings;
        deleted = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );
    // 삭제 직후 화면이 인증 배지를 다시 읽는다(#390 item 2) — 모킹하지 않으면 실네트워크로 빠진다.
    await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

    await page.goto('/admin/settings');
    const tokenBox = fieldBox(page, 'ai-cli-oauth-token');
    await expect(tokenBox.getByText(SECRET_SET_HINT)).toBeVisible();

    await tokenBox.getByRole('button', { name: '저장된 OAuth 토큰 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('에이전트 유형과 API 키는 그대로 유지', { exact: false })).toBeVisible();
    await dialog.getByRole('button', { name: '삭제' }).click();

    // 페이로드에 토큰 하나만 있어야 한다 — API 키가 섞이면 "토큰만 지운다"가 거짓이 된다.
    await expect.poll(() => putPayload).toEqual({ 'ai.cli_oauth_token': '' });
    await expect(page.getByText('저장된 OAuth 토큰을 삭제했습니다.')).toBeVisible({ timeout: 5000 });

    // 화면이 서버 해석을 따라간다: 토큰은 번들 안에서 빈 값, API 키는 그대로 설정된 상태.
    await expect(tokenBox.getByText(EMPTY_IN_BUNDLE_NOTE)).toBeVisible();
    await expect(fieldBox(page, 'ai-api-key').getByText(SECRET_SET_HINT)).toBeVisible();
    // 삭제가 끝났으므로 버튼도 사라진다(지울 값이 더 이상 없다).
    await expect(tokenBox.getByRole('button', { name: '저장된 OAuth 토큰 삭제' })).toHaveCount(0);
  });

  test('번들이 아직 상속 중이면 "저장된 OAuth 토큰 삭제"가 아예 없다', async ({
    authenticatedPage: page,
  }) => {
    // 게이트가 없으면 이 버튼의 PUT 이 <b>테넌트 행을 새로 만들어</b> 번들을 발동시키고, 상속
    // 중이던 `ai.api_key` 가 "" 로 떨어진다 — "토큰만 지운다"가 "API 키까지 날린다"가 된다.
    // `inheritedAi` 는 토큰에 값이 있으면서(****oldt) 번들은 상속 중이라 정확히 이 경계다.
    await setupSettingsMocks(page, { ai: inheritedAi });
    await page.goto('/admin/settings');

    await expect(fieldBox(page, 'ai-cli-oauth-token').getByText(SECRET_SET_HINT)).toBeVisible();
    await expect(
      fieldBox(page, 'ai-cli-oauth-token').getByRole('button', { name: '저장된 OAuth 토큰 삭제' }),
    ).toHaveCount(0);
  });
  /**
   * #390 item 1 — <b>번들 해제 부분 실패</b>. 번들 삭제 엔드포인트가 없어 3번의 DELETE 로 쪼개지는
   * 이상 이 중간 상태는 실재한다. 원자 해석 아래에서 <b>안전하지만</b>(행이 하나라도 남으면 3키가
   * 전부 테넌트 평면에서 해석된다) 사용자가 보기엔 "해제했는데 아직 재정의 배지"라, 다시 조작해야
   * 하는 상태다. 토스트 한 번으로 뭉개면 스크린리더 사용자가 놓친다.
   *
   * SMTP 연결 그룹의 같은 테스트(`settings.spec.ts` "그룹 해제 중 하나가 실패하면…")를 거울로 삼는다.
   */
  test('자격증명 그룹 해제 중 하나가 실패하면 부분 실패 문구가 화면에 남는다', async ({
    authenticatedPage: page,
  }) => {
    const { deletedPaths } = await captureOverrideDeletes(page, { failOn: 'ai.cli_oauth_token' });
    await setupSettingsMocks(page, {
      // 재조회에서도 여전히 번들 재정의 상태다 — 토큰 행이 남아 번들이 계속 발동한다.
      ai: () => (deletedPaths.length > 0 ? afterPartialBundleClearAi : overriddenAi),
    });
    // 해제는 <b>부분 실패해도</b> 적용되는 자격증명을 바꾼다(3키 중 둘이 플랫폼 값으로 돌아갔다).
    // 배지 갱신이 성공 경로에만 걸려 있으면 여기서 낡은 `✓ 인증됨` 이 남는다 — 그 갈림을 덮는다.
    let authValid = true;
    await mockAiAuthStatus(page, () => ({ valid: authValid, email: 'tenant@example.com' }));

    await page.goto('/admin/settings');
    const group = credentialGroup(page);
    await expect(group.getByText('테넌트 재정의 적용됨')).toHaveCount(1);

    // 전제: 해제 전 자격증명으로 얻은 "인증됨" 이 떠 있다.
    await fieldBox(page, 'ai-cli-oauth-token').getByRole('button', { name: '인증 확인' }).click();
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false }).first()).toBeVisible({
      timeout: 8000,
    });
    authValid = false;

    await group.getByRole('button', { name: '자격증명 전체 재정의 해제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '되돌리기' }).click();

    // 실패한 키는 `deletedPaths` 에 담기지 않는다 — 성공한 둘만 남아야 한다. 이 단언이 없으면
    // "애초에 DELETE 가 안 나갔다"도 통과한다.
    await expect
      .poll(() => [...deletedPaths].sort())
      .toEqual([
        '/api/v1/settings/overrides/ai.agent_type',
        '/api/v1/settings/overrides/ai.api_key',
      ]);

    // 토스트가 아니라 <b>화면에 남는 텍스트</b>여야 한다. 슬롯은 탭 범위이므로 자격증명
    // `fieldset` 안에는 없다(#390 item 3 이 이 자리를 옮겼다 — 저장 후 재조회 실패가 같은 슬롯을
    // 쓰게 되면서 "그룹 안의 사건뿐"이라는 전제가 깨졌다).
    await expect(
      aiPanel(page).getByText('일부 항목만 해제되었습니다', { exact: false }),
    ).toBeVisible({ timeout: 8000 });
    await expect(
      credentialGroup(page).getByText('일부 항목만 해제되었습니다', { exact: false }),
    ).toHaveCount(0);

    // <b>무엇이 남았는지</b>를 이름으로 말해야 한다. "다시 시도하세요"라면서 3개 중 무엇을 다시
    // 시도해야 하는지 말하지 않으면 사용자가 할 수 있는 것이 없다.
    await expect(
      aiPanel(page).getByText('해제하지 못한 항목: OAuth 토큰', { exact: false }),
    ).toBeVisible();
    // 성공한 두 항목이 실패 목록에 섞이면 사용자가 멀쩡한 항목을 다시 지우려 든다.
    await expect(aiPanel(page).getByText('해제하지 못한 항목: OAuth 토큰, ', { exact: false })).toHaveCount(0);
    await expect(aiPanel(page).getByText('API 키, OAuth 토큰', { exact: false })).toHaveCount(0);

    // 성공이라고 말하면 안 된다 — 사용자가 다 끝난 줄 알고 떠난다.
    await expect(page.getByText('플랫폼 기본값으로 되돌렸습니다.')).toHaveCount(0);

    // 그리고 안전하다는 사실도 함께 말한다: 남은 항목은 아직 우리 조직 값으로 적용 중이다.
    await expect(group.getByText('테넌트 재정의 적용됨')).toHaveCount(1);
    await expect(group.getByRole('button', { name: '자격증명 전체 재정의 해제' })).toHaveCount(1);

    // 부분 실패도 자격증명을 바꿨으므로 인증 배지가 따라와야 한다.
    await expect(
      fieldBox(page, 'ai-cli-oauth-token').getByText('✗ 유효하지 않음', { exact: false }),
    ).toBeVisible({ timeout: 8000 });
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false })).toHaveCount(0);
  });

  /**
   * #390 item 2 — 토큰을 지우면 <b>인증 배지도 다시 읽는다</b>.
   *
   * `✓ 인증됨` 은 방금 지운 <b>그 토큰</b>으로 얻은 결과다. 삭제 후에도 그대로 남으면 화면이
   * "인증됨"이라고 말하는데 서버에는 토큰이 없다 — `handleSave` 는 저장 뒤 `verifyAuth()` 를
   * 부르는데 삭제 쪽만 빠져 있었다.
   */
  test('저장된 OAuth 토큰을 삭제하면 인증 배지를 다시 읽는다', async ({
    authenticatedPage: page,
  }) => {
    let deleted = false;
    // 삭제 전후로 다른 인증 결과를 준다 — 한쪽만 주면 "다시 읽지 않아도" 배지가 그대로라 통과한다.
    let authValid = true;

    await setupSettingsMocks(page, { ai: () => (deleted ? afterTokenDeleteAi : overriddenAi) });
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        deleted = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );
    // 값을 함수로 넘긴다 — `mockApi` 는 고정 본문이라 도중에 바꿀 수 없다(헬퍼 주석 참고).
    await mockAiAuthStatus(page, () => ({ valid: authValid, email: 'tenant@example.com' }));

    await page.goto('/admin/settings');
    const tokenBox = fieldBox(page, 'ai-cli-oauth-token');

    // 전제: 배지가 "인증됨"으로 떠 있다. 이 상태를 만들지 않으면 삭제 후 배지가 없는 것이
    // "다시 읽어서 바뀐" 결과인지 "애초에 없던" 것인지 구별되지 않는다.
    await tokenBox.getByRole('button', { name: '인증 확인' }).click();
    await expect(tokenBox.getByText('✓ 인증됨', { exact: false })).toBeVisible({ timeout: 8000 });

    // 서버 쪽 사실을 뒤집는다 — 토큰이 사라졌으니 이제 인증은 실패한다.
    authValid = false;

    await tokenBox.getByRole('button', { name: '저장된 OAuth 토큰 삭제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await expect(page.getByText('저장된 OAuth 토큰을 삭제했습니다.')).toBeVisible({ timeout: 8000 });

    // 핵심: 배지가 새 사실을 반영해야 한다. 다시 읽지 않으면 여기서 `✓ 인증됨` 이 그대로 남는다.
    await expect(
      fieldBox(page, 'ai-api-key').getByText('✗ 유효하지 않음', { exact: false }),
    ).toBeVisible({ timeout: 8000 });
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false })).toHaveCount(0);
  });

  /**
   * #390 item 3 — <b>저장은 성공했고 다시 그리기가 실패했다</b>.
   *
   * 예전에는 `.catch(() => undefined)` 로 삼켰다. 그러면 화면이 조용히 거짓말한다: 방금 저장한
   * 비밀의 평문이 `form`·`original` 에 남고 힌트는 옛 "설정되어 있습니다"를 계속 보여주는데,
   * 사용자에게는 "저장되었습니다"만 보인다.
   *
   * 자격증명이 아닌 `ai.max_turns` 만 저장한다 — 이 사건이 <b>번들 전용이 아님</b>을 고정해야
   * 안내 슬롯이 왜 탭 범위여야 하는지가 테스트로 남는다.
   */
  test('저장 후 재조회가 실패하면 저장 성공과 별개로 화면이 낡았다고 알린다', async ({
    authenticatedPage: page,
  }) => {
    // 호출 횟수가 아니라 플래그로 분기한다 — StrictMode 가 최초 마운트에서 GET 을 두 번 낸다.
    let refetchFails = false;
    await setupSettingsMocks(page, {
      ai: () => (refetchFails ? SETTINGS_FETCH_ERROR : inheritedAi),
    });
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );
    await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

    await page.goto('/admin/settings');
    await expect(page.locator('#ai-max-turns')).toHaveValue(/.+/);
    // 최초 조회가 끝난 뒤에만 재조회를 실패시킨다.
    refetchFails = true;

    await page.locator('#ai-max-turns').fill('15');
    await page.getByRole('button', { name: '저장' }).click();

    // 저장 성공은 성공대로 말한다 — "저장 실패"로 뭉뚱그리면 사용자가 저장된 값을 되돌리려 든다.
    await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
    // 그리고 화면이 낡았다는 사실을 <b>지속 안내</b>로 남긴다(토스트만으로는 사라진다).
    await expect(
      aiPanel(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
    ).toBeVisible({ timeout: 8000 });
    await expect(aiPanel(page).getByText('새로고침하세요', { exact: false })).toBeVisible();
    // <b>자리를 고정한다.</b> 번들과 무관한 키를 저장했으므로 자격증명 fieldset 안이면 안 된다 —
    // 거기 붙으면 사용자가 "자격증명이 잘못됐다"로 읽는다.
    await expect(
      credentialGroup(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
    ).toHaveCount(0);
  });

  /**
   * #390 item 4 — 해제·삭제는 <b>저장하지 않은 입력</b>도 함께 버린다.
   *
   * 두 다이얼로그의 기본 카피는 전부 "저장된" 값 이야기인데, 두 핸들러 모두 끝에서 3키를
   * `resyncFromServer` 하므로 방금 친 값도 서버 해석으로 덮인다. 입력을 되살리지 않는 것은
   * 의도한 결정이므로(되돌린다고 확인해 놓고 입력만 남으면 그게 더 놀랍다), <b>누르기 전에</b>
   * 말하는 것으로 해결한다.
   */
  test('저장하지 않은 자격증명 입력이 있으면 확인 다이얼로그가 그 사실을 말한다', async ({
    authenticatedPage: page,
  }) => {
    await setupSettingsMocks(page, { ai: overriddenAi });
    await page.goto('/admin/settings');

    const group = credentialGroup(page);
    const UNSAVED_WARNING = '아직 저장하지 않은 입력이 있습니다';

    // (1) 입력이 없으면 기본 카피 그대로다 — 늘 붙이면 경고가 배경 소음이 된다.
    await group.getByRole('button', { name: '자격증명 전체 재정의 해제' }).click();
    const cleanDialog = page.getByRole('alertdialog');
    await expect(
      cleanDialog.getByText('에이전트 유형, OAuth 토큰, API 키', { exact: false }),
    ).toBeVisible();
    await expect(cleanDialog.getByText(UNSAVED_WARNING, { exact: false })).toHaveCount(0);
    await cleanDialog.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    // (2) 번들 키 하나에 입력이 생기면 두 다이얼로그 모두 그 사실을 덧붙인다.
    await page.locator('#ai-api-key').fill('sk-typed-but-not-saved');

    await group.getByRole('button', { name: '자격증명 전체 재정의 해제' }).click();
    const dirtyDialog = page.getByRole('alertdialog');
    await expect(dirtyDialog.getByText(UNSAVED_WARNING, { exact: false })).toBeVisible();
    await dirtyDialog.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await fieldBox(page, 'ai-cli-oauth-token')
      .getByRole('button', { name: '저장된 OAuth 토큰 삭제' })
      .click();
    await expect(
      page.getByRole('alertdialog').getByText(UNSAVED_WARNING, { exact: false }),
    ).toBeVisible();
  });

  /**
   * #390 item 5 — 되돌릴 수 없는 삭제는 <b>되돌리기처럼 보이면 안 된다</b>.
   *
   * "저장된 OAuth 토큰 삭제"는 서버가 평문을 내려주지 않아 복구 경로가 없는데, 재정의 해제와 같은
   * 컴포넌트를 쓰면서 문구만 바꿔 되돌리기 아이콘과 비파괴 확인 버튼을 그대로 달고 있었다.
   *
   * <b>같은 화면의 그룹 해제로 반대편을 함께 고정한다</b> — 그쪽은 언제든 다시 재정의할 수 있는
   * 되돌릴 수 있는 조작이라 파괴적 표현이 붙으면 안 된다. 이 대조가 없으면 "전부 빨갛게 칠하기"도
   * 통과한다(그 변경은 SMTP 탭의 해제 버튼까지 함께 물들인다).
   */
  test('복구 불가한 토큰 삭제만 파괴적 표현을 쓰고, 되돌릴 수 있는 그룹 해제는 그대로다', async ({
    authenticatedPage: page,
  }) => {
    await setupSettingsMocks(page, { ai: overriddenAi });
    await page.goto('/admin/settings');

    const deleteTrigger = fieldBox(page, 'ai-cli-oauth-token').getByRole('button', {
      name: '저장된 OAuth 토큰 삭제',
    });
    const clearTrigger = credentialGroup(page).getByRole('button', {
      name: '자격증명 전체 재정의 해제',
    });

    // 트리거: 위험색 + 휴지통 아이콘. 색만으로는 색각 이상 사용자에게 전달되지 않으므로 아이콘도 본다.
    await expect(deleteTrigger).toHaveClass(/text-destructive/);
    await expect(deleteTrigger.locator('svg.lucide-trash2')).toHaveCount(1);
    await expect(deleteTrigger.locator('svg.lucide-rotate-ccw')).toHaveCount(0);

    // 반대편: 되돌릴 수 있는 해제는 옛 표현 그대로다(SMTP 탭의 모든 해제 버튼이 같은 경로다).
    await expect(clearTrigger).not.toHaveClass(/text-destructive/);
    await expect(clearTrigger.locator('svg.lucide-rotate-ccw')).toHaveCount(1);

    // 확인 버튼도 갈린다.
    await deleteTrigger.click();
    await expect(page.getByRole('alertdialog').getByRole('button', { name: '삭제' })).toHaveClass(
      /bg-destructive/,
    );
    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await clearTrigger.click();
    await expect(
      page.getByRole('alertdialog').getByRole('button', { name: '되돌리기' }),
    ).not.toHaveClass(/bg-destructive/);
  });
  /**
   * 리뷰 Medium — <b>늦게 도착한 낡은 인증 확인이 화면을 되돌리면 안 된다</b>.
   *
   * `onCredentialsChanged` 가 세 번째 `verifyAuth` 호출부를 열면서 겹침이 처음으로 도달 가능해졌다:
   * "인증 확인" 버튼은 `isVerifying` 으로 잠기지만 자격증명 조작 버튼은 `isClearing` 으로만 잠긴다.
   * auth-status 는 외부 제공자를 부르므로 느린 응답이 이상한 일이 아니다.
   *
   * 이 테스트는 두 응답의 <b>도착 순서를 실제로 뒤집는다</b> — 그냥 토큰을 지우고 배지를 보는
   * 테스트는 가드가 있든 없든 통과하는 공허한 단언이다.
   */
  test('인증 확인이 진행 중일 때 토큰을 지우면, 늦게 도착한 낡은 응답이 배지를 되돌리지 못한다', async ({
    authenticatedPage: page,
  }) => {
    let deleted = false;
    let authCalls = 0;
    // 첫 번째(A) 응답을 테스트가 풀어 줄 때까지 붙잡는다 — 시간에 기대지 않고 순서를 확정한다.
    let releaseStaleAuth = false;
    let staleAuthFulfilled = false;

    await setupSettingsMocks(page, { ai: () => (deleted ? afterTokenDeleteAi : overriddenAi) });
    await page.route(
      (url) => url.pathname === '/api/v1/settings',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        deleted = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );
    // <b>이 한 곳만 `mockAiAuthStatus` 를 쓰지 않는다.</b> 이 테스트의 전부가 "첫 응답을 붙잡아
    // 도착 순서를 뒤집는다"인데, 그러려면 핸들러가 호출 회차를 세고 <b>응답 시점</b>을 직접
    // 다뤄야 한다 — 값만 함수로 받는 헬퍼로는 표현할 수 없고, 헬퍼를 그렇게 넓히면 나머지 세
    // 호출부가 쓰지 않는 기능이 생긴다. 대신 <b>경로 상수와 본문 생성기는 공유해서</b> 계약이
    // 바뀔 때 이 자리만 빠지는 일을 막는다(타이밍은 한 글자도 건드리지 않는다).
    await page.route(
      (url) => url.pathname === AI_AUTH_STATUS_PATH,
      async (route) => {
        authCalls += 1;
        if (authCalls === 1) {
          // A — 토큰이 살아 있던 시점의 확인. `valid:true` 를 들고 <b>가장 늦게</b> 도착한다.
          // 라우트 핸들러 안에서는 `page.waitForTimeout` 을 쓸 수 없다(login.spec.ts 와 같은 이유).
          //
          // <b>마감 시한을 둔다.</b> 테스트가 `releaseStaleAuth = true` 에 닿기 전에 실패하면 이
          // 루프는 워커가 죽을 때까지 돈다 — 진짜 실패 원인이 타임아웃 뒤에 묻힌다. 시한이 지나면
          // 그냥 응답을 보내고, 그 경우 테스트는 단언에서 제 이유로 실패한다.
          const deadline = Date.now() + 15_000;
          while (!releaseStaleAuth && Date.now() < deadline) {
            await new Promise((resolve) => setTimeout(resolve, 50));
          }
          await route.fulfill(aiAuthStatusResponse({ valid: true, email: 'tenant@example.com' }));
          staleAuthFulfilled = true;
          return;
        }
        // B — 삭제 직후의 확인. 토큰이 없으니 실패한다.
        return route.fulfill(aiAuthStatusResponse({ valid: false }));
      },
    );

    await page.goto('/admin/settings');
    const tokenBox = fieldBox(page, 'ai-cli-oauth-token');

    // (A) 인증 확인을 시작하고 응답을 붙잡아 둔다.
    await tokenBox.getByRole('button', { name: '인증 확인' }).click();
    await expect.poll(() => authCalls).toBe(1);

    // (B) 그 사이에 토큰을 지운다 — 이 버튼은 `isClearing` 으로만 잠기므로 실제로 누를 수 있다.
    await tokenBox.getByRole('button', { name: '저장된 OAuth 토큰 삭제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await expect(page.getByText('저장된 OAuth 토큰을 삭제했습니다.')).toBeVisible({ timeout: 8000 });
    // B 의 답이 화면에 도달했다.
    await expect(
      fieldBox(page, 'ai-api-key').getByText('✗ 유효하지 않음', { exact: false }),
    ).toBeVisible({ timeout: 8000 });

    // 이제 A 를 풀어 준다 — `valid:true` 가 <b>마지막에</b> 도착한다.
    //
    // <b>고정 대기(`waitForTimeout`)를 쓰면 안 된다.</b> 이 테스트의 힘은 "낡은 응답이 화면을
    // 되돌릴 기회를 <b>실제로</b> 가졌다"에 달려 있는데, 고정 시간은 느린 러너에서 그 기회가
    // 오기 <b>전에</b> 단언이 끝나 <b>가드를 지워도 통과</b>한다 — 실패 방향이 거짓 실패가 아니라
    // <b>거짓 통과</b>라 침묵하고, 이 리포가 네 번 겪은 공허한 테스트의 모양이 바로 그것이다.
    //
    // 그래서 <b>인과적 배리어</b> 둘을 잇는다:
    //  1. `waitForResponse` — A 의 응답이 브라우저에 실제로 도착했다(시간이 아니라 사건).
    //  2. 두 번의 `requestAnimationFrame` — 그 도착 이후 페이지가 <b>두 프레임을 커밋</b>했다.
    //     React 가 응답 처리로 예약한 렌더가 있었다면 이 안에서 반드시 화면에 반영된다.
    // 러너가 아무리 느려도 둘 다 사건을 기다리므로, 가드가 없으면 <b>어느 기계에서나</b> 빨개진다.
    const staleResponse = page.waitForResponse((res) => res.url().includes(AI_AUTH_STATUS_PATH));
    releaseStaleAuth = true;
    await expect.poll(() => staleAuthFulfilled).toBe(true);
    await staleResponse;
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );

    // 핵심: 이미 지운 토큰으로 얻은 `true` 가 화면을 되돌리면 안 된다.
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false })).toHaveCount(0);
    await expect(
      fieldBox(page, 'ai-api-key').getByText('✗ 유효하지 않음', { exact: false }),
    ).toBeVisible();
  });

  /**
   * (C) — 번들 해제도 <b>적용되는 자격증명을 바꾼다</b>(테넌트 값 → 플랫폼 값).
   * 토큰 삭제에만 배지 갱신을 달면 같은 종류의 낡은 배지가 다른 버튼으로 다시 들어온다.
   */
  test('자격증명 번들을 해제하면 인증 배지를 다시 읽는다', async ({ authenticatedPage: page }) => {
    let authValid = true;
    const { deletedPaths } = await captureOverrideDeletes(page);
    await setupSettingsMocks(page, {
      ai: () => (deletedPaths.length > 0 ? afterBundleClearAi : overriddenAi),
    });
    await mockAiAuthStatus(page, () => ({ valid: authValid, email: 'tenant@example.com' }));

    await page.goto('/admin/settings');
    const group = credentialGroup(page);
    const tokenBox = fieldBox(page, 'ai-cli-oauth-token');

    // 전제: 테넌트 자격증명으로 얻은 "인증됨" 이 떠 있다.
    await tokenBox.getByRole('button', { name: '인증 확인' }).click();
    await expect(tokenBox.getByText('✓ 인증됨', { exact: false })).toBeVisible({ timeout: 8000 });

    // 해제하고 나면 플랫폼 자격증명이 적용된다 — 이 배포에서는 그게 통하지 않는다고 하자.
    authValid = false;

    await group.getByRole('button', { name: '자격증명 전체 재정의 해제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '되돌리기' }).click();
    await expect(page.getByText('플랫폼 기본값으로 되돌렸습니다.')).toBeVisible({ timeout: 8000 });

    // 핵심: 해제 전 자격증명으로 얻은 `✓ 인증됨` 이 남아 있으면 안 된다.
    await expect(tokenBox.getByText('✗ 유효하지 않음', { exact: false })).toBeVisible({
      timeout: 8000,
    });
    await expect(aiPanel(page).getByText('✓ 인증됨', { exact: false })).toHaveCount(0);
  });
});
