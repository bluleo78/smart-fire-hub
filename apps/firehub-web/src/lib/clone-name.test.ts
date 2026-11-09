import { describe, expect, it } from 'vitest';

import { cloneName } from './clone-name';
import { DATASET_NAME_MAX_LENGTH, datasetNameSchema } from './validations/dataset';

describe('cloneName (WD-430)', () => {
  it('짧은 이름은 그대로 두고 접미사를 붙인다', () => {
    expect(cloneName('일일 리포트', '(복사본)')).toBe('일일 리포트 (복사본)');
  });

  it('붙이면 200자를 넘는 이름은 앞부분만 남겨 정확히 200자로 맞춘다', () => {
    const result = cloneName('가'.repeat(200), '(복사본)');
    expect(Array.from(result)).toHaveLength(200);
    expect(result.endsWith(' (복사본)')).toBe(true);
    expect(result.startsWith('가'.repeat(194))).toBe(true);
  });

  it('잘린 끝의 공백은 지워 접미사 앞에 공백이 두 번 오지 않는다', () => {
    // 「 (사본)」은 5자라 원래 이름은 195자까지 남는다 — 195번째가 공백이면 잘라 낸다
    const name = `${'a'.repeat(194)} bcdef`;
    expect(cloneName(name, '(사본)')).toBe(`${'a'.repeat(194)} (사본)`);
  });

  it('이모지(서로게이트 쌍)는 UTF-16 단위로 세고 반으로 자르지 않는다 (WD-699)', () => {
    // 서버 @Size·웹 zod .max 가 모두 String.length(UTF-16)로 세므로 같은 단위로 맞춘다 — 「 (복사본)」 6단위 → 이모지 97개(194단위)
    const result = cloneName('😀'.repeat(200), '(복사본)');
    expect(result.length).toBeLessThanOrEqual(200);
    expect(result).toBe(`${'😀'.repeat(97)} (복사본)`);
  });

  it('구분 글자를 비우면 접미사를 바로 붙이고 그만큼 원래 이름을 남긴다 (WD-523 데이터셋 복제)', () => {
    expect(cloneName('매출', '_copy', 100, '')).toBe('매출_copy');
    expect(cloneName('가'.repeat(98), '_copy', 100, '')).toBe(`${'가'.repeat(95)}_copy`);
  });

  describe('데이터셋 복제 기본 이름 — UTF-16 100 단위 (WD-699)', () => {
    /** 데이터셋 복제 화면과 같은 인자로 부른다. */
    const datasetClone = (name: string) => cloneName(name, '_copy', DATASET_NAME_MAX_LENGTH, '');
    /** 잘린 결과에 짝 없는 서로게이트가 남았는지 본다. */
    const hasLoneSurrogate = (s: string) => /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(s);

    it('이모지 50개(UTF-16 100단위) 원본도 기본 이름이 이름 스키마를 통과한다', () => {
      const result = datasetClone('😀'.repeat(50));
      expect(datasetNameSchema.safeParse(result).success).toBe(true);
      expect(result).toBe(`${'😀'.repeat(47)}_copy`);
      expect(hasLoneSurrogate(result)).toBe(false);
    });

    it('자르는 자리에 이모지가 걸리면 쌍을 통째로 빼 99단위가 된다', () => {
      // 'ab' 2단위 + 이모지 → 95번째 단위가 이모지의 앞쪽(high surrogate)이라 그 이모지를 통째로 뺀다
      const result = datasetClone(`ab${'😀'.repeat(60)}`);
      expect(result).toBe(`ab${'😀'.repeat(46)}_copy`);
      expect(result.length).toBe(100 - 1);
      expect(hasLoneSurrogate(result)).toBe(false);
      expect(datasetNameSchema.safeParse(result).success).toBe(true);
    });

    it.each([95, 96, 100, 150])('원본이 UTF-16 %i단위일 때 결과는 100단위 이하이고 스키마를 통과한다', (units) => {
      const name = `${'b'.repeat(units % 2)}${'😀'.repeat(Math.floor(units / 2))}`;
      expect(name.length).toBe(units);
      const result = datasetClone(name);
      expect(result.length).toBeLessThanOrEqual(100);
      expect(result.endsWith('_copy')).toBe(true);
      expect(hasLoneSurrogate(result)).toBe(false);
      expect(datasetNameSchema.safeParse(result).success).toBe(true);
    });

    it('원본이 UTF-16 95단위면 자르지 않고 정확히 100단위다', () => {
      const name = `b${'😀'.repeat(47)}`;
      expect(datasetClone(name)).toBe(`${name}_copy`);
    });
  });
});

