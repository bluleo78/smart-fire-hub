import { describe, expect, it } from 'vitest';

import {
  EXTRA_HEADER_PREFIX,
  type ExtraHeaderRow,
  extraHeaderRowErrors,
  extraHeaderRowsFromConfig,
  extraHeadersToConfig,
  keptStoredMasked,
  MAX_EXTRA_HEADERS,
  newExtraHeaderRow,
  withoutChangedRowErrors,
  withoutExtraHeaders,
} from './api-connection-extra-headers';

/** 행을 짧게 만든다 — id 는 테스트가 오류 위치를 가리키는 데 쓴다. */
function row(id: string, name: string, value: string, storedMasked?: string): ExtraHeaderRow {
  return { id, name, value, storedMasked };
}

/** API 연결 추가 인증 헤더 행 ↔ authConfig 변환·검사(WD-753). */
describe('api-connection-extra-headers', () => {
  it('접두어는 서버 AuthConfigValidator.EXTRA_HEADER_PREFIX 와 같고, 상한은 10개다', () => {
    expect(EXTRA_HEADER_PREFIX).toBe('extraHeader:');
    expect(MAX_EXTRA_HEADERS).toBe(10);
  });

  it('가린 config 에서 추가 헤더만 행으로 꺼낸다 — 값은 비우고 가린 값은 storedMasked 에 둔다', () => {
    const rows = extraHeaderRowsFromConfig({
      placement: 'header',
      headerName: 'X-NCP-APIGW-API-KEY',
      apiKey: '****abcd',
      'extraHeader:X-NCP-APIGW-API-KEY-ID': '****4567',
      'extraHeader:X-Tenant': '****nant',
    });
    expect(
      rows.map(({ name, value, storedMasked, storedName }) => ({ name, value, storedMasked, storedName })),
    ).toEqual([
      { name: 'X-NCP-APIGW-API-KEY-ID', value: '', storedMasked: '****4567', storedName: 'X-NCP-APIGW-API-KEY-ID' },
      { name: 'X-Tenant', value: '', storedMasked: '****nant', storedName: 'X-Tenant' },
    ]);
    // 행 id 는 서로 달라야 React key·오류 위치로 쓸 수 있다
    expect(new Set(rows.map((r) => r.id)).size).toBe(2);
    expect(extraHeaderRowsFromConfig(undefined)).toEqual([]);
  });

  it('새 행은 이름·값이 빈 고유 id 행이다', () => {
    const a = newExtraHeaderRow();
    const b = newExtraHeaderRow();
    expect(a).toMatchObject({ name: '', value: '' });
    expect(a.storedMasked).toBeUndefined();
    expect(a.id).not.toBe(b.id);
  });

  it('행을 접두어 키로 바꾼다 — 이름 trim, 빈 행 버림, 값을 비운 저장 행은 가린 값을 보내 저장값을 유지한다', () => {
    expect(
      extraHeadersToConfig([
        row('1', '  X-NCP-APIGW-API-KEY-ID ', 'keyid-4567'),
        row('2', '', ''),
        row('3', '   ', '   '),
        row('4', 'X-Tenant', '', '****nant'),
        row('5', 'X-Other', 'new-value', '****lder'),
      ]),
    ).toEqual({
      'extraHeader:X-NCP-APIGW-API-KEY-ID': 'keyid-4567',
      'extraHeader:X-Tenant': '****nant',
      'extraHeader:X-Other': 'new-value',
    });
    expect(extraHeadersToConfig([])).toEqual({});
  });

  it('메인 인증 칸 계산용으로 접두어 키를 뺀 새 config 를 돌려준다', () => {
    const config = { apiKey: '****abcd', 'extraHeader:X-A': '****1234' };
    expect(withoutExtraHeaders(config)).toEqual({ apiKey: '****abcd' });
    // 원본은 건드리지 않는다
    expect(config).toHaveProperty('extraHeader:X-A');
  });

  it('행 오류 — 이름 없이 값만, 새 행의 빈 값, 대소문자 무시 이름 겹침', () => {
    expect(
      extraHeaderRowErrors([
        row('a', '', 'value-only'),
        row('b', 'X-New', ''),
        row('c', 'X-New2', '   '),
        row('d', 'X-Kept', '', '****kept'),
        row('e', 'X-Dup', 'v1'),
        row('f', ' x-dup ', 'v2'),
        row('g', '', ''),
      ]),
    ).toEqual({
      a: '헤더 이름을 입력하세요',
      b: '값을 입력하세요',
      c: '값을 입력하세요',
      f: '이름이 겹칩니다',
    });
    expect(extraHeaderRowErrors([row('a', 'X-A', 'v')])).toEqual({});
  });

  it('고치거나 지운 행의 오류만 지우고, 지울 것이 없으면 같은 참조를 돌려준다', () => {
    const before = [row('a', '', 'v'), row('b', 'X-B', ''), row('c', 'X-C', '')];
    const errors = { a: '헤더 이름을 입력하세요', b: '값을 입력하세요', c: '값을 입력하세요' };
    const after = [row('a', 'X-A', 'v'), row('c', 'X-C', '')];
    expect(withoutChangedRowErrors(errors, before, after)).toEqual({ c: '값을 입력하세요' });
    expect(withoutChangedRowErrors(errors, before, before)).toBe(errors);
  });

  it('이름을 바꾸면 저장값 유지가 풀리고, 앞뒤 공백을 뗀 이름이 원래 이름과 같아지면 다시 유지한다 (WD-753 리뷰)', () => {
    const [stored] = extraHeaderRowsFromConfig({ 'extraHeader:X-Id': '****4567' });
    expect(keptStoredMasked(stored)).toBe('****4567');

    const renamed = { ...stored, name: 'X-Other' };
    expect(keptStoredMasked(renamed)).toBeUndefined();
    // 바꾼 이름은 새 헤더라 값이 필요하다 — 가린 값을 보내지 않는다
    expect(extraHeaderRowErrors([renamed])).toEqual({ [stored.id]: '값을 입력하세요' });
    expect(extraHeadersToConfig([renamed])).toEqual({ 'extraHeader:X-Other': '' });

    const restored = { ...renamed, name: '  X-Id ' };
    expect(keptStoredMasked(restored)).toBe('****4567');
    expect(extraHeaderRowErrors([restored])).toEqual({});
    expect(extraHeadersToConfig([restored])).toEqual({ 'extraHeader:X-Id': '****4567' });

    // 정리 규칙 전에 공백 든 키로 저장된 행도 정리한 이름끼리 비교한다
    const [legacy] = extraHeaderRowsFromConfig({ 'extraHeader: X-Id': '****4567' });
    expect(keptStoredMasked({ ...legacy, name: 'X-Id' })).toBe('****4567');
    // 저장값이 없는 새 행은 유지할 것이 없다
    expect(keptStoredMasked(newExtraHeaderRow())).toBeUndefined();
  });
});
