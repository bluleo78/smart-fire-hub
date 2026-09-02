import { expect, test } from '../fixtures/auth.fixture';

/**
 * 버튼 안 아이콘-텍스트 간격 회귀 테스트 (refs #436)
 *
 * 무엇: 아이콘과 텍스트 사이의 **실제 렌더 간격(px)** 이 컨테이너 gap 값과 일치하는지 단언한다.
 * 왜:  shadcn `Button` 은 기저에 `gap-2`(8px), `size="sm"` 은 `gap-1.5`(6px)를 갖고 있다.
 *      그 안의 아이콘에 `mr-2` 를 덧붙이면 gap 과 margin 이 더해져 16px 로 벌어진다.
 *      같은 화면에서 margin 이 붙은 버튼과 안 붙은 버튼의 간격이 두 배 차이 나는데,
 *      소스만 보는 정적 게이트(`design-guideline-gate.test.ts`)로는 "margin 이 없다"까지만
 *      확인되지 실제 간격이 맞는지는 확인되지 않는다. 그래서 픽셀로 재는 이 spec 을 함께 둔다.
 *
 * 대상: 홈의 퀵 액션 버튼들 — `size="sm"`(gap-1.5 = 6px) + Lucide 아이콘 + 텍스트 조합이라
 *       #436 에서 실제로 `mr-2` 가 붙어 있던 자리다.
 */
test('버튼 안 아이콘과 텍스트 간격이 컨테이너 gap 과 일치한다', async ({
  authenticatedPage: page,
}) => {
  await page.goto('/');

  const button = page.getByRole('button', { name: '새 데이터셋' });
  await expect(button).toBeVisible({ timeout: 15000 });

  // 아이콘 오른쪽 끝 ~ 텍스트 왼쪽 끝 사이의 실제 거리를 잰다.
  const gapPx = await button.evaluate((el) => {
    const svg = el.querySelector('svg');
    if (!svg) throw new Error('버튼 안에 아이콘(svg)이 없다');
    const iconRight = svg.getBoundingClientRect().right;

    // 텍스트 노드의 실제 렌더 박스를 Range 로 잰다 (span 래퍼가 없어도 동작한다).
    const textNode = Array.from(el.childNodes).find(
      (n) => n.nodeType === Node.TEXT_NODE && n.textContent?.trim(),
    );
    if (!textNode) throw new Error('버튼 안에 텍스트 노드가 없다');
    const range = document.createRange();
    range.selectNodeContents(textNode);
    return range.getBoundingClientRect().left - iconRight;
  });

  // size="sm" 의 gap-1.5 = 6px. 서브픽셀 렌더링 여지를 1px 준다.
  // margin 이 다시 붙으면 6 + 8 = 14px 이 되어 이 단언이 깨진다.
  expect(gapPx, `아이콘-텍스트 간격이 ${gapPx}px — gap-1.5(6px)이어야 한다`).toBeGreaterThan(5);
  expect(gapPx, `아이콘-텍스트 간격이 ${gapPx}px — margin 이 덧붙어 벌어졌다`).toBeLessThan(7);
});
