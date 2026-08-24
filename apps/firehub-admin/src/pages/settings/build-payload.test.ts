import { describe, expect, it } from 'vitest';

import { buildSettingsPayload } from './build-payload';

const ORIGINAL = {
  'ai.model': 'claude-sonnet-5',
  'ai.max_turns': '20',
  'smtp.starttls': 'true',
  'smtp.password': '',
  'ai.api_key': '',
  'ai.cli_oauth_token': '',
};

describe('buildSettingsPayload', () => {
  it('변경되지 않은 키는 보내지 않는다 — 전부 보내면 테넌트 상속이 끊긴다', () => {
    const { payload } = buildSettingsPayload({ ...ORIGINAL }, ORIGINAL, new Set());
    expect(payload).toEqual({});
  });

  it('바뀐 비-비밀 키만 담는다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'ai.max_turns': '30' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'ai.max_turns': '30' });
    expect(diff).toEqual([
      { key: 'ai.max_turns', label: '최대 턴 수', before: '20', after: '30', secret: false },
    ]);
  });

  it('비밀 키를 빈 채로 두면 페이로드에 넣지 않는다 (= 유지)', () => {
    const { payload } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).not.toHaveProperty('smtp.password');
  });

  it('비밀 키에 새 값을 넣으면 그대로 담고 diff 는 값을 숨긴다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': 'n3wPassw0rd' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'smtp.password': 'n3wPassw0rd' });
    expect(diff).toEqual([
      { key: 'smtp.password', label: '비밀번호', before: '', after: '값 변경됨', secret: true },
    ]);
  });

  it('지우기 표시된 비밀 키는 빈 문자열을 명시적으로 보낸다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '' },
      ORIGINAL,
      new Set(['smtp.password']),
    );
    expect(payload).toEqual({ 'smtp.password': '' });
    expect(diff[0]).toMatchObject({ key: 'smtp.password', after: '값 삭제됨', secret: true });
  });

  it('서버가 준 마스크 값이 폼에 들어와 있어도 그 키를 되돌려보내지 않는다', () => {
    // 진짜 위험은 "빌더가 마스크를 만든다"가 아니라(그럴 코드 경로가 없다)
    // "서버가 준 마스크가 폼 값으로 들어와 그대로 되돌아간다" 쪽이다. 그러면 서버
    // isMaskSentinel(`****` 접두 + 길이 4 또는 8)이 그 키를 드롭해 "204 성공 + 아무 일도 없음"이
    // 되거나, 길이가 어긋나면 마스크 문자열 자체가 진짜 비밀번호로 저장된다.
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '****ab12' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).not.toHaveProperty('smtp.password');
    expect(Object.values(payload).some((v) => v.startsWith('****'))).toBe(false);
    expect(diff.some((d) => d.key === 'smtp.password')).toBe(false);
  });

  it('STARTTLS 는 리터럴 문자열 true/false 로 나간다', () => {
    const off = buildSettingsPayload({ ...ORIGINAL, 'smtp.starttls': 'false' }, ORIGINAL, new Set());
    expect(off.payload['smtp.starttls']).toBe('false');
    // JSON boolean 이나 "on" 을 보내면 저장은 200 으로 성공하고 다운스트림에서 STARTTLS 가
    // 조용히 꺼진다(화면은 켜짐으로 보인다). 서버 validateValues 에 이 키의 case 가 하나도 없어
    // 클라이언트가 유일한 방어선이다.
    expect(typeof off.payload['smtp.starttls']).toBe('string');

    const on = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.starttls': 'false' },
      { ...ORIGINAL, 'smtp.starttls': 'false' },
      new Set(),
    );
    expect(on.payload).toEqual({});
  });
});
