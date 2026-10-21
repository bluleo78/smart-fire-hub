import { describe, expect, it } from 'vitest';

import { neutralizeCsvFormula, toCsvCell } from './csv';

// CSV 수식 주입(OWASP CSV Injection) 중화 규칙 — 문자열만 ' 접두, 숫자·불리언·객체는 원형 (WD-156)
describe('neutralizeCsvFormula', () => {
  it.each(['=1+1', '+1', '-1', '@SUM(A1)', '\t=1', '\r=1'])('수식 시작 문자 %j 앞에 작은따옴표를 붙인다', (v) => {
    expect(neutralizeCsvFormula(v)).toBe(`'${v}`);
  });

  it('정상 문자열·빈 문자열·중간 위험 문자·앞 공백 값은 그대로 둔다', () => {
    expect(neutralizeCsvFormula('정상값')).toBe('정상값');
    expect(neutralizeCsvFormula('a=1+1')).toBe('a=1+1');
    expect(neutralizeCsvFormula(' =1+1')).toBe(' =1+1');
    expect(neutralizeCsvFormula('')).toBe('');
  });
});

describe('toCsvCell', () => {
  it('문자열 수식 값을 중화한다', () => {
    expect(toCsvCell('=1+1')).toBe("'=1+1");
    expect(toCsvCell('@cmd')).toBe("'@cmd");
    expect(toCsvCell('-1')).toBe("'-1");
  });

  it('중화 뒤 RFC 4180 따옴표 처리 — 콤마·쌍따옴표·개행이 있으면 감싼다', () => {
    expect(toCsvCell('=HYPERLINK("http://x","클릭")')).toBe('"\'=HYPERLINK(""http://x"",""클릭"")"');
    expect(toCsvCell('\r=1')).toBe('"\'\r=1"');
    expect(toCsvCell('Carol, Jr.')).toBe('"Carol, Jr."');
    expect(toCsvCell('a\nb')).toBe('"a\nb"');
  });

  it('숫자·불리언은 원형 유지 — 음수 -5 에 따옴표가 붙지 않는다', () => {
    expect(toCsvCell(-5)).toBe('-5');
    expect(toCsvCell(-5.5)).toBe('-5.5');
    expect(toCsvCell(0)).toBe('0');
    expect(toCsvCell(true)).toBe('true');
  });

  it('null·undefined 는 빈 칸, 객체는 JSON 문자열(GeoJSON 은 { 로 시작해 원형)', () => {
    expect(toCsvCell(null)).toBe('');
    expect(toCsvCell(undefined)).toBe('');
    expect(toCsvCell({ type: 'Point', coordinates: [-126.97, 37.56] })).toBe(
      '"{""type"":""Point"",""coordinates"":[-126.97,37.56]}"',
    );
  });

  it('정상 문자열은 그대로', () => {
    expect(toCsvCell('정상값')).toBe('정상값');
  });
});
