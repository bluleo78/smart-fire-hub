import type { Page } from '@playwright/test';

import { createAiSettings, createSmtpSettings } from '../../factories/admin.factory';
import { captureSmtpClear, SETTINGS_FETCH_ERROR } from '../../fixtures/admin.fixture';
import { setupAdminAuth, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 설정 페이지 E2E 테스트
 *
 * 검증 대상은 "무엇이 보이는가"가 아니라 화면이 지키는 계약이다:
 *  - AI 탭: 동작 설정 6키가 서버 값으로 채워지고, `PUT /settings` 페이로드가 이번에 바꾼 키만 담는가.
 *    AI 설정은 테넌트 전용이다 — 그 화면 계약(기본값 힌트, 문구 부재)은 `tenant-ai-settings.spec.ts` 가 맡는다.
 *  - 이메일 탭(SMTP 6키, #712 워크스페이스 전용): 미설정(`[]`)이면 안내 + 빈 폼, 저장은 6키를 한 벌로
 *    `PUT /settings`, "설정 해제"는 확인 뒤 `DELETE /settings/smtp` 한 번. 비밀번호 마스크가 입력에
 *    들어가지 않고 빈 칸이면 키가 빠져 서버가 유지하는가, 필수 키 규칙이 지켜지는가가 이 탭 고유의 경계다.
 *  - 임베딩 탭이 전면 잠금이고 저장 경로가 화면에서 사라졌는가
 *
 * AdminRoute 통과를 위해 ADMIN 역할로 users/me 를 오버라이드한다.
 */

/**
 * 필드 한 개를 감싸는 컨테이너(라벨+배지+입력+안내문).
 *
 * 오류 문구·안내문은 여러 필드에 비슷하게 반복되므로, 컨테이너로 스코프를 좁히지 않으면
 * "어느 필드에 붙었는지"를 검증하지 못한다.
 */
const fieldBox = (page: Page, inputId: string) =>
  page.locator('div.space-y-2', { has: page.locator(`#${inputId}`) });

test.describe('설정 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('설정 페이지가 올바르게 로드된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '설정' })).toBeVisible();

    // 탭 목록 확인
    await expect(page.getByRole('tab', { name: '일반' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '이메일' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '임베딩' })).toBeVisible();
  });

  test('모든 탭에 아이콘이 렌더링된다 (UI 일관성)', async ({ authenticatedPage: page }) => {
    // 이슈 #4: "일반" 탭만 아이콘이 없어 탭 그룹 내 UI 불일관 — 수정 회귀 방지
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    for (const name of ['일반', 'AI 에이전트', '이메일', '임베딩']) {
      await expect(page.getByRole('tab', { name }).locator('svg')).toBeVisible();
    }
  });

  test('AI 에이전트 탭이 기본 선택되고 서버 응답 값이 각 필드에 반영된다', async ({
    authenticatedPage: page,
  }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // AI 에이전트 탭이 기본 선택되어 있음 (defaultValue="ai")
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await expect(page.getByText('모델 설정')).toBeVisible();

    // 응답 → UI 반영 검증. 모델은 코드값(claude-sonnet-5)이 사람이 읽는 라벨로 표시되어야 한다.
    await expect(page.locator('#ai-model')).toContainText('Claude Sonnet 5');
    await expect(page.locator('#ai-max-turns')).toHaveValue('10');
    await expect(page.locator('#ai-temperature')).toHaveValue('1.0');
    await expect(page.locator('#ai-max-tokens')).toHaveValue('16384');
    await expect(page.locator('#ai-session-max-tokens')).toHaveValue('50000');
    await expect(page.locator('#ai-system-prompt')).toHaveValue(
      '당신은 Smart Fire Hub의 AI 어시스턴트입니다.\n응답은 한국어로 하고, 마크다운 형식을 사용하세요.',
    );

    // 계약: AI 동작 설정 6키는 테넌트 전용이라 **전부** 조작 가능해야 한다.
    for (const id of ['ai-model', 'ai-max-turns', 'ai-max-tokens', 'ai-session-max-tokens', 'ai-system-prompt', 'ai-temperature']) {
      await expect(page.locator(`#${id}`)).toBeEnabled();
    }
  });

  test('일반 탭 클릭 시 탭 내용이 전환된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // 일반 탭 클릭
    await page.getByRole('tab', { name: '일반' }).click();

    // 일반 탭 내용 확인 ("준비 중입니다" 메시지)
    await expect(page.getByText('준비 중입니다')).toBeVisible();
  });

  /**
   * 저장 페이로드는 AI 동작 설정 6키 중 <b>실제로 바꾼 키만</b> 담는다. 편집하지 않은 키까지 보내면
   * 서버가 기본값으로 내려준 값이 그대로 저장값으로 굳어, 이후 코드 기본값이 바뀌어도 따라가지 않는다.
   */
  test.describe('저장 페이로드 경계', () => {
    test(
      '설정 변경 후 저장하면 바꾼 키만 PUT 되고 미편집 키는 담기지 않는다',
      { tag: '@smoke' },
      async ({ authenticatedPage: page }) => {
        await setupSettingsMocks(page);
        // PUT 캡처 — goto 이전에 등록해야 첫 저장을 놓치지 않는다
        const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
        await page.goto('/admin/settings');
        await expect(page.locator('#ai-max-turns')).toHaveValue('10');

        await page.locator('#ai-max-turns').fill('15');
        const saveButton = page.getByRole('button', { name: '저장' });
        await expect(saveButton).toBeEnabled();
        await saveButton.click();

        const req = await saveCapture.waitForRequest();
        const settings = (req.payload as { settings: Record<string, string> }).settings;

        // 경계 단언: 페이로드는 **바꾼 키 하나만** 담는다. 부분 단언(toMatchObject)으로 두면
        // 나머지 5키(서버가 기본값으로 내려준 값)가 섞여 들어와도 통과한다.
        expect(Object.keys(settings)).toEqual(['ai.max_turns']);

        // 입력 → payload 값 검증
        expect(settings['ai.max_turns']).toBe('15');

        await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
      },
    );

    test('이미 저장된 키라도 이번에 손대지 않았으면 페이로드에 담기지 않는다', async ({
      authenticatedPage: page,
    }) => {
      // 계약: 제외 기준은 "이번에 바꾸지 않았다" 하나뿐이다 — 이미 저장된 키인지 여부와 무관하다.
      // 저장할 때마다 기존 값을 같은 값으로 다시 쓰면 updated_at 과 감사 로그가 오염된다.
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.temperature': { overridden: true, value: '0.7' } }),
      });
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });

      await page.goto('/admin/settings');
      // 저장된 값이 실려 있는 상태에서 출발한다는 전제를 먼저 고정한다
      await expect(page.locator('#ai-temperature')).toHaveValue('0.7');

      // temperature 는 건드리지 않고 다른 키 하나만 편집한다
      await page.locator('#ai-max-turns').fill('15');
      await page.getByRole('button', { name: '저장' }).click();

      const req = await saveCapture.waitForRequest();
      const settings = (req.payload as { settings: Record<string, string> }).settings;
      expect(Object.keys(settings)).toEqual(['ai.max_turns']);
      expect(settings).not.toHaveProperty('ai.temperature');
    });

    test('변경 전에는 저장 버튼이 비활성이고 편집 가능 필드를 바꾸면 활성화된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
      await page.locator('#ai-max-turns').fill('15');
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
    });

    test('되돌리기 버튼 클릭 시 변경 사항이 서버 값으로 초기화된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      const maxTurnsInput = page.locator('#ai-max-turns');
      await maxTurnsInput.fill('20');
      await page.getByRole('button', { name: '되돌리기' }).click();

      // 서버가 준 원래 값(10)으로 복원 + dirty 해제
      await expect(maxTurnsInput).toHaveValue('10');
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
    });

    /**
     * 계약 6: 편집한 필드를 비우고 저장하면 성공이 아니라 오류다.
     *
     * 실측 결론: 편집 가능 6키 중 5키(max_turns/temperature/max_tokens/session_max_tokens/
     * system_prompt)는 `validate()` 가 빈 값을 먼저 잡아 필드 아래 인라인 오류 + 공통 토스트로
     * 막고, 남은 `ai.model` 은 Select 라 UI 에서 비울 수단이 없다. 즉 "페이로드에서 빠진 키를
     * 이름으로 지목하는" 경로(handleSave 의 droppedChangedKeys)는 UI 로 도달할 수 없다.
     * 그래서 도달 가능한 경로 — 인라인 오류가 필드를 지목 + PUT 미발생 — 를 고정한다.
     */
    test('편집 가능 필드를 비우고 저장하면 필드에 오류가 붙고 PUT 이 발생하지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });

      await page.goto('/admin/settings');
      await page.locator('#ai-max-turns').fill('');

      const saveButton = page.getByRole('button', { name: '저장' });
      await expect(saveButton).toBeEnabled();
      await saveButton.click();

      // 어떤 필드가 문제인지 그 필드 옆에서 알려준다
      await expect(
        fieldBox(page, 'ai-max-turns').getByText('1~50 사이의 정수를 입력하세요'),
      ).toBeVisible();
      await expect(page.getByText('입력값을 확인하세요.')).toBeVisible({ timeout: 5000 });
      // 성공 토스트가 뜨지 않고, 저장 요청 자체가 나가지 않아야 한다(가짜 성공 금지)
      await expect(page.getByText('설정이 저장되었습니다.')).toHaveCount(0);
      expect(saveCapture.lastRequest()).toBeUndefined();
    });
  });

  /**
   * 계약(#712): 이메일 탭은 <b>워크스페이스 전용</b> SMTP 6키 편집 화면이다.
   *
   * 서버는 저장된 키만 내려주고(미설정이면 `[]`), 저장은 6키를 한 벌로 보내며, "설정 해제"는
   * `DELETE /settings/smtp` 한 번으로 6키(발신자 주소 포함)를 지운다. 섞일 다른 값이 없으므로
   * 예전 두 평면 어휘(배지·개별 해제·번들 안내)는 화면 어디에도 없어야 한다.
   * SMTP 고유의 경계: 비밀번호 마스크 비노출(빈 칸 = 유지), 필수 키 규칙, "연결 테스트는 저장된 값으로 돈다".
   */
  test.describe('이메일 탭 — 워크스페이스 SMTP 설정', () => {
    /** 이메일 탭 패널 — 토스트와 같은 문구를 구별하려면 스코프가 필요하다. */
    const emailPanel = (page: Page) => page.getByRole('tabpanel', { name: '이메일' });

    const UNCONFIGURED_NOTICE = 'SMTP 서버가 설정되지 않았습니다';
    // 예전 두 평면 모델의 어휘. 새 화면은 이 표현을 한 글자도 쓰지 않는다.
    const LEGACY_VOCAB = /재정의|오버라이드|상속|플랫폼 기본값|플랫폼 값/;

    /** 이메일 탭을 열고 조회가 끝날 때까지(호스트 칸이 기대값이 될 때까지) 기다린다. */
    async function openEmailTab(page: Page, expectedHost = 'smtp.gmail.com') {
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      // 조회 중에는 입력창 자체가 없다 — 빈 호스트를 기다리는 미설정 시나리오도 조회 완료 뒤에 통과한다.
      await expect(page.locator('#smtp-host')).toHaveValue(expectedHost);
    }

    /** 6키 페이로드를 꺼낸다 — `PUT /settings` 본문의 `settings` 맵. */
    const payloadOf = (req: { payload: unknown }) =>
      (req.payload as { settings: Record<string, string> }).settings;

    const PASSWORD_KEPT_HINT = '저장된 비밀번호가 있습니다. 비워 두면 유지됩니다.';
    // 저장된 비밀번호를 유지하는 저장의 페이로드 키 — `smtp.password` 가 빠진다(서버 PUT 은 받은 키만 쓴다).
    const KEYS_WITHOUT_PASSWORD = [
      'smtp.from_address',
      'smtp.host',
      'smtp.port',
      'smtp.starttls',
      'smtp.username',
    ];

    const SMTP_KEYS_SORTED = [
      'smtp.from_address',
      'smtp.host',
      'smtp.password',
      'smtp.port',
      'smtp.starttls',
      'smtp.username',
    ];

    test('미설정이면 안내와 빈 폼을 보여주고 해제 버튼·연결 테스트는 없다', { tag: '@smoke' }, async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page, { smtp: [] });
      await openEmailTab(page, '');

      await expect(emailPanel(page).getByText(UNCONFIGURED_NOTICE, { exact: false })).toBeVisible();
      // 빈 폼 — 응답에 없는 키는 빈 값, 포트·STARTTLS 는 발송 코드의 기본값과 같은 초기값이다.
      await expect(page.locator('#smtp-port')).toHaveValue('587');
      await expect(page.locator('#smtp-username')).toHaveValue('');
      await expect(page.locator('#smtp-password')).toHaveValue('');
      await expect(page.locator('#smtp-from')).toHaveValue('');
      await expect(page.locator('#smtp-starttls')).toHaveAttribute('data-state', 'checked');
      for (const id of ['smtp-host', 'smtp-port', 'smtp-username', 'smtp-password', 'smtp-from']) {
        await expect(page.locator(`#${id}`)).toBeEnabled();
      }

      // 지울 것이 없으므로 해제 버튼이 없다. 저장된 설정이 없어 연결 테스트도 막히고 이유를 말한다.
      await expect(page.getByRole('button', { name: '설정 해제' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeDisabled();
      await expect(emailPanel(page).getByText('저장된 SMTP 설정이 없어', { exact: false })).toBeVisible();
      // 아직 아무것도 바꾸지 않았다 — 빈 폼이 dirty 로 시작하면 이탈 가드가 헛경보를 낸다.
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();

      await expect(emailPanel(page).getByText(LEGACY_VOCAB)).toHaveCount(0);
      await expect(page.getByText('플랫폼 전용')).toHaveCount(0);
    });

    test('미설정에서 입력해 저장하면 6키가 한 벌로 PUT 되고 설정된 상태로 바뀐다', async ({
      authenticatedPage: page,
    }) => {
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      // 저장 전에는 미설정, 저장이 한 번이라도 나간 뒤의 재조회는 서버가 저장한 값(비밀번호는 마스크)이다.
      await setupSettingsMocks(page, {
        smtp: () =>
          saveCapture.lastRequest() === undefined
            ? []
            : createSmtpSettings({
                'smtp.host': { value: 'smtp.ourcompany.com' },
                'smtp.username': { value: 'mailer' },
                'smtp.password': { value: '****cret' },
                'smtp.from_address': { value: 'noreply@ourcompany.com' },
              }),
      });
      await openEmailTab(page, '');

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.locator('#smtp-username').fill('mailer');
      await page.locator('#smtp-password').fill('app-secret');
      await page.locator('#smtp-from').fill('noreply@ourcompany.com');
      await page.getByRole('button', { name: '저장' }).click();

      const req = await saveCapture.waitForRequest();
      const settings = payloadOf(req);
      // 손대지 않은 포트·STARTTLS 까지 6키 전부가 실린다 — 빠진 키는 저장되지 않아 발송 코드의
      // 폴백에 기대게 된다.
      expect(Object.keys(settings).sort()).toEqual(SMTP_KEYS_SORTED);
      expect(settings).toEqual({
        'smtp.host': 'smtp.ourcompany.com',
        'smtp.port': '587',
        'smtp.username': 'mailer',
        'smtp.password': 'app-secret',
        'smtp.starttls': 'true',
        'smtp.from_address': 'noreply@ourcompany.com',
      });

      await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 5000 });
      // 재조회로 설정된 상태가 된다 — 안내가 사라지고 해제 버튼이 생기며, 평문 비밀번호는 칸에서 지워지고
      // "저장됨" 안내로 바뀐다(마스크는 입력에 들어가지 않는다).
      await expect(page.getByRole('button', { name: '설정 해제' })).toBeVisible();
      await expect(emailPanel(page).getByText(UNCONFIGURED_NOTICE, { exact: false })).toHaveCount(0);
      await expect(page.locator('#smtp-password')).toHaveValue('');
      await expect(emailPanel(page).getByText(PASSWORD_KEPT_HINT)).toBeVisible();
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeEnabled();
    });

    test('설정된 상태는 저장값을 보여주되 비밀번호 마스크는 입력에 넣지 않고 옛 어휘가 없다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await openEmailTab(page);

      await expect(page.locator('#smtp-port')).toHaveValue('587');
      await expect(page.locator('#smtp-username')).toHaveValue('user@example.com');
      await expect(page.locator('#smtp-from')).toHaveValue('noreply@example.com');
      // 서버 마스크(`****3f2a`)는 편집 가능한 입력에 들어가지 않는다 — 들어가면 덧붙인 문자열이
      // 센티널 판정을 벗어나 진짜 비밀번호로 저장된다. 저장 사실은 안내문이 말한다.
      await expect(page.locator('#smtp-password')).toHaveValue('');
      await expect(emailPanel(page).getByText(PASSWORD_KEPT_HINT)).toBeVisible();
      await expect(page.locator('#smtp-starttls')).toHaveAttribute('data-state', 'checked');

      await expect(emailPanel(page).getByText(UNCONFIGURED_NOTICE, { exact: false })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '설정 해제' })).toBeVisible();
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
      await expect(page.getByRole('button', { name: '되돌리기' })).toBeDisabled();

      // 옛 두 평면 UI(배지·번들 그룹·개별 해제)가 어디에도 남지 않았다.
      await expect(emailPanel(page).getByText(LEGACY_VOCAB)).toHaveCount(0);
      await expect(emailPanel(page).locator('fieldset')).toHaveCount(0);
      await expect(page.getByText('5개 항목이 함께 적용됩니다')).toHaveCount(0);
      await expect(page.getByText('플랫폼 전용')).toHaveCount(0);
    });

    test('한 칸만 바꿔도 나머지 키를 함께 보내고, 비워 둔 비밀번호는 키째 빠진다', async ({
      authenticatedPage: page,
    }) => {
      // 저장된 비밀번호가 있는 상태에서 빈 칸은 "유지"다. 빈 문자열을 보내면 비밀번호가 지워지고,
      // 마스크를 보내면 서버의 형태 판정(길이 4/8)에 기대게 되므로, 키 자체가 없어야 한다.
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.getByRole('button', { name: '저장' }).click();

      const settings = payloadOf(await saveCapture.waitForRequest());
      expect(settings).toEqual({
        'smtp.host': 'smtp.ourcompany.com',
        'smtp.port': '587',
        'smtp.username': 'user@example.com',
        'smtp.starttls': 'true',
        'smtp.from_address': 'noreply@example.com',
      });
      expect(Object.keys(settings).sort()).toEqual(KEYS_WITHOUT_PASSWORD);
    });

    test('비밀번호를 새로 입력하면 그 값이 그대로 전송된다', async ({ authenticatedPage: page }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-password').fill('new-app-password');
      await page.getByRole('button', { name: '저장' }).click();

      const settings = payloadOf(await saveCapture.waitForRequest());
      expect(settings['smtp.password']).toBe('new-app-password');
      expect(Object.keys(settings).sort()).toEqual(SMTP_KEYS_SORTED);
    });

    test('STARTTLS 를 끄면 false 로 저장된다', async ({ authenticatedPage: page }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-starttls').click();
      await expect(page.locator('#smtp-starttls')).toHaveAttribute('data-state', 'unchecked');
      await page.getByRole('button', { name: '저장' }).click();

      expect(payloadOf(await saveCapture.waitForRequest())['smtp.starttls']).toBe('false');
    });

    test('필수 칸(호스트·발신자 주소)을 비우면 필드 오류가 붙고 PUT 이 나가지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-host').fill('');
      await page.locator('#smtp-from').fill('');
      await page.getByRole('button', { name: '저장' }).click();

      await expect(emailPanel(page).getByText('SMTP 호스트을(를) 입력하세요')).toBeVisible();
      await expect(emailPanel(page).getByText('발신자 주소을(를) 입력하세요')).toBeVisible();
      await expect(page.getByText('입력값을 확인하세요.')).toBeVisible({ timeout: 5000 });
      await expect(page.getByText('설정이 저장되었습니다.')).toHaveCount(0);
      expect(saveCapture.lastRequest()).toBeUndefined();

      // 오류는 그 칸을 고치면 사라진다.
      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await expect(emailPanel(page).getByText('SMTP 호스트을(를) 입력하세요')).toHaveCount(0);
    });

    test('포트가 범위를 벗어나면 저장이 거부된다', async ({ authenticatedPage: page }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-port').fill('70000');
      await page.getByRole('button', { name: '저장' }).click();

      await expect(emailPanel(page).getByText('1~65535 사이의 정수를 입력하세요')).toBeVisible();
      expect(saveCapture.lastRequest()).toBeUndefined();
    });

    test('비밀번호가 저장되지 않은 릴레이는 사용자 이름·비밀번호를 빈 문자열로 저장한다', async ({
      authenticatedPage: page,
    }) => {
      // 저장된 비밀번호가 없으면 빈 칸은 "유지"가 아니라 "비밀번호 없음"이다 — 키가 빈 값으로 실린다.
      await setupSettingsMocks(page, { smtp: createSmtpSettings({ 'smtp.password': { value: '' } }) });
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);
      await expect(
        emailPanel(page).getByText('설정된 비밀번호가 없습니다 (인증 없는 SMTP)'),
      ).toBeVisible();

      await page.locator('#smtp-username').fill('');
      await page.getByRole('button', { name: '저장' }).click();

      const settings = payloadOf(await saveCapture.waitForRequest());
      expect(settings['smtp.username']).toBe('');
      expect(settings['smtp.password']).toBe('');
      expect(Object.keys(settings).sort()).toEqual(SMTP_KEYS_SORTED);
    });

    test('설정 해제는 확인을 거쳐 DELETE /settings/smtp 를 한 번 부르고 미설정 화면으로 돌아간다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      const clear = await captureSmtpClear(page);
      await openEmailTab(page);

      // 해제 전에 편집을 하나 남겨 둔다 — 해제가 미저장 편집까지 함께 치우는지(dirty 해제) 확인한다.
      await page.locator('#smtp-username').fill('someone-else');

      await page.getByRole('button', { name: '설정 해제' }).click();
      const dialog = page.getByRole('alertdialog');
      await expect(dialog).toBeVisible();
      // 발신자 주소까지 6개 항목이 함께 지워진다는 사실을 누르기 전에 알린다.
      await expect(
        dialog.getByText('발신자 주소 6개 항목이 모두 삭제됩니다', { exact: false }),
      ).toBeVisible();
      await expect(dialog.getByText(LEGACY_VOCAB)).toHaveCount(0);

      // 취소하면 아무것도 지우지 않는다(음성 대조군).
      await dialog.getByRole('button', { name: '취소' }).click();
      await expect(dialog).toBeHidden();
      expect(clear.deleteCount()).toBe(0);
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');

      await page.getByRole('button', { name: '설정 해제' }).click();
      await page.getByRole('alertdialog').getByRole('button', { name: '설정 해제' }).click();

      await expect(page.getByText('SMTP 설정을 해제했습니다.')).toBeVisible({ timeout: 5000 });
      expect(clear.deleteCount()).toBe(1);
      // 미설정 화면 — 값이 비고, 안내가 뜨고, 해제 버튼이 사라지고, 편집 흔적(dirty)도 없다.
      await expect(emailPanel(page).getByText(UNCONFIGURED_NOTICE, { exact: false })).toBeVisible();
      await expect(page.locator('#smtp-host')).toHaveValue('');
      await expect(page.locator('#smtp-username')).toHaveValue('');
      await expect(page.locator('#smtp-password')).toHaveValue('');
      // 비밀번호 칸은 원래 빈 칸이라 값 단언만으로는 해제를 증명하지 못한다 — "저장됨" 안내가 사라져야 한다.
      await expect(emailPanel(page).getByText(PASSWORD_KEPT_HINT)).toHaveCount(0);
      await expect(page.locator('#smtp-from')).toHaveValue('');
      await expect(page.getByRole('button', { name: '설정 해제' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeDisabled();
    });

    test('설정 해제가 실패하면 설정된 화면을 그대로 둔다', async ({ authenticatedPage: page }) => {
      await setupSettingsMocks(page);
      const clear = await captureSmtpClear(page, { fail: true });
      await openEmailTab(page);

      await page.getByRole('button', { name: '설정 해제' }).click();
      await page.getByRole('alertdialog').getByRole('button', { name: '설정 해제' }).click();

      await expect(page.getByText('SMTP 설정 해제에 실패했습니다.')).toBeVisible({ timeout: 5000 });
      expect(clear.deleteCount()).toBe(0);
      // 서버에는 아직 설정이 있다 — 화면이 미리 "미설정"으로 뒤집히면 거짓이다.
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');
      await expect(emailPanel(page).getByText(PASSWORD_KEPT_HINT)).toBeVisible();
      await expect(page.getByRole('button', { name: '설정 해제' })).toBeVisible();
      await expect(emailPanel(page).getByText(UNCONFIGURED_NOTICE, { exact: false })).toHaveCount(0);
    });

    test('설정 조회가 실패하면 편집 가능한 빈 폼 대신 재시도 화면이 뜬다', async ({
      authenticatedPage: page,
    }) => {
      // 빈 폼을 그리면 "미설정"과 구별되지 않고, 거기서 저장하면 기존 설정(비밀번호 포함)을 덮어쓴다.
      // 호출 횟수가 아니라 플래그로 분기한다 — StrictMode 가 dev 에서 최초 마운트 GET 을 두 번 낸다.
      let failing = true;
      await setupSettingsMocks(page, {
        smtp: () => (failing ? SETTINGS_FETCH_ERROR : createSmtpSettings()),
      });

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();

      await expect(page.getByText('SMTP 설정을 불러오지 못했습니다', { exact: false })).toBeVisible();
      await expect(page.locator('#smtp-host')).toHaveCount(0);
      await expect(page.getByRole('button', { name: '저장' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '설정 해제' })).toHaveCount(0);

      failing = false;
      await page.getByRole('button', { name: '다시 시도' }).click();
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');
      await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
    });

    test('저장 후 재조회가 실패하면 저장 성공과 별개로 화면이 낡았다고 알린다', async ({
      authenticatedPage: page,
    }) => {
      let refetchFails = false;
      await setupSettingsMocks(page, {
        smtp: () => (refetchFails ? SETTINGS_FETCH_ERROR : createSmtpSettings()),
      });
      await mockApi(page, 'PUT', '/api/v1/settings', {});
      await openEmailTab(page);
      // 최초 조회가 끝난 뒤에만 재조회를 실패시킨다.
      refetchFails = true;

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.getByRole('button', { name: '저장' }).click();

      // 저장 성공은 성공대로 말한다 — "저장 실패"로 뭉뚱그리면 사용자가 저장된 값을 되돌리려 든다.
      await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
      // 화면이 낡았다는 사실은 지속 안내로 남긴다(토스트만으로는 사라진다).
      await expect(
        emailPanel(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
      ).toBeVisible({ timeout: 8000 });
      // 보낸 값은 그대로 남고 dirty 는 풀린다(저장은 실제로 됐다).
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.ourcompany.com');
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
    });

    test('편집 중에는 연결 테스트가 "마지막 저장값으로 테스트한다"고 알리되 막지는 않는다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await openEmailTab(page);
      const notice = emailPanel(page).getByText('저장 전 값이 아니라 마지막 저장값으로 테스트합니다');
      // 양성 대조군: clean 상태에서는 안내가 없다.
      await expect(notice).toHaveCount(0);

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await expect(notice).toBeVisible();
      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeEnabled();
    });

    test('연결 테스트는 계속 동작한다 (POST /settings/smtp/test)', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      const testCapture = await mockApi(
        page,
        'POST',
        '/api/v1/settings/smtp/test',
        { success: true },
        { capture: true },
      );

      await openEmailTab(page);

      const testBtn = page.getByRole('button', { name: '연결 테스트' });
      await expect(testBtn).toBeEnabled();
      await testBtn.click();

      const req = await testCapture.waitForRequest();
      expect(req.url.pathname).toBe('/api/v1/settings/smtp/test');
      await expect(page.getByText('SMTP 연결에 성공했습니다.')).toBeVisible({ timeout: 5000 });
    });

    test('연결 테스트가 success=false 를 돌려주면 서버 메시지로 실패를 알린다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await mockApi(page, 'POST', '/api/v1/settings/smtp/test', {
        success: false,
        message: 'SMTP 인증에 실패했습니다',
      });
      await openEmailTab(page);

      await page.getByRole('button', { name: '연결 테스트' }).click();
      await expect(page.getByText('SMTP 인증에 실패했습니다')).toBeVisible({ timeout: 5000 });
      await expect(page.getByText('SMTP 연결에 성공했습니다.')).toHaveCount(0);
    });
  });

  /**
   * 이슈 #86 회귀 방지 — 미저장 변경사항 이탈 가드.
   * P7-b 로 이메일 탭이 dirty 가 될 수 없게 되었으므로 진입점을 AI 탭으로 옮겼다(가드 시나리오는 유지).
   */
  test.describe('이슈 #86 — 미저장 변경 가드', () => {
    async function setupAiTabDirty(page: Page) {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');
      await expect(page.locator('#ai-max-turns')).toHaveValue('10');

      // dirty 상태 만들기 — 편집 가능한 최대 턴 수를 변경
      await page.locator('#ai-max-turns').fill('20');
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
    }

    test('dirty 상태에서 사이드바 메뉴 클릭 시 이탈 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupAiTabDirty(page);

      // 사이드바 "홈" 링크 클릭 (사이드바 nav 영역으로 한정)
      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
      // URL은 그대로 /admin/settings 유지 (즉시 이동되지 않아야 함)
      expect(new URL(page.url()).pathname).toBe('/admin/settings');
    });

    test('이탈 다이얼로그에서 취소 클릭 시 페이지에 머무르고 입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupAiTabDirty(page);

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '취소' }).click();
      await expect(page.getByRole('alertdialog')).toBeHidden();

      expect(new URL(page.url()).pathname).toBe('/admin/settings');
      await expect(page.locator('#ai-max-turns')).toHaveValue('20');
    });

    test('이탈 다이얼로그에서 이탈 클릭 시 변경값을 버리고 다른 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupAiTabDirty(page);

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '이탈' }).click();

      await expect(page).toHaveURL(/\/$/);
    });

    test('이메일 탭의 미저장 편집도 이탈 가드에 잡힌다', async ({ authenticatedPage: page }) => {
      // 이메일 탭도 dirty 가 될 수 있다 — 이슈 #86 의 원래 무대라 보고 경로가 실제로 이어져 있는지 확인한다.
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await page.locator('#smtp-host').fill('smtp.ourcompany.com');

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/admin/settings');
    });

    test('이메일 탭 입력은 다른 탭에 다녀와도 그대로 남는다', async ({
      authenticatedPage: page,
    }) => {
      // #390-2b 회귀 가드. Radix TabsContent 는 비활성 탭을 언마운트하므로, 폼 state 를
      // SmtpSettingsTab 자신이 소유하던 시절에는 탭을 바꾸는 순간 미저장 편집이 경고 없이
      // 사라졌다. 무게가 다른 이유: `smtp.password` 는 대개 메일 제공자 콘솔에서 앱 비밀번호를
      // 새로 발급받아 붙여넣은 값이라, 복구가 "다시 타이핑"이 아니라 다른 시스템을 한 번 더
      // 다녀오는 일이다.
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });

      // `prefix=smtp` GET 횟수를 센다. **이것이 `original` 재시드를 잡는 유일한 표면이다** —
      // 아래 페이로드 단언은 재시드를 검출하지 못한다(`original` 만 새 마스크로 덮여도 폼 값과는
      // 여전히 달라 같은 키·같은 값이 그대로 실린다). 나중에 등록한 핸들러가 먼저 돌고
      // `fallback()` 이 setupSettingsMocks 의 핸들러로 넘긴다.
      let smtpGetCount = 0;
      await page.route(
        (url) => url.pathname === '/api/v1/settings',
        (route) => {
          const req = route.request();
          if (req.method() === 'GET' && new URL(req.url()).searchParams.get('prefix') === 'smtp') {
            smtpGetCount += 1;
          }
          return route.fallback();
        },
      );

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.locator('#smtp-password').fill('new-app-password');

      await page.getByRole('tab', { name: 'AI 에이전트' }).click();
      await expect(page.locator('#ai-max-turns')).toHaveValue('10');
      await page.getByRole('tab', { name: '이메일' }).click();

      // 값이 살아 있다.
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.ourcompany.com');
      await expect(page.locator('#smtp-password')).toHaveValue('new-app-password');
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

      // 값이 살아 있다고 끝이 아니다 — `original` 이 왕복 중 새 마스크로 다시 시드되면 저장 대상
      // 판정(form === original → 제외)이 무너져 편집이 조용히 누락되거나 마스크가 값처럼 실린다.
      // **그 재시드를 잡는 것은 아래 페이로드 단언이 아니라 이 GET 횟수다**(위 카운터 주석 참고):
      // 훅이 최초 1회만 조회하므로 탭을 오가도 1회여야 한다.
      expect(smtpGetCount).toBe(1);

      // 페이로드는 다른 것을 지킨다 — 왕복한 편집이 6키 한 벌에 그대로 실리는지. 비밀번호는
      // 탭을 오가도 새로 입력한 평문이어야 한다(마스크로 되돌아갔다면 편집이 조용히 버려진 것이다).
      await page.getByRole('button', { name: '저장' }).click();
      const req = await saveCapture.waitForRequest();
      const settings = (req.payload as { settings: Record<string, string> }).settings;

      expect(settings).toEqual({
        'smtp.host': 'smtp.ourcompany.com',
        'smtp.port': '587',
        'smtp.username': 'user@example.com',
        'smtp.password': 'new-app-password',
        'smtp.starttls': 'true',
        'smtp.from_address': 'noreply@example.com',
      });
    });

    test('이메일 탭이 clean 상태면 탭을 옮긴 뒤 떠나도 이탈 다이얼로그가 뜨지 않는다', async ({
      authenticatedPage: page,
    }) => {
      // **이 테스트는 재작성판이다.** 예전 판은 이메일 탭에 편집을 남긴 채 탭을 옮겨 다이얼로그
      // 부재를 단언했고, 자기 주석에 "누군가 편집 유실을 고치더라도 이 단언은 계속 참이어야
      // 한다 — 빨개진다면 고치는 쪽이 아니라 이 테스트를 다시 볼 것"이라는 탈출구를 남겼다.
      // #390-2b 가 그 유실을 고쳤고, 지금 그 탈출구를 쓴다.
      //
      // 왜 예전 판이 틀렸나: 그 시나리오는 진짜 불변식("이탈 다이얼로그는 실제 미저장 변경이
      // 있을 때에만 뜬다")과 당시 결함("탭 전환이 편집을 죽인다")이 **우연히 같은 결과를 내서**
      // 둘을 구별하지 못했다. 편집이 살아남는 지금 거기서 뜨는 다이얼로그는 유령이 아니라
      // 정확한 경고다(바로 아래 테스트가 그것을 단언한다).
      //
      // 그래서 이 판은 이메일 탭을 **진짜로 clean** 하게 만든 뒤 같은 불변식만 고정한다 —
      // 변경 전후 모두 참인 단언이다.
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');

      // **양성 대조군**: 이 화면에서 dirty 가 실제로 관측 가능하다는 것을 같은 테스트 안에서
      // 증명한다. 이것이 없으면 아래 부재 단언은 "이 탭은 애초에 아무것도 못 한다"로도 통과한다.
      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

      // 되돌리기로 진짜 clean 하게 만든다. '되돌리기' 이름의 버튼은 리셋 하나뿐이다
      // (해제 확인 다이얼로그의 버튼은 '설정 해제'다).
      await page.getByRole('button', { name: '되돌리기' }).click();
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();

      await page.getByRole('tab', { name: 'AI 에이전트' }).click();
      await expect(page.locator('#ai-max-turns')).toHaveValue('10');

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      // 이탈이 실제로 일어났음을 먼저 고정한다 — 다이얼로그가 막아 세운 경우와
      // "아무 데도 안 갔는데 다이얼로그도 없다"를 구별하는 것이 이 순서다.
      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByRole('alertdialog')).toBeHidden();
    });

    test('이메일 탭의 미저장 편집은 다른 탭으로 옮긴 뒤에도 이탈 가드에 잡힌다', async ({
      authenticatedPage: page,
    }) => {
      // #390-2b 로 상태가 **실제로 살아 있다는 유일한 증거**다. 폼 state 가 탭 언마운트로
      // 사라지면(또는 dirty 보고자가 언마운트 클린업으로 false 를 쏘면) 이 다이얼로그가 뜨지
      // 않는다 — 그때 결함은 유실에서 **경고 누락**으로 모습만 바꾼 것이다.
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

      await page.getByRole('tab', { name: 'AI 에이전트' }).click();
      await expect(page.locator('#ai-max-turns')).toHaveValue('10');
      // AI 탭은 손대지 않았다 — 여기서 뜨는 다이얼로그의 원인이 이메일 탭임을 고정한다.
      // 이 단언이 없으면 AI 탭이 어쩌다 dirty 여도 테스트가 통과해 무엇을 증명하는지 흐려진다.
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/admin/settings');
    });

    test('변경 없는(clean) 상태에서는 메뉴 이동이 정상적으로 즉시 이루어진다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');
      await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();

      // dirty 상태가 아니므로 사이드바 메뉴 클릭 시 즉시 이동, 다이얼로그 없음
      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();

      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByRole('alertdialog')).toBeHidden();
    });
  });
});
