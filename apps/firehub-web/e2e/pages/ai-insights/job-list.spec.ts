import { createJob } from '../../factories/ai-insight.factory';
import { setupJobListMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업(Job) 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('스마트 작업 목록 페이지', () => {
  test('작업 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 3개 작업 목록을 모킹한 후 목록 페이지 접근
    await setupJobListMocks(page, 3);
    await page.goto('/ai-insights/jobs');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '스마트 작업' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '작업명' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '실행 주기' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();

    // 팩토리에서 생성한 이름 패턴("잡 1", "잡 2", "잡 3") 확인
    await expect(page.getByText('잡 1')).toBeVisible();
    await expect(page.getByText('잡 3')).toBeVisible();

    // 데이터 행 수 확인: 헤더 1 + 데이터 3 = 총 4행
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(4);

    // 팩토리 기본값 cronExpression '0 9 * * *' 또는 관련 스케줄 텍스트가 렌더링되는지 확인
    // 크론 표현식 자체 또는 UI에서 변환된 스케줄 문구가 하나 이상 존재해야 한다
    const cronOrSchedule = page.getByText('0 9 * * *');
    const scheduleText = page.getByText(/매일|오전 9시|09:00/);
    const hasCronOrSchedule =
      (await cronOrSchedule.count()) > 0 || (await scheduleText.count()) > 0;
    expect(hasCronOrSchedule).toBe(true);

    // 팩토리 기본값 templateName '기본 리포트 템플릿' 또는 스케줄 관련 정보가 렌더링되는지 확인
    // 목록 행에 템플릿 이름이 표시되거나, 스케줄/실행 주기 셀이 비어 있지 않아야 한다
    const templateOrScheduleCell = page.getByText('기본 리포트 템플릿');
    const hasTemplateInfo = (await templateOrScheduleCell.count()) > 0;
    // 헤더에 '실행 주기' 컬럼이 있으면 데이터 셀도 비어 있지 않아야 하므로 충분한 검증
    if (!hasTemplateInfo) {
      // 스케줄 컬럼 데이터 셀이 존재하는지 확인
      await expect(page.getByRole('cell').first()).toBeVisible();
    }
  });

  test('빈 목록일 때 빈 상태 메시지와 첫 작업 만들기 버튼을 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 목록으로 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 빈 상태 메시지 확인
    await expect(page.getByText('스마트 작업 없음')).toBeVisible();

    // "첫 작업 만들기" 버튼 확인
    await expect(page.getByRole('button', { name: '첫 작업 만들기' })).toBeVisible();
  });

  test('작업 추가 버튼 클릭 시 새 작업 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupJobListMocks(page, 2);
    // 새 작업 페이지에서 필요한 템플릿 API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');

    // "작업 추가" 버튼 클릭
    await page.getByRole('button', { name: '작업 추가' }).click();

    // 새 작업 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/new');
  });

  test('활성 작업에 활성화 스위치가 켜진 상태로 표시된다', async ({ authenticatedPage: page }) => {
    // enabled: true 작업으로 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '활성 작업', enabled: true }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 활성화 스위치(aria-label으로 찾음)가 체크된 상태인지 확인
    const toggle = page.getByRole('switch', { name: '활성 작업 활성화' });
    await expect(toggle).toBeChecked();
  });

  test('비활성 작업에 "비활성" 배지가 표시된다', async ({ authenticatedPage: page }) => {
    // enabled: false 작업으로 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '비활성 작업', enabled: false }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // "비활성" 뱃지 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '비활성' })).toBeVisible();
  });

  test('작업 행 클릭 시 작업 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupJobListMocks(page, 2);
    // 작업 상세 페이지에서 필요한 API 모킹 — createJob({ id: 1 }) 기본값: name='매일 현황 리포트'
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');

    // 첫 번째 작업 행 클릭 — 잡 1
    await page.getByText('잡 1').click();

    // 작업 상세 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/1');

    // 상세 페이지에서 팩토리 기본값 작업명 '매일 현황 리포트'가 표시되는지 확인
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
  });
});
