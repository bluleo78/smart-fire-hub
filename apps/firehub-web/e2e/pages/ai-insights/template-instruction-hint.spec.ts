import type { Page } from '@playwright/test';

import type { TemplateSection } from '../../../src/api/proactive';
import { formatUnsupportedValueHint, REPORT_NUMBER_SOURCE_GUIDE } from '../../../src/lib/report-instruction-hints';
import { createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 양식 섹션 지시 — 데이터에 없는 값 요구 안내 (WD-774).
 *
 * WD-767 이 리포트 수치를 조회 결과와 대조해 출처 없는 수치에 경고를 붙인다. 섹션 지시가 목표·전망·달성률처럼
 * 데이터에 없는 값을 요구하면 모델이 빈칸을 숫자로 채우므로, 지시를 쓰는 섹션 속성 편집기에서 미리 알린다.
 * 안내는 늘 보이고, 주의 문구는 주의 낱말이 있을 때만 보이며, 어느 쪽도 저장을 막지 않는다.
 */

// 지시 칸의 기본 설명(도움말 + 수치 출처 안내) — 안내 문구는 src/lib 원본을 그대로 써서 문구를 고쳐도 따라간다
const BASE_DESCRIPTION = `이 지시는 AI 프롬프트에 포함되어 섹션 내용 생성을 안내합니다. ${REPORT_NUMBER_SOURCE_GUIDE}`;

/** 섹션 속성 편집기의 AI 지시 입력 칸. */
const instructionField = (page: Page) => page.getByLabel('AI 지시 (Instruction)');
/** 지시 칸 아래 주의 문구. */
const unsupportedHint = (page: Page) => page.getByTestId('instruction-unsupported-hint');

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

/** 새 양식 화면을 열고 Text 섹션을 하나 추가한다 — 추가한 섹션이 바로 선택돼 속성 편집기가 열린다. */
async function openNewTemplateWithTextSection(page: Page) {
  await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await page.goto('/ai-insights/templates/new');
  await expect(page.getByRole('heading', { name: '새 템플릿' })).toBeVisible();
  await page.getByRole('button', { name: /섹션 추가/ }).click();
  await page.getByRole('menuitem', { name: /Text/ }).click();
  await expect(page.getByText(/key:\s*text_1/)).toBeVisible();
}

/** 주어진 섹션을 가진 커스텀 양식을 열고 편집 모드로 들어간다. */
async function openTemplateEditor(page: Page, id: number, sections: TemplateSection[]) {
  const template = createTemplate({ id, name: '지시 안내 양식', builtin: false, sections });
  await mockApi(page, 'GET', `/api/v1/proactive/templates/${id}`, template);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await page.goto(`/ai-insights/templates/${id}`);
  await expect(page.getByRole('heading', { name: '지시 안내 양식' })).toBeVisible({ timeout: 10000 });
  await page.getByRole('button', { name: '편집' }).click();
  await expect(page.locator('#tpl-name')).toBeVisible();
  return template;
}

test.describe('리포트 양식 섹션 지시 안내 (WD-774)', () => {
  test('새 양식에서 섹션을 추가하면 AI 지시 아래에 수치 출처 안내가 보이고 입력 칸 설명에 연결된다', async ({
    authenticatedPage: page,
  }) => {
    await openNewTemplateWithTextSection(page);

    const instruction = instructionField(page);
    await expect(instruction).toBeVisible();
    await expect(page.getByText(REPORT_NUMBER_SOURCE_GUIDE, { exact: true })).toBeVisible();
    // 읽기 프로그램이 지시 칸에 들어가면 기존 도움말과 수치 출처 안내를 함께 읽는다
    await expect(instruction).toHaveAccessibleDescription(BASE_DESCRIPTION);
    // 지시가 비어 있으면 주의 문구는 없다
    await expect(unsupportedHint(page)).toHaveCount(0);
  });

  test('지시에 「목표 대비 달성률」을 쓰면 찾은 낱말로 주의 문구가 뜨고, 낱말을 지우면 사라진다', async ({
    authenticatedPage: page,
  }) => {
    await openNewTemplateWithTextSection(page);
    const instruction = instructionField(page);

    await instruction.fill('목표 대비 달성률');

    const hint = unsupportedHint(page);
    await expect(hint).toHaveText(formatUnsupportedValueHint(['목표', '달성률']));
    // 문구는 WD-767 동작과 같은 말(데이터 없음·출처 미확인 경고)로 알린다
    await expect(hint).toContainText('「데이터 없음」으로 나오거나 출처 미확인 경고가 붙습니다.');
    // 입력 중 바뀐 상태를 읽기 프로그램이 정중하게(polite) 알린다
    await expect(page.getByRole('status').filter({ has: hint })).toHaveCount(1);
    // 주의 문구도 지시 칸 설명에 이어진다
    await expect(instruction).toHaveAccessibleDescription(/「목표」·「달성률」 같은 값이 있습니다/);

    await instruction.fill('이번 주 처리 건수를 요약');
    await expect(hint).toHaveCount(0);
    await expect(instruction).toHaveAccessibleDescription(BASE_DESCRIPTION);
  });

  test('그 값을 쓰지 말라는 금지 문장(「목표 수치는 쓰지 마세요.」)에는 주의 문구가 뜨지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await openNewTemplateWithTextSection(page);
    const instruction = instructionField(page);

    // 먼저 주의가 뜨는 지시로 문구가 실제로 그려지는 상태를 확인한 뒤 금지 문장으로 바꾼다 — 처음부터 없던 것과 구분한다
    await instruction.fill('목표 수치를 보여 주세요.');
    await expect(unsupportedHint(page)).toHaveText(formatUnsupportedValueHint(['목표']));

    await instruction.fill('목표 수치는 쓰지 마세요.');
    await expect(unsupportedHint(page)).toHaveCount(0);
    // 수치 출처 안내는 그대로 보이고, 지시 칸 설명에서도 주의 문구가 빠진다
    await expect(page.getByText(REPORT_NUMBER_SOURCE_GUIDE, { exact: true })).toBeVisible();
    await expect(instruction).toHaveAccessibleDescription(BASE_DESCRIPTION);
  });

  test('주의 문구가 떠 있어도 새 양식 저장(POST)이 나가고 지시가 그대로 실린다', async ({
    authenticatedPage: page,
  }) => {
    const created = createTemplate({ id: 774, name: '달성률 양식', builtin: false });
    const post = await mockApi(page, 'POST', '/api/v1/proactive/templates', created, { capture: true });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/774', created);
    await openNewTemplateWithTextSection(page);

    await page.locator('#tpl-name').fill('달성률 양식');
    await instructionField(page).fill('목표 대비 달성률을 표로 정리');
    await expect(unsupportedHint(page)).toBeVisible();

    await page.getByRole('button', { name: '만들기', exact: true }).click();

    const req = await post.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '달성률 양식',
      sections: [{ key: 'text_1', type: 'text', instruction: '목표 대비 달성률을 표로 정리' }],
    });
    await expect(page).toHaveURL(/\/ai-insights\/templates\/774$/, { timeout: 15_000 });
  });

  test('저장된 지시에 주의 낱말이 있으면 편집 화면에서 바로 보이고, 수정 저장(PUT)도 막지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const sections = [
      createTemplateSection({ key: 'outlook', type: 'text', label: '전망 섹션', instruction: '다음 달 예측과 전망을 쓰세요.' }),
    ];
    const put = await mockApi(
      page,
      'PUT',
      '/api/v1/proactive/templates/775',
      createTemplate({ id: 775, name: '지시 안내 양식', builtin: false, sections }),
      { capture: true },
    );
    await openTemplateEditor(page, 775, sections);

    await page.getByText('전망 섹션', { exact: true }).first().click();
    // 낱말은 지시에 처음 나온 순서(예측 → 전망)로 보인다
    await expect(unsupportedHint(page)).toHaveText(
      formatUnsupportedValueHint(['예측', '전망']),
    );

    await page.getByRole('button', { name: '저장', exact: true }).click();

    const req = await put.waitForRequest();
    expect(req.payload).toMatchObject({
      sections: [{ key: 'outlook', instruction: '다음 달 예측과 전망을 쓰세요.' }],
    });
  });

  test('정적(고정 텍스트) 섹션에는 지시 칸이 없으므로 수치 출처 안내·주의 문구도 없다', async ({
    authenticatedPage: page,
  }) => {
    await openTemplateEditor(page, 776, [
      // 고정 텍스트에 주의 낱말이 있어도 AI 가 채우지 않으므로 안내하지 않는다
      createTemplateSection({
        key: 'notice',
        type: 'text',
        label: '고정 안내',
        static: true,
        content: '올해 목표는 별도 문서를 참고하세요.',
        instruction: undefined,
      }),
    ]);

    await page.getByText('고정 안내', { exact: true }).first().click();
    await expect(page.getByLabel('고정 텍스트')).toHaveValue('올해 목표는 별도 문서를 참고하세요.');
    await expect(instructionField(page)).toHaveCount(0);
    await expect(page.getByText(REPORT_NUMBER_SOURCE_GUIDE, { exact: true })).toHaveCount(0);
    await expect(unsupportedHint(page)).toHaveCount(0);
  });
});
