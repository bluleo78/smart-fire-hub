import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 읽기 전용 파이프라인 상세의 라벨 dangling 회귀 테스트 (refs #442)
 *
 * 무엇: 화면 안의 모든 `<label for>` 가 실제로 존재하는 요소를 가리키는지 단언한다.
 * 왜:  `StepConfigPanel` 의 "설명" `<Label htmlFor="pipeline-description">` 이 readOnly
 *      삼항 **바깥**에 있어 항상 렌더됐는데, 짝인 `<Textarea id="pipeline-description">`
 *      는 else 분기에만 있었다. 읽기 전용으로 열면 `label.control === null` 이 되어
 *      스크린리더가 이름을 연결하지 못하고 라벨 클릭도 무동작이었다.
 *
 *      `src/styles/design-guideline-gate.test.ts` 의 Label 게이트는 **소스의 태그 형태만**
 *      본다 — `htmlFor` 가 있으면 그것이 가리키는 id 가 런타임에 렌더되는지까지는 모른다.
 *      그 사각을 이 spec 이 메운다. 그래서 "설명" 하나가 아니라 **화면 전체의 label[for]**
 *      를 훑는다(같은 부류가 다른 곳에 생겨도 잡히도록).
 */
test('읽기 전용 파이프라인 상세에 dangling label[for] 이 없다', async ({
  authenticatedPage: page,
}) => {
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 1000,
    totalElements: 0,
    totalPages: 0,
  });
  await mockApi(page, 'GET', '/api/v1/pipelines/36', {
    id: 36,
    name: '읽기 전용 파이프라인',
    description: '설명 텍스트',
    active: true,
    steps: [],
  });

  await page.goto('/pipelines/36');

  // 읽기 전용 진입 확인 — "설명" 라벨 텍스트가 그려질 때까지 기다린다.
  await expect(page.getByText('설명', { exact: true }).first()).toBeVisible({ timeout: 15000 });

  const dangling = await page.evaluate(() =>
    [...document.querySelectorAll('label[for]')]
      .filter((l) => !document.getElementById((l as HTMLLabelElement).htmlFor))
      .map((l) => (l as HTMLLabelElement).htmlFor),
  );

  expect(dangling, `가리키는 id 가 DOM 에 없는 label[for]: ${dangling.join(', ')}`).toEqual([]);
});
