import { describe, expect, it } from 'vitest';

import { eulReul, euroRo, waGwa } from './utils';

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

describe('waGwa (WD-1135)', () => {
  it('한글 음절: 받침 있으면 과, 없으면 와', () => {
    expect(waGwa('서울역')).toBe('과');
    expect(waGwa('서울시')).toBe('와');
    expect(waGwa('힣')).toBe('과'); // 한글 음절 상한(0xD7A3)
  });
  it('숫자: DIGITS_WITH_BATCHIM(0·1·3·6·7·8) 과 / 2·4·5·9 와', () => {
    for (const d of ['0', '1', '3', '6', '7', '8']) expect(waGwa(`소방서${d}`)).toBe('과');
    for (const d of ['2', '4', '5', '9']) expect(waGwa(`소방서${d}`)).toBe('와');
  });
  it('영문·기호·빈 값·한글 음절 밖(한자 등): 병기', () => {
    expect(waGwa('Acme')).toBe('와(과)');
    expect(waGwa('')).toBe('와(과)');
    expect(waGwa('漢')).toBe('와(과)');
  });
});

describe('euroRo (WD-1135)', () => {
  it('한글 음절: 받침 없음 또는 ㄹ 받침이면 로, 그 밖 받침이면 으로', () => {
    expect(euroRo('지역')).toBe('으로');
    expect(euroRo('고객사')).toBe('로');
    expect(euroRo('서울')).toBe('로'); // ㄹ 받침
    expect(euroRo('물')).toBe('로'); // ㄹ 받침
    expect(euroRo('책')).toBe('으로');
  });
  it('숫자: 1·7·8 로 / 0·3·6 으로 / 2·4·5·9 로', () => {
    for (const d of ['1', '7', '8']) expect(euroRo(`x${d}`)).toBe('로');
    for (const d of ['0', '3', '6']) expect(euroRo(`x${d}`)).toBe('으로');
    for (const d of ['2', '4', '5', '9']) expect(euroRo(`x${d}`)).toBe('로');
  });
  it('영문·기호·빈 값·한글 음절 밖(한자 등): 병기', () => {
    expect(euroRo('Cause')).toBe('(으)로');
    expect(euroRo('')).toBe('(으)로');
    expect(euroRo('漢')).toBe('(으)로');
  });
});

// WD-1135 /code-review: 값 끝 공백(입력·AI 페이로드)은 발음과 무관 — 앞 글자로 조사를 고른다.
describe('조사 헬퍼 끝 공백 무시 (WD-1135)', () => {
  it('끝 공백을 건너뛰고 마지막 글자로 고른다', () => {
    expect(waGwa('서울역 ')).toBe('과');
    expect(euroRo('30000000 ')).toBe('으로');
    expect(euroRo('지역\t')).toBe('으로');
    expect(eulReul('서울역사 ')).toBe('를');
  });
  it('공백뿐이면 빈 값처럼 병기한다', () => {
    expect(waGwa('   ')).toBe('와(과)');
    expect(euroRo('  ')).toBe('(으)로');
  });
});
