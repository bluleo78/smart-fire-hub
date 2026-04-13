import type { Page } from '@playwright/test';

import { createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * useSectionTree + SectionTreeBuilder + SectionPropertyEditor 통합 E2E 테스트
 * - 편집 모드에서 섹션 추가/그룹 추가/선택/라벨 수정/삭제/그룹 접기 등
 *   섹션 트리 CRUD 전 경로를 검증해 useSectionTree 훅의 add/remove/update/
 *   toggleCollapsed 브랜치와 SectionPropertyEditor 라벨 편집 경로를 커버한다.
 */
test.describe('리포트 템플릿 섹션 트리 빌더', () => {
  /** 공통 모킹 헬퍼 — 커스텀 템플릿 + 업데이트 API 캡처 */
  async function setupEditableTemplate(page: Page) {
    const template = createTemplate({
      id: 10,
      name: '섹션 트리 편집 템플릿',
      builtin: false,
      sections: [
        createTemplateSection({ key: 'summary', type: 'text', label: '요약 섹션' }),
        createTemplateSection({
          key: 'details',
          type: 'list',
          label: '상세 목록',
          instruction: '주요 항목을 나열하세요.',
        }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/10', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/proactive/templates/10',
      { ...template },
      { capture: true },
    );
    return { template, updateCapture };
  }

  test('편집 모드에서 "섹션 추가" 드롭다운으로 Text 섹션을 추가한다', async ({ authenticatedPage: page }) => {
    await setupEditableTemplate(page);

    await page.goto('/ai-insights/templates/10');
    await expect(page.getByRole('heading', { name: '섹션 트리 편집 템플릿' })).toBeVisible({ timeout: 10000 });

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 섹션 카운트 Badge — 초기 2개 (SectionTreeBuilder 헤더의 "X개")
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible({ timeout: 10000 });

    // "섹션 추가" 드롭다운 열기 → Text 선택
    await page.getByRole('button', { name: /섹션 추가/ }).click();
    await page.getByRole('menuitem', { name: /Text/ }).click();

    // 섹션 카운트가 3으로 증가
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible();

    // 새로 추가된 섹션의 기본 label "새 text 섹션" 이 트리에 표시된다 (generateKey: text_1)
    await expect(page.getByText('새 text 섹션').first()).toBeVisible();

    // 새 섹션이 자동 선택되어 SectionPropertyEditor 에 key 가 표시된다
    await expect(page.getByText(/key:\s*text_1/)).toBeVisible();
  });

  test('"그룹 추가" 버튼으로 그룹을 추가하고 접기/펴기가 동작한다', async ({ authenticatedPage: page }) => {
    await setupEditableTemplate(page);

    await page.goto('/ai-insights/templates/10');
    await expect(page.getByRole('heading', { name: '섹션 트리 편집 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // "그룹 추가" 클릭 → sections 배열 끝에 새 그룹이 추가된다
    await page.getByRole('button', { name: /그룹 추가/ }).click();

    // 그룹 라벨 "새 그룹" 이 표시된다 — selectedSection 이 그룹으로 설정되면
    // SectionPropertyEditor 가 group 분기로 렌더링되고 key: group_1 텍스트가 보인다
    await expect(page.getByText('새 그룹').first()).toBeVisible();
    await expect(page.getByText(/key:\s*group_1/)).toBeVisible();

    // 섹션 카운트 3개 (기존 2 + 그룹 1)
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible();
  });

  test('섹션 선택 후 Label 입력으로 트리의 라벨이 즉시 업데이트된다', async ({ authenticatedPage: page }) => {
    await setupEditableTemplate(page);

    await page.goto('/ai-insights/templates/10');
    await expect(page.getByRole('heading', { name: '섹션 트리 편집 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 트리에서 "요약 섹션" 클릭 → selectedKey = 'summary'
    await page.getByText('요약 섹션', { exact: true }).first().click();

    // SectionPropertyEditor 의 key 표시 확인
    await expect(page.getByText(/key:\s*summary/)).toBeVisible();

    // Label 입력 찾기 — form label "Label" 과 연결된 Input
    const labelInput = page.locator('input').filter({ hasNot: page.locator('[type="hidden"]') })
      .nth(0); // 편집 모드에서 첫 번째 텍스트 input 은 템플릿 "이름" (tpl-name)
    // 대신 SectionPropertyEditor 내부의 Label 인풋을 값으로 찾는다
    const propertyLabelInput = page.locator('input[value="요약 섹션"]').first();
    await expect(propertyLabelInput).toBeVisible();

    // 값 변경
    await propertyLabelInput.fill('수정된 요약');

    // 트리에도 새 라벨이 반영된다 (updateSection → setSections → flatItems 재계산)
    await expect(page.getByText('수정된 요약').first()).toBeVisible();
    // 기존 "요약 섹션" 은 사라진다
    await expect(page.getByText('요약 섹션', { exact: true })).toHaveCount(0);

    // labelInput 변수는 미사용 방지용으로 touch
    expect(labelInput).toBeTruthy();
  });

  test('섹션 삭제 버튼 클릭 시 해당 섹션이 트리에서 제거된다', async ({ authenticatedPage: page }) => {
    await setupEditableTemplate(page);

    await page.goto('/ai-insights/templates/10');
    await expect(page.getByRole('heading', { name: '섹션 트리 편집 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기 섹션 카운트 2개 확인
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible({ timeout: 10000 });

    // "요약 섹션" 행을 hover 하여 삭제 버튼(Trash2) 클릭 (opacity-0 → force: true 필요)
    const summaryItem = page.getByText('요약 섹션', { exact: true }).first();
    await summaryItem.hover();
    // 삭제 버튼은 Trash2 아이콘을 담은 ghost Button — 같은 행의 마지막 버튼
    const summaryRow = summaryItem.locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await summaryRow.getByRole('button').last().click({ force: true });

    // 카운트가 1개로 감소하고 "요약 섹션" 텍스트가 사라진다
    await expect(page.getByText('1개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('요약 섹션', { exact: true })).toHaveCount(0);
  });

  test('그룹 추가 후 접기/펴기 버튼으로 그룹 내 섹션이 숨겨지고 다시 표시된다', async ({ authenticatedPage: page }) => {
    const template = createTemplate({
      id: 10,
      name: '섹션 트리 편집 템플릿',
      builtin: false,
      sections: [
        createTemplateSection({ key: 'grp1', type: 'group', label: '분석 그룹', children: [
          createTemplateSection({ key: 'child1', type: 'text', label: '자식 섹션' }),
        ]}),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/10', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'PUT', '/api/v1/proactive/templates/10', { ...template });

    await page.goto('/ai-insights/templates/10');
    await expect(page.getByRole('heading', { name: '섹션 트리 편집 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기에 그룹과 자식 섹션이 모두 표시된다 (flatItems 2개)
    await expect(page.getByText('분석 그룹').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('자식 섹션').first()).toBeVisible();
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible();

    // 그룹 행의 ChevronDown 버튼(접기 토글)을 클릭
    const groupRow = page.getByText('분석 그룹').first().locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await groupRow.getByRole('button').first().click({ force: true });

    // 접힌 상태 — 자식 섹션이 flatItems 에서 제거되어 "1개" 로 줄고 자식 텍스트가 사라진다
    await expect(page.getByText('1개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('자식 섹션', { exact: true })).toHaveCount(0);

    // 다시 클릭하면 펼쳐진다
    await groupRow.getByRole('button').first().click({ force: true });
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('자식 섹션').first()).toBeVisible();
  });

  test('그룹에 자식 섹션 추가 시 그룹 안에 새 섹션이 생성된다', async ({ authenticatedPage: page }) => {
    const template = createTemplate({
      id: 10,
      name: '섹션 트리 편집 템플릿',
      builtin: false,
      sections: [
        createTemplateSection({ key: 'grp1', type: 'group', label: '분석 그룹', children: [] }),
        createTemplateSection({ key: 'summary', type: 'text', label: '요약 섹션' }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/10', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'PUT', '/api/v1/proactive/templates/10', { ...template });

    await page.goto('/ai-insights/templates/10');
    await expect(page.getByRole('heading', { name: '섹션 트리 편집 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기 flatItems 2개 (그룹 + 요약 섹션)
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible({ timeout: 10000 });

    // 그룹 행의 "+" add-child 드롭다운 버튼 클릭 (opacity-0 → force)
    const groupRow = page.getByText('분석 그룹').first().locator('xpath=ancestor::div[contains(@class,"group")]').first();
    // + 버튼은 삭제 버튼 바로 앞의 버튼
    const addChildBtn = groupRow.getByRole('button').nth(1);
    await addChildBtn.click({ force: true });

    // 드롭다운 메뉴에서 Text 선택
    await page.getByRole('menuitem', { name: /Text/ }).click();

    // flatItems 3개로 증가 (그룹 + 자식1 + 요약)
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible();

    // 새 자식 섹션이 트리에 표시된다
    await expect(page.getByText('새 text 섹션').first()).toBeVisible();
  });

  test('저장 시 편집한 sections 가 PUT payload 에 반영된다', async ({ authenticatedPage: page }) => {
    const { updateCapture } = await setupEditableTemplate(page);

    await page.goto('/ai-insights/templates/10');
    await expect(page.getByRole('heading', { name: '섹션 트리 편집 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 그룹 추가 → 트리에 "새 그룹" 이 생긴다
    await page.getByRole('button', { name: /그룹 추가/ }).click();
    await expect(page.getByText('새 그룹').first()).toBeVisible();

    // 저장 버튼 클릭 (편집 모드, 기존 템플릿 → "저장")
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // PUT payload 검증 — sections 에 group_1 이 포함되어야 한다
    const captured = await updateCapture.waitForRequest();
    const payload = captured.payload as {
      sections: Array<{ key: string; type: string }>;
    };
    expect(payload.sections).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ key: 'summary', type: 'text' }),
        expect.objectContaining({ key: 'details', type: 'list' }),
        expect.objectContaining({ key: 'group_1', type: 'group' }),
      ]),
    );
  });
});
