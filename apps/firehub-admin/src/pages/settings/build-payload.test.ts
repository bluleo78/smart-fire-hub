import { describe, expect, it } from 'vitest';

import { buildSettingsPayload } from './build-payload';

const ORIGINAL = {
  'smtp.host': 'smtp.example.com',
  'smtp.port': '587',
  'smtp.starttls': 'true',
  'smtp.password': '',
  // "다른 비밀 키" 양성 대조군 — smtp.password 와 같은 비밀 분기를 지난다.
  'embedding.api_key': '',
};

describe('buildSettingsPayload', () => {
  it('변경되지 않은 키는 보내지 않는다 — 전부 보내면 테넌트 상속이 끊긴다', () => {
    const { payload } = buildSettingsPayload({ ...ORIGINAL }, ORIGINAL, new Set());
    expect(payload).toEqual({});
  });

  it('바뀐 비-비밀 키만 담는다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.port': '2525' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'smtp.port': '2525' });
    expect(diff).toEqual([
      { key: 'smtp.port', label: '포트', group: '이메일(SMTP)', before: '587', after: '2525', secret: false },
    ]);
  });

  it('비밀 키를 빈 채로 두면 페이로드에 넣지 않는다 (= 유지) — 양성 대조군 포함', () => {
    // smtp.password 는 비워 두고, 다른 비밀 키(embedding.api_key)에는 진짜 새 값을 넣는다.
    // 둘 다 같은 "비밀 분기" 코드를 지나므로, payload 가 그냥 텅 비어서 통과하는 게 아니라
    // "새 값을 넣은 비밀 키는 담기고 빈 채로 둔 비밀 키는 안 담긴다"를 같은 객체로 단언해야
    // 이 테스트가 실제로 무언가를 검사한다는 것이 증명된다(양성 대조군).
    const { payload } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '', 'embedding.api_key': 'n3wEmbeddingKey' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'embedding.api_key': 'n3wEmbeddingKey' });
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
      { key: 'smtp.password', label: '비밀번호', group: '이메일(SMTP)', before: '', after: '값 변경됨', secret: true },
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
    // 다른 비밀 키(embedding.api_key)에 진짜 새 값을 함께 넣어 payload 가 애초에 비어 있어서
    // 통과하는 게 아님을 보장한다 — 같은 비밀 분기 코드를 지나는 양성 대조군.
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '****ab12', 'embedding.api_key': 'n3wEmbeddingKey' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'embedding.api_key': 'n3wEmbeddingKey' });
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

  it('여러 탭의 변경은 각자의 탭 그룹을 단다 (리뷰 L1)', () => {
    const original = { ...ORIGINAL, 'embedding.model': 'bge-m3' };
    const { diff } = buildSettingsPayload(
      { ...original, 'smtp.host': 'mail.example.org', 'embedding.model': 'bge-m4' },
      original,
      new Set(),
    );
    expect(Object.fromEntries(diff.map((d) => [d.key, d.group]))).toEqual({
      'smtp.host': '이메일(SMTP)',
      'embedding.model': '임베딩',
    });
  });
});
