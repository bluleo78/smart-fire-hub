// SEEDED_18 은 e2e/factories/platform.factory.ts 로 옮겼다(리뷰 L2) — 스펙 파일에서 export 하면
// 다른 스펙이 import 하는 순간 Playwright 가 이 파일도 테스트 파일로 등록해 settings.spec.ts 의
// 테스트가 함께 중복 실행된다.
import type { Page } from '@playwright/test';

import { SEEDED_18 } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

/**
 * `GET /api/platform/settings`(범용 설정)를 모킹한다. AI 설정은 워크스페이스 전용이라 플랫폼
 * 화면이 부르는 AI 엔드포인트가 없다 — 이 한 경로만 모킹하면 된다.
 */
async function mockPlatformSettings(page: Page, settings: typeof SEEDED_18 = SEEDED_18) {
  await mockApi(page, 'GET', '/api/platform/settings', settings);
}

test.describe('플랫폼 설정 — 카탈로그와 상태', () => {
  test('탭 2개(이메일·임베딩)와 안내 배너가 나온다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await expect(page.getByRole('heading', { name: '플랫폼 설정' })).toBeVisible();
    await expect(page.getByText('여기서 저장한 값은 모든 워크스페이스에 적용되는 기본값입니다. AI 설정은 각 워크스페이스의 설정 화면에서 관리합니다.')).toBeVisible();
    await expect(page.getByRole('tab', { name: '이메일(SMTP)' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '임베딩' })).toBeVisible();
    await expect(page.getByRole('tab')).toHaveCount(2);
    // AI 에이전트 탭은 없다 — AI 설정은 워크스페이스별이다.
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toHaveCount(0);

    await page.screenshot({ path: 'test-results/tc/admin-settings/platform-settings-smtp.png', fullPage: true });
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByLabel('기본 URL')).toBeVisible();
    await page.screenshot({ path: 'test-results/tc/admin-settings/platform-settings-embedding.png', fullPage: true });
  });

  test('배지 두 종류가 항상 붙는다 — 10필드 전부(개수로 단언, 리뷰 L1)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    // .first() 두 번은 "존재한다"만 증명하고 "전부에 붙는다"는 증명하지 못한다 — 어느 필드가
    // 배지를 빼먹어도 초록이다. 탭마다 두 배지 수가 그 탭의 필드 수와 정확히 같은지 개수로
    // 단언한다. SMTP 6 / 임베딩 4 = 10.
    const tabFieldCounts: Record<string, { overridable: number; fixed: number }> = {
      '이메일(SMTP)': { overridable: 6, fixed: 0 },
      '임베딩': { overridable: 0, fixed: 4 },
    };
    for (const [tabName, expected] of Object.entries(tabFieldCounts)) {
      await page.getByRole('tab', { name: tabName }).click();
      // toHaveCount 는 자동 대기한다 — 탭 전환 직후 이전 패널 잔상으로 세지 않는다.
      await expect(page.getByText('테넌트 재정의 가능')).toHaveCount(expected.overridable);
      await expect(page.getByText('전역 고정')).toHaveCount(expected.fixed);
    }

    // 두 종류가 각기 다른 안내 문구를 낸다는 대조군.
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

  test('필드 설명은 서버 description 을 그대로 쓴다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await expect(page.getByText('SMTP 사용자')).toBeVisible();
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
    await expect(page.getByLabel('포트')).toBeDisabled();
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
    // (알려진 커버리지 공백: 남은 비밀 2개가 둘 다 clearable:true 라 "삭제할 수 없으며 교체만
    // 가능합니다" 분기를 exercised 하는 e2e 가 없다.)
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

    await page.getByLabel('포트').fill('2525');
    await page.getByRole('button', { name: '저장' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('플랫폼 기본값 저장')).toBeVisible();
    await expect(
      dialog.getByText(
        '1개 항목을 변경합니다. 이 값은 전 테넌트에 적용되며, 해당 항목을 재정의하지 않은 모든 워크스페이스가 즉시 영향을 받습니다.',
        { exact: true },
      ),
    ).toBeVisible();
    await expect(dialog.getByText('포트', { exact: true })).toBeVisible();
    await expect(dialog.getByText('587 → 2525', { exact: true })).toBeVisible();
  });

  test('여러 탭의 변경을 함께 저장하면 확인 다이얼로그가 탭 이름을 단다(리뷰 L1)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await page.getByLabel('SMTP 호스트').fill('mail.example.org');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await page.getByLabel('모델').fill('bge-m4');
    await page.getByRole('button', { name: '저장' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('이메일(SMTP) ·')).toBeVisible();
    await expect(dialog.getByText('임베딩 ·')).toBeVisible();
    await expect(dialog.getByText('smtp.example.com → mail.example.org', { exact: true })).toBeVisible();
    await expect(dialog.getByText('bge-m3 → bge-m4', { exact: true })).toBeVisible();
  });

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

    await page.getByLabel('포트').fill('2525');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    // 미편집 키까지 보내면 그 키에 플랫폼 행이 새로 써져 테넌트 상속이 끊긴다.
    expect(req.payload).toEqual({ 'smtp.port': '2525' });
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

    await page.getByLabel('포트').fill('70000');
    await page.getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('1~65535 사이의 정수를 입력하세요')).toBeVisible();
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

    await page.getByLabel('포트').fill('2525');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('OpenAI 임베딩 provider 에는 API 키가 필요합니다')).toBeVisible();
  });

  test('되돌리기는 편집을 원복한다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await page.getByLabel('포트').fill('2525');
    await page.getByRole('button', { name: '되돌리기' }).click();

    await expect(page.getByLabel('포트')).toHaveValue('587');
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  test('저장 성공 후 재조회로 지우기 표시·값이 실제로 초기화된다(M2, 재시드 경로 실제 검증)', async ({
    authenticatedPage: page,
  }) => {
    // mockApi 는 매 요청마다 같은 참조의 body 를 돌려주므로 react-query 의 structural sharing 이
    // 같은 참조를 유지해 재시드 블록(`data !== seededFrom`)이 한 번도 안 돈다 — 그래서 여기서는
    // page.route 를 직접 써서 두 번째 GET 이 다른 참조·다른 내용을 주도록 만든다.
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

    await page.getByLabel('포트').fill('2525');
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
 * AI 설정은 플랫폼 설정에 없다 — 워크스페이스(테넌트) 전용이다. 서버가 실수로 `ai.*` 행을 내려도
 * 화면은 그리지 않고, 저장에도 싣지 않는다.
 */
test.describe('플랫폼 설정 — AI 설정 없음', () => {
  /** 서버가 (V127 이전 DB 등으로) `ai.*` 행을 섞어 보낸 응답. 화면은 이를 무시해야 한다. */
  const WITH_STRAY_AI = [
    ...SEEDED_18,
    { key: 'ai.model', value: 'stray-model', description: 'AI 에이전트 사용 모델', updatedAt: '2026-08-19T14:02:31' },
    { key: 'ai.max_turns', value: '20', description: '최대 턴 수', updatedAt: '2026-08-19T14:02:31' },
  ];

  test('AI 탭·필드가 없고 AI 엔드포인트를 부르지 않는다', async ({ authenticatedPage: page }) => {
    const aiCalls: string[] = [];
    page.on('request', (req) => {
      const { pathname } = new URL(req.url());
      if (pathname.includes('/ai-credential') || pathname.includes('/ai/')) {
        aiCalls.push(`${req.method()} ${pathname}`);
      }
    });
    await mockPlatformSettings(page, WITH_STRAY_AI);
    await page.goto('/settings');

    // 부재 단언 전에 실제 콘텐츠가 떴는지 먼저 기다린다 — 스켈레톤 상태에서 "이미 0" 으로
    // 통과하는 공허한 단언을 막는다.
    await expect(page.getByLabel('SMTP 호스트')).toBeVisible();

    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toHaveCount(0);
    for (const tabName of ['이메일(SMTP)', '임베딩']) {
      await page.getByRole('tab', { name: tabName }).click();
      await expect(page.getByLabel('최대 턴 수')).toHaveCount(0);
      await expect(page.getByLabel('시스템 프롬프트')).toHaveCount(0);
      await expect(page.getByLabel('Temperature')).toHaveCount(0);
      await expect(page.getByText('stray-model')).toHaveCount(0);
      await expect(page.getByText('AI 에이전트 사용 모델')).toHaveCount(0);
    }
    expect(aiCalls).toEqual([]);
  });

  test('저장 페이로드에 ai.* 키가 섞이지 않는다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page, WITH_STRAY_AI);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('SMTP 호스트').fill('mail.example.org');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ 'smtp.host': 'mail.example.org' });
  });
});
