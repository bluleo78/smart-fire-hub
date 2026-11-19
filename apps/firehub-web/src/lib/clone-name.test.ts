import { describe, expect, it, vi } from 'vitest';

import {
  cloneName,
  CloneNameExhaustedError,
  createWithNumberedName,
  createWithUniqueCloneName,
  NAME_RETRY_MAX_ATTEMPTS,
  nextCloneAttempt,
  numberedCloneName,
  numberedName,
} from './clone-name';
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


/** 이름 중복 409 모양의 axios 오류 — isDuplicateName 이 인정하는 최소 형태 */
function duplicateError() {
  return { isAxiosError: true, response: { status: 409, data: { status: 409, code: 'DUPLICATE_NAME', message: 'x' } } };
}

describe('numberedCloneName (WD-824)', () => {
  it('1번째는 「 (복사본)」, 2번째부터 「 (복사본 N)」 — 서버 ResourceNameGuard.copyName 과 같은 모양', () => {
    expect(numberedCloneName('일일 리포트', '복사본', 1)).toBe('일일 리포트 (복사본)');
    expect(numberedCloneName('일일 리포트', '복사본', 2)).toBe('일일 리포트 (복사본 2)');
    expect(numberedCloneName('일일 리포트', '복사본', 13)).toBe('일일 리포트 (복사본 13)');
  });

  it('번호가 붙어 접미사가 길어져도 원래 이름 쪽을 잘라 200자를 넘지 않는다', () => {
    const first = numberedCloneName('가'.repeat(200), '복사본', 1);
    expect(first).toBe(`${'가'.repeat(194)} (복사본)`);
    const third = numberedCloneName('가'.repeat(200), '복사본', 3);
    expect(third).toBe(`${'가'.repeat(192)} (복사본 3)`);
    expect(third.length).toBe(200);
  });

  it('잘린 끝의 공백은 지운다', () => {
    const name = `${'a'.repeat(192)} bcdef`;
    expect(numberedCloneName(name, '복사본', 2)).toBe(`${'a'.repeat(192)} (복사본 2)`);
  });
});

describe('createWithUniqueCloneName (WD-824)', () => {
  it('처음 이름이 받아들여지면 한 번만 만든다', async () => {
    const create = vi.fn(async (name: string) => ({ name }));
    await expect(createWithUniqueCloneName('A', '복사본', create)).resolves.toEqual({ name: 'A (복사본)' });
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('DUPLICATE_NAME 이면 다음 번호로 다시 시도한다', async () => {
    const create = vi
      .fn<(name: string) => Promise<{ name: string }>>()
      .mockRejectedValueOnce(duplicateError())
      .mockRejectedValueOnce(duplicateError())
      .mockImplementation(async (name) => ({ name }));
    await expect(createWithUniqueCloneName('A', '복사본', create)).resolves.toEqual({ name: 'A (복사본 3)' });
    expect(create.mock.calls.map((c) => c[0])).toEqual(['A (복사본)', 'A (복사본 2)', 'A (복사본 3)']);
  });

  it('이름 중복이 아닌 오류는 다시 시도하지 않고 그대로 던진다', async () => {
    const editConflict = { isAxiosError: true, response: { status: 409, data: { code: 'EDIT_CONFLICT' } } };
    const create = vi.fn().mockRejectedValue(editConflict);
    await expect(createWithUniqueCloneName('A', '복사본', create)).rejects.toBe(editConflict);
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('시도 상한까지 계속 겹치면 소진 오류를 던진다 (WD-834)', async () => {
    const create = vi.fn().mockRejectedValue(duplicateError());
    await expect(createWithUniqueCloneName('A', '복사본', create)).rejects.toBeInstanceOf(CloneNameExhaustedError);
    expect(create).toHaveBeenCalledTimes(NAME_RETRY_MAX_ATTEMPTS);
  });

  describe('기존 이름 읽기 (WD-834)', () => {
    /** 「A (복사본)」, 「A (복사본 2)」 … 「A (복사본 20)」 */
    const taken = Array.from({ length: 20 }, (_, i) => numberedCloneName('A', '복사본', i + 1));

    function fakeCreate() {
      return vi.fn(async (name: string) => {
        if (taken.includes(name)) throw duplicateError();
        return { name };
      });
    }

    it('1회차가 겹치면 목록을 읽어 다음 번호부터 이어 시도한다', async () => {
      const create = fakeCreate();
      const loader = vi.fn(async () => taken);
      await createWithUniqueCloneName('A', '복사본', create, loader);
      expect(create.mock.calls.map((c) => c[0])).toEqual(['A (복사본)', 'A (복사본 21)']);
      expect(loader).toHaveBeenCalledTimes(1);
    });

    it('읽은 다음 번호도 겹치면 계속 올린다', async () => {
      const create = vi.fn(async (name: string) => {
        if (taken.includes(name) || name === 'A (복사본 21)') throw duplicateError();
        return { name };
      });
      await createWithUniqueCloneName('A', '복사본', create, async () => taken);
      expect(create.mock.calls.map((c) => c[0])).toEqual(['A (복사본)', 'A (복사본 21)', 'A (복사본 22)']);
    });

    it('목록 읽기가 실패하면 순차 재시도로 돌아간다', async () => {
      const create = vi
        .fn<(name: string) => Promise<{ name: string }>>()
        .mockRejectedValueOnce(duplicateError())
        .mockImplementation(async (name) => ({ name }));
      await createWithUniqueCloneName('A', '복사본', create, () => Promise.reject(new Error('목록 실패')));
      expect(create.mock.calls.map((c) => c[0])).toEqual(['A (복사본)', 'A (복사본 2)']);
    });

    it('이름 중복이 아닌 오류는 그대로 던지고 목록을 읽지 않는다', async () => {
      const boom = { isAxiosError: true, response: { status: 500, data: {} } };
      const create = vi.fn().mockRejectedValue(boom);
      const loader = vi.fn(async () => taken);
      await expect(createWithUniqueCloneName('A', '복사본', create, loader)).rejects.toBe(boom);
      expect(loader).not.toHaveBeenCalled();
    });

    it('계속 겹치면 읽은 뒤에도 총 시도 20번에서 소진 오류를 던진다', async () => {
      const create = vi.fn().mockRejectedValue(duplicateError());
      await expect(
        createWithUniqueCloneName('A', '복사본', create, async () => taken),
      ).rejects.toBeInstanceOf(CloneNameExhaustedError);
      expect(create).toHaveBeenCalledTimes(NAME_RETRY_MAX_ATTEMPTS);
    });
  });
});

describe('nextCloneAttempt (WD-834)', () => {
  it('기존 사본 중 가장 큰 번호의 다음 번호를 돌려준다', () => {
    const existing = ['A (복사본)', ...Array.from({ length: 19 }, (_, i) => `A (복사본 ${i + 2})`)];
    expect(nextCloneAttempt('A', '복사본', existing)).toBe(21);
    expect(nextCloneAttempt('A', '복사본', [])).toBe(1);
    expect(nextCloneAttempt('A', '복사본', ['A (복사본)'])).toBe(2);
  });

  it('서버처럼 대소문자·앞뒤 공백을 무시하고 센다', () => {
    // 「abc (복사본 n)」이 있으면 서버는 「ABC (복사본 n)」도 겹친다고 막는다 — 같은 번호를 건너뛰어야 한다
    const existing = ['abc (복사본)', ' abc (복사본 2) ', 'ABC (복사본 3)'];
    expect(nextCloneAttempt('ABC', '복사본', existing)).toBe(4);
  });

  it('다른 원본의 사본은 세지 않는다', () => {
    expect(nextCloneAttempt('A', '복사본', ['B (복사본 30)', 'A (복사본)'])).toBe(2);
  });

  it('잘린 긴 이름의 사본도 번호를 읽는다', () => {
    const name = '가'.repeat(200);
    const existing = [numberedCloneName(name, '복사본', 1), numberedCloneName(name, '복사본', 2)];
    expect(nextCloneAttempt(name, '복사본', existing)).toBe(3);
  });
});

describe('numberedName / createWithNumberedName (WD-824)', () => {
  it('1번째는 기본 이름 그대로, 2번째부터 「 (N)」을 붙인다', () => {
    expect(numberedName('A 양식', 1)).toBe('A 양식');
    expect(numberedName('A 양식', 2)).toBe('A 양식 (2)');
    expect(numberedName('A 양식', 10)).toBe('A 양식 (10)');
  });

  it('1회차도 200자 안으로 자른다 (WD-834)', () => {
    const result = numberedName(`AI 생성: ${'a'.repeat(195)}`, 1);
    expect(result.length).toBe(200);
    expect(result.startsWith('AI 생성: ')).toBe(true);
  });

  it('1회차도 200자 안으로 자르고 서로게이트를 가르지 않는다', () => {
    const result = numberedName(`AI 생성: ${'a'.repeat(192)}😀😀`, 1);
    expect(result.length).toBeLessThanOrEqual(200);
    const last = result.charCodeAt(result.length - 1);
    expect(last >= 0xd800 && last <= 0xdbff).toBe(false);
  });

  it('1회차는 한도 안이면 끝 공백까지 그대로 두고, 잘랐을 때만 끝 공백을 지운다', () => {
    // 자르지 않는 이름은 손대지 않는다 — cloneName·ai-agent 와 같은 규칙(WD-834 최종 리뷰)
    expect(numberedName('AI 생성: x ', 1)).toBe('AI 생성: x ');
    // 자른 자리가 공백이면 끝 공백을 지운다
    expect(numberedName(`${'a'.repeat(199)} bbb`, 1)).toBe('a'.repeat(199));
  });

  it('번호가 붙어도 200자를 넘지 않게 앞쪽을 자른다', () => {
    expect(numberedName('a'.repeat(200), 2).length).toBe(200);
    expect(numberedName('a'.repeat(200), 2).endsWith(' (2)')).toBe(true);
  });

  it('DUPLICATE_NAME 이면 다음 번호 이름으로 다시 시도한다', async () => {
    const create = vi
      .fn<(name: string) => Promise<string>>()
      .mockRejectedValueOnce(duplicateError())
      .mockImplementation(async (name) => name);
    await expect(createWithNumberedName((n) => numberedName('A 양식', n), create)).resolves.toBe('A 양식 (2)');
    expect(create.mock.calls.map((c) => c[0])).toEqual(['A 양식', 'A 양식 (2)']);
  });

  it('이름 중복이 아닌 오류는 그대로 던지고, 상한까지 겹치면 마지막 오류를 던진다', async () => {
    const other = new Error('boom');
    const failing = vi.fn().mockRejectedValue(other);
    await expect(createWithNumberedName((n) => numberedName('A', n), failing)).rejects.toBe(other);
    expect(failing).toHaveBeenCalledTimes(1);

    const dup = duplicateError();
    const always = vi.fn().mockRejectedValue(dup);
    await expect(createWithNumberedName((n) => numberedName('A', n), always)).rejects.toBe(dup);
    expect(always).toHaveBeenCalledTimes(NAME_RETRY_MAX_ATTEMPTS);
  });
});
