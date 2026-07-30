import { createEntityReviewItem, createEvidenceChunk, createPropertyReviewItem, createRelationReviewItem, createSynonymReviewItem } from '../../factories/reviewItem.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('AI 검수 인박스', () => {
  test('동의어·속성 항목이 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem(), createPropertyReviewItem()]);
    await page.goto('/knowledge-graph/review');

    await expect(page.getByText('전기적 요인')).toBeVisible();
    await expect(page.getByText('0.707')).toBeVisible();
    await expect(page.getByText('“수천만원대”')).toBeVisible();
  });

  test('동의어 승인 시 approve API를 호출하고 목록에서 사라진다', async ({ authenticatedPage: page }) => {
    let approveCalled = false;
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/1/approve', (route) => {
      approveCalled = true;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createSynonymReviewItem({ status: 'approved' })) });
    });

    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('전기적 요인')).toBeVisible();
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', []);
    await page.getByRole('button', { name: '승인' }).click();

    await expect(page.getByText('검수 대기 중인 항목이 없습니다.')).toBeVisible();
    expect(approveCalled).toBe(true);
  });

  test('속성 정정값 입력 후 정정 적용 시 correctedValue를 전송한다', async ({ authenticatedPage: page }) => {
    let sentBody: unknown = null;
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createPropertyReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/2/approve', async (route) => {
      sentBody = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createPropertyReviewItem({ status: 'approved' })) });
    });

    await page.goto('/knowledge-graph/review');
    await page.getByPlaceholder('정정 숫자(예: 30000000)').fill('30000000');
    await page.getByRole('button', { name: '정정 적용' }).click();

    await expect.poll(() => (sentBody as { correctedValue?: string })?.correctedValue).toBe('30000000');
  });

  test('원문 근거 보기 클릭 시 evidence를 조회해 청크를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createPropertyReviewItem()]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/2/evidence', [{ chunkId: 7, content: '약 수천만원대의 재산피해가 발생했다.' }]);

    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();

    await expect(page.getByText('약 수천만원대의 재산피해가 발생했다.')).toBeVisible();
  });

  test('동의어 항목에서 원문 근거 보기를 누르면 청크 스니펫이 표시된다', async ({ authenticatedPage: page }) => {
    const synonym = createSynonymReviewItem({ id: 777 });
    const chunk = createEvidenceChunk({ chunkId: 501 });
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [synonym]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/777/evidence', [chunk]);

    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();

    await expect(page.getByText(chunk.content)).toBeVisible();
    await expect(page.getByText(`청크 #${chunk.chunkId}`)).toBeVisible();
  });

  test('엔티티 항목이 이름·신뢰도·관계수와 함께 렌더링된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createEntityReviewItem()]);
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('노후 배선 추정')).toBeVisible();
    await expect(page.getByText('0.42')).toBeVisible();
    await expect(page.getByText(/연결 관계 1건/)).toBeVisible();
  });

  test('엔티티 적재 승인 시 correctedValue 없이 approve API를 호출하고 사라진다', async ({ authenticatedPage: page }) => {
    let sentBody: unknown = 'unset';
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createEntityReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/3/approve', (route) => {
      sentBody = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createEntityReviewItem({ status: 'approved' })) });
    });
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('노후 배선 추정')).toBeVisible();
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', []);
    await page.getByRole('button', { name: '적재' }).click();
    await expect(page.getByText('검수 대기 중인 항목이 없습니다.')).toBeVisible();
    // 엔티티 승인은 correctedValue를 보내지 않는다(undefined).
    await expect.poll(() => (sentBody as { correctedValue?: string })?.correctedValue).toBeUndefined();
  });

  test('엔티티 항목에서 원문 근거 보기를 누르면 청크 스니펫이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createEntityReviewItem()]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/3/evidence', [{ chunkId: 9, content: '노후된 배선으로 추정된다.' }]);
    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();
    await expect(page.getByText('노후된 배선으로 추정된다.')).toBeVisible();
  });

  test('관계 항목이 주어→관계→목적어·신뢰도와 함께 렌더링된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('노후 배선')).toBeVisible();
    await expect(page.getByText('창고 화재')).toBeVisible();
    await expect(page.getByText('CAUSED_BY')).toBeVisible();
    await expect(page.getByText('0.35')).toBeVisible();
  });

  test('관계 적재 승인 시 correctedValue 없이 approve API를 호출하고 사라진다', async ({ authenticatedPage: page }) => {
    let sentBody: unknown = 'unset';
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/4/approve', (route) => {
      sentBody = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createRelationReviewItem({ status: 'approved' })) });
    });
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('CAUSED_BY')).toBeVisible();
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', []);
    await page.getByRole('button', { name: '적재' }).click();
    await expect(page.getByText('검수 대기 중인 항목이 없습니다.')).toBeVisible();
    await expect.poll(() => (sentBody as { correctedValue?: string })?.correctedValue).toBeUndefined();
  });

  // #310 회귀 가드 — 끝점 없는 관계를 적재 승인하면 서버가 409 + 사유를 준다.
  // 이때 성공 토스트를 띄우고 행을 지워버리면 검수 결과가 조용히 유실되므로,
  // 반드시 사유를 그대로 노출하고 항목을 목록에 남겨 재시도할 수 있어야 한다.
  test('끝점 없는 관계 적재가 409로 거절되면 사유 토스트를 띄우고 항목이 목록에 남는다', async ({ authenticatedPage: page }) => {
    const reason = '주어/목적어 엔티티가 그래프에 없어 관계를 적재할 수 없습니다(subject=12:노후 배선, object=34:창고 화재).';
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/4/approve', (route) =>
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ status: 409, error: 'Conflict', message: reason, timestamp: '2026-07-30T00:00:00Z', path: '/api/v1/graphrag/review-items/4/approve' }),
      }));

    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('CAUSED_BY')).toBeVisible();
    await page.getByRole('button', { name: '적재' }).click();

    // 서버가 알려준 구체적 사유가 그대로 보여야 한다(일반 폴백 문구로 퇴화하면 원인을 알 수 없다).
    await expect(page.getByText(reason)).toBeVisible();
    await expect(page.getByText('검수를 승인했습니다.')).toHaveCount(0);
    // 항목은 여전히 검수 대기 상태로 남아 있어야 한다.
    await expect(page.getByText('CAUSED_BY')).toBeVisible();
    await expect(page.getByText('검수 대기 중인 항목이 없습니다.')).toHaveCount(0);
  });

  test('관계 항목에서 원문 근거 보기를 누르면 청크 스니펫이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/4/evidence', [{ chunkId: 9, content: '노후 배선이 화재 원인으로 추정된다.' }]);
    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();
    await expect(page.getByText('노후 배선이 화재 원인으로 추정된다.')).toBeVisible();
  });
});
