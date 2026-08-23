import type { Page } from '@playwright/test';

import { createAiSettings, createSmtpSettings } from '../../factories/admin.factory';
import { captureOverrideDeletes, SETTINGS_FETCH_ERROR } from '../../fixtures/admin.fixture';
import { setupAdminAuth, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 설정 페이지 E2E 테스트 (P7-b 설정 2단 상속 + P7-c1 SMTP 재분류)
 *
 * 검증 대상은 "무엇이 보이는가"가 아니라 두 밴드가 세운 계약이다:
 *  - `GET /settings?prefix=...` 의 `overridden`/`tenantEditable` 플래그가 필드 상태(상속/재정의/잠금)로
 *    정확히 번역되는가
 *  - `PUT /settings` 페이로드가 편집 허용 키 중 바꾼 키만 담고, 잠긴 키와 미편집 키는 담지 않는가
 *    (밴드 핵심 경계 — 미편집 키를 보내면 상속이 끊긴다)
 *  - `DELETE /settings/overrides/{key}` 가 그 필드 하나만 상속으로 되돌리고 다른 필드의 미저장 편집을
 *    건드리지 않는가
 *  - 이메일 탭(SMTP 6키)이 AI 탭과 같은 상속/재정의 편집 화면인가 — P7-c1 이 이 6키를 열었다.
 *    비밀번호 마스크 센티널이 저장에서 빠지는가, 빈 값 규칙이 키마다 다른가가 이 탭 고유의 경계다.
 *  - 임베딩 탭이 전면 잠금이고 저장 경로가 화면에서 사라졌는가
 *
 * AdminRoute 통과를 위해 ADMIN 역할로 users/me 를 오버라이드한다.
 */

/**
 * 필드 한 개를 감싸는 컨테이너(라벨+배지+입력+안내문).
 *
 * 잠금 안내문("플랫폼 운영자만 변경할 수 있는 항목입니다.")과 배지 문구는 여러 필드에 동일하게
 * 반복되므로, 컨테이너로 스코프를 좁히지 않으면 "어느 필드가 잠겼는지"를 전혀 검증하지 못한다.
 */
const fieldBox = (page: Page, inputId: string) =>
  page.locator('div.space-y-2', { has: page.locator(`#${inputId}`) });

/** 시스템 프롬프트는 배지·재정의 해제 버튼이 카드 제목 줄에 있어 카드 단위로 스코프를 잡는다. */
const systemPromptCard = (page: Page) =>
  page.locator('div.card-hover', { has: page.locator('#ai-system-prompt') });

const LOCKED_NOTE = '플랫폼 운영자만 변경할 수 있는 항목입니다.';

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
    await expect(page.locator('#ai-system-prompt')).toHaveValue(
      '당신은 도움이 되는 AI 어시스턴트입니다.',
    );

    // 계약: 편집 허용 6키는 **전부** 실제로 조작 가능해야 한다. 밴드의 전제가 "이 6키만 열린다"인데
    // 지금까지 max_turns·session_max_tokens 둘만 단언돼 있어 나머지 넷은 잠겨도 아무도 몰랐다.
    // (잠긴 3키의 비활성은 별도 테스트가 지킨다 — 이쪽은 그 반대 방향의 회귀를 막는다.)
    for (const id of ['ai-model', 'ai-max-tokens', 'ai-system-prompt', 'ai-temperature']) {
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
   * 계약 1·2: 응답 플래그(`overridden`/`tenantEditable`)가 필드 상태로 번역되는지.
   * 세 상태(상속/재정의/잠금)와 응답에 아예 없는 키(내장 기본값)를 모두 덮는다.
   */
  test.describe('필드 상태 — 상속 / 재정의 / 잠금', () => {
    test('상속 중인 필드는 "기본값 사용 중" 배지 + 편집 가능 + 재정의 해제 버튼 없음', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      const box = fieldBox(page, 'ai-max-turns');
      await expect(box.getByText('기본값 사용 중')).toBeVisible();
      await expect(page.locator('#ai-max-turns')).toBeEnabled();
      // 지울 오버라이드가 없으므로 해제 버튼이 붙어서는 안 된다 — 이 음성 단언이 없으면
      // 버튼이 전 필드에 붙는 회귀를 놓친다.
      await expect(box.getByRole('button', { name: '재정의 해제' })).toHaveCount(0);
      // 상속 중인 필드에 잠금 안내문이 새어 나오면 안 된다
      await expect(box.getByText(LOCKED_NOTE)).toHaveCount(0);
    });

    test('재정의된 필드는 "테넌트 재정의 적용됨" 배지 + 재정의 해제 버튼이 붙는다', async ({
      authenticatedPage: page,
    }) => {
      // 같은 키가 재정의 상태로 내려오면(overridden: true) 값도 오버라이드 값이 보여야 한다
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.max_turns': { overridden: true, value: '25' } }),
      });
      await page.goto('/admin/settings');

      const box = fieldBox(page, 'ai-max-turns');
      await expect(box.getByText('테넌트 재정의 적용됨')).toBeVisible();
      await expect(page.locator('#ai-max-turns')).toHaveValue('25');
      await expect(page.locator('#ai-max-turns')).toBeEnabled();
      await expect(box.getByRole('button', { name: '재정의 해제' })).toBeVisible();

      // 다른 편집 가능 필드는 여전히 상속 상태여야 한다(재정의가 필드 단위임을 고정)
      await expect(fieldBox(page, 'ai-temperature').getByText('기본값 사용 중')).toBeVisible();
    });

    test('플랫폼 전용 키는 Lock 배지 + 비활성 입력 + 고정 안내문 네 겹으로 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      // tenantEditable: false 로 내려오는 3키 — 색이 아니라 아이콘+텍스트+비활성+안내문으로 전달
      for (const inputId of ['ai-agent-type', 'ai-api-key', 'ai-cli-oauth-token']) {
        const box = fieldBox(page, inputId);
        const badge = box.getByLabel('플랫폼 전용: 이 테넌트에서 편집할 수 없음');
        await expect(badge).toBeVisible();
        // 색 단독 전달 금지 — 배지 안에 Lock 아이콘이 함께 있어야 한다
        await expect(badge.locator('svg')).toBeVisible();
        await expect(box.getByText(LOCKED_NOTE)).toBeVisible();
        await expect(page.locator(`#${inputId}`)).toBeDisabled();
        // 잠긴 필드에는 해제할 테넌트 재정의가 존재할 수 없다
        await expect(box.getByRole('button', { name: '재정의 해제' })).toHaveCount(0);
      }
    });

    test('화이트리스트 키라도 서버가 tenantEditable=false 로 내리면 잠금으로 렌더된다', async ({
      authenticatedPage: page,
    }) => {
      // 계약: 편집 가능 여부의 권위는 **서버 플래그**다 — 화면이 든 편집 허용 6키 상수가 아니다.
      // 백엔드는 읽을 때마다 화이트리스트를 다시 확인하므로, 플랫폼이 ai.model 을 회수하면 그
      // 즉시 tenantEditable=false 가 내려온다. 화면이 자기 사본을 우선하면 Select 가 열린 채
      // 남고 사용자는 고른 뒤 저장에서 400 을 받는다. 다른 테스트는 플래그와 상수가 같은 답을
      // 주는 응답만 써서 이 갈림을 전혀 검증하지 못한다.
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.model': { tenantEditable: false } }),
      });
      await page.goto('/admin/settings');

      const box = fieldBox(page, 'ai-model');
      const badge = box.getByLabel('플랫폼 전용: 이 테넌트에서 편집할 수 없음');
      await expect(badge).toBeVisible();
      await expect(badge.locator('svg')).toBeVisible();
      // 배지만 잠금이고 입력이 열려 있으면 "잠겼다면서 조작은 된다"가 된다 — 둘을 함께 못 박는다.
      await expect(page.locator('#ai-model')).toBeDisabled();
      // 잠긴 필드에 해제할 테넌트 재정의가 있을 수 없다
      await expect(box.getByRole('button', { name: '재정의 해제' })).toHaveCount(0);
      // 잠겨도 읽기는 계속 동작한다(값 표시까지 사라지면 회수 = 정보 소실이 된다)
      await expect(page.locator('#ai-model')).toContainText('Claude Sonnet 5');
    });

    test('응답에 없는 ai.session_max_tokens 는 "내장 기본값" 배지와 코드 기본값 50000 을 보여준다', async ({
      authenticatedPage: page,
    }) => {
      // 이 키는 어떤 마이그레이션도 시드하지 않아 프리픽스 조회 응답에서 빠진다(플랫폼 행 없음).
      // 그래도 백엔드 코드 폴백(50000)이 실제로 적용되므로 화면은 값 + "내장 기본값"을 보여야 한다.
      // 빈칸이나 잠금으로 떨어지면 결함이다(없는 키를 falsy 로 흘리면 잠김으로 뒤집힌다).
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      const box = fieldBox(page, 'ai-session-max-tokens');
      await expect(box.getByText('내장 기본값')).toBeVisible();
      await expect(page.locator('#ai-session-max-tokens')).toHaveValue('50000');
      await expect(page.locator('#ai-session-max-tokens')).toBeEnabled();
    });

    test('시스템 프롬프트 카드도 상태 배지를 카드 제목 줄에 표시한다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.system_prompt': { overridden: true, value: '테넌트 전용 프롬프트' } }),
      });
      await page.goto('/admin/settings');

      const card = systemPromptCard(page);
      await expect(card.getByText('테넌트 재정의 적용됨')).toBeVisible();
      await expect(card.getByRole('button', { name: '재정의 해제' })).toBeVisible();
      await expect(page.locator('#ai-system-prompt')).toHaveValue('테넌트 전용 프롬프트');
    });
  });

  /**
   * 계약 5(밴드 핵심 경계): 저장 페이로드는 편집 허용 6키 중 <b>실제로 바꾼 키만</b> 담는다.
   * 두 가지가 걸려 있다 — (1) 잠긴 3키(agent_type/api_key/cli_oauth_token)가 새면 테넌트가 플랫폼
   * 자산을 덮어쓰는 경로가 된다. (2) 편집하지 않은 키까지 보내면 그 키들이 tenant_settings 에
   * 기록되어 상속이 조용히 끊기고, 이후 플랫폼 기본값 변경이 이 테넌트에 전파되지 않는다 —
   * 2단 상속을 세우는 밴드에서 UI 가 상속을 없애는 셈이 된다.
   */
  test.describe('저장 페이로드 경계', () => {
    test(
      '설정 변경 후 저장하면 바꾼 키만 PUT 되고 잠긴 키·미편집 키는 담기지 않는다',
      { tag: '@smoke' },
      async ({ authenticatedPage: page }) => {
        await setupSettingsMocks(page);
        // PUT 캡처 — goto 이전에 등록해야 첫 저장을 놓치지 않는다
        const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
        // 저장 성공 후 화면이 호출하는 인증 상태 조회
        await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

        await page.goto('/admin/settings');
        await expect(page.locator('#ai-max-turns')).toHaveValue('10');

        await page.locator('#ai-max-turns').fill('15');
        const saveButton = page.getByRole('button', { name: '저장' });
        await expect(saveButton).toBeEnabled();
        await saveButton.click();

        const req = await saveCapture.waitForRequest();
        const settings = (req.payload as { settings: Record<string, string> }).settings;

        // 경계 단언 1: 페이로드는 **바꾼 키 하나만** 담는다.
        // 편집하지 않은 키까지 보내면 그 키들이 같은 값으로 tenant_settings 에 기록되어 상속이
        // 조용히 끊기고, 그 뒤로 플랫폼 기본값 변경이 이 테넌트에 영원히 전파되지 않는다.
        // 2단 상속을 세우는 밴드에서 UI 가 상속을 없애는 셈이므로 여기서 못 박는다.
        // 부분 단언(toMatchObject)으로 두면 나머지 5키가 섞여 들어와도 통과한다.
        expect(Object.keys(settings)).toEqual(['ai.max_turns']);
        // 경계 단언 1-b: 페이로드에는 서버가 tenantEditable=false 로 내린 키가 절대 없어야 한다.
        // 이 픽스처에서 잠긴 키는 3개이므로 그 셋의 부재로 불변식을 표현한다. 예전에는 web 상수
        // TENANT_EDITABLE_AI_KEYS 소속인지를 물었는데, 저장 대상 판정 자체가 그 상수로 이뤄지던
        // 시절의 단언이라 **같은 사본을 사본으로 검증**하는 동어반복이었다. 지금은 판정 권위가
        // 서버 플래그로 옮겨졌으므로 단언도 서버가 내린 사실을 기준으로 세운다.
        for (const key of Object.keys(settings)) {
          expect(['ai.agent_type', 'ai.api_key', 'ai.cli_oauth_token']).not.toContain(key);
        }
        // 경계 단언 2: 잠긴 3키는 어떤 경우에도 담기지 않는다. 위 단언이 이미 배제하지만,
        // 화이트리스트 상수가 잘못 바뀌거나 "전 키 저장"으로 되돌아가는 회귀까지 잡기 위해 명시한다.
        expect(settings).not.toHaveProperty('ai.agent_type');
        expect(settings).not.toHaveProperty('ai.api_key');
        expect(settings).not.toHaveProperty('ai.cli_oauth_token');
        // 편집 가능하지만 손대지 않은 키도 담기지 않는다 — 특히 응답에 아예 없던
        // ai.session_max_tokens 가 첫 저장에 딸려 들어가 재정의로 굳는 일이 없어야 한다.
        expect(settings).not.toHaveProperty('ai.session_max_tokens');
        expect(settings).not.toHaveProperty('ai.temperature');

        // 입력 → payload 값 검증
        expect(settings['ai.max_turns']).toBe('15');

        await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
      },
    );

    test('이미 재정의된 키라도 이번에 손대지 않았으면 페이로드에 담기지 않는다', async ({
      authenticatedPage: page,
    }) => {
      // 계약: 제외 기준은 "이번에 바꾸지 않았다" 하나뿐이다 — 이미 재정의 중인지 여부와 무관하다.
      // 전 필드가 상속 상태인 픽스처만 쓰면 "미편집 키 제외"와 "미편집이면서 아직 재정의가 없는
      // 키만 제외"를 구분할 수 없고, 후자는 저장할 때마다 기존 오버라이드를 같은 값으로 다시 써서
      // updated_at 과 감사 로그를 오염시키고 재정의 해제 직후의 상속 복귀도 되돌려 버린다.
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.temperature': { overridden: true, value: '0.7' } }),
      });
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

      await page.goto('/admin/settings');
      // 재정의가 실제로 실려 있는 상태에서 출발한다는 전제를 먼저 고정한다
      await expect(page.locator('#ai-temperature')).toHaveValue('0.7');
      await expect(fieldBox(page, 'ai-temperature').getByText('테넌트 재정의 적용됨')).toBeVisible();

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
   * 계약 4: `DELETE /settings/{key}` 는 확인 다이얼로그를 지나며, 그 필드 하나만 상속으로 되돌린다.
   */
  test.describe('재정의 해제', () => {
    test('확인 다이얼로그를 지나 DELETE 가 호출되고 해당 필드만 상속으로 돌아온다', async ({
      authenticatedPage: page,
    }) => {
      // 해제 후 화면은 GET 을 다시 읽어 배지를 갱신한다. 그래서 모킹 응답도 "해제된 뒤" 상태로
      // 바뀌어야 한다 — 아니면 배지가 그대로여서 앱이 아니라 모킹을 디버깅하게 된다.
      // 재조회 응답 분기를 별도 boolean 이 아니라 캡처 배열에서 읽는다 — 플래그와 배열이 따로
      // 놀 여지가 사라지고, "DELETE 가 나갔다"의 근거가 한 곳이 된다.
      const { deletedPaths } = await captureOverrideDeletes(page);
      await setupSettingsMocks(page, {
        ai: () =>
          deletedPaths.length > 0
            ? createAiSettings()
            : createAiSettings({ 'ai.max_turns': { overridden: true, value: '25' } }),
      });

      await page.goto('/admin/settings');
      await expect(page.locator('#ai-max-turns')).toHaveValue('25');

      // 다른 필드에 미저장 편집을 남긴다 — 해제가 이걸 날려버리면 안 된다
      await page.locator('#ai-temperature').fill('0.3');

      await fieldBox(page, 'ai-max-turns').getByRole('button', { name: '재정의 해제' }).click();
      const dialog = page.getByRole('alertdialog');
      await expect(dialog).toBeVisible();
      await expect(dialog.getByText('재정의 해제')).toBeVisible();
      await dialog.getByRole('button', { name: '되돌리기' }).click();

      // 요청 경로 검증 — 키가 URL 세그먼트로 인코딩되어 나간다
      await expect.poll(() => deletedPaths).toEqual(['/api/v1/settings/overrides/ai.max_turns']);

      // 해당 필드만 플랫폼 값(10) + 상속 배지로 전환
      await expect(page.locator('#ai-max-turns')).toHaveValue('10');
      await expect(fieldBox(page, 'ai-max-turns').getByText('기본값 사용 중')).toBeVisible();
      await expect(
        fieldBox(page, 'ai-max-turns').getByRole('button', { name: '재정의 해제' }),
      ).toHaveCount(0);
      await expect(page.getByText('플랫폼 기본값으로 되돌렸습니다.')).toBeVisible({ timeout: 5000 });

      // 핵심: 다른 필드의 미저장 편집은 그대로 남아 있어야 한다(전체 폼 재시드 회귀 방지)
      await expect(page.locator('#ai-temperature')).toHaveValue('0.3');
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
    });

    test('다이얼로그에서 취소하면 DELETE 가 호출되지 않고 재정의가 유지된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.max_turns': { overridden: true, value: '25' } }),
      });
      const { deletedPaths } = await captureOverrideDeletes(page);

      await page.goto('/admin/settings');
      await fieldBox(page, 'ai-max-turns').getByRole('button', { name: '재정의 해제' }).click();
      await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
      await expect(page.getByRole('alertdialog')).toBeHidden();

      expect(deletedPaths).toEqual([]);
      await expect(page.locator('#ai-max-turns')).toHaveValue('25');
      await expect(fieldBox(page, 'ai-max-turns').getByText('테넌트 재정의 적용됨')).toBeVisible();
    });
  });

  /**
   * 에이전트 유형에 따른 키 필드 분기.
   * P7-b 이후 유형 자체가 플랫폼 소유라 사용자가 바꿀 수 없으므로, 분기는 **서버 값이 구동**한다.
   * (예전 스펙은 사용자가 Select 를 바꿔 분기를 확인했지만 그 조작은 더 이상 존재하지 않는다.)
   */
  test.describe('에이전트 유형별 키 필드 분기 (서버 값 구동)', () => {
    test('agent_type=sdk 면 OAuth 토큰과 API 키가 모두 노출되고 둘 다 잠금이다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      // 유형 자체를 테넌트가 바꿀 수 없다 — 분기가 서버 값으로만 결정된다는 전제
      await expect(page.locator('#ai-agent-type')).toBeDisabled();
      await expect(page.locator('#ai-cli-oauth-token')).toBeVisible();
      await expect(page.locator('#ai-cli-oauth-token')).toBeDisabled();
      await expect(page.locator('#ai-api-key')).toBeVisible();
      await expect(page.locator('#ai-api-key')).toBeDisabled();
    });

    test('agent_type=cli-api 면 API 키만 노출된다', async ({ authenticatedPage: page }) => {
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.agent_type': { value: 'cli-api' } }),
      });
      await page.goto('/admin/settings');

      await expect(page.locator('#ai-api-key')).toBeVisible();
      await expect(page.locator('#ai-cli-oauth-token')).toHaveCount(0);
    });

    test('agent_type=opencode 면 키 입력 UI 없이 안내 문구만 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page, {
        ai: createAiSettings({ 'ai.agent_type': { value: 'opencode' } }),
      });
      await page.goto('/admin/settings');

      await expect(page.locator('#ai-api-key')).toHaveCount(0);
      await expect(page.locator('#ai-cli-oauth-token')).toHaveCount(0);
      await expect(
        page.getByText(
          '배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.',
        ),
      ).toBeVisible();
    });
  });

  /**
   * 계약 3(P7-c1): 이메일 탭은 AI 탭과 같은 상속/재정의 편집 화면이다.
   *
   * 이 describe 는 P7-b 시절 "6필드 전부 잠금 + 저장 경로 없음"을 단언하던 블록을 **대체**한다 —
   * 그 단언들은 지금 전부 거짓이므로 남겨 두면 밴드가 되돌려진 것처럼 보인다.
   * SMTP 고유의 경계 셋을 덮는다: 비밀번호 마스크 센티널, 키마다 다른 빈 값 규칙,
   * "연결 테스트는 저장된 값으로 돈다"는 안내.
   */
  test.describe('이메일 탭 — 상속/재정의 편집', () => {
    /**
     * 이메일 탭을 열고 첫 필드가 채워질 때까지 기다린다 — 모든 SMTP 시나리오의 공통 진입.
     *
     * 기대 호스트를 인자로 받는 이유: 번들이 재정의된 픽스처는 호스트가 플랫폼 기본값이 아니라
     * 테넌트 값이다. 기본값만 기다리게 두면 그 시나리오들이 같은 3줄을 다시 인라인하게 된다.
     */
    async function openEmailTab(page: Page, expectedHost = 'smtp.gmail.com') {
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await expect(page.locator('#smtp-host')).toHaveValue(expectedHost);
    }

    /**
     * 연결 5키를 감싸는 `fieldset`(Task 5). 그룹 배지·그룹 해제 버튼·그룹 설명문·경고 배너가 전부
     * 이 안에 있고, 배지 문구는 `발신자 주소` 와 겹치므로 스코프 없이 단언하면 어느 쪽 배지인지
     * 검증하지 못한다.
     */
    const connectionGroup = (page: Page) =>
      page.locator('fieldset', { has: page.locator('#smtp-host') });

    /**
     * 이메일 탭 패널. 낡음 안내는 **토스트에도 같은 문구**가 뜨므로, 지속 안내가 화면에 남는지
     * 단언하려면 스코프가 필요하다 — 스코프 없이 쓰면 토스트만으로도 통과해 "지속"을 증명하지 못한다
     * (실제로 strict mode 위반으로 드러났다).
     */
    const emailPanel = (page: Page) => page.getByRole('tabpanel', { name: '이메일' });

    // starttlsBox 헬퍼는 삭제했다 — STARTTLS 는 이제 그룹 안이라 개별 배지가 없고, 그 헬퍼가
    // 존재하는 유일한 이유가 "그 필드의 배지를 스코프로 잡는 것"이었다.

    test('상속 상태의 6필드가 전부 편집 가능하고 저장 경로가 존재한다', { tag: '@smoke' }, async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await openEmailTab(page);

      // 응답 → UI 반영
      await expect(page.locator('#smtp-port')).toHaveValue('587');
      await expect(page.locator('#smtp-username')).toHaveValue('user@example.com');
      await expect(page.locator('#smtp-from')).toHaveValue('noreply@example.com');
      // 비밀번호는 서버가 마스킹해서 준 값이 그대로 시드된다(평문이 아니다)
      await expect(page.locator('#smtp-password')).toHaveValue('****3f2a');

      // 계약: 6키 전부 실제로 조작 가능해야 한다 — 하나라도 잠기면 재분류가 반쪽이다
      for (const id of ['smtp-host', 'smtp-port', 'smtp-username', 'smtp-password', 'smtp-from']) {
        await expect(page.locator(`#${id}`)).toBeEnabled();
      }
      await expect(page.locator('#smtp-starttls')).toBeEnabled();

      // 연결 5키는 그룹 배지 하나가 상태를 말한다. 개별 필드 배지를 단언하던 예전 줄
      // (`fieldBox(page,'smtp-host')` / `starttlsBox(page)`)은 번들 모델에서 거짓이라 제거했다 —
      // 배지가 필드 단위면 "이 비밀번호는 플랫폼 것" 같은 거짓말을 그린다.
      await expect(connectionGroup(page).getByText('기본값 사용 중')).toBeVisible();
      await expect(page.getByText('5개 항목이 함께 적용됩니다')).toBeVisible();
      // 발신자 주소는 그룹 밖에서 개별 배지를 유지한다.
      await expect(fieldBox(page, 'smtp-from').getByText('기본값 사용 중')).toBeVisible();

      // 전면 잠금 배너·잠금 안내문은 사라졌고 저장/되돌리기 행이 돌아왔다
      await expect(page.getByText('플랫폼 전용 설정')).toHaveCount(0);
      await expect(page.getByText(LOCKED_NOTE)).toHaveCount(0);
      await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
      await expect(page.getByRole('button', { name: '되돌리기' })).toBeVisible();
      // 상속 중인 필드에는 지울 오버라이드가 없다
      await expect(page.getByRole('button', { name: /재정의 해제/ })).toHaveCount(0);
    });

    test('번들이 재정의되면 그룹 배지 하나만 바뀌고 연결 5키에 개별 배지가 없다', async ({
      authenticatedPage: page,
    }) => {
      // 서버는 호스트 한 키만 저장돼도 연결 5키 전부를 overridden=true 로 내려주고, 행이 없는
      // 키는 value='' 다(원자 해석). 픽스처도 그 응답을 그대로 재현한다.
      await setupSettingsMocks(page, {
        smtp: createSmtpSettings({}, { connectionOverridden: {
            // 서버가 실제로 내려보내는 5키를 **전부** 적는다 — 팩토리는 더 이상 채움 규칙을
            // 갖고 있지 않다. 호스트만 저장한 테넌트의 응답이 글자 그대로 이 모양이다.
            'smtp.host': 'smtp.ourcompany.com',
            'smtp.port': '',
            'smtp.username': '',
            'smtp.password': '',
            'smtp.starttls': 'true',
          } }),
      });
      await openEmailTab(page, 'smtp.ourcompany.com');

      const group = connectionGroup(page);
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.ourcompany.com');
      await expect(group.getByText('테넌트 재정의 적용됨')).toBeVisible();
      await expect(
        group.getByRole('button', { name: '연결 설정 전체 재정의 해제' }),
      ).toBeVisible();

      // **핵심**: 배지도 해제 버튼도 그룹에 하나씩뿐이다. 필드마다 반복되면 "각각 독립적으로
      // 그런 상태다"로 읽혀 원자 해석을 오해하게 만든다.
      await expect(group.getByText('테넌트 재정의 적용됨')).toHaveCount(1);
      await expect(group.getByText('기본값 사용 중')).toHaveCount(0);
      await expect(group.getByRole('button', { name: /재정의 해제/ })).toHaveCount(1);

      // 행이 없는 연결 키는 플랫폼 값이 아니라 빈 값이고, 그 사실을 노트가 말한다.
      await expect(page.locator('#smtp-port')).toHaveValue('');
      await expect(page.locator('#smtp-username')).toHaveValue('');
      // 4가 아니라 **3**이다 — `smtp.starttls` 는 번들 채움이 'true' 로 채우므로(RULING F)
      // 빈 값이 될 수 없고, 따라서 그 필드에는 노트가 붙지 않는다.
      await expect(
        group.getByText('이 항목은 비어 있습니다 — 플랫폼 값이 사용되지 않습니다.', {
          exact: false,
        }),
      ).toHaveCount(3);
      // 보안 토글은 켜진 채로 남는다. 자격증명이 비었다고 암호화까지 함께 꺼지면, 이 태스크가
      // 막으려던 "조용한 평문 전송"을 관측자만 바꿔 재생산하게 된다.
      await expect(page.locator('#smtp-starttls')).toBeChecked();
      // 포트 노트는 실제로 적용되는 값을 함께 말한다 — "비어 있다"만 있으면 "못 나간다"로 읽힌다.
      await expect(group.getByText('기본 포트 587 로 접속합니다.', { exact: false })).toBeVisible();
      // 비밀번호 안내는 "설정된 비밀번호가 없습니다"가 아니라 위 노트로 **대체**된다 —
      // 그 문구는 사용자가 의도해서 비운 것처럼 읽혀 위험을 감춘다.
      await expect(page.getByText('설정된 비밀번호가 없습니다 (인증 없는 SMTP)')).toHaveCount(0);

      // 발신자 주소는 번들 밖이라 여전히 상속 + 개별 배지다.
      await expect(fieldBox(page, 'smtp-from').getByText('기본값 사용 중')).toBeVisible();
      await expect(page.locator('#smtp-from')).toHaveValue('noreply@example.com');
    });

    test('발신자 주소만 저장하고 재조회가 실패해도 안내가 연결 그룹에 붙지 않는다', async ({
      authenticatedPage: page,
    }) => {
      // simplify2 S2/A1 이 드러낸 경로. `smtp.from_address` 는 **의도적으로** 번들 밖이므로,
      // 그 키 하나만 저장하고 재조회가 실패하는 상태가 실재한다. 안내 슬롯이 연결 fieldset 안에
      // 있으면 번들과 아무 상관 없는 경고가 "지금은 플랫폼 기본값을 그대로 쓰고 있습니다" 문단
      // 밑에 붙어, 사용자는 연결 설정이 잘못됐다고 읽는다.
      let refetchFails = false;
      await setupSettingsMocks(page, {
        smtp: () => (refetchFails ? SETTINGS_FETCH_ERROR : createSmtpSettings()),
      });
      await mockApi(page, 'PUT', '/api/v1/settings', {});
      await openEmailTab(page);
      refetchFails = true;

      await page.locator('#smtp-from').fill('ours@ourcompany.com');
      await page.getByRole('button', { name: '저장' }).click();

      await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
      await expect(
        emailPanel(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
      ).toBeVisible({ timeout: 8000 });
      // 연결 그룹은 이 실패와 무관하다 — 안내가 그 안에 있으면 안 된다.
      await expect(
        connectionGroup(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
      ).toHaveCount(0);
      // 그룹 설명문은 여전히 "플랫폼 기본값을 그대로 쓰고 있습니다" 다 — 그 옆에 경고가 붙으면
      // 두 문장이 서로를 부정하는 것처럼 읽힌다.
      await expect(
        connectionGroup(page).getByText('지금은 플랫폼 기본값을 그대로 쓰고 있습니다', {
          exact: false,
        }),
      ).toBeVisible();
    });

    test('재조회가 성공하면 낡음 안내가 사라진다', async ({ authenticatedPage: page }) => {
      // simplify2 S2. 예전에는 안내를 호출부마다 지웠고 두 곳(`fetchSettings`·`handleClearOverride`)을
      // 빠뜨려, 성공적인 단일 키 해제 + 재조회 뒤에도 "새로고침하세요" 가 살아남았다 — 조건이
      // 사라진 뒤에도 남는 안내는 이 커밋들이 없애려던 "화면이 조용히 거짓말한다"의 또 다른 판본이다.
      // 지금은 화면이 실제로 새로워지는 그 지점(`refreshMeta` 성공)에서 한 번만 지운다.
      let refetchFails = false;
      await setupSettingsMocks(page, {
        smtp: () =>
          refetchFails
            ? SETTINGS_FETCH_ERROR
            : createSmtpSettings({
                'smtp.from_address': { overridden: true, value: 'ours@ourcompany.com' },
              }),
      });
      await mockApi(page, 'PUT', '/api/v1/settings', {});
      const { deletedPaths } = await captureOverrideDeletes(page);
      await openEmailTab(page);

      // (1) 저장 + 재조회 실패로 낡음 안내를 띄운다.
      refetchFails = true;
      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.getByRole('button', { name: '저장' }).click();
      await expect(
        emailPanel(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
      ).toBeVisible({ timeout: 8000 });

      // (2) 이제 재조회가 성공하는 조작(단일 키 재정의 해제)을 한다.
      refetchFails = false;
      await fieldBox(page, 'smtp-from').getByRole('button', { name: '재정의 해제' }).click();
      await page.getByRole('alertdialog').getByRole('button', { name: '되돌리기' }).click();
      await expect.poll(() => deletedPaths.length).toBeGreaterThan(0);

      // (3) 화면이 다시 읽혔으므로 안내는 사라져야 한다.
      await expect(
        emailPanel(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
      ).toHaveCount(0);
    });

    test('상속 중에 호스트를 입력하면 저장 전 경고 배너가 뜬다', async ({
      authenticatedPage: page,
    }) => {
      // 전환 순간의 정직함(§2): 타이핑 시점에는 아무 상태도 바뀌지 않았다고 그리고, 저장이
      // 무슨 일을 하는지만 예고한다. 배지를 미리 뒤집으면 거짓이면서 반증도 안 되는 화면이 된다.
      await setupSettingsMocks(page);
      await openEmailTab(page);

      const warning = page.getByText('저장하면 연결 설정 5개 항목이 모두 우리 조직 값으로', {
        exact: false,
      });
      await expect(warning).toHaveCount(0);

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');

      await expect(warning).toBeVisible();
      // 배지는 아직 상속이다 — 저장 전에는 서버에 행이 없고 실제로 플랫폼 값으로 메일이 나간다.
      await expect(connectionGroup(page).getByText('기본값 사용 중')).toBeVisible();
      // 어떤 필드도 사전 채움되지 않는다: 플랫폼 값을 채워 두면 저장 시 플랫폼과 같은 테넌트 행이
      // 4개 기록돼 상속이 조용히 끊긴다.
      await expect(page.locator('#smtp-port')).toHaveValue('587');

      // 발신자 주소만 고치는 경우에는 뜨지 않는다 — 번들 밖이다.
      await page.locator('#smtp-host').fill('smtp.gmail.com');
      await expect(warning).toHaveCount(0);
      await page.locator('#smtp-from').fill('other@example.com');
      await expect(warning).toHaveCount(0);
    });

    test('자격증명만 채워 저장해도 STARTTLS 스위치는 켜진 채로 남는다', async ({
      authenticatedPage: page,
    }) => {
      // 밴드 리뷰 MUST-FIX 1(RULING F)의 화면 쪽 회귀 가드. 사용자가 하는 조작은 "호스트·사용자
      // 이름·비밀번호를 우리 회사 값으로 채운다"뿐이고 스위치는 손대지 않는다 — 화면에 켜짐으로
      // 보이므로 손댈 이유가 없다. 그래서 PUT 에 smtp.starttls 가 없고, 서버가 그 키를 빈 값으로
      // 채우면 방금 입력한 자격증명이 평문으로 나간다. 발송은 성공하므로 아무도 못 본다.
      let saved = false;
      await setupSettingsMocks(page, {
        smtp: () =>
          saved
            ? createSmtpSettings(
                {},
                {
                  connectionOverridden: {
                    'smtp.host': 'smtp.ourcompany.com',
                    'smtp.port': '',
                    'smtp.username': 'tenant-user@ourcompany.com',
                    // 서버는 비밀번호를 마스킹해서 준다(평문을 내려보내지 않는다).
                    'smtp.password': '****ss1!',
                    'smtp.starttls': 'true',
                  },
                },
              )
            : createSmtpSettings(),
      });
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await page.route(
        (url) => url.pathname === '/api/v1/settings',
        (route) => {
          if (route.request().method() !== 'PUT') return route.fallback();
          saved = true;
          return route.fallback();
        },
      );
      await openEmailTab(page);

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.locator('#smtp-username').fill('tenant-user@ourcompany.com');
      await page.locator('#smtp-password').fill('real-password');
      await page.getByRole('button', { name: '저장' }).click();

      // 스위치를 건드리지 않았으므로 페이로드에 없다 — 이것이 이 결함의 전제다.
      const req = await saveCapture.waitForRequest();
      const settings = (req.payload as { settings: Record<string, string> }).settings;
      expect(settings).not.toHaveProperty('smtp.starttls');

      await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
      await expect(connectionGroup(page).getByText('테넌트 재정의 적용됨')).toBeVisible();
      // 전환 후에도 암호화는 켜진 채다.
      await expect(page.locator('#smtp-starttls')).toBeChecked();
      // 자격증명이 실린 상태이므로 무인증 안내는 뜨지 않는다.
      await expect(page.getByText('인증 없이 접속을 시도합니다', { exact: false })).toHaveCount(0);
    });

    test('바꾼 SMTP 키만 PUT 되고 손대지 않은 비밀번호 마스크는 담기지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.getByRole('button', { name: '저장' }).click();

      const req = await saveCapture.waitForRequest();
      const settings = (req.payload as { settings: Record<string, string> }).settings;
      // 번들이라고 5키를 전부 보내지 않는다 — 원자성은 **해석기의 책임**이고, 화면이 빈 행을
      // 만들어 흉내 내면 플랫폼과 같은 값의 테넌트 행이 생겨 상속이 조용히 끊긴다.
      expect(Object.keys(settings)).toEqual(['smtp.host']);
      expect(settings['smtp.host']).toBe('smtp.ourcompany.com');
      // 핵심: 마스킹 값(`****3f2a`)이 비밀번호로 저장되면 살아 있는 비밀번호가 문자열
      // "****" 로 덮여 "아무것도 안 바꿨는데 메일이 안 나간다"가 된다. 백엔드에도 센티널 필터가
      // 있지만(심층 방어) 정상 경로는 **애초에 보내지 않는 것**이다.
      expect(settings).not.toHaveProperty('smtp.password');
      await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
    });

    test('저장이 성공하면 그룹 배지가 "테넌트 재정의 적용됨"으로 바뀐다', async ({
      authenticatedPage: page,
    }) => {
      // 저장 후 배지를 다시 읽지 않으면 화면은 "기본값 사용 중"이라고 계속 말한다 — 저장은 됐는데
      // 표시만 틀린, 이 밴드가 반복해서 잡아 온 "둘 중 하나만 맞는" 모양이다. 그래서 저장 성공
      // 토스트가 아니라 **배지 전환**까지 단언한다.
      let saved = false;
      await setupSettingsMocks(page, {
        smtp: () =>
          saved
            ? createSmtpSettings({}, { connectionOverridden: {
            // 서버가 실제로 내려보내는 5키를 **전부** 적는다 — 팩토리는 더 이상 채움 규칙을
            // 갖고 있지 않다. 호스트만 저장한 테넌트의 응답이 글자 그대로 이 모양이다.
            'smtp.host': 'smtp.ourcompany.com',
            'smtp.port': '',
            'smtp.username': '',
            'smtp.password': '',
            'smtp.starttls': 'true',
          } })
            : createSmtpSettings(),
      });
      await page.route(
        (url) => url.pathname === '/api/v1/settings',
        (route) => {
          if (route.request().method() !== 'PUT') return route.fallback();
          saved = true;
          return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        },
      );

      await openEmailTab(page);
      await expect(connectionGroup(page).getByText('기본값 사용 중')).toBeVisible();

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await page.getByRole('button', { name: '저장' }).click();

      await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
      await expect(connectionGroup(page).getByText('테넌트 재정의 적용됨')).toBeVisible();
      await expect(
        connectionGroup(page).getByRole('button', { name: '연결 설정 전체 재정의 해제' }),
      ).toBeVisible();
      // 저장 후에는 경고 배너가 사라진다(dirty 해소 + 상태 전환 완료).
      await expect(
        page.getByText('저장하면 연결 설정 5개 항목이 모두 우리 조직 값으로', { exact: false }),
      ).toHaveCount(0);

      // **배지만이 아니라 값도** 서버 해석으로 다시 시드돼야 한다(밴드 리뷰 MUST-FIX 2).
      // 재시드가 없으면 입력창은 옛 플랫폼 값(포트 587·플랫폼 사용자 이름·플랫폼 마스크)을 계속
      // 보여주는데 그 아래 노트는 "이 항목은 비어 있습니다"라고 말한다 — 이 태스크가 배지에서
      // 제거한 거짓말이 필드 값 자체로 옮겨온 것이다.
      await expect(page.locator('#smtp-port')).toHaveValue('');
      await expect(page.locator('#smtp-username')).toHaveValue('');
      await expect(page.locator('#smtp-password')).toHaveValue('');
      // starttls 는 채움 값이 'true' 라 켜진 채로 재시드된다(RULING F).
      await expect(page.locator('#smtp-starttls')).toBeChecked();
      // 재시드가 dirty 를 만들면 안 된다 — form 과 original 을 같은 값으로 맞춰야 한다.
      await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
      // 이제 무인증 안내와 화면이 일치한다: 비어 있다고 말하는 칸이 실제로 비어 있다.
      await expect(page.getByText('인증 없이 접속을 시도합니다', { exact: false })).toBeVisible();
    });

    test('비밀번호를 새로 입력하면 그 값이 그대로 전송된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-password').fill('new-secret');
      await page.getByRole('button', { name: '저장' }).click();

      const req = await saveCapture.waitForRequest();
      const settings = (req.payload as { settings: Record<string, string> }).settings;
      expect(Object.keys(settings)).toEqual(['smtp.password']);
      expect(settings['smtp.password']).toBe('new-secret');
    });

    test('smtp.host 를 비우고 저장하면 거부되고 PUT 이 나가지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      let putCount = 0;
      await page.route(
        (url) => url.pathname === '/api/v1/settings',
        (route) => {
          if (route.request().method() !== 'PUT') return route.fallback();
          putCount += 1;
          return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        },
      );
      await openEmailTab(page);

      // 전제: 지금 화면에 "재정의 해제" 류 버튼이 하나도 없다 — 안내가 가리킬 대상이 없다는 뜻이고,
      // 아래 문구 단언은 그 사실 위에서만 의미를 갖는다.
      await expect(page.getByRole('button', { name: /재정의 해제/ })).toHaveCount(0);

      await page.locator('#smtp-host').fill('');
      await page.getByRole('button', { name: '저장' }).click();

      // 그냥 빼고 저장하면 "저장했다"면서 아무것도 안 쓰고 dirty 까지 지운다 — 그래서 거부다.
      // 안내가 가리키는 탈출구는 **상속 중**이라 "되돌리기"다(코드리뷰 Minor 4). 예전에는 이
      // 자리에서도 "재정의 해제"를 안내했는데, 상속 중에는 지울 오버라이드도 그 버튼도 없다.
      await expect(
        page.getByText('입력을 취소하려면 "되돌리기"를 사용하세요', { exact: false }),
      ).toBeVisible({ timeout: 5000 });
      await expect(page.getByText('"재정의 해제"를 사용하세요', { exact: false })).toHaveCount(0);
      expect(putCount).toBe(0);
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
    });

    test('smtp.username 은 비워도 빈 문자열로 저장된다 — 인증 없는 릴레이', async ({
      authenticatedPage: page,
    }) => {
      // 빈 값 규칙은 키 단위 화이트리스트다. username/password 는 "비어 있음"이 합법적 최종
      // 상태이므로 host 와 반대로 동작해야 한다 — 뭉뚱그리면 둘 중 하나가 반드시 틀린다.
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await openEmailTab(page);

      await page.locator('#smtp-username').fill('');
      await page.getByRole('button', { name: '저장' }).click();

      const req = await saveCapture.waitForRequest();
      const settings = (req.payload as { settings: Record<string, string> }).settings;
      expect(Object.keys(settings)).toEqual(['smtp.username']);
      expect(settings['smtp.username']).toBe('');
    });

    test('그룹 해제 버튼은 연결 5키에 대해 DELETE 를 발행하고 전부 상속으로 돌아온다', async ({
      authenticatedPage: page,
    }) => {
      // 필드별 해제를 단언하던 예전 테스트를 **대체**한다. 번들 삭제 엔드포인트가 없으므로 화면이
      // 5번의 DELETE 를 순차 발행하는 것이 계약이고, 어느 키에 실제 행이 있는지 화면은 알 수 없다
      // (서버가 5키 전부 overridden=true 로 내려준다) — 그래서 조건 없이 5번 지운다.
      const { deletedPaths } = await captureOverrideDeletes(page);
      await setupSettingsMocks(page, {
        smtp: () =>
          deletedPaths.length > 0
            ? createSmtpSettings()
            : createSmtpSettings({}, { connectionOverridden: {
            // 서버가 실제로 내려보내는 5키를 **전부** 적는다 — 팩토리는 더 이상 채움 규칙을
            // 갖고 있지 않다. 호스트만 저장한 테넌트의 응답이 글자 그대로 이 모양이다.
            'smtp.host': 'smtp.ourcompany.com',
            'smtp.port': '',
            'smtp.username': '',
            'smtp.password': '',
            'smtp.starttls': 'true',
          } }),
      });

      await openEmailTab(page, 'smtp.ourcompany.com');

      // 그룹 밖의 미저장 편집은 번들 해제에 휩쓸리면 안 된다.
      await page.locator('#smtp-from').fill('kept@example.com');

      await connectionGroup(page)
        .getByRole('button', { name: '연결 설정 전체 재정의 해제' })
        .click();
      // 확인 문구가 5개 항목을 **이름으로 나열**한다 — "이 그룹"이라고 쓰면 사용자가 경계를
      // 스크롤 밖에서 추정해야 한다.
      const dialog = page.getByRole('alertdialog');
      await expect(dialog.getByText('SMTP 호스트, 포트, 사용자 이름, 비밀번호, STARTTLS', { exact: false })).toBeVisible();
      await expect(dialog.getByText('복구할 수 없으며', { exact: false })).toBeVisible();
      await dialog.getByRole('button', { name: '되돌리기' }).click();

      await expect
        .poll(() => [...deletedPaths].sort())
        .toEqual([
          '/api/v1/settings/overrides/smtp.host',
          '/api/v1/settings/overrides/smtp.password',
          '/api/v1/settings/overrides/smtp.port',
          '/api/v1/settings/overrides/smtp.starttls',
          '/api/v1/settings/overrides/smtp.username',
        ]);
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');
      await expect(page.locator('#smtp-port')).toHaveValue('587');
      await expect(connectionGroup(page).getByText('기본값 사용 중')).toBeVisible();
      await expect(page.locator('#smtp-from')).toHaveValue('kept@example.com');
    });

    test('그룹 해제 중 하나가 실패하면 부분 실패 문구가 화면에 남는다', async ({
      authenticatedPage: page,
    }) => {
      // 이 중간 상태는 원자 해석 아래에서 **안전하지만**(행이 하나라도 남으면 5키가 전부 테넌트
      // 평면에서 해석된다) 사용자가 보기엔 "해제했는데 아직 재정의 배지"다. 토스트 한 번으로
      // 뭉개면 스크린리더 사용자가 놓치고, 다시 조작해야 하는 상태라는 사실이 사라진다.
      await setupSettingsMocks(page, {
        // 재조회에서도 여전히 번들 재정의 상태다 — 일부만 지워졌으므로.
        smtp: () => createSmtpSettings({}, { connectionOverridden: {
            // 서버가 실제로 내려보내는 5키를 **전부** 적는다 — 팩토리는 더 이상 채움 규칙을
            // 갖고 있지 않다. 호스트만 저장한 테넌트의 응답이 글자 그대로 이 모양이다.
            'smtp.host': 'smtp.ourcompany.com',
            'smtp.port': '',
            'smtp.username': '',
            'smtp.password': '',
            'smtp.starttls': 'true',
          } }),
      });
      await captureOverrideDeletes(page, { failOn: 'smtp.password' });

      await openEmailTab(page, 'smtp.ourcompany.com');

      await connectionGroup(page)
        .getByRole('button', { name: '연결 설정 전체 재정의 해제' })
        .click();
      await page.getByRole('alertdialog').getByRole('button', { name: '되돌리기' }).click();

      // 토스트가 아니라 **화면에 남는 텍스트**로도 있어야 한다. 슬롯은 연결 그룹 안이 아니라
      // 탭 범위다 — 이 안내가 붙는 사건이 그룹 전용이 아니기 때문이다(아래 저장 후 재조회 실패
      // 테스트가 그 이유를 직접 보여준다).
      await expect(
        emailPanel(page).getByText('일부 항목만 해제되었습니다', { exact: false }),
      ).toBeVisible({ timeout: 8000 });
      await expect(
        connectionGroup(page).getByText('일부 항목만 해제되었습니다', { exact: false }),
      ).toHaveCount(0);
      // 안전하다는 사실도 함께 말한다 — 남은 항목은 아직 테넌트 값으로 적용된다.
      await expect(connectionGroup(page).getByText('테넌트 재정의 적용됨')).toBeVisible();
    });

    test('발신자 주소는 그룹 밖에서 개별 배지·개별 해제 버튼을 유지한다', async ({
      authenticatedPage: page,
    }) => {
      // §4: 구분은 **구조**가 한다. 안쪽(테두리 있는 그룹, 배지 0개) vs 바깥(배지 1개 + 개별 해제).
      const { deletedPaths } = await captureOverrideDeletes(page);
      await setupSettingsMocks(page, {
        smtp: () =>
          deletedPaths.length > 0
            ? createSmtpSettings()
            : createSmtpSettings({
                'smtp.from_address': { overridden: true, value: 'ours@ourcompany.com' },
              }),
      });

      await openEmailTab(page);
      await expect(page.locator('#smtp-from')).toHaveValue('ours@ourcompany.com');

      const box = fieldBox(page, 'smtp-from');
      await expect(box.getByText('테넌트 재정의 적용됨')).toBeVisible();
      // 연결 5키는 상속 그대로다 — from_address 는 번들이 아니므로 5키를 끌고 들어가지 않는다.
      await expect(connectionGroup(page).getByText('기본값 사용 중')).toBeVisible();
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');

      await box.getByRole('button', { name: '재정의 해제' }).click();
      await page.getByRole('alertdialog').getByRole('button', { name: '되돌리기' }).click();

      // 딱 한 키만 지운다 — 개별 해제가 번들 해제로 번지면 안 된다.
      await expect.poll(() => deletedPaths).toEqual(['/api/v1/settings/overrides/smtp.from_address']);
      await expect(page.locator('#smtp-from')).toHaveValue('noreply@example.com');
    });

    test('편집 중에는 연결 테스트가 "마지막 저장값으로 테스트한다"고 알리되 막지는 않는다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await openEmailTab(page);

      const notice = page.getByText('저장 전 값이 아니라 마지막 저장값으로 테스트합니다');
      await expect(notice).toHaveCount(0);

      await page.locator('#smtp-host').fill('smtp.ourcompany.com');
      await expect(notice).toBeVisible();
      // 버튼은 막지 않는다 — 지금 적용 중인 값을 확인하려는 것도 유효한 용도다
      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeEnabled();
    });

    test('자격증명이 빈 번들 상태에서는 연결 테스트가 무인증 시도임을 알린다', async ({
      authenticatedPage: page,
    }) => {
      // §5 우선순위: 빈 자격증명 안내가 dirty 안내보다 먼저다. 두 문구가 나란히 뜨면 어느 쪽이
      // 지금 문제인지 흐려진다. 버튼은 여전히 막지 않는다 — 무인증 릴레이는 합법적 최종 상태라
      // 그 구성에서 테스트를 못 하게 막으면 정당한 설정을 검증할 길이 사라진다.
      // **포트를 일부러 비운다.** 포트에 값을 주면 아래 `not.toContainText('포트')` 가 "포트가
      // 애초에 안 비어서" 통과해 버려, 나열 대상을 자격증명 2키로 좁힌 것을 아무것도 고정하지
      // 못한다(공허한 부정 단언 — 이 밴드가 반복해서 기록해 온 실패 방식이다).
      await setupSettingsMocks(page, {
        smtp: createSmtpSettings({}, { connectionOverridden: {
            // 서버가 실제로 내려보내는 5키를 **전부** 적는다 — 팩토리는 더 이상 채움 규칙을
            // 갖고 있지 않다. 호스트만 저장한 테넌트의 응답이 글자 그대로 이 모양이다.
            'smtp.host': 'smtp.ourcompany.com',
            'smtp.port': '',
            'smtp.username': '',
            'smtp.password': '',
            'smtp.starttls': 'true',
          } }),
      });
      await openEmailTab(page, 'smtp.ourcompany.com');

      // 비어 있는 **자격증명** 키를 이름으로 나열한다 — 그래야 "무엇을 채우면 되는가"가 화면에
      // 있다. 연결 5키 전부를 나열하면 거짓이 된다: 빈 포트는 인증과 무관하게 587 로 대체되고
      // (그 사실은 포트 필드의 노트가 따로 말한다), STARTTLS 는 빈 값이 될 수 없다.
      const notice = page.getByText('인증 없이 접속을 시도합니다', { exact: false });
      await expect(notice).toBeVisible();
      await expect(notice).toContainText('사용자 이름·비밀번호');
      // 포트는 지금 **비어 있는데도** 나열되지 않아야 한다 — 빈 포트는 인증과 무관하게 587 로
      // 대체되므로, 나열하면 "인증 없이 접속한다"는 문장이 포트 탓이라고 거짓을 말한다.
      // 그 사실은 포트 필드의 노트가 따로 전달한다.
      await expect(page.locator('#smtp-port')).toHaveValue('');
      await expect(notice).not.toContainText('포트');
      await expect(
        connectionGroup(page).getByText('기본 포트 587 로 접속합니다.', { exact: false }),
      ).toBeVisible();
      // `SMTP 호스트` 에 대한 부정 단언은 두지 않는다 — 이 시나리오에서 호스트는 채워져 있어
      // 어떤 구현에서도 나열될 수 없다(공허하다). 빈 호스트는 인증이 아니라 발송 자체가
      // 실패하는 다른 상태이고, 이 안내가 덮는 범위가 아니다.
      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeEnabled();

      // dirty 가 되어도 이 안내가 유지되고 dirty 안내가 나란히 뜨지 않는다(배타적 슬롯).
      await page.locator('#smtp-host').fill('smtp.other.com');
      await expect(notice).toBeVisible();
      await expect(page.getByText('저장 전 값이 아니라 마지막 저장값으로 테스트합니다')).toHaveCount(0);
    });

    test('호스트가 빈 번들 상태에서는 접속을 시도하지 않는다고 알린다', async ({
      authenticatedPage: page,
    }) => {
      // #390 item 4. 도달 경로는 평범하다: 테넌트가 **포트만** 재정의하면("우리는 465 를 쓴다")
      // 번들이 나머지 연결 키를 빈 값으로 채워 호스트가 빈다. 이때 자격증명 안내를 띄우면
      // 실패 원인을 잘못 지목한다 — 서버는 호스트가 비면 접속을 시도조차 하지 않고
      // "SMTP 호스트가 설정되지 않았습니다" 로 돌아온다.
      await setupSettingsMocks(page, {
        smtp: createSmtpSettings(
          {},
          {
            connectionOverridden: {
              'smtp.host': '',
              'smtp.port': '465',
              'smtp.username': '',
              'smtp.password': '',
              'smtp.starttls': 'true',
            },
          },
        ),
      });
      await openEmailTab(page, '');

      const hostNotice = page.getByText('접속을 시도하지 않습니다', { exact: false });
      await expect(hostNotice).toBeVisible();
      await expect(hostNotice).toContainText('SMTP 호스트가 비어 있어');

      // **배타적 슬롯**: 자격증명도 함께 비어 있지만 그 안내는 뜨지 않는다. 둘 다 뜨면 어느 쪽이
      // 지금 문제인지 흐려지고, 사용자는 있지도 않은 인증 문제를 고치려 든다.
      await expect(page.getByText('인증 없이 접속을 시도합니다', { exact: false })).toHaveCount(0);
      // 버튼은 여전히 막지 않는다 — 지금 적용 중인 값을 확인하려는 것도 유효한 용도다.
      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeEnabled();

      // dirty 가 되어도 우선순위가 유지된다(dirty 안내가 끼어들지 않는다).
      await page.locator('#smtp-port').fill('587');
      await expect(hostNotice).toBeVisible();
      await expect(page.getByText('저장 전 값이 아니라 마지막 저장값으로 테스트합니다')).toHaveCount(0);
    });

    test('설정 조회가 실패하면 편집 가능한 빈 폼 대신 재시도 화면이 뜬다', async ({
      authenticatedPage: page,
    }) => {
      // 코드리뷰 Major 2. 토스트만 띄우고 폼을 그리면 5필드가 **편집 가능한 빈 칸**이 되어
      // "아직 아무것도 설정되지 않았다"와 구별되지 않는다. 거기서 호스트만 입력해 저장하면
      // 그 테넌트의 사용자 이름·비밀번호·포트가 전부 빈 값으로 해석된다 — 조회 실패가 파괴적
      // 저장을 부르는 경로다.
      // **호출 횟수가 아니라 플래그로 분기한다.** React StrictMode 가 dev 에서 effect 를 두 번
      // 실행하므로 최초 마운트만으로 GET 이 2회 나간다 — "첫 번째만 실패" 로 짜면 두 번째가
      // 성공해 화면이 정상 폼으로 복구되고, 검증하려던 상태에 도달하지 못한다(실제로 겪었다).
      let failing = true;
      await setupSettingsMocks(page, {
        smtp: () => (failing ? SETTINGS_FETCH_ERROR : createSmtpSettings()),
      });

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();

      // 폼이 아예 없다 — 입력창도 저장 버튼도 그리지 않는다.
      await expect(page.getByText('SMTP 설정을 불러오지 못했습니다', { exact: false })).toBeVisible();
      await expect(page.locator('#smtp-host')).toHaveCount(0);
      await expect(page.getByRole('button', { name: '저장' })).toHaveCount(0);

      // 재시도가 있고, 성공하면 정상 폼으로 돌아온다.
      failing = false;
      await page.getByRole('button', { name: '다시 시도' }).click();
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');
      await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
    });

    test('저장 후 재조회가 실패하면 저장 성공과 별개로 화면이 낡았다고 알린다', async ({
      authenticatedPage: page,
    }) => {
      // 코드리뷰 Major 1. 저장은 성공했고 **다시 그리기**가 실패했다. 삼키면 배지는
      // `기본값 사용 중`, 그룹 문구는 "플랫폼 기본값을 쓰고 있습니다", 폼은 플랫폼 사용자 이름을
      // 계속 보여주는데 서버는 이미 그 테넌트를 빈 자격증명 번들로 옮긴 상태다.
      // 호출 횟수가 아니라 플래그로 분기한다 — StrictMode 가 최초 마운트에서 GET 을 두 번 내므로
      // "두 번째부터 실패" 로 짜면 저장 전에 이미 실패해 다른 상태를 시험하게 된다.
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
      // 그리고 화면이 낡았다는 사실을 **지속 안내**로 남긴다(토스트만으로는 사라진다).
      await expect(
        emailPanel(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
      ).toBeVisible({ timeout: 8000 });
      await expect(emailPanel(page).getByText('새로고침하세요', { exact: false })).toBeVisible();
      // **자리를 고정한다.** 이 안내는 연결 그룹 사건이 아니므로 그 fieldset 안에 있으면 안 된다 —
      // 아래 from_address 테스트가 그 자리 오류의 실제 결과를 보여준다.
      await expect(
        connectionGroup(page).getByText('화면을 다시 읽지 못했습니다', { exact: false }),
      ).toHaveCount(0);
    });

    test('재정의된 필드를 비우면 화면에 실제로 있는 "재정의 해제"를 안내한다', async ({
      authenticatedPage: page,
    }) => {
      // 코드리뷰 Minor 4 의 **반대 arm**. 상속 중 arm 은 위 `smtp.host 를 비우고 저장하면…` 이
      // 덮으므로, 이 테스트는 재정의 중 arm 하나만 맡는다 — 두 테스트가 같은 arm 을 겹쳐 지키고
      // 다른 arm 이 비어 있던 상태를 없앤다.
      //
      // `smtp.from_address` 는 번들 밖이라 **개별** 재정의 해제 버튼을 갖는다. 즉 이 상태에서는
      // 안내가 가리키는 컨트롤이 실제로 화면에 있고, 그래서 "재정의 해제" 문구가 참이다.
      await setupSettingsMocks(page, {
        smtp: createSmtpSettings({
          'smtp.from_address': { overridden: true, value: 'ours@ourcompany.com' },
        }),
      });
      await openEmailTab(page);

      // 전제: 그 필드에 해제 버튼이 실재한다 — 이 단언이 없으면 문구가 참인지 알 수 없다.
      await expect(
        fieldBox(page, 'smtp-from').getByRole('button', { name: '재정의 해제' }),
      ).toBeVisible();

      // from_address 는 빈 값 허용 화이트리스트에 없으므로 비우면 거부된다.
      await page.locator('#smtp-from').fill('');
      await page.getByRole('button', { name: '저장' }).click();

      await expect(
        page.getByText('플랫폼 기본값으로 되돌리려면 "재정의 해제"를 사용하세요', { exact: false }),
      ).toBeVisible({ timeout: 5000 });
      await expect(page.getByText('"되돌리기"를 사용하세요', { exact: false })).toHaveCount(0);
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
      // P7-c1 로 이메일 탭이 다시 dirty 가 될 수 있게 됐다 — 이슈 #86 의 원래 무대가 돌아온 셈이라
      // 보고 경로가 실제로 이어져 있는지 확인한다.
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await page.locator('#smtp-host').fill('smtp.ourcompany.com');

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/admin/settings');
    });

    test('이메일 탭을 떠난 뒤에는 유령 이탈 다이얼로그가 뜨지 않는다', async ({
      authenticatedPage: page,
    }) => {
      // Radix TabsContent 는 비활성 탭을 언마운트한다 — 폼 state 를 소유한 SMTP 탭이 사라지면서
      // 편집 내용도 함께 사라지는데, 합산기에 남은 dirty=true 를 지우지 않으면 **존재하지 않는
      // 변경** 때문에 이탈 다이얼로그가 뜬다. 이 테스트가 고정하는 것은 그 유령 다이얼로그의
      // 부재뿐이다.
      //
      // **편집 유실 자체는 이 테스트가 옳다고 말하는 것이 아니다.** 탭을 바꾸면 SMTP 미저장
      // 편집이 경고 없이 사라지는데(AI 탭은 폼 state 를 SettingsPage 가 소유해 살아남는다 —
      // 비대칭이다), 그 손실은 P7-c1 이 이메일 탭을 편집 가능하게 열면서 처음 생긴 경로이고
      // 후속 과제다. 누군가 그 손실을 고치더라도 이 단언(유령 다이얼로그 부재)은 계속 참이어야
      // 한다 — 빨개진다면 고치는 쪽이 아니라 이 테스트를 다시 볼 것.
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await page.locator('#smtp-host').fill('smtp.ourcompany.com');

      await page.getByRole('tab', { name: 'AI 에이전트' }).click();
      await expect(page.locator('#ai-max-turns')).toHaveValue('10');

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByRole('alertdialog')).toBeHidden();
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
