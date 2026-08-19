import { createEmbeddingSettings } from '../../factories/admin.factory';
import { setupAdminAuth, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 임베딩 설정 탭 E2E 테스트 (P7-b 이후)
 *
 * `embedding.*` 4키는 모델이 바뀌면 벡터 차원이 바뀌어 기존 임베딩 전량이 무효화되므로 플랫폼이
 * 소유한다. 그래서 이 탭은 **전면 잠금**이다 — 검증 대상은 "편집·저장이 사라졌는가"와 "값 표시는
 * 여전히 서버 응답만 따르는가"다. 잠금 표시 자체는 하드코딩이므로(균일 잠금이라 데이터로 구동할
 * 편차가 없다) 서버 플래그의 번역은 이 탭이 아니라 AI 탭 스펙이 지킨다.
 *
 * 편집·검증 시나리오(#322 provider 스왑, #323 API 키 검증, 저장 실패 토스트 등)는 해당 UI 자체가
 * 제거되어 삭제했다 — 약화된 형태로 남기면 없는 기능을 검증하는 스펙이 된다.
 * 반면 **재임베딩 카드는 설정 변경이 아니라 운영 액션**이므로 그대로 유지·검증한다.
 */

const LOCKED_NOTE = '플랫폼 운영자만 변경할 수 있는 항목입니다.';

test.describe('임베딩 설정 탭 — 플랫폼 전용', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    // GET /settings 는 prefix 로 분기된다(ai=기본 탭, embedding=이 탭)
    await setupSettingsMocks(page);
  });

  test('임베딩 탭이 렌더링되고 서버 값이 읽기 전용으로 채워진다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/admin/settings');

    // 임베딩 탭 클릭 → 탭 마운트 시 GET /settings?prefix=embedding 호출
    await page.getByRole('tab', { name: '임베딩' }).click();

    // 카드 제목 + 차원 고정 안내 노출 확인
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
    await expect(page.getByText(/임베딩 차원은 1024로 고정됩니다/)).toBeVisible();

    // 응답 → UI 반영. provider 는 코드값(OLLAMA)이 사람이 읽는 라벨로 표시되어야 한다.
    await expect(page.locator('#embedding-provider')).toContainText('Ollama');
    await expect(page.locator('#embedding-model')).toHaveValue('bge-m3');
    await expect(page.locator('#embedding-base-url')).toHaveValue(
      'http://host.docker.internal:11434',
    );
    // 마스킹된 api_key가 그대로 표시된다
    await expect(page.locator('#embedding-api-key')).toHaveValue('****masked****');
  });

  test('4필드 전부 잠금 표시 + 비활성이고 저장 경로가 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();

    // 이 탭의 잠금 표시는 서버 플래그가 아니라 **하드코딩**이다(SMTP 탭과 동일). `embedding.*`
    // 4키가 정책상 전부 균일하게 플랫폼 잠금이라 데이터로 구동할 편차가 없기 때문이다. 따라서 아래
    // 단언이 지키는 것은 "플래그가 잠금으로 번역되는가"가 아니라 "4필드 전부가 실제로 잠겨 보이고
    // 조작 불가인가"다. 대가도 함께 적어 둔다 — 정책이 바뀌어 `embedding.*` 중 하나가 테넌트에
    // 열려도 이 탭은 따라가지 않으며, 그 회귀는 여기서 잡히지 않는다(구현을 함께 고쳐야 한다).
    for (const id of [
      'embedding-provider',
      'embedding-model',
      'embedding-base-url',
      'embedding-api-key',
    ]) {
      const box = page.locator('div.space-y-2', { has: page.locator(`#${id}`) });
      const badge = box.getByLabel('플랫폼 전용: 이 테넌트에서 편집할 수 없음');
      await expect(badge).toBeVisible();
      await expect(badge.locator('svg')).toBeVisible();
      await expect(box.getByText(LOCKED_NOTE)).toBeVisible();
      await expect(page.locator(`#${id}`)).toBeDisabled();
    }

    // 저장/되돌리기 버튼 행이 배너로 대체되었다(백엔드가 이 키들의 저장을 거부하므로)
    await expect(page.getByText('플랫폼 전용 설정').first()).toBeVisible();
    await expect(page.getByRole('button', { name: '저장' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '되돌리기' })).toHaveCount(0);
  });

  test('서버가 준 값이 달라지면 표시도 그대로 따라간다', async ({ authenticatedPage: page }) => {
    // 화면이 provider 기본값을 하드코딩해 보여주지 않는다는 것을 고정한다 — 읽기 전용 화면에서
    // 코드 기본값을 시드하면 실제 적용값과 다른 값을 자신 있게 보여주게 된다.
    await setupSettingsMocks(page, {
      embedding: createEmbeddingSettings({
        'embedding.provider': { value: 'OPENAI' },
        'embedding.model': { value: 'text-embedding-3-small' },
        'embedding.base_url': { value: 'https://api.openai.com' },
      }),
    });

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();
    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();

    await expect(page.locator('#embedding-provider')).toContainText('OpenAI');
    await expect(page.locator('#embedding-model')).toHaveValue('text-embedding-3-small');
    await expect(page.locator('#embedding-base-url')).toHaveValue('https://api.openai.com');
  });

  // 회귀: 설정 탭 스트립(TabsList)에 overflow-x-auto만 주면 CSS 사양상 overflow-y가
  // visible→auto로 승격되어 탭 콘텐츠가 고정 높이를 미세 초과할 때 유령 세로 스크롤바가 생긴다.
  // overflow-y를 hidden으로 고정해 세로 오버플로가 없어야 한다.
  test('설정 탭 스트립에 유령 세로 스크롤바가 없다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/settings');
    const tablist = page.getByRole('tablist');
    await expect(tablist).toBeVisible();

    // overflow-y가 auto로 승격되지 않고 hidden으로 고정되어야 한다(승격 시 유령 세로 스크롤바 발생).
    const overflowY = await tablist.evaluate((el) => getComputedStyle(el).overflowY);
    expect(overflowY).toBe('hidden');
  });
});

/**
 * 재임베딩 카드 E2E 테스트
 * - provider 설정 카드 아래의 "재임베딩" 카드(현황 + 전체 재임베딩 실행)를 검증한다.
 * - 설정 변경이 아니라 운영 액션이므로 P7-b 전면 잠금에도 그대로 살아 있어야 한다.
 */
test.describe('재임베딩 카드', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    // 임베딩 탭 진입 시 provider 폼이 호출하는 GET /settings 모킹 (prefix 분기 헬퍼 재사용)
    await setupSettingsMocks(page);
    // 재임베딩 현황 — 데이터셋은 완료(28/28), 문서 청크는 진행 중(340/500)
    await mockApi(page, 'GET', '/api/v1/admin/embedding/status', {
      model: 'bge-m3',
      datasets: { total: 28, embedded: 28 },
      documentChunks: { total: 500, embedded: 340 },
    });
  });

  test(
    '재임베딩 카드가 현황을 표시하고 전체 재임베딩 실행 시 시작 toast가 노출된다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      // 전체 재임베딩 트리거 — 202 Accepted + 대상 카운트(데이터셋 28, 문서셋 4)
      const reindexCapture = await mockApi(
        page,
        'POST',
        '/api/v1/admin/embedding/reindex-all',
        { datasets: 28, documentDatasets: 4 },
        { status: 202, capture: true },
      );

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '임베딩' }).click();

      // 재임베딩 카드 노출 + 현재 모델 + 문서 청크 진행 카운트 확인
      await expect(page.getByText('재임베딩', { exact: true })).toBeVisible();
      await expect(page.getByText('현재 모델:')).toBeVisible();
      await expect(page.getByText('340 / 500')).toBeVisible();

      // 트리거 버튼 클릭 → AlertDialog 노출 → 다이얼로그 내 "실행" 클릭
      // (트리거는 "전체 재임베딩 실행", 확인 액션은 "실행"이므로 alertdialog로 스코프를 좁힌다)
      await page.getByRole('button', { name: '전체 재임베딩 실행' }).click();
      const dialog = page.getByRole('alertdialog');
      await expect(dialog).toBeVisible();
      await dialog.getByRole('button', { name: '실행' }).click();

      // 트리거 API 가 실제로 호출되었는지 + 시작 toast 확인
      const req = await reindexCapture.waitForRequest();
      expect(req.url.pathname).toBe('/api/v1/admin/embedding/reindex-all');
      await expect(page.getByText(/재임베딩을 시작했습니다/)).toBeVisible({ timeout: 5000 });
    },
  );
});
