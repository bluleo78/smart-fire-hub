import type { Locator } from '@playwright/test';

import { expect } from './auth.fixture';

/**
 * 필수 칸 접근성 확인 도우미(WD-964) — 필수 칸이 별표 글자 대신 aria-required="true" 로 알려지는지 여러 spec 이 같은 기준으로 본다.
 */

/** 칸에 aria-required="true" 가 있고 native required 는 없다(WD-908·928) */
export async function expectAriaRequired(field: Locator) {
  await expect(field).toHaveAttribute('aria-required', 'true');
  await expect(field).not.toHaveAttribute('required', /.*/);
}

/**
 * 칸(input·textarea·Select 의 combobox 트리거 공통)이 필수로 알려지고, 접근 이름에 별표가 남지 않으며(이름이 라벨 글자와 정확히 같다),
 * 라벨 안 별표는 aria-hidden 이다. 별표의 span 을 라벨(label[for=id])에서 찾는다.
 */
export async function expectRequiredField(field: Locator, labelText: string) {
  await expectAriaRequired(field);
  await expect(field).toHaveAccessibleName(labelText);
  const id = await field.getAttribute('id');
  // id 가 없으면 label[for="null"] 을 기다리다 엉뚱하게 실패하므로, 원인(id 없음)을 먼저 뜻이 보이게 알린다(WD-964)
  expect(id, '필수 칸에 id 가 있어야 label[for] 로 별표를 찾는다').toBeTruthy();
  const star = field.page().locator(`label[for="${id}"] span[aria-hidden="true"]`);
  await expect(star).toHaveText('*');
}
