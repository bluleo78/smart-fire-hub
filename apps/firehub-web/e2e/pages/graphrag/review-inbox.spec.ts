import { createDatePropertyReviewItem, createEntityReviewItem, createEvidenceChunk, createPropertyReviewItem, createRelationReviewItem, createSynonymReviewItem } from '../../factories/reviewItem.factory';
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

  // #311 회귀 가드 — date 정정값은 "사람이 대신 정규화한 값"이므로 형식을 만족해야 한다.
  // 무검증이던 시절엔 '작년겨울'이 그대로 그래프에 적재되어 정규화 검수 자체가 무의미해졌다.
  test('date 속성에 형식을 벗어난 정정값을 넣으면 사유가 표시되고 정정 적용이 막힌다', async ({ authenticatedPage: page }) => {
    let approveCalled = false;
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createDatePropertyReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/5/approve', (route) => {
      approveCalled = true;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createDatePropertyReviewItem({ status: 'approved' })) });
    });

    await page.goto('/knowledge-graph/review');
    await page.getByPlaceholder('YYYY-MM-DD').fill('작년겨울');

    await expect(page.getByText('YYYY-MM-DD 형식의 날짜를 입력하세요(예: 2026-01-05).')).toBeVisible();
    await expect(page.getByRole('button', { name: '정정 적용' })).toBeDisabled();
    expect(approveCalled).toBe(false);
  });

  test('date 속성에 달력상 없는 날짜를 넣어도 막고, 유효한 날짜로 고치면 정정값이 전송된다', async ({ authenticatedPage: page }) => {
    let sentBody: unknown = null;
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createDatePropertyReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/5/approve', (route) => {
      sentBody = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createDatePropertyReviewItem({ status: 'approved' })) });
    });

    await page.goto('/knowledge-graph/review');
    const input = page.getByPlaceholder('YYYY-MM-DD');
    await input.fill('2026-02-31');
    await expect(page.getByText('존재하지 않는 날짜입니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '정정 적용' })).toBeDisabled();

    await input.fill('2026-01-05');
    await expect(page.getByText('존재하지 않는 날짜입니다.')).toHaveCount(0);
    await page.getByRole('button', { name: '정정 적용' }).click();

    await expect.poll(() => (sentBody as { correctedValue?: string })?.correctedValue).toBe('2026-01-05');
  });

  test('number 속성에 숫자가 아닌 정정값을 넣으면 서버 왕복 없이 사유가 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createPropertyReviewItem()]);
    await page.goto('/knowledge-graph/review');
    await page.getByPlaceholder('정정 숫자(예: 30000000)').fill('삼천만원 정도');

    await expect(page.getByText('숫자만 입력할 수 있습니다(예: 30000000).')).toBeVisible();
    await expect(page.getByRole('button', { name: '정정 적용' })).toBeDisabled();
  });

  // 클라이언트를 우회한 요청(직접 API 호출 등)은 서버가 409 + 사유로 거절한다. 이때도 항목은 남아야 한다.
  test('서버가 정정값 형식을 409로 거절하면 사유 토스트를 띄우고 항목이 목록에 남는다', async ({ authenticatedPage: page }) => {
    const reason = '날짜 속성의 정정값은 YYYY-MM-DD 형식이어야 합니다(예: 2026-01-05). 입력값: "작년겨울"';
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createDatePropertyReviewItem()]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/5/approve', (route) =>
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ status: 409, error: 'Conflict', message: reason, timestamp: '2026-07-30T00:00:00Z', path: '/api/v1/graphrag/review-items/5/approve' }),
      }));

    await page.goto('/knowledge-graph/review');
    await page.getByPlaceholder('YYYY-MM-DD').fill('2026-01-05');
    await page.getByRole('button', { name: '정정 적용' }).click();

    await expect(page.getByText(reason)).toBeVisible();
    await expect(page.getByText('검수를 승인했습니다.')).toHaveCount(0);
    await expect(page.getByText('“작년 겨울쯤”')).toBeVisible();
  });

  // 신호 컬럼은 종류와 무관하게 한국어여야 한다 — 점수 없는 신호가 원시 enum으로 새던 회귀(#314).
  test('점수 없는 속성 항목의 신호가 원시 enum 대신 한국어 레이블로 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createPropertyReviewItem()]);
    await page.goto('/knowledge-graph/review');

    await expect(page.getByText('정규화 실패')).toBeVisible();
    await expect(page.getByText('normalization_failure')).toHaveCount(0);
  });

  test('매핑에 없는 signalType도 원시값 대신 기타로 폴백한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [
      createPropertyReviewItem({ signalType: 'some_future_signal', signalScore: null }),
    ]);
    await page.goto('/knowledge-graph/review');

    await expect(page.getByText('기타')).toBeVisible();
    await expect(page.getByText('some_future_signal')).toHaveCount(0);
  });

  test('관계 항목에서 원문 근거 보기를 누르면 청크 스니펫이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/4/evidence', [{ chunkId: 9, content: '노후 배선이 화재 원인으로 추정된다.' }]);
    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();
    await expect(page.getByText('노후 배선이 화재 원인으로 추정된다.')).toBeVisible();
  });
});
