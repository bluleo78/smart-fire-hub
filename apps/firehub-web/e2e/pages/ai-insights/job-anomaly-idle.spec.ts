import { createJob, createTemplates } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 — 메트릭 0개 이상 탐지의 '미동작' 노출 (#362) E2E 회귀 테스트
 *
 * MetricPollerService는 anomaly.metrics를 순회할 뿐이라, 메트릭이 0개면 enabled=true여도
 * 절대 발화하지 않는다. 그런데 UI는 '활성' 배지만 보여줘서 사용자는 감시가 되고 있다고 믿었다.
 * #354의 '미등록'과 같은 방식으로 파생 신호(활성 + 메트릭 0개)를 목록·상세 양쪽에 노출한다.
 *
 * 저장 자체는 막지 않는다 — 작업을 먼저 만들고 메트릭을 나중에 붙이는 흐름이 정당하기 때문에,
 * 이미 저장된 행에도 신호가 보여야 한다는 점이 이 테스트의 핵심이다.
 */

/** 이상 탐지 config를 만든다. metrics를 비우면 폴러가 순회할 대상이 없는 상태가 된다. */
function anomalyConfig(metrics: unknown[]) {
  return {
    anomaly: { enabled: true, metrics, sensitivity: 'medium', cooldownMinutes: 30 },
    channels: [{ type: 'CHAT', recipientEmails: [], recipientUserIds: [] }],
  };
}

const SYSTEM_METRIC = {
  id: 'm1',
  name: '데이터셋 수',
  source: 'system',
  metricKey: 'dataset_count',
  pollingInterval: 300,
};

/** 상세 페이지(모니터링 탭) 렌더에 필요한 API를 모킹한다. */
async function setupJobWithConfig(
  page: Parameters<typeof mockApi>[0],
  config: Record<string, unknown>,
) {
  const job = createJob({ id: 1, name: 'ZZ-이상탐지-작업', config });
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', job);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  return job;
}

test.describe('스마트 작업 — 메트릭 0개 이상 탐지 (#362)', () => {
  test('메트릭이 0개면 상세 모니터링 탭이 활성 대신 미동작을 알린다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobWithConfig(page, anomalyConfig([]));

    await page.goto('/ai-insights/jobs/1?tab=monitoring');

    // 이 이슈의 현상 — 폴링 대상이 없는데 '활성'으로 안심시키면 안 된다
    const badge = page.getByTestId('anomaly-idle-badge');
    await expect(badge).toBeVisible();
    await expect(badge).toContainText('미동작');

    // 원인과 해결 방법이 함께 보여야 사용자가 조치할 수 있다
    await expect(page.getByTestId('anomaly-idle-hint')).toContainText('실행되지 않습니다');

    // 모니터링 탭 안에 '활성' 배지가 남아 있으면 신호가 상쇄된다
    await expect(
      page.getByRole('tabpanel').locator('[data-slot="badge"]').filter({ hasText: /^활성$/ }),
    ).toHaveCount(0);
  });

  test('메트릭이 하나라도 있으면 정상적으로 활성으로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobWithConfig(page, anomalyConfig([SYSTEM_METRIC]));

    await page.goto('/ai-insights/jobs/1?tab=monitoring');

    // 정상 구성에 경고가 뜨면 오탐이다 (#354의 ANOMALY 오탐 회귀와 같은 부류)
    await expect(page.getByTestId('anomaly-idle-badge')).toHaveCount(0);
    await expect(page.getByTestId('anomaly-idle-hint')).toHaveCount(0);
    await expect(
      page.getByRole('tabpanel').locator('[data-slot="badge"]').filter({ hasText: /^활성$/ }),
    ).toBeVisible();
    await expect(page.getByText('데이터셋 수')).toBeVisible();
  });

  test('이상 탐지가 꺼져 있으면 메트릭이 없어도 경고하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobWithConfig(page, {
      anomaly: { enabled: false, metrics: [], sensitivity: 'medium', cooldownMinutes: 30 },
    });

    await page.goto('/ai-insights/jobs/1?tab=monitoring');

    await expect(page.getByTestId('anomaly-idle-badge')).toHaveCount(0);
    await expect(
      page.getByRole('tabpanel').locator('[data-slot="badge"]').filter({ hasText: '비활성' }),
    ).toBeVisible();
  });

  test('목록에서도 메트릭 0개 이상 탐지 작업을 구별할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    const idle = createJob({ id: 1, name: 'ZZ-메트릭없음', config: anomalyConfig([]) });
    const healthy = createJob({
      id: 2,
      name: 'ZZ-메트릭있음',
      config: anomalyConfig([SYSTEM_METRIC]),
    });
    // config가 비어 있는 일반 스케줄 작업 — 옵셔널 체이닝 가드가 뚫리면 여기서 목록이 깨진다
    const plain = createJob({ id: 3, name: 'ZZ-일반스케줄', config: {} });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [idle, healthy, plain]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    const idleRow = page.getByRole('row').filter({ hasText: 'ZZ-메트릭없음' });
    await expect(idleRow.getByTestId('anomaly-idle')).toContainText('메트릭 없음');

    // 정상 작업과 일반 스케줄 작업에는 신호가 붙지 않아야 한다
    await expect(
      page.getByRole('row').filter({ hasText: 'ZZ-메트릭있음' }).getByTestId('anomaly-idle'),
    ).toHaveCount(0);
    await expect(
      page.getByRole('row').filter({ hasText: 'ZZ-일반스케줄' }).getByTestId('anomaly-idle'),
    ).toHaveCount(0);

    // 세 행이 모두 살아 있어야 한다 — config 모양이 제각각일 때 목록 전체가
    // 백지가 된 전례가 있다(94fe44b4)
    await expect(page.getByRole('row').filter({ hasText: 'ZZ-' })).toHaveCount(3);
  });

  test('config에 anomaly 키가 없거나 metrics가 배열이 아니어도 목록이 깨지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 방어 대상: 손상되거나 예상 밖 모양의 config가 섞여 들어온 경우
    const noAnomaly = createJob({ id: 1, name: 'ZZ-anomaly없음', config: { channels: [] } });
    const nullMetrics = createJob({
      id: 2,
      name: 'ZZ-metrics널',
      config: { anomaly: { enabled: true } },
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [noAnomaly, nullMetrics]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    await expect(page.getByRole('row').filter({ hasText: 'ZZ-anomaly없음' })).toBeVisible();
    // metrics 자체가 없으면 순회할 대상이 없는 것과 같으므로 미동작으로 취급한다
    await expect(
      page.getByRole('row').filter({ hasText: 'ZZ-metrics널' }).getByTestId('anomaly-idle'),
    ).toBeVisible();
    await expect(
      page.getByRole('row').filter({ hasText: 'ZZ-anomaly없음' }).getByTestId('anomaly-idle'),
    ).toHaveCount(0);
  });
});

// WD-301: 좁은 화면에서 「모니터링 메트릭」 머리 줄이 넘쳐 제목이 한 글자씩 세로로 눌리고 「커스텀 메트릭」이 카드 밖으로 나가던 회귀를 막는다.
test.describe('스마트 작업 모니터링 탭 편집 — 모바일 머리 줄 (WD-301)', () => {
  // 재배치 기준 폭 320px(#345)과 이슈를 재현한 390px 두 폭에서 본다
  for (const width of [320, 390]) {
    test(`${width}px 폭에서 「모니터링 메트릭」 제목이 한 줄이고 추가 버튼·메트릭 행이 카드 안에 있다`, async ({ authenticatedPage: page }) => {
      await mockProactivePermissions(page);
      await page.setViewportSize({ width, height: 844 });
      await setupJobWithConfig(page, anomalyConfig([SYSTEM_METRIC]));

      await page.goto('/ai-insights/jobs/1?tab=monitoring');
      await page.getByRole('button', { name: '편집', exact: true }).click();

      const title = page.getByText('모니터링 메트릭', { exact: true });
      await expect(title).toBeVisible();
      // 한 줄이면 높이가 글자 한 줄(text-sm, leading-none ≈ 14px) 정도다 — 이전에는 세로 일곱 줄(98px)로 눌렸다.
      const titleBox = await title.boundingBox();
      expect(titleBox, '제목 위치').not.toBeNull();
      expect(titleBox!.height).toBeLessThan(24);

      // 제목을 감싼 테두리 카드(가장 안쪽) 안에 추가 묶음과 메트릭 행이 들어와야 한다 — 이전에는 「커스텀 메트릭」 오른쪽이 카드 밖으로 3px 나갔다.
      const card = page.locator('div.rounded-lg.border').filter({ has: title }).last();
      const cardBox = await card.boundingBox();
      expect(cardBox, '카드 위치').not.toBeNull();
      expect(cardBox!.x + cardBox!.width, '카드 오른쪽 끝').toBeLessThanOrEqual(width);
      const targets = {
        '커스텀 메트릭': page.getByRole('button', { name: '커스텀 메트릭' }),
        '시스템 메트릭 추가': page.getByRole('combobox').filter({ hasText: '시스템 메트릭 추가' }),
        '메트릭 행 폴링 주기': page.getByLabel('폴링 주기'),
      };
      for (const [name, target] of Object.entries(targets)) {
        const box = await target.boundingBox();
        expect(box, `${name} 위치`).not.toBeNull();
        expect(box!.x + box!.width, `${name} 오른쪽 끝`).toBeLessThanOrEqual(cardBox!.x + cardBox!.width);
      }

      // 본문(main)이 가로로 스크롤되지 않아야 한다.
      const overflow = await page.locator('main').evaluate((el) => el.scrollWidth - el.clientWidth);
      expect(overflow).toBeLessThanOrEqual(0);
    });
  }
});
