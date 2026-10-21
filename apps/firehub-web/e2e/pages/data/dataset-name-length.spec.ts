import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetMocks } from '../../fixtures/dataset.fixture';

// 생성·수정·삭제 진입점은 권한이 있을 때만 보인다(WD-144) — 기본 인증 fixture 는 권한이 비어 있으므로 쓰기 흐름을 검증하는 이 spec 은
// 데이터셋 쓰기 권한(dataset:write·dataset:delete 포함)을 준다. 조회 전용 노출은 dataset-list-permissions.spec.ts 가 검증한다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

/**
 * 데이터셋 테이블명·칼럼명 길이 상한 E2E (WD-95)
 *
 * PostgreSQL 은 식별자를 63바이트에서 조용히 자른다. 예전엔 웹이 100자까지 허용해, 앞 63자가 같은 긴 테이블명으로
 * 새 데이터셋을 만들면 기존 데이터셋의 물리 테이블이 지워졌다. 이제 테이블명은 56자, 칼럼명은 63자까지만 허용하고,
 * 넘으면 제출 단계에서 인라인 메시지로 막아 생성 POST 자체가 나가지 않아야 한다.
 */

/** prefix 뒤를 'x' 로 채워 정확히 length 자인 이름을 만든다. */
function nameOf(prefix: string, length: number): string {
  return prefix + 'x'.repeat(length - prefix.length);
}

test.describe('데이터셋 이름 길이 상한 (WD-95)', () => {
  test('57자 테이블명은 메시지가 보이고 생성 요청을 보내지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('긴 테이블명');
    await page.getByLabel('테이블명').fill(nameOf('long_', 57));
    await page.getByPlaceholder('예: user_id').fill('col1');

    // 입력 중(제출 전) "사용 가능" 안내가 뜨면 제출 오류와 어긋난다(#644 와 같은 유형) — 뜨지 않아야 한다.
    await page.waitForTimeout(600);
    await expect(page.getByText('사용 가능한 테이블명입니다.')).toHaveCount(0);

    // 폼이 onSubmit 모드라 제출해야 zod 메시지가 나타난다.
    await page.getByRole('button', { name: '생성' }).click();
    await expect(page.getByText('테이블명은 56자 이하여야 합니다')).toBeVisible();

    // 생성 POST 가 나가지 않았음을 확인한다(메시지가 보인 뒤 잠시 더 기다려 늦은 요청도 잡는다).
    await page.waitForTimeout(300);
    expect(capture.requests).toHaveLength(0);
  });

  test('56자 테이블명과 63자 칼럼명은 통과해 생성 요청을 그대로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });

    const tableName = nameOf('ok_', 56);
    const columnName = nameOf('c_', 63);
    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('경계 테이블명');
    await page.getByLabel('테이블명').fill(tableName);
    await page.getByPlaceholder('예: user_id').fill(columnName);

    // 경계값에서는 입력 중 "사용 가능" 안내가 정상으로 떠야 한다(길이 검사가 과하게 막지 않음).
    await page.waitForTimeout(600);
    await expect(page.getByText('사용 가능한 테이블명입니다.')).toBeVisible();

    await page.getByRole('button', { name: '생성' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      tableName,
      columns: [expect.objectContaining({ columnName })],
    });
    await expect(page.getByText('테이블명은 56자 이하여야 합니다')).toHaveCount(0);
    await expect(page.getByText('칼럼명은 63자 이하여야 합니다')).toHaveCount(0);
  });

  test('64자 칼럼명은 메시지가 보이고 생성 요청을 보내지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('긴 칼럼명');
    await page.getByLabel('테이블명').fill('short_table');
    await page.getByPlaceholder('예: user_id').fill(nameOf('c_', 64));

    await page.getByRole('button', { name: '생성' }).click();
    await expect(page.getByText('칼럼명은 63자 이하여야 합니다')).toBeVisible();

    await page.waitForTimeout(300);
    expect(capture.requests).toHaveLength(0);
  });
});
