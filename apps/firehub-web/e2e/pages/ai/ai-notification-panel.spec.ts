import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * AINotificationPanel (상단 종 아이콘 알림 패널) E2E 테스트
 * - AIStatusChip 의 Bell 버튼 → AINotificationPanel 렌더링 전체 흐름 커버.
 * - useProactiveMessages/useMarkAllAsRead/useMarkAsRead 훅 경로 함께 커버된다.
 *
 * 주의:
 * - authenticatedPage 는 로그인 후 '/' 홈에 진입한 상태로 제공된다.
 * - page.reload() 는 in-memory access token 을 잃어버리므로 사용하지 않는다.
 * - 대신 테스트 시작 전 mockApi 로 /proactive/messages 를 미리 등록한 뒤
 *   홈 페이지 내에서 종 아이콘을 눌러 패널을 연다. (unreadCount 는 0이어도 상관없음)
 */
test.describe('AI 인사이트 알림 패널', () => {
  /** 프로액티브 메시지 목록 모킹 데이터 — read/unread 혼합 */
  const messages = [
    {
      id: 1,
      userId: 1,
      executionId: 100,
      jobName: '일일 데이터 품질 리포트',
      title: '데이터 품질 이상 감지',
      content: {
        summary: '어제 대비 NULL 값이 25% 증가했습니다. 확인이 필요합니다.',
      },
      messageType: 'PROACTIVE_INSIGHT',
      read: false,
      createdAt: new Date(Date.now() - 3600_000).toISOString(),
    },
    {
      id: 2,
      userId: 1,
      executionId: 101,
      jobName: '주간 요약',
      title: '주간 데이터 요약 리포트',
      content: { summary: '금주 데이터셋 업로드 12건이 완료되었습니다.' },
      messageType: 'PROACTIVE_INSIGHT',
      read: true,
      createdAt: new Date(Date.now() - 86400_000).toISOString(),
    },
  ];

  /** 종 아이콘(Bell) 버튼 locator — 아이콘 전용이라 accessible name 에 'AI 인사이트' 포함 */
  const bellSelector = /AI 인사이트 알림|안 읽은 AI 인사이트/;

  test('종 아이콘 클릭 시 알림 목록이 표시된다', async ({ authenticatedPage: page }) => {
    // 패널 open 시 호출되는 messages API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/messages', messages);

    // 헤더의 종 아이콘 클릭 — AIStatusChip 내부의 Bell 버튼
    await page.getByRole('button', { name: bellSelector }).first().click();

    // 패널 다이얼로그 렌더링 확인 (role=dialog, aria-label='AI 인사이트 알림')
    const dialog = page.getByRole('dialog', { name: 'AI 인사이트 알림' });
    await expect(dialog).toBeVisible();

    // 메시지 목록에 두 제목이 모두 표시
    await expect(page.getByText('데이터 품질 이상 감지')).toBeVisible();
    await expect(page.getByText('주간 데이터 요약 리포트')).toBeVisible();
  });

  test('알림 상세 → 뒤로 가기 흐름이 동작한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages', messages);
    // 읽음 처리 API — 클릭 시 호출
    await mockApi(page, 'PUT', '/api/v1/proactive/messages/1/read', {});

    await page.getByRole('button', { name: bellSelector }).first().click();
    await expect(page.getByText('데이터 품질 이상 감지')).toBeVisible();

    // 첫 번째 메시지 항목 클릭 → DetailView 로 이동
    await page.getByRole('button', { name: /데이터 품질 이상 감지/ }).click();

    // DetailView 의 '목록으로 돌아가기' 버튼이 노출되어야 함
    const backBtn = page.getByRole('button', { name: '목록으로 돌아가기' });
    await expect(backBtn).toBeVisible();

    // AI에게 물어보기 버튼도 DetailView 푸터에 렌더링됨
    await expect(page.getByRole('button', { name: /AI에게 물어보기/ })).toBeVisible();

    // 뒤로 가기 → 목록 복귀
    await backBtn.click();
    await expect(page.getByText('주간 데이터 요약 리포트')).toBeVisible();
  });

  test('전체 읽음 버튼 클릭 시 markAllAsRead API 가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages', messages);
    const markAllCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/proactive/messages/read-all',
      {},
      { capture: true },
    );

    await page.getByRole('button', { name: bellSelector }).first().click();
    // 목록 데이터가 로드된 뒤 '전체 읽음' 버튼이 렌더링됨 (unreadCount > 0 조건)
    await expect(page.getByText('데이터 품질 이상 감지')).toBeVisible();

    await page.getByRole('button', { name: '전체 읽음 처리' }).click();

    // markAllAsRead API 가 실제로 호출되었는지 검증
    const req = await markAllCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/proactive/messages/read-all');
  });

  test('알림이 없으면 빈 상태 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages', []);

    await page.getByRole('button', { name: bellSelector }).first().click();

    // EmptyState 문구
    await expect(page.getByText('새 알림이 없습니다')).toBeVisible();
    await expect(
      page.getByText('AI 스마트 작업이 완료되면 여기에 표시됩니다'),
    ).toBeVisible();
  });
});
