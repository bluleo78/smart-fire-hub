import { describe, expect, it } from 'vitest';

import {
  EXTRA_HEADER_PREFIX,
  type ExtraHeaderRow,
  extraHeaderRowErrors,
  extraHeaderRowsFromConfig,
  extraHeadersToConfig,
  isExtraHeaderKey,
  isExtraHeaderValueError,
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

  it('행 오류 — 보낼 행이 상한(10)을 넘으면 11번째 행부터 이름 칸 오류가 붙고, 빈 행은 세지 않는다 (WD-804)', () => {
    const ten = Array.from({ length: MAX_EXTRA_HEADERS }, (_, i) => row(`r${i}`, `X-${i}`, 'v'));
    expect(extraHeaderRowErrors(ten)).toEqual({});
    // 이름·값이 모두 빈 행은 저장에서 빠지므로 세지 않는다 — 열 개 + 빈 행은 통과
    expect(extraHeaderRowErrors([...ten, row('blank', '', '')])).toEqual({});
    // 11번째 보낼 행(과 그 뒤)에만 오류 — 이름 칸 오류라 값 칸 오류 판정이 아니다
    const errors = extraHeaderRowErrors([...ten, row('over', 'X-Over', 'v'), row('over2', 'X-Over2', 'v')]);
    expect(errors).toEqual({
      over: '추가 헤더는 최대 10개까지 넣을 수 있습니다',
      over2: '추가 헤더는 최대 10개까지 넣을 수 있습니다',
    });
    expect(isExtraHeaderValueError(errors.over)).toBe(false);
    // 빈 행이 사이에 끼어도 보낼 행만 센다
    expect(extraHeaderRowErrors([row('b0', '', ''), ...ten, row('b1', '', ''), row('over', 'X-Over', 'v')])).toEqual({
      over: '추가 헤더는 최대 10개까지 넣을 수 있습니다',
    });
  });

  it('행 오류 — 값은 서버와 같은 ASCII 규칙(0x20~0x7E·탭)만 통과하고, 값을 비운 저장 행(가린 값 유지)은 검사하지 않으며, 「****」로 시작해도 친 값은 검사한다 (WD-780)', () => {
    expect(
      extraHeaderRowErrors([
        row('a', 'X-Ko', '키아이디'),
        row('b', 'X-Nbsp', 'key\u00a0id'),
        row('c', 'X-Ctrl', 'key\u0001id'),
        row('d', 'X-Del', 'key\u007fid'),
        row('e', 'X-Tab', 'key\tid 4567 ~!@#$%^&*()_+-={}[]|\\:;"\'<>,.?/`'),
        row('f', 'X-Trim', '  keyid  '),
        row('g', 'X-Kept', '', '****한글값'),
        row('h', 'X-Echo', '****한글값'),
      ]),
    ).toEqual({
      a: '영문·숫자·기호(ASCII)만 쓸 수 있습니다',
      b: '영문·숫자·기호(ASCII)만 쓸 수 있습니다',
      c: '영문·숫자·기호(ASCII)만 쓸 수 있습니다',
      d: '영문·숫자·기호(ASCII)만 쓸 수 있습니다',
      h: '영문·숫자·기호(ASCII)만 쓸 수 있습니다',
    });
    // 값 칸의 오류라 문구 연결·포커스도 값 칸이다
    expect(isExtraHeaderValueError('영문·숫자·기호(ASCII)만 쓸 수 있습니다')).toBe(true);
    // 이름 빠짐·겹침이 먼저다(한 행에 오류 하나)
    expect(extraHeaderRowErrors([row('a', '', '한글'), row('b', 'X-A', 'v'), row('c', 'x-a', '한글')])).toEqual({
      a: '헤더 이름을 입력하세요',
      c: '이름이 겹칩니다',
    });
  });

  it('고치거나 지운 행의 오류만 지우고, 지울 것이 없으면 같은 참조를 돌려준다', () => {
    const before = [row('a', '', 'v'), row('b', 'X-B', ''), row('c', 'X-C', '')];
    const errors = { a: '헤더 이름을 입력하세요', b: '값을 입력하세요', c: '값을 입력하세요' };
    const after = [row('a', 'X-A', 'v'), row('c', 'X-C', '')];
    expect(withoutChangedRowErrors(errors, before, after)).toEqual({ c: '값을 입력하세요' });
    expect(withoutChangedRowErrors(errors, before, before)).toBe(errors);
  });

  it('개수 초과 오류는 보낼 행이 바뀌면 다른 행을 고쳐도 지우고, 빈 행만 바뀌면 남긴다 (WD-804 리뷰)', () => {
    const ten = Array.from({ length: MAX_EXTRA_HEADERS }, (_, i) => row(`r${i}`, `X-${i}`, 'v'));
    const before = [...ten, row('over', 'X-Over', 'v'), row('blank', '', '')];
    const errors = extraHeaderRowErrors(before);
    expect(errors).toEqual({ over: '추가 헤더는 최대 10개까지 넣을 수 있습니다' });

    // 넘친 행이 아닌 다른 행(r0)을 지워 10개로 맞추면 넘친 행의 오류도 지운다 — 다시 검사하면 오류가 없다
    const removedOther = before.filter((r) => r.id !== 'r0');
    expect(withoutChangedRowErrors(errors, before, removedOther)).toEqual({});
    expect(extraHeaderRowErrors(removedOther)).toEqual({});

    // 다른 행을 비워(이름·값 모두) 보내지 않게 해도 같다
    const emptiedOther = before.map((r) => (r.id === 'r1' ? { ...r, name: '', value: '' } : r));
    expect(withoutChangedRowErrors(errors, before, emptiedOther)).toEqual({});

    // 빈 행만 지우거나 더하면 여전히 넘치므로 오류를 남긴다(같은 참조)
    const withoutBlank = before.filter((r) => r.id !== 'blank');
    expect(withoutChangedRowErrors(errors, before, withoutBlank)).toBe(errors);
    expect(withoutChangedRowErrors(errors, before, [...before, row('blank2', '', '')])).toBe(errors);

    // 개수 초과가 아닌 다른 행의 오류는 보낼 행이 바뀌어도 고칠 때까지 남긴다
    const mixed = { ...errors, r5: '값을 입력하세요' };
    expect(withoutChangedRowErrors(mixed, before, removedOther)).toEqual({ r5: '값을 입력하세요' });
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

  // WD-772 리뷰: 접두어는 서버(AuthConfigValidator.isExtraHeaderKey)·Python 실행기·AI 도구처럼 대소문자를 가리지 않는다.
  it.each([
    ['extraHeader:X-Id', true],
    ['ExtraHeader:X-Id', true],
    ['extraheader:X-Id', true],
    ['EXTRAHEADER: X-Id', true],
    ['ExtraHeader:', true],
    ['apiKey', false],
    ['extraHeader', false],
    ['extraHeaders:X-Id', false],
    [' extraHeader:X-Id', false],
    ['headerExtra:X-Id', false],
  ] as const)(
    '추가 헤더 키 판정은 접두어 대소문자를 가리지 않는다 — 접두어 비슷한 키·앞 공백은 아니다: %j → %s',
    (key, expected) => {
      expect(isExtraHeaderKey(key)).toBe(expected);
    },
  );

  it('대소문자가 다른 접두어로 저장된 추가 헤더도 행으로 꺼내고, 고치지 않고 저장하면 정식 접두어 키로 가린 값을 보낸다', () => {
    const rows = extraHeaderRowsFromConfig({ apiKey: '****abcd', 'ExtraHeader:X-Id': '****4567' });
    expect(rows.map(({ name, value, storedMasked, storedName }) => ({ name, value, storedMasked, storedName }))).toEqual([
      { name: 'X-Id', value: '', storedMasked: '****4567', storedName: 'X-Id' },
    ]);
    // 서버는 저장 키를 정식 접두어로 맞춰 가린 값을 찾으므로(normalizeExtraHeaderKey) 정식 키로 보내도 저장 원문이 유지된다
    expect(extraHeadersToConfig(rows)).toEqual({ 'extraHeader:X-Id': '****4567' });
  });

  it('메인 인증 칸 계산용 config 에서 대소문자가 다른 접두어 키도 뺀다', () => {
    expect(withoutExtraHeaders({ apiKey: '****abcd', 'ExtraHeader:X-A': '****1234', 'extraheader:X-B': '****5678' })).toEqual({
      apiKey: '****abcd',
    });
  });
});
