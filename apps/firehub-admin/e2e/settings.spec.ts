// SEEDED_SETTINGS 는 e2e/factories/platform.factory.ts 에 둔다(리뷰 L2) — 스펙 파일에서 export 하면
// 다른 스펙이 import 하는 순간 Playwright 가 이 파일도 테스트 파일로 등록해 settings.spec.ts 의
// 테스트가 함께 중복 실행된다.
import type { Page } from '@playwright/test';

import { SEEDED_SETTINGS } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

/**
 * `GET /api/platform/settings`(범용 설정)를 모킹한다. AI·SMTP 설정은 워크스페이스 전용이라 플랫폼
 * 화면이 부르는 다른 엔드포인트가 없다 — 이 한 경로만 모킹하면 된다.
 */
async function mockPlatformSettings(page: Page, settings: typeof SEEDED_SETTINGS = SEEDED_SETTINGS) {
  await mockApi(page, 'GET', '/api/platform/settings', settings);
}

/** 임베딩 API 키가 이미 설정된 상태(마스크로 내려옴). 지우기·마스크 힌트 테스트용. */
const WITH_API_KEY = SEEDED_SETTINGS.map((s) =>
  s.key === 'embedding.api_key' ? { ...s, value: '****cd34' } : s,
);

const BANNER_TEXT =
  '여기서 저장한 값은 모든 워크스페이스에 적용됩니다. AI·이메일 설정은 각 워크스페이스의 설정 화면에서 관리합니다.';

test.describe('플랫폼 설정 — 카탈로그와 상태', () => {
  test('탭바 없이 임베딩 카드만 나오고 안내 배너가 뜬다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await expect(page.getByRole('heading', { name: '플랫폼 설정' })).toBeVisible();
    await expect(page.getByText(BANNER_TEXT)).toBeVisible();

    // 임베딩 4필드가 먼저 떴는지 확인한다 — 스켈레톤 상태에서 부재 단언이 "이미 0" 으로 통과하는
    // 공허한 단언을 막는다.
    for (const label of ['제공자', '모델', '기본 URL', 'API 키']) {
      await expect(page.getByLabel(label, { exact: true })).toBeVisible();
    }

    // 탭바가 없다(#712) — 남은 그룹이 하나라 고를 탭이 없다.
    await expect(page.getByRole('tablist')).toHaveCount(0);
    await expect(page.getByRole('tab')).toHaveCount(0);

    // SMTP 필드·라벨이 없다.
    for (const label of ['SMTP 호스트', '포트', '사용자 이름', '비밀번호', 'STARTTLS', '보낸 사람 주소']) {
      await expect(page.getByLabel(label, { exact: true })).toHaveCount(0);
    }
    await expect(page.getByText(/SMTP/)).toHaveCount(0);
    await expect(page.getByRole('switch')).toHaveCount(0);

    // 키별 적용 범위 배지·안내 문구가 없고, 금지 용어가 화면 어디에도 없다.
    await expect(page.getByText(/재정의|오버라이드/)).toHaveCount(0);
    await expect(page.getByText('전역 고정')).toHaveCount(0);

    await page.screenshot({ path: 'test-results/tc/admin-settings/platform-settings-embedding.png', fullPage: true });
  });

  test('필드 설명은 서버 description 을 그대로 쓴다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await expect(page.getByText('임베딩 base URL', { exact: true })).toBeVisible();
  });

  test('쓰기 권한이 없으면 전 필드가 disabled 이고 안내 배너가 뜬다', async ({ page }) => {
    await loginAs(page, ['platform:settings:read', 'platform:tenant:read']);
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await expect(
      page.getByText('조회 권한만 있습니다. 값을 변경하려면 platform:settings:write 권한이 필요합니다.'),
    ).toBeVisible();
    await expect(page.getByLabel('모델', { exact: true })).toBeDisabled();
    await expect(page.getByLabel('기본 URL', { exact: true })).toBeDisabled();
    await expect(page.getByLabel('API 키', { exact: true })).toBeDisabled();
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

  test('마지막 변경 시각을 카드 하단에 한 줄로만 보여준다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await expect(page.getByText('마지막 변경: 2026-08-19 14:02')).toBeVisible();
  });
});

test.describe('플랫폼 설정 — 비밀값과 저장', () => {
  test('비밀 필드는 빈 입력창 + "설정되지 않음" 힌트로 그린다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    // 마스크를 입력창 초기값으로 쓰지 않는다.
    await expect(page.getByLabel('API 키')).toHaveValue('');
    await expect(page.getByLabel('API 키')).toHaveAttribute('placeholder', '새 값을 입력하면 교체됩니다');
    // embedding.api_key 는 SEEDED_SETTINGS 에서 빈 값이다 — "설정되지 않음" 상태이고 지우기가 없다.
    await expect(page.getByText('설정되지 않음')).toBeVisible();
    await expect(page.getByRole('button', { name: '지우기' })).toHaveCount(0);
  });

  test('설정된 비밀 키는 마스크 힌트와 지우기 버튼을 보여 준다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page, WITH_API_KEY);
    await page.goto('/settings');

    await expect(page.getByLabel('API 키')).toHaveValue('');
    await expect(page.getByText('현재 설정됨 · ****cd34')).toBeVisible();
    await expect(page.getByRole('button', { name: '지우기' })).toBeVisible();
  });

  test('변경이 없으면 저장 버튼이 비활성이다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  test('저장 전 확인 다이얼로그가 변경 개수·적용 범위·diff 를 보여준다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await page.getByLabel('모델', { exact: true }).fill('bge-m4');
    await page.getByRole('button', { name: '저장' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('플랫폼 설정 저장')).toBeVisible();
    await expect(
      dialog.getByText('1개 항목을 변경합니다. 저장하면 모든 워크스페이스에 즉시 적용됩니다.', { exact: true }),
    ).toBeVisible();
    await expect(dialog.getByText('모델', { exact: true })).toBeVisible();
    await expect(dialog.getByText('bge-m3 → bge-m4', { exact: true })).toBeVisible();
    // 옛 문구(테넌트 상속 전제)가 남아 있지 않다.
    await expect(dialog.getByText(/재정의|오버라이드|테넌트/)).toHaveCount(0);
  });

  test('비밀 키의 이전/새 값은 다이얼로그에 절대 나오지 않는다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page, WITH_API_KEY);
    await page.goto('/settings');

    await page.getByLabel('API 키').fill('n3wEmbeddingKey');
    await page.getByRole('button', { name: '저장' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('값 변경됨')).toBeVisible();
    await expect(dialog.getByText('n3wEmbeddingKey')).toHaveCount(0);
    await expect(dialog.getByText('****cd34')).toHaveCount(0);
  });

  test('확인 후 변경된 임베딩 키만 PUT 으로 나간다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('모델', { exact: true }).fill('bge-m4');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    // 미편집 키는 보내지 않는다 — 부분 갱신 계약이고, 보낸 키만 updatedAt 이 바뀌어야 한다.
    expect(req.payload).toEqual({ 'embedding.model': 'bge-m4' });
    expect(Object.keys(req.payload as Record<string, string>).every((k) => k.startsWith('embedding.'))).toBe(true);
    await expect(page.getByText('플랫폼 설정이 저장되었습니다.')).toBeVisible();
  });

  test('지우기는 빈 문자열을 명시적으로 보낸다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page, WITH_API_KEY);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByRole('button', { name: '지우기' }).click();
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ 'embedding.api_key': '' });
  });

  test('검증 실패는 저장을 막고 필드 아래 문구를 띄운다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('기본 URL', { exact: true }).fill('localhost:11434');
    await page.getByRole('button', { name: '저장' }).click();

    await expect(
      page.getByText('임베딩 Base URL 은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다'),
    ).toBeVisible();
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

    await page.getByLabel('모델', { exact: true }).fill('bge-m4');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('OpenAI 임베딩 provider 에는 API 키가 필요합니다')).toBeVisible();
  });

  test('되돌리기는 편집을 원복한다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page);
    await page.goto('/settings');

    await page.getByLabel('모델', { exact: true }).fill('bge-m4');
    await page.getByRole('button', { name: '되돌리기' }).click();

    await expect(page.getByLabel('모델', { exact: true })).toHaveValue('bge-m3');
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
          const body = getCount === 1 ? WITH_API_KEY : SEEDED_SETTINGS;
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
    await expect(page.getByText('현재 설정됨 · ****cd34')).toBeVisible();

    await page.getByRole('button', { name: '지우기' }).click();
    await expect(page.getByText('저장하면 이 값이 삭제됩니다.')).toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('플랫폼 설정이 저장되었습니다.')).toBeVisible();

    // 재조회(두 번째 GET, 다른 참조)가 도착하면 재시드 블록이 실제로 돌아 cleared 표시가
    // 초기화되고 새 데이터('설정되지 않음')가 반영된다. cleared 가 리셋되지 않았다면
    // "저장하면 이 값이 삭제됩니다." 가 여전히 남아 있어야 한다(양성 대조군).
    await expect(page.getByText('저장하면 이 값이 삭제됩니다.')).toHaveCount(0);
    await expect(page.getByText('설정되지 않음')).toBeVisible();
    expect(getCount).toBeGreaterThanOrEqual(2);
  });

  test('확인 다이얼로그를 거치지 않으면 PUT 이 나가지 않는다(다이얼로그가 실질 관문이다, M2)', async ({
    authenticatedPage: page,
  }) => {
    await mockPlatformSettings(page);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('모델', { exact: true }).fill('bge-m4');
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
 * 워크스페이스 전용 설정(AI·SMTP)은 플랫폼 설정에 없다. 서버가 (마이그레이션 이전 DB 등으로)
 * `ai.*`/`smtp.*` 행을 섞어 보내도 화면은 그리지 않고, 저장에도 싣지 않는다 — 서버는 `smtp.*` 가
 * 섞인 플랫폼 쓰기를 요청 전체 400 으로 거부하므로, 여기 섞이면 임베딩 저장까지 통째로 실패한다.
 */
test.describe('플랫폼 설정 — 워크스페이스 전용 설정 없음', () => {
  const WITH_STRAY_ROWS = [
    ...SEEDED_SETTINGS,
    { key: 'ai.model', value: 'stray-model', description: 'AI 에이전트 사용 모델', updatedAt: '2026-08-19T14:02:31' },
    { key: 'ai.max_turns', value: '20', description: '최대 턴 수', updatedAt: '2026-08-19T14:02:31' },
    { key: 'smtp.host', value: 'stray.smtp.example.com', description: 'SMTP 호스트', updatedAt: '2026-08-19T14:02:31' },
    { key: 'smtp.password', value: '****ef56', description: 'SMTP 비밀번호', updatedAt: '2026-08-19T14:02:31' },
  ];

  test('AI·SMTP 필드가 없고 AI 엔드포인트를 부르지 않는다', async ({ authenticatedPage: page }) => {
    const aiCalls: string[] = [];
    page.on('request', (req) => {
      const { pathname } = new URL(req.url());
      if (pathname.includes('/ai-credential') || pathname.includes('/ai/')) {
        aiCalls.push(`${req.method()} ${pathname}`);
      }
    });
    await mockPlatformSettings(page, WITH_STRAY_ROWS);
    await page.goto('/settings');

    // 부재 단언 전에 실제 콘텐츠가 떴는지 먼저 기다린다 — 스켈레톤 상태에서 "이미 0" 으로
    // 통과하는 공허한 단언을 막는다.
    await expect(page.getByLabel('모델', { exact: true })).toBeVisible();

    await expect(page.getByRole('tab')).toHaveCount(0);
    await expect(page.getByLabel('최대 턴 수')).toHaveCount(0);
    await expect(page.getByLabel('SMTP 호스트')).toHaveCount(0);
    await expect(page.getByText('stray-model')).toHaveCount(0);
    await expect(page.getByText('AI 에이전트 사용 모델')).toHaveCount(0);
    await expect(page.getByText('stray.smtp.example.com')).toHaveCount(0);
    await expect(page.getByText('****ef56')).toHaveCount(0);
    await expect(page.getByText(/SMTP/)).toHaveCount(0);
    expect(aiCalls).toEqual([]);
  });

  test('저장 페이로드에 ai.*·smtp.* 키가 섞이지 않는다', async ({ authenticatedPage: page }) => {
    await mockPlatformSettings(page, WITH_STRAY_ROWS);
    const capture = await mockApi(page, 'PUT', '/api/platform/settings', {}, { status: 204, capture: true });
    await page.goto('/settings');

    await page.getByLabel('모델', { exact: true }).fill('bge-m4');
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ 'embedding.model': 'bge-m4' });
  });
});
