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

  test('관계 항목에서 원문 근거 보기를 누르면 청크 스니펫이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createRelationReviewItem()]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/4/evidence', [{ chunkId: 9, content: '노후 배선이 화재 원인으로 추정된다.' }]);
    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();
    await expect(page.getByText('노후 배선이 화재 원인으로 추정된다.')).toBeVisible();
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
});
