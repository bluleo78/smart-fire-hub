import { describe, expect, it } from 'vitest';

import { eulReul } from './utils';

describe('eulReul (WD-15)', () => {
  it('한글 음절: 받침 유무', () => {
    expect(eulReul('서울소방청')).toBe('을');
    expect(eulReul('한빛소방서')).toBe('를');
    expect(eulReul('힣')).toBe('을'); // 한글 음절 상한(0xD7A3)
  });
  it('숫자: 읽기 끝소리 — 0(영)·1(일)·3(삼)·6(육)·7(칠)·8(팔) 을 / 2(이)·4(사)·5(오)·9(구) 를', () => {
    for (const d of ['0', '1', '3', '6', '7', '8']) expect(eulReul(`소방서${d}`)).toBe('을');
    for (const d of ['2', '4', '5', '9']) expect(eulReul(`소방서${d}`)).toBe('를');
    expect(eulReul('10')).toBe('을'); // 십
    expect(eulReul('100')).toBe('을'); // 백
  });
  it('영문·기호·빈 값·한글 음절 밖(한자 등): 발음을 알 수 없으므로 "을(를)"', () => {
    expect(eulReul('Acme')).toBe('을(를)');
    expect(eulReul('소방청(본부)')).toBe('을(를)');
    expect(eulReul('')).toBe('을(를)');
    expect(eulReul('消防')).toBe('을(를)');
    expect(eulReul('ㄱ')).toBe('을(를)'); // 자모는 음절이 아니다
    expect(eulReul('Ａ')).toBe('을(를)'); // 전각 A(U+FF21) — 한글 음절 상한(0xD7A3) 밖
  });
});
