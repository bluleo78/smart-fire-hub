import { describe, expect, it } from 'vitest';

import { numberedTemplateName } from './proactive-api.js';

/** 자동 양식 이름 번호 후보(WD-824) — 웹 numberedGoalTemplateName 과 같은 모양이어야 한다. */
describe('numberedTemplateName', () => {
  it('1번째는 그대로, 2번째부터 번호를 붙인다', () => {
    expect(numberedTemplateName('A 양식', 1)).toBe('A 양식');
    expect(numberedTemplateName('A 양식', 2)).toBe('A 양식 (2)');
  });

  it('긴 이름은 이름 쪽을 잘라 「양식」 꼬리를 지킨다', () => {
    const name = numberedTemplateName(`${'가'.repeat(199)} 양식`, 2);
    expect(Array.from(name).length).toBeLessThanOrEqual(200);
    expect(name.endsWith(' 양식 (2)')).toBe(true);
  });

  it('자르는 자리가 이모지여도 쌍을 가르지 않고 코드 포인트로 센다', () => {
    const name = numberedTemplateName(`${'😀'.repeat(250)} 양식`, 2);
    expect(Array.from(name).length).toBeLessThanOrEqual(200);
    expect(name).toBe(`${'😀'.repeat(200 - ' 양식 (2)'.length)} 양식 (2)`);
  });

  it('자르는 자리의 공백은 지운다', () => {
    expect(numberedTemplateName(`${'a'.repeat(190)}${' '.repeat(9)} 양식`, 2)).toBe(`${'a'.repeat(190)} 양식 (2)`);
  });
});
