// SEEDED_18 은 e2e/factories/platform.factory.ts 로 옮겼다(리뷰 L2) — 스펙 파일에서 export 하면
// 다른 스펙이 import 하는 순간 Playwright 가 이 파일도 테스트 파일로 등록해 settings.spec.ts 의
// 테스트가 함께 중복 실행된다.
import type { Page } from '@playwright/test';

import { createAiCredential, SEEDED_18 } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

/**
 * `GET /api/platform/settings`(범용 18키)와 `GET /api/platform/settings/ai-credential`(타입형
 * AI 자격증명, Task 13) 을 함께 모킹한다. `AiCredentialSection` 이 AI 탭에서 후자를 독립적으로
 * 호출하므로, 이 GET 을 모킹하지 않으면 모든 기존 테스트가 실제 백엔드가 없는 e2e 환경에서
 * 네트워크 오류(`loadFailed`)를 맞고 "자격증명 정보를 불러오지 못했습니다"를 띄운다 — 그 잡음이
 * 이 파일의 다른 단언(예: getByLabel('API 키') 단일 매치 기대)과 충돌한다.
 */
async function mockPlatformSettings(
  page: Page,
  settings: typeof SEEDED_18 = SEEDED_18,
  credential = createAiCredential(),
) {
  await mockApi(page, 'GET', '/api/platform/settings', settings);
  await mockApi(page, 'GET', '/api/platform/settings/ai-credential', credential);
}

test.describe('플랫폼 설정 — 카탈로그와 상태', () => {
  test('탭 3개와 안내 배너가 나온다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await expect(page.getByRole('heading', { name: '플랫폼 설정' })).toBeVisible();
    await expect(
      page.getByText(
        '여기서 저장한 값은 전 테넌트의 기본값입니다. 일부 항목은 각 워크스페이스가 자기 값으로 재정의할 수 있습니다.',
      ),
    ).toBeVisible();
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '이메일(SMTP)' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '임베딩' })).toBeVisible();
    await expect(page.getByRole('tab')).toHaveCount(3);
  });

  test('배지 두 종류가 항상 붙는다 — AI 탭은 없다, 나머지 10필드 전부(개수로 단언, 리뷰 L1 + Task 13)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    // .first() 두 번은 "존재한다"만 증명하고 "전부에 붙는다"는 증명하지 못한다 — 어느 필드가
    // 배지를 빼먹어도 초록이다. 탭마다 (테넌트 재정의 가능 배지 수 + 전역 고정 배지 수)가
    // 그 탭의 필드 수와 정확히 같은지 개수로 단언한다.
    //
    // AI 탭은 **0** 이다(타입형 AI 설정 전환, Task 13, 뮤테이션 검사 #2) — AiCredentialSection
    // 이 이 탭을 전용 화면으로 바꾸면서 배지·안내문을 아예 렌더하지 않는다(설계서 §225,
    // 브리프 Step 3). 나머지 5키(ai.max_turns 등 — ai.model 은 Ruling #48 로 이 범용 카탈로그를
    // 떠나 AiCredentialSection 이 가져갔다)도 hideOverrideBadge 로 배지를 숨긴다
    // (SettingField.tsx). SMTP 6 / 임베딩 4 = 10.
    const tabFieldCounts: Record<string, number> = { 'AI 에이전트': 0, '이메일(SMTP)': 6, '임베딩': 4 };
    let totalBadges = 0;
    for (const [tabName, fieldCount] of Object.entries(tabFieldCounts)) {
      await page.getByRole('tab', { name: tabName }).click();
      const overridable = await page.getByText('테넌트 재정의 가능').count();
      const fixed = await page.getByText('전역 고정').count();
      expect(overridable + fixed).toBe(fieldCount);
      totalBadges += overridable + fixed;
    }
    expect(totalBadges).toBe(10);

    // 안내 문구도 여전히 확인한다 — 두 종류가 각기 다른 문구를 낸다는 대조군.
    //
    // AI 탭이 사라지기 전에는 이 두 문구를 **AI 탭 하나**에서 함께 확인할 수 있었다(그 탭에
    // 재정의 가능 키와 전역 고정 키가 섞여 있었으므로). 지금은 AI 탭에 배지가 아예 없고
    // (뮤테이션 검사 #2 의 연장), 남은 두 탭은 각각 한쪽으로 쏠려 있다 — SMTP 6키는 전부
    // `TENANT_OVERRIDABLE_KEYS`(override-policy.ts)에 있어 전부 재정의 가능, 임베딩 4키는
    // 전부 그 목록 밖이라 전부 전역 고정이다. 그래서 두 문구를 각자의 탭에서 확인한다.
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();
    await expect(
      page
        .getByText(
          '워크스페이스가 자기 값으로 재정의할 수 있습니다. 재정의하지 않은 워크스페이스에만 이 값이 적용됩니다.',
        )
        .first(),
    ).toBeVisible();
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('모든 워크스페이스에 이 값이 적용됩니다.').first()).toBeVisible();
  });

  test('AI 탭에는 재정의 배지·안내문이 없다(뮤테이션 검사 #2)', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    // `toHaveCount(0)` 는 자동 대기하지만 "이미 0" 이면 그 순간 통과한다 — 콘텐츠가 아직
    // 로딩 스켈레톤(배지도 없고 필드도 없는 상태)인 순간에 평가되면, 뮤턴트(배지를 다시
    // 렌더하게 되돌림)를 넣어도 초록으로 잘못 통과한다(실측: 이 대기가 없으면 이 테스트가
    // 뮤테이션 검사 #2 를 실제로 잡지 못한다). 먼저 실제 콘텐츠(모델 select)가 뜨는 것을
    // 기다린 뒤에 부재를 단언한다.
    await expect(page.getByLabel('모델')).toBeVisible();

    await expect(page.getByText('테넌트 재정의 가능')).toHaveCount(0);
    await expect(page.getByText('전역 고정')).toHaveCount(0);
    // 필드별 `OverrideNote` 전체 문장으로 매칭한다(부분 문자열이 아니라) — 페이지 상단
    // 고정 안내 배너("여기서 저장한 값은 전 테넌트의 기본값입니다. 일부 항목은 각
    // 워크스페이스가 자기 값으로 재정의할 수 있습니다.")가 `OverrideNote` 문장의 앞
    // 절과 글자 그대로 겹쳐, 짧게 자르면 그 배너를 오탐한다(실측).
    await expect(
      page.getByText(
        '워크스페이스가 자기 값으로 재정의할 수 있습니다. 재정의하지 않은 워크스페이스에만 이 값이 적용됩니다.',
      ),
    ).toHaveCount(0);
    await expect(page.getByText('모든 워크스페이스에 이 값이 적용됩니다.')).toHaveCount(0);
  });

  test('시드되지 않은 키(ai.session_max_tokens)는 내장 기본값 배지와 값 50000 으로 그린다', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await expect(page.getByLabel('세션 최대 토큰')).toHaveValue('50000');
    await expect(page.getByText('내장 기본값')).toBeVisible();
    await expect(
      page.getByText(
        '아직 저장된 값이 없어 코드 기본값이 적용되고 있습니다. 저장하면 이 값이 플랫폼 기본값이 됩니다.',
      ),
    ).toBeVisible();
  });

  test('필드 설명은 서버 description 을 그대로 쓴다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await expect(page.getByText('AI 에이전트 사용 모델')).toBeVisible();
  });

  test('STARTTLS 는 Switch 로 그리고 값이 true 면 켜져 있다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();
    await expect(page.getByRole('switch')).toBeChecked();
  });

  test('쓰기 권한이 없으면 전 필드가 disabled 이고 안내 배너가 뜬다', async ({ page }) => {
    await loginAs(page, ['platform:settings:read', 'platform:tenant:read']);
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await expect(
      page.getByText('조회 권한만 있습니다. 값을 변경하려면 platform:settings:write 권한이 필요합니다.'),
    ).toBeVisible();
    await expect(page.getByLabel('최대 턴 수')).toBeDisabled();
  });

  test('조회 실패는 다시 시도 버튼을 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', {}, { status: 500 });
    await page.goto('/settings');

    await expect(page.getByText('설정을 불러오는데 실패했습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
  });

  test('403 이면 권한 배너로 본문을 교체한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/settings', {}, { status: 403 });
    await page.goto('/settings');
    // role 까지 고정한다 — InlineBanner 는 role="status" + aria-live="polite" 다.
    // 문구만 잡으면 나중에 누가 평범한 <p> 로 바꿔도 테스트가 통과해 버린다.
    await expect(
      page.getByRole('status').filter({ hasText: '이 작업을 수행할 권한이 없습니다.' }),
    ).toBeVisible();
  });

  test('마지막 변경 시각을 탭 하단에 한 줄로만 보여준다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await expect(page.getByText('마지막 변경: 2026-08-19 14:02')).toBeVisible();
  });
});

test.describe('플랫폼 설정 — 비밀값과 저장', () => {
  test('비밀 필드는 빈 입력창 + 마스크 힌트로 그린다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();

    // 마스크를 입력창 초기값으로 쓰지 않는다.
    await expect(page.getByLabel('API 키')).toHaveValue('');
    await expect(page.getByLabel('API 키')).toHaveAttribute('placeholder', '새 값을 입력하면 교체됩니다');

    // embedding.api_key 는 SEEDED_18 에서 빈 값이다 — "설정되지 않음" 상태.
    //
    // 알려진 커버리지 공백(Task 13): 이 카탈로그에서 "삭제할 수 없으며 교체만 가능합니다"
    // (SettingField.tsx, spec.clearable === false 인 비밀) 분기를 exercised 하는 e2e 가 이제
    // 없다 — 그 문구의 유일한 사례였던 ai.api_key 가 ai.credential 문서로 옮겨갔고, 남은 비밀
    // 2개(smtp.password/embedding.api_key) 는 둘 다 clearable:true 다. 코드는 그대로 두되
    // (미래에 clearable:false 비밀이 다시 생길 수 있다), 지금은 이 분기가 죽은 코드에 가깝다.
    await expect(page.getByText('설정되지 않음').first()).toBeVisible();
  });

  test('smtp.password 는 비밀이면서 테넌트 재정의 가능 배지를 함께 받는다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();

    await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('');
    await expect(page.getByText('현재 설정됨 · ****cd34')).toBeVisible();
    await expect(page.getByRole('button', { name: '지우기' })).toBeVisible();
  });

  test('변경이 없으면 저장 버튼이 비활성이다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  test('저장 전 확인 다이얼로그가 변경 개수와 diff 를 보여준다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await page.getByLabel('최대 턴 수').fill('30');
    await page.getByRole('button', { name: '저장' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('플랫폼 기본값 저장')).toBeVisible();
    await expect(
      dialog.getByText(
        '1개 항목을 변경합니다. 이 값은 전 테넌트에 적용되며, 해당 항목을 재정의하지 않은 모든 워크스페이스가 즉시 영향을 받습니다.',
        { exact: true },
      ),
    ).toBeVisible();
    await expect(dialog.getByText('최대 턴 수', { exact: true })).toBeVisible();
    await expect(dialog.getByText('20 → 30', { exact: true })).toBeVisible();
  });

  // "탭이 다른 두 API 키를 동시에 바꾸면 확인 다이얼로그가 탭 이름으로 구별한다" 테스트는
  // 여기 없다(Task 13) — 그 시나리오의 전제(ai.api_key 와 embedding.api_key 가 이 범용
  // 카탈로그·같은 저장 흐름 안에서 라벨이 충돌한다)가 사라졌다. ai.api_key 는 이제
  // AiCredentialSection 의 완전히 별도인 저장 흐름(다른 엔드포인트, 다른 확인 다이얼로그)에
  // 있어 페이지 전역 SaveConfirmDialog 의 diff 목록에 아예 나타나지 않는다 — 라벨이 같은
  // 값이 이제 embedding.api_key 하나뿐이라 충돌할 상대가 없다.

  test('비밀 키의 이전/새 값은 다이얼로그에 절대 나오지 않는다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();

    await page.getByLabel('비밀번호', { exact: true }).fill('n3wPassw0rd');
    await page.getByRole('button', { name: '저장' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('값 변경됨')).toBeVisible();
    await expect(dialog.getByText('n3wPassw0rd')).toHaveCount(0);
    await expect(dialog.getByText('****cd34')).toHaveCount(0);
  });

  test('확인 후 변경된 키만 PUT 으로 나간다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('최대 턴 수').fill('30');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    // 미편집 키까지 보내면 그 키에 플랫폼 행이 새로 써져 테넌트 상속이 끊긴다.
    expect(req.payload).toEqual({ 'ai.max_turns': '30' });
    await expect(page.getByText('플랫폼 기본값이 저장되었습니다.')).toBeVisible();
  });

  test('STARTTLS 스위치는 리터럴 문자열을 보낸다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();

    await page.getByRole('switch').click();
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    // JSON boolean 이나 "on" 을 보내면 200 으로 저장되고 다운스트림에서 STARTTLS 가 조용히 꺼진다.
    expect(req.payload).toEqual({ 'smtp.starttls': 'false' });
  });

  test('지우기는 빈 문자열을 명시적으로 보낸다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();

    await page.getByRole('button', { name: '지우기' }).click();
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ 'smtp.password': '' });
  });

  test('검증 실패는 저장을 막고 필드 아래 문구를 띄운다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('최대 턴 수').fill('51');
    await page.getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('1~50 사이의 정수를 입력하세요')).toBeVisible();
    await expect(page.getByText('입력값을 확인하세요.')).toBeVisible();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });

  test('서버 400 메시지를 토스트에 그대로 싣는다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await mockApi(
      page,
      'PUT',
      '/api/platform/settings',
      { status: 400, error: 'Bad Request', message: 'OpenAI 임베딩 provider 에는 API 키가 필요합니다' },
      { status: 400 },
    );
    await page.goto('/settings');

    await page.getByLabel('최대 턴 수').fill('30');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('OpenAI 임베딩 provider 에는 API 키가 필요합니다')).toBeVisible();
  });

  test('되돌리기는 편집을 원복한다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await page.getByLabel('최대 턴 수').fill('30');
    await page.getByRole('button', { name: '되돌리기' }).click();

    await expect(page.getByLabel('최대 턴 수')).toHaveValue('20');
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  test('저장 성공 후 재조회로 지우기 표시·값이 실제로 초기화된다(M2, 재시드 경로 실제 검증)', async ({
    authenticatedPage: page,
  }) => {
    // mockApi 는 매 요청마다 같은 참조의 body 를 돌려주므로 react-query 의 structural sharing 이
    // 같은 참조를 유지해 재시드 블록(`data !== seededFrom`)이 한 번도 안 돈다 — 그래서 여기서는
    // page.route 를 직접 써서 두 번째 GET 이 다른 참조·다른 내용을 주도록 만든다.
    //
    // 이 커스텀 라우트는 정확히 '/api/platform/settings' 경로만 잡는다 — AiCredentialSection
    // 이 부르는 '/api/platform/settings/ai-credential' 은 별도 경로라 여기 안 걸린다. 그
    // GET 을 모킹하지 않으면 AiCredentialSection 이 loadFailed 로 빠져 "자격증명 정보를
    // 불러오지 못했습니다" 가 뜬다(이 테스트의 단언과는 무관하지만 잡음이라 명시적으로 막는다).
    await mockApi(page, 'GET', '/api/platform/settings/ai-credential', createAiCredential());
    let getCount = 0;
    await page.route(
      (url) => url.pathname === '/api/platform/settings',
      (route) => {
        if (route.request().method() === 'GET') {
          getCount += 1;
          const body =
            getCount === 1
              ? SEEDED_18
              : SEEDED_18.map((s) => (s.key === 'smtp.password' ? { ...s, value: '' } : s));
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(body),
          });
        }
        if (route.request().method() === 'PUT') {
          return route.fulfill({ status: 204, contentType: 'application/json', body: '{}' });
        }
        return route.fallback();
      },
    );

    await page.goto('/settings');
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();
    await expect(page.getByText('현재 설정됨 · ****cd34')).toBeVisible();

    await page.getByRole('button', { name: '지우기' }).click();
    await expect(page.getByText('저장하면 이 값이 삭제됩니다.')).toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('플랫폼 기본값이 저장되었습니다.')).toBeVisible();

    // 재조회(두 번째 GET, 다른 참조)가 도착하면 재시드 블록이 실제로 돌아 cleared 표시가
    // 초기화되고 새 데이터('설정되지 않음')가 반영된다. cleared 가 리셋되지 않았다면
    // "저장하면 이 값이 삭제됩니다." 가 여전히 남아 있어야 한다(양성 대조군).
    await expect(page.getByText('저장하면 이 값이 삭제됩니다.')).toHaveCount(0);
    await expect(page.getByText('설정되지 않음').first()).toBeVisible();
    expect(getCount).toBeGreaterThanOrEqual(2);
  });

  test('확인 다이얼로그를 거치지 않으면 PUT 이 나가지 않는다(다이얼로그가 실질 관문이다, M2)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('최대 턴 수').fill('30');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();

    // 취소해도 여전히 PUT 이 없어야 한다 — 다이얼로그가 열리는 것 자체가 아니라 '확인'
    // 클릭이 관문이다.
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });
});

/**
 * `AiCredentialSection`(Task 13) 전용 시나리오. 브리프 Step 5 가 요구하는 4가지(유형이 맨 앞 /
 * opencode 전환 시 필드 변화 / AI 탭 배지 없음 / 이메일 탭엔 여전히 있음)에 더해, Step 6 의
 * 뮤테이션 검사 대상과 이 리포트가 스스로 찾은 추가 뮤턴트를 함께 고정한다.
 */
test.describe('플랫폼 설정 — AI 자격증명 전용 섹션(Task 13)', () => {
  test(
    '유형(에이전트 유형)이 AI 탭 자격증명 fieldset 맨 앞에 온다(뮤테이션 검사 #1 — 유형을 필드 아래로 옮기면 RED)',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      await mockPlatformSettings(
        page,
        SEEDED_18,
        createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
      );
      await page.goto('/settings');
      // `allInnerTexts()` 는 자동 대기하지 않는다 — 로딩 스켈레톤(레이블 없는 "불러오는
      // 중..." fieldset) 순간에 평가되면 빈 배열을 돌려준다. 로드가 끝난 뒤 상태를 먼저
      // 기다린다.
      await expect(page.getByLabel('에이전트 유형')).toBeVisible();

      const labels = await page
        .locator('fieldset')
        .filter({ hasText: '자격증명' })
        .locator('label')
        .allInnerTexts();
      expect(labels[0]).toBe('에이전트 유형');
    },
  );

  test('유형을 opencode 로 바꾸면 공급자·기본 URL 이 나타나고 OAuth 토큰이 사라진다', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/settings');

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
    await expect(page.getByRole('button', { name: '모델 불러오기' })).toBeVisible();
  });

  test('이메일 탭에는 재정의 배지가 여전히 있다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();
    await expect(page.getByText('테넌트 재정의 가능').first()).toBeVisible();
  });

  test('저장된 비밀이 있어도 입력칸은 항상 비어 있다 — 마스크를 시드하지 않는다(뮤테이션 검사 #3)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: ['oauthToken', 'apiKey'] }),
    );
    await page.goto('/settings');

    await expect(page.locator('#ai-cred-oauth-token')).toHaveValue('');
    await expect(page.locator('#ai-cred-api-key')).toHaveValue('');
    // 값 대신 사실절/행동절만 보여준다(설계서 공통 절) — "현재 값이 설정되어 있습니다"이지
    // 마스크(****)가 아니다.
    await expect(page.getByText('현재 값이 설정되어 있습니다.').first()).toBeVisible();
  });

  test('저장된 값이 없으면 "설정된 값이 없습니다" 를 보여준다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );
    await page.goto('/settings');
    await expect(page.getByText('설정된 값이 없습니다.').first()).toBeVisible();
  });

  test('손대지 않은 비밀은 저장 시 빈 문자열로도 보내지 않는다(뮤테이션 검사 #4 — 삭제 방지)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({
        agentType: 'opencode',
        payload: { providerId: 'openai', baseURL: 'https://old.example.com/v1', reasoningEffort: '' },
        secretFieldNames: ['apiKey'],
      }),
    );
    const capture = await mockApi(
      page,
      'PUT',
      '/api/platform/settings/ai-credential',
      {},
      { status: 204, capture: true },
    );
    await page.goto('/settings');

    // baseURL 만 고치고 API 키는 전혀 손대지 않는다.
    await page.locator('#ai-cred-base-url').fill('https://new.example.com/v1');
    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();

    const req = await capture.waitForRequest();
    const body = req.payload as {
      agentType: string;
      payload: Record<string, string>;
      secret: Record<string, string>;
    };
    expect(body.agentType).toBe('opencode');
    expect(body.payload.baseURL).toBe('https://new.example.com/v1');
    // 핵심 단언: 타이핑하지 않은 apiKey 는 secret 맵에 **키 자체가 없어야** 한다(값이 ''
    // 이어도 실패). 여기서 빈 문자열을 보내면 서버 계약상 "삭제"가 되어, 이 값을 상속하는
    // 모든 테넌트의 opencode 자격증명이 저장 한 번에 함께 사라진다.
    expect(body.secret).not.toHaveProperty('apiKey');
  });

  test(
    '연결 확인(모델 불러오기) 버튼은 저장된 키가 있어도 지금 타이핑한 apiKey 가 없으면 비활성이다' +
      '(Ruling #24 — 플랫폼은 평면 교차 폴백이 없다, 추가 뮤턴트)',
    async ({ authenticatedPage: page }) => {
      await mockPlatformSettings(
        page,
        SEEDED_18,
        createAiCredential({
          agentType: 'opencode',
          payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
          secretFieldNames: ['apiKey'],
        }),
      );
      await page.goto('/settings');

      await expect(page.getByRole('button', { name: '모델 불러오기' })).toBeDisabled();
    },
  );

  test('유형을 바꾸고 저장하면 확인 다이얼로그가 뜨고, 확인해야만 PUT 이 나간다', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: ['oauthToken'] }),
    );
    const capture = await mockApi(
      page,
      'PUT',
      '/api/platform/settings/ai-credential',
      {},
      { status: 204, capture: true },
    );
    await page.goto('/settings');

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'Claude API', exact: true }).click();

    await expect(
      page.getByText(
        '유형을 바꾸면 이전 유형(Claude Agent SDK)의 저장된 비밀이 삭제됩니다. 복구할 수 없습니다.',
      ),
    ).toBeVisible();

    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('자격증명 유형을 바꿀까요?')).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();

    await dialog.getByRole('button', { name: '저장' }).click();
    const req = await capture.waitForRequest();
    expect((req.payload as { agentType: string }).agentType).toBe('cli-api');
  });
});

/**
 * Task 13 fix round 1 — 네 가지 조정 사항(Ruling #47~#50) 전용 시나리오. 위 describe 블록의
 * 기존 시나리오는 그대로 두고, 새로 생긴 동작만 여기 모은다.
 */
test.describe('플랫폼 설정 — Task 13 fix round 1', () => {
  test('인증 확인 버튼은 sdk/cli/cli-api 에만 있고, 클릭하면 배지를 보여준다(Ruling #47)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: ['oauthToken'] }),
    );
    await mockApi(page, 'GET', '/api/platform/ai/auth-status', {
      valid: true,
      email: 'ops@example.com',
      subscriptionType: 'pro',
    });
    await page.goto('/settings');

    await expect(page.getByRole('button', { name: '인증 확인' }).first()).toBeVisible();
    await page.getByRole('button', { name: '인증 확인' }).first().click();
    // sdk 는 OAuth 토큰·API 키 두 칸 모두에 같은 배지를 그린다(공유 상태) — .first() 로
    // "존재한다"만 증명한다, 여기서 개수를 단언하는 건 다른 테스트의 몫이 아니다.
    await expect(page.getByText('✓ 인증됨').first()).toBeVisible();
    await expect(page.getByText('(ops@example.com)').first()).toBeVisible();

    // opencode 로 바꾸면 이 버튼·배지 자체가 없다(Anthropic 인증 개념이 없다).
    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();
    await expect(page.getByRole('button', { name: '인증 확인' })).toHaveCount(0);
  });

  test('타이핑 중인 값이 있으면 인증 확인 버튼이 비활성이다(낡은 저장값을 검증하지 않도록)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );
    await page.goto('/settings');

    await expect(page.getByRole('button', { name: '인증 확인' }).first()).toBeEnabled();
    await page.locator('#ai-cred-oauth-token').fill('sk-ant-oat01-new');
    await expect(page.getByRole('button', { name: '인증 확인' }).first()).toBeDisabled();
  });

  test('Claude 모델만 바꿔도 인증 확인 버튼은 잠기지 않는다(fix round 2 — hasUnsavedCredentialInput)', async ({
    authenticatedPage: page,
  }) => {
    // 리뷰 지적: `hasUnsavedInput`(모델 포함)을 그대로 쓰면, 자격증명은 그대로인데 Claude
    // 모델만 바꿔도 "인증 확인"이 잠겼다 — 그 가드의 원래 목적("타이핑 중인 낡은 자격증명을
    // 검증하지 않는다")과 무관한 이유로 잠기는 것이었다. `hasUnsavedCredentialInput`(자격증명
    // 만 본다)을 써야 한다.
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );
    await page.goto('/settings');

    await expect(page.getByRole('button', { name: '인증 확인' }).first()).toBeEnabled();
    await page.locator('#ai-cred-model').click();
    await page.getByRole('option', { name: 'Claude Opus 4.8', exact: true }).click();
    await expect(page.getByRole('button', { name: '인증 확인' }).first()).toBeEnabled();
  });

  test('sdk 에서 "✓ 인증됨" 배지는 한 번만 그린다(fix round 2 — AuthBadge 중복 렌더)', async ({
    authenticatedPage: page,
  }) => {
    // 리뷰 지적: sdk 는 showOauth·showApiKey 가 둘 다 true 라, 예전엔 OAuth 칸·API 키 칸에
    // 같은 배지를 각각 그려 "✓ 인증됨"이 두 번 나타났다. 배지 하나는 "지금 적용 중인
    // 자격증명" 하나를 말하지, 필드 두 개를 각각 말하는 게 아니다.
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: ['oauthToken'] }),
    );
    await mockApi(page, 'GET', '/api/platform/ai/auth-status', { valid: true });
    await page.goto('/settings');

    await page.getByRole('button', { name: '인증 확인' }).first().click();
    await expect(page.getByText('✓ 인증됨')).toHaveCount(1);
  });

  test('모델은 저장됐는데 자격증명 저장이 실패하면, 되돌리기는 방금 저장된 모델을 보여준다(fix round 2 — doSave 부분 실패)', async ({
    authenticatedPage: page,
  }) => {
    // 리뷰 지적: 모델 PUT 이 성공하고 자격증명 PUT 이 실패하는 부분 실패에서, `originalModel`
    // 확정을 자격증명 PUT 성공까지 미루면 그 값이 낡은 채로 남는다 — 그 상태로 `되돌리기` 를
    // 누르면 서버에 더는 없는 옛 모델 값을 "되돌린" 값이라며 보여주게 된다. 지금은 모델 PUT
    // 이 성공하는 즉시(자격증명 PUT 을 기다리지 않고) `originalModel` 을 확정한다.
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );
    await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204 });
    await mockApi(
      page,
      'PUT',
      '/api/platform/settings/ai-credential',
      { message: '자격증명 저장에 실패했습니다' },
      { status: 400 },
    );
    await page.goto('/settings');

    await page.locator('#ai-cred-model').click();
    await page.getByRole('option', { name: 'Claude Opus 4.8', exact: true }).click();
    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();
    // 자격증명 PUT 이 400 으로 실패한다 — 토스트로 실패를 확인한다(`serverMessage` 는 400
    // 에서만 서버 메시지를 뽑는다, `lib/http-errors.ts` 참고).
    await expect(page.getByText('자격증명 저장에 실패했습니다')).toBeVisible();

    // 부분 실패 직후엔 모델이 이미 "저장된" 상태라 되돌릴 미저장 편집이 없다(`originalModel`
    // 이 모델 PUT 성공 시점에 곧바로 확정됐다 — 바로 이 확정이 이번 fix 의 핵심이다). 그
    // 확정이 실제로 반영됐는지 보려면, 그 위에 **추가로** 손대지 않은 편집을 하나 더 얹어야
    // 한다 — 세 번째 모델을 골라 "지금 화면의 미저장 편집"을 만든 다음 되돌리면, 이 세션의
    // 맨 처음 값(Claude Sonnet 5)이 아니라 방금 부분 실패 전에 저장에 성공한 값(Claude Opus
    // 4.8)으로 돌아가야 한다 — 되돌리기 전 이 버튼이 활성화된다는 것 자체가 "되돌릴 기준점이
    // Claude Sonnet 5 그대로 낡아 있지 않다"는 증거이기도 하다(낡았다면 이 시점에 이미
    // "미저장"으로 잡혀 버튼이 활성이었을 것이다 — 직전 단언 직후 버튼이 disabled 였던 것과
    // 대조하라).
    await page.locator('#ai-cred-model').click();
    await page.getByRole('option', { name: 'Claude Haiku 4.5', exact: true }).click();
    await expect(page.getByRole('button', { name: 'AI 자격증명·모델 초기화' })).toBeEnabled();
    await page.getByRole('button', { name: 'AI 자격증명·모델 초기화' }).click();
    await expect(page.locator('#ai-cred-model')).toContainText('Claude Opus 4.8');
  });

  test('모델(Claude 세 유형)은 select, opencode 는 4상태이고 접두어를 저장 시점에 자동으로 붙인다(Ruling #48/#51)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({
        agentType: 'opencode',
        payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
        secretFieldNames: ['apiKey'],
      }),
    );
    await page.goto('/settings');

    // 미로드 — "모델 불러오기" 를 누르기 전엔 입력칸이 비활성이고, 저장된 맨 모델 id 만
    // 보여준다(SEEDED_18 의 ai.model 'claude-sonnet-5' 는 providerId 'openai' 접두어가 없어
    // stripProviderPrefix 가 그대로 돌려준다). 값이 사라진 것처럼 보이면 안 된다(Minor #8).
    await expect(page.locator('#ai-cred-model')).toBeDisabled();
    await expect(page.locator('#ai-cred-model')).toHaveValue('claude-sonnet-5');

    // 공급자가 모델 목록을 주지 않으면(목록 없음) 그제서야 자유 입력으로 풀린다(Ruling #51).
    await page.locator('#ai-cred-opencode-api-key').fill('sk-oai-test');
    await mockApi(page, 'POST', '/api/platform/settings/ai-credential/probe', { ok: true, models: [] });
    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect(page.getByText('공급자가 모델 목록을 주지 않아 직접 입력합니다.')).toBeVisible();
    await expect(page.locator('#ai-cred-model')).toBeEnabled();
    await page.locator('#ai-cred-model').fill('gpt-4o');

    const modelCapture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await mockApi(page, 'PUT', '/api/platform/settings/ai-credential', {}, { status: 204, capture: true });
    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();

    const req = await modelCapture.waitForRequest();
    // 저장 직전 공급자 접두어(openai/)를 자동으로 붙인다 — 화면엔 맨 id 만 보이지만 서버엔
    // opencode 형식(providerId/modelId)으로 나가야 checkProviderConsistency 가 통과한다.
    expect(req.payload).toEqual({ 'ai.model': 'openai/gpt-4o' });
  });

  test('모델을 공급자보다 먼저 타이핑해도(직접 입력 전환 경로) 저장 시 접두어가 붙는다(Ruling #52, fix round 3 — 반대 순서)', async ({
    authenticatedPage: page,
  }) => {
    // 재리뷰 지적: 위 테스트("모델(Claude 세 유형)은...")는 "공급자를 먼저 고르고 모델을
    // 나중에 타이핑"만 검증한다. `canLoadModels` 는 baseURL·apiKey 만 요구하고 providerId 는
    // 요구하지 않으므로(훅 주석 참고), 공급자를 아직 고르지 않은 채로 "모델 불러오기" →
    // 실패 → "직접 입력으로 전환" → 모델을 먼저 타이핑 → 그제서야 공급자를 고르는 반대 순서가
    // 실제로 도달 가능하다. 라운드 1 의 실측 버그(`{"ai.model":"gpt-4o"}`, 접두어 없이 나간
    // 것)를 잡았던 게 바로 이 순서였다 — 저장 시점 조립(Ruling #52)이 정말 순서 무관인지는
    // 이 반대 순서까지 확인해야 증명된다.
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({
        agentType: 'opencode',
        // providerId 를 아예 안 준다 — "아직 공급자를 고르지 않은" 상태를 그대로 재현한다.
        payload: { baseURL: 'https://api.openai.com/v1' },
        secretFieldNames: ['apiKey'],
      }),
    );
    await page.goto('/settings');

    await expect(page.locator('#ai-cred-provider')).toContainText('공급자를 선택하세요');
    await page.locator('#ai-cred-opencode-api-key').fill('sk-oai-test');

    // 공급자 없이도 모델 불러오기는 가능하다(canLoadModels 는 baseURL·apiKey 만 본다) — 실패
    // 응답으로 "직접 입력으로 전환" 경로를 연다.
    await mockApi(page, 'POST', '/api/platform/settings/ai-credential/probe', {
      ok: false,
      message: '연결을 확인하지 못했습니다.',
    });
    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect(page.getByText('연결을 확인하지 못했습니다.')).toBeVisible();
    await page.getByRole('button', { name: '직접 입력으로 전환' }).click();

    // 모델을 먼저 타이핑한다 — 이 시점엔 providerId 가 아직 빈 문자열이다.
    await expect(page.locator('#ai-cred-model')).toBeEnabled();
    await page.locator('#ai-cred-model').fill('gpt-4o');

    // 공급자는 그 다음에 고른다.
    await page.locator('#ai-cred-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();

    const modelCapture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await mockApi(page, 'PUT', '/api/platform/settings/ai-credential', {}, { status: 204, capture: true });
    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();

    const req = await modelCapture.waitForRequest();
    // 타이핑 순서가 반대였어도(모델 먼저, 공급자 나중) 저장 시점엔 똑같이 접두어가 붙는다 —
    // `onChange` 시점 조립이었다면 이 순서에서 접두어 없는 `{"ai.model":"gpt-4o"}` 가 나갔다
    // (라운드 1 의 실측 버그 그대로).
    expect(req.payload).toEqual({ 'ai.model': 'openai/gpt-4o' });
  });

  test('모델이 바뀌면 자격증명보다 먼저 PUT /settings 로 쓴다(opencode 정합성 검사 순서, 추가 뮤턴트)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );
    // page.route 로 두 PUT 을 직접 잡아 호출 순서를 기록한다 — mockApi 캡처 두 개를 따로 쓰면
    // "둘 다 결국 도착했다"만 증명하고 "어느 쪽이 먼저인가"는 증명하지 못한다.
    const callOrder: string[] = [];
    // GET 은 손대지 않는다 — mockPlatformSettings 가 이미 등록한 목킹(초기 조회)으로
    // fallback 한다. PUT 만 가로채 순서를 기록하고 204 로 응답한다.
    await page.route(
      (url) => url.pathname === '/api/platform/settings' || url.pathname === '/api/platform/settings/ai-credential',
      (route) => {
        if (route.request().method() !== 'PUT') {
          return route.fallback();
        }
        callOrder.push(route.request().url().includes('ai-credential') ? 'credential' : 'model');
        return route.fulfill({ status: 204, contentType: 'application/json', body: '{}' });
      },
    );
    await page.goto('/settings');

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();
    await page.locator('#ai-cred-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();
    await page.locator('#ai-cred-base-url').fill('https://api.openai.com/v1');
    await page.locator('#ai-cred-opencode-api-key').fill('sk-oai-test');
    // Ruling #51 — 모델 칸은 4상태다. 목록 없음(자유 입력) 상태까지 "모델 불러오기" 를 거쳐야
    // #ai-cred-model 이 편집 가능해진다(미로드 상태는 비활성).
    await mockApi(page, 'POST', '/api/platform/settings/ai-credential/probe', { ok: true, models: [] });
    await page.getByRole('button', { name: '모델 불러오기' }).click();
    await expect(page.locator('#ai-cred-model')).toBeEnabled();
    await page.locator('#ai-cred-model').fill('gpt-4o');
    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();

    await expect.poll(() => callOrder.length).toBeGreaterThanOrEqual(2);
    expect(callOrder.slice(0, 2)).toEqual(['model', 'credential']);
  });

  test('opencode 로 바꾸고 모델 칸을 전혀 건드리지 않아도 저장 시 접두어가 자동으로 붙는다(sibling 버그, Ruling #52)', async ({
    authenticatedPage: page,
  }) => {
    // Ruling #52 의 "sibling 버그": opencode 로 바꾼 뒤 모델 칸을 손대지 않고 저장하면, 예전엔
    // `modelChanged` 가 false 로 남아 `ai.model` 이 접두어 없는 옛 Claude 값('claude-sonnet-5')
    // 그대로 저장됐다 — opencode 형식(providerId/modelId)이 아니라 저장은 되지만 실제 채팅
    // 시점에 `splitOpencodeModel` 에서 깨진다(메인 버그와 같은 실패, 계기만 "순서"가 아니라
    // "생략"이다). 지금은 opencode 진입 자체가 "손댔다"로 취급돼(`opencodeBoundaryCrossed`),
    // 모델 텍스트를 안 건드려도 저장 시점에 현재 공급자 접두어로 재조립된다.
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );
    const modelCapture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await mockApi(page, 'PUT', '/api/platform/settings/ai-credential', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.locator('#ai-cred-agent-type').click();
    await page.getByRole('option', { name: 'OpenCode', exact: true }).click();
    await page.locator('#ai-cred-provider').click();
    await page.getByRole('option', { name: 'OpenAI', exact: true }).click();
    await page.locator('#ai-cred-base-url').fill('https://api.openai.com/v1');
    await page.locator('#ai-cred-opencode-api-key').fill('sk-oai-test');
    // 모델 칸은 전혀 건드리지 않는다 — "모델 불러오기" 도 누르지 않는다.
    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();

    const req = await modelCapture.waitForRequest();
    expect(req.payload).toEqual({ 'ai.model': 'openai/claude-sonnet-5' });
  });

  test('두 저장 버튼 라벨이 각자 무엇을 저장하는지 말한다(Ruling #49)', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await expect(page.getByRole('button', { name: 'AI 자격증명·모델 반영' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'AI 자격증명·모델 초기화' })).toBeVisible();
    await expect(page.getByRole('button', { name: '나머지 설정 저장' })).toBeVisible();
    await expect(page.getByRole('button', { name: '나머지 설정 되돌리기' })).toBeVisible();
    await expect(
      page.getByText('AI 자격증명·모델을 제외한 나머지 설정(이 탭의 다른 필드, 이메일, 임베딩)을 저장합니다.'),
    ).toBeVisible();
  });

  test('탭을 옮겼다 돌아와도 타이핑 중이던 OAuth 토큰이 남아 있다(Ruling #50)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(
      page,
      SEEDED_18,
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );
    await page.goto('/settings');

    await page.locator('#ai-cred-oauth-token').fill('sk-ant-oat01-survives-tab-switch');
    await page.getByRole('tab', { name: '이메일(SMTP)' }).click();
    await expect(page.getByRole('tab', { name: '이메일(SMTP)' })).toHaveAttribute('aria-selected', 'true');
    await page.getByRole('tab', { name: 'AI 에이전트' }).click();

    await expect(page.locator('#ai-cred-oauth-token')).toHaveValue('sk-ant-oat01-survives-tab-switch');
  });

  test('AI 자격증명·모델을 저장해도 페이지 하단의 미저장 편집(최대 턴 수)이 사라지지 않는다(#50 과 같은 결함군, 자체 발견)', async ({
    authenticatedPage: page,
  }) => {
    // 자문 리뷰에서 발견한 실제 결함의 회귀 테스트: 처음엔 이 섹션의 저장이 성공하면
    // `queryClient.invalidateQueries(['platform-settings'])` 를 불렀는데, 그 무효화가
    // `SettingsPage` 의 재시드 블록을 다시 돌려 이메일/AI 나머지 탭의 미저장 편집까지
    // 지웠다(훅 파일 헤더 주석 참고). page.route 로 GET 요청 횟수를 세어, 이 저장이 그
    // 쿼리를 다시 부르지 않는다는 것까지 함께 확인한다(재발 방지 — "우연히 초기화 안 됨"이
    // 아니라 "애초에 재조회가 없다"는 것을 증명한다).
    await mockApi(page, 'GET', '/api/platform/settings/ai-credential', createAiCredential({
      agentType: 'sdk',
      secretFieldNames: [],
    }));
    let getCount = 0;
    await page.route(
      (url) => url.pathname === '/api/platform/settings',
      (route) => {
        if (route.request().method() === 'GET') {
          getCount += 1;
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SEEDED_18) });
        }
        if (route.request().method() === 'PUT') {
          return route.fulfill({ status: 204, contentType: 'application/json', body: '{}' });
        }
        return route.fallback();
      },
    );
    await mockApi(page, 'PUT', '/api/platform/settings/ai-credential', {}, { status: 204 });
    await page.goto('/settings');

    // 페이지 하단 폼(나머지 설정)에 아직 저장하지 않은 편집을 만든다.
    await page.getByLabel('최대 턴 수').fill('30');
    // 모델을 바꾸고 자격증명 섹션만 저장한다 — 이 저장은 별도 엔드포인트라 '최대 턴 수'를
    // 전혀 건드리지 않아야 한다.
    await page.locator('#ai-cred-model').click();
    await page.getByRole('option', { name: 'Claude Opus 4.8', exact: true }).click();
    await page.getByRole('button', { name: 'AI 자격증명·모델 반영' }).click();
    await expect(page.getByText('AI 자격증명·모델을 저장했습니다.')).toBeVisible();

    await expect(page.getByLabel('최대 턴 수')).toHaveValue('30');
    await expect(page.getByRole('button', { name: '나머지 설정 저장' })).toBeEnabled();
    // 이 저장은 GET /api/platform/settings 를 다시 부르지 않는다(최초 페이지 로드 1회뿐) —
    // 그래서 재시드 블록이 애초에 돌 기회가 없다.
    expect(getCount).toBe(1);
  });

  test('"나머지 설정 저장" 이 다른 값을 바꿔도 AI 모델 칸의 미저장 편집을 덮어쓰지 않는다(Item 3 — #50 과 같은 결함군, 세 번째 사례)', async ({
    authenticatedPage: page,
  }) => {
    // 리뷰가 실측한 결함: "나머지 설정 저장"(`SettingsPage.tsx` 의 `saveMutation.onSuccess`,
    // `AiCredentialSection` 자신의 저장과는 다른 흐름이라 이 섹션이 막을 수 없다)은 성공 시
    // `['platform-settings']` 쿼리를 무효화한다. 그 재조회 응답에서 '최대 턴 수' 처럼 `ai.model`
    // 과 무관한 값 <b>하나만</b> 바뀌어도, react-query 구조적 공유가 <b>최상위 배열 참조</b>를
    // 새로 만든다(`ai.model` 행 자체는 그대로인데도). 그 새 참조만 보고 재시드하면, 지금 막
    // 골라 둔(아직 저장 전인) 모델이 예전 서버 값으로 조용히 되돌아간다(리뷰 실측: Claude Opus
    // 4.8 로 편집 중이던 값이 Claude Sonnet 5 로 되돌아갔다).
    //
    // <b>뮤테이션 함정 주의</b>: 재조회 mock 응답을 **바꾸지 않으면**(=이전과 완전히 같은
    // 페이로드) react-query 구조적 공유가 예전 최상위 참조를 그대로 재사용해 이 결함이 아예
    // 드러나지 않는다(`hasUnsavedCredentialInput` 회귀 테스트가 실제로 겪은 함정 — 이 파일
    // 766번째 줄 테스트는 애초에 재조회 자체가 없어 이 함정과 무관하다). 그래서 두 번째
    // GET 응답은 반드시 '최대 턴 수' 값을 바꿔 내보낸다.
    await mockApi(
      page,
      'GET',
      '/api/platform/settings/ai-credential',
      createAiCredential({ agentType: 'sdk', secretFieldNames: [] }),
    );

    let getCount = 0;
    await page.route(
      (url) => url.pathname === '/api/platform/settings',
      (route) => {
        if (route.request().method() === 'GET') {
          getCount += 1;
          const settings =
            getCount === 1
              ? SEEDED_18
              : SEEDED_18.map((s) => (s.key === 'ai.max_turns' ? { ...s, value: '25' } : s));
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(settings),
          });
        }
        if (route.request().method() === 'PUT') {
          return route.fulfill({ status: 204, contentType: 'application/json', body: '{}' });
        }
        return route.fallback();
      },
    );
    await page.goto('/settings');

    // 모델을 바꾸되 이 섹션의 저장 버튼은 누르지 않는다 — 아직 미저장 편집이다.
    await page.locator('#ai-cred-model').click();
    await page.getByRole('option', { name: 'Claude Opus 4.8', exact: true }).click();
    await expect(page.locator('#ai-cred-model')).toContainText('Claude Opus 4.8');

    // 같은 AI 탭의, ai.model 과 무관한 범용 카탈로그 필드를 바꾸고 "나머지 설정 저장" 을
    // 누른다 — 이 저장은 PUT /settings 에 `ai.max_turns` 만 담는다(ai.model 은 여기 없다,
    // Ruling #48).
    await page.getByLabel('최대 턴 수').fill('25');
    await page.getByRole('button', { name: '나머지 설정 저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('플랫폼 기본값이 저장되었습니다.')).toBeVisible();

    // 무효화로 인한 재조회(2번째 GET)가 끝날 때까지 기다린다.
    await expect.poll(() => getCount).toBeGreaterThanOrEqual(2);

    // 핵심 단언 — 재조회가 끝난 뒤에도 모델 칸은 여전히 사용자가 고른(아직 저장하지 않은)
    // 'Claude Opus 4.8' 이다. 가드가 없으면 `ai.model` 행이 안 바뀌었어도 배열 참조가
    // 바뀌었다는 이유만으로 예전 서버 값('Claude Sonnet 5')으로 되돌아간다.
    await expect(page.locator('#ai-cred-model')).toContainText('Claude Opus 4.8');
  });
});
