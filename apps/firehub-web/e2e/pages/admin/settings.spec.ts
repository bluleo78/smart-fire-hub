import type { Page } from '@playwright/test';

import { TENANT_EDITABLE_AI_KEYS } from '@/lib/settings-fields';

import { createAiSettings } from '../../factories/admin.factory';
import {
  setupAdminAuth,
  setupSettingsMocks,
  setupSmtpSettingsMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 설정 페이지 E2E 테스트 (P7-b 설정 2단 상속)
 *
 * 검증 대상은 "무엇이 보이는가"가 아니라 P7-b 가 세운 계약이다:
 *  - `GET /settings?prefix=ai` 의 `overridden`/`tenantEditable` 플래그가 필드 상태(상속/재정의/잠금)로
 *    정확히 번역되는가
 *  - `PUT /settings` 페이로드가 편집 허용 6키 중 바꾼 키만 담고, 잠긴 3키와 미편집 키는 담지 않는가
 *    (밴드 핵심 경계 — 미편집 키를 보내면 상속이 끊긴다)
 *  - `DELETE /settings/{key}` 가 그 필드 하나만 상속으로 되돌리고 다른 필드의 미저장 편집을 건드리지 않는가
 *  - 이메일·임베딩 탭이 전면 잠금이고 저장 경로가 화면에서 사라졌는가(진단용 연결 테스트는 유지)
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
        // 경계 단언 1-b: 페이로드의 모든 키가 화이트리스트 소속이어야 한다. 위 단언은 이 시나리오의
        // 키 하나를 고정하지만, 이쪽은 어떤 시나리오로 바뀌어도 계속 성립하는 불변식이다 —
        // 새 필드를 저장 대상에 추가하면서 화이트리스트에 넣는 것을 잊으면 여기서 걸린다.
        for (const key of Object.keys(settings)) {
          expect([...TENANT_EDITABLE_AI_KEYS]).toContain(key);
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
      let cleared = false;
      await setupSettingsMocks(page, {
        ai: () =>
          cleared
            ? createAiSettings()
            : createAiSettings({ 'ai.max_turns': { overridden: true, value: '25' } }),
      });

      // DELETE 는 204 no-content 라 본문이 없다 — mockApi 는 항상 JSON 본문을 붙이므로 직접 라우팅한다.
      const deletedPaths: string[] = [];
      await page.route(
        (url) => url.pathname.startsWith('/api/v1/settings/ai.'),
        (route) => {
          if (route.request().method() !== 'DELETE') return route.fallback();
          deletedPaths.push(new URL(route.request().url()).pathname);
          cleared = true;
          return route.fulfill({ status: 204 });
        },
      );

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
      await expect.poll(() => deletedPaths).toEqual(['/api/v1/settings/ai.max_turns']);

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
      const deletedPaths: string[] = [];
      await page.route(
        (url) => url.pathname.startsWith('/api/v1/settings/ai.'),
        (route) => {
          if (route.request().method() !== 'DELETE') return route.fallback();
          deletedPaths.push(new URL(route.request().url()).pathname);
          return route.fulfill({ status: 204 });
        },
      );

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
   * 계약 3: 이메일 탭은 전면 잠금이다. 저장 경로(버튼·PUT)는 화면에서 사라졌고,
   * 값을 노출하지 않는 진단 액션(연결 테스트)만 남는다.
   */
  test.describe('이메일 탭 — 플랫폼 전용', () => {
    test('SMTP 값이 표시되지만 6필드 전부 잠금이고 저장 경로가 없다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await setupSmtpSettingsMocks(page);

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await expect(page.getByText('SMTP 서버 설정')).toBeVisible();

      // 응답 → UI 반영(읽기는 계속 동작해야 한다)
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');
      await expect(page.locator('#smtp-port')).toHaveValue('587');
      await expect(page.locator('#smtp-username')).toHaveValue('user@example.com');
      await expect(page.locator('#smtp-from')).toHaveValue('noreply@example.com');

      // 6필드 전부 비활성(스위치 포함)
      for (const id of ['smtp-host', 'smtp-port', 'smtp-username', 'smtp-password', 'smtp-from']) {
        await expect(page.locator(`#${id}`)).toBeDisabled();
      }
      await expect(page.locator('#smtp-starttls')).toBeDisabled();

      // 저장 버튼 행이 배너로 대체되었다 — 남겨 두면 누르는 순간 403 을 받는 버튼이 된다
      await expect(page.getByText('플랫폼 전용 설정').first()).toBeVisible();
      await expect(page.getByRole('button', { name: '저장' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '되돌리기' })).toHaveCount(0);
    });

    test('연결 테스트는 계속 동작한다 (POST /settings/smtp/test)', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await setupSmtpSettingsMocks(page);
      const testCapture = await mockApi(
        page,
        'POST',
        '/api/v1/settings/smtp/test',
        { success: true },
        { capture: true },
      );

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await expect(page.getByText('SMTP 서버 설정')).toBeVisible();

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
