import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { createAiSettings } from '../../factories/admin.factory';
import { captureOverrideDeletes, setupAdminAuth, setupSettingsMocks } from '../../fixtures/admin.fixture';
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
});
