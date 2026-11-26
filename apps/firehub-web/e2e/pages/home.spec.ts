import type { Page } from '@playwright/test';

import { createActivityFeedResponse, createActivityItem } from '../factories/dashboard.factory';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { DATASET_WRITE_PERMISSIONS, grantDatasetPermissions } from '../fixtures/dataset.fixture';
import { holdApi } from '../fixtures/hold-api';
import { grantPipelinePermissions } from '../fixtures/pipeline.fixture';

/** 홈의 파이프라인 요약·바로가기를 보는 권한(WD-272) — 조회 + 「파이프라인 실행하기」 빈 상태 버튼용 실행 */
const HOME_PIPELINE_PERMISSIONS = ['pipeline:read', 'pipeline:execute'];

/**
 * 홈 페이지 E2E 테스트
 * - authenticatedPage fixture: setupAuthMocks + setupHomeMocks(기본 대시보드 모킹) 포함
 * - 추가 모킹이 필요한 경우 goto('/') 전에 mockApi로 해당 엔드포인트를 오버라이드한다
 * - mockApi는 pathname 정확 매칭 — 동일 경로를 재모킹하면 마지막 모킹이 우선 적용된다
 */

test.describe('홈 페이지', () => {
  // 파이프라인 요약·바로가기·필터는 pipeline:read 가 있을 때만 보인다(WD-272) — 이 describe 는 그것들을 검증하므로 권한을 준다.
  // 권한이 없는 경우는 아래 「홈 — 파이프라인 조회 권한 없음」 describe 가 검증한다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page, HOME_PIPELINE_PERMISSIONS);
  });

  /**
   * 테스트 1: 기본 렌더링
   * - 로그인 후 홈('/')으로 이동하여 핵심 UI 요소가 표시되는지 확인한다
   */
  test('홈 페이지 기본 요소가 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // 「새 데이터셋」 퀵 액션은 생성 권한(dataset:write)이 있을 때만 보인다(WD-144)
    await grantDatasetPermissions(page, [...DATASET_WRITE_PERMISSIONS, ...HOME_PIPELINE_PERMISSIONS]);
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

    // 건강 상태바 대시보드 지표 버튼 — setupHomeMocks 기본값: analytics/dashboards totalElements=0 → '대시보드 0개'
    await expect(
      page.getByRole('region', { name: '시스템 건강 상태' }).getByRole('button', { name: '대시보드 0개', exact: true }),
    ).toBeVisible();
  });

  /**
   * WD-898: 건강 상태바의 대시보드 지표도 파이프라인·데이터셋처럼 눌러서 이동한다.
   */
  test('건강 상태바 대시보드 지표를 누르면 대시보드 목록으로 이동한다 (WD-898)', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    // 퀵 액션 「대시보드 관리」와 겹치지 않게 건강 상태 영역 안에서만 찾는다.
    // 접근 이름은 aria-label(「대시보드 <n>개」)로 고정돼 화면 글자 사이 공백에 기대지 않는다(WD-898 리뷰 후속)
    await page
      .getByRole('region', { name: '시스템 건강 상태' })
      .getByRole('button', { name: '대시보드 0개', exact: true })
      .click();
    await expect(page).toHaveURL(/\/analytics\/dashboards$/);
  });

  test('건강 상태바 대시보드 지표는 키보드 Enter 로도 이동하고 조회 실패여도 눌린다 (WD-898)', async ({
    authenticatedPage: page,
  }) => {
    // 대시보드 목록 조회가 실패해도(수치 「—」) 이동 버튼은 살아 있어야 한다
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', { message: '서버 오류' }, { status: 500 });
    await page.goto('/');
    const button = page
      .getByRole('region', { name: '시스템 건강 상태' })
      .getByRole('button', { name: '대시보드 — 불러오지 못함', exact: true });
    await expect(button).toContainText('—', { timeout: 10000 });
    await button.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/analytics\/dashboards$/);
  });

  /**
   * WD-898 리뷰 후속: 대시보드 목록을 받기 전(첫 로딩)에는 「0개」(빈 상태 모양) 대신 로딩 표시를 보인다.
   * 건강 상태 줄은 health 만 기다려 먼저 그려지므로, 대시보드가 있는 사용자도 잠깐 「0개」를 봤다가 실제 수로 바뀌었다.
   */
  test('건강 상태바 대시보드 수치는 목록을 받기 전엔 0개 대신 로딩 표시다 (WD-898)', async ({
    authenticatedPage: page,
  }) => {
    // 대시보드 목록 응답을 붙잡아 두었다가 확인 뒤에 풀어 준다 — 시간 지연보다 확정적이다
    const { release } = await holdApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      200,
      createPageResponse([], { totalElements: 12 }),
    );
    await page.goto('/');
    const region = page.getByRole('region', { name: '시스템 건강 상태' });
    try {
      const loadingButton = region.getByRole('button', { name: '대시보드 — 불러오는 중', exact: true });
      await expect(loadingButton).toBeVisible();
      await expect(loadingButton).toHaveAttribute('aria-busy', 'true');
      await expect(loadingButton.getByTestId('dashboard-count-skeleton')).toBeVisible();
      // 응답을 붙잡아 둔 동안에는 「0개」 글자가 없다 — 되돌아가면 이 동안 내내 「0개」가 보여 여기서 걸린다
      await expect(loadingButton).not.toContainText('0개');
    } finally {
      // 단언이 실패해도 붙잡은 요청을 풀어 정리 단계에서 걸려 있지 않게 한다
      release();
    }
    // 응답을 받으면 실제 수(12개)로 바뀌고 로딩 표시는 사라진다
    const loadedButton = region.getByRole('button', { name: '대시보드 12개', exact: true });
    await expect(loadedButton).toHaveText(/12개/);
    await expect(loadedButton).toHaveAttribute('aria-busy', 'false');
    await expect(region.getByTestId('dashboard-count-skeleton')).toHaveCount(0);
  });

  test('건강 상태바 세 지표는 아이콘 16px·높이 32 이상이다 (WD-898)', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    const region = page.getByRole('region', { name: '시스템 건강 상태' });
    // 대시보드 수치에도 다른 두 지표처럼 단위 「개」가 붙는다
    await expect(region.getByRole('button', { name: '대시보드 0개', exact: true })).toBeVisible();
    const names = [/^파이프라인/, /^데이터셋/, /^대시보드 \d+개$/];

    /** 세 지표 버튼의 높이(≥32)와 첫 아이콘 크기(16×16)를 단언한다 */
    const expectUniformMetrics = async () => {
      for (const name of names) {
        const button = region.getByRole('button', { name });
        await expect(button).toBeVisible();
        const box = await button.boundingBox();
        expect(box?.height ?? 0).toBeGreaterThanOrEqual(32);
        const iconSize = await button.locator('svg').first().evaluate((el) => {
          const r = el.getBoundingClientRect();
          return { w: r.width, h: r.height };
        });
        expect(iconSize.w).toBeCloseTo(16, 0);
        expect(iconSize.h).toBeCloseTo(16, 0);
      }
    };

    await expectUniformMetrics();
    await page.setViewportSize({ width: 390, height: 844 });
    await expectUniformMetrics();
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
   * 테스트 2.5: 스파크라인이 실제 health API의 trend 데이터를 반영한다 (#669 회귀 방지)
   * - 이전에는 [3,5,2,8,4,6,9] / [8,10,6,4,7,9,5]가 하드코딩되어 어떤 API 응답을 줘도 그대로였다.
   * - health API를 서로 다른 trend 배열로 두 번 모킹하여, 두 번째 응답의 막대 개수/높이가
   *   첫 번째와 달라지는지 — 즉 실제로 API 응답을 그려내는지 검증한다.
   */
  test('스파크라인이 health API의 trend 값을 그대로 반영한다', async ({ authenticatedPage: page }) => {
    // 최댓값이 명확한 trend로 오버라이드: [1,1,1,1,1,1,10] → 마지막 막대만 100% 높이
    await mockApi(page, 'GET', '/api/v1/dashboard/health', {
      pipelineHealth: { total: 5, healthy: 3, failing: 1, running: 0, disabled: 1, trend: [1, 1, 1, 1, 1, 1, 10] },
      datasetHealth: { total: 10, fresh: 8, stale: 1, empty: 1, trend: [5, 5, 5, 5, 5, 5, 5] },
    });

    await page.goto('/');

    const pipelineSparkline = page.getByTestId('pipeline-sparkline');
    const datasetSparkline = page.getByTestId('dataset-sparkline');

    // 막대 개수 = trend 배열 길이(7) — 고정 배열이었다면 항상 동일한 개수/모양이었을 것
    await expect(pipelineSparkline.locator('> div')).toHaveCount(7);
    await expect(datasetSparkline.locator('> div')).toHaveCount(7);

    // 파이프라인 trend의 마지막 값(10)이 최댓값이므로 마지막 막대 높이는 100%
    const lastBarHeight = await pipelineSparkline.locator('> div').last().evaluate(
      (el) => (el as HTMLElement).style.height,
    );
    expect(lastBarHeight).toBe('100%');

    // 첫 막대(값 1)의 높이는 10% ((1/10)*100)
    const firstBarHeight = await pipelineSparkline.locator('> div').first().evaluate(
      (el) => (el as HTMLElement).style.height,
    );
    expect(firstBarHeight).toBe('10%');

    // 데이터셋 trend는 모든 값이 동일(5) → 모든 막대 높이가 100%로 동일해야 함
    const datasetBarHeights = await datasetSparkline.locator('> div').evaluateAll(
      (els) => els.map((el) => (el as HTMLElement).style.height),
    );
    expect(datasetBarHeights).toEqual(new Array(7).fill('100%'));
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
      pipelineHealth: { total: 5, healthy: 5, failing: 0, running: 0, disabled: 0, trend: [1, 1, 2, 1, 2, 1, 1] },
      datasetHealth: { total: 10, fresh: 10, stale: 0, empty: 0, trend: [1, 2, 1, 1, 2, 1, 2] },
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
    // 「새 데이터셋」 퀵 액션·생성 화면 진입은 생성 권한(dataset:write)이 필요하다(WD-144)
    await grantDatasetPermissions(page, [...DATASET_WRITE_PERMISSIONS, ...HOME_PIPELINE_PERMISSIONS]);
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

  /**
   * WD-574: 320px 폭(브라우저 400% 확대, WCAG 1.4.10 Reflow)에서 상단 요약 줄이 main 밖으로 넘치지 않는다.
   * - 요약 버튼 안 항목이 모두 whitespace-nowrap 이고 버튼이 줄을 바꾸지 않아, 숫자가 크고 항목이 모두 보이면
   *   데이터셋 요약이 main 폭을 넘어 main 안에 가로 스크롤이 생겼다.
   * - 파이프라인 네 상태·데이터셋 세 상태를 모두 세 자리 수로 채워 가장 긴 요약을 만든다.
   */
  test('320px 폭에서 시스템 건강 요약 줄이 줄을 바꿔 main 에 가로 스크롤이 생기지 않는다 (WD-574)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dashboard/health', {
      pipelineHealth: { total: 480, healthy: 120, failing: 120, running: 120, disabled: 120, trend: [1, 2, 3, 4, 5, 6, 7] },
      datasetHealth: { total: 375, fresh: 124, stale: 124, empty: 127, trend: [1, 2, 3, 4, 5, 6, 7] },
    });
    await page.setViewportSize({ width: 320, height: 640 });
    await page.goto('/');

    // 요약이 다 그려진 뒤 잰다 — 가장 긴 항목들이 보여야 넘침 조건이 만들어진다.
    await expect(page.getByText('127 빈 데이터', { exact: true })).toBeVisible();
    await expect(page.getByText('120 비활성', { exact: true })).toBeVisible();

    const main = page.locator('main');
    const size = await main.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }));
    expect(size.scroll).toBeLessThanOrEqual(size.client);

    // 요약 버튼 오른쪽 끝이 main 안에 있다 — 넘친 요소를 직접 확인한다.
    const mainBox = await main.boundingBox();
    for (const name of [/파이프라인/, /데이터셋/]) {
      const box = await page
        .getByRole('region', { name: '시스템 건강 상태' })
        .getByRole('button', { name })
        .first()
        .boundingBox();
      expect(box!.x + box!.width).toBeLessThanOrEqual(mainBox!.x + mainBox!.width);
    }

    // 낱말은 여전히 한 덩어리 — 「빈 데이터」가 글자 단위로 깨지지 않는다(이슈 #60 회귀 방지).
    const emptyBox = await page.getByText('127 빈 데이터', { exact: true }).boundingBox();
    expect(emptyBox!.height).toBeLessThan(30);
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
          storageType: 'TABLE',
          originType: 'SOURCE',
          createdAt: '2026-04-01T00:00:00',
        },
        {
          id: 2,
          name: '화재 발생 통계',
          storageType: 'TABLE',
          originType: 'DERIVED',
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
    // 데이터셋 유형 뱃지 확인 — storageType(TABLE)+originType(SOURCE/DERIVED) 기반 라벨
    await expect(page.getByText('원본').first()).toBeVisible();
    await expect(page.getByText('파생').first()).toBeVisible();
  });

  /**
   * 회귀: WD-874 — 홈 「최근 데이터셋」 카드가 정렬 인자 없이 요청해 서버 기본(id 오름차순)대로 가장 오래된 5건을 보여 줬다.
   * 이제 sortBy=createdAt·sortDir=desc·size=5 로 요청하고, 응답 순서(최신 → 오래된 순) 그대로 그린다.
   */
  test('최근 데이터셋 카드는 최신 순으로 요청하고 응답 순서대로 그린다 (WD-874)', async ({
    authenticatedPage: page,
  }) => {
    // mockApi 는 pathname 만 매칭하므로, 실제 요청 URL 의 쿼리를 직접 가로채 기록한다.
    const requestedUrls: URL[] = [];
    const names = ['가장 최신', '두 번째', '세 번째', '네 번째', '다섯 번째'];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      async (route) => {
        requestedUrls.push(new URL(route.request().url()));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content: names.map((name, i) => ({
              id: 100 - i,
              name,
              storageType: 'TABLE',
              originType: 'SOURCE',
              createdAt: `2026-04-0${5 - i}T00:00:00`,
            })),
            page: 0,
            size: 5,
            totalElements: 9,
            totalPages: 2,
          }),
        });
      },
    );

    await page.goto('/');

    const card = page
      .locator('[class*="card-hover"]')
      .filter({ has: page.getByRole('heading', { name: '최근 데이터셋' }) });
    await expect(card.getByText('가장 최신')).toBeVisible();

    // 카드 안 항목 이름이 응답 순서(최신 → 오래된 순)와 같다
    await expect(card.locator('button span.truncate')).toHaveText(names);

    // 요청이 최신 순·5건 인자를 실었다
    expect(requestedUrls.length).toBeGreaterThan(0);
    const params = requestedUrls[0].searchParams;
    expect(params.get('sortBy')).toBe('createdAt');
    expect(params.get('sortDir')).toBe('desc');
    expect(params.get('size')).toBe('5');
  });

  /**
   * 회귀: WD-296 — 파이프라인이 자동 생성한 임시(TEMP) 데이터셋은 「원본」이 아니라 「임시」로 보인다(데이터셋 목록과 같은 라벨).
   * 예전에는 DERIVED 밖을 모두 「원본」으로 표시해, 홈만 보면 사용자가 올린 원본 데이터로 오해했다.
   */
  test('최근 데이터셋 — 임시 데이터셋은 「임시」, 원본·파생·문서는 각자 라벨로 보인다 (WD-296)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [
        { id: 11, name: '급여 집계 > s1 (자동생성)', storageType: 'TABLE', originType: 'TEMP', createdAt: '2026-04-03T00:00:00' },
        { id: 12, name: '소방서 위치 데이터', storageType: 'TABLE', originType: 'SOURCE', createdAt: '2026-04-02T00:00:00' },
        { id: 13, name: '화재 발생 통계', storageType: 'TABLE', originType: 'DERIVED', createdAt: '2026-04-01T00:00:00' },
        { id: 14, name: '점검 보고서', storageType: 'DOCUMENT', originType: 'SOURCE', createdAt: '2026-03-31T00:00:00' },
      ],
      page: 0,
      size: 5,
      totalElements: 4,
      totalPages: 1,
    });

    await page.goto('/');

    const row = (name: string) => page.getByRole('button').filter({ hasText: name });
    await expect(row('급여 집계 > s1 (자동생성)')).toContainText('임시');
    await expect(row('급여 집계 > s1 (자동생성)')).not.toContainText('원본');
    await expect(row('소방서 위치 데이터')).toContainText('원본');
    await expect(row('화재 발생 통계')).toContainText('파생');
    await expect(row('점검 보고서')).toContainText('문서');
  });

  /**
   * 회귀: WD-760 — 배지가 DOCUMENT 만 골라내 파일(FILE) 데이터셋이 「원본」 같은 출처 라벨로 보여 테이블과 구분되지 않았다.
   * 테이블이 아닌 저장 방식은 저장 방식 라벨(파일), 테이블은 지금처럼 출처 라벨로 보인다.
   */
  test('최근 데이터셋 — 파일 데이터셋은 출처 대신 「파일」로 보인다 (WD-760)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [
        { id: 31, name: '현장 사진 묶음', storageType: 'FILE', originType: 'SOURCE', createdAt: '2026-04-03T00:00:00' },
        { id: 32, name: '소방서 위치 데이터', storageType: 'TABLE', originType: 'SOURCE', createdAt: '2026-04-02T00:00:00' },
      ],
      page: 0,
      size: 5,
      totalElements: 2,
      totalPages: 1,
    });

    await page.goto('/');

    const row = (name: string) => page.getByRole('button').filter({ hasText: name });
    await expect(row('현장 사진 묶음')).toContainText('파일');
    await expect(row('현장 사진 묶음')).not.toContainText('원본');
    // 테이블은 지금처럼 출처 라벨
    await expect(row('소방서 위치 데이터')).toContainText('원본');
  });

  /**
   * 회귀: WD-332 — 모바일(390px)에서 최근 데이터셋에 긴 이름이 있으면 그리드 칸이 내용 최소 너비만큼 늘어나,
   * 최근 대시보드·최근 데이터셋 카드가 화면보다 넓어지고 「전체 보기」·배지가 오른쪽에서 잘렸다.
   * 카드는 화면 폭 안에 있고, 긴 이름은 말줄임되며, 본문이 가로로 스크롤되지 않아야 한다.
   */
  test('모바일 — 긴 데이터셋 이름이 있어도 최근 사용 카드가 화면 폭을 넘지 않는다 (WD-332)', async ({
    authenticatedPage: page,
  }) => {
    const longName = '인사 급여 비밀 집계 파이프라인 > s1 (자동생성) 아주 긴 이름이 계속 이어지는 데이터셋';
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [{ id: 21, name: longName, storageType: 'TABLE', originType: 'TEMP', createdAt: '2026-04-03T00:00:00' }],
      page: 0,
      size: 5,
      totalElements: 15,
      totalPages: 3,
    });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');

    const viewportWidth = 390;
    const datasetsCard = page.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name: '최근 데이터셋' }) });
    const dashboardsCard = page.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name: '최근 대시보드' }) });
    await expect(datasetsCard.getByText(longName)).toBeVisible();

    // 두 카드의 오른쪽 끝이 화면 안에 있다(수정 전에는 약 38px 넘침)
    for (const card of [datasetsCard, dashboardsCard]) {
      const box = await card.boundingBox();
      expect(box!.x + box!.width).toBeLessThanOrEqual(viewportWidth);
    }
    // 「전체 보기」 링크와 「임시」 배지가 화면 안에 다 보인다
    for (const el of [datasetsCard.getByRole('link', { name: '전체 보기' }), datasetsCard.getByText('임시', { exact: true })]) {
      const box = await el.boundingBox();
      expect(box!.x + box!.width).toBeLessThanOrEqual(viewportWidth);
    }
    // 긴 이름은 말줄임돼 실제 글자 폭보다 좁게 그려진다
    const nameEl = datasetsCard.getByText(longName);
    const { scrollWidth, clientWidth } = await nameEl.evaluate((el) => ({ scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }));
    expect(scrollWidth).toBeGreaterThan(clientWidth);
    // 본문(main)과 문서 전체 모두 가로로 스크롤되지 않는다
    const overflow = await page.locator('main').evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth);
  });

  /**
   * 회귀: WD-1115 — 최근 사용 카드 머리줄의 「전체 보기」(×2)와 빈 상태 「만들기」(×2) 링크는 글자 크기 그대로
   * 클릭 영역이 16~17px 높이뿐이라 디자인 가이드의 최소 클릭 타깃(32×32)에 못 미쳤다.
   * 네 링크 모두 박스가 32×32 이상이고, 눌렀을 때 여전히 제 주소로 이동하며, 카드 머리줄 높이는 늘지 않아야 한다.
   */
  test('최근 사용 카드의 「전체 보기」·「만들기」 링크 클릭 영역이 32×32 이상이다 (WD-1115)', async ({
    authenticatedPage: page,
  }) => {
    // 「데이터셋 만들기」 링크는 생성 권한이 있고 목록이 비었을 때만 보인다 — 기본 모킹은 대시보드가 비어 있어 「대시보드 만들기」가 보인다
    await grantDatasetPermissions(page, [...DATASET_WRITE_PERMISSIONS, ...HOME_PIPELINE_PERMISSIONS]);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
    await page.goto('/');

    const dashboardsCard = page.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name: '최근 대시보드' }) });
    const datasetsCard = page.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name: '최근 데이터셋' }) });
    const targets = [
      { link: dashboardsCard.getByRole('link', { name: '전체 보기' }), href: '/analytics/dashboards' },
      { link: dashboardsCard.getByRole('link', { name: '만들기' }), href: '/analytics/dashboards' },
      { link: datasetsCard.getByRole('link', { name: '전체 보기' }), href: '/data/datasets' },
      { link: datasetsCard.getByRole('link', { name: '만들기' }), href: '/data/datasets/new' },
    ];
    for (const { link } of targets) {
      await expect(link).toBeVisible();
      const box = await link.boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(32);
      expect(box!.width).toBeGreaterThanOrEqual(32);
    }
    // 클릭 영역을 넓혀도 머리줄(28px)은 커지지 않는다
    for (const card of [dashboardsCard, datasetsCard]) {
      const header = await card.locator('[data-slot="card-header"]').boundingBox();
      expect(header!.height).toBeLessThanOrEqual(30);
    }
    // 눌렀을 때 제 주소로 이동한다 — 가운데뿐 아니라 32px 박스의 위·아래 끝 가까이를 눌러도 이동해야
    // 「박스는 컸는데 끝은 안 눌린다」 회귀(글자 줄만 눌리는 경우)를 잡는다
    for (const { link, href } of targets) {
      // (WD-1115 후속) 앞 대상의 마지막 클릭으로 다른 주소에 와 있으므로, 박스를 재기 전에 홈으로 돌아와 링크가 보이길 기다린다
      await page.goto('/');
      await expect(link).toBeVisible();
      const box = (await link.boundingBox())!;
      const positions = [
        { x: box.width / 2, y: box.height / 2 },
        { x: box.width / 2, y: 2 },
        { x: box.width / 2, y: box.height - 2 },
      ];
      for (const position of positions) {
        await page.goto('/');
        await expect(link).toBeVisible();
        await link.click({ position });
        await expect(page).toHaveURL(new RegExp(`${href}$`));
      }
    }
  });

  /**
   * 회귀: WD-1115 후속 — 카드 머리줄이 줄바꿈(제목 아래로 「총 N개 · 전체 보기」가 내려옴)될 때
   * 늘린 클릭 영역(-my-1.5)이 윗줄 제목 위로 2px 겹쳤다(줄 사이 간격 4px < 늘어난 6px).
   * 두 칸 그리드가 좁아지는 폭을 훑어 줄바꿈이 실제로 일어난 폭에서 링크 박스가 제목 박스와 겹치지 않는지 본다.
   */
  test('머리줄이 줄바꿈돼도 「전체 보기」 클릭 영역이 제목과 겹치지 않는다 (WD-1115 후속)', async ({
    authenticatedPage: page,
  }) => {
    let wrappedSeen = 0;
    for (const width of [640, 700, 760, 820, 900, 1024, 1100]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/');
      for (const name of ['최근 대시보드', '최근 데이터셋']) {
        const card = page.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name }) });
        const link = card.getByRole('link', { name: '전체 보기' });
        await expect(link).toBeVisible();
        const title = (await card.getByRole('heading', { name }).boundingBox())!;
        const box = (await link.boundingBox())!;
        // 링크가 제목보다 아래 줄로 내려갔으면 줄바꿈된 것
        if (box.y + box.height / 2 > title.y + title.height) {
          wrappedSeen += 1;
          // 링크 박스 윗변이 제목 아랫변 이상이어야 한다(겹침 0) — 소수 픽셀 반올림 오차로 흔들리지 않게 0.5px 여유
          expect(box.y).toBeGreaterThanOrEqual(title.y + title.height - 0.5);
        }
      }
    }
    // 줄바꿈 폭을 하나도 못 만났다면 이 시험이 아무것도 검사하지 않은 것이다
    expect(wrappedSeen).toBeGreaterThan(0);
  });

  /**
   * 회귀: 이슈 #62 — 섹션 헤딩(h2/h3)이 SR 네비게이션을 위해 존재해야 한다
   * - 수정 전: <h1>홈</h1> 단 1개만 존재 → SR이 섹션 점프 불가
   * - 수정 후: 각 시각 섹션마다 h2/h3 존재 (시스템 건강 상태/주의 필요/퀵 액션/최근 사용(WD-1071)/활동 피드 + 카드별 h3)
   * - WCAG SC 2.4.6 (Headings and Labels) 준수
   */
  test('각 섹션마다 시멘틱 헤딩(h2/h3)이 존재한다 (이슈 #62)', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    // h1 — 페이지 제목
    await expect(page.getByRole('heading', { level: 1, name: '홈' })).toBeVisible();

    // h2 — 페이지 1단계 섹션들 (sr-only 포함하여 accessible name으로 검증)
    await expect(page.getByRole('heading', { level: 2, name: '시스템 건강 상태' })).toBeAttached();
    await expect(page.getByRole('heading', { level: 2, name: '퀵 액션' })).toBeAttached();
    await expect(page.getByRole('heading', { level: 2, name: '최근 사용' })).toBeAttached();
    await expect(page.getByRole('heading', { level: 2, name: '활동 피드' })).toBeAttached();

    // h3 — 최근 사용(h2) 아래 카드들 (대시보드/데이터셋은 항상 표시, 순서는 WD-1071 시험)
    await expect(page.getByRole('heading', { level: 3, name: '최근 대시보드' })).toBeAttached();
    await expect(page.getByRole('heading', { level: 3, name: '최근 데이터셋' })).toBeAttached();

    // 헤딩 개수 sanity — 최소 6개(이전 1개 → 회귀 방지)
    const headingCount = await page.locator('h1, h2, h3').count();
    expect(headingCount).toBeGreaterThanOrEqual(6);
  });

  /**
   * 회귀: WD-1071 — ZONE 4(최근 사용)에 자체 h2 가 없어 카드 h3 가 「퀵 액션」 h2 아래로 묶였다.
   * - 수정 후: sr-only h2 「최근 사용」이 퀵 액션과 카드 h3 사이에 놓이고 region 이름으로 노출된다.
   */
  test('최근 사용 영역은 자체 h2 아래에 카드 h3 를 둔다 (WD-1071)', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    // 기대 순서·단계: 퀵 액션(h2) < 최근 사용(h2) < 최근 대시보드(h3) < 최근 데이터셋(h3) < 활동 피드(h2)
    const expected = [
      { text: '퀵 액션', level: 'H2' },
      { text: '최근 사용', level: 'H2' },
      { text: '최근 대시보드', level: 'H3' },
      { text: '최근 데이터셋', level: 'H3' },
      { text: '활동 피드', level: 'H2' },
    ];
    // 순서를 한 번에 읽으므로, 비교할 제목이 모두 그려진 뒤 읽는다(늦게 그려진 제목이 빠지지 않게)
    for (const { text } of expected) {
      await expect(page.getByRole('heading', { name: text, exact: true })).toBeAttached();
    }
    const names = expected.map((h) => h.text);
    const headings = await page.locator('h1, h2, h3').evaluateAll((els) =>
      els.map((el) => ({ text: (el.textContent ?? '').trim(), level: el.tagName })),
    );
    // 문서 순서대로 걸러 한 번에 비교 — 빠지거나 순서·단계가 다르면 실패한다
    expect(headings.filter((h) => names.includes(h.text))).toEqual(expected);

    await expect(
      page.getByRole('region', { name: '최근 사용' }).getByRole('heading', { level: 3, name: '최근 대시보드' }),
    ).toBeVisible();
  });

  /**
   * 회귀: 이슈 #225 — 홈 위젯 스크롤 영역 일괄 점검
   * - "최근 임포트" / "최근 실행" 위젯은 slice(0,5)로 5건 고정 표시이며 내부 스크롤이 발생하면 안된다.
   *   (max-h-[13rem] 안에 5건이 들어가지 않아 첫 항목 상단이 잘리는 회귀)
   * - 풀리스트/페이지네이션 위젯(최근 대시보드/데이터셋, 주의 필요, 활동 피드)은 스크롤이 발생하더라도
   *   스크롤바와 컨텐츠 사이 우측 패딩(pr-2 = 8px)이 있어야 한다.
   */
  test('5건 고정 위젯("최근 임포트", "최근 실행")은 내부 스크롤이 발생하지 않는다 (이슈 #225)', async ({ authenticatedPage: page }) => {
    // dashboard/stats를 5건 임포트 + 5건 실행으로 오버라이드
    await mockApi(page, 'GET', '/api/v1/dashboard/stats', {
      totalDatasets: 10,
      sourceDatasets: 6,
      derivedDatasets: 4,
      totalPipelines: 5,
      activePipelines: 3,
      recentImports: Array.from({ length: 5 }, (_, i) => ({
        id: i + 1,
        datasetName: `데이터셋 ${i + 1}`,
        fileName: `file_${i + 1}.csv`,
        status: 'COMPLETED',
        createdAt: '2026-05-15T10:00:00',
      })),
      recentExecutions: Array.from({ length: 5 }, (_, i) => ({
        id: i + 1,
        pipelineName: `파이프라인 ${i + 1}`,
        status: 'COMPLETED',
        createdAt: '2026-05-15T10:00:00',
      })),
    });

    await page.goto('/');

    // "최근 임포트" 위젯 컨테이너 — 카드 제목 다음 형제인 CardContent 안의 div를 잡는다
    // 카드 헤더 텍스트로 카드 루트를 찾고, 내부의 첫 번째 .py-1.5 항목의 부모 div를 평가한다
    const importItem = page.getByText('데이터셋 1', { exact: true });
    await expect(importItem).toBeVisible();
    // 5건 모두 표시 확인 (잘림 회귀 방지)
    await expect(page.getByText('데이터셋 5', { exact: true })).toBeVisible();

    // 컨테이너의 scrollHeight == clientHeight (스크롤 미발생)
    // 항목 div의 부모(스크롤 컨테이너)를 직접 평가
    const importScrollState = await importItem.evaluate((node) => {
      // node = <p class="truncate text-sm">데이터셋 1</p>
      // 부모 체인: p -> .min-w-0 -> .py-1.5 항목 -> 스크롤 컨테이너 div
      const itemRow = node.closest('.py-1\\.5');
      const scrollContainer = itemRow?.parentElement;
      return {
        scrollHeight: scrollContainer?.scrollHeight ?? 0,
        clientHeight: scrollContainer?.clientHeight ?? 0,
      };
    });
    // 스크롤이 발생하지 않으려면 scrollHeight <= clientHeight여야 한다
    expect(importScrollState.scrollHeight).toBeLessThanOrEqual(importScrollState.clientHeight);

    // "최근 실행" 위젯도 동일 검증
    const execItem = page.getByText('파이프라인 1', { exact: true });
    await expect(execItem).toBeVisible();
    await expect(page.getByText('파이프라인 5', { exact: true })).toBeVisible();

    const execScrollState = await execItem.evaluate((node) => {
      const itemRow = node.closest('.py-1\\.5');
      const scrollContainer = itemRow?.parentElement;
      return {
        scrollHeight: scrollContainer?.scrollHeight ?? 0,
        clientHeight: scrollContainer?.clientHeight ?? 0,
      };
    });
    expect(execScrollState.scrollHeight).toBeLessThanOrEqual(execScrollState.clientHeight);
  });

  /**
   * WD-1116: 「최근 사용」 카드가 정확히 3장이면 2열 그리드의 오른쪽 아래 칸이 비었다.
   * 이제 마지막 카드(임포트 또는 실행)가 두 칸을 채우고, 2장·4장일 때는 그대로 반 폭이다.
   */
  async function mockRecentCards(page: Page, opts: { imports: boolean; executions: boolean }) {
    await mockApi(page, 'GET', '/api/v1/dashboard/stats', {
      totalDatasets: 10,
      sourceDatasets: 6,
      derivedDatasets: 4,
      totalPipelines: 5,
      activePipelines: 3,
      recentImports: opts.imports
        ? [{ id: 1, datasetName: '임포트 데이터셋', fileName: 'a.csv', status: 'COMPLETED', createdAt: '2026-05-15T10:00:00' }]
        : [],
      recentExecutions: opts.executions
        ? [{ id: 1, pipelineName: '실행 파이프라인', status: 'COMPLETED', createdAt: '2026-05-15T10:00:00' }]
        : [],
    });
  }

  /** 「최근 사용」 section 과 그 직계 카드들의 박스를 읽는다. */
  async function recentCardBoxes(page: Page) {
    const section = page.locator('section[aria-labelledby="home-recent-heading"]');
    const cards = section.locator(':scope > div');
    const sectionBox = (await section.boundingBox())!;
    const boxes = [];
    for (const card of await cards.all()) boxes.push((await card.boundingBox())!);
    return { sectionBox, boxes };
  }

  for (const [name, imports, executions, title] of [
    ['임포트 없음·실행 있음', false, true, '최근 실행'],
    ['임포트 있음·실행 없음', true, false, '최근 임포트'],
  ] as const) {
    test(`최근 사용 카드가 3장(${name})이면 마지막 카드가 두 칸을 채운다 (WD-1116)`, async ({ authenticatedPage: page }) => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await mockRecentCards(page, { imports, executions });
      await page.goto('/');
      await expect(page.getByRole('heading', { name: title })).toBeVisible();

      const { sectionBox, boxes } = await recentCardBoxes(page);
      expect(boxes).toHaveLength(3);
      // 위 두 장은 나란히(같은 줄) 반 폭
      expect(Math.abs(boxes[0].y - boxes[1].y)).toBeLessThanOrEqual(1);
      // 마지막 카드는 아래 줄에서 첫 카드 왼쪽 끝~둘째 카드 오른쪽 끝까지 — 빈 칸이 없다
      expect(boxes[2].y).toBeGreaterThan(boxes[0].y + 10);
      expect(Math.abs(boxes[2].x - boxes[0].x)).toBeLessThanOrEqual(1);
      expect(Math.abs(boxes[2].x + boxes[2].width - (boxes[1].x + boxes[1].width))).toBeLessThanOrEqual(1);
      expect(Math.abs(boxes[2].width - sectionBox.width)).toBeLessThanOrEqual(1);
    });
  }

  test('최근 사용 카드가 4장이면 모두 반 폭이다 (WD-1116)', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockRecentCards(page, { imports: true, executions: true });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '최근 실행' })).toBeVisible();

    const { sectionBox, boxes } = await recentCardBoxes(page);
    expect(boxes).toHaveLength(4);
    for (const box of boxes) {
      expect(box.width).toBeLessThan(sectionBox.width * 0.55);
    }
  });

  /**
   * 이슈 #226 — 활동 피드 더보기 버튼 제거 후 무한 스크롤로 전환
   * - sentinel 진입 시 자동으로 page=1 요청이 발생하고 누적 렌더되는지 검증
   * - 1페이지/2페이지가 같은 컨테이너에 누적되어 스크롤 위치가 보존되는지 검증
   * - "더보기" 버튼이 더 이상 존재하지 않음을 검증
   */
  test('활동 피드는 sentinel 진입 시 다음 페이지를 자동 fetch하여 누적 렌더한다 (이슈 #226)', async ({ authenticatedPage: page }) => {
    // page 파라미터에 따라 다른 응답을 주는 동적 핸들러 등록.
    // setupHomeMocks가 이미 GET /api/v1/dashboard/activity를 빈 응답으로 모킹해놨으므로
    // 우리는 우선순위가 더 높은(나중에 등록되는) 핸들러로 덮어쓴다.
    const requestedPages: number[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/dashboard/activity',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const reqUrl = new URL(route.request().url());
        const pageParam = Number(reqUrl.searchParams.get('page') ?? '0');
        requestedPages.push(pageParam);

        // page=0 → 20개 + hasMore=true, page=1 → 10개 + hasMore=false
        if (pageParam === 0) {
          const items = Array.from({ length: 20 }, (_, i) => ({
            id: i + 1,
            eventType: 'PIPELINE_SUCCESS',
            title: `활동 ${i + 1}`,
            description: `desc ${i + 1}`,
            severity: 'INFO',
            entityType: 'PIPELINE',
            entityId: i + 1,
            occurredAt: '2026-05-15T10:00:00',
            isResolved: true,
          }));
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ items, totalCount: 30, hasMore: true }),
          });
        }
        const items = Array.from({ length: 10 }, (_, i) => ({
          id: 21 + i,
          eventType: 'PIPELINE_SUCCESS',
          title: `활동 ${21 + i}`,
          description: `desc ${21 + i}`,
          severity: 'INFO',
          entityType: 'PIPELINE',
          entityId: 21 + i,
          occurredAt: '2026-05-15T09:00:00',
          isResolved: true,
        }));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items, totalCount: 30, hasMore: false }),
        });
      },
    );

    await page.goto('/');

    // 1페이지 로드 완료 — 첫/마지막 항목 확인
    await expect(page.getByText('활동 1', { exact: true })).toBeVisible();
    await expect(page.getByText('활동 20', { exact: true })).toBeVisible();

    // "더보기" 버튼은 더 이상 존재하지 않아야 한다 (이슈 #226의 핵심)
    await expect(page.getByRole('button', { name: '더보기' })).toHaveCount(0);

    // sentinel이 컨테이너 내부에 존재하나 화면 밖이므로, 스크롤 컨테이너를 끝까지 내려서 진입시킴
    const scrollContainer = page.getByTestId('activity-feed-scroll');
    await scrollContainer.evaluate((el) => {
      el.scrollTop = el.scrollHeight;
    });

    // 2페이지 진입으로 page=1 호출이 발생 — 새 항목이 화면에 누적
    await expect(page.getByText('활동 21', { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('활동 30', { exact: true })).toBeVisible();

    // 1페이지 항목은 사라지지 않고 함께 렌더 (스크롤 누적)
    await expect(page.getByText('활동 1', { exact: true })).toBeAttached();

    // API 호출 시퀀스 검증 — page=0, page=1이 정확히 호출되었어야 함
    expect(requestedPages).toContain(0);
    expect(requestedPages).toContain(1);

    // 끝 도달 후 sentinel은 사라지고 끝 안내 표시
    await expect(page.getByTestId('activity-feed-sentinel')).toHaveCount(0);
    await expect(page.getByText('더 이상 항목이 없습니다.')).toBeVisible();
  });

  /**
   * WD-897: 활동 피드 필터 칩(24px)과 상태 선택 상자(36px)가 서로 높이가 달라 줄이 들쭉날쭉했고
   * 칩은 최소 히트 영역(32px)에도 못 미쳤다. 데스크톱·모바일 모두 같은 높이(≥32px)여야 한다.
   */
  test('활동 피드 필터 칩과 상태 선택 상자 높이가 같고 32 이상이다 (WD-897)', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    // 유형 칩들과 상태 선택 상자의 높이를 재서 서로 같고 32px 이상인지 단언한다.
    // alignCenter: 넓은 화면(같은 줄)에서만 세로 가운데 정렬까지 본다 — 390px 에서는 줄이 감겨 칩과 선택 상자가
    // 다른 줄에 놓일 수 있으니 높이만 본다.
    const expectUniformHeights = async (alignCenter: boolean) => {
      const chips = page.getByRole('group', { name: '유형' }).getByRole('button');
      await expect(chips.first()).toBeVisible();
      const select = page.getByRole('combobox', { name: '상태' });
      await expect(select).toBeVisible();
      const chipRects = await chips.evaluateAll((els) =>
        els.map((el) => {
          const r = el.getBoundingClientRect();
          return { top: r.top, height: r.height };
        }),
      );
      const box = await select.boundingBox();
      // 칩은 권한 없으면 「전체」·「데이터셋」 2개뿐이다 — beforeEach 의 파이프라인 권한에 기대지 않도록 2 이상만 요구한다.
      expect(chipRects.length).toBeGreaterThanOrEqual(2);
      expect(box).not.toBeNull();
      const selectHeight = box!.height;
      const selectCenter = box!.y + selectHeight / 2;
      expect(selectHeight).toBeGreaterThanOrEqual(32);
      for (const c of chipRects) {
        expect(c.height).toBeGreaterThanOrEqual(32);
        expect(Math.abs(c.height - selectHeight)).toBeLessThanOrEqual(1);
        // 이슈의 「top 305 vs 299 로 6px 어긋남」 고정 — 세로 가운데 차이 1px 이하
        if (alignCenter) expect(Math.abs(c.top + c.height / 2 - selectCenter)).toBeLessThanOrEqual(1);
      }
    };

    await page.setViewportSize({ width: 1440, height: 900 });
    await expectUniformHeights(true);
    await page.setViewportSize({ width: 390, height: 844 });
    await expectUniformHeights(false);
  });

  test('활동 피드 필터 변경 시 page=0부터 다시 시작한다 (이슈 #226)', async ({ authenticatedPage: page }) => {
    // 필터 변경 → queryKey 변경 → useInfiniteQuery가 page=0으로 재시작.
    // 동일 핸들러로 type 파라미터별 응답을 분기한다.
    const capturedRequests: Array<{ page: number; type: string | null; severity: string | null }> = [];
    await page.route(
      (url) => url.pathname === '/api/v1/dashboard/activity',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const reqUrl = new URL(route.request().url());
        const pageParam = Number(reqUrl.searchParams.get('page') ?? '0');
        const type = reqUrl.searchParams.get('type');
        const severity = reqUrl.searchParams.get('severity');
        capturedRequests.push({ page: pageParam, type, severity });

        const label = type === 'PIPELINE' ? '파이프 항목' : '기본 항목';
        const items = Array.from({ length: 3 }, (_, i) => ({
          id: i + 1,
          eventType: 'PIPELINE_SUCCESS',
          title: `${label} ${i + 1}`,
          description: 'desc',
          severity: 'INFO',
          entityType: 'PIPELINE',
          entityId: i + 1,
          occurredAt: '2026-05-15T10:00:00',
          isResolved: true,
        }));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items, totalCount: 3, hasMore: false }),
        });
      },
    );

    await page.goto('/');
    await expect(page.getByText('기본 항목 1', { exact: true })).toBeVisible();

    // "파이프라인" 필터 클릭 — 활동 피드 카드 내부의 필터 버튼
    await page.getByRole('button', { name: '파이프라인', exact: true }).click();

    // 새 type 파라미터로 page=0이 다시 호출되며, 응답이 화면에 반영
    await expect(page.getByText('파이프 항목 1', { exact: true })).toBeVisible({ timeout: 5000 });

    // type=PIPELINE 요청 중 page=0이 포함되어야 함
    const pipelineReqs = capturedRequests.filter((r) => r.type === 'PIPELINE');
    expect(pipelineReqs.length).toBeGreaterThan(0);
    expect(pipelineReqs.some((r) => r.page === 0)).toBe(true);
  });

  /**
   * 회귀: WD-339 — 활동 피드 「유형」 버튼은 선택 여부를 색으로만, 「상태」 선택 상자는 이름 없이 노출돼
   * 스크린리더 사용자가 무엇으로 걸렀는지 알 수 없었다. 그룹 이름·aria-pressed·콤보박스 이름을 확인하고,
   * 이름으로 찾은 컨트롤로 실제 필터가 API 파라미터에 실리는지까지 본다.
   */
  test('활동 피드 필터 — 「유형」 그룹의 선택 상태(aria-pressed)와 「상태」 콤보박스 이름이 노출된다 (WD-339)', async ({
    authenticatedPage: page,
  }) => {
    const captured: Array<{ type: string | null; severity: string | null }> = [];
    await page.route(
      (url) => url.pathname === '/api/v1/dashboard/activity',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const reqUrl = new URL(route.request().url());
        captured.push({ type: reqUrl.searchParams.get('type'), severity: reqUrl.searchParams.get('severity') });
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: [], totalCount: 0, hasMore: false }),
        });
      },
    );
    await page.goto('/');

    const typeGroup = page.getByRole('group', { name: '유형' });
    const all = typeGroup.getByRole('button', { name: '전체', exact: true });
    const pipeline = typeGroup.getByRole('button', { name: '파이프라인', exact: true });
    const dataset = typeGroup.getByRole('button', { name: '데이터셋', exact: true });
    // 처음에는 「전체」만 눌린 상태
    await expect(all).toHaveAttribute('aria-pressed', 'true');
    await expect(pipeline).toHaveAttribute('aria-pressed', 'false');
    await expect(dataset).toHaveAttribute('aria-pressed', 'false');

    // 「파이프라인」을 누르면 선택 상태가 옮겨 가고 type=PIPELINE 으로 다시 조회한다
    await pipeline.click();
    await expect(pipeline).toHaveAttribute('aria-pressed', 'true');
    await expect(all).toHaveAttribute('aria-pressed', 'false');
    await expect.poll(() => captured.some((r) => r.type === 'PIPELINE')).toBe(true);
    // 이미 눌린 버튼을 다시 눌러도 선택은 그대로(한 가지만 고르는 그룹)
    await pipeline.click();
    await expect(pipeline).toHaveAttribute('aria-pressed', 'true');
    // 「데이터셋」으로 바꾸면 선택이 옮겨 간다
    await dataset.click();
    await expect(dataset).toHaveAttribute('aria-pressed', 'true');
    await expect(pipeline).toHaveAttribute('aria-pressed', 'false');
    await expect.poll(() => captured.some((r) => r.type === 'DATASET')).toBe(true);

    // 「상태」 콤보박스는 이름으로 찾을 수 있고, 고른 값이 severity 로 실린다
    const severity = page.getByRole('combobox', { name: '상태' });
    await expect(severity).toHaveText('전체');
    await severity.click();
    await page.getByRole('option', { name: '실패' }).click();
    await expect(severity).toHaveText('실패');
    await expect.poll(() => captured.some((r) => r.type === 'DATASET' && r.severity === 'CRITICAL')).toBe(true);
  });

  /**
   * 회귀: WD-875 — 활동 피드에서 유형·상태 필터로 0건이 되어도 「활동 내역이 없습니다.」와 「파이프라인 실행하기」만
   * 보여, 필터 때문에 비었다는 사실도 풀 방법도 알 수 없었다. 필터 0건이면 별도 문구와 「필터 초기화」를 준다.
   */
  async function mockActivityFilteredEmpty(
    page: import('@playwright/test').Page,
    captured?: Array<{ type: string | null; severity: string | null }>,
  ) {
    await page.route(
      (url) => url.pathname === '/api/v1/dashboard/activity',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const reqUrl = new URL(route.request().url());
        const type = reqUrl.searchParams.get('type');
        const severity = reqUrl.searchParams.get('severity');
        // 요청 파라미터를 기록해 테스트가 「어떤 쿼리로 호출됐는지」 검증할 수 있게 한다
        captured?.push({ type, severity });
        // 필터가 하나라도 걸리면 0건, 없으면 1건 (src/types 타입의 팩토리로 생성)
        const body = type || severity ? createActivityFeedResponse([]) : createActivityFeedResponse([createActivityItem()]);
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(body),
        });
      },
    );
  }

  test('활동 피드 — 0건 필터 사이를 오가면(A→B) 빈 상태 status 노드가 새로 갈아 끼워져 다시 낭독된다 (WD-875)', async ({
    authenticatedPage: page,
  }) => {
    await mockActivityFilteredEmpty(page);
    await page.goto('/');
    await expect(page.getByText('데이터셋 생성 항목')).toBeVisible();

    const message = page.getByText('조건에 맞는 활동이 없습니다.');
    const pipeline = page.getByRole('button', { name: '파이프라인', exact: true });
    const dataset = page.getByRole('button', { name: '데이터셋', exact: true });
    // 낭독용 노드(지연 뒤 aria-hidden 해제)
    const node = page.getByRole('status').filter({ hasText: '조건에 맞는 활동이 없습니다.' }).locator('> div');

    // 로딩 재마운트가 키 변경을 가리지 않도록 A·B 두 0건 쿼리를 먼저 모두 캐시에 올린다(staleTime 30초)
    await pipeline.click();
    await expect(message).toBeVisible();
    await dataset.click();
    await expect(message).toBeVisible();
    await expect(node).not.toHaveAttribute('aria-hidden', 'true');
    await node.evaluate((el) => el.setAttribute('data-probe', 'old'));

    // 캐시된 0건 필터 A 로 복귀 — 로딩 없이 바로 전환되므로 노드 교체는 announceKey 변경만으로 일어난다
    await pipeline.click();
    await expect(message).toBeVisible();
    await expect(node).not.toHaveAttribute('aria-hidden', 'true');
    await expect(node).not.toHaveAttribute('data-probe', 'old');
  });

  test('활동 피드 — 필터로 0건이면 「조건에 맞는 활동이 없습니다」와 「필터 초기화」가 보이고, 누르면 필터가 풀린다 (WD-875)', async ({
    authenticatedPage: page,
  }) => {
    // 무필터 쿼리 캐시(staleTime 30초)가 만료되도록 가짜 시계를 쓴다 — 앱 캐시 설정은 건드리지 않는다
    await page.clock.install();
    const captured: Array<{ type: string | null; severity: string | null }> = [];
    await mockActivityFilteredEmpty(page, captured);
    await page.goto('/');
    await expect(page.getByText('데이터셋 생성 항목')).toBeVisible();

    await page.getByRole('button', { name: '파이프라인', exact: true }).click();
    await expect(page.getByText('조건에 맞는 활동이 없습니다.')).toBeVisible();
    await expect(page.getByText('활동 내역이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '파이프라인 실행하기' })).toHaveCount(0);

    // 무필터 캐시를 stale 로 만든 뒤 초기화 — 새 요청이 나가야 하고, 그 요청엔 type/severity 가 없어야 한다
    await page.clock.fastForward(31_000);
    const before = captured.length;
    await page.getByRole('button', { name: '필터 초기화' }).click();
    await expect.poll(() => captured.length).toBeGreaterThan(before);
    expect(captured.slice(before).every((r) => r.type === null && r.severity === null)).toBe(true);
    await expect(page.getByText('데이터셋 생성 항목')).toBeVisible();
    const typeGroup = page.getByRole('group', { name: '유형' });
    await expect(typeGroup.getByRole('button', { name: '전체', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('combobox', { name: '상태' })).toHaveText('전체');
    // 초기화 후 포커스는 사라진 버튼 대신 유형 「전체」 버튼으로 옮겨진다
    await expect(typeGroup.getByRole('button', { name: '전체', exact: true })).toBeFocused();
  });

  test('활동 피드 — 상태 필터만으로 0건이어도 「조건에 맞는 활동이 없습니다」가 보인다 (WD-875)', async ({
    authenticatedPage: page,
  }) => {
    await mockActivityFilteredEmpty(page);
    await page.goto('/');
    await expect(page.getByText('데이터셋 생성 항목')).toBeVisible();

    await page.getByRole('combobox', { name: '상태' }).click();
    await page.getByRole('option', { name: '실패' }).click();
    await expect(page.getByText('조건에 맞는 활동이 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '필터 초기화' })).toBeVisible();
    await expect(page.getByText('활동 내역이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '파이프라인 실행하기' })).toHaveCount(0);

    await page.getByRole('button', { name: '필터 초기화' }).click();
    await expect(page.getByText('데이터셋 생성 항목')).toBeVisible();
    await expect(page.getByRole('combobox', { name: '상태' })).toHaveText('전체');
  });

  test('풀리스트 위젯("최근 대시보드"/"최근 데이터셋")은 스크롤 컨테이너에 우측 패딩이 있다 (이슈 #225)', async ({ authenticatedPage: page }) => {
    // 다수 항목으로 오버라이드하여 스크롤 발생 조건 확보
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', {
      content: Array.from({ length: 10 }, (_, i) => ({
        id: i + 1,
        name: `대시보드 ${i + 1}`,
        widgetCount: i + 1,
      })),
      page: 0,
      size: 10,
      totalElements: 10,
      totalPages: 1,
    });
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: Array.from({ length: 10 }, (_, i) => ({
        id: i + 1,
        name: `데이터셋 ${i + 1}`,
        storageType: 'TABLE',
        originType: 'SOURCE',
        createdAt: '2026-05-15T10:00:00',
      })),
      page: 0,
      size: 10,
      totalElements: 10,
      totalPages: 1,
    });

    await page.goto('/');

    // 첫 항목으로 스크롤 컨테이너 접근 → padding-right >= 6px 검증
    const dashItem = page.getByText('대시보드 1', { exact: true }).first();
    await expect(dashItem).toBeVisible();
    const dashPadding = await dashItem.evaluate((node) => {
      // 대시보드 항목은 button 요소. 가장 가까운 scroll container를 부모 체인에서 찾는다.
      let el: HTMLElement | null = node as HTMLElement;
      while (el && el.parentElement) {
        const style = getComputedStyle(el);
        if (style.overflowY === 'auto' || style.overflowY === 'scroll') {
          return parseFloat(style.paddingRight);
        }
        el = el.parentElement;
      }
      return 0;
    });
    expect(dashPadding).toBeGreaterThanOrEqual(6);

    // "최근 데이터셋"도 동일 검증
    const dsItem = page.getByText('데이터셋 1', { exact: true }).first();
    const dsPadding = await dsItem.evaluate((node) => {
      let el: HTMLElement | null = node as HTMLElement;
      while (el && el.parentElement) {
        const style = getComputedStyle(el);
        if (style.overflowY === 'auto' || style.overflowY === 'scroll') {
          return parseFloat(style.paddingRight);
        }
        el = el.parentElement;
      }
      return 0;
    });
    expect(dsPadding).toBeGreaterThanOrEqual(6);
  });
  test('활동 피드 높이가 뷰포트에 연동된다 (고정 32rem 회귀 방지)', async ({
    authenticatedPage: page,
  }) => {
    // `h-[32rem]`(512px)은 화면 크기와 무관한 고정값이라, 낮은 화면에서 피드 하나가
    // 뷰포트보다 커지는 구간이 생겼다. 홈은 main(457px)·피드(604px)·다른 카드(191px)가
    // 동시에 스크롤되던 화면이라 이 고정값이 체감을 가장 악화시켰다.
    // 내부 스크롤 자체는 #225에서 확정된 설계이므로 유지하고, 높이 산정만 검증한다.
    await page.setViewportSize({ width: 1280, height: 600 });
    // setupHomeMocks의 빈 활동 응답을 덮어써서 피드가 실제로 렌더되게 한다
    await page.route(
      (url) => url.pathname === '/api/v1/dashboard/activity',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            items: Array.from({ length: 30 }, (_, i) => ({
              id: i + 1,
              eventType: 'PIPELINE_SUCCESS',
              title: `활동 ${i + 1}`,
              description: `desc ${i + 1}`,
              severity: 'INFO',
              entityType: 'PIPELINE',
              entityId: i + 1,
              occurredAt: '2026-05-15T10:00:00',
              isResolved: true,
            })),
            hasMore: false,
          }),
        });
      }
    );
    await page.goto('/');

    const feed = page.getByTestId('activity-feed-scroll');
    await expect(feed).toBeVisible();

    const { height, viewport } = await feed.evaluate((el) => ({
      height: el.getBoundingClientRect().height,
      viewport: window.innerHeight,
    }));

    // 뷰포트 절반을 넘지 않아야 한다 — 넘으면 고정 픽셀 높이로 되돌아간 것이다.
    expect(height).toBeLessThanOrEqual(viewport / 2 + 1);
  });
});

/**
 * WD-272 — 파이프라인 조회 권한(pipeline:read)이 없는 일반 멤버의 홈.
 * 서버는 이런 사용자에게 홈 API 에서 파이프라인 정보를 빼고 보내지만(개수 0·목록 비움), 화면도 파이프라인 요약·바로가기·필터를
 * 그리지 않아야 한다 — 누르면 「파이프라인을 볼 권한이 없습니다」 화면으로 가기 때문이다. 응답에 파이프라인 정보가 섞여 와도(구버전 서버)
 * 그리지 않는지 보려고 모킹 응답에는 파이프라인 데이터를 그대로 둔다.
 */
test.describe('홈 — 파이프라인 조회 권한 없음 (WD-272)', () => {
  /** 권한 응답이 반영된 뒤 판정하도록 /auth/me/permissions 응답을 기다리며 이동한다(dataset-list-permissions.spec 과 같은 방식) */
  async function gotoWithPermissions(page: Page, path: string) {
    const permissionsLoaded = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
    await page.goto(path);
    await permissionsLoaded;
  }

  /** 데이터셋 조회만 있는 USER 와 같은 권한 묶음 */
  const DATASET_ONLY = ['dataset:read', 'data:read'];

  test('파이프라인 요약·「파이프라인 목록」·최근 실행 카드가 없고 데이터셋 요약은 남는다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, DATASET_ONLY);
    await mockApi(page, 'GET', '/api/v1/dashboard/stats', {
      totalDatasets: 10,
      sourceDatasets: 6,
      derivedDatasets: 4,
      totalPipelines: 1,
      activePipelines: 1,
      recentImports: [],
      recentExecutions: [
        { id: 1, pipelineName: '인사 급여 비밀 집계 파이프라인', status: 'COMPLETED', createdAt: '2026-10-28T10:00:00' },
      ],
    });
    await gotoWithPermissions(page, '/');

    // 같은 상태바의 데이터셋 요약·같은 퀵 액션 묶음의 SQL 편집기가 그려진 뒤 판정 — 묶음이 안 그려져서 없는 것과 구분한다
    await expect(page.getByRole('button', { name: /데이터셋 .* 최신/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /SQL 편집기/ })).toBeVisible();

    await expect(page.getByRole('button', { name: /파이프라인 .* 실패/ })).toHaveCount(0);
    await expect(page.getByTestId('pipeline-sparkline')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /파이프라인 목록/ })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: '최근 실행' })).toHaveCount(0);
    await expect(page.getByText('인사 급여 비밀 집계 파이프라인')).toHaveCount(0);
  });

  test('활동 피드 「유형」 필터에 「파이프라인」이 없고, 빈 상태에 「파이프라인 실행하기」가 없다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, DATASET_ONLY);
    await gotoWithPermissions(page, '/');

    await expect(page.getByText('활동 내역이 없습니다.')).toBeVisible();
    // 같은 필터 묶음의 「데이터셋」 버튼이 그려진 뒤 판정
    await expect(page.getByRole('button', { name: '데이터셋', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '파이프라인', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '파이프라인 실행하기' })).toHaveCount(0);
  });

  test('파이프라인을 볼 수 없으면 데이터셋이 모두 정상이어도 「모든 시스템 정상」을 띄우지 않는다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, DATASET_ONLY);
    // 서버는 권한 없는 사용자에게 파이프라인 건강도를 0 으로 보낸다 — 실패 파이프라인이 있어도 0 이라 「정상」 판단 근거가 될 수 없다
    await mockApi(page, 'GET', '/api/v1/dashboard/health', {
      pipelineHealth: { total: 0, healthy: 0, failing: 0, running: 0, disabled: 0, trend: [0, 0, 0, 0, 0, 0, 0] },
      datasetHealth: { total: 10, fresh: 10, stale: 0, empty: 0, trend: [1, 2, 1, 1, 2, 1, 2] },
    });
    await mockApi(page, 'GET', '/api/v1/dashboard/attention', []);
    await page.goto('/');

    await expect(page.getByRole('button', { name: /데이터셋 .* 최신/ })).toBeVisible();
    await expect(page.getByText('모든 시스템 정상')).toHaveCount(0);
  });

  test('조회만 있고 실행 권한이 없으면 「파이프라인 목록」은 보이지만 「파이프라인 실행하기」는 없다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, [...DATASET_ONLY, 'pipeline:read']);
    await page.goto('/');

    await expect(page.getByRole('button', { name: /파이프라인 목록/ })).toBeVisible();
    await expect(page.getByRole('button', { name: '파이프라인', exact: true })).toBeVisible();
    await expect(page.getByText('활동 내역이 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '파이프라인 실행하기' })).toHaveCount(0);
  });
});
