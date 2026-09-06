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

  // #422 — pending 큐가 페이지 크기(50)를 넘기면 무한스크롤로 나머지를 이어 불러온다.
  test.describe('무한스크롤 페이지네이션 (#422)', () => {
    test('첫 페이지가 꽉 차면 "더 보기"가 나타나고, 클릭 시 page=1로 다음 페이지를 요청해 이어붙인다', async ({ authenticatedPage: page }) => {
      // 첫 페이지(size=50) 응답은 꽉 채워 hasMore를 유도한다. 두 번째 페이지는 1건만 반환해 이어붙임을 확인한다.
      const page0Items = Array.from({ length: 50 }, (_, i) =>
        createSynonymReviewItem({ id: i + 1, payload: { entityType: 'Cause', nameA: `요인${i}`, nameB: `누전${i}` } }));
      const page1Items = [createSynonymReviewItem({ id: 999, payload: { entityType: 'Cause', nameA: '마지막페이지항목', nameB: '누전999' } })];

      const requestedPages: (string | null)[] = [];
      await page.route((url) => url.pathname === '/api/v1/graphrag/review-items', (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const params = new URL(route.request().url()).searchParams;
        requestedPages.push(params.get('page'));
        // page/size가 opt-in이라는 것을 함께 확인 — 백엔드 계약대로 size=50이 항상 실려간다.
        expect(params.get('size')).toBe('50');
        const body = params.get('page') === '1' ? page1Items : page0Items;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      });

      await page.goto('/knowledge-graph/review');
      await expect(page.getByText('요인0')).toBeVisible();
      await expect(page.getByText('마지막페이지항목')).toHaveCount(0);

      const moreButton = page.getByRole('button', { name: '더 보기' });
      await expect(moreButton).toBeVisible();
      await moreButton.click();

      // 두 번째 페이지가 로드되어 기존 50건 위에 이어붙는다(교체가 아니라 누적).
      await expect(page.getByText('마지막페이지항목')).toBeVisible();
      await expect(page.getByText('요인0')).toBeVisible();
      expect(requestedPages).toEqual(['0', '1']);

      // 두 번째 페이지가 size(50)보다 적어 hasMore가 false로 떨어져 버튼이 사라진다.
      await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
    });

    test('전체 pending이 페이지 크기 미만이면 "더 보기"가 나타나지 않는다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem(), createPropertyReviewItem()]);
      await page.goto('/knowledge-graph/review');

      await expect(page.getByText('전기적 요인')).toBeVisible();
      await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
    });
  });

  // #495 — 탭 필터가 로컬 state로만 있으면 라우트 이동 후 뒤로가기/새로고침 시 조용히 '전체'로
  // 리셋된다. URL 쿼리 파라미터(`?type=`)에 반영해 히스토리·새로고침에서도 유지되는지 검증한다.
  test.describe('탭 필터 URL 반영 (#495)', () => {
    test('탭 클릭 시 API가 itemType으로 재호출되고 URL에 type 쿼리 파라미터가 반영된다', async ({ authenticatedPage: page }) => {
      const requestedItemTypes: (string | null)[] = [];
      await page.route((url) => url.pathname === '/api/v1/graphrag/review-items', (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const params = new URL(route.request().url()).searchParams;
        requestedItemTypes.push(params.get('itemType'));
        const body = params.get('itemType') === 'property_normalization'
          ? [createPropertyReviewItem()] : [createSynonymReviewItem(), createPropertyReviewItem()];
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      });

      await page.goto('/knowledge-graph/review');
      await expect(page.getByRole('tab', { name: '전체' })).toHaveAttribute('aria-selected', 'true');
      expect(new URL(page.url()).searchParams.get('type')).toBeNull();

      await page.getByRole('tab', { name: '속성' }).click();
      await expect(page.getByRole('tab', { name: '속성' })).toHaveAttribute('aria-selected', 'true');
      await expect.poll(() => new URL(page.url()).searchParams.get('type')).toBe('property_normalization');
      expect(requestedItemTypes).toContain('property_normalization');
    });

    test('속성 탭 선택 후 다른 페이지로 이동했다가 뒤로가기하면 속성 탭이 그대로 선택되어 있다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createPropertyReviewItem()]);

      await page.goto('/knowledge-graph/review');
      await page.getByRole('tab', { name: '속성' }).click();
      await expect(page.getByRole('tab', { name: '속성' })).toHaveAttribute('aria-selected', 'true');

      await page.goto('/knowledge-graph/explore');
      await page.goBack();

      await expect(page.getByRole('tab', { name: '속성' })).toHaveAttribute('aria-selected', 'true');
      await expect(page.getByRole('tab', { name: '전체' })).toHaveAttribute('aria-selected', 'false');
    });

    test('속성 탭 선택 후 새로고침해도 필터가 유지된다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createPropertyReviewItem()]);

      await page.goto('/knowledge-graph/review');
      await page.getByRole('tab', { name: '속성' }).click();
      await expect(page.getByRole('tab', { name: '속성' })).toHaveAttribute('aria-selected', 'true');

      await page.reload();

      await expect(page.getByRole('tab', { name: '속성' })).toHaveAttribute('aria-selected', 'true');
    });

    test('알 수 없는 type 쿼리 파라미터로 직접 진입하면 전체 탭으로 폴백한다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem(), createPropertyReviewItem()]);

      await page.goto('/knowledge-graph/review?type=not_a_real_type');

      await expect(page.getByRole('tab', { name: '전체' })).toHaveAttribute('aria-selected', 'true');
    });
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
    await page.getByTestId('review-decide-confirm-action').click();

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
    await page.getByTestId('review-decide-confirm-action').click();

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
    await page.getByTestId('review-decide-confirm-action').click();
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
    await page.getByTestId('review-decide-confirm-action').click();
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
    await page.getByTestId('review-decide-confirm-action').click();

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
    await page.getByTestId('review-decide-confirm-action').click();

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
    await page.getByTestId('review-decide-confirm-action').click();

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

  // #478 — low_confidence 신호인데 confidence가 null로 저장되면 "신뢰도"라는 라벨만 남아
  // "신뢰도가 낮다"와 "값 자체가 없다"를 검수자가 구분할 수 없었다. 점수 없음을 명시적으로 표기해야 한다.
  test('저신뢰 신호인데 confidence가 null이면 값 없음을 명시적으로 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [
      createEntityReviewItem({ signalScore: null }),
    ]);
    await page.goto('/knowledge-graph/review');

    await expect(page.getByText('신뢰도 정보 없음')).toBeVisible();
    // "신뢰도"만 단독으로 남아 점수 유무를 구분 못 하던 회귀를 막는다.
    await expect(page.getByText('신뢰도', { exact: true })).toHaveCount(0);
  });

  test('관계 항목에서 원문 근거 보기를 누르면 청크 스니펫이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/4/evidence', [{ chunkId: 9, content: '노후 배선이 화재 원인으로 추정된다.' }]);
    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();
    await expect(page.getByText('노후 배선이 화재 원인으로 추정된다.')).toBeVisible();
  });

  // #398 회귀 가드 — 다른 세션이 같은 항목을 먼저 처리하면 서버가
  // "이미 처리된 항목입니다(status=rejected): {id}" 형태로 409를 준다. 이때는
  // (1) 내부 DB row id를 그대로 노출하지 않고 검수자 친화적 문구로 대체하고
  // (2) pending 목록을 무효화해 stale 행을 제거해야 한다 — 그렇지 않으면 같은 행을
  // 다시 눌러도 항상 같은 409만 반복되는 죽은 UI로 남는다.
  test('이미 처리된 항목 거부 시 내부 id 없는 문구를 띄우고 목록을 새로고침해 행을 제거한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem({ id: 51 })]);
    await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/51/reject', (route) =>
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 409, error: 'Conflict',
          message: '이미 처리된 항목입니다(status=rejected): 51',
          timestamp: '2026-08-31T00:00:00Z', path: '/api/v1/graphrag/review-items/51/reject',
        }),
      }));

    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('전기적 요인')).toBeVisible();

    // 실패 응답 이후 재조회(invalidate)에서는 다른 세션이 이미 처리했다는 사실이 반영되어 목록이 빈다.
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', []);

    await page.getByRole('button', { name: '거부' }).click();
    await page.getByTestId('review-decide-confirm-action').click();

    // 내부 row id(51)가 그대로 노출되는 백엔드 원문 메시지가 아니라 검수자 친화적 문구여야 한다.
    await expect(page.getByText('이미 다른 사용자가 처리한 항목입니다. 목록을 새로고침했습니다.')).toBeVisible();
    await expect(page.getByText('이미 처리된 항목입니다')).toHaveCount(0);
    await expect(page.getByText(': 51')).toHaveCount(0);

    // stale 행이 목록에서 사라져야 한다 — 남아있으면 재클릭해도 같은 409만 반복되는 죽은 UI가 된다.
    await expect(page.getByText('검수 대기 중인 항목이 없습니다.')).toBeVisible();
    await expect(page.getByText('전기적 요인')).toHaveCount(0);
  });

  // #315 — 승인/적재/정정 적용/거부 4개 조치는 그래프를 바꾸거나(비가역) 항목을 목록에서 영구히 없앤다.
  // 행 높이가 낮고 버튼이 인접해 오클릭 위험이 실재하므로 전건 확인 게이트를 둔다.
  test.describe('확정 전 확인 다이얼로그 (#315)', () => {
    test('승인 버튼만 눌러서는 API가 호출되지 않고 확인 다이얼로그가 뜬다', async ({ authenticatedPage: page }) => {
      let approveCalled = false;
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
      await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/1/approve', (route) => {
        approveCalled = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createSynonymReviewItem({ status: 'approved' })) });
      });

      await page.goto('/knowledge-graph/review');
      await page.getByRole('button', { name: '승인' }).click();

      // 다이얼로그가 열려 있는 동안에도 서버는 호출되지 않아야 한다 — 게이트가 실제로 존재한다는 증거.
      await expect(page.getByTestId('review-decide-confirm')).toBeVisible();
      expect(approveCalled).toBe(false);
      // 기본 포커스는 취소여야 한다(Enter 오입력이 곧바로 확정되지 않게).
      await expect(page.getByTestId('review-decide-confirm-cancel')).toBeFocused();
    });

    test('확인 다이얼로그에서 취소하면 mutation이 발생하지 않고 행이 남는다', async ({ authenticatedPage: page }) => {
      let rejectCalled = false;
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
      await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/1/reject', (route) => {
        rejectCalled = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createSynonymReviewItem({ status: 'rejected' })) });
      });

      await page.goto('/knowledge-graph/review');
      await page.getByRole('button', { name: '거부' }).click();
      await page.getByTestId('review-decide-confirm-cancel').click();

      await expect(page.getByTestId('review-decide-confirm')).toHaveCount(0);
      expect(rejectCalled).toBe(false);
      await expect(page.getByText('전기적 요인')).toBeVisible();
      await expect(page.getByText('검수를 거부했습니다.')).toHaveCount(0);
    });

    test('거부 확인 시 reject API를 호출하고 성공 토스트가 뜬다', async ({ authenticatedPage: page }) => {
      let rejectCalled = false;
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
      await page.route((url) => url.pathname === '/api/v1/graphrag/review-items/1/reject', (route) => {
        rejectCalled = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createSynonymReviewItem({ status: 'rejected' })) });
      });

      await page.goto('/knowledge-graph/review');
      await page.getByRole('button', { name: '거부' }).click();
      await page.getByTestId('review-decide-confirm-action').click();

      await expect(page.getByText('검수를 거부했습니다.')).toBeVisible();
      expect(rejectCalled).toBe(true);
    });

    test('동의어 승인 다이얼로그는 병합 대상과 비가역성을 알리고 destructive 액션을 쓴다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
      await page.goto('/knowledge-graph/review');
      await page.getByRole('button', { name: '승인' }).click();

      const dialog = page.getByTestId('review-decide-confirm');
      await expect(dialog).toHaveAttribute('data-item-type', 'synonym_merge');
      await expect(dialog).toContainText('동의어를 병합합니다');
      await expect(dialog).toContainText('“전기적 요인”와 “분전반의 누전”를 같은 Cause로 병합합니다');
      await expect(dialog).toContainText('되돌릴 수 없습니다');
      // 확인 라벨은 행에서 누른 동사를 그대로 재사용한다.
      await expect(page.getByTestId('review-decide-confirm-action')).toHaveText('승인');
      await expect(page.getByTestId('review-decide-confirm-action')).toHaveClass(/bg-destructive/);
    });

    test('속성 정정 다이얼로그는 원문과 입력한 정정값을 그대로 에코한다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createPropertyReviewItem()]);
      await page.goto('/knowledge-graph/review');
      await page.getByPlaceholder('정정 숫자(예: 30000000)').fill('30000000');
      await page.getByRole('button', { name: '정정 적용' }).click();

      const dialog = page.getByTestId('review-decide-confirm');
      await expect(dialog).toContainText('속성 정정값을 반영합니다');
      await expect(dialog).toContainText('Incident.피해액');
      await expect(dialog).toContainText('“수천만원대”');
      await expect(dialog).toContainText('“30000000”');
      await expect(page.getByTestId('review-decide-confirm-action')).toHaveText('정정 적용');
      // 되돌리기 난이도가 낮은 조치까지 빨강이면 경고가 무의미해진다.
      await expect(page.getByTestId('review-decide-confirm-action')).not.toHaveClass(/bg-destructive/);
    });

    test('엔티티 적재 다이얼로그는 함께 적재되는 연결 관계 건수를 알린다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createEntityReviewItem()]);
      await page.goto('/knowledge-graph/review');
      await page.getByRole('button', { name: '적재' }).click();

      const dialog = page.getByTestId('review-decide-confirm');
      await expect(dialog).toContainText('엔티티를 그래프에 적재합니다');
      await expect(dialog).toContainText('“노후 배선 추정”(Cause) 엔티티를 그래프에 적재합니다.');
      await expect(dialog).toContainText('연결된 관계 1건도 함께 적재됩니다');
      await expect(page.getByTestId('review-decide-confirm-action')).toHaveText('적재');
      await expect(page.getByTestId('review-decide-confirm-action')).not.toHaveClass(/bg-destructive/);
    });

    test('관계 적재 다이얼로그는 주어→관계→목적어를 그대로 보여준다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
      await page.goto('/knowledge-graph/review');
      await page.getByRole('button', { name: '적재' }).click();

      const dialog = page.getByTestId('review-decide-confirm');
      await expect(dialog).toContainText('관계를 그래프에 적재합니다');
      await expect(dialog).toContainText('“노후 배선” → CAUSED_BY → “창고 화재” 관계를 그래프 엣지로 적재합니다.');
      await expect(page.getByTestId('review-decide-confirm-action')).not.toHaveClass(/bg-destructive/);
    });

    test('거부 다이얼로그는 item_type별 대상 요약과 함께 destructive 액션을 쓴다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
      await page.goto('/knowledge-graph/review');
      await page.getByRole('button', { name: '거부' }).click();

      const dialog = page.getByTestId('review-decide-confirm');
      await expect(dialog).toHaveAttribute('data-action', 'reject');
      await expect(dialog).toContainText('검수를 거부합니다');
      await expect(dialog).toContainText('검수 목록에서 사라집니다');
      await expect(dialog).toContainText('대상: “노후 배선” → CAUSED_BY → “창고 화재”');
      await expect(page.getByTestId('review-decide-confirm-action')).toHaveText('거부');
      await expect(page.getByTestId('review-decide-confirm-action')).toHaveClass(/bg-destructive/);
    });

    // #311 회귀 가드 — 확인 게이트가 생겼다고 형식 위반 정정값이 다이얼로그를 통해 새어나가면 안 된다.
    test('정정값 형식 위반 상태에서는 트리거가 다이얼로그를 열지 못한다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createDatePropertyReviewItem()]);
      await page.goto('/knowledge-graph/review');
      await page.getByPlaceholder('YYYY-MM-DD').fill('작년겨울');

      await expect(page.getByText('YYYY-MM-DD 형식의 날짜를 입력하세요(예: 2026-01-05).')).toBeVisible();
      await page.getByRole('button', { name: '정정 적용' }).click({ force: true });
      await expect(page.getByTestId('review-decide-confirm')).toHaveCount(0);
    });
  });

  // #337 회귀 가드 — #315의 `autoFocus`가 #328 포커스 복귀 훅의 트리거 캡처를 가로채
  // 이 페이지의 확인 다이얼로그만 닫힘 후 포커스가 <body>로 떨어지던 결함.
  // 세 경로(ESC / 취소 / 확정 후 행 소멸) 모두 키보드 사용자가 원래 위치로 돌아와야 한다.
  test.describe('확인 다이얼로그 닫힘 후 포커스 복귀 (#337)', () => {
    test('ESC로 닫으면 포커스가 트리거(승인) 버튼으로 돌아온다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
      await page.goto('/knowledge-graph/review');

      // 키보드 전용 사용자와 동일한 경로 — 트리거에 포커스를 두고 Enter로 연다.
      const trigger = page.getByRole('button', { name: '승인' });
      await trigger.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByTestId('review-decide-confirm-cancel')).toBeFocused();

      await page.keyboard.press('Escape');
      await expect(page.getByTestId('review-decide-confirm')).toHaveCount(0);
      await expect(trigger).toBeFocused();
    });

    test('취소 버튼으로 닫으면 포커스가 트리거(거부) 버튼으로 돌아온다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
      await page.goto('/knowledge-graph/review');

      const trigger = page.getByRole('button', { name: '거부' });
      await trigger.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByTestId('review-decide-confirm-cancel')).toBeFocused();

      await page.keyboard.press('Enter'); // 기본 포커스가 취소이므로 Enter = 취소
      await expect(page.getByTestId('review-decide-confirm')).toHaveCount(0);
      await expect(trigger).toBeFocused();
    });

    test('거부를 확정해 행이 사라지면 포커스가 표로 복귀한다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
      await mockApi(page, 'POST', '/api/v1/graphrag/review-items/1/reject', createSynonymReviewItem({ status: 'rejected' }));
      await page.goto('/knowledge-graph/review');

      await page.getByRole('button', { name: '거부' }).focus();
      await page.keyboard.press('Enter');
      // 확정 직후 목록은 비게 되므로 트리거 자체가 사라진다 → restoreFocusRef(표)가 받아야 한다.
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', []);
      await page.getByTestId('review-decide-confirm-action').click();

      await expect(page.getByText('검수 대기 중인 항목이 없습니다.')).toBeVisible();
      await expect(page.getByTestId('review-inbox-table')).toBeFocused();
    });
  });

  // #338/#340/#342 회귀 가드 — 표의 반복 컨트롤이 스크린리더에 상태·대상·오류 사유를 전달하는지 검증한다.
  // 시각 라벨(승인/거부/원문 근거 보기)은 행마다 동일하므로 접근 가능한 이름·ARIA 배선만이 유일한 구분 수단이다.
  test.describe('표 반복 컨트롤의 접근성 배선 (#338, #340, #342)', () => {
    test('근거 토글이 aria-expanded로 상태를, aria-controls로 실제 패널을 가리킨다 (#338)', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem({ id: 777 })]);
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items/777/evidence', [createEvidenceChunk({ chunkId: 501 })]);
      await page.goto('/knowledge-graph/review');

      const toggle = page.getByRole('button', { name: /원문 근거 보기/ });
      await expect(toggle).toHaveAttribute('aria-expanded', 'false');

      await toggle.click();
      const expanded = page.getByRole('button', { name: /근거 숨기기/ });
      await expect(expanded).toHaveAttribute('aria-expanded', 'true');

      // aria-controls가 가리키는 id가 실제 DOM에 존재하고 근거 내용을 담고 있어야 한다(공허한 배선 방지).
      const controlsId = await expanded.getAttribute('aria-controls');
      expect(controlsId).toBeTruthy();
      const panel = page.locator(`#${controlsId!}`);
      await expect(panel).toContainText('스프링클러 설비 오작동');

      // 로딩→결과 전환이 조용히 일어나지 않도록 live region이 패널 안에 있어야 한다(SC 4.1.3).
      await expect(panel.locator('[aria-live="polite"]')).toHaveCount(1);
    });

    test('행마다 반복되는 근거 토글·조치 버튼의 접근 가능한 이름이 서로 다른 대상을 식별한다 (#338, #342)', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [
        createSynonymReviewItem(), createRelationReviewItem(),
      ]);
      await page.goto('/knowledge-graph/review');

      // 행 단위 접근 가능한 이름 수집 — 표 본문의 각 <tr> 안 버튼들.
      const namesOf = async (i: number) =>
        page.locator('tbody tr').nth(i).locator('button')
          .evaluateAll((els) => els.map((e) => e.getAttribute('aria-label') ?? e.textContent ?? ''));

      await expect(page.getByText('전기적 요인')).toBeVisible();
      const row0 = await namesOf(0);
      const row1 = await namesOf(1);
      expect(row0.length).toBeGreaterThan(0);

      // 각 행의 이름에 그 행의 대상이 들어가 있어야 한다.
      expect(row0.join('|')).toContain('전기적 요인');
      expect(row1.join('|')).toContain('노후 배선');
      // 두 행의 같은 조치 버튼이 동일한 이름을 갖지 않아야 한다(rotor에서 구분 불가 방지).
      for (const n of row0) expect(row1).not.toContain(n);
      // 시각 라벨은 짧게 유지된다 — 표 밀도를 위해 텍스트는 그대로.
      await expect(page.locator('tbody tr').nth(0).getByRole('button', { name: /거부$/ })).toHaveText('거부');
    });

    test('속성 정정값 오류가 aria-describedby로 입력에 연결되어 사유가 낭독된다 (#340)', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createDatePropertyReviewItem()]);
      await page.goto('/knowledge-graph/review');

      const input = page.getByLabel('occurredAt 정정값');
      // 정상 입력 상태에서는 오류 연결이 없어야 한다.
      await input.fill('2026-01-02');
      await expect(input).toHaveAttribute('aria-invalid', 'false');
      await expect(input).not.toHaveAttribute('aria-describedby', /.+/);

      // 달력상 존재하지 않는 날짜 → aria-invalid + 사유 연결.
      await input.fill('2026-13-99');
      await expect(input).toHaveAttribute('aria-invalid', 'true');
      const describedBy = await input.getAttribute('aria-describedby');
      expect(describedBy).toBeTruthy();
      // 참조된 노드가 실제로 존재하고 사유 문구를 담고 있어야 한다(#300과 동일 배선).
      await expect(page.locator(`#${describedBy!}`)).toHaveText('존재하지 않는 날짜입니다.');
      // 입력 도중 등장하는 오류라 role=alert로 즉시 고지되어야 한다.
      await expect(page.locator(`#${describedBy!}`)).toHaveAttribute('role', 'alert');
    });
  });
});
