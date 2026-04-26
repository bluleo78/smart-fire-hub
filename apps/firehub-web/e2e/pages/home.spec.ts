import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 홈 페이지 E2E 테스트
 * - authenticatedPage fixture: setupAuthMocks + setupHomeMocks(기본 대시보드 모킹) 포함
 * - 추가 모킹이 필요한 경우 goto('/') 전에 mockApi로 해당 엔드포인트를 오버라이드한다
 * - mockApi는 pathname 정확 매칭 — 동일 경로를 재모킹하면 마지막 모킹이 우선 적용된다
 */

test.describe('홈 페이지', () => {
  /**
   * 테스트 1: 기본 렌더링
   * - 로그인 후 홈('/')으로 이동하여 핵심 UI 요소가 표시되는지 확인한다
   */
  test('홈 페이지 기본 요소가 렌더링된다', async ({ authenticatedPage: page }) => {
    // authenticatedPage는 이미 로그인 + setupHomeMocks가 완료된 상태이므로
    // '/'로 이동하면 대시보드가 정상 로드된다
    await page.goto('/');

    // 페이지 헤더 — 제목과 환영 메시지 확인
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();
    await expect(page.getByText('환영합니다, 테스트 사용자님!')).toBeVisible();

    // 시스템 건강 상태바 — 파이프라인 섹션 (건강 상태바 버튼의 레이블)
    await expect(page.getByRole('button', { name: /파이프라인 .* 실패/ })).toBeVisible();

    // 시스템 건강 상태바 — 데이터셋 섹션 (건강 상태바 버튼의 레이블)
    await expect(page.getByRole('button', { name: /데이터셋 .* 최신/ })).toBeVisible();

    // 퀵 액션 버튼 4개 존재 확인
    await expect(page.getByRole('button', { name: /새 데이터셋/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /파이프라인 목록/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /SQL 편집기/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /대시보드 관리/ })).toBeVisible();

    // 활동 피드 카드 제목 확인
    await expect(page.getByText('활동 피드')).toBeVisible();

    // 인라인 통계 — setupHomeMocks 기본값: analytics/dashboards totalElements=0 → '대시보드 0'
    // 건강 상태바 하단 퀵 카운트 영역에 대시보드 수가 표시된다
    await expect(page.getByText(/대시보드\s*0/)).toBeVisible();
  });

  /**
   * 테스트 2: 시스템 건강 상태 수치 표시
   * - setupHomeMocks의 기본 모킹(failing:1, stale:1, empty:1)을 사용하여
   *   파이프라인/데이터셋 건강 수치가 올바르게 렌더링되는지 확인한다
   */
  test('시스템 건강 상태 수치가 표시된다', async ({ authenticatedPage: page }) => {
    // 기본 모킹: pipelineHealth { total:5, healthy:3, failing:1, running:0, disabled:1 }
    //           datasetHealth { total:10, fresh:8, stale:1, empty:1 }
    await page.goto('/');

    // 파이프라인 — 실패 1건, 정상 3건, 비활성 1건
    await expect(page.getByText('1 실패')).toBeVisible();
    await expect(page.getByText('3 정상')).toBeVisible();
    await expect(page.getByText('1 비활성')).toBeVisible();

    // 데이터셋 — 최신 8건, 오래됨 1건, 빈 데이터 1건
    await expect(page.getByText('8 최신')).toBeVisible();
    await expect(page.getByText('1 오래됨')).toBeVisible();
    await expect(page.getByText('1 빈 데이터')).toBeVisible();
  });

  /**
   * 테스트 3: 주의 항목이 있을 때 주의 섹션 표시
   * - attention API를 주의 항목 포함으로 오버라이드 모킹한 뒤
   *   "주의 필요" 카드와 항목 내용이 렌더링되는지 확인한다
   */
  test('주의 항목이 있으면 주의 필요 섹션이 표시된다', async ({ authenticatedPage: page }) => {
    // 기본 모킹(빈 배열)을 CRITICAL + WARNING 항목으로 오버라이드
    await mockApi(page, 'GET', '/api/v1/dashboard/attention', [
      {
        entityType: 'PIPELINE',
        entityId: 10,
        severity: 'CRITICAL',
        title: '파이프라인 실행 실패',
        description: '3회 연속 실패하였습니다.',
      },
      {
        entityType: 'DATASET',
        entityId: 20,
        severity: 'WARNING',
        title: '데이터셋 데이터 없음',
        description: '마지막 업데이트로부터 7일이 지났습니다.',
      },
    ]);

    await page.goto('/');

    // "주의 필요" 카드 제목 확인
    await expect(page.getByText('주의 필요')).toBeVisible();

    // 항목 개수 배지(2) 확인
    await expect(page.getByText('2')).toBeVisible();

    // CRITICAL 항목 — 긴급 뱃지, 제목, 설명
    await expect(page.getByText('파이프라인 실행 실패')).toBeVisible();
    await expect(page.getByText('긴급')).toBeVisible();
    // description 필드가 항목 카드 하단에 렌더링된다
    await expect(page.getByText('3회 연속 실패하였습니다.')).toBeVisible();

    // WARNING 항목 — 경고 뱃지, 제목, 설명
    await expect(page.getByText('데이터셋 데이터 없음')).toBeVisible();
    await expect(page.getByText('경고')).toBeVisible();
    await expect(page.getByText('마지막 업데이트로부터 7일이 지났습니다.')).toBeVisible();
  });

  /**
   * 테스트 4: 주의 항목이 없을 때 주의 섹션 숨김 + 모든 시스템 정상 표시
   * - 기본 모킹의 attention=[], pipelineHealth.failing=0, datasetHealth.stale=0, empty=0
   *   조건에서 "주의 필요" 섹션이 없고 "모든 시스템 정상"이 표시되는지 확인한다
   */
  test('주의 항목이 없으면 주의 필요 섹션이 숨겨진다', async ({ authenticatedPage: page }) => {
    // 건강 상태를 완전 정상으로 오버라이드 (failing/stale/empty = 0)
    await mockApi(page, 'GET', '/api/v1/dashboard/health', {
      pipelineHealth: { total: 5, healthy: 5, failing: 0, running: 0, disabled: 0 },
      datasetHealth: { total: 10, fresh: 10, stale: 0, empty: 0 },
    });
    // attention 빈 배열 — 기본 모킹과 동일하지만 명시적으로 재확인
    await mockApi(page, 'GET', '/api/v1/dashboard/attention', []);

    await page.goto('/');

    // "주의 필요" 카드가 없어야 한다
    await expect(page.getByText('주의 필요')).not.toBeVisible();

    // "모든 시스템 정상" 메시지가 표시된다
    await expect(page.getByText('모든 시스템 정상')).toBeVisible();
  });

  /**
   * 테스트 5: 빠른 액션 버튼 클릭 시 올바른 페이지로 이동
   * - 각 퀵 액션 버튼을 클릭하면 지정된 URL로 이동하는지 확인한다
   */
  test('퀵 액션 버튼 클릭 시 올바른 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    // "새 데이터셋" → /data/datasets/new
    await page.getByRole('button', { name: /새 데이터셋/ }).click();
    await expect(page).toHaveURL('/data/datasets/new');

    // 뒤로 돌아가서 다음 버튼 검사
    await page.goBack();
    await page.waitForURL('/');

    // "파이프라인 목록" → /pipelines
    await page.getByRole('button', { name: /파이프라인 목록/ }).click();
    await expect(page).toHaveURL('/pipelines');

    await page.goBack();
    await page.waitForURL('/');

    // "SQL 편집기" → /analytics/queries
    await page.getByRole('button', { name: /SQL 편집기/ }).click();
    await expect(page).toHaveURL('/analytics/queries');

    await page.goBack();
    await page.waitForURL('/');

    // "대시보드 관리" → /analytics/dashboards
    await page.getByRole('button', { name: /대시보드 관리/ }).click();
    await expect(page).toHaveURL('/analytics/dashboards');
  });

  /**
   * 테스트 6: 활동 피드 항목 렌더링
   * - activity API를 실제 항목으로 오버라이드하여
   *   활동 내역이 피드에 올바르게 표시되는지 확인한다
   */
  test('활동 피드에 항목이 있으면 목록이 렌더링된다', async ({ authenticatedPage: page }) => {
    // 활동 피드 데이터를 항목 포함으로 오버라이드
    await mockApi(page, 'GET', '/api/v1/dashboard/activity', {
      items: [
        {
          id: 1,
          eventType: 'PIPELINE_SUCCESS',
          title: '파이프라인 실행 성공',
          description: '소방 데이터 수집 파이프라인이 완료됐습니다.',
          occurredAt: '2026-04-06T10:00:00',
          isResolved: true,
        },
        {
          id: 2,
          eventType: 'PIPELINE_FAIL',
          title: '파이프라인 실행 실패',
          description: '기상 데이터 변환 파이프라인이 실패했습니다.',
          occurredAt: '2026-04-06T09:00:00',
          isResolved: false,
        },
      ],
      totalCount: 2,
      hasMore: false,
    });

    await page.goto('/');

    // 활동 피드 카드 제목 확인
    await expect(page.getByText('활동 피드')).toBeVisible();

    // 총 건수 확인
    await expect(page.getByText('총 2건')).toBeVisible();

    // 성공 항목 확인 + 설명 텍스트
    await expect(page.getByText('파이프라인 실행 성공')).toBeVisible();
    // description 필드가 제목 하단에 truncate 스타일로 렌더링된다
    await expect(page.getByText('소방 데이터 수집 파이프라인이 완료됐습니다.')).toBeVisible();

    // 실패 항목 확인 + 미해결 뱃지
    await expect(page.getByText('파이프라인 실행 실패')).toBeVisible();
    await expect(page.getByText('미해결')).toBeVisible();
  });

  /**
   * 테스트 7: 활동 피드 빈 상태
   * - 활동 항목이 없을 때 빈 상태 메시지가 표시되는지 확인한다
   */
  test('활동 내역이 없으면 빈 상태 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    // 기본 모킹이 이미 items:[]이므로 추가 모킹 없이 goto만 호출
    await page.goto('/');

    // 빈 상태 텍스트 확인
    await expect(page.getByText('활동 내역이 없습니다.')).toBeVisible();

    // "파이프라인 실행하기" CTA 버튼 확인
    await expect(page.getByRole('button', { name: '파이프라인 실행하기' })).toBeVisible();
  });

  /**
   * 테스트 8: 최근 대시보드/데이터셋 목록 렌더링
   * - 대시보드와 데이터셋 목록 API를 실제 항목으로 오버라이드하여
   *   "최근 대시보드", "최근 데이터셋" 카드에 항목이 표시되는지 확인한다
   */
  /**
   * 테스트 9: 모바일(375px) 뷰포트에서 통계 카드 라벨이 글자 단위로 깨지지 않는다
   * - 이슈 #60: 한글 라벨이 글자 단위 wrap되어 "데이터 셋", "빈 데이 터" 처럼 깨짐
   * - 수정: 라벨 span에 whitespace-nowrap, 컨테이너에 word-break:keep-all 적용
   * - 검증: 라벨이 단일 행으로 표시되어야 함 (라벨의 height가 1줄 높이여야 함)
   */
  test('모바일 뷰포트(375px)에서 통계 카드 라벨이 단어 보존 wrap된다', async ({ authenticatedPage: page }) => {
    // 모바일 뷰포트로 변경
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');

    // 핵심 라벨이 보이는지 확인 (한글 단어가 깨지면 부분 매치 실패)
    const labels = ['파이프라인', '데이터셋', '1 빈 데이터', '1 오래됨', '8 최신'];
    for (const label of labels) {
      const el = page.getByText(label, { exact: true }).first();
      await expect(el).toBeVisible();
    }

    // 단어 단위 wrap 검증: 각 라벨 span의 computed style이 nowrap 또는 keep-all
    // 컨테이너에 [word-break:keep-all]이 적용되어 라벨이 한 줄로 유지되어야 함
    const dataSetLabel = page.getByText('데이터셋', { exact: true }).first();
    const labelBox = await dataSetLabel.boundingBox();
    // 한글 4글자 라벨의 폰트 크기는 14px (text-sm), 한 줄 높이 ~20px 이내
    // 글자 단위로 깨지면 2줄 → 36px 이상이 되므로 30px 미만이어야 한 줄임을 보장
    expect(labelBox?.height).toBeLessThan(30);

    const emptyLabel = page.getByText('1 빈 데이터', { exact: true }).first();
    const emptyBox = await emptyLabel.boundingBox();
    expect(emptyBox?.height).toBeLessThan(30);
  });

  test('최근 대시보드와 데이터셋이 카드에 표시된다', async ({ authenticatedPage: page }) => {
    // 분석 대시보드 목록 오버라이드
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', {
      content: [
        { id: 1, name: '소방 현황 대시보드', widgetCount: 5 },
        { id: 2, name: '기상 분석 대시보드', widgetCount: 3 },
      ],
      page: 0,
      size: 5,
      totalElements: 2,
      totalPages: 1,
    });

    // 데이터셋 목록 오버라이드
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [
        {
          id: 1,
          name: '소방서 위치 데이터',
          datasetType: 'SOURCE',
          createdAt: '2026-04-01T00:00:00',
        },
        {
          id: 2,
          name: '화재 발생 통계',
          datasetType: 'DERIVED',
          createdAt: '2026-04-02T00:00:00',
        },
      ],
      page: 0,
      size: 5,
      totalElements: 2,
      totalPages: 1,
    });

    await page.goto('/');

    // 최근 대시보드 카드 — 항목 이름 확인
    await expect(page.getByText('소방 현황 대시보드')).toBeVisible();
    await expect(page.getByText('기상 분석 대시보드')).toBeVisible();
    // 두 대시보드의 위젯 수가 각각 표시된다
    await expect(page.getByText('위젯 5개')).toBeVisible();
    await expect(page.getByText('위젯 3개')).toBeVisible();

    // 최근 데이터셋 카드 — 항목 이름 확인
    await expect(page.getByText('소방서 위치 데이터')).toBeVisible();
    await expect(page.getByText('화재 발생 통계')).toBeVisible();
    // 데이터셋 유형 뱃지 확인
    await expect(page.getByText('소스').first()).toBeVisible();
    await expect(page.getByText('파생').first()).toBeVisible();
  });
});
