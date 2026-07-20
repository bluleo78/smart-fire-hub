import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetMocks, setupFileDatasetCreateMocks } from '../fixtures/dataset.fixture';

/**
 * FILE 유형 데이터셋 생성 E2E 테스트
 * - FILE 유형 선택 시 테이블명·칼럼 정의 카드 숨김, 대신 경로 프리픽스 입력 노출
 * - tableName 자동 생성(file_<timestamp>), columns: [] payload 검증
 * - prefix 입력값이 payload에 그대로 전달되는지 검증
 */
test.describe('FILE 데이터셋', () => {
  test(
    '생성 폼에서 컬럼 정의가 숨겨지고 파일 데이터셋을 만든다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      await setupDatasetMocks(page);
      const capture = await setupFileDatasetCreateMocks(page, 20);

      // 유형은 폼 셀렉트가 아니라 생성 유형 선택 모달이 전달하는 URL 쿼리로 고정된다.
      // 파일 유형은 문서와 마찬가지로 모달에서 출처 단계를 건너뛰고 storageType=FILE 로 진입한다.
      await page.goto('/data/datasets/new?storageType=FILE&originType=SOURCE');

      // 칼럼 정의 카드가 표시되지 않는지 확인 (FILE은 동적 칼럼 없음)
      await expect(page.getByRole('heading', { name: '칼럼 정의' })).toHaveCount(0);
      // 테이블명 입력 필드도 숨겨진다 (자동 생성)
      await expect(page.getByLabel('테이블명')).toHaveCount(0);
      // FILE 전용 경로 프리픽스 입력 필드는 노출된다
      await expect(page.getByLabel('경로 프리픽스')).toBeVisible();

      await page.getByLabel('데이터셋 이름').fill('장비 학습 데이터');
      await page.getByLabel('경로 프리픽스').fill('datasets/equipment/');

      await page.getByRole('button', { name: '생성' }).click();

      // POST payload 검증 — storageType/columns/prefix가 올바르게 전송되는지 확인
      const req = await capture.waitForRequest();
      expect(req.payload).toMatchObject({
        name: '장비 학습 데이터',
        storageType: 'FILE',
        originType: 'SOURCE',
        columns: [],
        prefix: 'datasets/equipment/',
      });
      // tableName은 file_<timestamp> 형식 — 백엔드 식별자 규칙([a-z][a-z0-9_]*)을 만족
      expect((req.payload as { tableName: string }).tableName).toMatch(/^file_\d+$/);
      // bucket은 전송하지 않아 백엔드 기본값을 사용한다
      expect((req.payload as { bucket?: string }).bucket).toBeUndefined();

      // 생성 후 상세 페이지로 자동 이동
      await expect(page).toHaveURL('/data/datasets/20');
    },
  );
});
